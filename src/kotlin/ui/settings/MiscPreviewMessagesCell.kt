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
class MiscPreviewMessagesCell(context: Context?, fragment: BaseFragment) :
    MessagesPreviewCell(context, fragment, buildMessages()) {

    companion object {
        private fun buildMessages(): Array<MessageObject> {
            val now = (System.currentTimeMillis() / 1000).toInt() - 60 * 60
            val selfId = UserConfig.getInstance(UserConfig.selectedAccount).clientUserId

            val forwardedTlMessage = TLRPC.TL_message().apply {
                message = getString(R.string.InuMiscPreviewForwardedMessage)
                date = now + 1270
                dialog_id = -1
                flags = 259 or TLRPC.MESSAGE_FLAG_FWD
                id = 1
                fwd_from = TLRPC.TL_messageFwdHeader().apply {
                    flags = flags or 32
                    from_name = getString(R.string.InuMiscPreviewForwardedFrom)
                    date = now - 60 * 60 * 20
                }
                media = TLRPC.TL_messageMediaEmpty()
                out = false
                peer_id = TLRPC.TL_peerUser().apply { user_id = 1 }
            }

            val editedTlMessage = TLRPC.TL_message().apply {
                message = getString(R.string.InuMiscPreviewEditedMessage)
                date = now + 1290
                dialog_id = 1
                flags = 259 or TLRPC.MESSAGE_FLAG_EDITED
                id = 2
                edit_date = now + 1350
                from_id = TLRPC.TL_peerUser().apply { user_id = selfId }
                media = TLRPC.TL_messageMediaEmpty()
                out = true
                peer_id = TLRPC.TL_peerUser().apply { user_id = 0 }
            }

            return arrayOf(
                MessageObject(UserConfig.selectedAccount, forwardedTlMessage, true, false),
                MessageObject(UserConfig.selectedAccount, editedTlMessage, true, false),
            )
        }
    }
}
