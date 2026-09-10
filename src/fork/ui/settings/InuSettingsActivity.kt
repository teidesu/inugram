package desu.inugram.ui.settings

import android.view.View
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class InuSettingsActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuSettings)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAppearance)))
        items.add(
            UItem.asButton(
                BUTTON_GENERAL,
                R.drawable.msg_palette,
                LocaleController.getString(R.string.InuLookAndFeel)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_CHATS,
                R.drawable.msg_discussion,
                LocaleController.getString(R.string.MainTabsChats)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_MESSAGES,
                R.drawable.msg_discuss,
                LocaleController.getString(R.string.InuMessages)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_DIALOGS,
                R.drawable.msg_viewchats,
                LocaleController.getString(R.string.InuMainPage)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_USER_PROFILE,
                R.drawable.msg_openprofile,
                LocaleController.getString(R.string.InuUserProfile)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_ANNOYANCES,
                R.drawable.menu_hide_gift,
                LocaleController.getString(R.string.InuAnnoyances)
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuOther)))
        items.add(
            UItem.asButton(
                BUTTON_BEHAVIOR,
                R.drawable.avd_speed,
                LocaleController.getString(R.string.InuBehavior)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_TRANSLATOR,
                R.drawable.msg_translate,
                LocaleController.getString(R.string.InuTranslator)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_PLUGINS,
                R.drawable.inu_tabler_code,
                addExperimentalSpan(LocaleController.getString(R.string.InuPlugins))
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_PRIVACY,
                R.drawable.msg_permissions,
                LocaleController.getString(R.string.InuPrivacySecurity)
            )
        )
        items.add(UItem.asShadow(null))

        items.add(
            UItem.asButton(
                BUTTON_BACKUP,
                R.drawable.inu_tabler_cloud,
                LocaleController.getString(R.string.InuBackupSettings)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_ABOUT,
                R.drawable.msg_info,
                LocaleController.getString(R.string.InuAbout)
            )
        )
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_GENERAL -> presentFragment(AppearanceSettingsActivity())
            BUTTON_CHATS -> presentFragment(ChatsSettingsActivity())
            BUTTON_MESSAGES -> presentFragment(MessagesSettingsActivity())
            BUTTON_DIALOGS -> presentFragment(DialogsSettingsActivity())
            BUTTON_USER_PROFILE -> presentFragment(UserProfileSettingsActivity())
            BUTTON_ANNOYANCES -> presentFragment(AnnoyancesSettingsActivity())
            BUTTON_BEHAVIOR -> presentFragment(BehaviorSettingsActivity())
            BUTTON_TRANSLATOR -> presentFragment(TranslatorSettingsActivity())
            BUTTON_PLUGINS -> presentFragment(PluginsActivity())
            BUTTON_PRIVACY -> presentFragment(PrivacySecurityActivity())
            BUTTON_ABOUT -> presentFragment(AboutActivity())
            BUTTON_BACKUP -> presentFragment(BackupSettingsActivity())
        }
    }

    companion object {
        private val BUTTON_GENERAL = InuUtils.generateId()
        private val BUTTON_CHATS = InuUtils.generateId()
        private val BUTTON_MESSAGES = InuUtils.generateId()
        private val BUTTON_DIALOGS = InuUtils.generateId()
        private val BUTTON_USER_PROFILE = InuUtils.generateId()
        private val BUTTON_ANNOYANCES = InuUtils.generateId()
        private val BUTTON_BEHAVIOR = InuUtils.generateId()
        private val BUTTON_TRANSLATOR = InuUtils.generateId()
        private val BUTTON_PLUGINS = InuUtils.generateId()
        private val BUTTON_PRIVACY = InuUtils.generateId()
        private val BUTTON_ABOUT = InuUtils.generateId()
        private val BUTTON_BACKUP = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "root",
            titleRes = R.string.InuSettings,
            iconRes = R.drawable.msg_settings,
            factory = ::InuSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("about", R.string.InuAbout, BUTTON_ABOUT),
            ),
        )
    }
}
