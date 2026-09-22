package desu.inugram.helpers.plugins.telegram

import desu.inugram.helpers.plugins.SessionResource
import desu.inugram.core.plugins.PluginRefusal
import android.util.Log
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.telegram.PluginWrites.Call
import desu.inugram.core.plugins.PluginWire.refuse
import desu.inugram.helpers.plugins.tl.TlHandles
import java.io.File
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLoader
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.SendMessagesHelper
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

/**
 * Sends through [SendMessagesHelper], which creates the local message, draws upload progress,
 * uploads staged media, and replaces the local ID with the server ID.
 *
 * [PluginWrites.send] remains the direct-request path for `optimistic: false`, `sendAs`,
 * already-uploaded files, and albums. The composer uses the dialog's default sender and
 * cannot represent these cases.
 *
 * Both paths resolve with the server message. The composer reports through [NotificationCenter],
 * so track sends with a token in `Message.params`, which survives retries and storage.
 */
object PluginOptimisticSend : SessionResource {
    /**
     * stock persists `Message.params` and hands them back on every retry of the same message, which
     * is what makes a key in there an identity the whole send can be followed by
     */
    private const val TOKEN_KEY = "inu_plugin_send"

    private const val TAG = "InuPluginSend"

    private class Pending(val call: Call, val dialogId: Long, val upload: PluginMedia.Upload?) {
        var localId: Int = 0
        var request: TLObject? = null
        var settled = false

        /** ui thread only: both the add and the remove run there, in that order */
        var observer: NotificationCenter.NotificationCenterDelegate? = null
    }

    private val pending = HashMap<String, Pending>()
    private var nextToken = 0L

    private val EVENTS = intArrayOf(
        NotificationCenter.didReceiveNewMessages,
        NotificationCenter.messageReceivedByServer,
        NotificationCenter.messageSendError,
    )

    /** whether the composer can say everything this call asked for */
    internal fun canSend(call: Call): Boolean = call.json.isNull("sendAs")

    internal fun sendText(call: Call, fallback: () -> Unit): String? {
        val dialogId = dialogIdOf(call)
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
                PluginWrites.entitiesOf(call.json).takeIf { it.isNotEmpty() },
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
        val dialogId = dialogIdOf(call)
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
            PluginWrites.answer(call) { e.wire }
            return
        }
        PluginMedia.watchUpload(call, upload.file.absolutePath)
        val path = upload.file
        val token = register(call, dialogId, upload)
        val caption = call.text()
        val entities = PluginWrites.entitiesOf(call.json).takeIf { it.isNotEmpty() }
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
                    documentOf(call.accountId, path, name, mime, described),
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

    /**
     * what stock does with an image the composer was given: a photo unless it was asked for a file,
     * and never for webp, a sticker being a document however it looks
     */
    internal fun asPhoto(mime: String, asDocument: Boolean): Boolean =
        !asDocument && mime.startsWith("image/") && mime != "image/webp"

