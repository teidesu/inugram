package desu.inugram.helpers

import android.graphics.drawable.Drawable
import android.view.View
import desu.inugram.InuConfig
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions

object ProfileHelper {
    const val ACTION_TOGGLE_HIDE_WALLPAPER = 505
    const val ACTION_TOGGLE_HIDE_THEME = 506

    @JvmStatic
    fun shouldShowIdRow(): Boolean {
        return InuConfig.PROFILE_ID_MODE.value != InuConfig.ProfileIdModeItem.OFF
    }

    private fun getRawDialogWallpaper(currentAccount: Int, dialogId: Long): TLRPC.WallPaper? {
        val controller = MessagesController.getInstance(currentAccount)
        return if (dialogId >= 0) controller.getUserFull(dialogId)?.wallpaper
        else controller.getChatFull(-dialogId)?.wallpaper
    }

    private fun hasRawDialogTheme(currentAccount: Int, dialogId: Long): Boolean {
        val controller = MessagesController.getInstance(currentAccount)
        val emoticon = if (dialogId >= 0) controller.getUserFull(dialogId)?.theme_emoticon
        else controller.getChatFull(-dialogId)?.theme_emoticon
        return !emoticon.isNullOrEmpty()
    }

    @JvmStatic
    fun addMenuItems(otherItem: ActionBarMenuItem?, currentAccount: Int, dialogId: Long) {
        if (otherItem == null) return
        if (!InuConfig.DISABLE_CHAT_BACKGROUNDS.value && getRawDialogWallpaper(currentAccount, dialogId) != null) {
            val hidden = ChatHelper.isRemoveWallpaperSetForDialog(currentAccount, dialogId)
            val label = if (hidden) R.string.InuShowCustomWallpaper else R.string.InuHideCustomWallpaper
            otherItem.addSubItem(
                ACTION_TOGGLE_HIDE_WALLPAPER,
                R.drawable.menu_feature_wallpaper,
                LocaleController.getString(label),
            )
        }
        if (!InuConfig.DISABLE_CHAT_THEMES.value && hasRawDialogTheme(currentAccount, dialogId)) {
            val hidden = ChatHelper.isRemoveThemeSetForDialog(currentAccount, dialogId)
            val label = if (hidden) R.string.InuShowCustomTheme else R.string.InuHideCustomTheme
            otherItem.addSubItem(
                ACTION_TOGGLE_HIDE_THEME,
                R.drawable.msg_theme,
                LocaleController.getString(label),
            )
        }
    }

    @JvmStatic
    fun handleMenuClick(id: Int, currentAccount: Int, dialogId: Long): Boolean {
        when (id) {
            ACTION_TOGGLE_HIDE_WALLPAPER -> ChatHelper.toggleRemoveWallpaper(currentAccount, dialogId)
            ACTION_TOGGLE_HIDE_THEME -> ChatHelper.toggleRemoveTheme(currentAccount, dialogId)
            else -> return false
        }
        return true
    }

    @JvmStatic
    fun formatId(userId: Long, chat: TLRPC.Chat?): String {
        val isBotApi = InuConfig.PROFILE_ID_MODE.value == InuConfig.ProfileIdModeItem.BOT_API_ID
        if (userId != 0L) {
            return userId.toString()
        }

        if (chat != null) {
            if (!isBotApi) return chat.id.toString()
            if (ChatObject.isChannel(chat)) return (-1000000000000L - chat.id).toString()
            return (-chat.id).toString()
        }

        return ""
    }

    @JvmStatic
    fun onIdRowClick(
        fragment: BaseFragment,
        clipBackground: Drawable,
        view: View,
        userId: Long,
        chatId: Long,
        accountInstance: AccountInstance,
    ) {
        val messagesController = accountInstance.messagesController;
        val chat = if (chatId != 0L) messagesController.getChat(chatId) else null
        val text = formatId(userId, chat)
        if (text.isEmpty()) return

        ItemOptions.makeOptions(fragment, view)
            .setScrimViewBackground(clipBackground)
            .add(R.drawable.msg_copy, LocaleController.getString(R.string.Copy)) {
                AndroidUtilities.addToClipboard(text)
                if (AndroidUtilities.shouldShowClipboardToast()) {
                    BulletinFactory.of(fragment).createCopyBulletin(
                        LocaleController.getString(R.string.InuProfileIdCopied)
                    ).show()
                }
            }.add(R.drawable.inu_tabler_code, LocaleController.getString(R.string.InuShowJson)) {
                val items = arrayListOf<TLObject>()
                if (userId != 0L) {
                    val user = messagesController.getUser(userId)
                    if (user != null) items.add(user)
                    val userFull = messagesController.getUserFull(userId)
                    if (userFull != null) items.add(userFull)
                    val botInfo = accountInstance.mediaDataController.getBotInfoCached(userId, userId)
                    if (botInfo != null) items.add(botInfo)
                } else {
                    if (chat != null) items.add(chat)
                    val chatFull = messagesController.getChatFull(chatId)
                    if (chatFull != null) items.add(chatFull)
                }
                WebAppHelper.openTlViewer(fragment, items)
            }.show()
    }
}
