package desu.inugram.helpers.plugins.telegram

import android.net.Uri
import android.os.Looper
import desu.inugram.core.plugins.PluginRefusal
import desu.inugram.core.plugins.PluginWire
import desu.inugram.core.plugins.PluginWire.refuse
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.PluginLog
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.telegram.PluginWrites.Call
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlJson
import desu.inugram.helpers.plugins.tl.TlReflect
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DialogObject
import org.telegram.messenger.FileLoader
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessageSuggestionParams
import org.telegram.messenger.MessagesController
import org.telegram.messenger.SendMessageChatArguments
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.SendMessagesHelper.SendMessageParams
import org.telegram.messenger.SendMessagesHelper.SendingMediaInfo
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.AlertsCreator
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.LaunchActivity

/**
 * The compose stage of `interceptSendMessage`. What the user asks to send is held at stock's send entry
 * points before the app draws, processes or uploads any of it, run past the stages, see [PluginSends],
 * and handed back to stock as they left it. Media stock cannot be handed goes out as a plain request,
 * drawn once the server answers. A chat's sends leave in the order they were made.
 *
 * Ui thread, but for [onBound] and the LocalMedia registry.
 */
object PluginCompose {
    private const val CREATED_TTL_MILLIS = 10 * 60_000L

    /** stock persists `Message.params` across retries, so a retried file still finds what follows it */
    private const val BOUND_KEY = "inu_plugin_compose"
    private const val LOCAL_MEDIA = "localMedia"
    private const val SCHEDULE_WHEN_ONLINE = 0x7FFFFFFE
    private const val FORWARD_BATCH = 100
    private const val ALBUM_LIMIT = 10
    internal val POLICY = TlFilter.Policy(takeover = true, drafts = false)
    internal val COMPOSED = SendMessageChatArguments.Builder().apply { inu_setComposed(true) }.build()

    fun interface MediaResume {
        fun send(
            media: ArrayList<SendingMediaInfo>,
            dialogId: Long,
            replyToMsg: MessageObject?,
            replyToTopMsg: MessageObject?,
            quote: ChatActivity.ReplyQuote?,
            notify: Boolean,
            scheduleDate: Int,
            arguments: SendMessageChatArguments,
        )
    }

    fun interface DocumentsResume {
        fun send(
            paths: ArrayList<String>?,
            originalPaths: ArrayList<String>?,
            uris: ArrayList<Uri>?,
            caption: String?,
            captionEntities: ArrayList<TLRPC.MessageEntity>?,
            dialogId: Long,
            replyToMsg: MessageObject?,
            replyToTopMsg: MessageObject?,
            quote: ChatActivity.ReplyQuote?,
            notify: Boolean,
            scheduleDate: Int,
            arguments: SendMessageChatArguments,
        )
    }

    /** a file a plugin staged with `createLocalMedia`, which stock uploads once a send takes it */
    internal class Media(
        val upload: PluginMedia.Upload,
        val name: String,
        val mime: String,
        val asDocument: Boolean,
        /** the composer reads this back on the ui thread */
        val described: PluginMedia.LocalDescription,
    ) {
        val path: File get() = upload.file
    }

    /** keyed by a random id, which is all a plugin hands back: another plugin's file cannot be guessed */
    private val created = ConcurrentHashMap<Long, Media>()

    private class Document(val path: String?, val originalPath: String?, val uri: Uri?)

    private class Target(
        val dialogId: Long,
        val replyToMsg: MessageObject?,
        val replyToTopMsg: MessageObject?,
        val quote: ChatActivity.ReplyQuote?,
        val notify: Boolean,
        val scheduleDate: Int,
    )

    /** stock's forward call, kept to make it again */
    private class ForwardCall(
        val messages: List<MessageObject>,
        val scheduleRepeatPeriod: Int,
        val videoTimestamp: Int,
        val payStars: Long,
        val monoForumPeerId: Long,
        val suggestionParams: MessageSuggestionParams?,
    )

    /** where a send's own media maps back to */
    private sealed interface Origin {
        class Params(val params: SendMessageParams) : Origin
        class Media(val infos: List<SendingMediaInfo>, val resume: MediaResume) : Origin
        class Documents(val documents: List<Document>, val resume: DocumentsResume) : Origin
        object Forward : Origin
    }

    /** a place in a chat's line of sends; [run] is set once it may go */
    private class Queued(val account: Int, val dialogId: Long, var run: (() -> Unit)?)

    private class Send(
        val account: Int,
        val target: Target,
        val message: JSONObject,
        val items: List<JSONObject>,
        val kinds: List<String?>,
        val origin: Origin,
        val queued: Queued,
    ) {
        var forward: ForwardCall? = null
    }

    /** a stage's verdict made ready to go: [taken] are the files it owns until then */
    private class Plan(val taken: List<Media>, val run: (Target) -> Unit)

    /**
     * a send made through stock's composer, and what goes once its request is out; [media] is its file when
     * it is one. [splice] is the send's media in order, a null for each of stock's files in the order it sends them
     */
    private class Bound(val media: Media?, val splice: List<JSONObject?>?, val then: Runnable)

    /** files stock sends one by one: the last of them carries [token] to the request it goes out in */
    private class Batch(val token: String, var left: Int)

    private val queue = ArrayList<Queued>()

    /** sends held this tick, which a forward made later in the tick joins as the composer's comment */
    private val tick = ArrayList<Send>()

    /** set while handing a send back to stock, whose entry points would hold it again */
    private var resuming = false

    private val bound = ConcurrentHashMap<String, Bound>()
    private var nextBound = 0L

    /** bound requests not yet sent, see [onSent] */
    private val sending = java.util.IdentityHashMap<TLObject, Bound>()
    @Volatile private var hasSending = false

    /** keyed by the arguments instance each batch is handed to stock with */
    private val batches = ConcurrentHashMap<SendMessageChatArguments, Batch>()

    private val sealTick = Runnable {
        val sends = tick.toList()
        tick.clear()
        for (send in sends) start(send)
    }

