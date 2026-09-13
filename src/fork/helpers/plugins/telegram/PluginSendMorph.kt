package desu.inugram.helpers.plugins.telegram

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.telegram.PluginWrites.Call
import desu.inugram.helpers.plugins.telegram.PluginWrites.refuse
import java.io.File
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.SendMessagesHelper
import org.telegram.tgnet.TLRPC

/**
 * `message.setMedia(file)`: a middleware giving a send its media as a file to be staged, on the
 * message the composer already drew rather than on a send of its own.
 *
 * The composer wires a media send through `DelayedMessage` from `sendMessage(params)` onwards, so
 * there is no seam that grows media onto a request already built. What there is instead is the
 * retry seam: `SendMessageParams.of(retryMessageObject)` reuses the very `TLRPC.Message` it is
 * handed, id and all. So the send is unwound, its local message takes the new media in place, and
 * that same message goes back to the composer - which uploads it and reports progress on the bubble
 * that was already on screen.
 *
 * The object that ends up on screen is the composer's own rather than one built here: it makes one
 * for the retry anyway, after it has set the sending state and registered the upload, and
 * [PluginSendHold] turns that draw into a replace so the cell animates the change.
 */
object PluginSendMorph {
    /**
     * stock persists `Message.params` and hands them back on every retry, which makes a key in
     * there the one marker a re-sent message cannot lose. A message carries it once: the re-send is
     * dispatched to the chains like any other, and without this a middleware that always sets media
     * would set it again on its own answer, forever.
     */
    private const val MORPHED_KEY = "inu_plugin_morphed"

    internal class Media(
        val upload: PluginMedia.Upload,
        val name: String,
        val mime: String,
        val asDocument: Boolean,
        /** read where the file is taken, since the composer reads this back on the ui thread */
        val described: PluginMedia.LocalDescription,
    ) {
        val path: File get() = upload.file
        var caption: String = ""
        var entities: ArrayList<TLRPC.MessageEntity> = ArrayList()
    }

    /** ui thread only: written as the retry starts, read by the draw it causes */
    private val morphing = HashSet<Int>()

    internal fun setMedia(call: Call): String? {
        val wire = call.values.firstOrNull() ?: refuse("invalid-argument", "setMedia: no file")
        val source = PluginMedia.stagedFile(call, wire)
        val name = PluginMedia.getFileName(source, call.json.optString("fileName"))
        // rust deletes what it staged the moment this write answers, and the composer uploads long after that
        val upload = PluginMedia.takeForUpload(call, source.path, name)
        val mime = source.mime.ifEmpty { PluginMedia.mimeOfName(name) }
        val asDocument = call.flag("asDocument")
        val media = Media(upload, name, mime, asDocument, PluginMedia.describeLocalDocument(upload.file, mime, asDocument))
        try {
            PluginRpc.holdMedia(call.session, call.json.optLong("dispatch", -1L), media)
        } catch (e: Throwable) {
            upload.discard()
            throw e
        }
        // what this returns is an error wire or nothing; the answer goes back through [answer],
        // which is also what keeps the settle out of this upcall and off the engine's own thread
        PluginWrites.answer(call) { PluginWire.encodeNull() }
        return null
    }

    /** whether this message may still take media, which it may not if it is already the answer to one */
    internal fun isMorphed(message: MessageObject): Boolean =
        message.messageOwner?.params?.containsKey(MORPHED_KEY) == true

    /** the chain's verdict, acted on from the composer's own error path, which is where a send is unwound */
    internal fun takeOver(
        helper: SendMessagesHelper,
        account: Int,
        message: TLRPC.Message,
        scheduled: Boolean,
        media: Media,
    ): Boolean {
        AndroidUtilities.runOnUIThread {
            // the composer still believes the first send is in flight, and the retry would collide with it
            helper.processSentMessage(message.id)
            helper.removeFromSendingMessages(message.id, scheduled)
            val retry = grow(account, message, media)
            morphing.add(message.id)
            helper.sendMessage(SendMessagesHelper.SendMessageParams.of(retry))
            // the composer draws inside that call, so an id still here is one whose draw never came
            morphing.remove(message.id)
            if (media.upload.owned) PluginSentFiles.track(account, message.id, media.path)
        }
        return true
    }

    /**
     * the composer draws the retry the way it draws any send, but this message is already on screen:
     * what it wants is the change animation, not a second arrival. Stock pairs that notification
     * with swapping the dialog's own last-message objects, or the chat list keeps the stale preview.
     */
    internal fun redrawInstead(account: Int, peer: Long, messages: ArrayList<MessageObject>, scheduleDate: Int): Boolean {
        val message = messages.firstOrNull() ?: return false
        if (!morphing.remove(message.id)) return false
        val controller = MessagesController.getInstance(account)
        val last = controller.dialogMessage.get(peer)
        val at = last?.indexOfFirst { it?.id == message.id } ?: -1
        if (last != null && at >= 0) {
            last[at] = message
            if (message.messageOwner?.peer_id?.channel_id == 0L) {
                controller.dialogMessagesByIds.put(message.id, message)
            }
        }
        val center = NotificationCenter.getInstance(account)
        center.postNotificationName(NotificationCenter.replaceMessagesObjects, peer, messages)
        if (scheduleDate == 0) center.postNotificationName(NotificationCenter.dialogsNeedReload)
        return true
    }

    private fun grow(account: Int, owner: TLRPC.Message, media: Media): MessageObject {
        val helper = SendMessagesHelper.getInstance(account)
        val photo = if (PluginOptimisticSend.asPhoto(media.mime, media.asDocument)) {
            helper.generatePhotoSizes(media.path.absolutePath, null)
        } else {
            null
        }
        owner.media = if (photo != null) {
            TLRPC.TL_messageMediaPhoto().apply {
                this.photo = photo
                flags = flags or 1
            }
        } else {
            TLRPC.TL_messageMediaDocument().apply {
                document = PluginOptimisticSend.documentOf(media.path, media.name, media.mime, media.described)
                flags = flags or 1
            }
        }
        owner.flags = owner.flags or TLRPC.MESSAGE_FLAG_HAS_MEDIA
        owner.attachPath = media.path.absolutePath
        owner.message = media.caption
        owner.entities = media.entities
        owner.flags = if (media.entities.isEmpty()) {
            owner.flags and TLRPC.MESSAGE_FLAG_HAS_ENTITIES.inv()
        } else {
            owner.flags or TLRPC.MESSAGE_FLAG_HAS_ENTITIES
        }
        val params = owner.params ?: HashMap<String, String>().also { owner.params = it }
        params[MORPHED_KEY] = "1"
        // the type is decided in the constructor, so a grown message needs a fresh object to read as media
        return MessageObject(account, owner, true, true)
    }
}
