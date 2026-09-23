package desu.inugram.helpers.plugins.telegram

import desu.inugram.helpers.plugins.PluginLog
import desu.inugram.helpers.plugins.SessionResource
import desu.inugram.core.plugins.PluginRefusal
import desu.inugram.core.plugins.BoundedLru
import desu.inugram.core.plugins.DispatchDeadline
import desu.inugram.core.plugins.GrantCatalog
import desu.inugram.core.plugins.PluginWire
import desu.inugram.core.plugins.ScopeMatch
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.RpcListener
import desu.inugram.helpers.plugins.tl.TlHandles
import desu.inugram.helpers.plugins.tl.TlNames
import desu.inugram.core.plugins.TlTables
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import org.json.JSONObject
import org.telegram.messenger.KeepAliveJob
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.QuickAckDelegate
import org.telegram.tgnet.RequestDelegate
import org.telegram.tgnet.RequestDelegateTimestamp
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.WriteToSocketDelegate
import org.telegram.ui.ChatActivity


/**
 * Chain ops run on [EngineDispatch.scheduler]. [onNext]/[onComplete] post instead of reentering the
 * engine inside a JNI upcall: reentry double-borrows the runtime's `RefCell` and aborts the process.
 * Responses return on [Utilities.stageQueue], where stock mutates pts/seq.
 */
object PluginRpc : SessionResource {
    private class Interceptor(
        val session: PluginSession,
        val callbackId: Int,
        val strict: Boolean,
        val scope: String,
        val filter: SendFilter?,
    )

    private class SendFilter(val text: Pattern?, val textIsSticky: Boolean, val isEdit: Boolean?) {
        fun matches(method: String, request: TLObject): Boolean {
            if (isEdit != null && isEdit != (method == "messages.editMessage")) return false
            return matchesText(collectTexts(request)?.firstOrNull()?.text)
        }

        fun matchesSend(text: String?): Boolean = isEdit != true && matchesText(text)

        private fun matchesText(value: String?): Boolean {
            if (text == null) return true
            value ?: return false
            val matcher = text.matcher(value)
            return if (textIsSticky) matcher.lookingAt() else matcher.find()
        }
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

    private class OptimisticMessages(
        val account: Int,
        val messages: List<MessageObject>,
        val draft: DraftKey?,
    )

    private class DraftKey(val dialogId: Long, val threadId: Long)

    private class OptimisticText(val text: String, val entities: ArrayList<TLRPC.MessageEntity>)

    private class PendingDispatch(
        val session: PluginSession,
        val operation: RpcChain,
        val index: Int,
        val request: TLObject,
        val finalize: (TLObject?, TLRPC.TL_error?, Long) -> Unit,
    ) {
        var responseTime = 0L
        var nextStarted = false
        var nextResult: PassthroughResult? = null
        var skipped = false
    }

    /**
     * suspended while a request is in flight, so server latency is not charged to plugins.
     * uptimeMillis because that is what `Handler.postDelayed` counts in.
     */
    private class RpcChain(
        val scopeId: Long,
        val connectionsManager: ConnectionsManager,
        val chain: List<Interceptor>,
        val method: String,
        val request: TLObject,
        val optimisticMessages: OptimisticMessages?,
        val params: OriginalParams,
        val account: Int,
        val finalize: (TLObject?, TLRPC.TL_error?, Long) -> Unit,
    ) {
        val deadlineMillis = if (chain.any { it.scope == SEND_SCOPE }) SEND_CHAIN_BUDGET_MS else RPC_CHAIN_BUDGET_MS
        val stages = ArrayList<Long>()
        val deadline = DispatchDeadline(EngineDispatch.scheduler, deadlineMillis) { expireChain(scopeId) }
        var completed = false
        // response whose stock free was suppressed; freed only by this chain's finalize
        var ownedResponse: TLObject? = null
        // plugin queue only
        var verdict: ChainVerdict? = null

        var passthrough: PassthroughResult? = null

        var guid = 0

        var sent: SentRequest? = null

        var media: PluginSendMorph.Media? = null
    }

    /**
     * stock frees request NativeByteBuffers after serialization, including `upload.saveFilePart`.
     * Suppressed while stages may hold request views across `await next()`, freed when the chain retires.
     *
     * [leased] survives retries: CONNECTION_NOT_INITED resends the same object with a new token and no
     * delegate call. Released only on response or native cancellation.
     */
    private class SentRequest(val request: TLObject) {
        var leased = true

        @Volatile var cancelled = false

        var reachedNative = false
    }

    private class PassthroughResult(val response: TLObject?, val error: TLRPC.TL_error?, val time: Long)

    private const val SEND_SCOPE = "interceptSendMessage"
    private val SEND_METHODS = arrayOf("messages.sendMessage", "messages.sendMedia", "messages.sendMultiMedia")
    private const val RAW_GRANT = "unsafe.invokeRaw"
    private const val TAKEOUT_GRANT = "takeout"
    private const val RPC_CHAIN_BUDGET_MS = 10_000L
    private const val SEND_CHAIN_BUDGET_MS = 60_000L

    private const val GUID_MEMORY = 512
    private const val SYNTHETIC_CODE = -1000
    /** only routes a verdict to the composer's error path; nothing matches on this text */
    private const val MORPHED_TEXT = "MESSAGE_MORPHED_BY_PLUGIN"
    private const val DROPPED_TEXT = "MESSAGE_DROPPED_BY_PLUGIN"

    /** only reached when the error path the verdict was armed for never runs */
    private const val VERDICT_TTL_MILLIS = 30_000L
    private const val TIMEOUT_TEXT = "INTERCEPTOR_TIMEOUT"
    private const val ABANDONED_TEXT = "INTERCEPTOR_ABANDONED"
    private const val CANCELLED_TEXT = "INTERCEPTOR_CANCELLED"
    internal val TIMEOUT_WIRE = PluginWire.encodeRpcError(SYNTHETIC_CODE, TIMEOUT_TEXT)
    internal val ABANDONED_WIRE = PluginWire.encodeRpcError(SYNTHETIC_CODE, ABANDONED_TEXT)
    private val CANCELLED_WIRE = PluginWire.encodeRpcError(SYNTHETIC_CODE, CANCELLED_TEXT)

