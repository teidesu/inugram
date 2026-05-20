package desu.inugram.ui.settings

import android.view.View
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.ParanoiaHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class PrivacySecurityActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuPrivacySecurity)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            UItem.asButton(
                BUTTON_PASSCODE,
                R.drawable.msg_permissions,
                LocaleController.getString(R.string.InuPerAccountPasscode)
            )
        )
        if (!ParanoiaHelper.isParanoia()) {
            items.add(
                UItem.asButton(
                    BUTTON_PARANOIA,
                    R.drawable.menu_hide_gift,
                    LocaleController.getString(R.string.InuParanoiaMode)
                )
            )
        }
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_PASSCODE -> presentFragment(PasscodeSettingsActivity())
            BUTTON_PARANOIA -> presentFragment(ParanoiaActivity())
        }
    }

    companion object {
        private val BUTTON_PASSCODE = InuUtils.generateId()
        private val BUTTON_PARANOIA = InuUtils.generateId()
    }
}
