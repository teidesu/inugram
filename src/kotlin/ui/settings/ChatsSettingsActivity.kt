package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Stories.recorder.DualCameraView

class ChatsSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.Chats)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    private var chatInputMaxLinesSlider: SliderCell? = null

    private val hideBotSlashGroup = ExpandableBoolGroup(
        LocaleController.getString(R.string.InuHideBotSlash),
        listOf(
            ExpandableBoolGroup.Option(R.string.InuHideBotSlashGroups, InuConfig.HIDE_BOT_SLASH_GROUPS),
            ExpandableBoolGroup.Option(R.string.InuHideBotSlashBots, InuConfig.HIDE_BOT_SLASH_BOTS),
        ),
        sectionId = SECTION_HIDE_BOT_SLASH,
    )

    private val hideBottomBarGroup = ExpandableBoolGroup(
        LocaleController.getString(R.string.InuHideBottomBar),
        listOf(
            ExpandableBoolGroup.Option(R.string.InuHideBottomBarJoined, InuConfig.HIDE_BOTTOM_BAR_JOINED),
            ExpandableBoolGroup.Option(R.string.InuHideBottomBarNonJoined, InuConfig.HIDE_BOTTOM_BAR_NON_JOINED),
            ExpandableBoolGroup.Option(R.string.InuHideBottomBarNonJoinedGroups, InuConfig.HIDE_BOTTOM_BAR_NON_JOINED_GROUPS),
            ExpandableBoolGroup.Option(R.string.InuHideBottomBarReplies, InuConfig.HIDE_BOTTOM_BAR_REPLIES),
            ExpandableBoolGroup.Option(R.string.InuHideBottomBarPinned, InuConfig.HIDE_BOTTOM_BAR_PINNED),
        ),
        sectionId = SECTION_HIDE_BOTTOM_BAR,
    )

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuGeneral)))
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
            mkTwoLineCheckItem(
                TOGGLE_CHAT_ALWAYS_SHOW_DOWN,
                R.string.InuChatAlwaysShowDown,
                R.string.InuChatAlwaysShowDownInfo,
                InuConfig.CHAT_ALWAYS_SHOW_DOWN.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_CHAT_TWO_FINGER_SELECT,
                R.string.InuChatTwoFingerSelect,
                R.string.InuChatTwoFingerSelectInfo,
                InuConfig.CHAT_TWO_FINGER_SELECT.value,
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAttachmentSheet)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_INSTANT_CAMERA,
                R.string.InuDisableInstantCamera,
                R.string.InuDisableInstantCameraInfo,
                InuConfig.DISABLE_INSTANT_CAMERA.value
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_SIMPLE_ATTACH_POPUP_ANIMATION,
                LocaleController.getString(R.string.InuSimpleAttachPopupAnimation),
            ).setChecked(InuConfig.SIMPLE_ATTACH_POPUP_ANIMATION.value)
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuVoiceRecorder)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_CHAT_VOICE_IN_ATTACH,
                R.string.InuChatVoiceInAttach,
                R.string.InuChatVoiceInAttachInfo,
                InuConfig.CHAT_VOICE_IN_ATTACH.value,
                experimental = true
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_ROUND_DEFAULT_CAMERA,
                LocaleController.getString(R.string.InuRoundDefaultCamera),
                roundCameraLabel(InuConfig.ROUND_DEFAULT_CAMERA.value),
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_ROUND_RECORDER_ZOOM_SLIDER,
                LocaleController.getString(R.string.InuRoundRecorderZoomSlider)
            ).setChecked(InuConfig.ROUND_RECORDER_ZOOM_SLIDER.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_ROUND_RECORDER_KEEP_ZOOM,
                LocaleController.getString(R.string.InuRoundRecorderKeepZoom)
            ).setChecked(InuConfig.ROUND_RECORDER_KEEP_ZOOM.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_ROUND_RECORDER_EXPONENTIAL_ZOOM,
                R.string.InuRoundRecorderExponentialZoom,
                R.string.InuRoundRecorderExponentialZoomInfo,
                InuConfig.ROUND_RECORDER_EXPONENTIAL_ZOOM.value,
            )
        )
        val usingCamera2 = SharedConfig.isUsingCamera2(currentAccount)
        if (DualCameraView.roundDualAvailableStatic(context) && usingCamera2) {
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_ROUND_RECORDER_DUAL_CAMERA,
                    R.string.InuRoundRecorderDualCamera,
                    R.string.InuRoundRecorderDualCameraInfo,
                    InuConfig.ROUND_RECORDER_DUAL_CAMERA.value,
                )
            )
        }
        val cameraApi = if (usingCamera2) "Camera2" else "Camera1"
        val otherCameraApi = if (usingCamera2) "Camera1" else "Camera2"
        val cameraApiText = LocaleController.formatString(R.string.InuRoundRecorderCameraApi, cameraApi, otherCameraApi)
        items.add(
            UItem.asShadow(
                AndroidUtilities.replaceArrows(
                    AndroidUtilities.replaceSingleTag(cameraApiText) {
                        SharedConfig.toggleUseCamera2(currentAccount)
                        listView.adapter.update(true)
                    },
                    true,
                )
            )
        )

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuMessageInput)))
        items.add(
            mkSplitCheckItem(
                BUTTON_FORMATTING_POPUP,
                R.string.InuFormattingPopup,
                R.string.InuFormattingPopupInfo,
                InuConfig.FORMATTING_POPUP.value,
                experimental = true
            )
        )
        if (chatInputMaxLinesSlider == null) chatInputMaxLinesSlider = SliderCell(
            context,
            min = 5f,
            max = 15f,
            defaultValue = InuConfig.CHAT_INPUT_MAX_LINES.default.toFloat(),
            initialValue = InuConfig.CHAT_INPUT_MAX_LINES.value.toFloat(),
            step = 1f,
            format = { it.toInt().toString() },
            onChanged = {
                InuConfig.CHAT_INPUT_MAX_LINES.value = it.toInt()
            },
        )
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuChatInputMaxLines)))
        items.add(UItem.asCustom(chatInputMaxLinesSlider))
        hideBotSlashGroup.addTo(items) { listView.adapter.update(true) }
        items.add(
            UItem.asCheck(TOGGLE_BOT_WEBVIEW_BUTTON, LocaleController.getString(R.string.InuHideBotWebView))
                .setChecked(InuConfig.HIDE_BOT_WEBVIEW_INPUT.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_HIDE_SEND_AS_PICKER,
                R.string.InuHideSendAsPicker,
                R.string.InuHideSendAsPickerInfo,
                InuConfig.HIDE_SEND_AS_PICKER.value
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.StickersName)))
        items.add(
            UItem.asCheck(
                TOGGLE_SHOW_ALL_RECENT_STICKERS,
                LocaleController.getString(R.string.InuShowAllRecentStickers),
            ).setChecked(InuConfig.SHOW_ALL_RECENT_STICKERS.value)
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
                TOGGLE_EMOJI_PANEL_KEYWORD_SEARCH,
                R.string.InuEmojiPanelKeywordSearch,
                R.string.InuEmojiPanelKeywordSearchInfo,
                InuConfig.EMOJI_PANEL_KEYWORD_SEARCH.value,
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuMiscellaneous)))
        items.add(
            mkSubPageButton(
                BUTTON_CHAT_MENU_ORDER,
                LocaleController.getString(R.string.InuChatMenuOrder),
            )
        )
        hideBottomBarGroup.addTo(items) { listView.adapter.update(true) }
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SEARCH_FROM_GLOBAL,
                R.string.InuSearchFromGlobal,
                R.string.InuSearchFromGlobalInfo,
                InuConfig.SEARCH_FROM_GLOBAL.value,
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_CALL_ACTION_BUTTON,
                LocaleController.getString(R.string.InuHideCallActionButton),
            ).setChecked(InuConfig.HIDE_CALL_ACTION_BUTTON.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SEND_TO_DISCUSS_WITHOUT_JOIN,
                R.string.InuSendToDiscussWithoutJoin,
                R.string.InuSendToDiscussWithoutJoinInfo,
                InuConfig.SEND_TO_DISCUSS_WITHOUT_JOIN.value,
            )
        )
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (hideBotSlashGroup.handleClick(item, view) { listView.adapter.update(true) }) return
        if (hideBottomBarGroup.handleClick(item, view) { listView.adapter.update(true) }) return

        when (item.id) {
            TOGGLE_HIDE_KEYBOARD_ON_SCROLL -> (view as? TextCheckCell)?.isChecked = InuConfig.HIDE_KEYBOARD_ON_SCROLL.toggle()
            TOGGLE_DISABLE_PULL_TO_NEXT -> (view as? TextCheckCell)?.isChecked = InuConfig.DISABLE_PULL_TO_NEXT.toggle()
            TOGGLE_CHAT_ALWAYS_SHOW_DOWN -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.CHAT_ALWAYS_SHOW_DOWN.toggle()
            TOGGLE_CHAT_TWO_FINGER_SELECT -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.CHAT_TWO_FINGER_SELECT.toggle()
            TOGGLE_SHOW_ALL_RECENT_STICKERS -> (view as? TextCheckCell)?.isChecked = InuConfig.SHOW_ALL_RECENT_STICKERS.toggle()
            TOGGLE_DISABLE_INSTANT_CAMERA -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.DISABLE_INSTANT_CAMERA.toggle()
            TOGGLE_CHAT_VOICE_IN_ATTACH -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.CHAT_VOICE_IN_ATTACH.toggle()
            TOGGLE_SIMPLE_ATTACH_POPUP_ANIMATION -> (view as? TextCheckCell)?.isChecked = InuConfig.SIMPLE_ATTACH_POPUP_ANIMATION.toggle()
            BUTTON_ROUND_DEFAULT_CAMERA -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuRoundCameraFront),
                    LocaleController.getString(R.string.InuRoundCameraRear),
                    LocaleController.getString(R.string.InuRoundCameraAsk),
                ),
                (InuConfig.ROUND_DEFAULT_CAMERA.value - 1).coerceIn(0, 2),
            ) { which ->
                InuConfig.ROUND_DEFAULT_CAMERA.value = which + 1
            }

            TOGGLE_ROUND_RECORDER_ZOOM_SLIDER -> (view as? TextCheckCell)?.isChecked = InuConfig.ROUND_RECORDER_ZOOM_SLIDER.toggle()
            TOGGLE_ROUND_RECORDER_KEEP_ZOOM -> (view as? TextCheckCell)?.isChecked = InuConfig.ROUND_RECORDER_KEEP_ZOOM.toggle()
            TOGGLE_ROUND_RECORDER_EXPONENTIAL_ZOOM ->
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.ROUND_RECORDER_EXPONENTIAL_ZOOM.toggle()

            TOGGLE_ROUND_RECORDER_DUAL_CAMERA -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.ROUND_RECORDER_DUAL_CAMERA.toggle()
            TOGGLE_BOT_WEBVIEW_BUTTON -> (view as? TextCheckCell)?.isChecked = InuConfig.HIDE_BOT_WEBVIEW_INPUT.toggle()
            TOGGLE_HIDE_SEND_AS_PICKER -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.HIDE_SEND_AS_PICKER.toggle()
            TOGGLE_SEND_TO_DISCUSS_WITHOUT_JOIN ->
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.SEND_TO_DISCUSS_WITHOUT_JOIN.toggle()
            TOGGLE_SUGGEST_CUSTOM_EMOJI_AFTER -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.SUGGEST_CUSTOM_EMOJI_AFTER.toggle()
            TOGGLE_EMOJI_PANEL_KEYWORD_SEARCH -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.EMOJI_PANEL_KEYWORD_SEARCH.toggle()

            BUTTON_FORMATTING_POPUP -> {
                val isSwitch = if (LocaleController.isRTL)
                    x < AndroidUtilities.dp(76f)
                else
                    x > view.measuredWidth - AndroidUtilities.dp(76f)
                if (isSwitch) {
                    val new = InuConfig.FORMATTING_POPUP.toggle()
                    (view as? NotificationsCheckCell)?.isChecked = new
                } else {
                    presentFragment(FormattingPopupActivity())
                }
            }

            TOGGLE_SEARCH_FROM_GLOBAL -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.SEARCH_FROM_GLOBAL.toggle()
            TOGGLE_HIDE_CALL_ACTION_BUTTON -> (view as? TextCheckCell)?.isChecked = InuConfig.HIDE_CALL_ACTION_BUTTON.toggle()
            BUTTON_CHAT_MENU_ORDER -> presentFragment(ChatMenuOrderActivity())
        }
    }

    companion object {
        private val TOGGLE_HIDE_KEYBOARD_ON_SCROLL = InuUtils.generateId()
        private val TOGGLE_DISABLE_PULL_TO_NEXT = InuUtils.generateId()
        private val TOGGLE_CHAT_ALWAYS_SHOW_DOWN = InuUtils.generateId()
        private val TOGGLE_CHAT_TWO_FINGER_SELECT = InuUtils.generateId()
        private val TOGGLE_SHOW_ALL_RECENT_STICKERS = InuUtils.generateId()
        private val TOGGLE_DISABLE_INSTANT_CAMERA = InuUtils.generateId()
        private val TOGGLE_CHAT_VOICE_IN_ATTACH = InuUtils.generateId()
        private val TOGGLE_SIMPLE_ATTACH_POPUP_ANIMATION = InuUtils.generateId()
        private val BUTTON_ROUND_DEFAULT_CAMERA = InuUtils.generateId()
        private val TOGGLE_ROUND_RECORDER_ZOOM_SLIDER = InuUtils.generateId()
        private val TOGGLE_ROUND_RECORDER_KEEP_ZOOM = InuUtils.generateId()
        private val TOGGLE_ROUND_RECORDER_EXPONENTIAL_ZOOM = InuUtils.generateId()
        private val TOGGLE_ROUND_RECORDER_DUAL_CAMERA = InuUtils.generateId()
        private val BUTTON_FORMATTING_POPUP = InuUtils.generateId()
        private val TOGGLE_BOT_WEBVIEW_BUTTON = InuUtils.generateId()
        private val TOGGLE_HIDE_SEND_AS_PICKER = InuUtils.generateId()
        private val TOGGLE_SEND_TO_DISCUSS_WITHOUT_JOIN = InuUtils.generateId()
        private val TOGGLE_SUGGEST_CUSTOM_EMOJI_AFTER = InuUtils.generateId()
        private val TOGGLE_EMOJI_PANEL_KEYWORD_SEARCH = InuUtils.generateId()
        private val TOGGLE_SEARCH_FROM_GLOBAL = InuUtils.generateId()
        private val TOGGLE_HIDE_CALL_ACTION_BUTTON = InuUtils.generateId()
        private val BUTTON_CHAT_MENU_ORDER = InuUtils.generateId()
        private val SECTION_HIDE_BOTTOM_BAR = InuUtils.generateId()
        private val SECTION_HIDE_BOT_SLASH = InuUtils.generateId()

        private fun roundCameraLabel(value: Int): String = when (value) {
            2 -> LocaleController.getString(R.string.InuRoundCameraRear)
            3 -> LocaleController.getString(R.string.InuRoundCameraAsk)
            else -> LocaleController.getString(R.string.InuRoundCameraFront)
        }

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "chats",
            titleRes = R.string.Chats,
            iconRes = R.drawable.msg_discussion,
            factory = ::ChatsSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("hide-keyboard-on-scroll", R.string.InuHideKeyboardOnScroll, TOGGLE_HIDE_KEYBOARD_ON_SCROLL),
                SearchRegistry.Entry("disable-pull-to-next", R.string.InuDisablePullToNext, TOGGLE_DISABLE_PULL_TO_NEXT),
                SearchRegistry.Entry("chat-always-show-down", R.string.InuChatAlwaysShowDown, TOGGLE_CHAT_ALWAYS_SHOW_DOWN),
                SearchRegistry.Entry("chat-two-finger-select", R.string.InuChatTwoFingerSelect, TOGGLE_CHAT_TWO_FINGER_SELECT),
                SearchRegistry.Entry("disable-instant-camera", R.string.InuDisableInstantCamera, TOGGLE_DISABLE_INSTANT_CAMERA),
                SearchRegistry.Entry("chat-voice-in-attach", R.string.InuChatVoiceInAttach, TOGGLE_CHAT_VOICE_IN_ATTACH),
                SearchRegistry.Entry("simple-attach-popup-animation", R.string.InuSimpleAttachPopupAnimation, TOGGLE_SIMPLE_ATTACH_POPUP_ANIMATION),
                SearchRegistry.Entry("round-default-camera", R.string.InuRoundDefaultCamera, BUTTON_ROUND_DEFAULT_CAMERA),
                SearchRegistry.Entry("round-recorder-zoom-slider", R.string.InuRoundRecorderZoomSlider, TOGGLE_ROUND_RECORDER_ZOOM_SLIDER),
                SearchRegistry.Entry("round-recorder-keep-zoom", R.string.InuRoundRecorderKeepZoom, TOGGLE_ROUND_RECORDER_KEEP_ZOOM),
                SearchRegistry.Entry(
                    "round-recorder-exponential-zoom",
                    R.string.InuRoundRecorderExponentialZoom,
                    TOGGLE_ROUND_RECORDER_EXPONENTIAL_ZOOM
                ),
                SearchRegistry.Entry("round-recorder-dual-camera", R.string.InuRoundRecorderDualCamera, TOGGLE_ROUND_RECORDER_DUAL_CAMERA),
                SearchRegistry.Entry("formatting-popup", R.string.InuFormattingPopup, BUTTON_FORMATTING_POPUP),
                SearchRegistry.Entry("hide-bot-webview", R.string.InuHideBotWebView, TOGGLE_BOT_WEBVIEW_BUTTON),
                SearchRegistry.Entry("hide-send-as-picker", R.string.InuHideSendAsPicker, TOGGLE_HIDE_SEND_AS_PICKER),
                SearchRegistry.Entry(
                    "send-to-discuss-without-join",
                    R.string.InuSendToDiscussWithoutJoin,
                    TOGGLE_SEND_TO_DISCUSS_WITHOUT_JOIN,
                ),
                SearchRegistry.Entry("show-all-recent-stickers", R.string.InuShowAllRecentStickers, TOGGLE_SHOW_ALL_RECENT_STICKERS),
                SearchRegistry.Entry("suggest-custom-emoji-after", R.string.InuSuggestCustomEmojiAfter, TOGGLE_SUGGEST_CUSTOM_EMOJI_AFTER),
                SearchRegistry.Entry("emoji-panel-keyword-search", R.string.InuEmojiPanelKeywordSearch, TOGGLE_EMOJI_PANEL_KEYWORD_SEARCH),
                SearchRegistry.Entry("search-from-global", R.string.InuSearchFromGlobal, TOGGLE_SEARCH_FROM_GLOBAL),
                SearchRegistry.Entry("hide-call-action-button", R.string.InuHideCallActionButton, TOGGLE_HIDE_CALL_ACTION_BUTTON),
                SearchRegistry.Entry("chat-menu-order", R.string.InuChatMenuOrder, BUTTON_CHAT_MENU_ORDER),
                SearchRegistry.Entry("hide-bottom-bar", R.string.InuHideBottomBar, SECTION_HIDE_BOTTOM_BAR),
                SearchRegistry.Entry("hide-bot-slash", R.string.InuHideBotSlash, SECTION_HIDE_BOT_SLASH),
            ),
        )
    }
}