    @JvmStatic
    fun interceptParams(helper: SendMessagesHelper, account: Int, params: SendMessageParams): Boolean {
        params.sendMessageChatArguments?.let(batches::get)?.let { batch -> markBatchItem(params, batch) }
        if (!PluginManager.anyRunning || resuming || params.retryMessageObject != null || isExempt(params.sendMessageChatArguments)) return false
        // an album's items, which stock sends one by one once their files are processed
        if (params.params?.get("groupId").let { it != null && it != "0" }) return false
        if (DialogObject.isEncryptedDialog(params.peer)) return false
        val items = readParamsMedia(params) ?: return false
        if (Looper.myLooper() != Looper.getMainLooper()) {
            AndroidUtilities.runOnUIThread { helper.sendMessage(params) }
            return true
        }
        val target = Target(params.peer, params.replyToMsg, params.replyToTopMsg, params.replyQuote, params.notify, params.scheduleDate)
        val text = params.message ?: params.caption.orEmpty()
        val kinds = listOf(params.document?.let(PluginSends::readDocumentKind))
        return hold(account, target, text, params.entities, items, kinds, Origin.Params(params), text, null) {
            resending { helper.sendMessage(params) }
        }
    }

    @JvmStatic
    fun interceptForward(
        helper: SendMessagesHelper,
        account: Int,
        messages: ArrayList<MessageObject>?,
        peer: Long,
        forwardFromMyName: Boolean,
        hideCaption: Boolean,
        notify: Boolean,
        scheduleDate: Int,
        scheduleRepeatPeriod: Int,
        replyToTopMsg: MessageObject?,
        videoTimestamp: Int,
        payStars: Long,
        monoForumPeerId: Long,
        suggestionParams: MessageSuggestionParams?,
    ): Boolean {
        if (!PluginManager.anyRunning || resuming || messages.isNullOrEmpty() || DialogObject.isEncryptedDialog(peer)) return false
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        val call = ForwardCall(ArrayList(messages), scheduleRepeatPeriod, videoTimestamp, payStars, monoForumPeerId, suggestionParams)
        val forward = createForward(account, messages, forwardFromMyName, hideCaption)
        val comment = tick.lastOrNull { it.account == account && it.target.dialogId == peer && it.forward == null }
        if (comment != null) {
            comment.forward = call
            comment.message.put("forward", forward)
            return true
        }
        val target = Target(peer, null, replyToTopMsg, null, notify, scheduleDate)
        return hold(account, target, "", null, emptyList(), emptyList(), Origin.Forward, null, forward to call) {
            resending {
                helper.sendMessage(messages, peer, forwardFromMyName, hideCaption, notify, scheduleDate, scheduleRepeatPeriod, replyToTopMsg, videoTimestamp, payStars, monoForumPeerId, suggestionParams)
            }
        }
    }

