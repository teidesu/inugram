package desu.inugram.helpers.plugins.tg

import android.os.SystemClock
import android.util.Log
import desu.inugram.core.plugins.BoundedIdentitySet
import desu.inugram.core.plugins.BoundedLru
import desu.inugram.core.plugins.ScopeMatch
import desu.inugram.core.plugins.TakeoverMethods
import desu.inugram.core.plugins.TlCtorIds
import desu.inugram.core.plugins.TlNames
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginDispatch
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlHandles
import desu.inugram.helpers.plugins.tl.TlJson
import java.util.Collections
import java.util.IdentityHashMap
import org.json.JSONObject
import org.telegram.messenger.KeepAliveJob
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
import org.telegram.tgnet.tl.TL_update

/**
 * Wires `inu.interceptRpc`/`inu.invokeRpc`/`inu.onUpdate` into the stock request/update pipeline.
 *
 * All chain orchestration happens on [Utilities.globalQueue], so there is no locking. Stronger than
 * that: an engine is entered **only from a globalQueue runnable, never from inside a JNI upcall** -
 * `Context::with` takes the runtime's `RefCell`, so re-entering the same engine from a callback it
 * is running is a `BorrowMutError` panicking out of an `extern "system"` fn, i.e. a process abort.
 * One plugin reaches that alone by registering twice for a method. Hence [onNext]/[onComplete] post.
 *
 * The app is always answered from [Utilities.stageQueue], where stock answers it from: the
 * delegate-less `Updates` tail runs `processUpdates`, which mutates pts/seq from that queue with no
 * locking.
 *
 * The three TL sources differ in lifetime and mutability: writable and scope-invalidated in bulk
 * for an intercept chain, writable and plugin-lifetime for an `invokeRpc` result (nobody app-side
 * reads it, so `disableFree` moves the free to the table), read-only and plugin-lifetime for an
 * `onUpdate` payload.
 */
object PluginRpc {
    private class Interceptor(val plugin: Plugin, val callbackId: Int)

    /**
     * the type list is per registration, so the fan-out can mint a handle only for a plugin some
     * registration of which named that constructor.
     *
     * [grantScope] is what it was gated on - the constructor for the raw form, the demuxed event
     * name otherwise. `common.d.ts` keeps the two vocabularies apart, so it is not derivable.
     */
    private class UpdateReg(
        val plugin: Plugin,
        val callbackId: Int,
        val types: Set<String>,
        val grantScope: String?,
    )

    private class UpdateListener(val plugin: Plugin) {
        val grantScopes = HashSet<String>()
    }

    /** the scopes here are the constructors, unlike `onUpdate`'s */
    private class UpdateInterceptor(val plugin: Plugin, val callbackId: Int, val types: Set<String>)

    /**
     * [synthesized] marks the two compressed short forms, where [update] is what
     * [normalizeShortMessage] built rather than anything the app will apply. [snapshot] is the
     * pre-walk state [deliverable] compares against; null means it failed, and a rewrite is assumed.
     */
    private class UpdateUnit(
        val update: TLObject,
        val tlName: String,
        val chain: List<UpdateInterceptor>,
        val synthesized: Boolean,
        val snapshot: String?,
    )

