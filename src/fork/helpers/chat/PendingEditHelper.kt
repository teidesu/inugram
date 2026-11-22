package desu.inugram.helpers.chat

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.SendMessagesHelper
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import java.util.WeakHashMap

object PendingEditHelper {
    private class PendingEdit(
        val account: Int,
        val localId: Int,
        val text: String,
        val entities: ArrayList<TLRPC.MessageEntity>,
        val media: TLRPC.MessageMedia?,
        val invertMedia: Boolean,
        val searchWebPage: Boolean,
    )

    private val requests = WeakHashMap<TLRPC.Message, TLRPC.TL_messages_sendMessage>()
    private val pendingEdits = WeakHashMap<TLRPC.Message, PendingEdit>()

    @JvmStatic
    fun trackRequest(message: TLRPC.Message, req: TLObject) {
        if (message.id < 0 && req is TLRPC.TL_messages_sendMessage) {
            synchronized(requests) { requests[message] = req }
        }
    }

    @JvmStatic
    fun canEditPendingMessage(account: Int, message: TLRPC.Message, scheduled: Boolean): Boolean {
        if (scheduled || message.send_state != MessageObject.MESSAGE_SEND_STATE_SENDING) return false
        if (message.rich_message != null || message.quick_reply_shortcut_id != 0 || message.quick_reply_shortcut != null) return false
        if (DialogObject.isEncryptedDialog(MessageObject.getDialogId(message))) return false
        val media = message.media
        if (media != null && media !is TLRPC.TL_messageMediaEmpty && media !is TLRPC.TL_messageMediaWebPage) return false
        val sending = SendMessagesHelper.getInstance(account).sendingMessages.get(message.id)
        return sending != null && sending.reqId != 0
    }

    @JvmStatic
    fun editPendingMessage(account: Int, messageObject: MessageObject): Boolean {
        val text = messageObject.editingMessage?.toString() ?: return false
        val entities = messageObject.editingMessageEntities ?: ArrayList()
        val searchWebPage = messageObject.editingMessageSearchWebPage
        messageObject.editingMessage = null
        messageObject.editingMessageEntities = null

        val owner = messageObject.messageOwner
        val sending = SendMessagesHelper.getInstance(account).sendingMessages.get(owner.id)
        for (message in listOfNotNull(owner, sending?.takeIf { it !== owner })) {
            message.message = text
            message.entities = entities
            message.flags = if (entities.isEmpty()) {
                message.flags and TLRPC.MESSAGE_FLAG_HAS_ENTITIES.inv()
            } else {
                message.flags or TLRPC.MESSAGE_FLAG_HAS_ENTITIES
            }
            message.media = owner.media
            message.flags = message.flags and TLRPC.MESSAGE_FLAG_HAS_MEDIA.inv() or (owner.flags and TLRPC.MESSAGE_FLAG_HAS_MEDIA)
            message.invert_media = owner.invert_media
        }

        MessagesStorage.getInstance(account).putMessages(arrayListOf(owner), false, true, false, 0, 0, 0L)
        val updated = MessageObject(account, owner, true, true)
        val controller = MessagesController.getInstance(account)
        controller.dialogMessage.get(updated.dialogId)?.replaceAll { if (it?.id == updated.id) updated else it }
        if (controller.dialogMessagesByIds.get(updated.id) != null) {
            controller.dialogMessagesByIds.put(updated.id, updated)
        }
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.replaceMessagesObjects, updated.dialogId, arrayListOf(updated))
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload)

        if (sending == null) return true
        val edit = PendingEdit(account, sending.id, text, entities, owner.media, owner.invert_media, searchWebPage)
        synchronized(pendingEdits) { pendingEdits[sending] = edit }

        val req = synchronized(requests) { requests[sending] }
        val media = owner.media
        if (req == null || media != null && media !is TLRPC.TL_messageMediaEmpty) return true
        req.message = text
        req.entities = entities
        req.flags = if (entities.isEmpty()) req.flags and 8.inv() else req.flags or 8
        req.no_webpage = true
        ConnectionsManager.getInstance(account).inu_replaceNotSentRequest(sending.reqId, req) { replaced ->
            if (!replaced) return@inu_replaceNotSentRequest
            AndroidUtilities.runOnUIThread {
                synchronized(pendingEdits) {
                    if (pendingEdits[sending] === edit) pendingEdits.remove(sending)
                }
            }
        }
        return true
    }

    @JvmStatic
    fun onMessageProcessed(account: Int, localId: Int) {
        val (message, edit) = synchronized(pendingEdits) {
            val entry = pendingEdits.entries.firstOrNull { it.value.account == account && it.value.localId == localId } ?: return
            pendingEdits.remove(entry.key)
            entry.key to entry.value
        }
        if (message.id < 0 || message.send_state != MessageObject.MESSAGE_SEND_STATE_SENT) return

        message.media = edit.media
        message.flags = if (edit.media != null) {
            message.flags or TLRPC.MESSAGE_FLAG_HAS_MEDIA
        } else {
            message.flags and TLRPC.MESSAGE_FLAG_HAS_MEDIA.inv()
        }
        message.invert_media = edit.invertMedia
        val messageObject = MessageObject(account, message, true, true)
        messageObject.editingMessage = edit.text
        messageObject.editingMessageEntities = edit.entities
        messageObject.editingMessageSearchWebPage = edit.searchWebPage
        SendMessagesHelper.getInstance(account).editMessage(messageObject, null, null, null, null, null, null, false, false, null)
    }
}
