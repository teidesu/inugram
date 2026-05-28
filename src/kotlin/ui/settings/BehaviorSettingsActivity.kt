package desu.inugram.ui.settings

import android.os.Build
import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.chat.WebPreviewHelper
import desu.inugram.helpers.maps.MapsHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class BehaviorSettingsActivity : SettingsPageActivity() {

    private val deleteForBothGroup = ExpandableBoolGroup(
        LocaleController.getString(R.string.InuDeleteForBoth),
        listOf(
            ExpandableBoolGroup.Option(R.string.InuDeleteForBothMessages, InuConfig.DELETE_FOR_BOTH_MESSAGES),
            ExpandableBoolGroup.Option(R.string.InuDeleteForBothDms, InuConfig.DELETE_FOR_BOTH_DMS),
            ExpandableBoolGroup.Option(R.string.InuDeleteForBothGroups, InuConfig.DELETE_FOR_BOTH_GROUPS),
        ),
        sectionId = SECTION_DELETE_FOR_BOTH,
    )

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuBehavior)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuFormatting)))
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
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuChatActions)))
        items.add(
            UItem.asCheck(
                TOGGLE_CALL_CONFIRMATION,
                LocaleController.getString(R.string.InuCallConfirmation),
            ).setChecked(InuConfig.CALL_CONFIRMATION.value)
        )
        deleteForBothGroup.addTo(items) { listView.adapter.update(true) }
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_CHAT_BUBBLES,
                LocaleController.getString(R.string.InuDisableChatBubbles),
            ).setChecked(InuConfig.DISABLE_CHAT_BUBBLES.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_GIF_SEEKBAR,
                R.string.InuGifSeekbar,
                R.string.InuGifSeekbarInfo,
                InuConfig.GIF_SEEKBAR.value,
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuLinksAndBrowser)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_CONFIRM_INTERNAL_LINKS,
                R.string.InuConfirmInternalLinks,
                R.string.InuConfirmInternalLinksInfo,
                InuConfig.CONFIRM_INTERNAL_LINKS.value,
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            items.add(
                UItem.asButton(
                    BUTTON_TEXT_CLASSIFIER_MODE,
                    LocaleController.getString(R.string.InuTextClassifierMode),
                    textClassifierModeLabel(InuConfig.TEXT_CLASSIFIER_MODE.value),
                )
            )
        }
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_BROWSER_SWIPE_COLLAPSE,
                LocaleController.getString(R.string.InuDisableBrowserSwipeCollapse),
            ).setChecked(InuConfig.DISABLE_BROWSER_SWIPE_COLLAPSE.value)
        )
        items.add(
            UItem.asButton(
                BUTTON_WEB_PREVIEW_REPLACEMENTS,
                LocaleController.getString(R.string.InuWebPreviewReplacements),
                if (InuConfig.WEB_PREVIEW_REPLACEMENTS_ENABLED.value)
                    WebPreviewHelper.load().size.toString()
                else
                    LocaleController.getString(R.string.SlowmodeOff),
            )
        )
        items.add(UItem.asShadow(null))

        items.add(
            UItem.asHeader(addExperimentalSpan(LocaleController.getString(R.string.InuNetwork)))
        )
        items.add(
            UItem.asCheck(
                TOGGLE_FASTER_DOWNLOADS,
                LocaleController.getString(R.string.InuFasterDownloads),
            ).setChecked(InuConfig.FASTER_DOWNLOADS.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_FASTER_UPLOADS,
                LocaleController.getString(R.string.InuFasterUploads),
            ).setChecked(InuConfig.FASTER_UPLOADS.value)
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuFasterTransfersInfo)))

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
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (deleteForBothGroup.handleClick(item, view) { listView.adapter.update(true) }) return
        when (item.id) {
            TOGGLE_DISABLE_CHAT_BUBBLES -> {
                val new = InuConfig.DISABLE_CHAT_BUBBLES.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            BUTTON_TEXT_CLASSIFIER_MODE -> showTextClassifierModeSelector()
            BUTTON_WEB_PREVIEW_REPLACEMENTS -> presentFragment(WebPreviewReplacementsActivity())

            TOGGLE_CALL_CONFIRMATION -> {
                val new = InuConfig.CALL_CONFIRMATION.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_CONFIRM_INTERNAL_LINKS -> {
                val new = InuConfig.CONFIRM_INTERNAL_LINKS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_BROWSER_SWIPE_COLLAPSE -> {
                val new = InuConfig.DISABLE_BROWSER_SWIPE_COLLAPSE.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_GIF_SEEKBAR -> {
                val new = InuConfig.GIF_SEEKBAR.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_FASTER_DOWNLOADS -> {
                val new = InuConfig.FASTER_DOWNLOADS.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_FASTER_UPLOADS -> {
                val new = InuConfig.FASTER_UPLOADS.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_SHOW_SECONDS -> {
                val new = InuConfig.SHOW_SECONDS.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_ROUNDING -> {
                val new = InuConfig.DISABLE_ROUNDING.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
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
        }
    }

    private fun showTextClassifierModeSelector() {
        val context = context ?: return
        val values = intArrayOf(
            InuConfig.TextClassifierModeItem.NATIVE,
            InuConfig.TextClassifierModeItem.IMPROVED,
            InuConfig.TextClassifierModeItem.OFF,
        )
        val items = listOf(
            RadioDialogBuilder.Item(LocaleController.getString(R.string.InuTextClassifierModeNative)),
            RadioDialogBuilder.Item(
                LocaleController.getString(R.string.InuTextClassifierModeImproved),
                LocaleController.getString(R.string.InuTextClassifierModeImprovedInfo),
            ),
            RadioDialogBuilder.Item(
                LocaleController.getString(R.string.InuTextClassifierModeOff),
                LocaleController.getString(R.string.InuTextClassifierModeOffInfo),
            ),
        )
        showDialog(
            RadioDialogBuilder(context, getResourceProvider())
                .setTitle(LocaleController.getString(R.string.InuTextClassifierMode))
                .setSubtitle(LocaleController.getString(R.string.InuTextClassifierModeInfo))
                .setItems(items, values.indexOf(InuConfig.TEXT_CLASSIFIER_MODE.value).coerceAtLeast(0)) { _, which ->
                    val newValue = values[which]
                    if (InuConfig.TEXT_CLASSIFIER_MODE.value == newValue) return@setItems
                    InuConfig.TEXT_CLASSIFIER_MODE.value = newValue
                    listView.adapter.update(true)
                    showRestartBulletin()
                }.create()
        )
    }

    companion object {
        private val TOGGLE_DISABLE_CHAT_BUBBLES = InuUtils.generateId()
        private val BUTTON_TEXT_CLASSIFIER_MODE = InuUtils.generateId()
        private val TOGGLE_CALL_CONFIRMATION = InuUtils.generateId()
        private val TOGGLE_CONFIRM_INTERNAL_LINKS = InuUtils.generateId()
        private val TOGGLE_DISABLE_BROWSER_SWIPE_COLLAPSE = InuUtils.generateId()
        private val TOGGLE_GIF_SEEKBAR = InuUtils.generateId()
        private val BUTTON_WEB_PREVIEW_REPLACEMENTS = InuUtils.generateId()
        private val TOGGLE_FASTER_DOWNLOADS = InuUtils.generateId()
        private val TOGGLE_FASTER_UPLOADS = InuUtils.generateId()
        private val SECTION_DELETE_FOR_BOTH = InuUtils.generateId()
        private val BUTTON_MAP_PROVIDER = InuUtils.generateId()
        private val BUTTON_MAP_PREVIEW_PROVIDER = InuUtils.generateId()
        private val TOGGLE_SHOW_SECONDS = InuUtils.generateId()
        private val TOGGLE_DISABLE_ROUNDING = InuUtils.generateId()

        private fun textClassifierModeLabel(value: Int): String = when (value) {
            InuConfig.TextClassifierModeItem.NATIVE -> LocaleController.getString(R.string.InuTextClassifierModeNative)
            InuConfig.TextClassifierModeItem.OFF -> LocaleController.getString(R.string.InuTextClassifierModeOff)
            else -> LocaleController.getString(R.string.InuTextClassifierModeImproved)
        }

        @JvmField val PAGE = SearchRegistry.Page(
            slug = "behavior",
            titleRes = R.string.InuBehavior,
            iconRes = R.drawable.avd_speed,
            factory = ::BehaviorSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("disable-chat-bubbles", R.string.InuDisableChatBubbles, TOGGLE_DISABLE_CHAT_BUBBLES),
                SearchRegistry.Entry("text-classifier-mode", R.string.InuTextClassifierMode, BUTTON_TEXT_CLASSIFIER_MODE),
                SearchRegistry.Entry("call-confirmation", R.string.InuCallConfirmation, TOGGLE_CALL_CONFIRMATION),
                SearchRegistry.Entry("confirm-internal-links", R.string.InuConfirmInternalLinks, TOGGLE_CONFIRM_INTERNAL_LINKS),
                SearchRegistry.Entry("disable-browser-swipe-collapse", R.string.InuDisableBrowserSwipeCollapse, TOGGLE_DISABLE_BROWSER_SWIPE_COLLAPSE),
                SearchRegistry.Entry("gif-seekbar", R.string.InuGifSeekbar, TOGGLE_GIF_SEEKBAR),
                SearchRegistry.Entry("web-preview-replacements", R.string.InuWebPreviewReplacements, BUTTON_WEB_PREVIEW_REPLACEMENTS),
                SearchRegistry.Entry("faster-downloads", R.string.InuFasterDownloads, TOGGLE_FASTER_DOWNLOADS),
                SearchRegistry.Entry("faster-uploads", R.string.InuFasterUploads, TOGGLE_FASTER_UPLOADS),
                SearchRegistry.Entry("delete-for-both", R.string.InuDeleteForBoth, SECTION_DELETE_FOR_BOTH),
                SearchRegistry.Entry("map-provider", R.string.InuMapProvider, BUTTON_MAP_PROVIDER),
                SearchRegistry.Entry("map-preview-provider", R.string.InuMapPreviewProvider, BUTTON_MAP_PREVIEW_PROVIDER),
                SearchRegistry.Entry("show-seconds", R.string.InuShowSeconds, TOGGLE_SHOW_SECONDS),
                SearchRegistry.Entry("disable-rounding", R.string.InuDisableRounding, TOGGLE_DISABLE_ROUNDING),
            ),
        )
    }
}
