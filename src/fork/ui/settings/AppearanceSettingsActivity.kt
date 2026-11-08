package desu.inugram.ui.settings

import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import desu.inugram.InuConfig
import desu.inugram.InuHooks
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.dialogs.DrawerHelper
import desu.inugram.helpers.icons.IconPackHelper
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

    private val material3Group = ExpandableBoolGroup(
        LocaleController.getString(R.string.InuMaterial3),
        listOf(
            ExpandableBoolGroup.Option(R.string.InuMaterial3Switches, InuConfig.MATERIAL3_SWITCHES, TOGGLE_MATERIAL3_SWITCHES),
            ExpandableBoolGroup.Option(R.string.InuMaterial3Sliders, InuConfig.MATERIAL3_SLIDERS, TOGGLE_MATERIAL3_SLIDERS),
            ExpandableBoolGroup.Option(R.string.InuMaterial3Fabs, InuConfig.MATERIAL3_FABS, TOGGLE_MATERIAL3_FABS),
            ExpandableBoolGroup.Option(R.string.InuMaterial3Sections, InuConfig.M3_SECTIONS_STYLE, TOGGLE_M3_SECTIONS_STYLE),
            ExpandableBoolGroup.Option(R.string.InuMaterial3Avatars, InuConfig.MATERIAL3_AVATARS, TOGGLE_MATERIAL3_AVATARS),
            ExpandableBoolGroup.Option(R.string.InuMaterial3BottomTabs, InuConfig.M3_BOTTOM_TABS, TOGGLE_M3_BOTTOM_TABS),
            ExpandableBoolGroup.Option(R.string.InuMaterialProfileActions, InuConfig.MATERIAL_PROFILE_ACTIONS, TOGGLE_MATERIAL_PROFILE_ACTIONS),
            ExpandableBoolGroup.Option(R.string.InuMaterial3NavigationAnimation, InuConfig.M3_NAVIGATION_ANIMATION, TOGGLE_M3_NAVIGATION_ANIMATION),
        ),
    )

    private val classicUiGroup = ExpandableBoolGroup(
        LocaleController.getString(R.string.InuNonIslandUI),
        listOf(
            ExpandableBoolGroup.Option(R.string.InuNonIslandFoldersBar, InuConfig.NON_ISLAND_FOLDERS_BAR, TOGGLE_NON_ISLAND_FOLDERS_BAR),
            ExpandableBoolGroup.Option(R.string.InuNonIslandSharedMediaTabs, InuConfig.NON_ISLAND_SHARED_MEDIA_TABS, TOGGLE_NON_ISLAND_SHARED_MEDIA_TABS),
            ExpandableBoolGroup.Option(R.string.InuNonIslandGlobalSearch, InuConfig.NON_ISLAND_GLOBAL_SEARCH, TOGGLE_NON_ISLAND_GLOBAL_SEARCH),
            ExpandableBoolGroup.Option(R.string.InuNonIslandChatElements, InuConfig.NON_ISLAND_CHAT_ELEMENTS, TOGGLE_NON_ISLAND_CHAT_ELEMENTS),
            ExpandableBoolGroup.Option(R.string.InuNonIslandHeaders, InuConfig.NON_ISLAND_HEADERS, TOGGLE_NON_ISLAND_HEADERS),
            ExpandableBoolGroup.Option(R.string.InuHideFadeView, InuConfig.HIDE_FADE_VIEW, TOGGLE_HIDE_FADE_VIEW),
            ExpandableBoolGroup.Option(R.string.InuDisableGlassGlare, InuConfig.DISABLE_GLASS_GLARE, TOGGLE_DISABLE_GLASS_GLARE),
        ),
    )

    override val expandableGroups = listOf(material3Group, classicUiGroup)

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuLookAndFeel)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }


    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuTypographyAndIcons)))
        items.add(mkSubPageButton(BUTTON_FONTS, LocaleController.getString(R.string.InuFonts)))
        items.add(
            UItem.asButton(
                BUTTON_ICON_REPLACEMENT,
                LocaleController.getString(R.string.InuIconReplacement),
                IconPackHelper.getActivePacksSummary(),
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

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuNavigation)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_NAVIGATION_DRAWER,
                R.string.InuNavigationDrawer,
                R.string.InuNavigationDrawerInfo,
                InuConfig.NAVIGATION_DRAWER.value,
            )
        )
        if (InuConfig.NAVIGATION_DRAWER.value) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                items.add(
                    mkTwoLineCheckItem(
                        TOGGLE_DRAWER_BACK_GESTURE,
                        R.string.InuDrawerBackGesture,
                        R.string.InuDrawerBackGestureInfo,
                        InuConfig.DRAWER_BACK_GESTURE.value,
                    )
                )
            }
            items.add(mkSubPageButton(BUTTON_DRAWER_MENU_ORDER, LocaleController.getString(R.string.InuDrawerMenuOrder)))
            items.add(UItem.asShadow(null))
        }
        items.add(
            UItem.asCheck(
                TOGGLE_BOTTOM_TABS,
                LocaleController.getString(R.string.InuBottomTabs),
            ).setChecked(!InuConfig.BOTTOM_TABS_HIDE.value)
        )
        if (!InuConfig.BOTTOM_TABS_HIDE.value) {
            items.add(
                UItem.asCheck(
                    TOGGLE_HIDE_CONTACTS_TAB,
                    LocaleController.getString(R.string.InuHideContactsTab),
                ).setChecked(InuConfig.BOTTOM_TABS_HIDE_CONTACTS.value)
            )
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_COMPACT_MODE,
                    R.string.InuCompactMode,
                    R.string.InuCompactModeInfo,
                    InuConfig.BOTTOM_TABS_COMPACT_MODE.value,
                )
            )
            items.add(
                UItem.asCheck(
                    TOGGLE_BOTTOM_TABS_HIDE_ON_SCROLL,
                    LocaleController.getString(R.string.InuBottomTabsHideOnScroll),
                ).setChecked(InuConfig.BOTTOM_TABS_HIDE_ON_SCROLL.value)
            )
        }
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuInterfaceStyle)))
        material3Group.addTo(items, ::applyStyleChanges)
        classicUiGroup.addTo(items, ::applyStyleChanges)
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_SCRIM_BLUR,
                R.string.InuDisableScrimBlur,
                R.string.InuDisableScrimBlurInfo,
                InuConfig.DISABLE_SCRIM_BLUR.value
            )
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

    private fun applyStyleChanges(changed: List<ExpandableBoolGroup.Option>) {
        val configs = changed.map { it.config }
        if (InuConfig.MATERIAL3_SWITCHES in configs || InuConfig.MATERIAL3_SLIDERS in configs) invalidateVisibleRows()
        if (InuConfig.NON_ISLAND_HEADERS in configs) rebuildAll()
        else if (configs.any { it in SOFT_REBUILD_CONFIGS }) softRebuild()
        // if (InuConfig.M3_SECTIONS_STYLE in configs) inu_rebuildSelf()
        if (InuConfig.NON_ISLAND_CHAT_ELEMENTS in configs) InuHooks.syncChatInputRowHeight()
        listView.adapter.update(true)
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (material3Group.handleClick(item, view) { applyStyleChanges(listOfNotNull(it)) }) return
        if (classicUiGroup.handleClick(item, view) { applyStyleChanges(listOfNotNull(it)) }) return
        when (item.id) {
            BUTTON_ICON_REPLACEMENT -> presentFragment(IconPacksActivity())

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

            BUTTON_DRAWER_MENU_ORDER -> presentFragment(DrawerMenuOrderActivity())

            TOGGLE_DISABLE_SCRIM_BLUR -> {
                val new = InuConfig.DISABLE_SCRIM_BLUR.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_REDUCE_MENU_MOTION -> {
                val new = InuConfig.REDUCE_MENU_MOTION.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_NAVIGATION_DRAWER -> {
                val new = InuConfig.NAVIGATION_DRAWER.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                listView.adapter.update(true)
                if (new) DrawerHelper.ensureSetup(parentLayout) else DrawerHelper.teardown(parentLayout)
                softRebuild()
                if (new && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && InuConfig.DRAWER_BACK_GESTURE.value) {
                    showRestartBulletin()
                }
            }

            TOGGLE_DRAWER_BACK_GESTURE -> {
                val new = InuConfig.DRAWER_BACK_GESTURE.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                showRestartBulletin()
            }

            TOGGLE_BOTTOM_TABS -> {
                val new = !InuConfig.BOTTOM_TABS_HIDE.toggle()
                (view as? TextCheckCell)?.isChecked = new
                listView.adapter.update(true)
                softRebuild()
            }

            TOGGLE_HIDE_CONTACTS_TAB -> {
                val new = InuConfig.BOTTOM_TABS_HIDE_CONTACTS.toggle()
                (view as? TextCheckCell)?.isChecked = new
                showRestartBulletin()
            }

            TOGGLE_BOTTOM_TABS_HIDE_ON_SCROLL -> {
                val new = InuConfig.BOTTOM_TABS_HIDE_ON_SCROLL.toggle()
                (view as? TextCheckCell)?.isChecked = new
                softRebuild()
            }

            TOGGLE_COMPACT_MODE -> {
                val new = InuConfig.BOTTOM_TABS_COMPACT_MODE.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                softRebuild()
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
        private val TOGGLE_NAVIGATION_DRAWER = InuUtils.generateId()
        private val TOGGLE_DRAWER_BACK_GESTURE = InuUtils.generateId()
        private val BUTTON_DRAWER_MENU_ORDER = InuUtils.generateId()
        private val TOGGLE_BOTTOM_TABS = InuUtils.generateId()
        private val TOGGLE_HIDE_CONTACTS_TAB = InuUtils.generateId()
        private val TOGGLE_COMPACT_MODE = InuUtils.generateId()
        private val TOGGLE_BOTTOM_TABS_HIDE_ON_SCROLL = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_FOLDERS_BAR = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_SHARED_MEDIA_TABS = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_GLOBAL_SEARCH = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_CHAT_ELEMENTS = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_HEADERS = InuUtils.generateId()
        private val BUTTON_FONTS = InuUtils.generateId()
        private val TOGGLE_DISABLE_SCRIM_BLUR = InuUtils.generateId()
        private val TOGGLE_DISABLE_GLASS_GLARE = InuUtils.generateId()
        private val TOGGLE_REDUCE_MENU_MOTION = InuUtils.generateId()
        private val TOGGLE_MATERIAL3_SWITCHES = InuUtils.generateId()
        private val TOGGLE_MATERIAL3_SLIDERS = InuUtils.generateId()
        private val TOGGLE_MATERIAL3_FABS = InuUtils.generateId()
        private val TOGGLE_M3_SECTIONS_STYLE = InuUtils.generateId()
        private val TOGGLE_MATERIAL3_AVATARS = InuUtils.generateId()
        private val TOGGLE_M3_BOTTOM_TABS = InuUtils.generateId()
        private val TOGGLE_MATERIAL_PROFILE_ACTIONS = InuUtils.generateId()
        private val TOGGLE_M3_NAVIGATION_ANIMATION = InuUtils.generateId()
        private val BUTTON_ICON_REPLACEMENT = InuUtils.generateId()
        private val BUTTON_NOTIFICATION_ICON = InuUtils.generateId()
        private val BUTTON_PREDICTIVE_BACK_MODE = InuUtils.generateId()
        private val BUTTON_MONET_THEME = InuUtils.generateId()

        private val SOFT_REBUILD_CONFIGS = setOf(
            InuConfig.MATERIAL3_SWITCHES,
            InuConfig.MATERIAL3_FABS,
            InuConfig.M3_SECTIONS_STYLE,
            InuConfig.M3_BOTTOM_TABS,
            InuConfig.MATERIAL_PROFILE_ACTIONS,
            InuConfig.NON_ISLAND_FOLDERS_BAR,
            InuConfig.NON_ISLAND_GLOBAL_SEARCH,
            InuConfig.HIDE_FADE_VIEW,
        )

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
                SearchRegistry.Entry("material3-sliders", R.string.InuMaterial3Sliders, TOGGLE_MATERIAL3_SLIDERS),
                SearchRegistry.Entry("material3-fabs", R.string.InuMaterial3Fabs, TOGGLE_MATERIAL3_FABS),
                SearchRegistry.Entry("material3-sections", R.string.InuMaterial3Sections, TOGGLE_M3_SECTIONS_STYLE),
                SearchRegistry.Entry("material3-avatars", R.string.InuMaterial3Avatars, TOGGLE_MATERIAL3_AVATARS),
                SearchRegistry.Entry("m3-bottom-tabs", R.string.InuMaterial3BottomTabs, TOGGLE_M3_BOTTOM_TABS),
                SearchRegistry.Entry("material-profile-actions", R.string.InuMaterialProfileActions, TOGGLE_MATERIAL_PROFILE_ACTIONS),
                SearchRegistry.Entry("material3-navigation-animation", R.string.InuMaterial3NavigationAnimation, TOGGLE_M3_NAVIGATION_ANIMATION),
                SearchRegistry.Entry("monet-theme", R.string.InuMonetTheme, BUTTON_MONET_THEME),
                SearchRegistry.Entry("icon-replacement", R.string.InuIconReplacement, BUTTON_ICON_REPLACEMENT),
                SearchRegistry.Entry("notification-icon", R.string.InuNotificationIcon, BUTTON_NOTIFICATION_ICON),
                SearchRegistry.Entry("font", R.string.InuFonts, BUTTON_FONTS),
                SearchRegistry.Entry("predictive-back-mode", R.string.InuPredictiveBack, BUTTON_PREDICTIVE_BACK_MODE),
                SearchRegistry.Entry("navigation-drawer", R.string.InuNavigationDrawer, TOGGLE_NAVIGATION_DRAWER),
                SearchRegistry.Entry("drawer-back-gesture", R.string.InuDrawerBackGesture, TOGGLE_DRAWER_BACK_GESTURE),
                SearchRegistry.Entry("drawer-menu-order", R.string.InuDrawerMenuOrder, BUTTON_DRAWER_MENU_ORDER),
                SearchRegistry.Entry("bottom-tabs-hide", R.string.InuBottomTabs, TOGGLE_BOTTOM_TABS),
                SearchRegistry.Entry("hide-contacts-tab", R.string.InuHideContactsTab, TOGGLE_HIDE_CONTACTS_TAB),
                SearchRegistry.Entry("bottom-tabs-compact-mode", R.string.InuCompactMode, TOGGLE_COMPACT_MODE),
                SearchRegistry.Entry("bottom-tabs-hide-on-scroll", R.string.InuBottomTabsHideOnScroll, TOGGLE_BOTTOM_TABS_HIDE_ON_SCROLL),
                SearchRegistry.Entry("non-island-folders-bar", R.string.InuNonIslandFoldersBar, TOGGLE_NON_ISLAND_FOLDERS_BAR),
                SearchRegistry.Entry("non-island-shared-media-tabs", R.string.InuNonIslandSharedMediaTabs, TOGGLE_NON_ISLAND_SHARED_MEDIA_TABS),
                SearchRegistry.Entry("non-island-global-search", R.string.InuNonIslandGlobalSearch, TOGGLE_NON_ISLAND_GLOBAL_SEARCH),
                SearchRegistry.Entry("non-island-chat-elements", R.string.InuNonIslandChatElements, TOGGLE_NON_ISLAND_CHAT_ELEMENTS),
                SearchRegistry.Entry("hide-fade-view", R.string.InuHideFadeView, TOGGLE_HIDE_FADE_VIEW),
            ),
        )
    }
}
