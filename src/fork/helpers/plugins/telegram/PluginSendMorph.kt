package desu.inugram.helpers.plugins.telegram

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.telegram.PluginWrites.Call
import desu.inugram.core.plugins.PluginWire.refuse
import java.io.File
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.SendMessagesHelper
import org.telegram.tgnet.TLRPC

/**
 * Stock configures media through `DelayedMessage` inside `sendMessage(params)`, so a request cannot gain
 * media. The send is unwound, its local message updated, and retried through
 * `SendMessageParams.of(retryMessageObject)`, which keeps the message and id.
 */
object PluginSendMorph {
    /** stock persists `Message.params` across retries; the marker stops a middleware from `setMedia`-looping its own re-send */
    private const val MORPHED_KEY = "inu_plugin_morphed"

    internal class Media(
        val upload: PluginMedia.Upload,
        val name: String,
        val mime: String,
        val asDocument: Boolean,
        /** the composer reads this back on the ui thread */
        val described: PluginMedia.LocalDescription,
    ) {
        val path: File get() = upload.file
        var caption: String = ""
        var entities: ArrayList<TLRPC.MessageEntity> = ArrayList()
    }

    /** ui thread only */
    private val morphing = HashSet<Int>()

    internal fun setMedia(call: Call): String? {
        val wire = call.values.firstOrNull() ?: refuse("invalid-argument", "setMedia: no file")
        val source = PluginMedia.stagedFile(call, wire)
        val name = PluginMedia.getFileName(source, call.json.optString("fileName"))
        // rust deletes what it staged when this write answers, long before the composer uploads
        val upload = PluginMedia.takeForUpload(call, source.path, name)
        val mime = source.mime.ifEmpty { PluginMedia.guessMimeFromName(name) }
        val asDocument = call.flag("asDocument")
        val media = Media(upload, name, mime, asDocument, PluginMedia.describeLocalDocument(upload.file, mime, asDocument))
        try {
            PluginRpc.holdMedia(call.session, call.json.optLong("dispatch", -1L), media)
        } catch (e: Throwable) {
            upload.discard()
            throw e
        }
        // [answer] keeps the settle out of this upcall
        call.answer { PluginWire.encodeNull() }
        return null
    }

    internal fun isMorphed(message: MessageObject): Boolean =
        message.messageOwner?.params?.containsKey(MORPHED_KEY) == true

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
            // the composer draws inside that call, so an id still here never drew
            morphing.remove(message.id)
            if (media.upload.owned) PluginSentFiles.track(account, message.id, media.path)
        }
        return true
    }

    /** stock pairs the change notification with swapping the dialog's last-message objects, or the chat list keeps a stale preview */
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
                document = PluginOptimisticSend.buildLocalDocument(account, media.path, media.name, media.mime, media.described)
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
        // the type is decided in the constructor
        return MessageObject(account, owner, true, true)
    }
}
