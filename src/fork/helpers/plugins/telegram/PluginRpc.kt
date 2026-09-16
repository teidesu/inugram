package desu.inugram.helpers.plugins.telegram

import desu.inugram.helpers.plugins.SessionResource
import desu.inugram.core.plugins.PluginRefusal
import android.os.SystemClock
import android.util.Log
import desu.inugram.core.plugins.BoundedIdentitySet
import desu.inugram.core.plugins.BoundedLru
import desu.inugram.core.plugins.DispatchDeadline
import desu.inugram.core.plugins.PluginWire
import desu.inugram.core.plugins.ScopeMatch
import desu.inugram.core.plugins.TakeoverMethods
import desu.inugram.core.plugins.TlNames
import desu.inugram.core.plugins.TlTables
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.RpcListener
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlHandles
import desu.inugram.helpers.plugins.tl.TlJson
import java.util.Collections
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
import org.telegram.tgnet.tl.TL_update
import org.telegram.ui.ChatActivity

/**
 * Wires `inu.interceptRpc`/`inu.invokeRpc` into the stock request pipeline. The arriving update
 * stream is [PluginUpdates]; the two share only [TlHandles] and this file's queue rules.
 *
 * All chain orchestration happens on [EngineDispatch.scheduler], so there is no locking. Stronger than
 * that: an engine is entered **only from a globalQueue runnable, never from inside a JNI upcall** -
 * `Context::with` takes the runtime's `RefCell`, so re-entering the same engine from a callback it
 * is running is a `BorrowMutError` panicking out of an `extern "system"` fn, i.e. a process abort.
 * One plugin reaches that alone by registering twice for a method. Hence [onNext]/[onComplete] post.
 *
 * The app is always answered from [Utilities.stageQueue], where stock answers it from: the
 * delegate-less `Updates` tail runs `processUpdates`, which mutates pts/seq from that queue with no
 * locking.
 *
 * The two TL sources here differ in lifetime and mutability: writable and scope-invalidated in bulk
 * for an intercept chain, writable and plugin-lifetime for an `invokeRpc` result (nobody app-side
 * reads it, so `disableFree` moves the free to the table).
 */
object PluginRpc : SessionResource {
    private class Interceptor(
        val session: PluginSession,
        val callbackId: Int,
        val strict: Boolean,
        val scope: String,
        /** presentation waits for the verdict up to its grace deadline */
        val filter: SendFilter?,
    )

    private class SendFilter(val text: Pattern?, val textIsSticky: Boolean, val isEdit: Boolean?) {
        fun matches(method: String, request: TLObject): Boolean {
            if (isEdit != null && isEdit != (method == "messages.editMessage")) return false
            return matchesText(
                when (request) {
                    is TLRPC.TL_messages_sendMessage -> request.message
                    is TLRPC.TL_messages_sendMedia -> request.message
                    is TLRPC.TL_messages_sendMultiMedia -> request.multi_media.firstOrNull()?.message
                    is TLRPC.TL_messages_editMessage -> request.message
                    else -> null
                }
            )
        }

        /** everything this can decide before the request exists, which is everything but the method */
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
        /** the draft this send is about to clear, when it has one, so a verdict can still clear it */
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
     * one top-level dispatch's deadline, shared by its whole chain. Send-message chains get a
     * longer budget because their promise is the user's visible pending send. The deadline is
     * suspended while a request is really in flight, so a slow server is not charged to plugins.
     *
     * [SystemClock.uptimeMillis] because that is what `Handler.postDelayed` counts in: it does not
     * advance in deep sleep, and any other clock would drift from the armed timer.
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
        // the passthrough response stock's free was suppressed for; that chain's finalize is the only consumer
        var ownedResponse: TLObject? = null
        // what that chain decided, until its finalize reads it. plugin queue only
        var verdict: ChainVerdict? = null

        var passthrough: PassthroughResult? = null

        var guid = 0

        var sent: SentRequest? = null

        /** what `message.setMedia()` named, put on the local message instead of a passthrough */
        var media: PluginSendMorph.Media? = null
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
    private const val SEND_SCOPE = "interceptSendMessage"
    private val SEND_METHODS = arrayOf("messages.sendMessage", "messages.sendMedia", "messages.sendMultiMedia")
    private const val RAW_GRANT = "unsafe.invokeRaw"
    private const val TAKEOUT_GRANT = "takeout"
    private const val RPC_CHAIN_BUDGET_MS = 10_000L
    private const val SEND_CHAIN_BUDGET_MS = 60_000L

