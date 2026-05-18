package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class UserProfileSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuUserProfile)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuUserProfileHeader)))
        items.add(
            UItem.asCheck(
                TOGGLE_PROFILE_PHOTO_GRADIENT_FADE,
                LocaleController.getString(R.string.InuProfilePhotoGradientFade)
            ).setChecked(InuConfig.PROFILE_PHOTO_GRADIENT_FADE.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_REDUCE_PROFILE_MOTION,
                LocaleController.getString(R.string.InuReduceProfileMotion),
            ).setChecked(InuConfig.REDUCE_PROFILE_MOTION.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_PROFILE_SCROLL_SNAP,
                R.string.InuDisableProfileScrollSnap,
                R.string.InuDisableProfileScrollSnapInfo,
                InuConfig.DISABLE_PROFILE_SCROLL_SNAP.value
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_PROFILE_PREFER_MEDIA_TAB,
                R.string.InuProfilePreferMediaTab,
                R.string.InuProfilePreferMediaTabInfo,
                InuConfig.PROFILE_PREFER_MEDIA_TAB.value
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuUserProfileInformation)))
        items.add(
            UItem.asButton(
                BUTTON_PROFILE_ID_MODE,
                LocaleController.getString(R.string.InuProfileIdMode),
                when (InuConfig.PROFILE_ID_MODE.value) {
                    InuConfig.ProfileIdModeItem.TELEGRAM_ID -> LocaleController.getString(R.string.InuProfileIdModeTelegram)
                    InuConfig.ProfileIdModeItem.BOT_API_ID -> LocaleController.getString(R.string.InuProfileIdModeBotApi)
                    else -> LocaleController.getString(R.string.InuProfileIdModeOff)
                }
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_MY_PHONE_NUMBER,
                LocaleController.getString(R.string.InuHideMyPhoneNumber)
            ).setChecked(InuConfig.HIDE_MY_PHONE_NUMBER.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_CHAT_TITLE_PHONE,
                LocaleController.getString(R.string.InuDisableChatTitlePhone)
            ).setChecked(InuConfig.DISABLE_CHAT_TITLE_PHONE.value)
        )
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_PROFILE_PHOTO_GRADIENT_FADE -> {
                val new = InuConfig.PROFILE_PHOTO_GRADIENT_FADE.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_REDUCE_PROFILE_MOTION -> {
                val new = InuConfig.REDUCE_PROFILE_MOTION.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_PROFILE_SCROLL_SNAP -> {
                val new = InuConfig.DISABLE_PROFILE_SCROLL_SNAP.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_PROFILE_PREFER_MEDIA_TAB -> {
                val new = InuConfig.PROFILE_PREFER_MEDIA_TAB.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            BUTTON_PROFILE_ID_MODE -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuProfileIdModeOff),
                    LocaleController.getString(R.string.InuProfileIdModeTelegram),
                    LocaleController.getString(R.string.InuProfileIdModeBotApi),
                ),
                InuConfig.PROFILE_ID_MODE.value,
            ) { which ->
                InuConfig.PROFILE_ID_MODE.value = which
            }

            TOGGLE_HIDE_MY_PHONE_NUMBER -> {
                val new = InuConfig.HIDE_MY_PHONE_NUMBER.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_CHAT_TITLE_PHONE -> {
                val new = InuConfig.DISABLE_CHAT_TITLE_PHONE.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }
        }
    }

    companion object {
        private val TOGGLE_PROFILE_PHOTO_GRADIENT_FADE = InuUtils.generateId()
        private val TOGGLE_REDUCE_PROFILE_MOTION = InuUtils.generateId()
        private val TOGGLE_DISABLE_PROFILE_SCROLL_SNAP = InuUtils.generateId()
        private val TOGGLE_PROFILE_PREFER_MEDIA_TAB = InuUtils.generateId()
        private val BUTTON_PROFILE_ID_MODE = InuUtils.generateId()
        private val TOGGLE_HIDE_MY_PHONE_NUMBER = InuUtils.generateId()
        private val TOGGLE_DISABLE_CHAT_TITLE_PHONE = InuUtils.generateId()
    }
}
