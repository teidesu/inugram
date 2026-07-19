package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment

@SuppressLint("ViewConstructor")
class StickerSizePreviewMessagesCell(context: Context?, fragment: BaseFragment) :
    MessagesPreviewCell(context, fragment, buildMessages()) {

    companion object {
        private fun buildMessages(): Array<MessageObject> {
            val now = (System.currentTimeMillis() / 1000).toInt() - 60 * 60
            val selfId = UserConfig.getInstance(UserConfig.selectedAccount).clientUserId

            val stickerTlMessage = TLRPC.TL_message().apply {
                date = now + 10
                dialog_id = 1
                flags = 257
                from_id = TLRPC.TL_peerUser().apply { user_id = selfId }
                id = 1
                media = TLRPC.TL_messageMediaDocument().apply {
                    flags = 1
                    document = TLRPC.TL_document().apply {
                        mime_type = "image/webp"
                        file_reference = ByteArray(0)
                        access_hash = 0
                        date = now
                        attributes.add(TLRPC.TL_documentAttributeSticker().apply { alt = "🐱" })
                        attributes.add(TLRPC.TL_documentAttributeImageSize().apply {
                            h = 512
                            w = 512
                        })
                    }
                }
                message = ""
                out = true
                peer_id = TLRPC.TL_peerUser().apply { user_id = 0 }
            }

            val textTlMessage = TLRPC.TL_message().apply {
                message = getString(R.string.InuStickerSizeDialogMessage)
                date = now + 1270
                dialog_id = -1
                flags = 259
                id = 3
                reply_to = TLRPC.TL_messageReplyHeader().apply {
                    flags = flags or 16
                    reply_to_msg_id = 2
                }
                media = TLRPC.TL_messageMediaEmpty()
                out = false
                peer_id = TLRPC.TL_peerUser().apply { user_id = 1 }
            }

            val stickerMessageObject = MessageObject(UserConfig.selectedAccount, stickerTlMessage, true, false).apply {
                useCustomPhoto = true
            }
            val textMessageObject = MessageObject(UserConfig.selectedAccount, textTlMessage, true, false).apply {
                replyMessageObject = stickerMessageObject
            }
            return arrayOf(stickerMessageObject, textMessageObject)
        }
    }
}