    private const val GUID_MEMORY = 512
    private const val SYNTHETIC_CODE = -1000
    /**
     * what a verdict is dressed as for the app, whose request delegate has no other vocabulary for
     * "this send did not happen". Minted where the app is answered, and read by nothing: the error
     * carries a verdict to the composer's error path, it does not name one.
     */
    private const val MORPHED_TEXT = "MESSAGE_MORPHED_BY_PLUGIN"
    private const val DROPPED_TEXT = "MESSAGE_DROPPED_BY_PLUGIN"

    /**
     * how long an armed verdict waits for the error path it was armed for. Nothing but a queue hop
     * stands between the two, so this is only reached when that path never ran at all, and it is
     * what keeps neither the entry nor a `setMedia` copy waiting on a send that is not coming.
     */
    private const val VERDICT_TTL_MILLIS = 30_000L
    private const val TIMEOUT_TEXT = "INTERCEPTOR_TIMEOUT"
    private const val ABANDONED_TEXT = "INTERCEPTOR_ABANDONED"
    private const val CANCELLED_TEXT = "INTERCEPTOR_CANCELLED"
    internal val TIMEOUT_WIRE = PluginWire.encodeRpcError(SYNTHETIC_CODE, TIMEOUT_TEXT)
    internal val ABANDONED_WIRE = PluginWire.encodeRpcError(SYNTHETIC_CODE, ABANDONED_TEXT)
    private val CANCELLED_WIRE = PluginWire.encodeRpcError(SYNTHETIC_CODE, CANCELLED_TEXT)

    // fast-path gate read from arbitrary stageQueue threads before paying for a globalQueue hop
    @Volatile private var hasInterceptors = false

    // published copy-on-write, so reads off globalQueue need no synchronization
    @Volatile private var interceptorsByMethod: Map<String, List<Interceptor>> = emptyMap()

    // its own space: rust keeps interceptRpc and interceptUpdate dispatches in different tables
    private var nextDispatchId = 1L
    private val pendingDispatches = HashMap<Long, PendingDispatch>()
    private val chains = HashMap<Long, RpcChain>()

    // [tokenKey] -> scope id, so an app-side cancel can find the chain still walking for that request
    private val chainsByToken = HashMap<Long, Long>()
    // [tokenKey] -> guid, for binds landing before there was a chain to hang them on: the app binds
    // synchronously, usually before the request reached `sendRequestInternal`. bounded so a burst
    // of requests cannot grow it while their chains wait to be armed
    private val guidByToken = BoundedLru<Long, Int>(GUID_MEMORY)

    /**
     * what a chain decided about the request itself, as opposed to what it answered for it.
     *
     * A *response* is a middleware's to substitute - catching what `next()` rejected with and
     * answering something else is what middleware is for. A *verdict* is not: it is the decision
     * that this send is not happening, and by the time it is made the app already owes the local
     * message an unwind. So it is recorded on the chain where it is decided and read where the app
     * is answered, and a stage above the deciding one cannot revoke it by swallowing a rejection.
     */
    private sealed interface ChainVerdict {
        object Dropped : ChainVerdict

        class TakenOver(val media: PluginSendMorph.Media) : ChainVerdict
    }

    /**
     * [sendKey] -> the verdict the composer's error path owes a local message. Armed as the app is
     * answered and consumed by [handleDroppedSend]; what the error itself says is only how that
     * path is reached, never how it decides.
     */
    private val sendVerdicts = ConcurrentHashMap<Long, ChainVerdict>()

    /** every account mints its local ids out of the same descending sequence, so an id alone names two messages */
    private fun sendKey(account: Int, id: Int): Long = (account.toLong() shl 32) or (id.toLong() and 0xffffffffL)

