package desu.inugram.helpers

import desu.inugram.InuConfig
import desu.inugram.helpers.chat.BlockedMessagesHelper
import desu.inugram.helpers.security.ParanoiaHelper
import desu.inugram.helpers.security.PasscodeHelper
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R

object NotificationsHelper {
    @JvmStatic
    fun smallIconRes(): Int = when (InuConfig.NOTIFICATION_ICON.value) {
        InuConfig.NotificationIconItem.TELEGRAM -> R.drawable.notification
        else -> R.drawable.icon_notification_inu
    }

    @JvmStatic
    fun shouldSuppressNotifications(account: Int): Boolean =
        PasscodeHelper.isAccountHidden(account) || ParanoiaHelper.shouldSuppressNotifications()

    @JvmStatic
    fun shouldSuppressMessageNotification(messageObject: MessageObject?): Boolean {
        if (messageObject == null) return false
        return BlockedMessagesHelper.shouldHide(messageObject)
            || ParanoiaHelper.isHidden(messageObject.currentAccount, messageObject.dialogId)
    }
}