    @Volatile private var hasInterceptors = false

    @Volatile private var interceptorsByMethod: Map<String, List<Interceptor>> = emptyMap()

    // rust keeps interceptRpc and interceptUpdate dispatch ids in separate tables
    private var nextDispatchId = 1L
    private val pendingDispatches = HashMap<Long, PendingDispatch>()
    private val chains = HashMap<Long, RpcChain>()

    private val chainsByToken = HashMap<Long, Long>()
    // the app binds synchronously, usually before the request reaches `sendRequestInternal`.
    // bounded so a burst of requests waiting to be armed cannot grow it
    private val guidByToken = BoundedLru<Long, Int>(GUID_MEMORY)

    /**
     * a middleware may substitute a response, not a verdict: once a verdict is made the app owes the
     * local message an unwind, so an outer stage must not revoke it by swallowing the rejection
     */
    private sealed interface ChainVerdict {
        object Dropped : ChainVerdict

        class TakenOver(val media: PluginSendMorph.Media) : ChainVerdict
    }

    private val sendVerdicts = ConcurrentHashMap<Long, ChainVerdict>()

    /** all accounts mint local ids from the same sequence */
    private fun accountKey(account: Int, id: Int): Long = (account.toLong() shl 32) or (id.toLong() and 0xffffffffL)

    // requests we re-issued, kept out of maybeIntercept. A lease, not consumed by the first send: on
    // CONNECTION_NOT_INITED stock re-sends the same object with a new token and no delegate call.
    // counted, because one instance can be leased twice
    private val bypassed = IdentityHashMap<TLObject, Int>()

    @Volatile private var hasBypass = false
    private val optimisticMessagesByRequest = IdentityHashMap<TLObject, OptimisticMessages>()

    /** a binding can outlive its chain and is still owed a release, so the fast path reads this too */
    @Volatile private var hasOptimistic = false

    @JvmStatic
    fun bindOptimisticMessage(request: TLObject, account: Int, message: MessageObject) =
        bindOptimisticMessages(request, account, arrayListOf(message))

    @JvmStatic
    fun bindOptimisticMessages(request: TLObject, account: Int, messages: ArrayList<MessageObject>) {
        if (!hasInterceptors && !hasBypass) return
        if (messages.any { PluginOptimisticSend.claimRequest(request, it) }) markBypassed(request)
        val method = TlNames.classNameToTlName(request.javaClass)
        if (interceptorsByMethod[method].orEmpty().none { it.scope == SEND_SCOPE && it.filter?.matches(method, request) != false }) {
            return PluginSendHold.release(account, messages)
        }
        val draft = messages.firstOrNull()?.let { draftAwaitingClear(account, it) }
        synchronized(optimisticMessagesByRequest) {
            optimisticMessagesByRequest[request] = OptimisticMessages(account, messages.toList(), draft)
            hasOptimistic = true
        }
    }

    /**
     * stock clears the draft right after this call. Only the key is kept: a verdict owes the server an
     * empty draft, never the typed one.
     */
    private fun draftAwaitingClear(account: Int, message: MessageObject): DraftKey? {
        val dialogId = message.dialogId
        if (dialogId == 0L) return null
        val threadId = getDraftThreadId(message.messageOwner)
        val existing = MediaDataController.getInstance(account).getDraft(dialogId, threadId) ?: return null
        return if (existing is TLRPC.TL_draftMessageEmpty) null else DraftKey(dialogId, threadId)
    }

    /** reply header flag bit 1 carries the topic root id */
    private fun getDraftThreadId(message: TLRPC.Message?): Long {
        val replyTo = message?.reply_to ?: return 0L
        return if ((replyTo.flags and 2) != 0) replyTo.reply_to_top_id.toLong() else 0L
    }

    /**
     * the server draft is cleared by the request's `clear_draft`, which a verdict never sends, and a
     * takeover re-send has `clear_draft = false`. Without this the draft returns on the next sync.
     */
    private fun clearServerDraft(account: Int, draft: DraftKey) {
        AndroidUtilities.runOnUIThread {
            MediaDataController.getInstance(account)
                .saveDraft(draft.dialogId, draft.threadId, "", null, null, null, null, 0L, false, true)
        }
    }

    internal fun maySendBeIntercepted(text: String?): Boolean {
        if (!hasInterceptors) return false
        return SEND_METHODS.any { method ->
            interceptorsByMethod[method].orEmpty().any {
                it.session.canDispatch() && it.scope == SEND_SCOPE && it.filter?.matchesSend(text) != false
            }
        }
    }

    /** a message that is itself a `setMedia` answer is refused, or a middleware could answer its own re-send forever */
    internal fun holdMedia(session: PluginSession, dispatchId: Long, media: PluginSendMorph.Media) {
        val pending = pendingDispatches[dispatchId]
        if (pending == null || pending.session !== session) {
            PluginWire.refuse("invalid-argument", "setMedia: this send is no longer being intercepted")
        }
        val budget = chains[pending.operation.scopeId]
            ?: PluginWire.refuse("invalid-argument", "setMedia: this send is no longer being intercepted")
        val message = budget.optimisticMessages?.messages?.singleOrNull()
            ?: PluginWire.refuse("unsupported", "setMedia: this send has no local message of its own to put media on")
        if (PluginSendMorph.isMorphed(message)) {
            PluginWire.refuse("unsupported", "setMedia: this message already took its media from a plugin")
        }
        budget.media?.upload?.discard()
        budget.media = media
    }

    @JvmStatic
    fun handleDroppedSend(
        helper: SendMessagesHelper,
        account: Int,
        message: TLRPC.Message,
        scheduled: Boolean,
    ): Boolean {
        if (sendVerdicts.isEmpty()) return false
        val verdict = sendVerdicts.remove(accountKey(account, message.id)) ?: return false
        return when (verdict) {
            is ChainVerdict.Dropped -> {
                removeDroppedMessage(helper, account, message, scheduled)
                true
            }
            is ChainVerdict.TakenOver -> PluginSendMorph.takeOver(helper, account, message, scheduled, verdict.media)
        }
    }

    @JvmStatic
    fun handleDroppedEdit(account: Int, messageId: Int): Boolean =
        !sendVerdicts.isEmpty() && sendVerdicts.remove(accountKey(account, messageId)) is ChainVerdict.Dropped

