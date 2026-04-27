package desu.inugram.ui

import android.view.View
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class InuSettingsActivity : InuSettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuSettings)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAppearance)))
        items.add(
            UItem.asButton(
                BUTTON_GENERAL,
                R.drawable.msg_settings_old,
                LocaleController.getString(R.string.InuGeneral)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_CHATS,
                R.drawable.msg_discussion,
                LocaleController.getString(R.string.Chats)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_DIALOGS,
                R.drawable.msg_folders,
                LocaleController.getString(R.string.InuMainPage)
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
                BUTTON_ABOUT,
                R.drawable.msg_info,
                LocaleController.getString(R.string.InuAbout)
            )
        )
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_GENERAL -> presentFragment(InuAppearanceSettingsActivity())
            BUTTON_CHATS -> presentFragment(InuChatsSettingsActivity())
            BUTTON_DIALOGS -> presentFragment(InuDialogsSettingsActivity())
            BUTTON_ANNOYANCES -> presentFragment(InuAnnoyancesSettingsActivity())
            BUTTON_BEHAVIOR -> presentFragment(InuBehaviorSettingsActivity())
            BUTTON_ABOUT -> presentFragment(InuAboutActivity())
        }
    }

    companion object {
        private val BUTTON_GENERAL = InuUtils.generateId()
        private val BUTTON_CHATS = InuUtils.generateId()
        private val BUTTON_DIALOGS = InuUtils.generateId()
        private val BUTTON_ANNOYANCES = InuUtils.generateId()
        private val BUTTON_BEHAVIOR = InuUtils.generateId()
        private val BUTTON_ABOUT = InuUtils.generateId()
    }
}
