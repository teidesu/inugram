package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.ui.settings.FormattingPopupActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

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
    )

    private val hideBottomBarGroup = ExpandableBoolGroup(
        LocaleController.getString(R.string.InuHideBottomBar),
        listOf(
            ExpandableBoolGroup.Option(R.string.InuHideBottomBarJoined, InuConfig.HIDE_BOTTOM_BAR_JOINED),
            ExpandableBoolGroup.Option(R.string.InuHideBottomBarNonJoined, InuConfig.HIDE_BOTTOM_BAR_NON_JOINED),
            ExpandableBoolGroup.Option(R.string.InuHideBottomBarReplies, InuConfig.HIDE_BOTTOM_BAR_REPLIES),
            ExpandableBoolGroup.Option(R.string.InuHideBottomBarPinned, InuConfig.HIDE_BOTTOM_BAR_PINNED),
        ),
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
            mkTwoLineCheckItem(
                TOGGLE_CHAT_VOICE_IN_ATTACH,
                R.string.InuChatVoiceInAttach,
                R.string.InuChatVoiceInAttachInfo,
                InuConfig.CHAT_VOICE_IN_ATTACH.value,
                experimental = true
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_SIMPLE_ATTACH_POPUP_ANIMATION,
                LocaleController.getString(R.string.InuSimpleAttachPopupAnimation),
            ).setChecked(InuConfig.SIMPLE_ATTACH_POPUP_ANIMATION.value)
        )
        items.add(
            UItem.asButton(
                BUTTON_ROUND_DEFAULT_CAMERA,
                LocaleController.getString(R.string.InuRoundDefaultCamera),
                roundCameraLabel(InuConfig.ROUND_DEFAULT_CAMERA.value),
            )
        )
        items.add(UItem.asShadow(null))

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
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuMiscellaneous)))
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
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_DRAFT_UPLOAD,
                R.string.InuDisableDraftUpload,
                R.string.InuDisableDraftUploadInfo,
                InuConfig.DISABLE_DRAFT_UPLOAD.value
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_CALL_ACTION_BUTTON,
                LocaleController.getString(R.string.InuHideCallActionButton),
            ).setChecked(InuConfig.HIDE_CALL_ACTION_BUTTON.value)
        )
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (hideBotSlashGroup.handleClick(item, view) { listView.adapter.update(true) }) return
        if (hideBottomBarGroup.handleClick(item, view) { listView.adapter.update(true) }) return

        when (item.id) {
            TOGGLE_HIDE_KEYBOARD_ON_SCROLL -> {
                val new = InuConfig.HIDE_KEYBOARD_ON_SCROLL.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_PULL_TO_NEXT -> {
                val new = InuConfig.DISABLE_PULL_TO_NEXT.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_CHAT_ALWAYS_SHOW_DOWN -> {
                val new = InuConfig.CHAT_ALWAYS_SHOW_DOWN.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_CHAT_TWO_FINGER_SELECT -> {
                val new = InuConfig.CHAT_TWO_FINGER_SELECT.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_SHOW_ALL_RECENT_STICKERS -> {
                val new = InuConfig.SHOW_ALL_RECENT_STICKERS.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_INSTANT_CAMERA -> {
                val new = InuConfig.DISABLE_INSTANT_CAMERA.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_CHAT_VOICE_IN_ATTACH -> {
                val new = InuConfig.CHAT_VOICE_IN_ATTACH.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_SIMPLE_ATTACH_POPUP_ANIMATION -> {
                val new = InuConfig.SIMPLE_ATTACH_POPUP_ANIMATION.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            BUTTON_ROUND_DEFAULT_CAMERA -> showRoundCameraSelector(view)

            TOGGLE_BOT_WEBVIEW_BUTTON -> {
                val new = InuConfig.HIDE_BOT_WEBVIEW_INPUT.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_SEND_AS_PICKER -> {
                val new = InuConfig.HIDE_SEND_AS_PICKER.toggle()
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

            TOGGLE_SEARCH_FROM_GLOBAL -> {
                val new = InuConfig.SEARCH_FROM_GLOBAL.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_CALL_ACTION_BUTTON -> {
                val new = InuConfig.HIDE_CALL_ACTION_BUTTON.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }
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
        private val BUTTON_FORMATTING_POPUP = InuUtils.generateId()
        private val TOGGLE_BOT_WEBVIEW_BUTTON = InuUtils.generateId()
        private val TOGGLE_HIDE_SEND_AS_PICKER = InuUtils.generateId()
        private val TOGGLE_SUGGEST_CUSTOM_EMOJI_AFTER = InuUtils.generateId()
        private val TOGGLE_DISABLE_DRAFT_UPLOAD = InuUtils.generateId()
        private val TOGGLE_SEARCH_FROM_GLOBAL = InuUtils.generateId()
        private val TOGGLE_HIDE_CALL_ACTION_BUTTON = InuUtils.generateId()

        private fun roundCameraLabel(value: Int): String = when (value) {
            2 -> LocaleController.getString(R.string.InuRoundCameraRear)
            3 -> LocaleController.getString(R.string.InuRoundCameraAsk)
            else -> LocaleController.getString(R.string.InuRoundCameraFront)
        }
    }
}
