package desu.inugram.ui.settings

import desu.inugram.InuConfig
import desu.inugram.helpers.menu.ChatMenuConfig
// #if PLUGINS
import desu.inugram.helpers.plugins.ui.PluginActions
// #endif
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R

class ChatMenuOrderActivity : MenuOrderActivity<ChatMenuConfig.Item>() {
    override val config get() = InuConfig.CHAT_MENU_ITEMS
    override val infoStringRes = R.string.InuChatMenuOrderInfo
    override val headerStringRes = R.string.InuChatMenuItems
    override val resetStringRes = R.string.InuChatMenuReset
    // #if PLUGINS
    override val pluginActionKind = PluginActions.KIND_CHAT
    // #endif

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuChatMenuOrder)
}
