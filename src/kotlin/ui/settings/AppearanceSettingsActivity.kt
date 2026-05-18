package desu.inugram.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import desu.inugram.InuConfig
import desu.inugram.InuHooks
import desu.inugram.helpers.FontHelper
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.MapsHelper
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

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuGeneral)


    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuMiscellaneous)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_OLD_LAYOUT,
                R.string.InuOldLayout,
                R.string.InuOldLayoutInfo,
                InuConfig.OLD_LAYOUT.value,
                experimental = true,
            )
        )
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
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_SCRIM_BLUR,
                R.string.InuDisableScrimBlur,
                R.string.InuDisableScrimBlurInfo,
                InuConfig.DISABLE_SCRIM_BLUR.value
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
                BUTTON_FONT_MODE,
                LocaleController.getString(R.string.InuFont),
                when (InuConfig.FONT_MODE.value) {
                    1 -> LocaleController.getString(R.string.InuFontSystem)
                    2 -> FontHelper.familyName ?: LocaleController.getString(R.string.InuFontCustom)
                    else -> LocaleController.getString(R.string.InuFontDefault)
                }
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuMapsHeader)))
        items.add(
            UItem.asButton(
                BUTTON_MAP_PROVIDER,
                LocaleController.getString(R.string.InuMapProvider),
                when (InuConfig.MAP_PROVIDER.value) {
                    InuConfig.MapProviderItem.OSM -> LocaleController.getString(R.string.InuMapProviderOsm)
                    else -> LocaleController.getString(R.string.InuMapProviderGoogle)
                }
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_MAP_PREVIEW_PROVIDER,
                LocaleController.getString(R.string.InuMapPreviewProvider),
                when (InuConfig.MAP_PREVIEW_PROVIDER.value) {
                    InuConfig.MapPreviewProviderItem.TELEGRAM -> LocaleController.getString(R.string.InuMapPreviewProviderTelegram)
                    InuConfig.MapPreviewProviderItem.GOOGLE -> LocaleController.getString(R.string.InuMapPreviewProviderGoogle)
                    InuConfig.MapPreviewProviderItem.YANDEX -> LocaleController.getString(R.string.InuMapPreviewProviderYandex)
                    InuConfig.MapPreviewProviderItem.DISABLED -> LocaleController.getString(R.string.Disable)
                    else -> LocaleController.getString(R.string.Default)
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
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuNonIslandHint)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_OLD_LAYOUT -> {
                val new = InuConfig.OLD_LAYOUT.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                showRestartBulletin()
            }
            TOGGLE_HIDE_FADE_VIEW -> {
                val new = InuConfig.HIDE_FADE_VIEW.toggle()
                (view as? TextCheckCell)?.isChecked = new
                softRebuild()
            }

            BUTTON_MAP_PROVIDER -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuMapProviderGoogle),
                    LocaleController.getString(R.string.InuMapProviderOsm),
                ),
                InuConfig.MAP_PROVIDER.value,
            ) { which ->
                InuConfig.MAP_PROVIDER.value = which
                showRestartBulletin()
            }

            BUTTON_MAP_PREVIEW_PROVIDER -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.Default),
                    LocaleController.getString(R.string.InuMapPreviewProviderTelegram),
                    LocaleController.getString(R.string.InuMapPreviewProviderGoogle),
                    LocaleController.getString(R.string.InuMapPreviewProviderYandex),
                    LocaleController.getString(R.string.Disable),
                ),
                InuConfig.MAP_PREVIEW_PROVIDER.value,
            ) { which ->
                InuConfig.MAP_PREVIEW_PROVIDER.value = which
                MapsHelper.syncMapProvider(messagesController)
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

            TOGGLE_SHOW_SECONDS -> {
                val new = InuConfig.SHOW_SECONDS.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_ROUNDING -> {
                val new = InuConfig.DISABLE_ROUNDING.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
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
        private val TOGGLE_OLD_LAYOUT = InuUtils.generateId()
        private val TOGGLE_HIDE_FADE_VIEW = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_TAB_BARS = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_GLOBAL_SEARCH = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_CHAT_ELEMENTS = InuUtils.generateId()
        private val TOGGLE_SHOW_SECONDS = InuUtils.generateId()
        private val TOGGLE_DISABLE_ROUNDING = InuUtils.generateId()
        private val BUTTON_FONT_MODE = InuUtils.generateId()
        private const val REQ_PICK_FONT = 31010
        private val TOGGLE_DISABLE_SCRIM_BLUR = InuUtils.generateId()
        private val BUTTON_ICON_REPLACEMENT = InuUtils.generateId()
        private val BUTTON_MAP_PROVIDER = InuUtils.generateId()
        private val BUTTON_MAP_PREVIEW_PROVIDER = InuUtils.generateId()
    }
}