    @JvmStatic
    fun handleDroppedSends(
        helper: SendMessagesHelper,
        account: Int,
        messages: ArrayList<MessageObject>,
        scheduled: Boolean,
    ): Boolean =
        // `count`, not `any`: every message owed a verdict must get one
        !sendVerdicts.isEmpty() &&
            messages.count { handleDroppedSend(helper, account, it.messageOwner, scheduled) } > 0

    private fun getChatMode(message: TLRPC.Message, scheduled: Boolean): Int = when {
        scheduled -> ChatActivity.MODE_SCHEDULED
        MessageObject.isWelcomeMessage(message) -> ChatActivity.MODE_WELCOME_MESSAGES
        message.quick_reply_shortcut_id != 0 || message.quick_reply_shortcut != null -> ChatActivity.MODE_QUICK_REPLIES
        else -> ChatActivity.MODE_DEFAULT
    }

    private fun removeDroppedMessage(helper: SendMessagesHelper, account: Int, message: TLRPC.Message, scheduled: Boolean) {
        val mode = getChatMode(message, scheduled)
        MessagesController.getInstance(account).deleteMessages(
            arrayListOf(message.id),
            null,
            null,
            message.dialog_id,
            if (mode == ChatActivity.MODE_QUICK_REPLIES) message.quick_reply_shortcut_id else MessageObject.getTopicId(account, message, 0).toInt(),
            false,
            mode,
            true,
        )
        helper.processSentMessage(message.id)
        helper.removeFromSendingMessages(message.id, scheduled)
    }

    fun listenerFor(session: PluginSession): RpcListener {
        // snapshot: a plugin's requests must not jump accounts on a switch
        val invokeAccount = UserConfig.selectedAccount
        val onHost = EngineDispatch.createHostDispatcher { session.isCurrent() }
        return object : RpcListener {
            override fun onRpcRegister(
                methods: Array<String>,
                callbackId: Int,
                scope: String,
                strict: Boolean,
                filterJson: String,
            ): String? = registerIntercept(session, methods, callbackId, scope, strict, filterJson)

            override fun onRpcUnregister(callbackId: Int) =
                onHost { unregisterIntercept(session, callbackId) }

            override fun onInvokeRpc(invokeId: Long, slot: Int, requestWire: String): String? =
                invokeRpc(session, slot, invokeAccount, invokeId, requestWire)

            override fun onRpcNext(dispatchId: Long, requestWire: String): String? =
                onNext(dispatchId, requestWire)

            override fun onRpcComplete(dispatchId: Long, resultWire: String) =
                onHost { onComplete(dispatchId, resultWire) }

            override fun onInvokeRaw(invokeId: Long, slot: Int, method: ByteArray): String? =
                invokeRaw(session, slot, invokeAccount, invokeId, method)

            override fun onTakeout(invokeId: Long, slot: Int, op: Int, takeoutId: String, arg: String): String? =
                takeout(session, slot, invokeAccount, invokeId, op, takeoutId, arg)
        }
    }

    /**
     * the handle table is released later by [TlHandles.releaseAll]: abandoned continuations may still
     * read their own request view
     */
    override fun detach(session: PluginSession) {
        publishInterceptors(
            interceptorsByMethod
                .mapValues { (_, list) -> list.filter { it.session !== session } }
                .filterValues { it.isNotEmpty() }
        )
        // ascending dispatch id is chain order
        val stale = pendingDispatches.filterValues { it.session === session }.keys.sorted()
        for (dispatchId in stale) {
            val pending = pendingDispatches.remove(dispatchId) ?: continue
            abandonBelow(dispatchId, pending, ABANDONED_WIRE)
            pending.finalize(null, syntheticError("plugin '${session.manifest.name}' was stopped"), completionTime(pending))
        }
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
        if (!hasInterceptors && !hasBypass && !hasOptimistic) return false
        // includes stock's own re-send of a leased request
        if (isBypassed(request)) return unintercepted(request)
        if (!hasInterceptors) return unintercepted(request)
        val tlName = TlNames.classNameToTlName(request.javaClass)
        val chain = interceptorsByMethod[tlName]
            ?.filter { it.filter?.matches(tlName, request) != false }
            ?.takeIf { it.isNotEmpty() }
            ?: return unintercepted(request)
        val optimisticMessages = takeOptimisticMessages(request)
        val params = OriginalParams(flags, datacenterId, connectionType, immediate, requestToken, onQuickAck, onWriteToSocket)
        val requestKey = accountKey(currentAccount, requestToken)
        EngineDispatch.scheduler.postRunnable {
            val scopeId = TlHandles.newScope()
            // an edit draws no message: unwind the one the request names, read before a stage can rewrite it
            val unwound = optimisticMessages?.messages?.map { it.id }
                ?: listOfNotNull((request as? TLRPC.TL_messages_editMessage)?.id)
            lateinit var operation: RpcChain
            val finalize: (TLObject?, TLRPC.TL_error?, Long) -> Unit = finalize@{ chainResponse, chainError, responseTime ->
                if (operation.completed) return@finalize
                operation.completed = true
                val verdict = operation.verdict
                operation.verdict = null
                armVerdict(optimisticMessages?.account ?: currentAccount, unwound, verdict)
                if (verdict != null) optimisticMessages?.draft?.let { clearServerDraft(optimisticMessages.account, it) }
                val response = if (verdict == null) chainResponse else null
                // the composer's error path unwinds a local send and needs an error to run
                val error = when (verdict) {
                    null -> chainError
                    is ChainVerdict.Dropped -> syntheticError(DROPPED_TEXT)
                    is ChainVerdict.TakenOver -> syntheticError(MORPHED_TEXT)
                }
                optimisticMessages?.let { PluginSendHold.settle(it.account, it.messages, verdict !is ChainVerdict.Dropped) }
                collapseChain(scopeId, ABANDONED_WIRE)
                chainsByToken.remove(requestKey)
                val owned = operation.ownedResponse
                operation.ownedResponse = null
                // processUpdates() mutates pts/seq without locking
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
                    freeChainResponse(chainResponse, owned)
                }
            }
            chainsByToken[requestKey] = scopeId
            // armed after the queue hop, so an app-side backlog isn't charged to the plugins
            operation = RpcChain(scopeId, connectionsManager, chain, tlName, request, optimisticMessages, params, currentAccount, finalize)
            operation.guid = guidByToken.remove(requestKey) ?: 0
            chains[scopeId] = operation
            operation.deadline.resume()
            dispatchChain(operation, 0, request, finalize)
        }
        return true
    }

