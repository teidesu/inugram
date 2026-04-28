package desu.inugram.ui

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.InuHooks
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class InuAppearanceSettingsActivity : InuSettingsPageActivity() {

    private var animationSpeedSlider: SliderCell? = null

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuGeneral)


    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuMiscellaneous)))
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
                TOGGLE_SHOW_SECONDS,
                LocaleController.getString(R.string.InuShowSeconds)
            ).setChecked(InuConfig.SHOW_SECONDS.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_ROUNDING,
                R.string.InuDisableRounding,
                R.string.InuDisableRoundingInfo,
                InuConfig.DISABLE_ROUNDING.value
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_MY_PHONE_NUMBER,
                LocaleController.getString(R.string.InuHideMyPhoneNumber)
            ).setChecked(InuConfig.HIDE_MY_PHONE_NUMBER.value)
        )
        items.add(
            UItem.asButton(
                BUTTON_ICON_REPLACEMENT,
                LocaleController.getString(R.string.InuIconReplacement),
                when (InuConfig.ICON_REPLACEMENT.value) {
                    InuConfig.IconReplacementItem.SOLAR -> LocaleController.getString(R.string.InuIconReplacementSolar)
                    else -> LocaleController.getString(R.string.InuIconReplacementOff)
                }
            )
        )
        items.add(UItem.asShadow(null))

        if (animationSpeedSlider == null) animationSpeedSlider = SliderCell(
            this.context, min = 0.5f, max = 2f,
            defaultValue = InuConfig.ANIMATION_SPEED.default,
            initialValue = if (InuConfig.ANIMATION_SPEED.value >= 2f) 2f else InuConfig.ANIMATION_SPEED.value,
            step = 0.05f,
            format = {
                if (it >= 2f) LocaleController.getString(R.string.InuAnimationSpeedInstant)
                else String.format("%.2fx", it)
            },
            onChanged = {
                InuConfig.ANIMATION_SPEED.value = if (it >= 2f) 9999f else it
                InuHooks.syncAnimationSpeed()
            },
        )
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAnimationSpeed)))
        items.add(UItem.asCustom(animationSpeedSlider))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAnimationSpeedInfo)))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuNonIslandUI)))
        items.add(
            UItem.asCheck(
                TOGGLE_NON_ISLAND_TAB_BARS,
                LocaleController.getString(R.string.InuNonIslandTabBars),
            ).setChecked(InuConfig.NON_ISLAND_TAB_BARS.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_NON_ISLAND_GLOBAL_SEARCH,
                LocaleController.getString(R.string.InuNonIslandGlobalSearch),
            ).setChecked(InuConfig.NON_ISLAND_GLOBAL_SEARCH.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_NON_ISLAND_CHAT_ELEMENTS,
                LocaleController.getString(R.string.InuNonIslandChatElements),
            ).setChecked(InuConfig.NON_ISLAND_CHAT_ELEMENTS.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_FADE_VIEW,
                LocaleController.getString(R.string.InuHideFadeView),
            ).setChecked(InuConfig.HIDE_FADE_VIEW.value)
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuNonIslandHint)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_HIDE_FADE_VIEW -> {
                val new = InuConfig.HIDE_FADE_VIEW.toggle()
                (view as? TextCheckCell)?.isChecked = new
                showRestartBulletin()
            }

            BUTTON_PROFILE_ID_MODE -> showDialog(
                RadioDialogBuilder(context, getResourceProvider())
                    .setTitle(LocaleController.getString(R.string.InuProfileIdMode))
                    .setItems(
                        arrayOf(
                            LocaleController.getString(R.string.InuProfileIdModeOff),
                            LocaleController.getString(R.string.InuProfileIdModeTelegram),
                            LocaleController.getString(R.string.InuProfileIdModeBotApi),
                        ),
                        InuConfig.PROFILE_ID_MODE.value,
                    ) { _, which ->
                        if (which == InuConfig.PROFILE_ID_MODE.value) return@setItems
                        InuConfig.PROFILE_ID_MODE.value = which
                        listView.adapter.update(true)
                    }
                    .create()
            )

            BUTTON_ICON_REPLACEMENT -> showDialog(
                RadioDialogBuilder(context, getResourceProvider())
                    .setTitle(LocaleController.getString(R.string.InuIconReplacement))
                    .setItems(
                        arrayOf(
                            LocaleController.getString(R.string.InuIconReplacementOff),
                            LocaleController.getString(R.string.InuIconReplacementSolar),
                        ),
                        InuConfig.ICON_REPLACEMENT.value,
                    ) { _, which ->
                        if (which == InuConfig.ICON_REPLACEMENT.value) return@setItems
                        InuConfig.ICON_REPLACEMENT.value = which
                        listView.adapter.update(true)
                        showRestartBulletin()
                    }
                    .create()
            )

            TOGGLE_SHOW_SECONDS -> {
                val new = InuConfig.SHOW_SECONDS.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_ROUNDING -> {
                val new = InuConfig.DISABLE_ROUNDING.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_MY_PHONE_NUMBER -> {
                val new = InuConfig.HIDE_MY_PHONE_NUMBER.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_NON_ISLAND_TAB_BARS -> {
                val new = InuConfig.NON_ISLAND_TAB_BARS.toggle()
                (view as? TextCheckCell)?.isChecked = new
                showRestartBulletin()
            }

            TOGGLE_NON_ISLAND_GLOBAL_SEARCH -> {
                val new = InuConfig.NON_ISLAND_GLOBAL_SEARCH.toggle()
                (view as? TextCheckCell)?.isChecked = new
                showRestartBulletin()
            }

            TOGGLE_NON_ISLAND_CHAT_ELEMENTS -> {
                val new = InuConfig.NON_ISLAND_CHAT_ELEMENTS.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }
        }
    }

    companion object {
        private val BUTTON_PROFILE_ID_MODE = InuUtils.generateId()
        private val TOGGLE_HIDE_FADE_VIEW = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_TAB_BARS = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_GLOBAL_SEARCH = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_CHAT_ELEMENTS = InuUtils.generateId()
        private val TOGGLE_SHOW_SECONDS = InuUtils.generateId()
        private val TOGGLE_DISABLE_ROUNDING = InuUtils.generateId()
        private val TOGGLE_HIDE_MY_PHONE_NUMBER = InuUtils.generateId()
        private val BUTTON_ICON_REPLACEMENT = InuUtils.generateId()
    }
}
