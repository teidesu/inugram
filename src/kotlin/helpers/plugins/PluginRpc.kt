package desu.inugram.helpers.plugins

import android.util.Log
import desu.inugram.core.plugins.ScopeMatch
import desu.inugram.core.plugins.TlNames
import desu.inugram.core.plugins.TlWire
import org.json.JSONObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.QuickAckDelegate
import org.telegram.tgnet.RequestDelegate
import org.telegram.tgnet.RequestDelegateTimestamp
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.WriteToSocketDelegate
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Wires plugin engines' `inu.interceptRpc`/`inu.invokeRpc`/`inu.onUpdate` into the stock
 * request/update pipeline. All chain orchestration + engine touches happen on
 * [Utilities.globalQueue] (same queue [PluginManager] runs engines on) so there's no locking:
 * a plugin can only ever be mid-dispatch or detached from that single thread's perspective.
 *
 * request flow: ConnectionsManager.sendRequestInternal -> maybeIntercept -> (if any interceptor
 * registered for that TL method) dispatchChain walks registered interceptors in registration
 * order; each stage either short-circuits with its own response or calls next(), which either
 * hands off to the next stage or, past the end of the chain, re-sends the (possibly rewritten)
 * request for real via a bypass-tagged re-entry into sendRequestInternal.
 *
 * The intercept chain never sees a JSON snapshot of the request/response: every value crossing
 * that boundary is a [TlWire]-encoded handle into [TlHandles], backing a lazy JS Proxy over the
 * *real* TLObject - mutations from JS land on the actual instance. Handles minted for one
 * top-level dispatch share a "scope id" ([TlHandles.newScope]) hard-invalidated in bulk once that
 * dispatch settles (see [maybeIntercept]). `inu.invokeRpc` responses and `inu.onUpdate` payloads
 * are the opposite: eager, detached JSON snapshots - nothing app-side reads those objects after
 * delivery, so live aliasing would buy nothing (see [encodeInvokeResult]).
 */
object PluginRpc {
    private const val TAG = "InuPluginRpc"

    private class Interceptor(val plugin: Plugin, val callbackId: Int)

    private class OriginalParams(
        val flags: Int,
        val datacenterId: Int,
        val connectionType: Int,
        val immediate: Boolean,
        val requestToken: Int,
        val onQuickAck: QuickAckDelegate?,
        val onWriteToSocket: WriteToSocketDelegate?,
    )

    private class PendingDispatch(
        val plugin: Plugin,
        val connectionsManager: ConnectionsManager,
        val chain: List<Interceptor>,
        val index: Int,
        val params: OriginalParams,
        val scopeId: Long,
        val finalize: (TLObject?, TLRPC.TL_error?, Long) -> Unit,
    ) {
        /** server timestamp of the deepest real send, stashed when this dispatch's next() settles */
        var responseTime = 0L
    }

    // fast-path gate checked from arbitrary stageQueue threads before paying for a globalQueue hop
    @Volatile private var hasInterceptors = false
    @Volatile private var hasUpdateListeners = false

    // published as a whole (copy-on-write) so reads off globalQueue don't need synchronization
    @Volatile private var interceptorsByMethod: Map<String, List<Interceptor>> = emptyMap()
    @Volatile private var updateListeners: List<Plugin> = emptyList()

    // touched only on globalQueue
    private var nextDispatchId = 1L
    private val pendingDispatches = HashMap<Long, PendingDispatch>()

    // requests we ourselves re-issued (chain passthrough / invokeRpc) — must not re-enter maybeIntercept
    private val bypassed: MutableSet<TLObject> = Collections.synchronizedSet(
        Collections.newSetFromMap(IdentityHashMap())
    )

    // -- plugin lifecycle --