    private fun unintercepted(request: TLObject): Boolean {
        takeOptimisticMessages(request)?.let { PluginSendHold.release(it.account, it.messages) }
        return false
    }

    private fun takeOptimisticMessages(request: TLObject): OptimisticMessages? =
        synchronized(optimisticMessagesByRequest) {
            optimisticMessagesByRequest.remove(request).also { hasOptimistic = optimisticMessagesByRequest.isNotEmpty() }
        }

    /**
     * the token reaches native only once the passthrough sends it, so stock's `cancelRequest` finds
     * nothing. The app is not answered: stock drops a cancelled request's delegate too.
     * Runs inside `cancelRequest`'s stageQueue runnable, ordered behind the app's `sendRequestInternal`.
     */
    @JvmStatic
    fun onRequestCancelled(account: Int, requestToken: Int, notifyServer: Boolean, onCancelled: Runnable?) {
        if (!hasInterceptors) return
        EngineDispatch.scheduler.postRunnable {
            cancelChain(accountKey(account, requestToken), notifyServer, onCancelled)
        }
    }

    /** native only knows requests whose chain already passed through */
    @JvmStatic
    fun onRequestsCancelledForGuid(account: Int, guid: Int) {
        if (!hasInterceptors || guid == 0) return
        EngineDispatch.scheduler.postRunnable {
            val account64 = account.toLong()
            val keys = chainsByToken.keys.filter { key ->
                key ushr 32 == account64 && chains[chainsByToken[key]]?.guid == guid
            }
            // native's own guid cancel notifies too (cancelRequestInternal(_, _, true, ...))
            for (key in keys) cancelChain(key, true, null)
        }
    }

    /** stock binds the guid in native, which never saw an intercepted request's token */
    @JvmStatic
    fun onRequestBoundToGuid(account: Int, requestToken: Int, guid: Int) {
        if (!hasInterceptors || guid == 0) return
        val key = accountKey(account, requestToken)
        EngineDispatch.scheduler.postRunnable {
            val budget = chainsByToken[key]?.let { chains[it] }
            if (budget == null) {
                guidByToken.put(key, guid)
                return@postRunnable
            }
            budget.guid = guid
            // the passthrough raced it; re-issue behind the send
            if (budget.sent != null) {
                Utilities.stageQueue.postRunnable {
                    ConnectionsManager.native_bindRequestToGuid(account, requestToken, guid)
                }
            }
        }
    }

    /**
     * stock's `listenCancel` hangs the callback off native callbacks the passthrough registers, so an
     * unsent request would leave the caller waiting forever (`FileLoadOperation` counts them down)
     */
    private fun cancelChain(key: Long, notifyServer: Boolean, onCancelled: Runnable?) {
        val scopeId = chainsByToken.remove(key) ?: return
        val budget = collapseChain(scopeId, CANCELLED_WIRE)
        budget?.let {
            it.completed = true
            discardVerdict(it.verdict)
            it.verdict = null
            releaseUnowned(it.ownedResponse)
            it.ownedResponse = null
        }
        val sent = budget?.sent
        if (sent == null) {
            onCancelled?.let { Utilities.stageQueue.postRunnable(it) }
            return
        }
        // the delegate that would end the lease is not coming
        endBypassLease(sent)
        val connectionsManager = budget.connectionsManager
        val requestToken = key.toInt()
        Utilities.stageQueue.postRunnable {
            if (!sent.reachedNative) {
                onCancelled?.run()
                return@postRunnable
            }
            // the send won the race with stock's cancel; cancelling a token native holds is idempotent
            connectionsManager.cancelRequest(requestToken, notifyServer, onCancelled)
        }
    }

    /** empty [scope] is the raw form, where each method is its own grant scope */
    private fun registerIntercept(
        session: PluginSession,
        methods: Array<String>,
        callbackId: Int,
        scope: String,
        strict: Boolean,
        filterJson: String,
    ): String? {
        for (method in methods) {
            takeoverRefusal(session.permissions, method)?.let { return it }
        }
        if (scope.isNotEmpty()) {
            if (!session.permissions.has(scope)) {
                return PluginWire.encodeNotGranted(scope)
            }
        } else for (method in methods) {
            if (!session.permissions.allows("interceptRpc", method, ScopeMatch.EXACT)) {
                return PluginWire.encodeNotGranted("interceptRpc", method)
            }
        }
        val filter = if (filterJson.isEmpty()) {
            null
        } else try {
            val json = JSONObject(filterJson)
            val regex = json.optJSONObject("text")
            val flags = regex?.optString("flags").orEmpty()
            if (flags.any { it !in "dgimsuy" }) {
                return PluginWire.encodePluginError("invalid-argument", "unsupported regular expression flags '$flags'")
            }
            var patternFlags = 0
            if ('i' in flags) patternFlags = patternFlags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            if ('m' in flags) patternFlags = patternFlags or Pattern.MULTILINE
            if ('s' in flags) patternFlags = patternFlags or Pattern.DOTALL
            if ('u' in flags) patternFlags = patternFlags or Pattern.UNICODE_CHARACTER_CLASS
            SendFilter(
                regex?.getString("source")?.let { Pattern.compile(it, patternFlags) },
                'y' in flags,
                json.optBoolean("isEdit").takeIf { json.has("isEdit") },
            )
        } catch (e: PatternSyntaxException) {
            return PluginWire.encodePluginError("invalid-argument", "invalid text regular expression: ${e.description}")
        } catch (e: Exception) {
            return PluginWire.encodePluginError("invalid-argument", "invalid send filter: ${e.message ?: e.toString()}")
        }
        val updated = interceptorsByMethod.toMutableMap()
        // a repeated method would run the middleware twice per `next()` and release the scope twice
        for (method in methods.toSet()) {
            updated[method] = (updated[method].orEmpty()) + Interceptor(session, callbackId, strict, scope, filter)
        }
        publishInterceptors(updated)
        return null
    }

