package desu.inugram.ui.settings

import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import desu.inugram.InuConfig
import desu.inugram.InuHooks
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.theme.MonetHelper
import desu.inugram.ui.settings.fonts.FontsSettingsActivity
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AppearanceSettingsActivity : SettingsPageActivity() {

    private var animationSpeedSlider: SliderCell? = null

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuLookAndFeel)


    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuTypographyAndIcons)))
        items.add(mkSubPageButton(BUTTON_FONTS, LocaleController.getString(R.string.InuFonts)))
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
        items.add(
            UItem.asButton(
                BUTTON_NOTIFICATION_ICON,
                LocaleController.getString(R.string.InuNotificationIcon),
                when (InuConfig.NOTIFICATION_ICON.value) {
                    InuConfig.NotificationIconItem.INUGRAM -> LocaleController.getString(R.string.InuNotificationIconInugram)
                    else -> LocaleController.getString(R.string.InuNotificationIconTelegram)
                }
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(addExperimentalSpan(LocaleController.getString(R.string.InuMaterial3))))
        items.add(
            UItem.asCheck(
                TOGGLE_MATERIAL3_SWITCHES,
                LocaleController.getString(R.string.InuMaterial3Switches)
            ).setChecked(InuConfig.MATERIAL3_SWITCHES.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_MATERIAL3_FABS,
                LocaleController.getString(R.string.InuMaterial3Fabs)
            ).setChecked(InuConfig.MATERIAL3_FABS.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_M3_SECTIONS_STYLE,
                LocaleController.getString(R.string.InuMaterial3Sections)
            ).setChecked(InuConfig.M3_SECTIONS_STYLE.value)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            items.add(
                UItem.asButton(
                    BUTTON_MONET_THEME,
                    LocaleController.getString(R.string.InuMonetTheme),
                    monetThemeModeLabel(MonetHelper.getThemeMode()),
                )
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            items.add(
                UItem.asButton(
                    BUTTON_PREDICTIVE_BACK_MODE,
                    LocaleController.getString(R.string.InuPredictiveBack),
                    predictiveBackModeLabel(InuConfig.PREDICTIVE_BACK_MODE.value),
                )
            )
        }
        items.add(UItem.asShadow(null))

        items.add(
            UItem.asHeader(addExperimentalSpan(LocaleController.getString(R.string.InuNonIslandUI)))
        )
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
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_GLASS_GLARE,
                LocaleController.getString(R.string.InuDisableGlassGlare),
            ).setChecked(InuConfig.DISABLE_GLASS_GLARE.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_SCRIM_BLUR,
                R.string.InuDisableScrimBlur,
                R.string.InuDisableScrimBlurInfo,
                InuConfig.DISABLE_SCRIM_BLUR.value
            )
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuNonIslandHint)))

        if (animationSpeedSlider == null) animationSpeedSlider = SliderCell(
            this.context, min = 0.5f, max = 3f,
            defaultValue = InuConfig.ANIMATION_SPEED.default,
            initialValue = if (InuConfig.ANIMATION_SPEED.value >= 3f) 3f else InuConfig.ANIMATION_SPEED.value,
            step = 0.05f,
            title = LocaleController.getString(R.string.InuAnimationSpeed),
            format = {
                if (it >= 3f) LocaleController.getString(R.string.InuAnimationSpeedInstant)
                else String.format("%.2fx", it)
            },
            onChanged = {
                InuConfig.ANIMATION_SPEED.value = if (it >= 3f) 9999f else it
                InuHooks.syncAnimationSpeed()
            },
        )

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuMotion)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_REDUCE_MENU_MOTION,
                R.string.InuReduceMenuMotion,
                R.string.InuReduceMenuMotionInfo,
                InuConfig.REDUCE_MENU_MOTION.value
            )
        )
        items.add(UItem.asCustom(animationSpeedSlider))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAnimationSpeedInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_HIDE_FADE_VIEW -> {
                val new = InuConfig.HIDE_FADE_VIEW.toggle()
                (view as? TextCheckCell)?.isChecked = new
                softRebuild()
            }

            BUTTON_ICON_REPLACEMENT -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuIconReplacementOff),
                    LocaleController.getString(R.string.InuIconReplacementSolar),
                ),
                InuConfig.ICON_REPLACEMENT.value,
            ) { which ->
                InuConfig.ICON_REPLACEMENT.value = which
                showRestartBulletin()
            }

            BUTTON_NOTIFICATION_ICON -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuNotificationIconTelegram),
                    LocaleController.getString(R.string.InuNotificationIconInugram),
                ),
                InuConfig.NOTIFICATION_ICON.value,
            ) { which ->
                InuConfig.NOTIFICATION_ICON.value = which
            }

            BUTTON_FONTS -> presentFragment(FontsSettingsActivity())

            TOGGLE_DISABLE_SCRIM_BLUR -> {
                val new = InuConfig.DISABLE_SCRIM_BLUR.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_GLASS_GLARE -> {
                val new = InuConfig.DISABLE_GLASS_GLARE.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_REDUCE_MENU_MOTION -> {
                val new = InuConfig.REDUCE_MENU_MOTION.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_MATERIAL3_SWITCHES -> {
                val new = InuConfig.MATERIAL3_SWITCHES.toggle()
                (view as? TextCheckCell)?.isChecked = new
                invalidateVisibleRows()
                softRebuild()
            }

            TOGGLE_MATERIAL3_FABS -> {
                val new = InuConfig.MATERIAL3_FABS.toggle()
                (view as? TextCheckCell)?.isChecked = new
                softRebuild()
            }

            TOGGLE_M3_SECTIONS_STYLE -> {
                val new = InuConfig.M3_SECTIONS_STYLE.toggle()
                (view as? TextCheckCell)?.isChecked = new
                softRebuild()
                inu_rebuildSelf()
            }

            TOGGLE_NON_ISLAND_TAB_BARS -> {
                val new = InuConfig.NON_ISLAND_TAB_BARS.toggle()
                (view as? TextCheckCell)?.isChecked = new
                softRebuild()
            }

            TOGGLE_NON_ISLAND_GLOBAL_SEARCH -> {
                val new = InuConfig.NON_ISLAND_GLOBAL_SEARCH.toggle()
                (view as? TextCheckCell)?.isChecked = new
                softRebuild()
            }

            TOGGLE_NON_ISLAND_CHAT_ELEMENTS -> {
                val new = InuConfig.NON_ISLAND_CHAT_ELEMENTS.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            BUTTON_MONET_THEME -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuMonetThemeDisabled),
                    LocaleController.getString(R.string.InuMonetThemeLight),
                    LocaleController.getString(R.string.InuMonetThemeDark),
                    LocaleController.getString(R.string.InuMonetThemeAmoled),
                    LocaleController.getString(R.string.InuMonetThemeAuto),
                    LocaleController.getString(R.string.InuMonetThemeAutoAmoled),
                ),
                MonetHelper.getThemeMode().ordinal,
            ) { which ->
                MonetHelper.setThemeMode(MonetHelper.ThemeMode.entries[which])
            }

            BUTTON_PREDICTIVE_BACK_MODE -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuPredictiveBackOff),
                    LocaleController.getString(R.string.InuPredictiveBackStock),
                    LocaleController.getString(R.string.InuPredictiveBackMaterial3),
                ),
                InuConfig.PREDICTIVE_BACK_MODE.value,
            ) { which ->
                if (InuConfig.PREDICTIVE_BACK_MODE.value == which) return@show
                InuConfig.PREDICTIVE_BACK_MODE.value = which
                showRestartBulletin()
            }
        }
    }

    companion object {
        private val TOGGLE_HIDE_FADE_VIEW = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_TAB_BARS = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_GLOBAL_SEARCH = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_CHAT_ELEMENTS = InuUtils.generateId()
        private val BUTTON_FONTS = InuUtils.generateId()
        private val TOGGLE_DISABLE_SCRIM_BLUR = InuUtils.generateId()
        private val TOGGLE_DISABLE_GLASS_GLARE = InuUtils.generateId()
        private val TOGGLE_REDUCE_MENU_MOTION = InuUtils.generateId()
        private val TOGGLE_MATERIAL3_SWITCHES = InuUtils.generateId()
        private val TOGGLE_MATERIAL3_FABS = InuUtils.generateId()
        private val TOGGLE_M3_SECTIONS_STYLE = InuUtils.generateId()
        private val BUTTON_ICON_REPLACEMENT = InuUtils.generateId()
        private val BUTTON_NOTIFICATION_ICON = InuUtils.generateId()
        private val BUTTON_PREDICTIVE_BACK_MODE = InuUtils.generateId()
        private val BUTTON_MONET_THEME = InuUtils.generateId()

        @RequiresApi(Build.VERSION_CODES.S)
        private fun monetThemeModeLabel(mode: MonetHelper.ThemeMode): String = when (mode) {
            MonetHelper.ThemeMode.LIGHT -> LocaleController.getString(R.string.InuMonetThemeLight)
            MonetHelper.ThemeMode.DARK -> LocaleController.getString(R.string.InuMonetThemeDark)
            MonetHelper.ThemeMode.AMOLED -> LocaleController.getString(R.string.InuMonetThemeAmoled)
            MonetHelper.ThemeMode.AUTO -> LocaleController.getString(R.string.InuMonetThemeAuto)
            MonetHelper.ThemeMode.AUTO_AMOLED -> LocaleController.getString(R.string.InuMonetThemeAutoAmoled)
            else -> LocaleController.getString(R.string.InuMonetThemeDisabled)
        }

        private fun predictiveBackModeLabel(value: Int): String = when (value) {
            InuConfig.PredictiveBackModeItem.OFF -> LocaleController.getString(R.string.InuPredictiveBackOff)
            InuConfig.PredictiveBackModeItem.STOCK -> LocaleController.getString(R.string.InuPredictiveBackStock)
            else -> LocaleController.getString(R.string.InuPredictiveBackMaterial3)
        }

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "appearance",
            titleRes = R.string.InuLookAndFeel,
            iconRes = R.drawable.msg_settings_old,
            factory = ::AppearanceSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("disable-scrim-blur", R.string.InuDisableScrimBlur, TOGGLE_DISABLE_SCRIM_BLUR),
                SearchRegistry.Entry("disable-glass-glare", R.string.InuDisableGlassGlare, TOGGLE_DISABLE_GLASS_GLARE),
                SearchRegistry.Entry("reduce-menu-motion", R.string.InuReduceMenuMotion, TOGGLE_REDUCE_MENU_MOTION),
                SearchRegistry.Entry("material3-switches", R.string.InuMaterial3Switches, TOGGLE_MATERIAL3_SWITCHES),
                SearchRegistry.Entry("material3-fabs", R.string.InuMaterial3Fabs, TOGGLE_MATERIAL3_FABS),
                SearchRegistry.Entry("material3-sections", R.string.InuMaterial3Sections, TOGGLE_M3_SECTIONS_STYLE),
                SearchRegistry.Entry("monet-theme", R.string.InuMonetTheme, BUTTON_MONET_THEME),
                SearchRegistry.Entry("icon-replacement", R.string.InuIconReplacement, BUTTON_ICON_REPLACEMENT),
                SearchRegistry.Entry("notification-icon", R.string.InuNotificationIcon, BUTTON_NOTIFICATION_ICON),
                SearchRegistry.Entry("font", R.string.InuFonts, BUTTON_FONTS),
                SearchRegistry.Entry("predictive-back-mode", R.string.InuPredictiveBack, BUTTON_PREDICTIVE_BACK_MODE),
                SearchRegistry.Entry("non-island-tab-bars", R.string.InuNonIslandTabBars, TOGGLE_NON_ISLAND_TAB_BARS),
                SearchRegistry.Entry("non-island-global-search", R.string.InuNonIslandGlobalSearch, TOGGLE_NON_ISLAND_GLOBAL_SEARCH),
                SearchRegistry.Entry("non-island-chat-elements", R.string.InuNonIslandChatElements, TOGGLE_NON_ISLAND_CHAT_ELEMENTS),
                SearchRegistry.Entry("hide-fade-view", R.string.InuHideFadeView, TOGGLE_HIDE_FADE_VIEW),
            ),
        )
    }
}