    /** the same shape the request path uploads: mime and a name, and nothing stock only knows how to read off a gallery pick */
    internal fun documentOf(
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

    private fun dialogIdOf(call: Call): Long {
        // the peer is resolved first, so a send into a secret chat is refused before a message is drawn
        call.peer()
        return PeerSpecs.dialogIdOf(call.controller, call.accountId, call.json.optString("peer"))
            ?: refuse("not-found", "sendMessage: no such dialog")
    }

    /**
     * The composer replies to a [MessageObject] rather than to an id, and the local message it draws
     * renders its quote off that very object - `MessageObject` takes it as its own
     * `replyMessageObject`, which is then the one thing that stops `ChatActivity` looking the real
     * message up. So a stub would draw a quote with a name and no text until the chat was reopened,
     * and a reply this cannot resolve is left to the request path instead.
     *
     * Only what the app already holds is consulted: replying to a message it has never seen is not
     * something its own composer can do either.
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
            // a post into a topic with no reply of its own replies to the topic's root message
            val anchor = found[if (replyId != 0) replyId else topicId]
            val top = if (topicId != 0) found[topicId] else null
            if (anchor == null || (topicId != 0 && top == null)) {
                onMissing()
            } else {
                done(objectOf(call.accountId, anchor), top?.let { objectOf(call.accountId, it) })
            }
        }
    }

    private fun objectOf(accountId: Int, message: TLRPC.Message) = MessageObject(accountId, message, true, true)

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

    /**
     * the composer is a ui-thread api, and anything it refuses throws there rather than on the queue
     * the write came in on, so the promise is settled from inside
     */
    private fun onUi(token: String, block: () -> Unit) {
        AndroidUtilities.runOnUIThread {
            val entry = synchronized(pending) { pending[token] } ?: return@runOnUIThread
            if (!entry.call.session.isCurrent()) return@runOnUIThread
            try {
                block()
            } catch (e: Throwable) {
                Log.e(TAG, "an optimistic send could not be drawn", e)
                fail(token, "the send could not be started")?.let(::discardUndrawn)
            }
        }
    }

    /**
     * one observer per send rather than one per account: a plugin's listener may not outlive the
     * plugin, and a send is the only thing that says when this one is done
     */
    private fun observe(accountId: Int, token: String, entry: Pending) {
        AndroidUtilities.runOnUIThread {
            val observer = NotificationCenter.NotificationCenterDelegate { id, _, args ->
                when (id) {
                    NotificationCenter.didReceiveNewMessages -> onDrawn(token, entry, args)
                    NotificationCenter.messageReceivedByServer -> onSent(token, args)
                    NotificationCenter.messageSendError -> onFailed(token, entry, args)
                }
            }
            entry.observer = observer
            val centre = NotificationCenter.getInstance(accountId)
            for (event in EVENTS) centre.addObserver(observer, event)
        }
    }

    /**
     * the remove takes the same ui hop the add did, which is the only thing that orders it behind
     * one: a send settled before its own add landed still removes the observer that add is about to
     * create
     */
    private fun stopObserving(accountId: Int, entry: Pending) {
        AndroidUtilities.runOnUIThread {
            val observer = entry.observer ?: return@runOnUIThread
            entry.observer = null
            val centre = NotificationCenter.getInstance(accountId)
            for (event in EVENTS) centre.removeObserver(observer, event)
        }
    }

    private fun onDrawn(token: String, entry: Pending, args: Array<Any?>) {
        @Suppress("UNCHECKED_CAST")
        val messages = args.getOrNull(1) as? ArrayList<MessageObject> ?: return
        for (message in messages) {
            if (tokenOf(message.messageOwner) != token) continue
            entry.localId = message.id
            entry.upload?.takeIf { it.owned }?.let { PluginSentFiles.track(entry.call.accountId, message.id, it.file) }
        }
    }

    private fun onSent(token: String, args: Array<Any?>) {
        val message = args.getOrNull(2) as? TLRPC.Message ?: return
        if (tokenOf(message) != token) return
        settle(token) { call -> PluginReads.mint(call.session.tl, message) }
    }

    /** ui thread only, where a draw is: a file the composer drew a message for is that message's to retry from */
    private fun discardUndrawn(entry: Pending) {
        if (entry.localId == 0) entry.upload?.discard()
    }

    private fun onFailed(token: String, entry: Pending, args: Array<Any?>) {
        val localId = args.getOrNull(0) as? Int ?: return
        if (entry.localId == 0 || entry.localId != localId) return
        fail(token, "the send failed")
    }

    private fun tokenOf(message: TLRPC.Message?): String? = message?.params?.get(TOKEN_KEY)

    /**
     * a request the composer built for a plugin's own send is leased the way [PluginWrites.send]
     * leases the ones it builds: a plugin that rewrites sends and a plugin that sends would
     * otherwise be an infinite loop. Answers whether the lease was taken, so the release is paired.
     */
    internal fun claimRequest(request: TLObject, message: MessageObject): Boolean {
        val token = tokenOf(message.messageOwner) ?: return false
        val replaced = synchronized(pending) {
            val entry = pending[token] ?: return false
            // stock re-sends the very instance on a retry, and the lease it already holds covers that
            if (entry.request === request) return false
            val replaced = entry.request
            entry.request = request
            replaced
        }
        replaced?.let { PluginRpc.releasePluginSend(it) }
        return true
    }

    private fun fail(token: String, message: String) =
        settle(token) { PluginWire.encodePluginError("internal", message) }

    private fun settle(token: String, produce: (Call) -> String): Pending? {
        val entry = synchronized(pending) {
            val entry = pending[token] ?: return null
            if (entry.settled) return null
            entry.settled = true
            pending.remove(token)
            entry
        }
        entry.request?.let { PluginRpc.releasePluginSend(it) }
        stopObserving(entry.call.accountId, entry)
        PluginWrites.answer(entry.call) { produce(entry.call) }
        return entry
    }


    /** a plugin that stopped while a send was in flight leaves nothing behind to answer */
    override fun detach(session: PluginSession) {
        val dropped = synchronized(pending) {
            val mine = pending.filterValues { it.call.session === session }
            for (token in mine.keys) pending.remove(token)
            mine.values
        }
        for (entry in dropped) {
            entry.request?.let { PluginRpc.releasePluginSend(it) }
            stopObserving(entry.call.accountId, entry)
            // behind a composer call that may be drawing this very send on the ui thread right now
            AndroidUtilities.runOnUIThread { discardUndrawn(entry) }
        }
    }
}