    private fun unregisterIntercept(session: PluginSession, callbackId: Int) {
        publishInterceptors(
            interceptorsByMethod
                .mapValues { (_, list) -> list.filter { it.session !== session || it.callbackId != callbackId } }
                .filterValues { it.isNotEmpty() }
        )
    }

    fun refreshChainOrder() {
        EngineDispatch.scheduler.postRunnable { publishInterceptors(interceptorsByMethod) }
    }

    /** `common.d.ts` promises the user's drag order. Stable sort keeps one plugin's stages in registration order */
    private fun publishInterceptors(updated: Map<String, List<Interceptor>>) {
        val order = PluginManager.orderIndex()
        interceptorsByMethod = updated.mapValues { (_, list) ->
            list.sortedBy { order[it.session.plugin] ?: Int.MAX_VALUE }
        }
        hasInterceptors = interceptorsByMethod.isNotEmpty()
    }

    private fun markBypassed(request: TLObject) {
        synchronized(bypassed) {
            bypassed[request] = (bypassed[request] ?: 0) + 1
            hasBypass = true
        }
    }

    private fun isBypassed(request: TLObject): Boolean =
        synchronized(bypassed) { bypassed.containsKey(request) }

    internal fun releaseBypass(request: TLObject) {
        synchronized(bypassed) {
            val count = bypassed[request] ?: return
            if (count > 1) bypassed[request] = count - 1 else bypassed.remove(request)
            hasBypass = bypassed.isNotEmpty()
        }
    }

    /**
     * plugin-originated requests skip every chain, or a rewriting plugin and a sending plugin loop.
     * The lease holds until the delegate answers, after which stock cannot re-send this instance.
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
     * install-time validation only sees the scopes a grant names, so an unscoped `invokeRpc` grant
     * would otherwise reach `auth.exportLoginToken`
     */
    private fun takeoverRefusal(permissions: desu.inugram.core.plugins.PluginPermissions, method: String): String? {
        if (!GrantCatalog.isTakeoverMethod(method)) return null
        if (permissions.has("unsafe.disableApiFiltering")) return null
        return PluginWire.encodePluginError("forbidden", "'$method' is an account-takeover method and is never available to plugins")
    }

    private fun invokeRefusal(session: PluginSession, tlName: String): String? {
        takeoverRefusal(session.permissions, tlName)?.let { return it }
        if (!session.permissions.allows("invokeRpc", tlName, ScopeMatch.EXACT)) {
            return PluginWire.encodeNotGranted("invokeRpc", tlName)
        }
        return null
    }

    private fun dispatchChain(
        operation: RpcChain,
        index: Int,
        request: TLObject,
        finalize: (TLObject?, TLRPC.TL_error?, Long) -> Unit,
    ) {
        if (index >= operation.chain.size) {
            // the app was already answered (or deliberately not, on cancel), and nothing could free this send
            val armed = chains[operation.scopeId] ?: return
            val media = armed.media
            if (media != null) collectTexts(request)?.firstOrNull()?.let {
                media.caption = it.text
                media.entities = it.entities
            }
            if (media != null && armed.optimisticMessages?.messages?.size == 1) {
                armed.media = null
                armed.verdict = ChainVerdict.TakenOver(media)
                finalize(null, null, operation.connectionsManager.currentTimeMillis)
                return
            }
            pauseChainTimer(operation.scopeId)
            val sent = SentRequest(request)
            armed.sent = sent
            sendPassthrough(operation.connectionsManager, operation.account, sent, operation.params, armed.guid, armed.optimisticMessages) { response, error, responseTime ->
                endBypassLease(sent)
                val budget = chains[operation.scopeId]
                if (budget == null) {
                    releaseUnowned(response)
                    return@sendPassthrough
                }
                if (response != null) budget.ownedResponse = response
                budget.passthrough = PassthroughResult(response, error, responseTime)
                resumeChainTimer(operation.scopeId)
                finalize(response, error, responseTime)
            }
            return
        }
        val interceptor = operation.chain[index]
        val session = interceptor.session
        if (!session.canDispatch()) {
            dispatchChain(operation, index + 1, request, finalize)
            return
        }
        val engine = session.engine
        val tl = session.tl
        val dispatchId = nextDispatchId++
        val method = operation.method
        pendingDispatches[dispatchId] =
            PendingDispatch(session, operation, index, request, finalize)
        chains[operation.scopeId]?.stages?.add(dispatchId)
        engine.dispatchRpc(
            interceptor.callbackId,
            dispatchId,
            method,
            operation.account,
            tl.mintWireForScope(request, operation.scopeId),
        )
    }

    private fun pauseChainTimer(scopeId: Long) {
        chains[scopeId]?.deadline?.pause()
    }

    private fun resumeChainTimer(scopeId: Long) {
        chains[scopeId]?.deadline?.resume()
    }

    /**
     * deepest first, each removed from [pendingDispatches] before its engine is told, so a rejection
     * continuation finds nothing. Handles are released only after all are abandoned, or that
     * continuation reads handle-expired off its own request.
     */
    private fun collapseChain(scopeId: Long, reasonWire: String): RpcChain? {
        val budget = chains.remove(scopeId) ?: return null
        budget.media?.upload?.discard()
        budget.media = null
        budget.deadline.cancel()
        for (dispatchId in budget.stages.reversed()) {
            val pending = pendingDispatches.remove(dispatchId) ?: continue
            pending.session.takeIf { it.isCurrent() }?.engine?.abandonDispatch(dispatchId, reasonWire)
        }
        for (interceptor in budget.chain) interceptor.session.tl.releaseScope(scopeId)
        // stock's deferred free of the serialized request. On stageQueue, behind any send this chain queued
        // there, or the buffers go back mid-serializeToStream. The lease stays: a collapse doesn't end the flight
        budget.sent?.cancelled = true
        // a middleware may pass next() its own request, and disableFree went on that instance
        val sent = budget.sent?.request?.takeIf { it !== budget.request }
        Utilities.stageQueue.postRunnable {
            releaseUnowned(budget.request)
            releaseUnowned(sent)
        }
        return budget
    }

