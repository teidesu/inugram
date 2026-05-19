package desu.inugram.ui.settings

import android.os.Build
import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.UrlCleanerHelper
import desu.inugram.helpers.WebPreviewHelper
import desu.inugram.ui.settings.WebPreviewReplacementsActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
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
    )

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuBehavior)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_CHAT_BUBBLES,
                LocaleController.getString(R.string.InuDisableChatBubbles),
            ).setChecked(InuConfig.DISABLE_CHAT_BUBBLES.value)
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
                TOGGLE_CALL_CONFIRMATION,
                LocaleController.getString(R.string.InuCallConfirmation),
            ).setChecked(InuConfig.CALL_CONFIRMATION.value)
        )
        deleteForBothGroup.addTo(items) { listView.adapter.update(true) }
        items.add(UItem.asShadow(null))

        items.add(stripTrackingParamsItem())
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

            TOGGLE_STRIP_TRACKING_PARAMS -> {
                val new = InuConfig.STRIP_TRACKING_PARAMS.toggle()
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
        }
    }

    private fun stripTrackingParamsItem(): UItem {
        val title = LocaleController.getString(R.string.InuStripTrackingParams)
        val subtitle = LocaleController.formatString(
            R.string.InuStripTrackingParamsSource, UrlCleanerHelper.lastUpdated ?: "?",
        )
        val checked = InuConfig.STRIP_TRACKING_PARAMS.value
        return UItem.asButtonCheck(TOGGLE_STRIP_TRACKING_PARAMS, title, subtitle).also {
            it.checked = checked
            it.bind = Utilities.Callback { view ->
                (view as? NotificationsCheckCell)?.apply {
                    setTextAndValueAndCheck(title, subtitle, checked, 0, true, true)
                    setDrawLine(false)
                }
            }
        }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean {
        if (item.id == TOGGLE_STRIP_TRACKING_PARAMS) {
            showStripTrackingParamsOptions(item, view)
            return true
        }
        return super.onLongClick(item, view, position, x, y)
    }

    private fun showStripTrackingParamsOptions(item: UItem, anchor: View) {
        val opts = ItemOptions.makeOptions(this, anchor)
            .add(R.drawable.msg_download, LocaleController.getString(R.string.InuStripTrackingParamsUpdate)) {
                fetchLatestStripTrackingParams()
            }
        if (UrlCleanerHelper.isUsingOverride) {
            opts.add(R.drawable.msg_delete, LocaleController.getString(R.string.InuStripTrackingParamsRevert)) {
                UrlCleanerHelper.resetToBundled()
                Utilities.globalQueue.postRunnable {
                    UrlCleanerHelper.preload()
                    AndroidUtilities.runOnUIThread { listView?.adapter?.update(true) }
                }
            }
        }
        addCopyLinkOption(opts, item)
        opts.show()
    }

    private fun fetchLatestStripTrackingParams() {
        Utilities.globalQueue.postRunnable {
            val result = runCatching { UrlCleanerHelper.fetchLatest() }
            UrlCleanerHelper.preload()
            AndroidUtilities.runOnUIThread {
                listView?.adapter?.update(true)
                val bulletin = BulletinFactory.of(this)
                result.fold(
                    onSuccess = { updated ->
                        val msg = if (updated) R.string.InuStripTrackingParamsUpdated
                        else R.string.InuStripTrackingParamsAlreadyLatest
                        bulletin.createSimpleBulletin(R.raw.contact_check, LocaleController.getString(msg)).show()
                    },
                    onFailure = { err ->
                        bulletin.createSimpleBulletin(
                            R.raw.error,
                            LocaleController.getString(R.string.InuStripTrackingParamsUpdateFailed),
                            err.message ?: "",
                        ).show()
                    },
                )
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
        private val TOGGLE_STRIP_TRACKING_PARAMS = InuUtils.generateId()
        private val BUTTON_WEB_PREVIEW_REPLACEMENTS = InuUtils.generateId()
        private val TOGGLE_FASTER_DOWNLOADS = InuUtils.generateId()
        private val TOGGLE_FASTER_UPLOADS = InuUtils.generateId()

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
                SearchRegistry.Entry("strip-tracking-params", R.string.InuStripTrackingParams, TOGGLE_STRIP_TRACKING_PARAMS),
                SearchRegistry.Entry("web-preview-replacements", R.string.InuWebPreviewReplacements, BUTTON_WEB_PREVIEW_REPLACEMENTS),
                SearchRegistry.Entry("faster-downloads", R.string.InuFasterDownloads, TOGGLE_FASTER_DOWNLOADS),
                SearchRegistry.Entry("faster-uploads", R.string.InuFasterUploads, TOGGLE_FASTER_UPLOADS),
            ),
        )
    }
}
