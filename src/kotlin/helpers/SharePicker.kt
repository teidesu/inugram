package desu.inugram.helpers

import android.os.Bundle
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.UserConfig
import org.telegram.ui.ChatActivity
import org.telegram.ui.DialogsActivity
import org.telegram.ui.LaunchActivity
import java.io.File

object SharePicker {
    fun shareFile(
        activity: LaunchActivity,
        file: File,
        mime: String,
        onSent: () -> Unit = {},
    ) {
        val account = UserConfig.selectedAccount
        val args = Bundle().apply {
            putBoolean("onlySelect", true)
            putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD)
            putBoolean("canSelectTopics", true)
            putBoolean("allowSwitchAccount", true)
        }
        val picker = DialogsActivity(args)
        picker.setDelegate { fragment, dids, _, _, _, _, _, _ ->
            for (key in dids) {
                SendMessagesHelper.prepareSendingDocument(
                    AccountInstance.getInstance(account),
                    file.absolutePath, file.absolutePath,
                    null, null, mime, key.dialogId,
                    null, null, null, null, null,
                    true, 0, null, null, 0, false,
                )
            }
            onSent()
            if (dids.size == 1) openChat(fragment, dids[0].dialogId)
            true
        }
        activity.actionBarLayout.presentFragment(picker)
    }

    private fun openChat(from: DialogsActivity, did: Long) {
        val args = Bundle().apply {
            putBoolean("scrollToTopOnResume", true)
            when {
                DialogObject.isEncryptedDialog(did) -> putInt("enc_id", DialogObject.getEncryptedChatId(did))
                DialogObject.isUserDialog(did) -> putLong("user_id", did)
                else -> putLong("chat_id", -did)
            }
        }
        val mc = MessagesController.getInstance(from.currentAccount)
        if (mc.checkCanOpenChat(args, from)) {
            from.presentFragment(ChatActivity(args), true)
        }
    }
}