    /** settling a stage answers upward; the stages below would keep advancing to the real send */
    private fun abandonBelow(dispatchId: Long, pending: PendingDispatch, reasonWire: String) {
        val budget = chains[pending.operation.scopeId] ?: return
        val at = budget.stages.indexOf(dispatchId)
        if (at < 0) return
        val below = budget.stages.subList(at + 1, budget.stages.size)
        for (deeperId in below.reversed()) {
            val deeper = pendingDispatches.remove(deeperId) ?: continue
            deeper.session.takeIf { it.isCurrent() }?.engine?.abandonDispatch(deeperId, reasonWire)
        }
        below.clear()
    }

    /**
     * only the deepest stage is named; the rest wait in `await next()`. Fails rather than falling
     * through: stages already rewrote the request, so passing it on would make a stall an interceptor bypass.
     * Skipped once the passthrough answered, or a committed send is reported failed and duplicated on retry.
     */
    private fun expireChain(scopeId: Long) {
        val running = chains[scopeId]?.stages?.reversed()?.firstNotNullOfOrNull { pendingDispatches[it] }
        val budget = collapseChain(scopeId, TIMEOUT_WIRE) ?: return
        (running?.session?.log ?: PluginLog.HOST).w("rpc", "'${budget.method}' ran past the chain's ${budget.deadlineMillis}ms budget")
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
            pending.session.tl.objectFromWire(requestWire)
        } catch (e: Exception) {
            return decodeFailureWire("next()", e)
        }
        // never the method: the app awaits that method's response type, and a swap would make any interceptRpc grant an unscoped send
        val nextMethod = TlNames.classNameToTlName(nextRequest.javaClass)
        if (nextMethod != pending.operation.method) {
            return PluginWire.encodePluginError(
                "forbidden",
                "next(): expected a '${pending.operation.method}' request, got '$nextMethod' - rewrite the request's fields rather than replacing it",
            )
        }
        EngineDispatch.scheduler.postRunnable {
            if (pendingDispatches[dispatchId] !== pending) return@postRunnable
            pending.nextStarted = true
            dispatchChain(
                pending.operation,
                pending.index + 1,
                nextRequest,
            ) { response, error, responseTime ->
                pending.responseTime = responseTime
                EngineDispatch.scheduler.postRunnable {
                    // completing a collapsed stage would mint into a released scope
                    if (pendingDispatches[dispatchId] !== pending) return@postRunnable
                    val result = PassthroughResult(response, error, responseTime)
                    pending.nextResult = result
                    if (pending.skipped) {
                        pendingDispatches.remove(dispatchId)
                        pending.finalize(result.response, result.error, result.time)
                        return@postRunnable
                    }
                    val engine = pending.session.takeIf { it.isCurrent() }?.engine ?: return@postRunnable
                    engine.completeNext(dispatchId, encodeChainResult(pending.session.tl, response, error, pending.operation.scopeId))
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
            response = decodeTlValueOrError(pending.session.tl, resultWire)
        } catch (e: TlResultError) {
            error = e.error
        } catch (e: Exception) {
            handleBadMiddlewareResponse(dispatchId, pending, e, time)
            return
        }
        settleStage(dispatchId, pending) {
            // typed here, where the authoring stage is known: error text alone could be written by any outer stage
            if (isDropVerdict(error) && pending.operation.chain[pending.index].scope == SEND_SCOPE) {
                chains[pending.operation.scopeId]?.verdict = ChainVerdict.Dropped
            }
            pending.finalize(response, error, time)
        }
    }

    private fun isDropVerdict(error: TLRPC.TL_error?): Boolean =
        error?.code == SYNTHETIC_CODE && error.text == DROPPED_TEXT

    /** [ChainVerdict.TakenOver] is only recorded for a send that drew a single message */
    private fun armVerdict(account: Int, ids: List<Int>, verdict: ChainVerdict?) {
        if (verdict == null) return
        for (id in ids) {
            val key = accountKey(account, id)
            sendVerdicts[key] = verdict
            EngineDispatch.scheduler.postRunnable({ discardVerdict(sendVerdicts.remove(key)) }, VERDICT_TTL_MILLIS)
        }
    }

    private fun discardVerdict(verdict: ChainVerdict?) {
        if (verdict is ChainVerdict.TakenOver) verdict.media.upload.discard()
    }

    private fun handleBadMiddlewareResponse(dispatchId: Long, pending: PendingDispatch, cause: Exception, time: Long) {
        EngineDispatch.scheduler.postRunnable {
            if (pendingDispatches[dispatchId] !== pending) return@postRunnable
            val detail = cause.message ?: cause.toString()
            val strict = pending.operation.chain[pending.index].strict
            pending.session.log.w(
                "rpc",
                "'${pending.operation.method}' returned an invalid TL response; " +
                    (if (strict) "failing the RPC" else "skipping the middleware") + ": $detail",
            )
            if (strict) {
                pendingDispatches.remove(dispatchId)
                abandonBelow(dispatchId, pending, ABANDONED_WIRE)
                pending.finalize(null, syntheticError("bad middleware response: $detail"), time)
                return@postRunnable
            }
            val nextResult = pending.nextResult
            if (nextResult != null) {
                pendingDispatches.remove(dispatchId)
                abandonBelow(dispatchId, pending, ABANDONED_WIRE)
                pending.finalize(nextResult.response, nextResult.error, nextResult.time)
            } else if (pending.nextStarted) {
                pending.skipped = true
            } else {
                pendingDispatches.remove(dispatchId)
                dispatchChain(
                    pending.operation,
                    pending.index + 1,
                    pending.request,
                    pending.finalize,
                )
            }
        }
    }

    /**
     * a due expiry timer sorts ahead of a runnable posted now, so a stage removed before the hop would
     * be invisible to [collapseChain] and answer the app twice
     */
    private fun settleStage(dispatchId: Long, pending: PendingDispatch, settle: () -> Unit) {
        EngineDispatch.scheduler.postRunnable {
            if (pendingDispatches.remove(dispatchId) !== pending) return@postRunnable
            // next() without await settles with live stages beneath it, which would keep walking toward the real send
            abandonBelow(dispatchId, pending, ABANDONED_WIRE)
            settle()
        }
    }

    private fun completionTime(pending: PendingDispatch): Long =
        if (pending.responseTime != 0L) pending.responseTime else pending.operation.connectionsManager.currentTimeMillis

    private fun sendPassthrough(
        connectionsManager: ConnectionsManager,
        account: Int,
        sent: SentRequest,
        params: OriginalParams,
        guid: Int,
        optimisticMessages: OptimisticMessages?,
        finalize: (TLObject?, TLRPC.TL_error?, Long) -> Unit,
    ) {
        syncOptimisticMessages(sent.request, optimisticMessages)
        optimisticMessages?.let { PluginSendHold.release(it.account, it.messages) }
        markBypassed(sent.request)
        // stock frees the request after serializing it, gutting the view a parked stage holds. freed in collapseChain
        sent.request.disableFree = true
        Utilities.stageQueue.postRunnable {
            if (sent.cancelled) {
                // the delegate that normally ends the lease will never run
                EngineDispatch.scheduler.postRunnable { endBypassLease(sent) }
                return@postRunnable
            }
            sent.reachedNative = true
            connectionsManager.sendRequestInternal(
                sent.request,
                null,
                RequestDelegateTimestamp { response, error, responseTime ->
                    // stock frees the response when this delegate returns, handing upload.getFile's NativeByteBuffer back to a pool
                    response?.disableFree = true
                    EngineDispatch.scheduler.postRunnable { finalize(response, error, responseTime) }
                },
                params.onQuickAck,
                params.onWriteToSocket,
                params.flags,
                params.datacenterId,
                params.connectionType,
                params.immediate,
                params.requestToken,
            )
            // stock's own bindRequestToGuid would come straight back through onRequestBoundToGuid
            if (guid != 0) ConnectionsManager.native_bindRequestToGuid(account, params.requestToken, guid)
        }
    }

    private fun collectTexts(request: TLObject): List<OptimisticText>? = when (request) {
        is TLRPC.TL_messages_sendMessage -> listOf(OptimisticText(request.message, ArrayList(request.entities)))
        is TLRPC.TL_messages_sendMedia -> listOf(OptimisticText(request.message, ArrayList(request.entities)))
        is TLRPC.TL_messages_sendMultiMedia -> request.multi_media.map { OptimisticText(it.message, ArrayList(it.entities)) }
        is TLRPC.TL_messages_editMessage -> listOf(OptimisticText(request.message, ArrayList(request.entities)))
        else -> null
    }

    private fun syncOptimisticMessages(request: TLObject, optimisticMessages: OptimisticMessages?) {
        optimisticMessages ?: return
        val texts = collectTexts(request) ?: return
        AndroidUtilities.runOnUIThread {
            optimisticMessages.messages.zip(texts).forEach { (message, text) ->
                message.messageOwner.message = text.text
                message.messageOwner.entities = text.entities
                message.messageOwner.flags = if (text.entities.isEmpty()) {
                    message.messageOwner.flags and TLRPC.MESSAGE_FLAG_HAS_ENTITIES.inv()
                } else {
                    message.messageOwner.flags or TLRPC.MESSAGE_FLAG_HAS_ENTITIES
                }
                message.updateMessageText()
                message.resetLayout()
                if (message.type != MessageObject.TYPE_TEXT) message.generateCaption()
                val mode = getChatMode(message.messageOwner, message.scheduled)
                MessagesStorage.getInstance(optimisticMessages.account).putMessages(
                    arrayListOf(message.messageOwner),
                    false,
                    true,
                    false,
                    0,
                    mode,
                    message.messageOwner.quick_reply_shortcut_id.toLong(),
                )
                NotificationCenter.getInstance(optimisticMessages.account).postNotificationName(
                    NotificationCenter.replaceMessagesObjects,
                    message.dialogId,
                    arrayListOf(message),
                )
            }
        }
    }

    private fun invokeRpc(
        session: PluginSession,
        slot: Int,
        startedOn: Int,
        invokeId: Long,
        requestWire: String,
    ): String? {
        val request = try {
            session.tl.objectFromWire(requestWire)
        } catch (e: Exception) {
            return decodeFailureWire("invokeRpc", e)
        }
        val tlName = TlNames.classNameToTlName(request.javaClass)
        invokeRefusal(session, tlName)?.let { return it }
        // last, so a takeover stays refused whatever the slot; a plugin can call `invokeRpc` through any object carrying an `id`
        val account = try {
            invokeAccountOrRefusal("invokeRpc", slot, startedOn)
        } catch (e: Exception) {
            return decodeFailureWire("invokeRpc", e)
        }
        sendInvoke(session, account, request) { response, error ->
            settleInvoke(session, invokeId, "invokeRpc") { encodeInvokeResult(session.tl, response, error) }
        }
        return null
    }

    /**
     * [settle] runs on the engine's runnable and owes it exactly one answer. A response not handed to
     * [TlHandles] must be released there.
     */
    private fun sendInvoke(
        session: PluginSession,
        account: Int,
        request: TLObject,
        settle: (TLObject?, TLRPC.TL_error?) -> Unit,
    ) {
        sendWithoutInterceptors(account, request, 0) { response, error ->
            // freeResources() runs when this delegate returns, before the runnable below mints a handle
            response?.disableFree = true
            EngineDispatch.onEngine(session, onDropped = { releaseUnowned(response) }) {
                settle(response, error)
            }
        }
    }

    private fun invokeAccountOrRefusal(prefix: String, slot: Int, startedOn: Int): Int {
        val account = if (slot == QuickJs.ANY_ACCOUNT) startedOn else slot
        if (!UserConfig.isValidAccount(account)) {
            PluginWire.refuse("invalid-argument", "$prefix: no account in slot $account")
        }
        return account
    }

    /**
     * only the leading constructor is checked, for the takeover refusal. An unknown constructor is sent as
     * written: reaching a method stock has no class for is the point of the api.
     */
    private fun invokeRaw(
        session: PluginSession,
        slot: Int,
        startedOn: Int,
        invokeId: Long,
        method: ByteArray,
    ): String? {
        if (!session.permissions.has(RAW_GRANT)) return PluginWire.encodeNotGranted(RAW_GRANT)
        val request = RawTlRequest(method)
        val constructor = request.constructorId()
            ?: return PluginWire.encodePluginError("invalid-argument", "invokeRaw: a method is at least its 4-byte constructor id")
        // every name for the id is checked: a legacy variant can share it
        for (named in TlTables.getConstructorNames(constructor)) takeoverRefusal(session.permissions, named)?.let { return it }
        val account = try {
            invokeAccountOrRefusal("invokeRaw", slot, startedOn)
        } catch (e: Exception) {
            return decodeFailureWire("invokeRaw", e)
        }
        sendInvoke(session, account, request) { response, error ->
            releaseUnowned(response)
            when {
                error != null -> settleInvoke(session, invokeId, "invokeRaw") { PluginWire.encodeRpcError(error.code, error.text ?: "") }
                response is RawTlResponse -> session.engine.settleBytes(QuickJs.SETTLE_INVOKE, invokeId, response.bytes)
                else -> settleInvoke(session, invokeId, "invokeRaw") { PluginWire.encodeNull() }
            }
        }
        return null
    }

    /** the host keeps no takeout state: the plugin carries the session id and the server refuses forged ones */
    private fun takeout(
        session: PluginSession,
        slot: Int,
        startedOn: Int,
        invokeId: Long,
        op: Int,
        takeoutId: String,
        arg: String,
    ): String? {
        if (!session.permissions.has(TAKEOUT_GRANT)) return PluginWire.encodeNotGranted(TAKEOUT_GRANT)
        val request: TLObject
        val account: Int
        val encode: (TLObject?, TLRPC.TL_error?) -> String
        try {
            when (op) {
                RpcListener.OP_TAKEOUT_INIT -> {
                    request = buildTakeoutInit(JSONObject(arg))
                    encode = ::encodeTakeoutId
                }
                RpcListener.OP_TAKEOUT_FINISH -> {
                    request = TakeoutWrapper(parseTakeoutId(takeoutId), TakeoutFinishRequest(arg == "1"))
                    encode = ::encodeTakeoutFinished
                }

                RpcListener.OP_TAKEOUT_INVOKE -> {
                    val query = session.tl.objectFromWire(arg)
                    val queryName = TlNames.classNameToTlName(query.javaClass)
                    invokeRefusal(session, queryName)?.let { return it }
                    request = TakeoutWrapper(parseTakeoutId(takeoutId), query)
                    encode = { response, error -> encodeInvokeResult(session.tl, response, error) }
                }
                else -> PluginWire.refuse("invalid-argument", "unknown takeout op $op")
            }
            account = invokeAccountOrRefusal("takeout", slot, startedOn)
        } catch (e: Exception) {
            return decodeFailureWire("takeout", e)
        }
        sendInvoke(session, account, request) { response, error ->
            settleInvoke(session, invokeId, "takeout") { encode(response, error) }
        }
        return null
    }

    private fun settleInvoke(session: PluginSession, invokeId: Long, what: String, produce: () -> String) {
        session.engine.settle(QuickJs.SETTLE_INVOKE, invokeId, EngineDispatch.produceWire(what, produce))
    }

    private fun buildTakeoutInit(options: JSONObject): TakeoutInitRequest = TakeoutInitRequest().apply {
        contacts = options.optBoolean("contacts")
        messageUsers = options.optBoolean("messageUsers")
        messageChats = options.optBoolean("messageChats")
        messageMegagroups = options.optBoolean("messageMegagroups")
        messageChannels = options.optBoolean("messageChannels")
        fileMaxSize = options.optLong("fileMaxSize")
        files = fileMaxSize > 0L
    }

    private fun parseTakeoutId(id: String): Long =
        id.toLongOrNull() ?: PluginWire.refuse("invalid-argument", "'$id' is not a takeout session id")

    private fun encodeTakeoutId(response: TLObject?, error: TLRPC.TL_error?): String {
        releaseUnowned(response)
        if (error != null) return PluginWire.encodeRpcError(error.code, error.text ?: "")
        if (response !is TakeoutSession) return PluginWire.encodeNull()
        return PluginWire.encodeLongAsString(response.id)
    }

    private fun encodeTakeoutFinished(response: TLObject?, error: TLRPC.TL_error?): String {
        releaseUnowned(response)
        if (error != null) return PluginWire.encodeRpcError(error.code, error.text ?: "")
        return PluginWire.encodeBool(response is TLRPC.TL_boolTrue)
    }

    private class TlResultError(val error: TLRPC.TL_error) : Exception("${error.code}: ${error.text}")

    private fun decodeFailureWire(prefix: String, e: Exception): String {
        val refused = (e as? PluginRefusal)?.let { PluginWire.decode(it.wire) as? PluginWire.Value.PluginErr }
            ?: return PluginWire.encodePluginError("invalid-argument", "$prefix: ${e.message}")
        return PluginWire.encodePluginError(refused.code, "$prefix: ${refused.message}", refused.grant)
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
        is PluginWire.Value.Json -> tl.constructTlObject(JSONObject(decoded.json))
        else -> throw IllegalArgumentException("unsupported result payload")
    }

    private fun encodeChainResult(tl: TlHandles, response: TLObject?, error: TLRPC.TL_error?, scopeId: Long): String {
        if (error != null) return PluginWire.encodeRpcError(error.code, error.text ?: "")
        if (response == null) return PluginWire.encodeNull()
        return tl.mintWireForScope(response, scopeId)
    }

    private fun encodeInvokeResult(tl: TlHandles, response: TLObject?, error: TLRPC.TL_error?): String {
        if (error != null) {
            releaseUnowned(response)
            return PluginWire.encodeRpcError(error.code, error.text ?: "")
        }
        if (response == null) return PluginWire.encodeNull()
        return tl.mintWireForPlugin(response, readOnly = false, owned = true)
    }

    internal fun releaseUnowned(response: TLObject?) {
        if (response == null) return
        response.disableFree = false
        response.freeResources()
    }

    private fun freeChainResponse(response: TLObject?, owned: TLObject?) {
        releaseUnowned(owned)
        if (response === owned || response == null) return
        // a substituted response still carrying disableFree is owned by the plugin's own table, which frees it
        if (!response.disableFree) response.freeResources()
    }

    private fun syntheticError(message: String): TLRPC.TL_error =
        TLRPC.TL_error().apply { code = SYNTHETIC_CODE; text = message }
}