    // requests we re-issued (chain passthrough / invokeRpc), which must not re-enter maybeIntercept.
    // a *lease* rather than something the first send consumes: on CONNECTION_NOT_INITED stock
    // re-sends the very object with a fresh token and no delegate call, so a lease ending at the
    // first send would let the retry start a second chain over a request the first still holds -
    // every middleware twice, and the nested finalize freeing the response the outer one will walk.
    // counted, because one instance can be leased twice
    private val bypassed = IdentityHashMap<TLObject, Int>()
    private val optimisticMessagesByRequest = IdentityHashMap<TLObject, OptimisticMessages>()

    @JvmStatic
    fun bindOptimisticMessage(request: TLObject, account: Int, message: MessageObject) =
        bindOptimisticMessages(request, account, arrayListOf(message))

    @JvmStatic
    fun bindOptimisticMessages(request: TLObject, account: Int, messages: ArrayList<MessageObject>) {
        // a send a plugin asked the composer to draw is still a plugin's own write, so it takes the
        // same lease [sendWithoutInterceptors] takes, released when that send settles
        if (messages.any { PluginOptimisticSend.claimRequest(request, it) }) markBypassed(request)
        val method = TlNames.classNameToTlName(request.javaClass)
        if (interceptorsByMethod[method].orEmpty().none { it.scope == SEND_SCOPE && it.filter?.matches(method, request) != false }) {
            return PluginSendHold.release(account, messages)
        }
        val draft = messages.firstOrNull()?.let { draftAwaitingClear(account, it) }
        synchronized(optimisticMessagesByRequest) {
            optimisticMessagesByRequest[request] = OptimisticMessages(account, messages.toList(), draft)
        }
    }

    /**
     * stock clears the dialog's draft a few lines after the call this is made from, so this is the
     * last moment a draft that is about to go can still be seen. Only its key is kept: what a
     * verdict owes the server is an empty draft, never the one the user typed.
     */
    private fun draftAwaitingClear(account: Int, message: MessageObject): DraftKey? {
        val dialogId = message.dialogId
        if (dialogId == 0L) return null
        val threadId = draftThreadOf(message.messageOwner)
        val existing = MediaDataController.getInstance(account).getDraft(dialogId, threadId) ?: return null
        return if (existing is TLRPC.TL_draftMessageEmpty) null else DraftKey(dialogId, threadId)
    }

    /** bit 1 of a reply header is what carries the topic's root id, which is the draft's own thread */
    private fun draftThreadOf(message: TLRPC.Message?): Long {
        val replyTo = message?.reply_to ?: return 0L
        return if ((replyTo.flags and 2) != 0) replyTo.reply_to_top_id.toLong() else 0L
    }

    /**
     * stock clears the local draft as it hands a send to the network, but the server's own copy
     * goes with the `clear_draft` flag on the request itself - the one a verdict means never goes
     * out. The re-send a takeover makes carries `clear_draft = false` too, stock reading a retry as
     * a send whose draft went with the first attempt. So the server is told here instead, or the
     * draft comes back on the next sync and the command the user sent reappears in the composer.
     */
    private fun clearServerDraft(account: Int, draft: DraftKey) {
        AndroidUtilities.runOnUIThread {
            MediaDataController.getInstance(account)
                .saveDraft(draft.dialogId, draft.threadId, "", null, null, null, null, 0L, false, true)
        }
    }

    /**
     * whether a middleware could still claim a send the composer has only just minted, decided off
     * what a [SendFilter] can read before there is a request: [PluginSendHold] parks a draw on it.
     */
    internal fun maySendBeIntercepted(text: String?): Boolean {
        if (!hasInterceptors) return false
        return SEND_METHODS.any { method ->
            interceptorsByMethod[method].orEmpty().any {
                it.session.canDispatch() && it.scope == SEND_SCOPE && it.filter?.matchesSend(text) != false
            }
        }
    }

    /**
     * `message.setMedia()`: the chain this dispatch belongs to puts the media on its local message
     * instead of sending the request it is walking. Refused when the dispatch is not this plugin's
     * own live one, when there is no local message to put it on - a send a plugin built itself has
     * none - and when that message is itself the answer to a `setMedia`, which is what stops a
     * middleware answering its own re-send forever.
     */
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
        // a second setMedia replaces the first, whose copy nothing will claim
        budget.media?.upload?.discard()
        budget.media = media
    }