    /**
     * The app is blocked on this, so it carries [UPDATE_BUDGET_MS] shared by every stage of every
     * update in the batch: updates arrive in bursts, and a per-update budget would let one batch
     * hold the stream for its size times the budget.
     */
    private class UpdateBatch(
        val controller: MessagesController,
        val updates: TLRPC.Updates,
        val account: Int,
        val fromQueue: Boolean,
        val units: List<UpdateUnit>,
    ) {
        var scopeId = 0L
        var index = 0
        var stage = 0
        var dispatchId = 0L

        var stagePlugin: Plugin? = null
        var timer: Runnable? = null
        var expired = false
        var finished = false

        val dropped: MutableSet<TLObject> = Collections.newSetFromMap(IdentityHashMap())
    }

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
        val tl: TlHandles,
        val connectionsManager: ConnectionsManager,
        val chain: List<Interceptor>,
        val index: Int,
        val params: OriginalParams,
        val scopeId: Long,
        val method: String,
        val account: Int,
        val finalize: (TLObject?, TLRPC.TL_error?, Long) -> Unit,
    ) {
        var responseTime = 0L
    }

    /**
     * one top-level dispatch's deadline, shared by its whole chain. [remaining] is suspended while
     * the request is really in flight, so a slow server is not charged to the plugins.
     *
     * [SystemClock.uptimeMillis] because that is what `Handler.postDelayed` counts in: it does not
     * advance in deep sleep, and any other clock would drift from the armed timer.
     */
    private class ChainBudget(
        val scopeId: Long,
        val connectionsManager: ConnectionsManager,
        val chain: List<Interceptor>,
        val method: String,
        val request: TLObject,
        val finalize: (TLObject?, TLRPC.TL_error?, Long) -> Unit,
    ) {
        val stages = ArrayList<Long>()
        var remaining = CHAIN_BUDGET_MS
        var startedAt = 0L
        var timer: Runnable? = null

        var passthrough: PassthroughResult? = null

        var guid = 0

        var sent: SentRequest? = null
    }

    /**
     * stock frees a request's `NativeByteBuffer` fields the moment it has serialized them
     * (`upload.saveFilePart`'s `bytes`, the secret-chat sends), gutting the writable view a stage
     * parked in `await next()` still holds - so the free is suppressed for the send and done once
     * the chain retires.
     *
     * [leased] outlives the first send: on CONNECTION_NOT_INITED stock re-sends this very instance
     * with a fresh token and without invoking the delegate, so it is dropped only by what proves no
     * further send can follow - the delegate answering, or a cancel taking it away from native.
     */
    private class SentRequest(val request: TLObject) {
        var leased = true

        @Volatile var cancelled = false

        var reachedNative = false
    }

    private class PassthroughResult(val response: TLObject?, val error: TLRPC.TL_error?, val time: Long)

    private const val TAG = "InuPluginRpc"
    private const val CHAIN_BUDGET_MS = 10_000L

    /** a tenth of a send's: the app's whole arriving batch is parked behind this */
    private const val UPDATE_BUDGET_MS = 2_000L
    private const val DISPATCH_MEMORY = 2048
    private const val GUID_MEMORY = 512
    private const val SYNTHETIC_CODE = -1000
    private const val TIMEOUT_TEXT = "INTERCEPTOR_TIMEOUT"
    private const val ABANDONED_TEXT = "INTERCEPTOR_ABANDONED"
    private val TIMEOUT_WIRE = PluginWire.encodeRpcError(SYNTHETIC_CODE, TIMEOUT_TEXT)
    private val ABANDONED_WIRE = PluginWire.encodeRpcError(SYNTHETIC_CODE, ABANDONED_TEXT)

    // fast-path gate read from arbitrary stageQueue threads before paying for a globalQueue hop
    @Volatile private var hasInterceptors = false
    @Volatile private var hasUpdateListeners = false
    @Volatile private var hasUpdateInterceptors = false

    // published copy-on-write, so reads off globalQueue need no synchronization
    @Volatile private var interceptorsByMethod: Map<String, List<Interceptor>> = emptyMap()
    @Volatile private var updateRegs: List<UpdateReg> = emptyList()
    @Volatile private var updateListenersByType: Map<String, List<UpdateListener>> = emptyMap()
    @Volatile private var updateInterceptRegs: List<UpdateInterceptor> = emptyList()
    @Volatile private var updateInterceptorsByType: Map<String, List<UpdateInterceptor>> = emptyMap()

    private var nextDispatchId = 1L
    private val pendingDispatches = HashMap<Long, PendingDispatch>()
    private val chains = HashMap<Long, ChainBudget>()
    private val tlTables = HashMap<Plugin, TlHandles>()

    /** `interceptDeserialize`'s middleware form mints into it too, so every view carries one [TlFilter] policy */
    internal fun tableFor(plugin: Plugin): TlHandles? = tlTables[plugin]

    // `plugin.engine` is *not* this signal: it is cleared only after `engine.close()` and `tlTables`
    // only after the abandon loops, so between the two a chain restarted by one of those abandons
    // would read a leaving plugin as live and dispatch into an engine already told it is unloading
    private val detaching = Collections.newSetFromMap(IdentityHashMap<Plugin, Boolean>())

    private fun attachedTable(plugin: Plugin): TlHandles? =
        if (plugin in detaching) null else tlTables[plugin]
    // [tokenKey] -> scope id, so an app-side cancel can find the chain still walking for that request
    private val chainsByToken = HashMap<Long, Long>()
    // [tokenKey] -> guid, for binds landing before there was a chain to hang them on: the app binds
    // synchronously, usually before the request reached `sendRequestInternal`. bounded - an entry
    // only has to outlive the walk to the passthrough, which the chain budget caps at 10 s
    private val guidByToken = BoundedLru<Long, Int>(GUID_MEMORY)
    // scope id -> the passthrough response stock's free was suppressed for; that chain's finalize is the only consumer
    private val ownedResponses = HashMap<Long, TLObject>()
    // already fanned out, newest last, so a re-fed batch doesn't deliver twice. identity, because no TLRPC class overrides hashCode
    private val dispatchedUpdates = BoundedIdentitySet<TLObject>(DISPATCH_MEMORY)

    // one fifo per account: the head is the batch being walked, and everything behind it waits whether or not an interceptor claimed it
    private val updateQueues = HashMap<Int, ArrayDeque<UpdateBatch>>()
    // the batches this re-fed, so [onUpdates] lets its own hand-back through
    private val takenOver = Collections.newSetFromMap(IdentityHashMap<TLRPC.Updates, Boolean>())
    // same bounded identity ring as [dispatchedUpdates]: stock re-feeds a parked batch around the very objects a first pass ran over
    private val interceptedUpdates = BoundedIdentitySet<TLObject>(DISPATCH_MEMORY)
    private val pendingUpdateDispatches = HashMap<Long, UpdateBatch>()

    // requests we re-issued (chain passthrough / invokeRpc), which must not re-enter maybeIntercept.
    // a *lease* rather than something the first send consumes: on CONNECTION_NOT_INITED stock
    // re-sends the very object with a fresh token and no delegate call, so a lease ending at the
    // first send would let the retry start a second chain over a request the first still holds -
    // every middleware twice, and the nested finalize freeing the response the outer one will walk.
    // counted, because one instance can be leased twice
    private val bypassed = IdentityHashMap<TLObject, Int>()

    fun attach(plugin: Plugin, engine: QuickJs) {
        val tl = TlHandles(TlFilter.policyFor(plugin.permissions))
        tlTables[plugin] = tl
        engine.tlListener = tl
        // snapshot: the account-less `inu.invokeRpc` names no account, and a plugin's requests must not jump slots on a switch
        val invokeAccount = UserConfig.selectedAccount
        engine.rpcListener = object : QuickJs.RpcListener {
            override fun onRpcRegister(methods: Array<String>, callbackId: Int, scope: String): String? =
                registerIntercept(plugin, methods, callbackId, scope)

            override fun onRpcUnregister(callbackId: Int) =
                unregisterIntercept(plugin, callbackId)

            override fun onInvokeRpc(invokeId: Long, slot: Int, requestWire: String): String? =
                invokeRpc(plugin, engine, tl, slot, invokeAccount, invokeId, requestWire)

            override fun onRpcNext(dispatchId: Long, requestWire: String): String? =
                onNext(dispatchId, requestWire)

            override fun onRpcComplete(dispatchId: Long, resultWire: String) =
                onComplete(dispatchId, resultWire)

            override fun onUpdateRegister(callbackId: Int, types: Array<String>, scope: String): String? =
                registerUpdates(plugin, callbackId, types, scope)

            override fun onUpdateUnregister(callbackId: Int) =
                unregisterUpdates(plugin, callbackId)

            override fun onInterceptUpdateRegister(callbackId: Int, types: Array<String>): String? =
                registerInterceptUpdates(plugin, callbackId, types)

            override fun onInterceptUpdateUnregister(callbackId: Int) =
                unregisterInterceptUpdates(plugin, callbackId)

            override fun onUpdateVerdict(dispatchId: Long, deliver: Boolean) =
                onUpdateStageSettled(dispatchId, deliver)
        }
        // `inu.interceptDeserialize` installs from inside `installRpc`, so its listener has to precede it
        PluginDeserialize.attach(plugin, engine)
        engine.installRpc()
    }

    /**
     * drops the plugin's handle table and its interceptors, and fails any dispatch waiting on its
     * own middleware so [maybeIntercept]'s finalize-exactly-once contract still holds.
     */
    fun detach(plugin: Plugin) {
        detaching.add(plugin)
        try {
            detachInner(plugin)
        } finally {
            detaching.remove(plugin)
        }
    }

    private fun detachInner(plugin: Plugin) {
        publishInterceptors(
            interceptorsByMethod
                .mapValues { (_, list) -> list.filter { it.plugin !== plugin } }
                .filterValues { it.isNotEmpty() }
        )
        publishUpdateRegs(updateRegs.filter { it.plugin !== plugin })
        publishUpdateInterceptors(updateInterceptRegs.filter { it.plugin !== plugin })

        // ascending dispatch id is chain order, so each chain's shallowest stage takes the ones below it down
        val stale = pendingDispatches.filterValues { it.plugin === plugin }.keys.sorted()
        for (dispatchId in stale) {
            val pending = pendingDispatches.remove(dispatchId) ?: continue
            abandonBelow(dispatchId, pending, ABANDONED_WIRE)
            pending.finalize(null, syntheticError("plugin '${plugin.manifest.name}' was stopped"), completionTime(pending))
        }
        // an update batch parked here is delivered rather than failed: dropping it desyncs pts
        for (dispatchId in pendingUpdateDispatches.filterValues { it.stagePlugin === plugin }.keys.toList()) {
            val batch = pendingUpdateDispatches.remove(dispatchId) ?: continue
            plugin.engine?.abandonUpdateDispatch(dispatchId)
            advanceBatch(batch)
        }
        // last: the abandons above reject inside this plugin too, and a continuation touching its request view must not find every field expired
        tlTables.remove(plugin)?.releaseAll()
    }

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
        // a leased request is ours however the entry got here, including stock's own re-send
        if (isBypassed(request)) return false
        if (!hasInterceptors) return false
        val tlName = TlNames.classNameToTlName(request.javaClass)
        val chain = interceptorsByMethod[tlName]?.takeIf { it.isNotEmpty() } ?: return false
        val params = OriginalParams(flags, datacenterId, connectionType, immediate, requestToken, onQuickAck, onWriteToSocket)
        val requestKey = tokenKey(currentAccount, requestToken)
        Utilities.globalQueue.postRunnable {
            val scopeId = TlHandles.newScope()
            val finalize: (TLObject?, TLRPC.TL_error?, Long) -> Unit = { response, error, responseTime ->
                // earns its keep when the *first* stage's plugin is stopped, the ones below it still running
                collapseChain(scopeId, ABANDONED_WIRE)
                chainsByToken.remove(requestKey)
                val owned = ownedResponses.remove(scopeId)
                // stageQueue, where processUpdates() is documented to run and mutates pts/seq without locking
                Utilities.stageQueue.postRunnable {
                    // mirrors the stock else-if in sendRequestInternal's listen() callback
                    when {
                        onComplete != null -> onComplete.run(response, error)
                        onCompleteTimestamp != null -> onCompleteTimestamp.run(response, error, responseTime)
                        response is TLRPC.Updates -> {
                            KeepAliveJob.finishJob()
                            MessagesController.getInstance(currentAccount).processUpdates(response, false)
                        }
                    }
                    freeChainResponse(response, owned)
                }
            }
            chainsByToken[requestKey] = scopeId
            // armed after the queue hop, so an app-side backlog isn't charged to the plugins
            armChain(scopeId, connectionsManager, chain, tlName, request, requestKey, finalize)
            dispatchChain(connectionsManager, chain, 0, request, params, scopeId, currentAccount, finalize)
        }
        return true
    }

    /**
     * the token only reaches native once the passthrough sends it, so stock's `cancelRequest` finds
     * nothing and, with the budget suspended, nothing would ever settle the chain. The app is
     * deliberately not answered - stock drops a cancelled request's delegate too.
     *
     * Called from inside `cancelRequest`'s own stageQueue runnable, so it is ordered behind the
     * `sendRequestInternal` the app's `sendRequest` posted there: a cancel issued the moment the
     * token was handed over would otherwise find no chain armed, and the passthrough would then
     * send a request the app cancelled.
     */
    @JvmStatic
    fun onRequestCancelled(account: Int, requestToken: Int, notifyServer: Boolean, onCancelled: Runnable?) {
        if (!hasInterceptors) return
        Utilities.globalQueue.postRunnable {
            cancelChain(tokenKey(account, requestToken), notifyServer, onCancelled)
        }
    }

    /** native only knows the requests whose chain already passed through; the ones still walking are cancelled here */
    @JvmStatic
    fun onRequestsCancelledForGuid(account: Int, guid: Int) {
        if (!hasInterceptors || guid == 0) return
        Utilities.globalQueue.postRunnable {
            val account64 = account.toLong()
            val keys = chainsByToken.keys.filter { key ->
                key ushr 32 == account64 && chains[chainsByToken[key]]?.guid == guid
            }
            // notifying is what native's own guid cancel does (cancelRequestInternal(_, _, true, ...))
            for (key in keys) cancelChain(key, true, null)
        }
    }

    /** stock binds the guid straight to native, which for an intercepted request is a token native has never seen */
    @JvmStatic
    fun onRequestBoundToGuid(account: Int, requestToken: Int, guid: Int) {
        if (!hasInterceptors || guid == 0) return
        val key = tokenKey(account, requestToken)
        Utilities.globalQueue.postRunnable {
            val budget = chainsByToken[key]?.let { chains[it] }
            if (budget == null) {
                rememberGuid(key, guid)
                return@postRunnable
            }
            budget.guid = guid
            // the passthrough raced it; re-issue behind the send, on the queue it was posted to
            if (budget.sent != null) {
                Utilities.stageQueue.postRunnable {
                    ConnectionsManager.native_bindRequestToGuid(account, requestToken, guid)
                }
            }
        }
    }

    /**
     * answers for the cancellation callback stock could not place: `listenCancel` hangs it off the
     * native callbacks the passthrough registers, so a request that never got that far leaves the
     * caller waiting forever (`FileLoadOperation` counts them down before a download is cancelled).
     * Which of the three cases this is can only be decided on stageQueue.
     */
    private fun cancelChain(key: Long, notifyServer: Boolean, onCancelled: Runnable?) {
        val scopeId = chainsByToken.remove(key) ?: return
        val budget = collapseChain(scopeId, ABANDONED_WIRE)
        releaseUnowned(ownedResponses.remove(scopeId))
        val sent = budget?.sent
        if (sent == null) {
            // no send was even posted, and the collapse means none can follow
            onCancelled?.let { Utilities.stageQueue.postRunnable(it) }
            return
        }
        // native has the request or is about to be told not to want it; the delegate that would have ended the lease is not coming
        endBypassLease(sent)
        val connectionsManager = budget.connectionsManager
        val requestToken = key.toInt()
        Utilities.stageQueue.postRunnable {
            if (!sent.reachedNative) {
                onCancelled?.run()
                return@postRunnable
            }
            // the send won the race with stock's own cancel. re-issued now the token is real -
            // redundant if the send had long happened, but cancelling a token native holds is
            // idempotent and the two are indistinguishable from here
            connectionsManager.cancelRequest(requestToken, notifyServer, onCancelled)
        }
    }

    private fun tokenKey(account: Int, requestToken: Int): Long =
        (account.toLong() shl 32) or (requestToken.toLong() and 0xffffffffL)

    private fun rememberGuid(key: Long, guid: Int) = guidByToken.put(key, guid)

    private fun takeGuid(key: Long): Int = guidByToken.remove(key) ?: 0

    /**
     * [scope] empty is the raw form, where each method is its own grant scope; otherwise it is the
     * api the engine gated on and [methods] is that api's fixed list. The takeover refusal applies
     * to both: it is a property of the method, not of how it was reached.
     */
    private fun registerIntercept(plugin: Plugin, methods: Array<String>, callbackId: Int, scope: String): String? {
        for (method in methods) {
            takeoverRefusal(plugin, method)?.let { return it }
        }
        if (scope.isNotEmpty()) {
            if (!plugin.permissions.has(scope)) {
                return PluginWire.encodeNotGranted(scope)
            }
        } else for (method in methods) {
            if (!plugin.permissions.allows("interceptRpc", method, ScopeMatch.EXACT)) {
                return PluginWire.encodeNotGranted("interceptRpc", method)
            }
        }
        val updated = interceptorsByMethod.toMutableMap()
        // one registration is one stage however often its list names a method - a repeat would run
        // the middleware twice per request off a single `next()`, against one budget, and
        // `releaseScope` twice. Same reason the update registrations take `types.toSet()`
        for (method in methods.toSet()) {
            updated[method] = (updated[method].orEmpty()) + Interceptor(plugin, callbackId)
        }
        publishInterceptors(updated)
        return null
    }

    private fun unregisterIntercept(plugin: Plugin, callbackId: Int) {
        publishInterceptors(
            interceptorsByMethod
                .mapValues { (_, list) -> list.filter { it.plugin !== plugin || it.callbackId != callbackId } }
                .filterValues { it.isNotEmpty() }
        )
    }

    /**
     * The types are checked against the constructor table as well as the grants: one no layer
     * defines can only be a typo, and one that silently never fires is an hour of debugging.
     *
     * [scope] empty is the raw form, where each constructor is its own grant scope; otherwise it is
     * the demuxed event name and [types] is that event's fixed list.
     */
    private fun registerUpdates(plugin: Plugin, callbackId: Int, types: Array<String>, scope: String): String? {
        for (type in types) {
            if (type !in TlCtorIds.updateNames) {
                return PluginWire.encodePluginError("unknown-constructor", "onUpdate: unknown update type '$type'")
            }
        }
        val grantScope = scope.ifEmpty { null }
        for (target in grantScope?.let { listOf(it) } ?: types.toList()) {
            if (!plugin.permissions.allows("onUpdate", target, ScopeMatch.EXACT)) {
                return PluginWire.encodeNotGranted("onUpdate", target)
            }
        }
        publishUpdateRegs(updateRegs + UpdateReg(plugin, callbackId, types.toSet(), grantScope))
        return null
    }

    private fun unregisterUpdates(plugin: Plugin, callbackId: Int) {
        publishUpdateRegs(updateRegs.filter { it.plugin !== plugin || it.callbackId != callbackId })
    }

    /** every `interceptUpdate` scope is a constructor name - there is no demuxed form over it */
    private fun registerInterceptUpdates(plugin: Plugin, callbackId: Int, types: Array<String>): String? {
        for (type in types) {
            if (type !in TlCtorIds.updateNames) {
                return PluginWire.encodePluginError("unknown-constructor", "interceptUpdate: unknown update type '$type'")
            }
            if (!plugin.permissions.allows("interceptUpdate", type, ScopeMatch.EXACT)) {
                return PluginWire.encodeNotGranted("interceptUpdate", type)
            }
        }
        publishUpdateInterceptors(updateInterceptRegs + UpdateInterceptor(plugin, callbackId, types.toSet()))
        return null
    }

    private fun unregisterInterceptUpdates(plugin: Plugin, callbackId: Int) {
        publishUpdateInterceptors(
            updateInterceptRegs.filter { it.plugin !== plugin || it.callbackId != callbackId }
        )
    }

    fun refreshChainOrder() {
        Utilities.globalQueue.postRunnable {
            publishInterceptors(interceptorsByMethod)
            publishUpdateRegs(updateRegs)
            publishUpdateInterceptors(updateInterceptRegs)
        }
    }

    /**
     * chain order is derived here on every publish rather than being registration order, since
     * `common.d.ts` promises the order the user drags. The sort is stable, so a plugin registering
     * twice keeps its own stages in registration order.
     */
    private fun publishInterceptors(updated: Map<String, List<Interceptor>>) {
        val order = findPluginOrder()
        interceptorsByMethod = updated.mapValues { (_, list) ->
            list.sortedBy { order[it.plugin] ?: Int.MAX_VALUE }
        }
        hasInterceptors = interceptorsByMethod.isNotEmpty()
    }

    private fun publishUpdateRegs(updated: List<UpdateReg>) {
        val order = findPluginOrder()
        updateRegs = updated
        val byType = HashMap<String, MutableList<UpdateListener>>()
        for (reg in updated.sortedBy { order[it.plugin] ?: Int.MAX_VALUE }) {
            for (type in reg.types) {
                val listening = byType.getOrPut(type) { mutableListOf() }
                // one dispatch per plugin however many of its registrations named this type; the engine fans out from a single handle
                val listener = listening.firstOrNull { it.plugin === reg.plugin }
                    ?: UpdateListener(reg.plugin).also { listening.add(it) }
                listener.grantScopes.add(reg.grantScope ?: type)
            }
        }
        updateListenersByType = byType
        hasUpdateListeners = byType.isNotEmpty()
        if (!hasUpdateListeners) {
            dispatchedUpdates.clear()
        }
    }

    private fun publishUpdateInterceptors(updated: List<UpdateInterceptor>) {
        val order = findPluginOrder()
        updateInterceptRegs = updated
        val byType = HashMap<String, MutableList<UpdateInterceptor>>()
        for (reg in updated.sortedBy { order[it.plugin] ?: Int.MAX_VALUE }) {
            for (type in reg.types) byType.getOrPut(type) { mutableListOf() }.add(reg)
        }
        updateInterceptorsByType = byType
        hasUpdateInterceptors = byType.isNotEmpty()
        // with nothing left to intercept, the ring is strong references to update graphs nothing will look up again
        if (!hasUpdateInterceptors) {
            interceptedUpdates.clear()
        }
    }

    // Plugin has no equals(), and import replaces the instance, so identity is the only stable key
    private fun findPluginOrder(): IdentityHashMap<Plugin, Int> {
        val order = IdentityHashMap<Plugin, Int>()
        PluginManager.plugins().forEachIndexed { index, plugin -> order[plugin] = index }
        return order
    }

    private fun markBypassed(request: TLObject) {
        synchronized(bypassed) { bypassed[request] = (bypassed[request] ?: 0) + 1 }
    }

    private fun isBypassed(request: TLObject): Boolean =
        synchronized(bypassed) { bypassed.containsKey(request) }

    private fun releaseBypass(request: TLObject) {
        synchronized(bypassed) {
            val count = bypassed[request] ?: return
            if (count > 1) bypassed[request] = count - 1 else bypassed.remove(request)
        }
    }

    /**
     * a request the *plugin* originated, kept out of every chain by the same lease [invokeRpc]
     * takes: a plugin that rewrites sends and a plugin that sends would otherwise be an infinite
     * loop. Held until the delegate answers, which is what proves stock cannot re-send this
     * instance.
     */
    internal fun sendWithoutInterceptors(
        account: Int,
        request: TLObject,
        flags: Int,
        onDone: (TLObject?, TLRPC.TL_error?) -> Unit,
    ) {
        markBypassed(request)
        ConnectionsManager.getInstance(account).sendRequest(request, { response, error ->
            releaseBypass(request)
            onDone(response, error)
        }, flags)
    }

    private fun endBypassLease(sent: SentRequest) {
        if (!sent.leased) return
        sent.leased = false
        releaseBypass(sent.request)
    }

    /**
     * refused regardless of grants: install-time validation only sees the scopes a grant *names*,
     * so an unscoped `@grant invokeRpc` would otherwise reach `auth.exportLoginToken`.
     */
    private fun takeoverRefusal(plugin: Plugin, method: String): String? {
        if (!TakeoverMethods.isBlocked(method)) return null
        if (plugin.permissions.has("unsafe.disableApiFiltering")) return null
        return PluginWire.encodePluginError("forbidden", "'$method' is an account-takeover method and is never available to plugins")
    }

    private fun dispatchChain(
        connectionsManager: ConnectionsManager,
        chain: List<Interceptor>,
        index: Int,
        request: TLObject,
        params: OriginalParams,
        scopeId: Long,
        account: Int,
        finalize: (TLObject?, TLRPC.TL_error?, Long) -> Unit,
    ) {
        if (index >= chain.size) {
            // the chain is gone, so the app has already been answered - or, for a cancel,
            // deliberately not. Sending anyway would also strand this [SentRequest]: nothing holds
            // it, so the collapse could neither cancel it, end its lease, nor free the request
            val armed = chains[scopeId] ?: return
            pauseChainTimer(scopeId)
            val sent = SentRequest(request)
            armed.sent = sent
            sendPassthrough(connectionsManager, account, sent, params, armed.guid) { response, error, responseTime ->
                // whatever it answered, this request cannot reach sendRequestInternal again
                endBypassLease(sent)
                val budget = chains[scopeId]
                if (budget == null) {
                    // the chain collapsed while the request was out, so nothing will consume this response
                    releaseUnowned(response)
                    return@sendPassthrough
                }
                if (response != null) ownedResponses[scopeId] = response
                budget.passthrough = PassthroughResult(response, error, responseTime)
                resumeChainTimer(scopeId)
                finalize(response, error, responseTime)
            }
            return
        }
        val interceptor = chain[index]
        val engine = interceptor.plugin.engine
        val tl = attachedTable(interceptor.plugin)
        if (engine == null || tl == null) {
            dispatchChain(connectionsManager, chain, index + 1, request, params, scopeId, account, finalize)
            return
        }
        val dispatchId = nextDispatchId++
        val method = TlNames.classNameToTlName(request.javaClass)
        pendingDispatches[dispatchId] =
            PendingDispatch(interceptor.plugin, tl, connectionsManager, chain, index, params, scopeId, method, account, finalize)
        chains[scopeId]?.stages?.add(dispatchId)
        val requestHandle = tl.mintForScope(request, scopeId)
        engine.dispatchRpc(
            interceptor.callbackId,
            dispatchId,
            method,
            account,
            PluginWire.encodeHandle(vector = false, id = requestHandle, readOnly = false),
        )
    }

    private fun armChain(
        scopeId: Long,
        connectionsManager: ConnectionsManager,
        chain: List<Interceptor>,
        method: String,
        request: TLObject,
        requestKey: Long,
        finalize: (TLObject?, TLRPC.TL_error?, Long) -> Unit,
    ) {
        val budget = ChainBudget(scopeId, connectionsManager, chain, method, request, finalize)
        budget.guid = takeGuid(requestKey)
        chains[scopeId] = budget
        startChainTimer(budget)
    }

    private fun startChainTimer(budget: ChainBudget) {
        val timer = Runnable { expireChain(budget.scopeId) }
        budget.timer = timer
        budget.startedAt = SystemClock.uptimeMillis()
        Utilities.globalQueue.postRunnable(timer, budget.remaining)
    }

    private fun pauseChainTimer(scopeId: Long) {
        val budget = chains[scopeId] ?: return
        val timer = budget.timer ?: return
        Utilities.globalQueue.cancelRunnable(timer)
        budget.timer = null
        budget.remaining -= SystemClock.uptimeMillis() - budget.startedAt
    }

    private fun resumeChainTimer(scopeId: Long) {
        val budget = chains[scopeId] ?: return
        if (budget.timer != null) return
        startChainTimer(budget)
    }

    /**
     * abandons every stage deepest first, dropping each from [pendingDispatches] *before* telling
     * its engine, so a rejection continuation re-entering [onNext]/[onComplete] finds nothing. Only
     * once all are abandoned are the scope's handles invalidated - the other order leaves that same
     * continuation reading handle-expired off every field of its own request.
     */
    private fun collapseChain(scopeId: Long, reasonWire: String): ChainBudget? {
        val budget = chains.remove(scopeId) ?: return null
        budget.timer?.let { Utilities.globalQueue.cancelRunnable(it) }
        budget.timer = null
        for (dispatchId in budget.stages.reversed()) {
            val pending = pendingDispatches.remove(dispatchId) ?: continue
            pending.plugin.engine?.abandonDispatch(dispatchId, reasonWire)
        }
        // covers handles stashed across an await; each stage minted into its own plugin's table
        for (interceptor in budget.chain) tlTables[interceptor.plugin]?.releaseScope(scopeId)
        // the free stock does the instant it has serialized a request, deferred to here because no
        // view can read it any more. Keyed on the chain, not the send: a stage that short-circuits
        // owes the free too. On stageQueue, which orders it behind a send this chain may still have
        // queued there - freeing from globalQueue could hand the buffers back mid-serializeToStream.
        // The lease is deliberately not dropped with it: a collapse does not end the flight
        budget.sent?.cancelled = true
        // a middleware may have handed next() a request it built, and that is the instance disableFree went on
        val sent = budget.sent?.request?.takeIf { it !== budget.request }
        Utilities.stageQueue.postRunnable {
            budget.request.disableFree = false
            budget.request.freeResources()
            sent?.let {
                it.disableFree = false
                it.freeResources()
            }
        }
        return budget
    }

    /** settling a stage answers *upward*, so without this the ones below keep advancing and the real request still goes out */
    private fun abandonBelow(dispatchId: Long, pending: PendingDispatch, reasonWire: String) {
        val budget = chains[pending.scopeId] ?: return
        val at = budget.stages.indexOf(dispatchId)
        if (at < 0) return
        val below = budget.stages.subList(at + 1, budget.stages.size)
        for (deeperId in below.reversed()) {
            val deeper = pendingDispatches.remove(deeperId) ?: continue
            deeper.plugin.engine?.abandonDispatch(deeperId, reasonWire)
        }
        below.clear()
    }

    /**
     * every stage but the deepest is parked in `await next()` and innocent, so only that one is
     * named. The request *fails* rather than falling through: earlier stages have already rewritten
     * it in place, and passing it on would make any stall a reliable interceptor bypass.
     *
     * Unless the passthrough already answered - a synthetic timeout there would report a committed
     * `messages.sendMessage` as failed and earn a duplicate when the user re-sends.
     */
    private fun expireChain(scopeId: Long) {
        val running = chains[scopeId]?.stages?.reversed()?.firstNotNullOfOrNull { pendingDispatches[it] }
        val budget = collapseChain(scopeId, TIMEOUT_WIRE) ?: return
        Log.w(TAG, "[${running?.plugin?.manifest?.name}] '${budget.method}' ran past the chain's ${CHAIN_BUDGET_MS}ms budget")
        val sent = budget.passthrough
        if (sent != null) {
            budget.finalize(sent.response, sent.error, sent.time)
        } else {
            budget.finalize(null, syntheticError(TIMEOUT_TEXT), budget.connectionsManager.currentTimeMillis)
        }
    }

    private fun onNext(dispatchId: Long, requestWire: String): String? {
        val pending = pendingDispatches[dispatchId] ?: return PluginWire.encodePluginError("internal", "next(): unknown dispatch")
        val nextRequest = try {
            decodeTlObject(pending.tl, requestWire)
        } catch (e: Exception) {
            return decodeFailureWire("next()", e)
        }
        // next() may rewrite fields but never the method: the app awaits that method's response type, and a swap would turn any interceptRpc grant into an unscoped send primitive
        val nextMethod = TlNames.classNameToTlName(nextRequest.javaClass)
        if (nextMethod != pending.method) {
            return PluginWire.encodePluginError(
                "forbidden",
                "next(): expected a '${pending.method}' request, got '$nextMethod' - rewrite the request's fields rather than replacing it",
            )
        }
        Utilities.globalQueue.postRunnable {
            if (pendingDispatches[dispatchId] !== pending) return@postRunnable
            dispatchChain(
                pending.connectionsManager,
                pending.chain,
                pending.index + 1,
                nextRequest,
                pending.params,
                pending.scopeId,
                pending.account,
            ) { response, error, responseTime ->
                pending.responseTime = responseTime
                Utilities.globalQueue.postRunnable {
                    // once the chain has collapsed this stage is gone, and completing it would mint into a released scope
                    if (pendingDispatches[dispatchId] !== pending) return@postRunnable
                    val engine = pending.plugin.engine ?: return@postRunnable
                    engine.completeNext(dispatchId, encodeChainResult(pending.tl, response, error, pending.scopeId))
                }
            }
        }
        return null
    }

    private fun onComplete(dispatchId: Long, resultWire: String) {
        val pending = pendingDispatches[dispatchId] ?: return
        val time = completionTime(pending)
        var response: TLObject? = null
        var error: TLRPC.TL_error? = null
        try {
            response = decodeTlValueOrError(pending.tl, resultWire)
        } catch (e: TlResultError) {
            error = e.error
        } catch (e: Exception) {
            error = syntheticError("bad middleware response: ${e.message}")
        }
        settleStage(dispatchId, pending) { pending.finalize(response, error, time) }
    }

    /**
     * drops the stage only once the posted settle runs: a due expiry timer sorts *ahead* of a
     * runnable posted now, so a stage removed before the hop would be invisible to [collapseChain]
     * and would answer the app a second time.
     */
    private fun settleStage(dispatchId: Long, pending: PendingDispatch, settle: () -> Unit) {
        Utilities.globalQueue.postRunnable {
            if (pendingDispatches.remove(dispatchId) !== pending) return@postRunnable
            // a stage that called next() without awaiting it settles with live stages beneath it, which would keep walking toward the real send
            abandonBelow(dispatchId, pending, ABANDONED_WIRE)
            settle()
        }
    }

    private fun completionTime(pending: PendingDispatch): Long =
        if (pending.responseTime != 0L) pending.responseTime else pending.connectionsManager.currentTimeMillis

    private fun sendPassthrough(
        connectionsManager: ConnectionsManager,
        account: Int,
        sent: SentRequest,
        params: OriginalParams,
        guid: Int,
        finalize: (TLObject?, TLRPC.TL_error?, Long) -> Unit,
    ) {
        markBypassed(sent.request)
        // stock frees the request the moment it has serialized it, gutting the writable view a parked stage holds. ownership moves to the chain; the free is in collapseChain
        sent.request.disableFree = true
        Utilities.stageQueue.postRunnable {
            // a cancel landed while this was queued: nothing will read the response, so it must not go out
            if (sent.cancelled) return@postRunnable
            sent.reachedNative = true
            connectionsManager.sendRequestInternal(
                sent.request,
                null,
                RequestDelegateTimestamp { response, error, responseTime ->
                    // stock frees the response the moment this delegate returns, handing upload.getFile's NativeByteBuffer back to a pool - gutting the object the chain is about to walk up
                    response?.disableFree = true
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
            // native learns the token only now. stock's own bindRequestToGuid is skipped because it would come straight back through onRequestBoundToGuid
            if (guid != 0) ConnectionsManager.native_bindRequestToGuid(account, params.requestToken, guid)
        }
    }

    private fun invokeRpc(
        plugin: Plugin,
        engine: QuickJs,
        tl: TlHandles,
        slot: Int,
        startedOn: Int,
        invokeId: Long,
        requestWire: String,
    ): String? {
        val request = try {
            decodeTlObject(tl, requestWire)
        } catch (e: Exception) {
            return decodeFailureWire("invokeRpc", e)
        }
        val tlName = TlNames.classNameToTlName(request.javaClass)
        takeoverRefusal(plugin, tlName)?.let { return it }
        if (!plugin.permissions.allows("invokeRpc", tlName, ScopeMatch.EXACT)) {
            return PluginWire.encodeNotGranted("invokeRpc", tlName)
        }
        // last, so a takeover method stays refused whichever slot it was aimed at. The slot is not the host's to trust: a plugin can call `invokeRpc` through any object carrying an `id`
        val account = if (slot == QuickJs.ANY_ACCOUNT) startedOn else slot
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || !UserConfig.isValidAccount(account)) {
            return PluginWire.encodePluginError("invalid-argument", "invokeRpc: no account in slot $account")
        }
        sendWithoutInterceptors(account, request, 0) { response, error ->
            // stageQueue, where freeResources() runs the moment this delegate returns - before the runnable below mints a handle. ownership moves here
            response?.disableFree = true
            PluginDispatch.onEngine(plugin, engine, onDropped = { releaseUnowned(response) }) {
                engine.resolveInvoke(invokeId, encodeInvokeResult(tl, response, error))
            }
        }
        return null
    }

    /**
     * Unpacking is synchronous because the batch is only whole on entry: a sub-update whose pts
     * does not line up is moved into a fresh wrapper stock parks, so by the time anything posted
     * from here runs the list may be empty. The fan-out then takes a stageQueue hop, so plugins
     * read the objects after `processUpdateArray` backfilled them rather than racing those writes -
     * and the two hops are the only happens-before edge to globalQueue.
     */
    @JvmStatic
    fun onUpdates(controller: MessagesController, updates: TLRPC.Updates, account: Int, fromQueue: Boolean): Boolean {
        // our own hand-back: observers see exactly what the app is about to apply, which is what makes a dropped update invisible to `onUpdate` too
        if (takenOver.remove(updates)) {
            if (hasUpdateListeners) fanOut(unpackUpdates(updates, account), account)
            return false
        }
        val busy = updateQueues[account]?.isNotEmpty() == true
        if (!busy && !hasUpdateInterceptors) {
            if (hasUpdateListeners) fanOut(unpackUpdates(updates, account), account)
            return false
        }
        val units = if (hasUpdateInterceptors) interceptableUnits(updates, account) else emptyList()
        // nothing to intercept and nothing ahead of it, so the app keeps the batch
        if (!busy && units.isEmpty()) {
            if (hasUpdateListeners) fanOut(unpackUpdates(updates, account), account)
            return false
        }
        val batch = UpdateBatch(controller, updates, account, fromQueue, units)
        val queue = updateQueues.getOrPut(account) { ArrayDeque() }
        queue.addLast(batch)
        // ordering is why an unclaimed batch is queued too: the app applies updates in arrival order
        if (queue.size == 1) Utilities.globalQueue.postRunnable { runBatch(batch) }
        return true
    }

    /**
     * Snapshotted here rather than at dispatch, so a registration made mid-walk joins the next
     * batch - the rule every `Disposer` follows. The two short forms carry no `TLRPC.Update` at
     * all, so what a middleware is handed is [normalizeShortMessage]'s synthetic one.
     */
    private fun interceptableUnits(updates: TLRPC.Updates, account: Int): List<UpdateUnit> {
        val units = ArrayList<UpdateUnit>()
        for ((arrival, update) in unpackUpdates(updates, account)) {
            val tlName = TlNames.classNameToTlName(update.javaClass)
            val chain = chainFor(update, tlName)
            if (chain.isEmpty()) continue
            // keyed on what stock would re-feed. A ring of its own, not [dispatchedUpdates]: sharing one would make the hand-back look already delivered
            if (!rememberIntercept(arrival)) continue
            val synthesized = arrival !== update
            units.add(UpdateUnit(update, tlName, chain, synthesized, if (synthesized) rawSnapshotOf(update) else null))
        }
        return units
    }

    /** carries the two rules [dispatchUpdate] does: a secret chat never reaches plugin code, and `updateServiceNotification` is a takeover surface the bypass grant lifts */
    private fun chainFor(update: TLObject, tlName: String): List<UpdateInterceptor> {
        if (isSecretChatUpdate(update)) return emptyList()
        val listening = updateInterceptorsByType[tlName] ?: return emptyList()
        val serviceNotification = update is TL_update.TL_updateServiceNotification
        return listening.filter { interceptor ->
            val permissions = interceptor.plugin.permissions
            (!serviceNotification || permissions.has("unsafe.disableApiFiltering")) &&
                permissions.allows("interceptUpdate", tlName, ScopeMatch.EXACT)
        }
    }

    private fun rememberIntercept(arrival: TLObject): Boolean = interceptedUpdates.add(arrival)

    /** unfiltered on purpose: only ever compared against another snapshot of the same object, and a hidden field would hide a rewrite of one reachable through a nested view */
    private val RAW_POLICY = TlFilter.Policy(takeover = false, drafts = true)

    private fun rawSnapshotOf(update: TLObject): String? = try {
        TlJson.toJson(update, RAW_POLICY).toString()
    } catch (e: Exception) {
        Log.w(TAG, "cannot snapshot ${update.javaClass.simpleName}: $e")
        null
    }

    private fun runBatch(batch: UpdateBatch) {
        batch.scopeId = TlHandles.newScope()
        val timer = Runnable { expireBatch(batch) }
        batch.timer = timer
        Utilities.globalQueue.postRunnable(timer, UPDATE_BUDGET_MS)
        advanceBatch(batch)
    }

    /** a loop rather than a recursion: a batch of a hundred updates none of whose plugins are running would be a hundred frames deep */
    private fun advanceBatch(batch: UpdateBatch) {
        while (!batch.expired && batch.index < batch.units.size) {
            val unit = batch.units[batch.index]
            val interceptor = unit.chain.getOrNull(batch.stage)
            if (interceptor == null || unit.update in batch.dropped) {
                batch.index++
                batch.stage = 0
                continue
            }
            batch.stage++
            val engine = interceptor.plugin.engine
            val tl = attachedTable(interceptor.plugin)
            if (engine == null || tl == null) continue
            val dispatchId = nextDispatchId++
            batch.dispatchId = dispatchId
            batch.stagePlugin = interceptor.plugin
            pendingUpdateDispatches[dispatchId] = batch
            val handle = tl.mintForScope(unit.update, batch.scopeId)
            engine.dispatchUpdateIntercept(
                interceptor.callbackId,
                dispatchId,
                unit.tlName,
                batch.account,
                PluginWire.encodeHandle(vector = false, id = handle, readOnly = false),
            )
            return
        }
        finishBatch(batch)
    }

    /** posted, not run inline: this arrives from inside the engine's own JNI upcall, and the next stage may be the same engine */
    private fun onUpdateStageSettled(dispatchId: Long, deliver: Boolean) {
        Utilities.globalQueue.postRunnable {
            val batch = pendingUpdateDispatches.remove(dispatchId) ?: return@postRunnable
            batch.stagePlugin = null
            if (!deliver) {
                // a drop ends the chain for that update, exactly as a short-circuiting request stage ends its own
                batch.dropped.add(batch.units[batch.index].update)
            }
            advanceBatch(batch)
        }
    }

    /**
     * Everything still undecided is **delivered**, never dropped: dropping is the one verdict that
     * desyncs pts, and doing it because a plugin stalled would turn any stall into lost messages.
     */
    private fun expireBatch(batch: UpdateBatch) {
        if (batch.finished) return
        batch.expired = true
        val dispatchId = batch.dispatchId
        val stagePlugin = batch.stagePlugin
        if (dispatchId != 0L && pendingUpdateDispatches.remove(dispatchId) === batch) {
            stagePlugin?.engine?.abandonUpdateDispatch(dispatchId)
        }
        Log.w(TAG, "[${stagePlugin?.manifest?.name}] an update batch ran past the ${UPDATE_BUDGET_MS}ms budget")
        finishBatch(batch)
    }

    private fun finishBatch(batch: UpdateBatch) {
        if (batch.finished) return
        batch.finished = true
        batch.timer?.let { Utilities.globalQueue.cancelRunnable(it) }
        batch.timer = null
        // before the hand-back, so no view can still read an update the app is about to apply
        for (plugin in batch.units.flatMap { it.chain }.map { it.plugin }.distinct()) {
            tlTables[plugin]?.releaseScope(batch.scopeId)
        }
        Utilities.stageQueue.postRunnable { deliverBatch(batch) }
    }

    /**
     * The hand-back is unbounded (`processUpdates` reaches most of the app) and the one place a
     * throw is unrecoverable: the account's whole stream is parked behind this queue entry, every
     * later arrival adds itself without posting a walk, and the budget is long spent. So the queue
     * advances whatever happens, and nothing escapes onto stageQueue.
     */
    private fun deliverBatch(batch: UpdateBatch) {
        try {
            val deliverable = deliverable(batch)
            if (deliverable != null) {
                takenOver.add(deliverable)
                batch.controller.processUpdates(deliverable, batch.fromQueue)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "handing an update batch back to the app failed", e)
        } finally {
            advanceUpdateQueue(batch)
        }
    }

    private fun advanceUpdateQueue(batch: UpdateBatch) {
        val queue = updateQueues[batch.account] ?: return
        if (queue.firstOrNull() !== batch) return
        queue.removeFirst()
        val next = queue.firstOrNull()
        if (next == null) updateQueues.remove(batch.account) else Utilities.globalQueue.postRunnable { runBatch(next) }
    }

    /**
     * A `TL_updates` that lost every update is still handed over: it carries the seq and date
     * advance, and withholding that desyncs strictly more than the drop already did.
     *
     * The two short forms cannot be answered in place - the app applies them from their own fields
     * and never builds the `Update` a middleware was handed - so a *rewritten* one is handed over as
     * the `TL_updates` the server would have sent. Only when rewritten: stock's branch prefetches
     * the sender and does its own pts bookkeeping, and there is no reason to leave it for a plugin
     * that only looked.
     */
    private fun deliverable(batch: UpdateBatch): TLRPC.Updates? {
        val updates = batch.updates
        val short = batch.units.firstOrNull { it.synthesized }
        if (short != null) {
            if (short.update in batch.dropped) return null
            val untouched = short.snapshot != null && rawSnapshotOf(short.update) == short.snapshot
            return if (untouched) updates else asUpdatesBatch(short.update, updates)
        }
        if (batch.dropped.isEmpty()) return updates
        if (updates is TLRPC.TL_updateShort) return if (updates.update in batch.dropped) null else updates
        updates.updates?.let { list -> list.removeAll { it in batch.dropped } }
        return updates
    }

    /**
     * `users`/`chats` stay empty on purpose. Stock groups by `getUpdatePts`/`getUpdatePtsCount`
     * (which is why the synthetic update carries both) and hands the group to `processUpdateArray`,
     * which resolves every peer through the batch's own `users`, then `MessagesController.getUser`,
     * then `MessagesStorage.getUserSync`, and answers a miss with `needGetDiff`. So an uncached
     * sender is backfilled exactly as on the path this replaces.
     */
    private fun asUpdatesBatch(update: TLObject, original: TLRPC.Updates): TLRPC.Updates =
        TLRPC.TL_updates().apply {
            updates = arrayListOf(update as TLRPC.Update)
            date = original.date
        }

    /**
     * the difference catch-up paths walk their payload themselves instead of feeding it through
     * [onUpdates], so without this a plugin sees nothing for anything that arrived while it was
     * offline. `new_messages` are bare messages, so each is wrapped in the update the server would
     * have sent had the client been online.
     *
     * Must stay at the top of the difference's own stageQueue runnable: the secret-chat messages
     * `getDifference` decrypts are appended to `new_messages` further down that same runnable.
     */
    @JvmStatic
    fun onDifference(newMessages: List<TLRPC.Message>?, otherUpdates: List<TLRPC.Update>?, account: Int) {
        if (!hasUpdateListeners) return
        val batch = ArrayList<UnpackedUpdate>()
        otherUpdates?.forEach { batch.add(UnpackedUpdate(it, it)) }
        newMessages?.forEach { message ->
            // stock skips these too: a hole the server is reporting, not a message
            if (message is TLRPC.TL_messageEmpty) return@forEach
            batch.add(UnpackedUpdate(message, wrapDifferenceMessage(message)))
        }
        fanOut(batch, account)
    }

    private fun fanOut(batch: List<UnpackedUpdate>, account: Int) {
        if (batch.isEmpty()) return
        Utilities.stageQueue.postRunnable {
            Utilities.globalQueue.postRunnable {
                for (unpacked in batch) {
                    if (!rememberDispatch(unpacked.arrival)) continue
                    dispatchUpdate(unpacked.update, account)
                }
            }
        }
    }

    /** the sender decides the shape, as on the live path; pts is 0 - a difference carries one state for the whole batch */
    private fun wrapDifferenceMessage(message: TLRPC.Message): TLObject =
        if (message.peer_id is TLRPC.TL_peerChannel) {
            TL_update.TL_updateNewChannelMessage().apply { this.message = message }
        } else {
            TL_update.TL_updateNewMessage().apply { this.message = message }
        }

    /**
     * paired with the object stock would re-feed it as. The short forms are their own update (each
     * pass through [normalizeShortMessage] mints a different one, which would defeat [arrival]), and
     * a difference's `new_messages` are keyed on the message for the same reason.
     */
    private data class UnpackedUpdate(val arrival: TLObject, val update: TLObject)

    /** the one reading of a batch's shape, so a new `Updates` subclass cannot be taught to the interception pass and not the fan-out */
    private fun unpackUpdates(updates: TLRPC.Updates, account: Int): List<UnpackedUpdate> = when (updates) {
        is TLRPC.TL_updateShort ->
            listOfNotNull(updates.update?.let { UnpackedUpdate(it, it) })
        is TLRPC.TL_updates ->
            updates.updates?.map { UnpackedUpdate(it, it) } ?: emptyList()
        is TLRPC.TL_updatesCombined ->
            updates.updates?.map { UnpackedUpdate(it, it) } ?: emptyList()
        is TLRPC.TL_updateShortMessage,
        is TLRPC.TL_updateShortChatMessage ->
            listOf(UnpackedUpdate(updates, normalizeShortMessage(updates, account)))
        else -> emptyList()
    }

    /**
     * stock re-feeds a parked batch once its pts lands, and the wrapper holds the very `Update`
     * instances the first pass delivered. The hook's own `fromQueue` flag cannot tell them apart,
     * since a wrapper is also how sub-updates the first pass could not apply come back.
     *
     * Bounded rather than complete: stock waits ~1.5 s on a pts hole before giving the queue up.
     */
    private fun rememberDispatch(arrival: TLObject): Boolean = dispatchedUpdates.add(arrival)

    /**
     * the compressed short forms are rebuilt the same way stock's `processUpdates` does (field
     * mapping must stay in sync with that branch). `updateShortSentMessage` is not delivered at
     * all - a send ack with no message body, and the sender already holds the rpc response.
     */
    private fun normalizeShortMessage(updates: TLRPC.Updates, account: Int): TL_update.TL_updateNewMessage {
        val message = TLRPC.TL_message()
        message.id = updates.id
        if (updates is TLRPC.TL_updateShortChatMessage) {
            message.from_id = TLRPC.TL_peerUser().apply { user_id = updates.from_id }
            message.peer_id = TLRPC.TL_peerChat().apply { chat_id = updates.chat_id }
            message.dialog_id = -updates.chat_id
        } else {
            val clientUserId = UserConfig.getInstance(account).getClientUserId()
            message.from_id = TLRPC.TL_peerUser().apply { user_id = if (updates.out) clientUserId else updates.user_id }
            message.peer_id = TLRPC.TL_peerUser().apply { user_id = updates.user_id }
            message.dialog_id = updates.user_id
        }
        message.fwd_from = updates.fwd_from
        message.silent = updates.silent
        message.out = updates.out
        message.mentioned = updates.mentioned
        message.media_unread = updates.media_unread
        message.entities = updates.entities
        message.message = updates.message
        message.date = updates.date
        message.via_bot_id = updates.via_bot_id
        message.flags = updates.flags or TLRPC.MESSAGE_FLAG_HAS_FROM_ID
        message.reply_to = updates.reply_to
        message.ttl_period = updates.ttl_period
        message.media = TLRPC.TL_messageMediaEmpty()
        return TL_update.TL_updateNewMessage().apply {
            this.message = message
            pts = updates.pts
            pts_count = updates.pts_count
        }
    }

    /** skipped before anything is minted: the type list is required precisely so filtering costs a map lookup rather than a handle and a bridge crossing per update */
    private fun dispatchUpdate(update: TLObject, account: Int) {
        val tlName = TlNames.classNameToTlName(update.javaClass)
        val listening = updateListenersByType[tlName] ?: return
        // the one rule `unsafe.disableApiFiltering` does not lift, same as `PluginReads.dialogIdOf` refusing an encrypted dialog id
        if (isSecretChatUpdate(update)) return
        val serviceNotification = update is TL_update.TL_updateServiceNotification
        for (listener in listening) {
            val plugin = listener.plugin
            val engine = plugin.engine ?: continue
            val tl = tlTables[plugin] ?: continue
            // carries a login code with no peer to redact against. per-plugin rather than in the unpack loop, so the bypass grant lifts it like the other three
            if (serviceNotification && !plugin.permissions.has("unsafe.disableApiFiltering")) continue
            // over the scopes that actually authorized this plugin for this constructor - a demuxed registration holds its event's scope, and the two never imply each other
            if (listener.grantScopes.none { plugin.permissions.allows("onUpdate", it, ScopeMatch.EXACT) }) continue
            val handle = tl.mintForPlugin(update, readOnly = true)
            engine.dispatchUpdate(tlName, account, PluginWire.encodeHandle(vector = false, id = handle, readOnly = true))
        }
    }

    /** none of the four has a `DialogId` a plugin could have named in the first place */
    private fun isSecretChatUpdate(update: TLObject): Boolean =
        update is TL_update.TL_updateNewEncryptedMessage ||
            update is TL_update.TL_updateEncryption ||
            update is TL_update.TL_updateEncryptedChatTyping ||
            update is TL_update.TL_updateEncryptedMessagesRead

    private class TlResultError(val error: TLRPC.TL_error) : Exception("${error.code}: ${error.text}")

    private class DecodeFault(val code: String, message: String) : Exception(message)

    private fun decodeTlObject(tl: TlHandles, wire: String): TLObject = when (val decoded = PluginWire.decode(wire)) {
        is PluginWire.Value.Handle -> {
            if (tl.isReadOnly(decoded.id)) throw DecodeFault("forbidden", TlHandles.READ_ONLY_MESSAGE)
            tl.resolveTlObject(decoded.id)
                ?: throw DecodeFault("handle-expired", PluginWire.HANDLE_EXPIRED_MESSAGE)
        }
        is PluginWire.Value.Json -> constructTlObject(JSONObject(decoded.json))
        else -> throw DecodeFault("invalid-argument", "expected a TL object")
    }

    private fun constructTlObject(json: JSONObject): TLObject {
        val tlName = json.optString("_", "")
        if (tlName.isEmpty()) throw DecodeFault("invalid-argument", "a constructed TL object needs a '_' type name")
        if (TlCtorIds.idsOf(tlName) == null) throw DecodeFault("unknown-constructor", "unknown TL type '$tlName'")
        return try {
            TlJson.fromJson(json)
        } catch (e: Exception) {
            throw DecodeFault("invalid-argument", e.message ?: e.toString())
        }
    }

    private fun decodeFailureWire(prefix: String, e: Exception): String = when (e) {
        is DecodeFault -> PluginWire.encodePluginError(e.code, "$prefix: ${e.message}")
        else -> PluginWire.encodePluginError("invalid-argument", "$prefix: ${e.message}")
    }

    private fun decodeTlValueOrError(tl: TlHandles, wire: String): TLObject? = when (val decoded = PluginWire.decode(wire)) {
        is PluginWire.Value.Null -> null
        is PluginWire.Value.Error -> throw TlResultError(syntheticError(decoded.message))
        is PluginWire.Value.RpcError -> throw TlResultError(TLRPC.TL_error().apply { code = decoded.code; text = decoded.text })
        is PluginWire.Value.Handle -> {
            if (tl.isReadOnly(decoded.id)) throw TlResultError(syntheticError(TlHandles.READ_ONLY_MESSAGE))
            tl.resolveTlObject(decoded.id)
                ?: throw TlResultError(syntheticError(PluginWire.HANDLE_EXPIRED_MESSAGE))
        }
        is PluginWire.Value.Json -> constructTlObject(JSONObject(decoded.json))
        else -> throw IllegalArgumentException("unsupported result payload")
    }

    private fun encodeChainResult(tl: TlHandles, response: TLObject?, error: TLRPC.TL_error?, scopeId: Long): String {
        if (error != null) return PluginWire.encodeRpcError(error.code, error.text ?: "")
        if (response == null) return PluginWire.encodeNull()
        return PluginWire.encodeHandle(vector = false, id = tl.mintForScope(response, scopeId), readOnly = false)
    }

    private fun encodeInvokeResult(tl: TlHandles, response: TLObject?, error: TLRPC.TL_error?): String {
        if (error != null) {
            // stock's free was suppressed before we knew it wouldn't be handed over, so nothing else will free it
            releaseUnowned(response)
            return PluginWire.encodeRpcError(error.code, error.text ?: "")
        }
        if (response == null) return PluginWire.encodeNull()
        return PluginWire.encodeHandle(
            vector = false,
            id = tl.mintForPlugin(response, readOnly = false, owned = true),
            readOnly = false,
        )
    }

    internal fun releaseUnowned(response: TLObject?) {
        if (response == null) return
        response.disableFree = false
        response.freeResources()
    }

    /** [owned] is the passthrough response this chain took ownership of, which a stage may have replaced; either way both are freed exactly once */
    private fun freeChainResponse(response: TLObject?, owned: TLObject?) {
        releaseUnowned(owned)
        if (response === owned || response == null) return
        // a substituted response still carrying disableFree is owned by the plugin's own table, which frees it
        if (!response.disableFree) response.freeResources()
    }

    private fun syntheticError(message: String): TLRPC.TL_error =
        TLRPC.TL_error().apply { code = SYNTHETIC_CODE; text = message }
}
