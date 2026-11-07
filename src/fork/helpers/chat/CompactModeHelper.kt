package desu.inugram.helpers.chat

import desu.inugram.InuConfig
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController

object CompactModeHelper {
    @JvmStatic
    fun isEnabledFor(account: Int, dialogId: Long): Boolean {
        if (DialogObject.isEncryptedDialog(dialogId) || dialogId > 0) return InuConfig.COMPACT_MODE_DMS.value
        val chat = MessagesController.getInstance(account).getChat(-dialogId) ?: return false
        return if (ChatObject.isChannelAndNotMegaGroup(chat)) InuConfig.COMPACT_MODE_CHANNELS.value
        else InuConfig.COMPACT_MODE_GROUPS.value
    }
}
