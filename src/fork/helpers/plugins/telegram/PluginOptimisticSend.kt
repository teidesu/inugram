package desu.inugram.helpers.plugins.telegram

import desu.inugram.helpers.plugins.SessionResource
import desu.inugram.helpers.plugins.UiObservation
import desu.inugram.core.plugins.PluginRefusal
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.telegram.PluginWrites.Call
import desu.inugram.core.plugins.PluginWire.refuse
import java.io.File
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessageObject
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.SendMessagesHelper
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

/**
 * [PluginWrites.send] stays the path for `optimistic: false`, `sendAs`, uploaded files and albums, which
 * the composer cannot represent. The composer reports through [NotificationCenter], so sends are
 * tracked by a token in `Message.params`, which survives retries and storage.
 */
object PluginOptimisticSend : SessionResource {
    /** stock persists `Message.params` and hands them back on every retry */
    private const val TOKEN_KEY = "inu_plugin_send"

    private class Pending(val call: Call, val dialogId: Long, val upload: PluginMedia.Upload?) {
        var localId: Int = 0
        var request: TLObject? = null

        @Volatile var observation: UiObservation? = null
    }

    private val pending = HashMap<String, Pending>()
    private var nextToken = 0L

    private val EVENTS = intArrayOf(
        NotificationCenter.didReceiveNewMessages,
        NotificationCenter.messageReceivedByServer,
        NotificationCenter.messageSendError,
    )

    internal fun canSend(call: Call): Boolean = call.json.isNull("sendAs")

    internal fun sendText(call: Call, fallback: () -> Unit): String? {
        val dialogId = resolveCallDialogId(call)
        resolveReply(call, dialogId, fallback) { replyTo, replyToTop ->
            startText(call, dialogId, replyTo, replyToTop)
        }
        return null
    }

    private fun startText(call: Call, dialogId: Long, replyTo: MessageObject?, replyToTop: MessageObject?) {
        val token = register(call, dialogId, null)
        onUi(token) {
            val params = SendMessagesHelper.SendMessageParams.of(
                call.text(),
                dialogId,
                replyTo,
                replyToTop,
                null,
                !call.flag("noWebpage"),
                PluginWrites.readEntities(call.json).takeIf { it.isNotEmpty() },
                null,
                hashMapOf(TOKEN_KEY to token),
                !call.flag("silent"),
                call.int("scheduleDate"),
                0,
                null,
                false,
            )
            SendMessagesHelper.getInstance(call.accountId).sendMessage(params)
        }
    }

    internal fun sendMedia(
        call: Call,
        source: File,
        name: String,
        mime: String,
        asDocument: Boolean,
        described: PluginMedia.LocalDescription,
        fallback: () -> Unit,
    ): String? {
        val dialogId = resolveCallDialogId(call)
        resolveReply(call, dialogId, fallback) { replyTo, replyToTop ->
            startMedia(call, dialogId, source, name, mime, asDocument, described, replyTo, replyToTop)
        }
        return null
    }

    private fun startMedia(
        call: Call,
        dialogId: Long,
        source: File,
        name: String,
        mime: String,
        asDocument: Boolean,
        described: PluginMedia.LocalDescription,
        replyTo: MessageObject?,
        replyToTop: MessageObject?,
    ) {
        val upload = try {
            PluginMedia.takeForUpload(call, source, name)
        } catch (e: PluginRefusal) {
            call.answer { e.wire }
            return
        }
        PluginMedia.watchUpload(call, upload.file.absolutePath)
        val path = upload.file
        val token = register(call, dialogId, upload)
        val caption = call.text()
        val entities = PluginWrites.readEntities(call.json).takeIf { it.isNotEmpty() }
        onUi(token) {
            val helper = SendMessagesHelper.getInstance(call.accountId)
            val photo = if (asPhoto(mime, asDocument)) helper.generatePhotoSizes(path.absolutePath, null) else null
            val params = if (photo != null) {
                SendMessagesHelper.SendMessageParams.of(
                    photo,
                    path.absolutePath,
                    dialogId,
                    replyTo,
                    replyToTop,
                    caption,
                    entities,
                    null,
                    hashMapOf(TOKEN_KEY to token),
                    !call.flag("silent"),
                    call.int("scheduleDate"),
                    0,
                    0,
                    null,
                    false,
                )
            } else {
                SendMessagesHelper.SendMessageParams.of(
                    buildLocalDocument(call.accountId, path, name, mime, described),
                    null,
                    path.absolutePath,
                    dialogId,
                    replyTo,
                    replyToTop,
                    caption,
                    entities,
                    null,
                    hashMapOf(TOKEN_KEY to token),
                    !call.flag("silent"),
                    call.int("scheduleDate"),
                    0,
                    0,
                    null,
                    null,
                    false,
                )
            }
            helper.sendMessage(params)
        }
    }

    /** stock's rule: webp stays a document, a sticker being one however it looks */
    internal fun asPhoto(mime: String, asDocument: Boolean): Boolean =
        !asDocument && mime.startsWith("image/") && mime != "image/webp"

    internal fun buildLocalDocument(
        accountId: Int,
        path: File,
        name: String,
        mime: String,
        described: PluginMedia.LocalDescription,
    ): TLRPC.TL_document =
        TLRPC.TL_document().apply {
            dc_id = 0
            id = 0
            date = ConnectionsManager.getInstance(accountId).getCurrentTime()
            mime_type = mime
            size = path.length()
            file_reference = ByteArray(0)
            attributes.add(TLRPC.TL_documentAttributeFilename().apply { file_name = name.ifEmpty { path.name } })
            attributes.addAll(described.attributes)
            described.thumb?.let {
                thumbs.add(it)
                flags = flags or 1
            }
        }

