package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.InuHooks
import desu.inugram.helpers.DoubleTapAction
import desu.inugram.helpers.InuUtils
import desu.inugram.ui.settings.PinnedReactionsActivity
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class MessagesSettingsActivity : SettingsPageActivity() {

    private var stickerSizePreview: StickerSizePreviewMessagesCell? = null
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

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuMiscellaneous)))
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

            TOGGLE_CHAT_REMEMBER_ALL_REPLIES -> {
                val new = InuConfig.CHAT_REMEMBER_ALL_REPLIES.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_SHOW_FORWARD_TIME -> {
                val new = InuConfig.SHOW_FORWARD_TIME.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            BUTTON_PINNED_REACTIONS -> presentFragment(PinnedReactionsActivity())

            BUTTON_DOUBLE_TAP_INCOMING -> showDoubleTapSelector(view, false)
            BUTTON_DOUBLE_TAP_OUTGOING -> showDoubleTapSelector(view, true)
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
        private val BUTTON_STICKER_TIME_MODE = InuUtils.generateId()
        private val TOGGLE_NO_STICKER_EXTRA_PADDING = InuUtils.generateId()
        private val BUTTON_PINNED_REACTIONS = InuUtils.generateId()
        private val TOGGLE_REACTION_BAR_BELOW = InuUtils.generateId()
        private val TOGGLE_CHAT_VIEWS_BOTTOM = InuUtils.generateId()
        private val TOGGLE_HIDE_REACTION_ENTRY = InuUtils.generateId()
        private val TOGGLE_CHAT_REMEMBER_ALL_REPLIES = InuUtils.generateId()
        private val TOGGLE_SHOW_FORWARD_TIME = InuUtils.generateId()
        private val BUTTON_DOUBLE_TAP_INCOMING = InuUtils.generateId()
        private val BUTTON_DOUBLE_TAP_OUTGOING = InuUtils.generateId()
    }
}