    /** whether a chain's verdict, rather than a failed send, is what this message is being answered with */
    @JvmStatic
    fun handleDroppedSend(
        helper: SendMessagesHelper,
        account: Int,
        message: TLRPC.Message,
        scheduled: Boolean,
    ): Boolean {
        val verdict = sendVerdicts.remove(sendKey(account, message.id)) ?: return false
        return when (verdict) {
            is ChainVerdict.Dropped -> {
                removeDroppedMessage(helper, account, message, scheduled)
                true
            }
            is ChainVerdict.TakenOver -> PluginSendMorph.takeOver(helper, account, message, scheduled, verdict.media)
        }
    }

    /** the same for an edit, whose unwind is the composer putting the message back as it was */
    @JvmStatic
    fun handleDroppedEdit(account: Int, messageId: Int): Boolean =
        sendVerdicts.remove(sendKey(account, messageId)) is ChainVerdict.Dropped

    @JvmStatic
    fun handleDroppedSends(
        helper: SendMessagesHelper,
        account: Int,
        messages: ArrayList<MessageObject>,
        scheduled: Boolean,
    ): Boolean =
        // `count`, not `any`: every message owed a verdict must get one, short-circuiting skips the rest
        messages.count { handleDroppedSend(helper, account, it.messageOwner, scheduled) } > 0

    private fun removeDroppedMessage(helper: SendMessagesHelper, account: Int, message: TLRPC.Message, scheduled: Boolean) {
        val mode = when {
            scheduled -> ChatActivity.MODE_SCHEDULED
            MessageObject.isWelcomeMessage(message) -> ChatActivity.MODE_WELCOME_MESSAGES
            message.quick_reply_shortcut_id != 0 || message.quick_reply_shortcut != null -> ChatActivity.MODE_QUICK_REPLIES
            else -> ChatActivity.MODE_DEFAULT
        }
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
        // snapshot: the account-less `inu.invokeRpc` names no account, and a plugin's requests must not jump slots on a switch
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
     * drops the plugin's interceptors and fails any dispatch waiting on its own middleware, so
     * [maybeIntercept]'s finalize-exactly-once contract still holds. The plugin's handle table
     * outlives this and is released by [TlHandles.releaseAll], since the abandons below reject
     * inside this plugin too and a continuation touching its own request view must not find every
     * field expired.
     */
    override fun detach(session: PluginSession) {
        publishInterceptors(
            interceptorsByMethod
                .mapValues { (_, list) -> list.filter { it.session !== session } }
                .filterValues { it.isNotEmpty() }
        )
        // ascending dispatch id is chain order, so each chain's shallowest stage takes the ones below it down
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
        // a leased request is ours however the entry got here, including stock's own re-send
        if (isBypassed(request)) return unintercepted(request)
        if (!hasInterceptors) return unintercepted(request)
        val tlName = TlNames.classNameToTlName(request.javaClass)
        val chain = interceptorsByMethod[tlName]
            ?.filter { it.filter?.matches(tlName, request) != false }
            ?.takeIf { it.isNotEmpty() }
            ?: return unintercepted(request)
        val optimisticMessages = synchronized(optimisticMessagesByRequest) { optimisticMessagesByRequest.remove(request) }
        val params = OriginalParams(flags, datacenterId, connectionType, immediate, requestToken, onQuickAck, onWriteToSocket)
        val requestKey = tokenKey(currentAccount, requestToken)
        EngineDispatch.scheduler.postRunnable {
            val scopeId = TlHandles.newScope()
            // what a verdict on this chain unwinds: the messages the composer drew, or - for an
            // edit, which draws none - the one the request names, read before a stage can rewrite it
            val unwound = optimisticMessages?.messages?.map { it.id }
                ?: listOfNotNull((request as? TLRPC.TL_messages_editMessage)?.id)
            lateinit var operation: RpcChain
            val finalize: (TLObject?, TLRPC.TL_error?, Long) -> Unit = finalize@{ chainResponse, chainError, responseTime ->
                if (operation.completed) return@finalize
                operation.completed = true
                // the chain's own decision, told to the app here rather than through the value the
                // stages above answered with - which is theirs to substitute, and this is not
                val verdict = operation.verdict
                operation.verdict = null
                armVerdict(optimisticMessages?.account ?: currentAccount, unwound, verdict)
                if (verdict != null) optimisticMessages?.draft?.let { clearServerDraft(optimisticMessages.account, it) }
                val response = if (verdict == null) chainResponse else null
                // the composer's error path is the one that unwinds a local send, and it needs an error to run
                val error = when (verdict) {
                    null -> chainError
                    is ChainVerdict.Dropped -> syntheticError(DROPPED_TEXT)
                    is ChainVerdict.TakenOver -> syntheticError(MORPHED_TEXT)
                }
                // a drop is the one outcome whose local message is about to be deleted rather than drawn
                optimisticMessages?.let { PluginSendHold.settle(it.account, it.messages, verdict !is ChainVerdict.Dropped) }
                // earns its keep when the *first* stage's plugin is stopped, the ones below it still running
                collapseChain(scopeId, ABANDONED_WIRE)
                chainsByToken.remove(requestKey)
                val owned = operation.ownedResponse
                operation.ownedResponse = null
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
                    freeChainResponse(chainResponse, owned)
                }
            }
            chainsByToken[requestKey] = scopeId
            // armed after the queue hop, so an app-side backlog isn't charged to the plugins
            operation = RpcChain(scopeId, connectionsManager, chain, tlName, request, optimisticMessages, params, currentAccount, finalize)
            operation.guid = takeGuid(requestKey)
            chains[scopeId] = operation
            operation.deadline.resume()
            dispatchChain(operation, 0, request, finalize)
        }
        return true
    }

