package desu.inugram.ui

import android.os.Build
import android.view.View
import android.widget.EditText
import desu.inugram.InuConfig
import desu.inugram.InuHooks
import desu.inugram.helpers.DoubleTapAction
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.UnifiedPushHelper
import desu.inugram.helpers.WebPreviewHelper
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.unifiedpush.android.connector.UnifiedPush

class InuBehaviorSettingsActivity : InuSettingsPageActivity() {

    private var doubleTapDelaySlider: SliderCell? = null

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
        items.add(UItem.asHeader(LocaleController.getString(R.string.Chats)))
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_KEYBOARD_ON_SCROLL,
                LocaleController.getString(R.string.InuHideKeyboardOnScroll),
            ).setChecked(InuConfig.HIDE_KEYBOARD_ON_SCROLL.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_PULL_TO_NEXT,
                LocaleController.getString(R.string.InuDisablePullToNext),
            ).setChecked(InuConfig.DISABLE_PULL_TO_NEXT.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_SWIPE_TO_UNARCHIVE,
                LocaleController.getString(R.string.InuDisableSwipeToUnarchive),
            ).setChecked(InuConfig.DISABLE_SWIPE_TO_UNARCHIVE.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_CHAT_BUBBLES,
                LocaleController.getString(R.string.InuDisableChatBubbles),
            ).setChecked(InuConfig.DISABLE_CHAT_BUBBLES.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_CHAT_ALWAYS_SHOW_DOWN,
                R.string.InuChatAlwaysShowDown,
                R.string.InuChatAlwaysShowDownInfo,
                InuConfig.CHAT_ALWAYS_SHOW_DOWN.value,
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_CHAT_REMEMBER_ALL_REPLIES,
                LocaleController.getString(R.string.InuChatRememberAllReplies),
            ).setChecked(InuConfig.CHAT_REMEMBER_ALL_REPLIES.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_INTRO_STICKER,
                R.string.InuDisableIntroSticker,
                R.string.InuDisableIntroStickerInfo,
                InuConfig.DISABLE_INTRO_STICKER.value
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SUGGEST_CUSTOM_EMOJI_AFTER,
                R.string.InuSuggestCustomEmojiAfter,
                R.string.InuSuggestCustomEmojiAfterInfo,
                InuConfig.SUGGEST_CUSTOM_EMOJI_AFTER.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_DRAFT_UPLOAD,
                R.string.InuDisableDraftUpload,
                R.string.InuDisableDraftUploadInfo,
                InuConfig.DISABLE_DRAFT_UPLOAD.value
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_PREDICTIVE_BACK,
                R.string.InuDisablePredictiveBack,
                R.string.InuDisablePredictiveBackInfo,
                InuConfig.DISABLE_PREDICTIVE_BACK.value
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_ROUND_DEFAULT_CAMERA,
                LocaleController.getString(R.string.InuRoundDefaultCamera),
                roundCameraLabel(InuConfig.ROUND_DEFAULT_CAMERA.value),
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
        deleteForBothGroup.addTo(items) { listView.adapter.update(true) }
        items.add(UItem.asShadow(null))

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

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuUnifiedPush)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_UNIFIED_PUSH,
                R.string.InuUnifiedPush,
                R.string.InuUnifiedPushInfo,
                InuConfig.UNIFIED_PUSH_ENABLED.value,
            )
        )
        if (InuConfig.UNIFIED_PUSH_ENABLED.value) {
            val ctx = context ?: ApplicationLoader.applicationContext
            val distributors = UnifiedPushHelper.getDistributors(ctx)
            val current = UnifiedPushHelper.getActiveDistributor(ctx)
            items.add(
                UItem.asButton(
                    BUTTON_UNIFIED_PUSH_DISTRIBUTOR,
                    LocaleController.getString(R.string.InuUnifiedPushDistributor),
                    current?.let { getAppLabel(it) }
                        ?: if (distributors.isEmpty()) LocaleController.getString(R.string.InuUnifiedPushNone)
                        else distributors.firstOrNull()?.let { getAppLabel(it) } ?: "",
                )
            )
            items.add(
                UItem.asButton(
                    BUTTON_UNIFIED_PUSH_GATEWAY,
                    LocaleController.getString(R.string.InuUnifiedPushGateway),
                    InuConfig.UNIFIED_PUSH_GATEWAY.value.ifBlank { "default" },
                )
            )
        }
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuDoubleTapActions)))
        items.add(
            UItem.asButton(
                BUTTON_DOUBLE_TAP_INCOMING,
                LocaleController.getString(R.string.InuIncomingMessages),
                DoubleTapAction.fromValue(InuConfig.DOUBLE_TAP_ACTION_INCOMING.value, false).label()
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_DOUBLE_TAP_OUTGOING,
                LocaleController.getString(R.string.InuOutgoingMessages),
                DoubleTapAction.fromValue(InuConfig.DOUBLE_TAP_ACTION_OUTGOING.value, true).label()
            )
        )
        if (doubleTapDelaySlider == null) doubleTapDelaySlider = SliderCell(
            this.context, min = 75f, max = 300f,
            defaultValue = InuConfig.DOUBLE_TAP_DELAY.default.toFloat(),
            initialValue = InuConfig.DOUBLE_TAP_DELAY.value.toFloat(),
            format = { "${it.toInt()} ms" },
            onChanged = {
                InuConfig.DOUBLE_TAP_DELAY.value = it.toInt()
                InuHooks.syncDoubleTapDelay()
            },
        )
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuDoubleTapDelay)))
        items.add(UItem.asCustom(doubleTapDelaySlider))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuDoubleTapInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (deleteForBothGroup.handleClick(item, view) { listView.adapter.update(true) }) return
        when (item.id) {
            TOGGLE_HIDE_KEYBOARD_ON_SCROLL -> {
                val new = InuConfig.HIDE_KEYBOARD_ON_SCROLL.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_PULL_TO_NEXT -> {
                val new = InuConfig.DISABLE_PULL_TO_NEXT.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_SWIPE_TO_UNARCHIVE -> {
                val new = InuConfig.DISABLE_SWIPE_TO_UNARCHIVE.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_CHAT_ALWAYS_SHOW_DOWN -> {
                val new = InuConfig.CHAT_ALWAYS_SHOW_DOWN.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_CHAT_REMEMBER_ALL_REPLIES -> {
                val new = InuConfig.CHAT_REMEMBER_ALL_REPLIES.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_CHAT_BUBBLES -> {
                val new = InuConfig.DISABLE_CHAT_BUBBLES.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_INTRO_STICKER -> {
                val new = InuConfig.DISABLE_INTRO_STICKER.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_SUGGEST_CUSTOM_EMOJI_AFTER -> {
                val new = InuConfig.SUGGEST_CUSTOM_EMOJI_AFTER.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_DRAFT_UPLOAD -> {
                val new = InuConfig.DISABLE_DRAFT_UPLOAD.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_PREDICTIVE_BACK -> {
                val new = InuConfig.DISABLE_PREDICTIVE_BACK.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                showRestartBulletin()
            }

            BUTTON_ROUND_DEFAULT_CAMERA -> showRoundCameraSelector(view)
            BUTTON_TEXT_CLASSIFIER_MODE -> showTextClassifierModeSelector()
            BUTTON_WEB_PREVIEW_REPLACEMENTS -> presentFragment(InuWebPreviewReplacementsActivity())
            BUTTON_DOUBLE_TAP_INCOMING -> showDoubleTapSelector(view, false)
            BUTTON_DOUBLE_TAP_OUTGOING -> showDoubleTapSelector(view, true)

            TOGGLE_FASTER_DOWNLOADS -> {
                val new = InuConfig.FASTER_DOWNLOADS.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_FASTER_UPLOADS -> {
                val new = InuConfig.FASTER_UPLOADS.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_UNIFIED_PUSH -> {
                val new = InuConfig.UNIFIED_PUSH_ENABLED.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                listView.adapter.update(true)
                showRestartBulletin()
            }

            BUTTON_UNIFIED_PUSH_DISTRIBUTOR -> showDistributorSelector()
            BUTTON_UNIFIED_PUSH_GATEWAY -> showGatewayEditor()
        }
    }

    private fun showRoundCameraSelector(anchor: View) {
        RadioItemOptions.show(
            this, anchor,
            listOf(
                LocaleController.getString(R.string.InuRoundCameraFront),
                LocaleController.getString(R.string.InuRoundCameraRear),
                LocaleController.getString(R.string.InuRoundCameraAsk),
            ),
            (InuConfig.ROUND_DEFAULT_CAMERA.value - 1).coerceIn(0, 2),
        ) { which ->
            InuConfig.ROUND_DEFAULT_CAMERA.value = which + 1
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

    private fun showDistributorSelector() {
        val ctx = context ?: return
        val distributors = UnifiedPushHelper.getDistributors(ctx)
        if (distributors.isEmpty()) return
        val current = UnifiedPushHelper.getActiveDistributor(ctx)
        val labels = distributors.map { RadioDialogBuilder.Item(getAppLabel(it)) }
        val selected = distributors.indexOf(current).coerceAtLeast(0)
        showDialog(
            RadioDialogBuilder(ctx, getResourceProvider())
                .setTitle(LocaleController.getString(R.string.InuUnifiedPushDistributor))
                .setItems(labels, selected) { _, which ->
                    val chosen = distributors.getOrNull(which) ?: return@setItems
                    UnifiedPush.saveDistributor(ctx, chosen)
                    UnifiedPush.register(ctx, "default", "Telegram Simple Push")
                    listView.adapter.update(true)
                }.create()
        )
    }

    private fun showGatewayEditor() {
        val ctx = context ?: return
        val input = EditText(ctx)
        input.setText(InuConfig.UNIFIED_PUSH_GATEWAY.value)
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        input.hint = "https://p2p.belloworld.it/"
        showDialog(
            AlertDialog.Builder(ctx)
                .setTitle(LocaleController.getString(R.string.InuUnifiedPushGateway))
                .setMessage(LocaleController.getString(R.string.InuUnifiedPushGatewayInfo))
                .setView(input)
                .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ ->
                    var value = input.text.toString().trim()
                    if (value.isNotEmpty() && !value.endsWith("/")) value += "/"
                    InuConfig.UNIFIED_PUSH_GATEWAY.value = value
                    listView.adapter.update(true)
                }
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create()
        )
    }

    private fun getAppLabel(packageName: String): String {
        return try {
            val pm = (context ?: ApplicationLoader.applicationContext).packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Throwable) {
            packageName.substringAfterLast('.')
        }
    }

    private fun showDoubleTapSelector(anchor: View, outgoing: Boolean) {
        val actions = DoubleTapAction.available(outgoing)
        val config = if (outgoing) InuConfig.DOUBLE_TAP_ACTION_OUTGOING else InuConfig.DOUBLE_TAP_ACTION_INCOMING
        RadioItemOptions.show(
            this, anchor,
            actions.map { it.label() },
            actions.indexOfFirst { it.value == config.value }.coerceAtLeast(0),
        ) { which ->
            val action = actions.getOrNull(which) ?: return@show
            config.value = action.value
        }
    }

    companion object {
        private val BUTTON_DOUBLE_TAP_INCOMING = InuUtils.generateId()
        private val BUTTON_DOUBLE_TAP_OUTGOING = InuUtils.generateId()
        private val TOGGLE_HIDE_KEYBOARD_ON_SCROLL = InuUtils.generateId()
        private val TOGGLE_DISABLE_PULL_TO_NEXT = InuUtils.generateId()
        private val TOGGLE_DISABLE_SWIPE_TO_UNARCHIVE = InuUtils.generateId()
        private val TOGGLE_CHAT_ALWAYS_SHOW_DOWN = InuUtils.generateId()
        private val TOGGLE_CHAT_REMEMBER_ALL_REPLIES = InuUtils.generateId()
        private val TOGGLE_DISABLE_CHAT_BUBBLES = InuUtils.generateId()
        private val BUTTON_WEB_PREVIEW_REPLACEMENTS = InuUtils.generateId()
        private val TOGGLE_DISABLE_INTRO_STICKER = InuUtils.generateId()
        private val TOGGLE_SUGGEST_CUSTOM_EMOJI_AFTER = InuUtils.generateId()
        private val TOGGLE_DISABLE_DRAFT_UPLOAD = InuUtils.generateId()
        private val TOGGLE_DISABLE_PREDICTIVE_BACK = InuUtils.generateId()
        private val BUTTON_ROUND_DEFAULT_CAMERA = InuUtils.generateId()
        private val BUTTON_TEXT_CLASSIFIER_MODE = InuUtils.generateId()
        private val TOGGLE_FASTER_DOWNLOADS = InuUtils.generateId()
        private val TOGGLE_FASTER_UPLOADS = InuUtils.generateId()
        private val TOGGLE_UNIFIED_PUSH = InuUtils.generateId()
        private val BUTTON_UNIFIED_PUSH_DISTRIBUTOR = InuUtils.generateId()
        private val BUTTON_UNIFIED_PUSH_GATEWAY = InuUtils.generateId()

        private fun roundCameraLabel(value: Int): String = when (value) {
            2 -> LocaleController.getString(R.string.InuRoundCameraRear)
            3 -> LocaleController.getString(R.string.InuRoundCameraAsk)
            else -> LocaleController.getString(R.string.InuRoundCameraFront)
        }

        private fun textClassifierModeLabel(value: Int): String = when (value) {
            InuConfig.TextClassifierModeItem.NATIVE -> LocaleController.getString(R.string.InuTextClassifierModeNative)
            InuConfig.TextClassifierModeItem.OFF -> LocaleController.getString(R.string.InuTextClassifierModeOff)
            else -> LocaleController.getString(R.string.InuTextClassifierModeImproved)
        }
    }
}