    @JvmStatic
    fun composeMedia(
        accountInstance: AccountInstance,
        media: ArrayList<SendingMediaInfo>,
        dialogId: Long,
        replyToMsg: MessageObject?,
        replyToTopMsg: MessageObject?,
        quote: ChatActivity.ReplyQuote?,
        forceDocument: Boolean,
        notify: Boolean,
        scheduleDate: Int,
        arguments: SendMessageChatArguments?,
        unsupported: Boolean,
        resume: MediaResume,
    ): Boolean {
        if (unsupported || !PluginManager.anyRunning || resuming || isExempt(arguments) || DialogObject.isEncryptedDialog(dialogId)) return false
        val again = { resume.send(media, dialogId, replyToMsg, replyToTopMsg, quote, notify, scheduleDate, arguments ?: SendMessageChatArguments.EMPTY) }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            AndroidUtilities.runOnUIThread(again)
            return true
        }
        val items = media.map { info ->
            val name = getInfoName(info)
            val kind = getInfoKind(info, forceDocument)
            createLocal(generateId(), kind, name, getMime(kind, name), info.hasMediaSpoilers)
        }
        val target = Target(dialogId, replyToMsg, replyToTopMsg, quote, notify, scheduleDate)
        val first = media.first()
        val text = first.caption?.toString().orEmpty()
        return hold(accountInstance.currentAccount, target, text, first.entities, items, emptyList(), Origin.Media(media.toList(), resume), text, null) {
            resending(again)
        }
    }

    @JvmStatic
    fun composeDocuments(
        accountInstance: AccountInstance,
        paths: ArrayList<String>?,
        originalPaths: ArrayList<String>?,
        uris: ArrayList<Uri>?,
        caption: CharSequence?,
        captionEntities: ArrayList<TLRPC.MessageEntity>?,
        dialogId: Long,
        replyToMsg: MessageObject?,
        replyToTopMsg: MessageObject?,
        quote: ChatActivity.ReplyQuote?,
        notify: Boolean,
        scheduleDate: Int,
        arguments: SendMessageChatArguments?,
        unsupported: Boolean,
        resume: DocumentsResume,
    ): Boolean {
        if (unsupported || !PluginManager.anyRunning || resuming || isExempt(arguments) || DialogObject.isEncryptedDialog(dialogId)) return false
        val documents = paths.orEmpty().mapIndexed { i, path -> Document(path, originalPaths?.getOrNull(i), null) } +
            uris.orEmpty().map { Document(null, null, it) }
        if (documents.isEmpty()) return false
        val text = caption?.toString().orEmpty()
        val again = {
            resume.send(paths, originalPaths, uris, text, captionEntities, dialogId, replyToMsg, replyToTopMsg, quote, notify, scheduleDate, arguments ?: SendMessageChatArguments.EMPTY)
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            AndroidUtilities.runOnUIThread(again)
            return true
        }
        val items = documents.map { document ->
            val name = document.path?.let { File(it).name } ?: document.uri?.lastPathSegment.orEmpty()
            createLocal(generateId(), "document", name, PluginMedia.guessMimeFromName(name), false)
        }
        val target = Target(dialogId, replyToMsg, replyToTopMsg, quote, notify, scheduleDate)
        return hold(accountInstance.currentAccount, target, text, captionEntities, items, emptyList(), Origin.Documents(documents, resume), text, null) {
            resending(again)
        }
    }

    private fun markBatchItem(params: SendMessageParams, batch: Batch) {
        val last = synchronized(batch) { --batch.left == 0 }
        if (!last) return
        batches.remove(params.sendMessageChatArguments)
        (params.params ?: HashMap<String, String>().also { params.params = it })[BOUND_KEY] = batch.token
    }

    /** quick replies and greetings are kept, not sent; a composed send already met the stages */
    private fun isExempt(arguments: SendMessageChatArguments?): Boolean =
        arguments != null && (arguments.inu_composed || arguments.quickReplyShortcut != null || arguments.quickReplyShortcutId != 0 || arguments.welcomeMessageChatId != 0L)

    /**
     * the request stock built for files this stage sent, which [onSent] then reports, and the media it is to
     * carry, see [Bound.splice]. Any thread
     */
    internal fun onBound(request: TLObject, account: Int, messages: List<MessageObject>): List<JSONObject?>? {
        val message = messages.firstOrNull { it.messageOwner?.params?.containsKey(BOUND_KEY) == true } ?: return null
        val entry = bound.remove(message.messageOwner.params[BOUND_KEY]) ?: return null
        entry.media?.takeIf { it.upload.owned }?.let { AndroidUtilities.runOnUIThread { PluginSentFiles.track(account, message.id, it.path) } }
        synchronized(sending) {
            sending[request] = entry
            hasSending = true
        }
        return entry.splice
    }

    /**
     * [request] reached the network, or never will. A forward waiting on it goes then: requests reach the
     * server in the order they reach the network, which an interceptor's stages can delay. Stage queue
     */
    internal fun onSent(request: TLObject) {
        if (!hasSending) return
        val entry = synchronized(sending) {
            sending.remove(request).also { hasSending = sending.isNotEmpty() }
        } ?: return
        AndroidUtilities.runOnUIThread(entry.then)
    }

    /**
     * [filterText] is what a text filter matches, null for a forward without a comment. A send no stage
     * holds still waits behind one that is held, and [resend] then hands it to stock as it was
     */
    private fun hold(
        account: Int,
        target: Target,
        text: String,
        entities: List<TLRPC.MessageEntity>?,
        items: List<JSONObject>,
        kinds: List<String?>,
        origin: Origin,
        filterText: String?,
        forward: Pair<JSONObject, ForwardCall>?,
        resend: () -> Unit,
    ): Boolean {
        val peer = PeerSpecs.toMarkedPeerId(MessagesController.getInstance(account), target.dialogId)
        if (!PluginSends.mayIntercept(false, PluginSends.Probe(account, peer, filterText, items.isNotEmpty(), PluginSends.readMediaKinds(items, kinds), forward != null))) {
            // flushed eagerly, so a chat with anything queued has a send still held
            if (queue.none { it.account == account && it.dialogId == target.dialogId }) return false
            queue.add(Queued(account, target.dialogId, resend))
            return true
        }
        val message = createMessage(account, target.dialogId, text, entities, createReplyTo(account, target), target.notify, target.scheduleDate)
        val send = Send(account, target, message, items, kinds, origin, Queued(account, target.dialogId, null))
        forward?.let { (json, call) ->
            message.put("forward", json)
            send.forward = call
        }
        queue.add(send.queued)
        if (tick.isEmpty()) AndroidUtilities.runOnUIThread(sealTick)
        tick.add(send)
        return true
    }

    private fun resending(block: () -> Unit) {
        resuming = true
        try {
            block()
        } finally {
            resuming = false
        }
    }

    private fun start(send: Send) {
        val started = PluginSends.run(send.account, false, send.message, send.items, send.kinds) { outcome ->
            AndroidUtilities.runOnUIThread { decide(send, outcome) }
        }
        if (!started) decide(send, PluginSends.Outcome.Send(send.message, send.items.mapIndexed { at, json -> PluginSends.Item(json, at) }))
    }

    private fun decide(send: Send, outcome: PluginSends.Outcome) {
        when (outcome) {
            PluginSends.Outcome.Dropped -> settle(send) { finishSendTransition(send.account, send.target.dialogId) }
            is PluginSends.Outcome.Failed -> fail(send, outcome.reason)
            is PluginSends.Outcome.Send -> {
                val plan = try {
                    plan(send, outcome.message, outcome.items)
                } catch (e: PluginRefusal) {
                    return fail(send, readRefusal(e))
                }
                resolveTarget(send, outcome.message, { reason ->
                    for (media in plan.taken) media.upload.discard()
                    fail(send, reason)
                }) { target -> settle(send) { plan.run(target) } }
            }
        }
    }

    private fun fail(send: Send, reason: String) {
        PluginLog.HOST.w("compose", "a send was not sent: $reason")
        settle(send) {
            finishSendTransition(send.account, send.target.dialogId)
            LaunchActivity.getSafeLastFragment()?.let { BulletinFactory.of(it).createErrorBulletin(reason).show() }
        }
    }

    private fun settle(send: Send, run: () -> Unit) {
        send.queued.run = run
        flush()
    }

    private fun flush() {
        val blocked = HashSet<Pair<Int, Long>>()
        val ready = ArrayList<Queued>()
        queue.removeAll { entry ->
            val dialog = entry.account to entry.dialogId
            when {
                dialog in blocked -> false
                entry.run == null -> {
                    blocked.add(dialog)
                    false
                }
                else -> {
                    ready.add(entry)
                    true
                }
            }
        }
        for (entry in ready) entry.run!!()
    }

    internal fun readRefusal(e: PluginRefusal): String = (PluginWire.decode(e.wire) as? PluginWire.Value.PluginErr)?.message ?: e.wire

    private fun plan(send: Send, message: JSONObject, items: List<PluginSends.Item>): Plan {
        val text = message.getJSONObject("text")
        val caption = text.getString("text")
        val entities = readEntities(text.optJSONArray("entities"))
        val forward = message.optJSONObject("forward")?.let { planForward(send, it) }
        val taken = ArrayList<Media>()
        val comment = try {
            planComment(send, caption, entities, items, taken)
        } catch (e: Throwable) {
            for (media in taken) media.upload.discard()
            throw e
        }
        if (comment == null && !isSameJson(message.opt("reply"), send.message.opt("reply"))) {
            refuse("invalid-argument", "reply: the message has no text or media to reply with")
        }
        return Plan(taken) { target ->
            // a send that draws nothing where it was typed leaves the composer waiting for a bubble. The chat
            // only takes one for its own dialog, scheduled mode and topic. Finished first: a scheduled bubble
            // arriving in a plain chat opens its scheduled messages over it
            val original = send.target
            val drawnHere = target.dialogId == original.dialogId && (target.scheduleDate != 0) == (original.scheduleDate != 0) &&
                target.replyToTopMsg?.id == original.replyToTopMsg?.id
            if (!drawnHere || (comment == null && forward == null)) {
                finishSendTransition(send.account, send.target.dialogId)
            }
            val then = Runnable { forward?.invoke(target, comment != null) }
            if (comment == null) then.run() else comment(target, then)
        }
    }

    /** null when the send has no comment. The runner runs its continuation once the comment's request is out */
    private fun planComment(
        send: Send,
        caption: String,
        entities: ArrayList<TLRPC.MessageEntity>,
        items: List<PluginSends.Item>,
        taken: MutableList<Media>,
    ): ((Target, Runnable) -> Unit)? {
        val account = send.account
        val origin = send.origin
        val unchanged = items.size == send.items.size && items.withIndex().all { (at, item) -> item.from == at && isSameMedia(item.json, send.items[at]) }
        if (origin is Origin.Params && unchanged) {
            val spoiler = items.firstOrNull()?.json?.takeIf { it.optString("_") == LOCAL_MEDIA }?.optBoolean("spoiler")
            return { target, then ->
                sendParams(account, origin.params, target, caption, entities, spoiler, COMPOSED)
                then.run()
            }
        }
        if (items.isEmpty()) {
            if (caption.isEmpty()) return null
            return { target, then ->
                sendText(account, target, caption, entities, origin)
                then.run()
            }
        }
        val locals = items.filter { it.json.optString("_") == LOCAL_MEDIA }
        val medias = items.mapIndexedNotNull { at, item ->
            if (item.json.optString("_") == LOCAL_MEDIA) return@mapIndexedNotNull null
            val media = try {
                TlJson.fromJson(item.json)
            } catch (e: Exception) {
                refuse("invalid-argument", "media[$at]: ${e.message}")
            }
            media as? TLRPC.InputMedia ?: refuse("invalid-argument", "media[$at]: expected an InputMedia or a LocalMedia")
        }
        if (locals.isEmpty()) return { target, then -> sendRequest(account, target, caption, entities, medias, then) }
        // stock draws and uploads the files, and the other media join them in the request once they are uploaded
        val splice = if (medias.isEmpty()) null else items.map { item -> item.json.takeUnless { it.optString("_") == LOCAL_MEDIA } }
        // stock splits more files than an album holds into several requests
        if (splice != null && items.size > ALBUM_LIMIT) refuse("unsupported", "media: an album holds at most $ALBUM_LIMIT items")
        val files = locals.map { item -> if (item.from >= 0) null else takeLocal(item.json).also(taken::add) }
        val spoilers = locals.map { it.json.optBoolean("spoiler") }
        when (origin) {
            is Origin.Media -> {
                val used = HashSet<SendingMediaInfo>()
                val infos = locals.mapIndexed { at, item ->
                    val info = files[at]?.let(::createInfo) ?: origin.infos[item.from].let { if (used.add(it)) it else copyInfo(it) }
                    info.hasMediaSpoilers = spoilers[at]
                    info
                }
                // stock captions an album on its first item
                for ((at, info) in infos.withIndex()) {
                    if (at == 0) {
                        info.caption = caption
                        info.entities = entities
                    } else if (info === origin.infos.first()) {
                        info.caption = null
                        info.entities = null
                    }
                }
                return { target, then ->
                    val arguments = bindArguments(Bound(null, splice, then), infos.size)
                    origin.resume.send(ArrayList(infos), target.dialogId, target.replyToMsg, target.replyToTopMsg, target.quote, target.notify, target.scheduleDate, arguments)
                }
            }
            is Origin.Documents -> {
                val documents = locals.mapIndexed { at, item ->
                    files[at]?.let { Document(it.path.absolutePath, it.path.absolutePath, null) } ?: origin.documents[item.from]
                }
                // stock sends a batch's paths before its uris, so their relative order is all that survives
                val paths = documents.filter { it.uri == null }
                val uris = documents.mapNotNull { it.uri }
                return { target, then ->
                    val arguments = bindArguments(Bound(null, splice, then), documents.size)
                    origin.resume.send(
                        paths.mapNotNullTo(ArrayList()) { it.path }.takeIf { it.isNotEmpty() },
                        paths.mapTo(ArrayList()) { it.originalPath ?: it.path!! }.takeIf { it.isNotEmpty() },
                        ArrayList(uris).takeIf { it.isNotEmpty() },
                        caption,
                        entities,
                        target.dialogId,
                        target.replyToMsg,
                        target.replyToTopMsg,
                        target.quote,
                        target.notify,
                        target.scheduleDate,
                        arguments,
                    )
                }
            }
            else -> {
                if (files.any { it == null }) {
                    if (origin !is Origin.Params || files.size != 1) refuse("unsupported", "media: a send's own file cannot go with a LocalMedia")
                    return { target, then ->
                        sendParams(account, origin.params, target, caption, entities, spoilers[0], bindArguments(Bound(null, splice, then), 1))
                    }
                }
                val created = files.filterNotNull()
                if (created.size == 1) return { target, then -> sendFile(account, target, caption, entities, created[0], spoilers[0], origin, splice, then) }
                return { target, then -> sendFiles(account, target, caption, entities, created, spoilers, splice, then) }
            }
        }
    }

    /** drawn and sent like the composer's own file */
    private fun sendFile(
        account: Int,
        target: Target,
        caption: String,
        entities: ArrayList<TLRPC.MessageEntity>,
        media: Media,
        spoiler: Boolean,
        origin: Origin,
        splice: List<JSONObject?>?,
        then: Runnable,
    ) {
        val params = PluginOptimisticSend.createMediaParams(
            account, media.path, media.name, media.mime, media.asDocument, media.described,
            target.dialogId, target.replyToMsg, target.replyToTopMsg, caption, entities, HashMap(), target.notify, target.scheduleDate,
        )
        params.replyQuote = target.quote
        params.hasMediaSpoilers = spoiler
        params.sendMessageChatArguments = bindArguments(Bound(media, splice, then), 1)
        inheritParams(params, origin, target)
        SendMessagesHelper.getInstance(account).sendMessage(params)
    }

    /** as files the user picked; [then] waits for the request the last of them goes out in */
    private fun sendFiles(
        account: Int,
        target: Target,
        caption: String,
        entities: ArrayList<TLRPC.MessageEntity>,
        medias: List<Media>,
        spoilers: List<Boolean>,
        splice: List<JSONObject?>?,
        then: Runnable,
    ) {
        val instance = AccountInstance.getInstance(account)
        val arguments = bindArguments(Bound(null, splice, then), medias.size)
        if (medias.all { PluginOptimisticSend.asPhoto(it.mime, it.asDocument) || (!it.asDocument && it.mime.startsWith("video/")) }) {
            val infos = medias.mapIndexedTo(ArrayList()) { at, it -> createInfo(it).apply { hasMediaSpoilers = spoilers[at] } }
            infos[0].caption = caption
            infos[0].entities = entities
            SendMessagesHelper.prepareSendingMedia(
                instance, infos, target.dialogId, target.replyToMsg, target.replyToTopMsg, null, target.quote,
                false, true, null, null, target.notify, target.scheduleDate, 0, 0, false, null, arguments, 0, false, 0, 0, null, null, false,
            )
        } else {
            val paths = medias.mapTo(ArrayList()) { it.path.absolutePath }
            SendMessagesHelper.prepareSendingDocuments(
                instance, paths, ArrayList(paths), null, caption, entities, null, target.dialogId, target.replyToMsg, target.replyToTopMsg,
                null, target.quote, null, target.notify, target.scheduleDate, 0, null, arguments, 0, false, 0, 0, null, null, null, null, false,
            )
        }
    }

    private fun sendParams(
        account: Int,
        params: SendMessageParams,
        target: Target,
        caption: String,
        entities: ArrayList<TLRPC.MessageEntity>,
        spoiler: Boolean?,
        arguments: SendMessageChatArguments,
    ) {
        params.peer = target.dialogId
        params.replyToMsg = target.replyToMsg
        params.replyToTopMsg = target.replyToTopMsg
        params.replyQuote = target.quote
        params.notify = target.notify
        params.scheduleDate = target.scheduleDate
        if (params.message != null) params.message = caption else params.caption = caption
        params.entities = entities
        spoiler?.let { params.hasMediaSpoilers = it }
        params.sendMessageChatArguments = arguments
        SendMessagesHelper.getInstance(account).sendMessage(params)
    }

    /** arguments for [count] files handed to stock at once: the last of them carries [entry] to its request */
    private fun bindArguments(entry: Bound, count: Int): SendMessageChatArguments {
        val arguments = SendMessageChatArguments.Builder().apply { inu_setComposed(true) }.build()
        val token = "f${nextBound++}"
        bound[token] = entry
        batches[arguments] = Batch(token, count)
        return arguments
    }

    private fun sendText(account: Int, target: Target, text: String, entities: ArrayList<TLRPC.MessageEntity>, origin: Origin?) {
        val params = SendMessageParams.of(text, target.dialogId, target.replyToMsg, target.replyToTopMsg, null, true, entities, null, null, target.notify, target.scheduleDate, 0, null, false)
        params.replyQuote = target.quote
        params.sendMessageChatArguments = COMPOSED
        origin?.let { inheritParams(params, it, target) }
        SendMessagesHelper.getInstance(account).sendMessage(params)
    }

    /** what stock's own send carried for its chat, which a send made in its place still needs there */
    private fun inheritParams(params: SendMessageParams, origin: Origin, target: Target) {
        val original = (origin as? Origin.Params)?.params ?: return
        if (original.peer != target.dialogId) return
        params.monoForumPeer = original.monoForumPeer
        params.suggestionParams = original.suggestionParams
        params.effect_id = original.effect_id
        params.payStars = original.payStars
    }

    /** an uploaded stage's send whose media it took all away: the text goes alone */
    internal fun sendText(account: Int, message: JSONObject) {
        val text = message.getJSONObject("text")
        val caption = text.getString("text")
        if (caption.isEmpty()) return
        val entities = try {
            readEntities(text.optJSONArray("entities"))
        } catch (e: PluginRefusal) {
            return PluginLog.HOST.w("compose", "a send's text was not sent: ${readRefusal(e)}")
        }
        resolveTarget(account, message, { PluginLog.HOST.w("compose", "a send's text was not sent: $it") }) { target ->
            sendText(account, target, caption, entities, null)
        }
    }

    /** for media stock is only handed whole objects of: the message is drawn once the server answers */
    private fun sendRequest(
        account: Int,
        target: Target,
        caption: String,
        entities: ArrayList<TLRPC.MessageEntity>,
        medias: List<TLRPC.InputMedia>,
        then: Runnable,
    ) {
        val peer = MessagesController.getInstance(account).getInputPeer(target.dialogId)
        val replyTo = createReplyTo(account, target)
        val single = medias.singleOrNull()
        val request: TLObject = if (single != null) {
            TLRPC.TL_messages_sendMedia().apply {
                this.peer = peer
                media = single
                message = caption
                this.entities = entities
                random_id = Utilities.random.nextLong()
                silent = !target.notify
                schedule_date = target.scheduleDate
                reply_to = replyTo
            }
        } else {
            TLRPC.TL_messages_sendMultiMedia().apply {
                this.peer = peer
                silent = !target.notify
                schedule_date = target.scheduleDate
                reply_to = replyTo
                for ((at, media) in medias.withIndex()) {
                    multi_media.add(TLRPC.TL_inputSingleMedia().apply {
                        this.media = media
                        random_id = Utilities.random.nextLong()
                        message = if (at == 0) caption else ""
                        this.entities = if (at == 0) entities else ArrayList()
                    })
                }
            }
        }
        TlReflect.syncFlagsDeep(request)
        PluginRpc.resolveAlbumMedia(account, request) { error ->
            if (error == null) return@resolveAlbumMedia sendPlain(account, request) { AndroidUtilities.runOnUIThread(then) }
            AndroidUtilities.runOnUIThread {
                AlertsCreator.processError(account, error, null, request)
                then.run()
            }
        }
        finishSendTransition(account, target.dialogId)
    }

    private fun sendPlain(account: Int, request: TLObject, done: () -> Unit = {}) {
        val flags = ConnectionsManager.RequestFlagCanCompress or ConnectionsManager.RequestFlagInvokeAfter
        PluginRpc.sendWithoutInterceptors(account, request, flags) { response, error ->
            if (response is TLRPC.Updates) MessagesController.getInstance(account).processUpdates(response, false)
            if (error != null) AndroidUtilities.runOnUIThread { AlertsCreator.processError(account, error, null, request) }
            done()
        }
    }

    private fun createReplyTo(account: Int, target: Target): TLRPC.InputReplyTo? {
        val replied = target.replyToMsg ?: target.replyToTopMsg ?: return null
        val controller = MessagesController.getInstance(account)
        val peer = controller.getInputPeer(target.dialogId)
        val replyTo = SendMessagesHelper.getInstance(account).createReplyInput(peer, replied.id, target.replyToTopMsg?.id ?: 0, target.quote)
        if (replyTo is TLRPC.TL_inputReplyToMessage && replyTo.reply_to_peer_id == null && replied.dialogId != target.dialogId) {
            replyTo.reply_to_peer_id = controller.getInputPeer(replied.dialogId)
        }
        return replyTo
    }

    /** the runner's flag is whether a comment went ahead of the forward */
    private fun planForward(send: Send, json: JSONObject): (Target, Boolean) -> Unit {
        val account = send.account
        val ids = json.optJSONArray("messageIds") ?: refuse("invalid-argument", "forward.messageIds: expected an array of message ids")
        // an empty list would silently send no forward
        if (ids.length() == 0) refuse("invalid-argument", "forward.messageIds: expected at least one message id; set forward to null to send none")
        val read = (0 until ids.length()).map { readMessageId(ids.opt(it), "forward.messageIds[$it]") }
        val mode = json.optString("mode")
        if (mode != "normal" && mode != "hide-sender" && mode != "hide-caption") {
            refuse("invalid-argument", "forward.mode: expected 'normal', 'hide-sender' or 'hide-caption', got '$mode'")
        }
        val sourceDialogId = PeerSpecs.toSimpleDialogId(readMarkedPeer(json.opt("peer"), "forward.peer"))
        val call = send.forward
        return { commented, behind ->
            // stock schedules a forward a second after the comment it follows
            val date = commented.scheduleDate
            val target = if (behind && date != 0 && date != SCHEDULE_WHEN_ONLINE) {
                Target(commented.dialogId, null, commented.replyToTopMsg, null, commented.notify, date + 1)
            } else {
                commented
            }
            val known = call?.messages.orEmpty().filter { it.dialogId == sourceDialogId }.associateBy { it.id }
            if (read.all(known::containsKey)) {
                forward(account, read.map(known::getValue), target, mode, call, send.target.dialogId)
            } else {
                PluginReads.loadLocalMessages(account, sourceDialogId, read) { found ->
                    AndroidUtilities.runOnUIThread {
                        if (read.all(found::containsKey)) {
                            forward(account, read.map { MessageObject(account, found.getValue(it), true, true) }, target, mode, call, send.target.dialogId)
                        } else {
                            sendPlainForward(account, sourceDialogId, read, target, mode)
                        }
                    }
                }
            }
        }
    }

    private fun forward(account: Int, sources: List<MessageObject>, target: Target, mode: String, call: ForwardCall?, originDialogId: Long) {
        val same = target.dialogId == originDialogId
        var result = 0
        resending {
            result = SendMessagesHelper.getInstance(account).sendMessage(
                ArrayList(sources),
                target.dialogId,
                mode != "normal",
                mode == "hide-caption",
                target.notify,
                target.scheduleDate,
                call?.scheduleRepeatPeriod ?: 0,
                target.replyToTopMsg,
                call?.videoTimestamp ?: -1,
                call?.payStars?.takeIf { same } ?: 0L,
                call?.monoForumPeerId?.takeIf { same } ?: 0L,
                call?.suggestionParams?.takeIf { same },
            )
        }
        // the caller that would have shown why a forward was refused was already answered
        if (result != 0) LaunchActivity.getSafeLastFragment()?.let { AlertsCreator.showSendMediaAlert(result, it, null) }
    }

    /** for sources this device never loaded, which stock cannot forward */
    private fun sendPlainForward(account: Int, sourceDialogId: Long, ids: List<Int>, target: Target, mode: String) {
        val controller = MessagesController.getInstance(account)
        for (batch in ids.chunked(FORWARD_BATCH)) {
            val request = TLRPC.TL_messages_forwardMessages()
            request.from_peer = controller.getInputPeer(sourceDialogId)
            request.to_peer = controller.getInputPeer(target.dialogId)
            request.id.addAll(batch)
            for (unused in batch) request.random_id.add(Utilities.random.nextLong())
            request.drop_author = mode != "normal"
            request.drop_media_captions = mode == "hide-caption"
            request.silent = !target.notify
            request.schedule_date = target.scheduleDate
            request.top_msg_id = target.replyToTopMsg?.id ?: 0
            TlReflect.syncFlagsDeep(request)
            sendPlain(account, request)
        }
    }

    /** a stage that left the chat, the reply and the topic alone keeps the objects stock was handed */
    private fun resolveTarget(send: Send, message: JSONObject, fail: (String) -> Unit, done: (Target) -> Unit) {
        val kept = listOf("peer", "reply", "topicId").all { isSameJson(message.opt(it), send.message.opt(it)) }
        if (!kept) return resolveTarget(send.account, message, fail, done)
        val original = send.target
        done(Target(original.dialogId, original.replyToMsg, original.replyToTopMsg, original.quote, !message.optBoolean("silent"), message.optInt("scheduleDate")))
    }

    /** [done] runs on the ui thread */
    private fun resolveTarget(account: Int, message: JSONObject, fail: (String) -> Unit, done: (Target) -> Unit) {
        val notify = !message.optBoolean("silent")
        val scheduleDate = message.optInt("scheduleDate")
        val controller = MessagesController.getInstance(account)
        val reply = message.optJSONObject("reply")
        val input = TLRPC.TL_inputReplyToMessage()
        val dialogId = try {
            val dialogId = readCachedPeer(controller, message.opt("peer"), "peer")
            input.top_msg_id = if (message.isNull("topicId")) 0 else readMessageId(message.opt("topicId"), "topicId")
            input.reply_to_msg_id = reply?.let { readMessageId(it.opt("messageId"), "reply.messageId") } ?: input.top_msg_id
            if (reply != null && !reply.isNull("peer")) {
                input.reply_to_peer_id = controller.getInputPeer(readCachedPeer(controller, reply.opt("peer"), "reply.peer"))
            }
            reply?.optJSONObject("quote")?.let { quote ->
                input.quote_text = quote.getString("text")
                input.quote_entities = readEntities(quote.optJSONArray("entities"))
                input.quote_offset = quote.optInt("offset")
            }
            dialogId
        } catch (e: PluginRefusal) {
            return fail(readRefusal(e))
        }
        if (input.reply_to_msg_id == 0) return AndroidUtilities.runOnUIThread { done(Target(dialogId, null, null, null, notify, scheduleDate)) }
        resolveReply(account, dialogId, input) { replied, top, quote ->
            done(Target(dialogId, replied, top, quote, notify, scheduleDate))
        }
    }

    private fun readCachedPeer(controller: MessagesController, value: Any?, what: String): Long {
        val dialogId = PeerSpecs.toSimpleDialogId(readMarkedPeer(value, what))
        if (DialogObject.isEncryptedDialog(dialogId) || controller.getUserOrChat(dialogId) == null) {
            refuse("not-found", "$what: $value is not a chat this account knows")
        }
        return dialogId
    }

    private fun readMarkedPeer(value: Any?, what: String): Long =
        (value as? Number)?.toLong()?.takeIf { it != 0L } ?: refuse("invalid-argument", "$what: expected a peer id, got $value")

    private fun readMessageId(value: Any?, what: String): Int =
        (value as? Int)?.takeIf { it > 0 } ?: refuse("invalid-argument", "$what: expected a positive integer, got $value")

    internal fun readEntities(list: JSONArray?): ArrayList<TLRPC.MessageEntity> {
        val entities = ArrayList<TLRPC.MessageEntity>()
        for (at in 0 until (list?.length() ?: 0)) {
            val entity = try {
                TlJson.fromJson(list!!.getJSONObject(at))
            } catch (e: Exception) {
                refuse("invalid-argument", "entities[$at]: ${e.message}")
            }
            entities.add(entity as? TLRPC.MessageEntity ?: refuse("invalid-argument", "entities[$at]: expected a MessageEntity"))
        }
        return entities
    }

    private fun encodeEntities(entities: List<TLRPC.MessageEntity>?): JSONArray =
        JSONArray(entities.orEmpty().map { TlJson.toJson(it, POLICY) })

    /** what `send_message.js` hands the stages, less its media. A reply to a topic's root names only the topic */
    internal fun createMessage(
        account: Int,
        dialogId: Long,
        text: String,
        entities: List<TLRPC.MessageEntity>?,
        replyTo: TLRPC.InputReplyTo?,
        notify: Boolean,
        scheduleDate: Int,
    ): JSONObject {
        val controller = MessagesController.getInstance(account)
        val reply = replyTo as? TLRPC.TL_inputReplyToMessage
        val quoted = !reply?.quote_text.isNullOrEmpty()
        val replied = reply?.takeIf { it.reply_to_msg_id != it.top_msg_id || quoted }?.let {
            val peer = it.reply_to_peer_id?.let { peer -> PeerSpecs.toMarkedPeerId(controller, PluginRpc.getDialogId(account, peer)) }
            val quote = if (quoted) JSONObject().put("text", it.quote_text).put("entities", encodeEntities(it.quote_entities)).put("offset", it.quote_offset) else null
            JSONObject()
                .put("messageId", it.reply_to_msg_id)
                .put("peer", peer ?: JSONObject.NULL)
                .put("quote", quote ?: JSONObject.NULL)
        }
        return JSONObject()
            .put("peer", PeerSpecs.toMarkedPeerId(controller, dialogId))
            .put("text", JSONObject().put("text", text).put("entities", encodeEntities(entities)))
            .put("reply", replied ?: JSONObject.NULL)
            .put("forward", JSONObject.NULL)
            .put("topicId", reply?.top_msg_id?.takeIf { it != 0 } ?: JSONObject.NULL)
            .put("scheduleDate", scheduleDate.takeIf { it != 0 } ?: JSONObject.NULL)
            .put("silent", !notify)
    }

    private fun createForward(account: Int, messages: List<MessageObject>, forwardFromMyName: Boolean, hideCaption: Boolean): JSONObject {
        val source = messages.first().dialogId
        return JSONObject()
            .put("peer", PeerSpecs.toMarkedPeerId(MessagesController.getInstance(account), source))
            .put("messageIds", JSONArray(messages.filter { it.dialogId == source }.map { it.id }))
            .put("mode", if (hideCaption) "hide-caption" else if (forwardFromMyName) "hide-sender" else "normal")
    }

    /**
     * Null for what no stage is handed, which stock builds from whole objects in place. A file on this
     * device is a LocalMedia; anything on the server already is the InputMedia stock sends for it
     */
    private fun readParamsMedia(params: SendMessageParams): List<JSONObject>? {
        if (params.game != null || params.poll != null || params.pollSendParams != null || params.todo != null || params.invoice != null ||
            params.mediaWebPage != null || params.richMessage != null || params.sendingStory != null || params.replyToStoryItem != null
        ) {
            return null
        }
        val photo = params.photo
        val document = params.document
        val location = params.location
        val user = params.user
        val media: TLRPC.InputMedia = when {
            photo != null && (params.path != null || photo.id == 0L) ->
                return listOf(createLocal(generateId(), "photo", params.path?.let { File(it).name }.orEmpty(), "image/jpeg", params.hasMediaSpoilers))
            document != null && (params.path != null || document.id == 0L) -> return listOf(
                createLocal(generateId(), PluginSends.readDocumentKind(document), FileLoader.getDocumentFileName(document).orEmpty(), document.mime_type.orEmpty(), params.hasMediaSpoilers),
            )
            photo != null -> TLRPC.TL_inputMediaPhoto().apply {
                id = TLRPC.TL_inputPhoto().apply {
                    id = photo.id
                    access_hash = photo.access_hash
                    file_reference = photo.file_reference ?: ByteArray(0)
                }
                spoiler = params.hasMediaSpoilers
            }
            document != null -> TLRPC.TL_inputMediaDocument().apply {
                id = TLRPC.TL_inputDocument().apply {
                    id = document.id
                    access_hash = document.access_hash
                    file_reference = document.file_reference ?: ByteArray(0)
                }
                spoiler = params.hasMediaSpoilers
            }
            location is TLRPC.TL_messageMediaVenue -> TLRPC.TL_inputMediaVenue().apply {
                geo_point = createGeoPoint(location.geo)
                title = location.title
                address = location.address
                provider = location.provider
                venue_id = location.venue_id
                venue_type = location.venue_type.orEmpty()
            }
            location is TLRPC.TL_messageMediaGeoLive -> TLRPC.TL_inputMediaGeoLive().apply {
                geo_point = createGeoPoint(location.geo)
                period = location.period
            }
            location != null -> TLRPC.TL_inputMediaGeoPoint().apply { geo_point = createGeoPoint(location.geo) }
            user != null -> TLRPC.TL_inputMediaContact().apply {
                phone_number = user.phone.orEmpty()
                first_name = user.first_name.orEmpty()
                last_name = user.last_name.orEmpty()
                vcard = ""
            }
            else -> return emptyList()
        }
        TlReflect.syncFlagsDeep(media)
        return listOf(TlJson.toJson(media, POLICY))
    }

    private fun createGeoPoint(geo: TLRPC.GeoPoint?): TLRPC.InputGeoPoint = TLRPC.TL_inputGeoPoint().apply {
        lat = geo?.lat ?: 0.0
        _long = geo?._long ?: 0.0
    }

    /** a kept LocalMedia may change only its spoiler, which the send carries apart */
    private fun isSameMedia(edited: JSONObject, original: JSONObject): Boolean {
        if (original.optString("_") != LOCAL_MEDIA) return isSameJson(edited, original)
        return edited.optString("_") == LOCAL_MEDIA && edited.optString("id") == original.optString("id")
    }

    /** a stage's json comes back through js, which keeps keys but not number types */
    internal fun isSameJson(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> a.length() == b.length() && a.keys().asSequence().all { b.has(it) && isSameJson(a.get(it), b.get(it)) }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { isSameJson(a.get(it), b.get(it)) }
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> (a ?: JSONObject.NULL) == (b ?: JSONObject.NULL)
    }

    private fun createLocal(id: Long, kind: String, name: String, mime: String, spoiler: Boolean): JSONObject = JSONObject()
        .put("_", LOCAL_MEDIA)
        .put("id", id.toString())
        .put("kind", kind)
        .put("name", name)
        .put("mimeType", mime)
        .put("spoiler", spoiler)

    private fun takeLocal(json: JSONObject): Media =
        json.optString("id").toLongOrNull()?.let(created::remove)
            ?: refuse("not-found", "media: this LocalMedia was already sent, or has expired")

    internal fun createLocalMedia(call: Call): String? {
        val wire = call.values.firstOrNull() ?: refuse("invalid-argument", "createLocalMedia: no file")
        val source = PluginMedia.stagedFile(call, wire)
        val name = PluginMedia.getFileName(source, call.json.optString("fileName"))
        // rust deletes what it staged when this write answers, long before the composer uploads
        val upload = PluginMedia.takeForUpload(call, source.path, name, picked = true)
        val mime = source.mime.ifEmpty { PluginMedia.guessMimeFromName(name) }
        val asDocument = call.flag("asDocument")
        val media = Media(upload, name, mime, asDocument, PluginMedia.describeLocalDocument(upload.file, mime, asDocument))
        val id = generateId()
        created[id] = media
        EngineDispatch.scheduler.postRunnable({ created.remove(id)?.upload?.discard() }, CREATED_TTL_MILLIS)
        val described = JSONObject().put("id", id.toString()).put("kind", getKind(media)).put("name", media.name).put("mimeType", media.mime)
        call.answer { PluginWire.encodeJson(described.toString()) }
        return null
    }

    /**
     * stock replies through the [MessageObject]s it is handed and draws the quote off them, so a reply
     * named by id is looked up, and stubbed when this device never saw it. [done] runs on the ui thread
     */
    private fun resolveReply(
        account: Int,
        dialogId: Long,
        reply: TLRPC.TL_inputReplyToMessage,
        done: (MessageObject, MessageObject?, ChatActivity.ReplyQuote?) -> Unit,
    ) {
        val repliedDialogId = reply.reply_to_peer_id?.let { PluginRpc.getDialogId(account, it) } ?: dialogId
        PluginReads.loadLocalMessages(account, repliedDialogId, listOf(reply.reply_to_msg_id)) { replied ->
            val topIds = listOfNotNull(reply.top_msg_id.takeIf { it != 0 })
            PluginReads.loadLocalMessages(account, dialogId, topIds) { tops ->
                AndroidUtilities.runOnUIThread {
                    val repliedObject = toMessageObject(account, repliedDialogId, reply.reply_to_msg_id, replied[reply.reply_to_msg_id])
                    val topObject = topIds.firstOrNull()?.let { toMessageObject(account, dialogId, it, tops[it]) }
                    val quote = reply.quote_text?.takeIf { it.isNotEmpty() }?.let { text ->
                        ChatActivity.ReplyQuote.from(repliedObject, reply.quote_offset, reply.quote_offset + text.length)?.also {
                            it.text = text
                            it.entities = reply.quote_entities?.let(::ArrayList)
                            it.start = reply.quote_offset
                        }
                    }
                    done(repliedObject, topObject, quote)
                }
            }
        }
    }

    private fun toMessageObject(account: Int, dialogId: Long, id: Int, found: TLRPC.Message?): MessageObject {
        val message = found ?: TLRPC.TL_message().apply {
            this.id = id
            peer_id = MessagesController.getInstance(account).getPeer(dialogId)
            dialog_id = dialogId
            message = ""
        }
        return MessageObject(account, message, false, false)
    }

    private fun generateId(): Long {
        while (true) {
            val id = Utilities.random.nextLong()
            if (id != 0L && !created.containsKey(id)) return id
        }
    }

    private fun getKind(media: Media): String = when {
        media.asDocument -> "document"
        media.mime == "image/gif" -> "gif"
        media.mime.startsWith("image/") -> "photo"
        media.mime.startsWith("video/") -> if (media.described.attributes.any { it is TLRPC.TL_documentAttributeAnimated }) "gif" else "video"
        media.mime.startsWith("audio/") -> "music"
        else -> "document"
    }

    private fun createInfo(media: Media): SendingMediaInfo = SendingMediaInfo().apply {
        path = media.path.absolutePath
        isVideo = media.mime.startsWith("video/") && !media.asDocument
    }

    /** stock processes each info in place, so a file sent twice needs a second one */
    private fun copyInfo(info: SendingMediaInfo): SendingMediaInfo {
        val copy = SendingMediaInfo()
        for (field in SendingMediaInfo::class.java.fields) {
            if (!java.lang.reflect.Modifier.isStatic(field.modifiers)) field.set(copy, field.get(info))
        }
        return copy
    }

    private fun getInfoName(info: SendingMediaInfo): String =
        info.path?.let { File(it).name }
            ?: info.uri?.lastPathSegment
            ?: info.searchImage?.imageUrl?.substringAfterLast('/')
            ?: info.inlineResult?.title
            ?: ""

    private fun getInfoKind(info: SendingMediaInfo, forceDocument: Boolean): String = when {
        forceDocument -> "document"
        info.isVideo -> "video"
        info.searchImage?.type == 1 || info.inlineResult?.type == "gif" -> "gif"
        else -> "photo"
    }

    private fun getMime(kind: String, name: String): String = when (kind) {
        "photo" -> "image/jpeg"
        "video" -> "video/mp4"
        else -> PluginMedia.guessMimeFromName(name)
    }

    /**
     * the composer keeps the typed text for 200ms so the bubble's enter animation grows out of it, and
     * `ChatActivity` cuts that short via [ChatActivityEnterView.startMessageTransition] when the bubble arrives.
     * It also keeps its reply or forward panel until then, refusing to close it. A send that draws no bubble
     * there has none, so both are finished here the way `ChatActivity` does for an arriving outgoing message.
     */
    internal fun finishSendTransition(account: Int, peer: Long) {
        val chat = LaunchActivity.getSafeLastFragment() as? ChatActivity ?: return
        if (chat.currentAccount != account || chat.dialogId != peer) return
        chat.chatActivityEnterView?.startMessageTransition()
        if (!chat.waitingForSendingMessageLoad) return
        chat.waitingForSendingMessageLoad = false
        chat.chatActivityEnterView?.hideTopView(true)
        chat.changeBoundAnimator?.start()
    }
}