    private fun resolveCallDialogId(call: Call): Long {
        // a secret-chat send must be refused before a message is drawn
        call.peer()
        return PeerSpecs.resolveDialogId(call.controller, call.accountId, call.json.optString("peer"))
            ?: refuse("not-found", "sendMessage: no such dialog")
    }

    /**
     * the local message renders its quote off the given [MessageObject], which also stops `ChatActivity`
     * looking the real one up, so a stub would draw an empty quote until the chat reopens
     */
    private fun resolveReply(
        call: Call,
        dialogId: Long,
        onMissing: () -> Unit,
        done: (MessageObject?, MessageObject?) -> Unit,
    ) {
        val replyId = call.int("replyTo")
        val topicId = call.int("topicId")
        if (replyId == 0 && topicId == 0) return done(null, null)
        val wanted = listOf(replyId, topicId).filter { it != 0 }.distinct()
        PluginReads.loadLocalMessages(call.accountId, dialogId, wanted) { found ->
            if (!call.session.isCurrent()) return@loadLocalMessages
            // a topic post with no reply of its own replies to the topic's root
            val anchor = found[if (replyId != 0) replyId else topicId]
            val top = if (topicId != 0) found[topicId] else null
            if (anchor == null || (topicId != 0 && top == null)) {
                onMissing()
            } else {
                done(MessageObject(call.accountId, anchor, true, true), top?.let { MessageObject(call.accountId, it, true, true) })
            }
        }
    }

    private fun register(call: Call, dialogId: Long, upload: PluginMedia.Upload?): String {
        val entry = Pending(call, dialogId, upload)
        val token = synchronized(pending) {
            val token = "p${++nextToken}"
            pending[token] = entry
            token
        }
        observe(call.accountId, token, entry)
        return token
    }

    /** the composer throws on the ui thread, so the promise is settled from inside */
    private fun onUi(token: String, block: () -> Unit) {
        AndroidUtilities.runOnUIThread {
            val entry = synchronized(pending) { pending[token] } ?: return@runOnUIThread
            if (!entry.call.session.isCurrent()) return@runOnUIThread
            try {
                block()
            } catch (e: Throwable) {
                entry.call.session.log.e("send", "an optimistic send could not be drawn", e)
                fail(token, "the send could not be started")?.let(::discardUndrawn)
            }
        }
    }

    /** a plugin's listener may not outlive the plugin, and only a send says when it is done */
    private fun observe(accountId: Int, token: String, entry: Pending) {
        val observation = UiObservation(EVENTS, { listOf(NotificationCenter.getInstance(accountId)) }) { id, _, args ->
            when (id) {
                NotificationCenter.didReceiveNewMessages -> onDrawn(token, entry, args)
                NotificationCenter.messageReceivedByServer -> onSent(token, args)
                NotificationCenter.messageSendError -> onFailed(token, entry, args)
            }
        }
        entry.observation = observation
        observation.start()
    }

    private fun onDrawn(token: String, entry: Pending, args: Array<Any?>) {
        @Suppress("UNCHECKED_CAST")
        val messages = args.getOrNull(1) as? ArrayList<MessageObject> ?: return
        for (message in messages) {
            if (readSendToken(message.messageOwner) != token) continue
            entry.localId = message.id
            entry.upload?.takeIf { it.owned }?.let { PluginSentFiles.track(entry.call.accountId, message.id, it.file) }
        }
    }

    private fun onSent(token: String, args: Array<Any?>) {
        val message = args.getOrNull(2) as? TLRPC.Message ?: return
        if (readSendToken(message) != token) return
        settle(token) { call -> PluginReads.mint(call.session.tl, message) }
    }

    /** ui thread only: a file the composer drew a message for is that message's to retry from */
    private fun discardUndrawn(entry: Pending) {
        if (entry.localId == 0) entry.upload?.discard()
    }

    private fun onFailed(token: String, entry: Pending, args: Array<Any?>) {
        val localId = args.getOrNull(0) as? Int ?: return
        if (entry.localId == 0 || entry.localId != localId) return
        fail(token, "the send failed")
    }

    private fun readSendToken(message: TLRPC.Message?): String? = message?.params?.get(TOKEN_KEY)

    /** leased like [PluginWrites.send]'s requests, or a rewriting plugin and a sending plugin loop */
    internal fun claimRequest(request: TLObject, message: MessageObject): Boolean {
        val token = readSendToken(message.messageOwner) ?: return false
        val replaced = synchronized(pending) {
            val entry = pending[token] ?: return false
            // stock re-sends the same instance on retry, already covered by the lease
            if (entry.request === request) return false
            val replaced = entry.request
            entry.request = request
            replaced
        }
        replaced?.let { PluginRpc.releaseBypass(it) }
        return true
    }

    private fun fail(token: String, message: String) =
        settle(token) { PluginWire.encodePluginError("internal", message) }

    private fun settle(token: String, produce: (Call) -> String): Pending? {
        val entry = synchronized(pending) { pending.remove(token) } ?: return null
        entry.request?.let { PluginRpc.releaseBypass(it) }
        entry.observation?.stop()
        entry.call.answer { produce(entry.call) }
        return entry
    }

    override fun detach(session: PluginSession) {
        val dropped = synchronized(pending) {
            val mine = pending.filterValues { it.call.session === session }
            for (token in mine.keys) pending.remove(token)
            mine.values
        }
        for (entry in dropped) {
            entry.request?.let { PluginRpc.releaseBypass(it) }
            entry.observation?.stop()
            // behind a composer call that may be drawing this send right now
            AndroidUtilities.runOnUIThread { discardUndrawn(entry) }
        }
    }
}
