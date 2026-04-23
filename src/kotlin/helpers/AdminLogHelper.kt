package desu.inugram.helpers

import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC

object AdminLogHelper {
    @JvmStatic
    fun injectOldMediaBubble(
        accountNum: Int,
        event: TLRPC.TL_channelAdminLogEvent,
        messageObjects: ArrayList<MessageObject>,
        chat: TLRPC.Chat,
        mid: IntArray,
        addToEnd: Boolean,
    ) {
        val action = event.action as? TLRPC.TL_channelAdminLogEventActionEditMessage ?: return
        val oldMessage = action.prev_message ?: return
        val newMessage = action.new_message ?: return
        val oldMedia = oldMessage.media ?: return
        val newMedia = newMessage.media ?: return

        if (oldMedia is TLRPC.TL_messageMediaEmpty || oldMedia is TLRPC.TL_messageMediaWebPage) return
        if (newMedia is TLRPC.TL_messageMediaEmpty || newMedia is TLRPC.TL_messageMediaWebPage) return

        val differentClass = newMedia.javaClass != oldMedia.javaClass
        val differentPhoto = newMedia.photo != null && oldMedia.photo != null && newMedia.photo.id != oldMedia.photo.id
        val differentDoc =
            newMedia.document != null && oldMedia.document != null && newMedia.document.id != oldMedia.document.id
        if (!differentClass && !differentPhoto && !differentDoc) return

        val peer = TLRPC.TL_peerChannel().apply { channel_id = chat.id }

        val labelMsg = TLRPC.TL_messageService().apply {
            this.action = TLRPC.TL_messageActionEmpty()
            peer_id = peer
            date = event.date
            id = mid[0]++
            dialog_id = -chat.id
            from_id = TLRPC.TL_peerUser().apply { user_id = event.user_id }
        }
        val label = MessageObject(accountNum, labelMsg, false, false)
        label.messageText = LocaleController.getString(R.string.InuEventLogMediaBeforeEdit)
        label.messageOwner.message = label.messageText.toString()
        label.contentType = 1
        label.currentEvent = event

        val mediaMsg = TLRPC.TL_message().apply {
            out = false
            unread = false
            peer_id = peer
            date = event.date
            realId = oldMessage.id
            id = mid[0]++
            from_id = newMessage.from_id ?: TLRPC.TL_peerUser().apply { user_id = event.user_id }
            dialog_id = -chat.id
            media = oldMedia
            message = oldMessage.message ?: ""
            if (oldMessage.entities != null) entities = oldMessage.entities
        }
        val mo = MessageObject(accountNum, mediaMsg, null, null, true, true, event.id)
        mo.currentEvent = event
        if (mo.contentType < 0) return

        val player = MediaController.getInstance()
        if (player.isPlayingMessage(mo)) {
            val playing = player.playingMessageObject
            mo.audioProgress = playing.audioProgress
            mo.audioProgressSec = playing.audioProgressSec
        }

        if (addToEnd) {
            messageObjects.add(0, mo)
            messageObjects.add(0, label)
        } else {
            messageObjects.add(messageObjects.size - 1, label)
            messageObjects.add(messageObjects.size - 1, mo)
        }
    }
}