    /** call once the plugin's engine has been created, before evaluating its source */
    fun attach(plugin: Plugin, engine: QuickJs) {
        val permissions = plugin.permissions
        val allowIntercept = permissions.has("inu.interceptRpc")
        val allowInvoke = permissions.has("inu.invokeRpc")
        val allowUpdates = permissions.has("inu.onUpdate")
        if (!allowIntercept && !allowInvoke && !allowUpdates) return
        if (allowIntercept || allowInvoke) engine.tlListener = TlHandles
        // snapshot, not read-per-invoke: a plugin's invokeRpc target must not silently jump
        // accounts when the user switches mid-session. todo: getAccounts()/per-account invoke api
        val invokeAccount = UserConfig.selectedAccount
        engine.rpcListener = object : QuickJs.RpcListener {
            override fun onRpcRegister(methods: Array<String>, callbackId: Int): String? =
                registerIntercept(plugin, methods, callbackId)

            override fun onInvokeRpc(invokeId: Long, requestWire: String): String? =
                invokeRpc(plugin, engine, invokeAccount, invokeId, requestWire)

            override fun onRpcNext(dispatchId: Long, requestWire: String): String? =
                onNext(dispatchId, requestWire)

            override fun onRpcComplete(dispatchId: Long, resultWire: String) =
                onComplete(dispatchId, resultWire)

            override fun onUpdateRegister(callbackId: Int): String? {
                // dispatchUpdate() fans out to every JS-side callback in one native call, so the
                // plugin only needs to appear once here regardless of how many onUpdate() calls it made
                if (updateListeners.none { it === plugin }) updateListeners = updateListeners + plugin
                hasUpdateListeners = true
                return null
            }
        }
        engine.installRpc(allowIntercept, allowInvoke, allowUpdates)
    }

    /**
     * call before closing the plugin's engine. drops its interceptors from the chain and
     * fails any dispatch currently *waiting on this plugin's own middleware* with a synthetic
     * error so [maybeIntercept]'s finalize contract (called exactly once) always holds.
     */
    fun detach(plugin: Plugin) {
        interceptorsByMethod = interceptorsByMethod.mapValues { (_, list) ->
            list.filter { it.plugin !== plugin }
        }.filterValues { it.isNotEmpty() }
        hasInterceptors = interceptorsByMethod.isNotEmpty()
        updateListeners = updateListeners.filter { it !== plugin }
        hasUpdateListeners = updateListeners.isNotEmpty()

        val stale = pendingDispatches.filterValues { it.plugin === plugin }.keys
        for (dispatchId in stale) {
            val pending = pendingDispatches.remove(dispatchId) ?: continue
            pending.finalize(null, syntheticError("plugin '${plugin.manifest.name}' was stopped"), completionTime(pending))
        }
    }

    // -- request interception --

    /** called from ConnectionsManager.sendRequestInternal; true if handling was taken over */
    @JvmStatic
    fun maybeIntercept(
        connectionsManager: ConnectionsManager,
        request: TLObject,
        onComplete: RequestDelegate?,
        onCompleteTimestamp: RequestDelegateTimestamp?,
        onQuickAck: QuickAckDelegate?,
        onWriteToSocket: WriteToSocketDelegate?,
        flags: Int,
        datacenterId: Int,
        connectionType: Int,
        immediate: Boolean,
        requestToken: Int,
        currentAccount: Int,
    ): Boolean {
        // bypassed check must come first: it's the only consumption point for entries added by
        // sendPassthrough/invokeRpc, and the re-entry is unconditional - gating it behind
        // hasInterceptors would leak the entry whenever interceptors vanish mid-flight
        if (bypassed.remove(request)) return false
        if (!hasInterceptors) return false
        val tlName = TlNames.classNameToTlName(request.javaClass)
        val chain = interceptorsByMethod[tlName]?.takeIf { it.isNotEmpty() } ?: return false
        val params = OriginalParams(flags, datacenterId, connectionType, immediate, requestToken, onQuickAck, onWriteToSocket)
        Utilities.globalQueue.postRunnable {
            val scopeId = TlHandles.newScope()
            dispatchChain(connectionsManager, chain, 0, request, params, scopeId) { response, error, responseTime ->
                // mirrors the stock else-if in sendRequestInternal's listen() callback
                when {
                    onComplete != null -> onComplete.run(response, error)
                    onCompleteTimestamp != null -> onCompleteTimestamp.run(response, error, responseTime)
                    response is TLRPC.Updates -> MessagesController.getInstance(currentAccount).processUpdates(response, false)
                }
                response?.freeResources()
                // hard-invalidate every handle minted for this dispatch, even ones stashed across an await
                TlHandles.releaseScope(scopeId)
            }
        }
        return true
    }

    private fun registerIntercept(plugin: Plugin, methods: Array<String>, callbackId: Int): String? {
        for (method in methods) {
            if (!plugin.permissions.allows("inu.interceptRpc", method, ScopeMatch.EXACT)) {
                return "interceptRpc: '$method' not granted"
            }
        }
        val updated = interceptorsByMethod.toMutableMap()
        for (method in methods) {
            updated[method] = (updated[method].orEmpty()) + Interceptor(plugin, callbackId)
        }
        interceptorsByMethod = updated
        hasInterceptors = true
        return null
    }