    /** no chain will walk this request, so a draw parked on the chance of one is owed its release */
    private fun unintercepted(request: TLObject): Boolean {
        val optimistic = synchronized(optimisticMessagesByRequest) { optimisticMessagesByRequest.remove(request) }
        optimistic?.let { PluginSendHold.release(it.account, it.messages) }
        return false
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
        EngineDispatch.scheduler.postRunnable {
            cancelChain(tokenKey(account, requestToken), notifyServer, onCancelled)
        }
    }

    /** native only knows the requests whose chain already passed through; the ones still walking are cancelled here */
    @JvmStatic
    fun onRequestsCancelledForGuid(account: Int, guid: Int) {
        if (!hasInterceptors || guid == 0) return
        EngineDispatch.scheduler.postRunnable {
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
        EngineDispatch.scheduler.postRunnable {
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
        // one registration is one stage however often its list names a method - a repeat would run
        // the middleware twice per request off a single `next()`, against one budget, and
        // `releaseScope` twice. Same reason the update registrations take `types.toSet()`
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

    /**
     * chain order is derived here on every publish rather than being registration order, since
     * `common.d.ts` promises the order the user drags. The sort is stable, so a plugin registering
     * twice keeps its own stages in registration order.
     */
    private fun publishInterceptors(updated: Map<String, List<Interceptor>>) {
        val order = PluginManager.orderIndex()
        interceptorsByMethod = updated.mapValues { (_, list) ->
            list.sortedBy { order[it.session.plugin] ?: Int.MAX_VALUE }
        }
        hasInterceptors = interceptorsByMethod.isNotEmpty()
    }

    internal fun releasePluginSend(request: TLObject) = releaseBypass(request)

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
    private fun takeoverRefusal(permissions: desu.inugram.core.plugins.PluginPermissions, method: String): String? {
        if (!TakeoverMethods.isBlocked(method)) return null
        if (permissions.has("unsafe.disableApiFiltering")) return null
        return PluginWire.encodePluginError("forbidden", "'$method' is an account-takeover method and is never available to plugins")
    }

    private fun dispatchChain(
        operation: RpcChain,
        index: Int,
        request: TLObject,
        finalize: (TLObject?, TLRPC.TL_error?, Long) -> Unit,
    ) {
        if (index >= operation.chain.size) {
            // the chain is gone, so the app has already been answered - or, for a cancel,
            // deliberately not. Sending anyway would also strand this [SentRequest]: nothing holds
            // it, so the collapse could neither cancel it, end its lease, nor free the request
            val armed = chains[operation.scopeId] ?: return
            val media = armed.media
            // the caption is the send's own text as the chain left it, which is where a media request carries one
            if (media != null) captionOf(request)?.let { (text, entities) ->
                media.caption = text
                media.entities = entities
            }
            if (media != null && armed.optimisticMessages?.messages?.size == 1) {
                // the media moves to the verdict, so a collapse no longer owes its copy a delete
                armed.media = null
                armed.verdict = ChainVerdict.TakenOver(media)
                finalize(null, null, operation.connectionsManager.currentTimeMillis)
                return
            }
            pauseChainTimer(operation.scopeId)
            val sent = SentRequest(request)
            armed.sent = sent
            sendPassthrough(operation.connectionsManager, operation.account, sent, operation.params, armed.guid, armed.optimisticMessages) { response, error, responseTime ->
                // whatever it answered, this request cannot reach sendRequestInternal again
                endBypassLease(sent)
                val budget = chains[operation.scopeId]
                if (budget == null) {
                    // the chain collapsed while the request was out, so nothing will consume this response
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
     * abandons every stage deepest first, dropping each from [pendingDispatches] *before* telling
     * its engine, so a rejection continuation re-entering [onNext]/[onComplete] finds nothing. Only
     * once all are abandoned are the scope's handles invalidated - the other order leaves that same
     * continuation reading handle-expired off every field of its own request.
     */
    private fun collapseChain(scopeId: Long, reasonWire: String): RpcChain? {
        val budget = chains.remove(scopeId) ?: return null
        // a setMedia the chain never reached the end of: its copy is owned by nothing else
        budget.media?.upload?.discard()
        budget.media = null
        budget.deadline.cancel()
        for (dispatchId in budget.stages.reversed()) {
            val pending = pendingDispatches.remove(dispatchId) ?: continue
            pending.session.takeIf { it.isCurrent() }?.engine?.abandonDispatch(dispatchId, reasonWire)
        }
        // covers handles stashed across an await; each stage minted into its own plugin's table
        for (interceptor in budget.chain) interceptor.session.tl.releaseScope(scopeId)
        // the free stock does the instant it has serialized a request, deferred to here because no
        // view can read it any more. Keyed on the chain, not the send: a stage that short-circuits
        // owes the free too. On stageQueue, which orders it behind a send this chain may still have
        // queued there - freeing from the plugin queue could hand the buffers back mid-serializeToStream.
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
        Log.w(TAG, "[${running?.session?.manifest?.name}] '${budget.method}' ran past the chain's ${budget.deadlineMillis}ms budget")
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
            decodeTlObject(pending.session.tl, requestWire)
        } catch (e: Exception) {
            return decodeFailureWire("next()", e)
        }
        // next() may rewrite fields but never the method: the app awaits that method's response type, and a swap would turn any interceptRpc grant into an unscoped send primitive
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
                    // once the chain has collapsed this stage is gone, and completing it would mint into a released scope
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
            // read once, here, where the stage that authored it is known: a verdict is typed onto
            // the chain rather than left as an error for the app to recognize by its text, which
            // any stage above this one could have written for itself
            if (isDropVerdict(error) && pending.operation.chain[pending.index].scope == SEND_SCOPE) {
                chains[pending.operation.scopeId]?.verdict = ChainVerdict.Dropped
            }
            pending.finalize(response, error, time)
        }
    }

    /** what the send prelude answers a `drop` with, which only a send interceptor's own stage may say */
    private fun isDropVerdict(error: TLRPC.TL_error?): Boolean =
        error?.code == SYNTHETIC_CODE && error.text == DROPPED_TEXT

    /**
     * a verdict reaches the app as one entry per message it unwinds, which is what the composer's
     * error path is handed. A [ChainVerdict.TakenOver] names exactly one by construction, the chain
     * end having recorded it only for a send that drew a single message.
     */
    private fun armVerdict(account: Int, ids: List<Int>, verdict: ChainVerdict?) {
        if (verdict == null) return
        for (id in ids) {
            val key = sendKey(account, id)
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
            Log.w(
                TAG,
                "[${pending.session.manifest.name}] '${pending.operation.method}' returned an invalid TL response; " +
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
     * drops the stage only once the posted settle runs: a due expiry timer sorts *ahead* of a
     * runnable posted now, so a stage removed before the hop would be invisible to [collapseChain]
     * and would answer the app a second time.
     */
    private fun settleStage(dispatchId: Long, pending: PendingDispatch, settle: () -> Unit) {
        EngineDispatch.scheduler.postRunnable {
            if (pendingDispatches.remove(dispatchId) !== pending) return@postRunnable
            // a stage that called next() without awaiting it settles with live stages beneath it, which would keep walking toward the real send
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
        // the chain kept it, so what the composer minted is drawn - behind the rewrite above, which took the same ui hop
        optimisticMessages?.let { PluginSendHold.release(it.account, it.messages) }
        markBypassed(sent.request)
        // stock frees the request the moment it has serialized it, gutting the writable view a parked stage holds. ownership moves to the chain; the free is in collapseChain
        sent.request.disableFree = true
        Utilities.stageQueue.postRunnable {
            // a cancel landed while this was queued: nothing will read the response, so it must not go out
            if (sent.cancelled) {
                // serialized with every other lease transition; the delegate that normally does it will never run
                EngineDispatch.scheduler.postRunnable { endBypassLease(sent) }
                return@postRunnable
            }
            sent.reachedNative = true
            connectionsManager.sendRequestInternal(
                sent.request,
                null,
                RequestDelegateTimestamp { response, error, responseTime ->
                    // stock frees the response the moment this delegate returns, handing upload.getFile's NativeByteBuffer back to a pool - gutting the object the chain is about to walk up
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
            // native learns the token only now. stock's own bindRequestToGuid is skipped because it would come straight back through onRequestBoundToGuid
            if (guid != 0) ConnectionsManager.native_bindRequestToGuid(account, params.requestToken, guid)
        }
    }

    /** where each of the send methods carries its text, so a caption survives whatever the chain made of it */
    private fun captionOf(request: TLObject): Pair<String, ArrayList<TLRPC.MessageEntity>>? = when (request) {
        is TLRPC.TL_messages_sendMessage -> request.message to ArrayList(request.entities)
        is TLRPC.TL_messages_sendMedia -> request.message to ArrayList(request.entities)
        else -> null
    }

    private fun syncOptimisticMessages(request: TLObject, optimisticMessages: OptimisticMessages?) {
        optimisticMessages ?: return
        val texts = when (request) {
            is TLRPC.TL_messages_sendMessage -> listOf(OptimisticText(request.message, ArrayList(request.entities)))
            is TLRPC.TL_messages_sendMedia -> listOf(OptimisticText(request.message, ArrayList(request.entities)))
            is TLRPC.TL_messages_sendMultiMedia -> request.multi_media.map { OptimisticText(it.message, ArrayList(it.entities)) }
            is TLRPC.TL_messages_editMessage -> listOf(OptimisticText(request.message, ArrayList(request.entities)))
            else -> return
        }
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
                val mode = when {
                    message.scheduled -> ChatActivity.MODE_SCHEDULED
                    MessageObject.isWelcomeMessage(message.messageOwner) -> ChatActivity.MODE_WELCOME_MESSAGES
                    message.messageOwner.quick_reply_shortcut_id != 0 || message.messageOwner.quick_reply_shortcut != null ->
                        ChatActivity.MODE_QUICK_REPLIES
                    else -> ChatActivity.MODE_DEFAULT
                }
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
            decodeTlObject(session.tl, requestWire)
        } catch (e: Exception) {
            return decodeFailureWire("invokeRpc", e)
        }
        val tlName = TlNames.classNameToTlName(request.javaClass)
        takeoverRefusal(session.permissions, tlName)?.let { return it }
        if (!session.permissions.allows("invokeRpc", tlName, ScopeMatch.EXACT)) {
            return PluginWire.encodeNotGranted("invokeRpc", tlName)
        }
        // last, so a takeover method stays refused whichever slot it was aimed at. The slot is not the host's to trust: a plugin can call `invokeRpc` through any object carrying an `id`
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
     * every settled `invokeRpc`-shaped call, whatever built the request. [settle] runs on the
     * engine's own runnable and owes the engine exactly one answer; a path that does not hand its
     * response to [TlHandles] must release it there rather than leaving stock's suppressed free
     * unanswered.
     */
    private fun sendInvoke(
        session: PluginSession,
        account: Int,
        request: TLObject,
        settle: (TLObject?, TLRPC.TL_error?) -> Unit,
    ) {
        sendWithoutInterceptors(account, request, 0) { response, error ->
            // stageQueue, where freeResources() runs the moment this delegate returns - before the runnable below mints a handle. ownership moves here
            response?.disableFree = true
            EngineDispatch.onEngine(session, onDropped = { releaseUnowned(response) }) {
                settle(response, error)
            }
        }
    }

    /** the slot a call names, or the refusal wire for one that names no live account */
    private fun invokeAccountOrRefusal(prefix: String, slot: Int, startedOn: Int): Int {
        val account = if (slot == QuickJs.ANY_ACCOUNT) startedOn else slot
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || !UserConfig.isValidAccount(account)) {
            PluginWire.refuse("invalid-argument", "$prefix: no account in slot $account")
        }
        return account
    }

    /**
     * `inu.invokeRaw`. The bytes are a whole method, so the only thing the host can say about them
     * is the constructor they open with - which is enough to keep the takeover refusal honest, and
     * is all it is used for. A payload naming a constructor no layer this build knows is sent as
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
        // an empty answer is the api working as intended: a constructor no layer this build knows is exactly what a plugin comes here for. Every name claiming the id is asked, since a legacy variant sharing it is named apart from the live constructor
        for (named in TlTables.namesOf(constructor)) takeoverRefusal(session.permissions, named)?.let { return it }
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

    /**
     * every takeout op. A session is its id and nothing else, so the host keeps no state for one:
     * the plugin carries the id it was given, and a forged one is refused by the server rather than
     * by us. What is checked here is that the plugin may open a session at all, and - for a wrapped
     * call - that it could have made that same call unwrapped.
     */
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
                    val query = decodeTlObject(session.tl, arg)
                    val queryName = TlNames.classNameToTlName(query.javaClass)
                    takeoverRefusal(session.permissions, queryName)?.let { return it }
                    if (!session.permissions.allows("invokeRpc", queryName, ScopeMatch.EXACT)) {
                        return PluginWire.encodeNotGranted("invokeRpc", queryName)
                    }
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

    /** already on the plugin queue: [sendInvoke] made the hop, and checked the session on the way */
    private fun settleInvoke(session: PluginSession, invokeId: Long, what: String, produce: () -> String) {
        session.engine.settle(QuickJs.SETTLE_INVOKE, invokeId, EngineDispatch.wireOf(what, produce))
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


    private fun decodeTlObject(tl: TlHandles, wire: String): TLObject = when (val decoded = PluginWire.decode(wire)) {
        is PluginWire.Value.Handle -> {
            if (tl.isReadOnly(decoded.id)) PluginWire.refuse("forbidden", TlHandles.READ_ONLY_MESSAGE)
            tl.resolveTlObject(decoded.id)
                ?: PluginWire.refuse("handle-expired", PluginWire.HANDLE_EXPIRED_MESSAGE)
        }
        is PluginWire.Value.Json -> constructTlObject(JSONObject(decoded.json))
        else -> PluginWire.refuse("invalid-argument", "expected a TL object")
    }

    private fun constructTlObject(json: JSONObject): TLObject {
        val tlName = json.optString("_", "")
        if (tlName.isEmpty()) PluginWire.refuse("invalid-argument", "a constructed TL object needs a '_' type name")
        if (TlTables.idsOf(tlName) == null) PluginWire.refuse("unknown-constructor", "unknown TL type '$tlName'")
        return try {
            TlJson.fromJson(json)
        } catch (e: Exception) {
            PluginWire.refuse("invalid-argument", e.message ?: e.toString())
        }
    }

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
        is PluginWire.Value.Json -> constructTlObject(JSONObject(decoded.json))
        else -> throw IllegalArgumentException("unsupported result payload")
    }

    private fun encodeChainResult(tl: TlHandles, response: TLObject?, error: TLRPC.TL_error?, scopeId: Long): String {
        if (error != null) return PluginWire.encodeRpcError(error.code, error.text ?: "")
        if (response == null) return PluginWire.encodeNull()
        return tl.mintWireForScope(response, scopeId)
    }

    private fun encodeInvokeResult(tl: TlHandles, response: TLObject?, error: TLRPC.TL_error?): String {
        if (error != null) {
            // stock's free was suppressed before we knew it wouldn't be handed over, so nothing else will free it
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
