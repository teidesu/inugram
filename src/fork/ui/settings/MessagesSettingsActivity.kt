package desu.inugram.ui.settings

import android.os.Bundle
import android.view.View
import desu.inugram.InuConfig
import desu.inugram.InuHooks
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.chat.BlockedMessagesHelper
import desu.inugram.helpers.chat.DoubleTapAction
import desu.inugram.helpers.chat.DoubleTapActionHelper
import desu.inugram.helpers.chat.DoubleTapContext
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.GroupCreateActivity

class MessagesSettingsActivity : SettingsPageActivity() {

    private var stickerSizePreview: StickerSizePreviewMessagesCell? = null
    private var miscPreview: MiscPreviewMessagesCell? = null
    private var stickerSizeSlider: SliderCell? = null
    private var reactionsInRowSlider: SliderCell? = null
    private var doubleTapDelaySlider: SliderCell? = null

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuMessages)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        if (stickerSizePreview == null) stickerSizePreview = StickerSizePreviewMessagesCell(this.context, this)
        if (stickerSizeSlider == null) stickerSizeSlider = SliderCell(
            context,
            min = 4f,
            max = 20f,
            defaultValue = InuConfig.STICKER_SIZE.default,
            initialValue = InuConfig.STICKER_SIZE.value,
            title = LocaleController.getString(R.string.InuStickerSize),
            format = { "%.1f".format(it) },
            onChanged = {
                InuConfig.STICKER_SIZE.value = it
                stickerSizePreview?.invalidate()
            },
        )
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuStickers)))
        items.add(UItem.asCustom(stickerSizePreview))
        items.add(UItem.asCustom(stickerSizeSlider))
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
                TOGGLE_NO_STICKER_EXTRA_PADDING,
                LocaleController.getString(R.string.InuNoStickerExtraPadding),
            ).setChecked(InuConfig.NO_STICKER_EXTRA_PADDING.value)
        )
        items.add(UItem.asShadow(null))

        if (reactionsInRowSlider == null) reactionsInRowSlider = SliderCell(
            context,
            min = 6f,
            max = 15f,
            defaultValue = InuConfig.REACTIONS_IN_ROW.default.toFloat(),
            initialValue = InuConfig.REACTIONS_IN_ROW.value.toFloat(),
            step = 1f,
            title = LocaleController.getString(R.string.InuReactionsInRow),
            format = { it.toInt().toString() },
            onChanged = {
                InuConfig.REACTIONS_IN_ROW.value = it.toInt()
            },
        )
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuReactions)))
        items.add(
            mkSubPageButton(
                BUTTON_PINNED_REACTIONS,
                LocaleController.getString(R.string.InuPinnedReactions),
            )
        )
        items.add(UItem.asCustom(reactionsInRowSlider))
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
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_CONFIRM_REACTION_NON_MEMBER,
                R.string.InuConfirmReactionNonMember,
                R.string.InuConfirmReactionNonMemberInfo,
                InuConfig.CONFIRM_REACTION_NON_MEMBER.value,
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuSpoilers)))
        items.add(
            UItem.asButton(
                BUTTON_TEXT_SPOILER_MODE,
                LocaleController.getString(R.string.InuTextSpoilerMode),
                textSpoilerModeLabel(InuConfig.TEXT_SPOILER_MODE.value),
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_MEDIA_SPOILER_MODE,
                LocaleController.getString(R.string.InuMediaSpoilerMode),
                mediaSpoilerModeLabel(InuConfig.MEDIA_SPOILER_MODE.value),
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SPOILER_EXTEND_TO_LINE_END,
                R.string.InuSpoilerExtendToLineEnd,
                R.string.InuSpoilerExtendToLineEndInfo,
                InuConfig.SPOILER_EXTEND_TO_LINE_END.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_LINK_PREVIEW_SPOILER,
                R.string.InuLinkPreviewSpoiler,
                R.string.InuLinkPreviewSpoilerInfo,
                InuConfig.LINK_PREVIEW_SPOILER.value,
            )
        )
        items.add(UItem.asShadow(null))

        if (miscPreview == null) miscPreview = MiscPreviewMessagesCell(this.context, this)
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuMiscellaneous)))
        items.add(UItem.asCustom(miscPreview))
        items.add(
            mkSubPageButton(
                BUTTON_MESSAGE_MENU_ORDER,
                LocaleController.getString(R.string.InuMessageMenuOrder),
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_CHAT_REMEMBER_ALL_REPLIES,
                LocaleController.getString(R.string.InuChatRememberAllReplies),
            ).setChecked(InuConfig.CHAT_REMEMBER_ALL_REPLIES.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_SHOW_FORWARD_TIME,
                LocaleController.getString(R.string.InuShowForwardTime),
            ).setChecked(InuConfig.SHOW_FORWARD_TIME.value)
        )
        items.add(
            UItem.asButton(
                BUTTON_FORWARD_HEADER_MODE,
                LocaleController.getString(R.string.InuForwardHeaderMode),
                forwardHeaderModeLabel(InuConfig.FORWARD_HEADER_MODE.value),
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_SHOW_FORWARDS_COUNT,
                LocaleController.getString(R.string.InuShowForwardsCount),
            ).setChecked(InuConfig.SHOW_FORWARDS_COUNT.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_COMPACT_EDITED,
                LocaleController.getString(R.string.InuCompactEdited),
            ).setChecked(InuConfig.COMPACT_EDITED.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_BUBBLE_TAILS,
                LocaleController.getString(R.string.InuBubbleTails),
            ).setChecked(InuConfig.BUBBLE_TAILS.value)
        )
        items.add(UItem.asShadow(null))
        items.add(
            UItem.asButton(
                BUTTON_BLOCKED_MESSAGES_MODE,
                LocaleController.getString(R.string.InuBlockedMessagesMode),
                blockedMessagesModeLabel(InuConfig.BLOCKED_MESSAGES_MODE.value),
            )
        )
        if (BlockedMessagesHelper.isEnabled()) {
            items.add(
                UItem.asButton(
                    BUTTON_BLOCKED_MESSAGES_EXTRA,
                    LocaleController.getString(R.string.InuBlockedMessagesExtra),
                    LocaleController.formatString(
                        R.string.InuBlockedMessagesExtraCount,
                        BlockedMessagesHelper.getExtraHidden(currentAccount).size,
                    ),
                )
            )
        }
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuDoubleTapActions)))
        items.add(
            UItem.asButton(
                BUTTON_DOUBLE_TAP_INCOMING,
                LocaleController.getString(R.string.InuIncomingMessages),
                DoubleTapAction.fromValue(InuConfig.DOUBLE_TAP_ACTION_INCOMING.value, DoubleTapContext.INCOMING).label()
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_DOUBLE_TAP_OUTGOING,
                LocaleController.getString(R.string.InuOutgoingMessages),
                DoubleTapAction.fromValue(InuConfig.DOUBLE_TAP_ACTION_OUTGOING.value, DoubleTapContext.OUTGOING).label()
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_DOUBLE_TAP_CHANNEL,
                LocaleController.getString(R.string.InuChannelMessages),
                channelActionLabel(),
            )
        )
        if (doubleTapDelaySlider == null) doubleTapDelaySlider = SliderCell(
            this.context, min = 75f, max = 300f,
            defaultValue = InuConfig.DOUBLE_TAP_DELAY.default.toFloat(),
            initialValue = InuConfig.DOUBLE_TAP_DELAY.value.toFloat(),
            title = LocaleController.getString(R.string.InuDelay),
            format = { "${it.toInt()} ms" },
            onChanged = {
                InuConfig.DOUBLE_TAP_DELAY.value = it.toInt()
                InuHooks.syncDoubleTapDelay()
            },
        )
        items.add(UItem.asCustom(doubleTapDelaySlider))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuDoubleTapInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_STICKER_TIME_MODE -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuStickerTimeModeShow),
                    LocaleController.getString(R.string.InuStickerTimeModeHideTime),
                    LocaleController.getString(R.string.InuStickerTimeModeHideIncoming),
                    LocaleController.getString(R.string.InuStickerTimeModeHideCompletely),
                ),
                InuConfig.STICKER_TIME_MODE.value - 1,
            ) { which ->
                InuConfig.STICKER_TIME_MODE.value = which + 1
                stickerSizePreview?.invalidate()
            }

            TOGGLE_NO_STICKER_EXTRA_PADDING -> {
                val new = InuConfig.NO_STICKER_EXTRA_PADDING.toggle()
                (view as? TextCheckCell)?.isChecked = new
                stickerSizePreview?.invalidate()
            }

            TOGGLE_REACTION_BAR_BELOW -> {
                val new = InuConfig.REACTION_BAR_BELOW.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_CHAT_VIEWS_BOTTOM -> {
                val new = InuConfig.CHAT_VIEWS_BOTTOM.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_REACTION_ENTRY -> {
                val new = InuConfig.HIDE_REACTIONS_ENTRY.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_CONFIRM_REACTION_NON_MEMBER -> {
                val new = InuConfig.CONFIRM_REACTION_NON_MEMBER.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_CHAT_REMEMBER_ALL_REPLIES -> {
                val new = InuConfig.CHAT_REMEMBER_ALL_REPLIES.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_SHOW_FORWARD_TIME -> {
                val new = InuConfig.SHOW_FORWARD_TIME.toggle()
                (view as? TextCheckCell)?.isChecked = new
                miscPreview?.invalidate()
            }

            BUTTON_FORWARD_HEADER_MODE -> showForwardHeaderModeSelector()

            TOGGLE_SHOW_FORWARDS_COUNT -> {
                val new = InuConfig.SHOW_FORWARDS_COUNT.toggle()
                (view as? TextCheckCell)?.isChecked = new
                miscPreview?.invalidate()
            }

            TOGGLE_COMPACT_EDITED -> {
                val new = InuConfig.COMPACT_EDITED.toggle()
                (view as? TextCheckCell)?.isChecked = new
                miscPreview?.invalidate()
            }

            TOGGLE_BUBBLE_TAILS -> {
                val new = InuConfig.BUBBLE_TAILS.toggle()
                (view as? TextCheckCell)?.isChecked = new
                miscPreview?.invalidate()
            }

            TOGGLE_SPOILER_EXTEND_TO_LINE_END -> {
                val new = InuConfig.SPOILER_EXTEND_TO_LINE_END.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_LINK_PREVIEW_SPOILER -> {
                val new = InuConfig.LINK_PREVIEW_SPOILER.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            BUTTON_MEDIA_SPOILER_MODE -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuMediaSpoilerModeTelegram),
                    LocaleController.getString(R.string.InuMediaSpoilerModePill),
                    LocaleController.getString(R.string.InuMediaSpoilerModeCircle),
                ),
                InuConfig.MEDIA_SPOILER_MODE.value,
            ) { which ->
                if (InuConfig.MEDIA_SPOILER_MODE.value == which) return@show
                InuConfig.MEDIA_SPOILER_MODE.value = which
            }

            BUTTON_TEXT_SPOILER_MODE -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuTextSpoilerModeDefault),
                    LocaleController.getString(R.string.InuTextSpoilerModeSimple),
                    LocaleController.getString(R.string.InuTextSpoilerModeEpstein),
                ),
                InuConfig.TEXT_SPOILER_MODE.value,
            ) { which ->
                if (InuConfig.TEXT_SPOILER_MODE.value == which) return@show
                InuConfig.TEXT_SPOILER_MODE.value = which
            }

            BUTTON_BLOCKED_MESSAGES_MODE -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuBlockedMessagesModeOff),
                    LocaleController.getString(R.string.InuBlockedMessagesModeSpoiler),
                    LocaleController.getString(R.string.InuBlockedMessagesModeHide),
                ),
                InuConfig.BLOCKED_MESSAGES_MODE.value,
            ) { which ->
                if (InuConfig.BLOCKED_MESSAGES_MODE.value == which) return@show
                InuConfig.BLOCKED_MESSAGES_MODE.value = which
                listView.adapter.update(true)
            }

            BUTTON_BLOCKED_MESSAGES_EXTRA -> openBlockedExtraPicker()

            BUTTON_PINNED_REACTIONS -> presentFragment(PinnedReactionsActivity())

            BUTTON_MESSAGE_MENU_ORDER -> presentFragment(MessageMenuOrderActivity())

            BUTTON_DOUBLE_TAP_INCOMING -> showDoubleTapSelector(view, DoubleTapContext.INCOMING)
            BUTTON_DOUBLE_TAP_OUTGOING -> showDoubleTapSelector(view, DoubleTapContext.OUTGOING)
            BUTTON_DOUBLE_TAP_CHANNEL -> showDoubleTapSelector(view, DoubleTapContext.CHANNEL)
        }
    }

    private fun showForwardHeaderModeSelector() {
        val context = context ?: return
        val items = listOf(
            RadioDialogBuilder.Item(LocaleController.getString(R.string.InuForwardHeaderModeRegular)),
            RadioDialogBuilder.Item(
                LocaleController.getString(R.string.InuForwardHeaderModeIcon),
                if (InuConfig.SHOW_FORWARD_TIME.value) null
                else LocaleController.getString(R.string.InuForwardHeaderModeIconNoTimeInfo),
            ),
            RadioDialogBuilder.Item(LocaleController.getString(R.string.InuForwardHeaderModeCompact)),
        )
        showDialog(
            RadioDialogBuilder(context, getResourceProvider())
                .setTitle(LocaleController.getString(R.string.InuForwardHeaderMode))
                .setItems(items, InuConfig.FORWARD_HEADER_MODE.value) { _, which ->
                    if (InuConfig.FORWARD_HEADER_MODE.value == which) return@setItems
                    InuConfig.FORWARD_HEADER_MODE.value = which
                    miscPreview?.invalidate()
                    listView.adapter.update(false)
                }.create()
        )
    }

    private fun openBlockedExtraPicker() {
        val args = Bundle().apply {
            putBoolean("isNeverShare", true)
            putInt("chatAddType", 2)
            putBoolean("inu_allowChannels", true)
        }
        val fragment = GroupCreateActivity(args)
        fragment.select(ArrayList(BlockedMessagesHelper.getExtraHidden(currentAccount)), false, false)
        fragment.setDelegate { _, _, ids ->
            BlockedMessagesHelper.setExtraHidden(currentAccount, ids)
            listView.adapter.update(true)
        }
        presentFragment(fragment)
    }

    private fun doubleTapConfig(context: DoubleTapContext) = when (context) {
        DoubleTapContext.INCOMING -> InuConfig.DOUBLE_TAP_ACTION_INCOMING
        DoubleTapContext.OUTGOING -> InuConfig.DOUBLE_TAP_ACTION_OUTGOING
        DoubleTapContext.CHANNEL -> InuConfig.DOUBLE_TAP_ACTION_CHANNEL
    }

    private fun showDoubleTapSelector(anchor: View, context: DoubleTapContext) {
        val actions = DoubleTapAction.available(context)
        val config = doubleTapConfig(context)
        val inheritable = context == DoubleTapContext.CHANNEL
        val offset = if (inheritable) 1 else 0

        val labels = ArrayList<CharSequence>()
        if (inheritable) labels.add(LocaleController.getString(R.string.InuSameAsIncoming))
        actions.mapTo(labels) { it.label() }

        val selected = if (inheritable && config.value == DoubleTapActionHelper.INHERIT_INCOMING) {
            0
        } else {
            actions.indexOfFirst { it.value == config.value }.coerceAtLeast(0) + offset
        }

        RadioItemOptions.show(this, anchor, labels, selected) { which ->
            config.value = if (inheritable && which == 0) {
                DoubleTapActionHelper.INHERIT_INCOMING
            } else {
                actions.getOrNull(which - offset)?.value ?: return@show
            }
        }
    }

    private fun channelActionLabel(): CharSequence {
        val value = InuConfig.DOUBLE_TAP_ACTION_CHANNEL.value
        if (value == DoubleTapActionHelper.INHERIT_INCOMING) {
            return LocaleController.getString(R.string.InuSameAsIncoming)
        }
        return DoubleTapAction.fromValue(value, DoubleTapContext.CHANNEL).label()
    }

    companion object {
        private val BUTTON_STICKER_TIME_MODE = InuUtils.generateId()
        private val TOGGLE_NO_STICKER_EXTRA_PADDING = InuUtils.generateId()
        private val BUTTON_PINNED_REACTIONS = InuUtils.generateId()
        private val BUTTON_MESSAGE_MENU_ORDER = InuUtils.generateId()
        private val TOGGLE_REACTION_BAR_BELOW = InuUtils.generateId()
        private val TOGGLE_CHAT_VIEWS_BOTTOM = InuUtils.generateId()
        private val TOGGLE_HIDE_REACTION_ENTRY = InuUtils.generateId()
        private val TOGGLE_CONFIRM_REACTION_NON_MEMBER = InuUtils.generateId()
        private val TOGGLE_CHAT_REMEMBER_ALL_REPLIES = InuUtils.generateId()
        private val TOGGLE_SHOW_FORWARD_TIME = InuUtils.generateId()
        private val BUTTON_FORWARD_HEADER_MODE = InuUtils.generateId()
        private val TOGGLE_SHOW_FORWARDS_COUNT = InuUtils.generateId()
        private val TOGGLE_COMPACT_EDITED = InuUtils.generateId()
        private val TOGGLE_BUBBLE_TAILS = InuUtils.generateId()
        private val BUTTON_DOUBLE_TAP_INCOMING = InuUtils.generateId()
        private val BUTTON_DOUBLE_TAP_OUTGOING = InuUtils.generateId()
        private val BUTTON_DOUBLE_TAP_CHANNEL = InuUtils.generateId()
        private val BUTTON_TEXT_SPOILER_MODE = InuUtils.generateId()
        private val TOGGLE_SPOILER_EXTEND_TO_LINE_END = InuUtils.generateId()
        private val TOGGLE_LINK_PREVIEW_SPOILER = InuUtils.generateId()
        private val BUTTON_MEDIA_SPOILER_MODE = InuUtils.generateId()
        private val BUTTON_BLOCKED_MESSAGES_MODE = InuUtils.generateId()
        private val BUTTON_BLOCKED_MESSAGES_EXTRA = InuUtils.generateId()

        private fun textSpoilerModeLabel(value: Int): String = when (value) {
            InuConfig.TextSpoilerModeItem.SIMPLE -> LocaleController.getString(R.string.InuTextSpoilerModeSimple)
            InuConfig.TextSpoilerModeItem.EPSTEIN -> LocaleController.getString(R.string.InuTextSpoilerModeEpstein)
            else -> LocaleController.getString(R.string.InuTextSpoilerModeDefault)
        }

        private fun mediaSpoilerModeLabel(value: Int): String = when (value) {
            InuConfig.MediaSpoilerModeItem.PILL -> LocaleController.getString(R.string.InuMediaSpoilerModePill)
            InuConfig.MediaSpoilerModeItem.CIRCLE -> LocaleController.getString(R.string.InuMediaSpoilerModeCircle)
            else -> LocaleController.getString(R.string.InuMediaSpoilerModeTelegram)
        }

        private fun forwardHeaderModeLabel(value: Int): String = when (value) {
            InuConfig.ForwardHeaderModeItem.COMPACT -> LocaleController.getString(R.string.InuForwardHeaderModeCompact)
            InuConfig.ForwardHeaderModeItem.ICON -> LocaleController.getString(R.string.InuForwardHeaderModeIcon)
            else -> LocaleController.getString(R.string.InuForwardHeaderModeRegular)
        }

        private fun blockedMessagesModeLabel(value: Int): String = when (value) {
            InuConfig.BlockedMessagesModeItem.SPOILER -> LocaleController.getString(R.string.InuBlockedMessagesModeSpoiler)
            InuConfig.BlockedMessagesModeItem.HIDE -> LocaleController.getString(R.string.InuBlockedMessagesModeHide)
            else -> LocaleController.getString(R.string.InuBlockedMessagesModeOff)
        }

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "messages",
            titleRes = R.string.InuMessages,
            iconRes = R.drawable.msg_discuss,
            factory = ::MessagesSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("sticker-time-mode", R.string.InuStickerTimeMode, BUTTON_STICKER_TIME_MODE),
                SearchRegistry.Entry("no-sticker-extra-padding", R.string.InuNoStickerExtraPadding, TOGGLE_NO_STICKER_EXTRA_PADDING),
                SearchRegistry.Entry("pinned-reactions", R.string.InuPinnedReactions, BUTTON_PINNED_REACTIONS),
                SearchRegistry.Entry("reaction-bar-below", R.string.InuReactionBarBelow, TOGGLE_REACTION_BAR_BELOW),
                SearchRegistry.Entry("chat-views-bottom", R.string.InuChatViewsBottom, TOGGLE_CHAT_VIEWS_BOTTOM),
                SearchRegistry.Entry("hide-reaction-entry", R.string.InuHideReactionEntry, TOGGLE_HIDE_REACTION_ENTRY),
                SearchRegistry.Entry("confirm-reaction-non-member", R.string.InuConfirmReactionNonMember, TOGGLE_CONFIRM_REACTION_NON_MEMBER),
                SearchRegistry.Entry("message-menu-order", R.string.InuMessageMenuOrder, BUTTON_MESSAGE_MENU_ORDER),
                SearchRegistry.Entry("chat-remember-all-replies", R.string.InuChatRememberAllReplies, TOGGLE_CHAT_REMEMBER_ALL_REPLIES),
                SearchRegistry.Entry("show-forward-time", R.string.InuShowForwardTime, TOGGLE_SHOW_FORWARD_TIME),
                SearchRegistry.Entry("compact-forwarded", R.string.InuForwardHeaderMode, BUTTON_FORWARD_HEADER_MODE),
                SearchRegistry.Entry("show-forwards-count", R.string.InuShowForwardsCount, TOGGLE_SHOW_FORWARDS_COUNT),
                SearchRegistry.Entry("compact-edited", R.string.InuCompactEdited, TOGGLE_COMPACT_EDITED),
                SearchRegistry.Entry("bubble-tails", R.string.InuBubbleTails, TOGGLE_BUBBLE_TAILS),
                SearchRegistry.Entry("double-tap-incoming", R.string.InuIncomingMessages, BUTTON_DOUBLE_TAP_INCOMING),
                SearchRegistry.Entry("double-tap-outgoing", R.string.InuOutgoingMessages, BUTTON_DOUBLE_TAP_OUTGOING),
                SearchRegistry.Entry("double-tap-channel", R.string.InuChannelMessages, BUTTON_DOUBLE_TAP_CHANNEL),
                SearchRegistry.Entry("text-spoiler-mode", R.string.InuTextSpoilerMode, BUTTON_TEXT_SPOILER_MODE),
                SearchRegistry.Entry("spoiler-extend-to-line-end", R.string.InuSpoilerExtendToLineEnd, TOGGLE_SPOILER_EXTEND_TO_LINE_END),
                SearchRegistry.Entry("link-preview-spoiler", R.string.InuLinkPreviewSpoiler, TOGGLE_LINK_PREVIEW_SPOILER),
                SearchRegistry.Entry("media-spoiler-mode", R.string.InuMediaSpoilerMode, BUTTON_MEDIA_SPOILER_MODE),
                SearchRegistry.Entry("blocked-messages-mode", R.string.InuBlockedMessagesMode, BUTTON_BLOCKED_MESSAGES_MODE),
                SearchRegistry.Entry("blocked-messages-extra", R.string.InuBlockedMessagesExtra, BUTTON_BLOCKED_MESSAGES_EXTRA),
            ),
        )
    }
}