    private fun dispatchChain(
        connectionsManager: ConnectionsManager,
        chain: List<Interceptor>,
        index: Int,
        request: TLObject,
        params: OriginalParams,
        scopeId: Long,
        finalize: (TLObject?, TLRPC.TL_error?, Long) -> Unit,
    ) {
        if (index >= chain.size) {
            sendPassthrough(connectionsManager, request, params, finalize)
            return
        }
        val interceptor = chain[index]
        val engine = interceptor.plugin.engine
        if (engine == null) {
            dispatchChain(connectionsManager, chain, index + 1, request, params, scopeId, finalize)
            return
        }
        val dispatchId = nextDispatchId++
        pendingDispatches[dispatchId] = PendingDispatch(interceptor.plugin, connectionsManager, chain, index, params, scopeId, finalize)
        val requestHandle = TlHandles.mintForScope(request, scopeId)
        val method = TlNames.classNameToTlName(request.javaClass)
        engine.dispatchRpc(interceptor.callbackId, dispatchId, method, TlWire.encodeHandle(vector = false, id = requestHandle))
    }

    private fun onNext(dispatchId: Long, requestWire: String): String? {
        val pending = pendingDispatches[dispatchId] ?: return "next(): unknown dispatch"
        val nextRequest = try {
            decodeTlObject(requestWire)
        } catch (e: Exception) {
            return "next(): ${e.message}"
        }
        dispatchChain(pending.connectionsManager, pending.chain, pending.index + 1, nextRequest, pending.params, pending.scopeId) { response, error, responseTime ->
            pending.responseTime = responseTime
            val engine = pending.plugin.engine
            if (engine == null) return@dispatchChain
            engine.completeNext(dispatchId, encodeChainResult(response, error, pending.scopeId))
        }
        return null
    }

    private fun onComplete(dispatchId: Long, resultWire: String) {
        val pending = pendingDispatches.remove(dispatchId) ?: return
        val time = completionTime(pending)
        val response = try {
            decodeTlValueOrError(resultWire)
        } catch (e: TlResultError) {
            pending.finalize(null, e.error, time)
            return
        } catch (e: Exception) {
            pending.finalize(null, syntheticError("bad middleware response: ${e.message}"), time)
            return
        }
        pending.finalize(response, null, time)
    }

    /** the real passthrough's server timestamp when one happened, else "now" for pure short-circuits */
    private fun completionTime(pending: PendingDispatch): Long =
        if (pending.responseTime != 0L) pending.responseTime else pending.connectionsManager.currentTimeMillis

    private fun sendPassthrough(
        connectionsManager: ConnectionsManager,
        request: TLObject,
        params: OriginalParams,
        finalize: (TLObject?, TLRPC.TL_error?, Long) -> Unit,
    ) {
        bypassed.add(request)
        Utilities.stageQueue.postRunnable {
            connectionsManager.sendRequestInternal(
                request,
                null,
                RequestDelegateTimestamp { response, error, responseTime ->
                    Utilities.globalQueue.postRunnable { finalize(response, error, responseTime) }
                },
                params.onQuickAck,
                params.onWriteToSocket,
                params.flags,
                params.datacenterId,
                params.connectionType,
                params.immediate,
                params.requestToken,
            )
        }
    }

    // -- inu.invokeRpc --

    private fun invokeRpc(plugin: Plugin, engine: QuickJs, account: Int, invokeId: Long, requestWire: String): String? {
        val request = try {
            decodeTlObject(requestWire)
        } catch (e: Exception) {
            return "invokeRpc: ${e.message}"
        }
        val tlName = TlNames.classNameToTlName(request.javaClass)
        if (!plugin.permissions.allows("inu.invokeRpc", tlName, ScopeMatch.EXACT)) {
            return "invokeRpc: '$tlName' not granted"
        }
        bypassed.add(request)
        val connectionsManager = ConnectionsManager.getInstance(account)
        connectionsManager.sendRequest(request) { response, error ->
            Utilities.globalQueue.postRunnable {
                // identity check, not just null: after a reload plugin.engine is a *new* engine
                // whose invoke ids restart - a stale response must not settle its pending invokes
                if (plugin.engine === engine) {
                    engine.resolveInvoke(invokeId, encodeInvokeResult(response, error))
                } else {
                    response?.freeResources()
                }
            }
        }
        return null
    }

    // -- updates fan-out --

