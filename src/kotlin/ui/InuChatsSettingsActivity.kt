package desu.inugram.ui

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class InuChatsSettingsActivity : InuSettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.Chats)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    var stickerSizePreview: StickerSizePreviewMessagesCell? = null
    var stickerSizeSlider: SliderCell? = null
    var chatInputMaxLinesSlider: SliderCell? = null
    var reactionsInRowSlider: SliderCell? = null

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
        // stickers section
        if (stickerSizePreview == null) stickerSizePreview = StickerSizePreviewMessagesCell(this.context, this)
        if (stickerSizeSlider == null) stickerSizeSlider = SliderCell(
            context,
            min = 4f,
            max = 20f,
            defaultValue = InuConfig.STICKER_SIZE.default,
            initialValue = InuConfig.STICKER_SIZE.value,
            format = { "%.1f".format(it) },
            onChanged = {
                InuConfig.STICKER_SIZE.value = it
                stickerSizePreview?.invalidate()
            },
        )
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuStickerSize)))
        items.add(UItem.asCustom(stickerSizeSlider))
        items.add(UItem.asCustom(stickerSizePreview))
        items.add(
            UItem.asButton(
                BUTTON_STICKER_TIME_MODE,
                LocaleController.getString(R.string.InuStickerTimeMode),
                when (InuConfig.STICKER_TIME_MODE.value) {
                    InuConfig.StickerTimeModeItem.HIDE_TIME -> LocaleController.getString(R.string.InuStickerTimeModeHideTime)
                    InuConfig.StickerTimeModeItem.HIDE_FULL -> LocaleController.getString(R.string.InuStickerTimeModeHideCompletely)
                    InuConfig.StickerTimeModeItem.HIDE_INCOMING -> LocaleController.getString(R.string.InuStickerTimeModeHideIncoming)
                    else -> LocaleController.getString(R.string.InuStickerTimeModeShow)
                }
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_SHOW_ALL_RECENT_STICKERS,
                LocaleController.getString(R.string.InuShowAllRecentStickers),
            ).setChecked(InuConfig.SHOW_ALL_RECENT_STICKERS.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_NO_STICKER_EXTRA_PADDING,
                LocaleController.getString(R.string.InuNoStickerExtraPadding),
            ).setChecked(InuConfig.NO_STICKER_EXTRA_PADDING.value)
        )
        items.add(UItem.asShadow(null))
        // end stickers section

        // attachment sheet section
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
        items.add(UItem.asShadow(null))
        // end attachment sheet section

        // message input section
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
        items.add(UItem.asShadow(null))
        // end message input section

        // reactions section
        if (reactionsInRowSlider == null) reactionsInRowSlider = SliderCell(
            context,
            min = 6f,
            max = 15f,
            defaultValue = InuConfig.REACTIONS_IN_ROW.default.toFloat(),
            initialValue = InuConfig.REACTIONS_IN_ROW.value.toFloat(),
            step = 1f,
            format = { it.toInt().toString() },
            onChanged = {
                InuConfig.REACTIONS_IN_ROW.value = it.toInt()
            },
        )
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuReactionsInRow)))
        items.add(UItem.asCustom(reactionsInRowSlider))
        items.add(
            UItem.asButton(
                BUTTON_PINNED_REACTIONS,
                LocaleController.getString(R.string.InuPinnedReactions),
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_REACTION_BAR_BELOW,
                R.string.InuReactionBarBelow,
                R.string.InuReactionBarBelowInfo,
                InuConfig.REACTION_BAR_BELOW.value,
                experimental = true
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_CHAT_VIEWS_BOTTOM,
                R.string.InuChatViewsBottom,
                R.string.InuChatViewsBottomInfo,
                InuConfig.CHAT_VIEWS_BOTTOM.value,
                experimental = true
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_HIDE_REACTION_ENTRY,
                R.string.InuHideReactionEntry,
                R.string.InuHideReactionEntryInfo,
                InuConfig.HIDE_REACTIONS_ENTRY.value
            )
        )
        items.add(UItem.asShadow(null))
        // end reactions section

        // misc section
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuMiscellaneous)))
        hideBottomBarGroup.addTo(items) { listView.adapter.update(true) }
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_CHAT_TITLE_PHONE,
                LocaleController.getString(R.string.InuDisableChatTitlePhone)
            ).setChecked(InuConfig.DISABLE_CHAT_TITLE_PHONE.value)
        )
        // end misc section
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (hideBotSlashGroup.handleClick(item, view) { listView.adapter.update(true) }) return
        if (hideBottomBarGroup.handleClick(item, view) { listView.adapter.update(true) }) return

        when (item.id) {
            BUTTON_STICKER_TIME_MODE -> showDialog(
                RadioDialogBuilder(context, getResourceProvider())
                    .setTitle(LocaleController.getString(R.string.InuStickerTimeMode))
                    .setItems(
                        arrayOf(
                            LocaleController.getString(R.string.InuStickerTimeModeShow),
                            LocaleController.getString(R.string.InuStickerTimeModeHideTime),
                            LocaleController.getString(R.string.InuStickerTimeModeHideIncoming),
                            LocaleController.getString(R.string.InuStickerTimeModeHideCompletely),
                        ),
                        InuConfig.STICKER_TIME_MODE.value - 1,
                    ) { _, which ->
                        val mode = which + 1
                        if (mode == InuConfig.STICKER_TIME_MODE.value) return@setItems
                        InuConfig.STICKER_TIME_MODE.value = mode
                        listView.adapter.update(true)
                        stickerSizePreview?.invalidate()
                    }
                    .create()
            )

            TOGGLE_SHOW_ALL_RECENT_STICKERS -> {
                val new = InuConfig.SHOW_ALL_RECENT_STICKERS.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_NO_STICKER_EXTRA_PADDING -> {
                val new = InuConfig.NO_STICKER_EXTRA_PADDING.toggle()
                (view as? TextCheckCell)?.isChecked = new
                stickerSizePreview?.invalidate()
            }

            TOGGLE_DISABLE_INSTANT_CAMERA -> {
                val new = InuConfig.DISABLE_INSTANT_CAMERA.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_CHAT_VOICE_IN_ATTACH -> {
                val new = InuConfig.CHAT_VOICE_IN_ATTACH.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_BOT_WEBVIEW_BUTTON -> {
                val new = InuConfig.HIDE_BOT_WEBVIEW_INPUT.toggle()
                (view as? TextCheckCell)?.isChecked = new
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
                    presentFragment(InuFormattingPopupActivity())
                }
            }

            TOGGLE_REACTION_BAR_BELOW -> {
                val new = InuConfig.REACTION_BAR_BELOW.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            BUTTON_PINNED_REACTIONS -> presentFragment(InuPinnedReactionsActivity())

            TOGGLE_SIMPLE_ATTACH_POPUP_ANIMATION -> {
                val new = InuConfig.SIMPLE_ATTACH_POPUP_ANIMATION.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_CHAT_TITLE_PHONE -> {
                val new = InuConfig.DISABLE_CHAT_TITLE_PHONE.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_REACTION_ENTRY -> {
                val new = InuConfig.HIDE_REACTIONS_ENTRY.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_CHAT_VIEWS_BOTTOM -> {
                val new = InuConfig.CHAT_VIEWS_BOTTOM.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
        }
    }

    companion object {
        private val BUTTON_STICKER_TIME_MODE = InuUtils.generateId()
        private val TOGGLE_SHOW_ALL_RECENT_STICKERS = InuUtils.generateId()
        private val TOGGLE_NO_STICKER_EXTRA_PADDING = InuUtils.generateId()
        private val TOGGLE_DISABLE_INSTANT_CAMERA = InuUtils.generateId()
        private val TOGGLE_CHAT_VOICE_IN_ATTACH = InuUtils.generateId()
        private val BUTTON_FORMATTING_POPUP = InuUtils.generateId()
        private val TOGGLE_BOT_WEBVIEW_BUTTON = InuUtils.generateId()
        private val TOGGLE_REACTION_BAR_BELOW = InuUtils.generateId()
        private val TOGGLE_SIMPLE_ATTACH_POPUP_ANIMATION = InuUtils.generateId()
        private val TOGGLE_DISABLE_CHAT_TITLE_PHONE = InuUtils.generateId()
        private val TOGGLE_HIDE_REACTION_ENTRY = InuUtils.generateId()
        private val TOGGLE_CHAT_VIEWS_BOTTOM = InuUtils.generateId()
        private val BUTTON_PINNED_REACTIONS = InuUtils.generateId()

    }
}
