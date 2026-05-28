package desu.inugram.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import desu.inugram.InuConfig
import desu.inugram.InuHooks
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.font.FontHelper
import desu.inugram.helpers.theme.MonetHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AppearanceSettingsActivity : SettingsPageActivity() {

    private var animationSpeedSlider: SliderCell? = null

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuLookAndFeel)


    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuTypographyAndIcons)))
        items.add(
            UItem.asButton(
                BUTTON_FONT_MODE,
                LocaleController.getString(R.string.InuFont),
                when (InuConfig.FONT_MODE.value) {
                    1 -> LocaleController.getString(R.string.InuFontSystem)
                    2 -> FontHelper.familyName ?: LocaleController.getString(R.string.InuFontCustom)
                    else -> LocaleController.getString(R.string.InuFontDefault)
                }
            )
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

            BUTTON_FONT_MODE -> {
                val labels = mutableListOf(
                    LocaleController.getString(R.string.InuFontDefault),
                    LocaleController.getString(R.string.InuFontSystem),
                )
                val hasPack = FontHelper.hasPack
                if (hasPack) {
                    labels.add(FontHelper.familyName ?: LocaleController.getString(R.string.InuFontCustom))
                }
                val pickerIndex = labels.size
                labels.add(LocaleController.getString(R.string.InuFontCustom))
                val selected = when {
                    InuConfig.FONT_MODE.value == 2 && hasPack -> 2
                    InuConfig.FONT_MODE.value == 2 -> pickerIndex
                    else -> InuConfig.FONT_MODE.value
                }
                RadioItemOptions.show(this, view, labels, selected) { which ->
                    when (which) {
                        pickerIndex -> launchFontPicker()
                        else -> {
                            // 0/1 = default/system; 2 (only when hasPack) = custom
                            InuConfig.FONT_MODE.value = which
                            showRestartBulletin()
                        }
                    }
                }
            }

            TOGGLE_DISABLE_SCRIM_BLUR -> {
                val new = InuConfig.DISABLE_SCRIM_BLUR.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
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
                showRestartBulletin()
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

    private fun launchFontPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "font/ttf", "font/otf", "font/collection", "font/sfnt",
                        "application/font-sfnt", "application/x-font-ttf",
                        "application/x-font-opentype", "application/octet-stream",
                    )
                )
            }
            startActivityForResult(intent, REQ_PICK_FONT)
        } catch (e: Exception) {
            FileLog.e(e)
            BulletinFactory.of(this).createErrorBulletin(e.message ?: "").show()
        }
    }

    override fun onActivityResultFragment(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_PICK_FONT) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            // RadioItemOptions optimistically updated the TextCell value to "Custom…"; resync.
            listView.adapter.update(false)
            return
        }
        val uris = mutableListOf<Uri>()
        data.clipData?.let { cd ->
            for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
        } ?: data.data?.let { uris.add(it) }
        if (uris.isEmpty()) {
            listView.adapter.update(false)
            return
        }
        val ctx = parentActivity ?: context ?: return
        Utilities.globalQueue.postRunnable {
            val n = FontHelper.importFromUris(ctx, uris)
            AndroidUtilities.runOnUIThread {
                if (n > 0) {
                    InuConfig.FONT_MODE.value = 2
                    softRebuild()
                    showRestartBulletin()
                } else {
                    BulletinFactory.of(this).createErrorBulletin(
                        LocaleController.getString(R.string.InuFontImportFailed)
                    ).show()
                }
                listView.adapter.update(false)
            }
        }
    }

    companion object {
        private val TOGGLE_HIDE_FADE_VIEW = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_TAB_BARS = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_GLOBAL_SEARCH = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_CHAT_ELEMENTS = InuUtils.generateId()
        private val BUTTON_FONT_MODE = InuUtils.generateId()
        private const val REQ_PICK_FONT = 31010
        private val TOGGLE_DISABLE_SCRIM_BLUR = InuUtils.generateId()
        private val TOGGLE_REDUCE_MENU_MOTION = InuUtils.generateId()
        private val TOGGLE_MATERIAL3_SWITCHES = InuUtils.generateId()
        private val TOGGLE_MATERIAL3_FABS = InuUtils.generateId()
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
                SearchRegistry.Entry("reduce-menu-motion", R.string.InuReduceMenuMotion, TOGGLE_REDUCE_MENU_MOTION),
                SearchRegistry.Entry("material3-switches", R.string.InuMaterial3Switches, TOGGLE_MATERIAL3_SWITCHES),
                SearchRegistry.Entry("material3-fabs", R.string.InuMaterial3Fabs, TOGGLE_MATERIAL3_FABS),
                SearchRegistry.Entry("monet-theme", R.string.InuMonetTheme, BUTTON_MONET_THEME),
                SearchRegistry.Entry("icon-replacement", R.string.InuIconReplacement, BUTTON_ICON_REPLACEMENT),
                SearchRegistry.Entry("notification-icon", R.string.InuNotificationIcon, BUTTON_NOTIFICATION_ICON),
                SearchRegistry.Entry("font", R.string.InuFont, BUTTON_FONT_MODE),
                SearchRegistry.Entry("predictive-back-mode", R.string.InuPredictiveBack, BUTTON_PREDICTIVE_BACK_MODE),
                SearchRegistry.Entry("non-island-tab-bars", R.string.InuNonIslandTabBars, TOGGLE_NON_ISLAND_TAB_BARS),
                SearchRegistry.Entry("non-island-global-search", R.string.InuNonIslandGlobalSearch, TOGGLE_NON_ISLAND_GLOBAL_SEARCH),
                SearchRegistry.Entry("non-island-chat-elements", R.string.InuNonIslandChatElements, TOGGLE_NON_ISLAND_CHAT_ELEMENTS),
                SearchRegistry.Entry("hide-fade-view", R.string.InuHideFadeView, TOGGLE_HIDE_FADE_VIEW),
            ),
        )
    }
}
