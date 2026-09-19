package desu.inugram.helpers

import android.os.Bundle
import androidx.collection.LongSparseArray
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.ShareAlert
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
                    true, 0, null, null, false,
                )
            }
            onSent()
            if (dids.size == 1) openChat(fragment, dids[0].dialogId, replace = true)
            true
        }
        activity.actionBarLayout.presentFragment(picker)
    }

    /** stock's share sheet over [fragment], sending [file] to every dialog picked in it */
    fun showShareSheet(fragment: BaseFragment, file: File, mime: String) {
        val context = fragment.parentActivity ?: return
        val account = fragment.accountInstance
        fragment.showDialog(object : ShareAlert(context, null, null, false, null, false) {
            override fun onSend(
                dids: LongSparseArray<TLRPC.Dialog>,
                count: Int,
                topic: TLRPC.TL_forumTopic?,
                showToast: Boolean,
            ) {
                for (i in 0 until dids.size()) {
                    SendMessagesHelper.prepareSendingDocument(
                        account,
                        file.absolutePath, file.absolutePath,
                        null, null, mime, dids.keyAt(i),
                        null, null, null, null, null,
                        true, 0, null, null, false,
                    )
                }
                if (dids.size() == 1) openChat(fragment, dids.keyAt(0), replace = false)
            }
        })
    }

    private fun openChat(from: BaseFragment, did: Long, replace: Boolean) {
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
            from.presentFragment(ChatActivity(args), replace)
        }
    }
}