    /** called (on Utilities.stageQueue) from the top of MessagesController.processUpdates */
    @JvmStatic
    fun onUpdates(updates: TLRPC.Updates, account: Int) {
        if (!hasUpdateListeners) return
        Utilities.globalQueue.postRunnable {
            for (update in unpackUpdates(updates)) dispatchUpdate(update)
        }
    }

    private fun unpackUpdates(updates: TLRPC.Updates): List<TLObject> = when (updates) {
        is TLRPC.TL_updateShort -> listOfNotNull(updates.update)
        is TLRPC.TL_updates -> updates.updates ?: emptyList()
        is TLRPC.TL_updatesCombined -> updates.updates ?: emptyList()
        is TLRPC.TL_updateShortMessage,
        is TLRPC.TL_updateShortChatMessage,
        is TLRPC.TL_updateShortSentMessage -> listOf(updates)
        else -> emptyList()
    }

    private fun dispatchUpdate(update: TLObject) {
        val tlName = TlNames.classNameToTlName(update.javaClass)
        var json: String? = null
        for (plugin in updateListeners) {
            val engine = plugin.engine ?: continue
            if (!plugin.permissions.allows("inu.onUpdate", tlName, ScopeMatch.EXACT)) continue
            if (json == null) {
                json = try {
                    TlJson.toJson(update).toString()
                } catch (e: Exception) {
                    Log.e(TAG, "failed to serialize update '$tlName'", e)
                    return
                }
            }
            engine.dispatchUpdate(json)
        }
    }

    // -- request/response <-> TlWire --

    /** thrown by [decodeTlValueOrError] for an `E`/`R`-tagged wire value; carries the decoded [error] */
    private class TlResultError(val error: TLRPC.TL_error) : Exception("${error.code}: ${error.text}")

    /** decodes a wire value that must be a live TL object (request side: `H` handle or `J` construct) */
    private fun decodeTlObject(wire: String): TLObject = when (val decoded = TlWire.decode(wire)) {
        is TlWire.Value.Handle -> TlHandles.resolveTlObject(decoded.id)
            ?: throw IllegalStateException(TlWire.HANDLE_EXPIRED_MESSAGE)
        is TlWire.Value.Json -> TlJson.fromJson(JSONObject(decoded.json))
        else -> throw IllegalArgumentException("expected a TL object")
    }

    /** decodes a result wire value (`H`/`J` on success, `N` = stock's (null, null) completion, throws [TlResultError] for `E`/`R`) */
    private fun decodeTlValueOrError(wire: String): TLObject? = when (val decoded = TlWire.decode(wire)) {
        is TlWire.Value.Null -> null
        is TlWire.Value.Error -> throw TlResultError(syntheticError(decoded.message))
        is TlWire.Value.RpcError -> throw TlResultError(TLRPC.TL_error().apply { code = decoded.code; text = decoded.text })
        is TlWire.Value.Handle -> TlHandles.resolveTlObject(decoded.id)
            ?: throw TlResultError(syntheticError(TlWire.HANDLE_EXPIRED_MESSAGE))
        is TlWire.Value.Json -> TlJson.fromJson(JSONObject(decoded.json))
        else -> throw IllegalArgumentException("unsupported result payload")
    }

    private fun encodeChainResult(response: TLObject?, error: TLRPC.TL_error?, scopeId: Long): String {
        if (error != null) return TlWire.encodeRpcError(error.code, error.text ?: "")
        if (response == null) return TlWire.encodeNull()
        return TlWire.encodeHandle(vector = false, id = TlHandles.mintForScope(response, scopeId))
    }

    /**
     * unlike the intercept chain, an invokeRpc response resolves as a detached plain snapshot,
     * not a live view: nothing app-side ever reads the response object after delivery, so write-
     * through buys nothing - and a snapshot needs no handle lifetime at all. the backing TLObject
     * is freed right here.
     */
    private fun encodeInvokeResult(response: TLObject?, error: TLRPC.TL_error?): String {
        if (error != null) return TlWire.encodeRpcError(error.code, error.text ?: "")
        if (response == null) return TlWire.encodeNull()
        val wire = try {
            TlWire.encodeJson(TlJson.toJson(response).toString())
        } catch (e: Exception) {
            TlWire.encodeError("invokeRpc: failed to serialize response: ${e.message}")
        }
        response.freeResources()
        return wire
    }

    private fun syntheticError(message: String): TLRPC.TL_error =
        TLRPC.TL_error().apply { code = -1000; text = message }
}
