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
import desu.inugram.helpers.plugins.tl.TlJson
import desu.inugram.helpers.plugins.tl.TlNames
import desu.inugram.helpers.plugins.tl.TlReflect
import android.os.Looper
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.telegram.messenger.KeepAliveJob
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DialogObject
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
import org.telegram.tgnet.tl.TL_update
import org.telegram.tgnet.WriteToSocketDelegate
import org.telegram.ui.ChatActivity


/**
 * Chain ops run on [EngineDispatch.scheduler]. [onNext]/[onComplete] post instead of reentering the
 * engine inside a JNI upcall: reentry double-borrows the runtime's `RefCell` and aborts the process.
 * Responses return on [Utilities.stageQueue], where stock mutates pts/seq.
 */
object PluginRpc : SessionResource {
    private class Interceptor(val session: PluginSession, val callbackId: Int, val strict: Boolean)

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
        /** the media the app drew, item for item with [messages], read before any stage could rewrite the request */
        val drawn: List<Pair<TLRPC.InputMedia, Long>>,
        val draft: DraftKey?,
        /** the media the compose stage gave the send, see `PluginCompose.Bound.splice` */
        val splice: List<JSONObject?>?,
    ) {
        /** the mode the app's send bookkeeping keeps whatever a stage moves the messages to */
        val sentScheduled = messages.firstOrNull()?.scheduled == true
    }

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
        /** the uploaded stage of `interceptSendMessage` may swap the request, see [applyUploaded] */
        var chain: List<Interceptor>,
        var method: String,
        var request: TLObject,
        val optimisticMessages: OptimisticMessages?,
        val params: OriginalParams,
        val account: Int,
        val finalize: (TLObject?, TLRPC.TL_error?, Long) -> Unit,
    ) {
        /** the request the app made, before any stage swapped it */
        val original = request
        val stages = ArrayList<Long>()
        val deadline = DispatchDeadline(EngineDispatch.scheduler, RPC_CHAIN_BUDGET_MS) { expireChain(scopeId) }
        var completed = false
        // response whose stock free was suppressed; freed only by this chain's finalize
        var ownedResponse: TLObject? = null

        /** plugin queue only: the uploaded stage dropped the send */
        var dropped = false

        var passthrough: PassthroughResult? = null

        var guid = 0

        var sent: SentRequest? = null

        /** plugin queue only */
        var restructured: Restructure? = null
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

    /**
     * [slots] are the messages the request's items kept, see [readKeptSlots]; [drawn] is filled on the
     * ui thread with the messages drawn for them, see [restructureOptimisticMessages]
     */
    private class Restructure(val slots: List<MessageObject?>) {
        val drawn = arrayOfNulls<MessageObject>(slots.size)
    }

    private class PassthroughResult(val response: TLObject?, val error: TLRPC.TL_error?, val time: Long)

    /** `TLRPC.Message.flags` bit of `ttl_period`, unnamed in stock */
    private const val TTL_PERIOD_FLAG = 33554432
    private const val RAW_GRANT = "unsafe.invokeRaw"
    private const val TAKEOUT_GRANT = "takeout"
    private const val RPC_CHAIN_BUDGET_MS = 10_000L
    private const val GUID_MEMORY = 512
    private const val SYNTHETIC_CODE = -1000
    private const val DROPPED_TEXT = "MESSAGE_DROPPED_BY_PLUGIN"

    /** only reached when the error path a drop was armed for never runs */
    private const val VERDICT_TTL_MILLIS = 30_000L
    private const val TIMEOUT_TEXT = "INTERCEPTOR_TIMEOUT"
    private const val ABANDONED_TEXT = "INTERCEPTOR_ABANDONED"
    private const val CANCELLED_TEXT = "INTERCEPTOR_CANCELLED"
    /** the flag bits `messages.sendMedia` and `messages.sendMultiMedia` share */
    private const val SHARED_SEND_FLAGS = TLObject.FLAG_0 or TLObject.FLAG_10 or TLObject.FLAG_13 or TLObject.FLAG_17 or TLObject.FLAG_18 or TLObject.FLAG_21
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

    /** the sends the uploaded stage dropped, whose error stock must answer by deleting them */
    private val droppedSends: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** owners of messages the host settled itself, whose send stock is answered for with a drop it must ignore */
    private val settledSends: MutableSet<TLRPC.Message> = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap()))

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
        val splice = PluginCompose.onBound(request, account, messages)
        if (messages.any { PluginOptimisticSend.claimRequest(request, it) }) return markBypassed(request)
        val drawn = readDrawnMedia(request) ?: return
        if (messages.isEmpty()) return
        val peer = PeerSpecs.toMarkedPeerId(MessagesController.getInstance(account), messages[0].dialogId)
        if (splice == null && !PluginSends.mayIntercept(true, PluginSends.Probe(account, peer, collectTexts(request)?.firstOrNull()?.text, true, messages.mapNotNullTo(HashSet()) { PluginSends.readMediaKind(it.messageOwner.media) }, false))) return
        storeOptimisticMessages(request, OptimisticMessages(account, messages.toList(), drawn, draftAwaitingClear(account, messages[0]), splice))
    }

    /** what a media send's requests share, which their TL classes do not */
    private class SendFields(val peer: TLRPC.InputPeer?, val replyTo: TLRPC.InputReplyTo?, val silent: Boolean, val scheduleDate: Int)

    private fun readSendFields(request: TLObject): SendFields? = when (request) {
        is TLRPC.TL_messages_sendMedia -> SendFields(request.peer, request.reply_to, request.silent, request.schedule_date)
        is TLRPC.TL_messages_sendMultiMedia -> SendFields(request.peer, request.reply_to, request.silent, request.schedule_date)
        else -> null
    }

    private fun storeOptimisticMessages(request: TLObject, bound: OptimisticMessages) {
        synchronized(optimisticMessagesByRequest) {
            optimisticMessagesByRequest[request] = bound
            hasOptimistic = true
        }
    }

    internal fun getDialogId(account: Int, peer: TLRPC.InputPeer?): Long =
        if (peer is TLRPC.TL_inputPeerSelf) UserConfig.getInstance(account).clientUserId else DialogObject.getPeerDialogId(peer)

    /**
     * stock clears the draft right after this call. Only the key is kept: a dropped send owes the server an
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
     * the server draft is cleared by the request's `clear_draft`, which a dropped send never sends.
     * Without this the draft returns on the next sync.
     */
    private fun clearServerDraft(account: Int, draft: DraftKey) {
        AndroidUtilities.runOnUIThread {
            MediaDataController.getInstance(account)
                .saveDraft(draft.dialogId, draft.threadId, "", null, null, null, null, 0L, false, true)
        }
    }

    @JvmStatic
    fun handleDroppedSend(
        helper: SendMessagesHelper,
        account: Int,
        message: TLRPC.Message,
        scheduled: Boolean,
    ): Boolean {
        if (settledSends.remove(message)) return true
        if (!droppedSends.remove(accountKey(account, message.id))) return false
        removeDroppedMessage(helper, account, message, scheduled)
        return true
    }

    @JvmStatic
    fun handleDroppedSends(
        helper: SendMessagesHelper,
        account: Int,
        messages: ArrayList<MessageObject>,
        scheduled: Boolean,
    ): Boolean =
        // `count`, not `any`: every message owed a drop must get one
        (!droppedSends.isEmpty() || !settledSends.isEmpty()) &&
            messages.count { handleDroppedSend(helper, account, it.messageOwner, scheduled) } > 0

    private fun getChatMode(message: TLRPC.Message, scheduled: Boolean): Int = when {
        scheduled -> ChatActivity.MODE_SCHEDULED
        MessageObject.isWelcomeMessage(message) -> ChatActivity.MODE_WELCOME_MESSAGES
        message.quick_reply_shortcut_id != 0 || message.quick_reply_shortcut != null -> ChatActivity.MODE_QUICK_REPLIES
        else -> ChatActivity.MODE_DEFAULT
    }

    internal fun removeDroppedMessage(helper: SendMessagesHelper, account: Int, message: TLRPC.Message, scheduled: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return AndroidUtilities.runOnUIThread { removeDroppedMessage(helper, account, message, scheduled) }
        }
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
            override fun onRpcRegister(methods: Array<String>, callbackId: Int, strict: Boolean): String? =
                registerIntercept(session, methods, callbackId, strict)

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

    /** a request no chain takes goes to the network as soon as this returns */
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
        val intercepted = intercept(
            connectionsManager, request, onComplete, onCompleteTimestamp, onQuickAck, onWriteToSocket,
            flags, datacenterId, connectionType, immediate, requestToken, currentAccount,
        )
        if (!intercepted) PluginCompose.onSent(request)
        return intercepted
    }

    private fun intercept(
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
        val optimisticMessages = takeOptimisticMessages(request)
        // includes stock's own re-send of a leased request
        if (isBypassed(request)) return false
        val tlName = TlNames.classNameToTlName(request.javaClass)
        val chain = interceptorsByMethod[tlName].orEmpty()
        if (chain.isEmpty() && optimisticMessages == null) return false
        val params = OriginalParams(flags, datacenterId, connectionType, immediate, requestToken, onQuickAck, onWriteToSocket)
        val requestKey = accountKey(currentAccount, requestToken)
        EngineDispatch.scheduler.postRunnable {
            val scopeId = TlHandles.newScope()
            lateinit var operation: RpcChain
            val finalize: (TLObject?, TLRPC.TL_error?, Long) -> Unit = finalize@{ chainResponse, chainError, responseTime ->
                if (operation.completed) return@finalize
                operation.completed = true
                Utilities.stageQueue.postRunnable { PluginCompose.onSent(request) }
                val restructure = operation.restructured
                val restructured = (chainResponse as? TLRPC.Updates)?.takeIf { restructure != null && chainError == null }
                val dropped = operation.dropped
                if (restructured != null && restructure != null && optimisticMessages != null) {
                    settleRestructured(optimisticMessages, restructure, operation.request, restructured)
                }
                if (dropped && optimisticMessages != null) {
                    armDropped(optimisticMessages)
                    optimisticMessages.draft?.let { clearServerDraft(optimisticMessages.account, it) }
                }
                val response = if (!dropped && restructured == null) chainResponse else null
                // the composer's error path unwinds a local send and needs an error to run
                val error = if (dropped || restructured != null) syntheticError(DROPPED_TEXT) else chainError
                collapseChain(scopeId, ABANDONED_WIRE)
                chainsByToken.remove(requestKey)
                val owned = operation.ownedResponse
                operation.ownedResponse = null
                // processUpdates() mutates pts/seq without locking
                Utilities.stageQueue.postRunnable {
                    restructured?.let { MessagesController.getInstance(currentAccount).processUpdates(it, false) }
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
            operation = RpcChain(scopeId, connectionsManager, chain, tlName, request, optimisticMessages, params, currentAccount, finalize)
            operation.guid = guidByToken.remove(requestKey) ?: 0
            chains[scopeId] = operation
            val dispatch = {
                // armed after the queue hop, so an app-side backlog isn't charged to the plugins
                operation.deadline.resume()
                dispatchChain(operation, 0, operation.request, finalize)
            }
            if (optimisticMessages == null) dispatch() else runUploadedStage(operation, optimisticMessages, dispatch)
        }
        return true
    }

    /**
     * The uploaded stage of `interceptSendMessage`: a media send as it goes out, before any `interceptRpc`
     * stage. Only its text and media are the stages' to change by now. [dispatch] sends it on
     */
    private fun runUploadedStage(operation: RpcChain, optimisticMessages: OptimisticMessages, dispatch: () -> Unit) {
        val account = optimisticMessages.account
        val request = operation.request
        val fields = readSendFields(request) ?: return dispatch()
        val text = collectTexts(request)?.firstOrNull() ?: return dispatch()
        val message = PluginCompose.createMessage(account, getDialogId(account, fields.peer), text.text, text.entities, fields.replyTo, !fields.silent, fields.scheduleDate)
        val drawnItems = optimisticMessages.drawn.map { TlJson.toJson(it.first, PluginCompose.POLICY) }
        var next = 0
        // stock sends files it does not group one request each, and the last of them carries the splice
        val placed = optimisticMessages.splice?.mapNotNull { json -> if (json != null) json to -1 else drawnItems.getOrNull(next)?.let { it to next++ } }
            ?: drawnItems.mapIndexed { at, json -> json to at }
        val items = placed.map { it.first }
        val drawnAt = placed.map { it.second }
        val kinds = drawnAt.map { at -> optimisticMessages.messages.getOrNull(at)?.let { PluginSends.readMediaKind(it.messageOwner.media) } }
        val settle = settle@{ outcome: PluginSends.Outcome ->
            if (chains[operation.scopeId] !== operation) return@settle
            val now = operation.connectionsManager.currentTimeMillis
            when (outcome) {
                PluginSends.Outcome.Dropped -> {
                    operation.dropped = true
                    operation.finalize(null, null, now)
                }
                is PluginSends.Outcome.Failed -> operation.finalize(null, syntheticError(outcome.reason), now)
                is PluginSends.Outcome.Send -> try {
                    if (applyUploaded(operation, optimisticMessages, message, items, drawnAt, outcome)) {
                        resolveAlbumMedia(account, operation.request) { error ->
                            EngineDispatch.scheduler.postRunnable {
                                if (chains[operation.scopeId] !== operation) return@postRunnable
                                if (error == null) dispatch() else operation.finalize(null, error, operation.connectionsManager.currentTimeMillis)
                            }
                        }
                    } else {
                        operation.dropped = true
                        operation.finalize(null, null, now)
                        AndroidUtilities.runOnUIThread { PluginCompose.sendText(account, outcome.message) }
                    }
                } catch (e: PluginRefusal) {
                    val refused = PluginCompose.readRefusal(e)
                    PluginLog.HOST.w("rpc", "a send was not sent: $refused")
                    operation.finalize(null, syntheticError(refused), now)
                }
            }
        }
        if (PluginSends.run(account, true, message, items, kinds, settle)) return
        if (optimisticMessages.splice == null) dispatch() else settle(PluginSends.Outcome.Send(message, items.mapIndexed { at, json -> PluginSends.Item(json, at) }))
    }

    /**
     * Puts the stages' text and media on the request, a single send and an album swapping into each other.
     * An item kept as drawn keeps its media object and random id, which [readKeptSlots] tells it by.
     * False when no media is left
     */
    private fun applyUploaded(
        operation: RpcChain,
        optimisticMessages: OptimisticMessages,
        original: JSONObject,
        originalItems: List<JSONObject>,
        /** which drawn item each of [originalItems] is, -1 for one the app never drew */
        drawnAt: List<Int>,
        outcome: PluginSends.Outcome.Send,
    ): Boolean {
        val edited = outcome.message
        for (key in listOf("peer", "reply", "forward", "topicId", "scheduleDate")) {
            if (!PluginCompose.isSameJson(edited.opt(key), original.opt(key))) {
                PluginWire.refuse("unsupported", "$key: a send's media is already uploaded, so only its text, media and silent may change; intercept the compose stage")
            }
        }
        if (outcome.items.isEmpty()) return false
        val text = edited.getJSONObject("text")
        val caption = text.getString("text")
        val entities = PluginCompose.readEntities(text.optJSONArray("entities"))
        val drawn = optimisticMessages.drawn
        val request = operation.request
        val captions = (request as? TLRPC.TL_messages_sendMultiMedia)?.multi_media?.map { it.message to it.entities }
        val used = HashSet<Int>()
        val singles = outcome.items.mapIndexed { at, item ->
            if (item.json.optString("_") == "localMedia") {
                PluginWire.refuse("unsupported", "media[$at]: a LocalMedia is only taken before the app uploads; intercept the compose stage")
            }
            val from = item.from
            val slot = if (from >= 0) drawnAt[from] else -1
            TLRPC.TL_inputSingleMedia().apply {
                if (slot >= 0 && PluginCompose.isSameJson(item.json, originalItems[from]) && used.add(slot)) {
                    media = drawn[slot].first
                    random_id = drawn[slot].second
                    // stock captions an album on its first item, which carries the send's text
                    captions?.getOrNull(slot)?.takeIf { slot != 0 }?.let { (itemCaption, itemEntities) ->
                        this.message = itemCaption
                        this.entities = itemEntities
                    }
                } else {
                    val parsed = try {
                        TlJson.fromJson(item.json)
                    } catch (e: Exception) {
                        PluginWire.refuse("invalid-argument", "media[$at]: ${e.message}")
                    }
                    media = parsed as? TLRPC.InputMedia ?: PluginWire.refuse("invalid-argument", "media[$at]: expected an InputMedia")
                    random_id = Utilities.random.nextLong()
                }
                if (this.message == null) this.message = ""
                if (at == 0) {
                    this.message = caption
                    this.entities = entities
                }
                TlReflect.syncFlags(this)
            }
        }
        val paid = when (request) {
            is TLRPC.TL_messages_sendMedia -> request.allow_paid_stars
            is TLRPC.TL_messages_sendMultiMedia -> request.allow_paid_stars
            else -> 0L
        }
        // the user agreed to a price for the messages the app drew
        if (paid != 0L && singles.size != drawn.size) {
            PluginWire.refuse("unsupported", "media: this chat charges per message, so a send cannot change how many it is")
        }
        val swapped = createMediaRequest(request, singles)
        if (swapped !== request) {
            operation.request = swapped
            operation.method = TlNames.classNameToTlName(swapped.javaClass)
            operation.chain = interceptorsByMethod[operation.method].orEmpty()
            Utilities.stageQueue.postRunnable { releaseUnowned(request) }
        }
        val silent = edited.optBoolean("silent")
        if (silent != original.optBoolean("silent")) {
            when (swapped) {
                is TLRPC.TL_messages_sendMedia -> swapped.silent = silent
                is TLRPC.TL_messages_sendMultiMedia -> swapped.silent = silent
            }
            AndroidUtilities.runOnUIThread { for (message in optimisticMessages.messages) message.messageOwner.silent = silent }
        }
        return true
    }

    /**
     * [request] itself when its class already fits [singles]. Stock sets fields such as `send_as` on every
     * send and leaves them unflagged, so only flags are read off it, never the fields' presence
     */
    private fun createMediaRequest(request: TLObject, singles: List<TLRPC.TL_inputSingleMedia>): TLObject {
        val single = singles.singleOrNull()
        if (single != null) {
            val media = request as? TLRPC.TL_messages_sendMedia ?: TLRPC.TL_messages_sendMedia().apply {
                val album = request as TLRPC.TL_messages_sendMultiMedia
                silent = album.silent
                background = album.background
                clear_draft = album.clear_draft
                noforwards = album.noforwards
                update_stickersets_order = album.update_stickersets_order
                invert_media = album.invert_media
                peer = album.peer
                reply_to = album.reply_to
                schedule_date = album.schedule_date
                send_as = album.send_as
                quick_reply_shortcut = album.quick_reply_shortcut
                effect = album.effect
                allow_paid_stars = album.allow_paid_stars
                flags = album.flags and SHARED_SEND_FLAGS
            }
            media.media = single.media
            media.random_id = single.random_id
            media.message = single.message
            media.entities = single.entities
            media.flags = if (single.entities.isNullOrEmpty()) media.flags and TLObject.FLAG_3.inv() else media.flags or TLObject.FLAG_3
            return media
        }
        val album = request as? TLRPC.TL_messages_sendMultiMedia ?: TLRPC.TL_messages_sendMultiMedia().apply {
            val media = request as TLRPC.TL_messages_sendMedia
            silent = media.silent
            background = media.background
            clear_draft = media.clear_draft
            noforwards = media.noforwards
            update_stickersets_order = media.update_stickersets_order
            invert_media = media.invert_media
            peer = media.peer
            reply_to = media.reply_to
            schedule_date = media.schedule_date
            send_as = media.send_as
            quick_reply_shortcut = media.quick_reply_shortcut
            effect = media.effect
            allow_paid_stars = media.allow_paid_stars
            flags = media.flags and SHARED_SEND_FLAGS
        }
        album.multi_media = ArrayList(singles)
        return album
    }

    private fun takeOptimisticMessages(request: TLObject): OptimisticMessages? =
        synchronized(optimisticMessagesByRequest) {
            optimisticMessagesByRequest.remove(request).also { hasOptimistic = optimisticMessagesByRequest.isNotEmpty() }
        }

    /** a media send takes a chain for `interceptSendMessage` alone, see [runUploadedStage] */
    private fun mayHaveChains(): Boolean = hasInterceptors || PluginSends.hasInterceptors

    /**
     * the token reaches native only once the passthrough sends it, so stock's `cancelRequest` finds
     * nothing. The app is not answered: stock drops a cancelled request's delegate too.
     * Runs inside `cancelRequest`'s stageQueue runnable, ordered behind the app's `sendRequestInternal`.
     */
    @JvmStatic
    fun onRequestCancelled(account: Int, requestToken: Int, notifyServer: Boolean, onCancelled: Runnable?) {
        if (!mayHaveChains()) return
        EngineDispatch.scheduler.postRunnable {
            cancelChain(accountKey(account, requestToken), notifyServer, onCancelled)
        }
    }

    /** native only knows requests whose chain already passed through */
    @JvmStatic
    fun onRequestsCancelledForGuid(account: Int, guid: Int) {
        if (!mayHaveChains() || guid == 0) return
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
        if (!mayHaveChains() || guid == 0) return
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
            Utilities.stageQueue.postRunnable { PluginCompose.onSent(it.original) }
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

    private fun registerIntercept(session: PluginSession, methods: Array<String>, callbackId: Int, strict: Boolean): String? {
        for (method in methods) {
            takeoverRefusal(session.permissions, method)?.let { return it }
            if (!session.permissions.allows("interceptRpc", method, ScopeMatch.EXACT)) {
                return PluginWire.encodeNotGranted("interceptRpc", method)
            }
        }
        val updated = interceptorsByMethod.toMutableMap()
        // a repeated method would run the middleware twice per `next()` and release the scope twice
        for (method in methods.toSet()) {
            updated[method] = (updated[method].orEmpty()) + Interceptor(session, callbackId, strict)
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

    internal fun isBypassed(request: TLObject): Boolean =
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
            val optimisticMessages = armed.optimisticMessages
            val slots = optimisticMessages?.let { readKeptSlots(request, it) }
            if (optimisticMessages != null && slots != null) {
                refreshRandomIds(request)
                val restructure = Restructure(slots)
                armed.restructured = restructure
                restructureOptimisticMessages(optimisticMessages, slots) {
                    it.toTypedArray().copyInto(restructure.drawn)
                    syncOptimisticMessages(request, optimisticMessages, it)
                }
            }
            pauseChainTimer(operation.scopeId)
            val sent = SentRequest(request)
            armed.sent = sent
            sendPassthrough(operation.connectionsManager, operation.account, sent, operation.params, armed.guid, optimisticMessages.takeIf { slots == null }, armed.original) { response, error, responseTime ->
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
        pendingDispatches[dispatchId] =
            PendingDispatch(session, operation, index, request, finalize)
        chains[operation.scopeId]?.stages?.add(dispatchId)
        engine.dispatchRpc(interceptor.callbackId, dispatchId, operation.method, operation.account, tl.mintWireForScope(request, operation.scopeId))
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
        (running?.session?.log ?: PluginLog.HOST).w("rpc", "'${budget.method}' ran past the chain's ${RPC_CHAIN_BUDGET_MS}ms budget")
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
        settleStage(dispatchId, pending) { pending.finalize(response, error, time) }
    }

    /** stock answers each message's error on its own, and an armed one it never answers expires */
    private fun armDropped(optimisticMessages: OptimisticMessages) {
        for (message in optimisticMessages.messages) {
            val key = accountKey(optimisticMessages.account, message.id)
            droppedSends.add(key)
            EngineDispatch.scheduler.postRunnable({ droppedSends.remove(key) }, VERDICT_TTL_MILLIS)
        }
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
        original: TLObject,
        finalize: (TLObject?, TLRPC.TL_error?, Long) -> Unit,
    ) {
        optimisticMessages?.let { syncOptimisticMessages(sent.request, it, it.messages) }
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
            PluginCompose.onSent(original)
        }
    }

    private fun readDrawnMedia(request: TLObject): List<Pair<TLRPC.InputMedia, Long>>? = when (request) {
        is TLRPC.TL_messages_sendMedia -> listOf(request.media to request.random_id)
        is TLRPC.TL_messages_sendMultiMedia -> request.multi_media.map { it.media to it.random_id }
        else -> null
    }

    /**
     * Stock matches a media send's answer to its messages by random_id, moves each local file onto the
     * media answered for it, and keeps an album's upload and file-reference state in per-item lists. A
     * send whose media a stage replaced, reordered, cut or added to fits none of that, so the host
     * settles it: each item maps to the message it kept, or null for media the app never drew. Null
     * when the media is as drawn
     */
    private fun readKeptSlots(request: TLObject, optimisticMessages: OptimisticMessages): List<MessageObject?>? {
        val drawn = optimisticMessages.drawn
        val current = readDrawnMedia(request) ?: return null
        if (current.size == drawn.size && current.indices.all { current[it].second == drawn[it].second }) return null
        return current.map { (_, randomId) ->
            drawn.indexOfFirst { it.second == randomId }.takeIf { it >= 0 }?.let { optimisticMessages.messages[it] }
        }
    }

    /**
     * Mirrors stock's answer to a send it matched: each kept message takes its server copy's id and
     * files, and its update is taken out of the answer so the chat does not draw it twice. What is
     * left, media the app never drew included, is applied as any other update
     */
    private fun settleRestructured(optimisticMessages: OptimisticMessages, restructure: Restructure, request: TLObject, response: TLRPC.Updates) {
        val account = optimisticMessages.account
        val randomIds = readDrawnMedia(request)?.map { it.second } ?: return
        val ids = response.updates.filterIsInstance<TL_update.TL_updateMessageID>().associate { it.random_id to it.id }
        val sentById = HashMap<Int, TLRPC.Message>()
        for (update in response.updates) {
            when (update) {
                is TL_update.TL_updateNewMessage -> sentById[update.message.id] = update.message
                is TL_update.TL_updateNewChannelMessage -> sentById[update.message.id] = update.message
                is TL_update.TL_updateNewScheduledMessage -> sentById[update.message.id] = update.message
            }
        }
        val sentBySlot = randomIds.map { ids[it]?.let(sentById::get) }
        // the chat groups an album as its messages arrive and never pulls a drawn one in, so media it never
        // drew comes back as the server's whole album, in place of what it drew
        val inPlace = restructure.slots.none { it == null }
        val settledIds = if (!inPlace) HashSet() else sentBySlot.withIndex().mapNotNullTo(HashSet()) { (slot, sent) -> sent?.id?.takeIf { restructure.slots[slot] != null } }
        val controller = MessagesController.getInstance(account)
        response.updates.removeAll { update ->
            when (update) {
                is TL_update.TL_updateMessageID -> update.id in settledIds
                is TL_update.TL_updateNewMessage -> (update.message.id in settledIds).also { if (it) Utilities.stageQueue.postRunnable { controller.processNewDifferenceParams(-1, update.pts, -1, update.pts_count) } }
                is TL_update.TL_updateNewChannelMessage -> (update.message.id in settledIds).also {
                    if (it) Utilities.stageQueue.postRunnable { controller.processNewChannelDifferenceParams(update.pts, update.pts_count, update.message.peer_id.channel_id) }
                }
                is TL_update.TL_updateNewScheduledMessage -> update.message.id in settledIds
                else -> false
            }
        }
        // every message stock awaits an answer for is answered here, kept or not
        for (message in optimisticMessages.messages) {
            val owner = message.messageOwner
            settledSends.add(owner)
            EngineDispatch.scheduler.postRunnable({ settledSends.remove(owner) }, VERDICT_TTL_MILLIS)
        }
        AndroidUtilities.runOnUIThread {
            val helper = SendMessagesHelper.getInstance(account)
            val storage = MessagesStorage.getInstance(account)
            val center = NotificationCenter.getInstance(account)
            if (!inPlace) {
                for ((slot, sent) in sentBySlot.withIndex()) {
                    val message = restructure.drawn[slot] ?: continue
                    if (sent != null) helper.updateMediaPaths(message, sent, sent.id, null as String?, false, sent.params)
                }
                deleteOptimisticMessages(account, restructure.drawn.filterNotNull())
                return@runOnUIThread
            }
            for ((slot, sent) in sentBySlot.withIndex()) {
                // filled by the ui runnable posted when the request went out, which runs first
                val message = restructure.drawn[slot] ?: continue
                if (sent == null || sent.id !in settledIds) continue
                val owner = message.messageOwner
                val oldId = owner.id
                helper.updateMediaPaths(message, sent, sent.id, null as String?, false, sent.params)
                val existFlags = message.mediaExistanceFlags
                owner.id = sent.id
                if ((sent.flags and TTL_PERIOD_FLAG) != 0) {
                    owner.ttl_period = sent.ttl_period
                    owner.flags = owner.flags or TTL_PERIOD_FLAG
                }
                owner.send_state = MessageObject.MESSAGE_SEND_STATE_SENT
                for (event in intArrayOf(NotificationCenter.messageReceivedByServer, NotificationCenter.messageReceivedByServer2)) {
                    center.postNotificationName(event, oldId, owner.id, owner, owner.dialog_id, sent.grouped_id, existFlags, message.scheduled)
                }
                val mode = getChatMode(owner, message.scheduled)
                storage.storageQueue.postRunnable {
                    storage.updateMessageStateAndId(owner.random_id, MessageObject.getPeerId(owner.peer_id), oldId, owner.id, 0, false, mode, owner.quick_reply_shortcut_id)
                    storage.putMessages(arrayListOf(sent), true, false, false, 0, mode, owner.quick_reply_shortcut_id.toLong())
                    AndroidUtilities.runOnUIThread {
                        helper.processSentMessage(oldId)
                        helper.removeFromSendingMessages(oldId, optimisticMessages.sentScheduled)
                    }
                }
            }
        }
    }

    /**
     * `messages.sendMultiMedia` takes no `inputMediaUploaded*` or external media, so like stock each item
     * goes through `messages.uploadMedia` first. [done] gets what stopped it, on any thread
     */
    internal fun resolveAlbumMedia(account: Int, request: TLObject, done: (TLRPC.TL_error?) -> Unit) {
        val album = request as? TLRPC.TL_messages_sendMultiMedia
        val pending = album?.multi_media.orEmpty().filter {
            it.media is TLRPC.TL_inputMediaUploadedPhoto || it.media is TLRPC.TL_inputMediaUploadedDocument ||
                it.media is TLRPC.TL_inputMediaPhotoExternal || it.media is TLRPC.TL_inputMediaDocumentExternal
        }
        if (album == null || pending.isEmpty()) return done(null)
        val left = AtomicInteger(pending.size)
        val failure = AtomicReference<TLRPC.TL_error?>()
        for (single in pending) {
            val upload = TLRPC.TL_messages_uploadMedia().apply {
                peer = album.peer
                media = single.media
            }
            sendWithoutInterceptors(account, upload, 0) { response, error ->
                val uploaded = single.media
                when (response) {
                    is TLRPC.TL_messageMediaPhoto -> single.media = TLRPC.TL_inputMediaPhoto().apply {
                        id = TLRPC.TL_inputPhoto().apply {
                            id = response.photo.id
                            access_hash = response.photo.access_hash
                            file_reference = response.photo.file_reference
                        }
                        spoiler = uploaded.spoiler
                        TlReflect.syncFlags(this)
                    }
                    is TLRPC.TL_messageMediaDocument -> single.media = TLRPC.TL_inputMediaDocument().apply {
                        id = TLRPC.TL_inputDocument().apply {
                            id = response.document.id
                            access_hash = response.document.access_hash
                            file_reference = response.document.file_reference
                        }
                        spoiler = uploaded.spoiler
                        TlReflect.syncFlags(this)
                    }
                    else -> failure.compareAndSet(null, error ?: syntheticError("messages.uploadMedia answered no photo or document"))
                }
                if (left.decrementAndGet() == 0) done(failure.get())
            }
        }
    }

    private fun refreshRandomIds(request: TLObject) {
        when (request) {
            is TLRPC.TL_messages_sendMedia -> request.random_id = Utilities.random.nextLong()
            is TLRPC.TL_messages_sendMultiMedia -> request.multi_media.forEach { it.random_id = Utilities.random.nextLong() }
        }
    }

    private fun collectTexts(request: TLObject): List<OptimisticText>? = when (request) {
        is TLRPC.TL_messages_sendMessage -> listOf(OptimisticText(request.message, ArrayList(request.entities)))
        is TLRPC.TL_messages_sendMedia -> listOf(OptimisticText(request.message, ArrayList(request.entities)))
        is TLRPC.TL_messages_sendMultiMedia -> request.multi_media.map { OptimisticText(it.message, ArrayList(it.entities)) }
        else -> null
    }

    /**
     * A stage restructured the send's media, see [readKeptSlots]. Stock answers into the messages it drew,
     * so they are fit to the request before it goes out. [onDrawn] gets the messages now drawn for its items
     */
    private fun restructureOptimisticMessages(
        optimisticMessages: OptimisticMessages,
        slots: List<MessageObject?>,
        onDrawn: (List<MessageObject?>) -> Unit,
    ) {
        val account = optimisticMessages.account
        val messages = optimisticMessages.messages
        AndroidUtilities.runOnUIThread {
            val first = messages.first()
            val mode = getChatMode(first.messageOwner, first.scheduled)
            val drawn = placeKeptMedia(messages, slots)
            val kept = drawn.filterNotNull()
            deleteOptimisticMessages(account, messages.drop(kept.size))
            if (kept.isNotEmpty()) {
                MessagesStorage.getInstance(account).putMessages(ArrayList(kept.map { it.messageOwner }), false, true, false, 0, mode, 0L)
                // applied at once, where replaceMessagesObjects waits for the chat to go idle
                for (message in kept) NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateMessageMedia, message.messageOwner)
            }
            if (!first.scheduled) NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload)
            onDrawn(drawn)
        }
    }

    /** ui thread */
    private fun deleteOptimisticMessages(account: Int, messages: List<MessageObject>) {
        val first = messages.firstOrNull() ?: return
        val owner = first.messageOwner
        val mode = getChatMode(owner, first.scheduled)
        MessagesController.getInstance(account).deleteMessages(
            ArrayList(messages.map { it.id }),
            null,
            null,
            owner.dialog_id,
            if (mode == ChatActivity.MODE_QUICK_REPLIES) owner.quick_reply_shortcut_id else MessageObject.getTopicId(account, owner, 0).toInt(),
            false,
            mode,
            true,
        )
        val helper = SendMessagesHelper.getInstance(account)
        for (message in messages) {
            helper.processSentMessage(message.id)
            helper.removeFromSendingMessages(message.id, first.scheduled)
        }
    }

    /**
     * ui thread. The chat lays an album out in the order it drew it, keyed by id, so the messages keep
     * their ids and slots and each takes the media of the item now placed there; the ones left over
     * are cut. The chat's own objects are rebuilt in place and forced to redraw
     */
    private fun placeKeptMedia(messages: List<MessageObject>, slots: List<MessageObject?>): List<MessageObject?> {
        val contents = slots.filterNotNull().map { source ->
            val owner = source.messageOwner
            val media = owner.media
            val attachPath = owner.attachPath
            val params = owner.params
            val videoEditedInfo = source.videoEditedInfo
            val sentHighQuality = source.sentHighQuality
            { target: MessageObject ->
                target.messageOwner.media = media
                target.messageOwner.attachPath = attachPath
                target.messageOwner.params = params
                target.videoEditedInfo = videoEditedInfo
                target.sentHighQuality = sentHighQuality
            }
        }
        var next = 0
        return slots.map { slot ->
            slot ?: return@map null
            messages[next].also { message ->
                contents[next++](message)
                message.type = -1
                message.setType()
                message.generateThumbs(false)
                message.checkMediaExistance()
                message.resetLayout()
                message.forceUpdate = true
            }
        }
    }

    /** stock never redraws a message's text from the server's answer. [slots] are the messages of the request's items */
    private fun syncOptimisticMessages(request: TLObject, optimisticMessages: OptimisticMessages, slots: List<MessageObject?>) {
        val texts = collectTexts(request) ?: return
        val drawn = slots.zip(texts).mapNotNull { (message, text) -> message?.let { it to text } }
        AndroidUtilities.runOnUIThread {
            for ((message, text) in drawn) {
                val owner = message.messageOwner
                if (owner.message == text.text && owner.entities == text.entities) continue
                owner.message = text.text
                owner.entities = text.entities
                owner.flags = if (text.entities.isEmpty()) owner.flags and TLRPC.MESSAGE_FLAG_HAS_ENTITIES.inv() else owner.flags or TLRPC.MESSAGE_FLAG_HAS_ENTITIES
                redrawOptimisticMessage(optimisticMessages.account, message)
            }
        }
    }

    /** ui thread */
    private fun redrawOptimisticMessage(account: Int, message: MessageObject) {
        // a cell rebound to the same object keeps its layout, and its reply header, unless forced
        message.forceUpdate = true
        message.updateMessageText()
        message.resetLayout()
        if (message.type != MessageObject.TYPE_TEXT) {
            message.caption = null
            message.generateCaption()
        }
        val mode = getChatMode(message.messageOwner, message.scheduled)
        MessagesStorage.getInstance(account).putMessages(
            arrayListOf(message.messageOwner),
            false,
            true,
            false,
            0,
            mode,
            message.messageOwner.quick_reply_shortcut_id.toLong(),
        )
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.replaceMessagesObjects, message.dialogId, arrayListOf(message))
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

    private fun invokeRaw(
        session: PluginSession,
        slot: Int,
        startedOn: Int,
        invokeId: Long,
        method: ByteArray,
    ): String? {
        if (!session.permissions.has(RAW_GRANT)) return PluginWire.encodeNotGranted(RAW_GRANT)
        if (method.size < Int.SIZE_BYTES) {
            return PluginWire.encodePluginError("invalid-argument", "invokeRaw: a method is at least its 4-byte constructor id")
        }
        val request = RawTlRequest(method)
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
