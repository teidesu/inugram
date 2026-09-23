package desu.inugram.helpers.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.Layout
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.edit
import desu.inugram.InuConfig
import desu.inugram.helpers.plugins.ui.ActionKey
import desu.inugram.helpers.plugins.ui.ActionRow
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.StickerDownloadHelper
import desu.inugram.helpers.WebAppHelper
import desu.inugram.helpers.cloud.SettingsBackupHelper
import desu.inugram.helpers.font.FontImportHelper
import desu.inugram.helpers.media.MediaSendDebugHelper
import desu.inugram.helpers.menu.MessageMenuConfig
import desu.inugram.helpers.menu.reorderByMenu
import desu.inugram.helpers.menu.reorderByKeys
import desu.inugram.helpers.plugins.PluginImportHelper
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.ui.MessageActionSource
import desu.inugram.helpers.plugins.ui.ActionSurface
import desu.inugram.helpers.plugins.ui.PluginActions
import desu.inugram.helpers.plugins.ui.PluginIcons
import desu.inugram.helpers.plugins.ui.RegisteredActionRow
import desu.inugram.helpers.translate.TranslateHelper
import desu.inugram.ui.MessageDetailsActivity
import desu.inugram.ui.showInputDialog
import java.io.File
import java.util.Calendar
import kotlin.math.roundToInt
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.BuildVars
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.FileLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagePreviewParams
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.TranslateController
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.messenger.Utilities
import org.telegram.messenger.utils.tlutils.TLKeyboardHelper
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_keyboard
import org.telegram.ui.ActionBar.ActionBarMenuSubItem
import org.telegram.ui.ActionBar.ActionBarPopupWindow
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.BasePermissionsActivity
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.AnimatedEmojiSpan
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ChatActivityEnterView
import org.telegram.ui.Components.ColoredImageSpan
import org.telegram.ui.Components.EditTextCaption
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.PopupSwipeBackLayout
import org.telegram.ui.Components.RLottieDrawable
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble
import org.telegram.ui.Components.ReactionsContainerLayout
import org.telegram.ui.Components.ScaleStateListAnimator
import org.telegram.ui.Components.URLSpanUserMention
import org.telegram.ui.Components.UndoView
import org.telegram.ui.DialogsActivity
import org.telegram.ui.LaunchActivity
import kotlin.math.ceil

object ChatHelper {
    private var skipNextReactionConfirm = false

    private const val COMPACT_FORWARD_ICON_SIZE = 12f
    private const val COMPACT_FORWARD_MIN_NAME_WIDTH = 56f

    const val OPTION_SAVE = 501
    const val OPTION_DETAILS = 502
    const val OPTION_REPLY_IN = 503
    const val OPTION_SHOW_IN_CHAT = 505
    const val OPTION_TRANSLATE_REVERT = 508
    const val OPTION_FORWARD_NO_QUOTE = 509
    const val OPTION_REPLY_IN_DMS = 510
    const val OPTION_SUMMARIZE = 511
    const val OPTION_REMOVE_FROM_CACHE = 512
    const val OPTION_COPY_MEDIA = 513
    const val OPTION_REPEAT = 514
    const val OPTION_REPEAT_COPY = 515
    const val OPTION_REPEAT_FORWARD = 516
    const val OPTION_SHOW_JSON = 517
    const val OPTION_SAVE_STICKER_TO_DOWNLOADS = 521
    const val OPTION_PLUGIN_ACTIONS = 522

    private fun getForwardsCount(msg: MessageObject?): Int {
        if (msg == null || !InuConfig.SHOW_FORWARDS_COUNT.value) return 0
        return msg.messageOwner?.forwards ?: 0
    }

    private fun formatForwardsCount(msg: MessageObject?): String? {
        val forwards = getForwardsCount(msg)
        if (forwards <= 0) return null
        return " " + LocaleController.formatShortNumber(forwards, null) + "  "
    }

    @JvmStatic
    fun timeAdditionsHash(msg: MessageObject?): Int {
        if (msg == null) return 0
        var hash = 0
        if (TranslateHelper.hasTimeAddition(msg)) {
            hash = hash * 31 + 1
            hash = hash * 31 + TranslateHelper.timeAdditionsHash(msg)
        }
        if (BlockedMessagesHelper.shouldSpoil(msg)) {
            hash = hash * 31 + 2
        }
        val forwards = getForwardsCount(msg)
        if (forwards > 0) {
            hash = hash * 31 + 3
            hash = hash * 31 + forwards
        }
        return hash
    }

    @JvmStatic
    fun extraTimeWidth(msg: MessageObject?, edited: Boolean = false): Int {
        var width = 0
        if (msg != null && TranslateHelper.hasTimeAddition(msg)) {
            width += TranslateHelper.extraTimeWidth(msg)
        }
        if (BlockedMessagesHelper.shouldSpoil(msg)) {
            width += AndroidUtilities.dp(11f)
        }
        if (edited && InuConfig.COMPACT_EDITED.value) {
            width += AndroidUtilities.dp(11f)
        }
        val forwards = formatForwardsCount(msg)
        if (forwards != null) {
            width += AndroidUtilities.dp(11f) + ceil(Theme.chat_timePaint.measureText(forwards)).toInt()
        }
        return width
    }

    @JvmStatic
    fun timePrefix(msg: MessageObject?, time: CharSequence?, edited: Boolean = false): CharSequence? {
        if (time == null || msg == null) return time
        val sb = SpannableStringBuilder()
        TranslateHelper.appendTimePrefix(sb, msg)
        if (BlockedMessagesHelper.shouldSpoil(msg)) {
            appendTimeIcon(sb, R.drawable.msg_block, sizeDp = 11f, translateYDp = 1f)
            sb.append(" ")
        }
        if (edited && InuConfig.COMPACT_EDITED.value) {
            appendTimeIcon(sb, R.drawable.group_edit, sizeDp = 11f)
            sb.append(" ")
        }
        return if (sb.isEmpty()) time else sb.append(time)
    }

    @JvmStatic
    fun forwardsPrefix(msg: MessageObject?, time: CharSequence?): CharSequence? {
        if (time == null || msg == null) return time
        val forwards = formatForwardsCount(msg) ?: return time
        val sb = SpannableStringBuilder()
        appendTimeIcon(sb, R.drawable.mini_forwarded, sizeDp = 11f, translateYDp = 0f)
        sb.append(forwards)
        return sb.append(time)
    }

    @JvmStatic
    fun appendTimeIcon(
        sb: SpannableStringBuilder,
        icon: Int,
        sizeDp: Float = -1f,
        translateYDp: Float = 0f,
        align: Int = ColoredImageSpan.ALIGN_DEFAULT,
    ) {
        sb.append("​")
        sb.setSpan(ColoredImageSpan(icon, align).apply {
            if (sizeDp > 0f) setSize(AndroidUtilities.dp(sizeDp))
            if (translateYDp != 0f) setTranslateY(AndroidUtilities.dpf2(translateYDp))
        }, sb.length - 1, sb.length, 0)
    }

    @JvmStatic
    fun forwardToSavedMessages(activity: ChatActivity, messages: ArrayList<MessageObject>) {
        if (messages.isEmpty()) return
        val selfId = UserConfig.getInstance(activity.currentAccount).clientUserId
        SendMessagesHelper.getInstance(activity.currentAccount)
            .sendMessage(messages, selfId, false, false, true, 0, 0L)
        activity.createUndoView()
        activity.undoView.showWithAction(selfId, UndoView.ACTION_FWD_MESSAGES, messages.size)
    }

    private fun removeWallpaperKey(currentAccount: Int, dialogId: Long) = "remove_wallpaper:$currentAccount:$dialogId"
    private fun removeThemeKey(currentAccount: Int, dialogId: Long) = "remove_theme:$currentAccount:$dialogId"

    private fun toggleDialogBool(key: String): Boolean {
        val new = !InuConfig.prefs.getBoolean(key, false)
        InuConfig.prefs.edit { if (new) putBoolean(key, true) else remove(key) }
        return new
    }

    @JvmStatic
    fun shouldRemoveWallpaper(currentAccount: Int, dialogId: Long): Boolean {
        if (InuConfig.DISABLE_CHAT_BACKGROUNDS.value) return true
        return InuConfig.prefs.getBoolean(removeWallpaperKey(currentAccount, dialogId), false)
    }

    @JvmStatic
    fun toggleRemoveWallpaper(currentAccount: Int, dialogId: Long): Boolean =
        toggleDialogBool(removeWallpaperKey(currentAccount, dialogId))

    @JvmStatic
    fun isRemoveWallpaperSetForDialog(currentAccount: Int, dialogId: Long): Boolean {
        return InuConfig.prefs.getBoolean(removeWallpaperKey(currentAccount, dialogId), false)
    }

    @JvmStatic
    fun shouldRemoveTheme(currentAccount: Int, dialogId: Long): Boolean {
        if (InuConfig.DISABLE_CHAT_THEMES.value) return true
        return InuConfig.prefs.getBoolean(removeThemeKey(currentAccount, dialogId), false)
    }

    @JvmStatic
    fun toggleRemoveTheme(currentAccount: Int, dialogId: Long): Boolean =
        toggleDialogBool(removeThemeKey(currentAccount, dialogId))

    @JvmStatic
    fun isRemoveThemeSetForDialog(currentAccount: Int, dialogId: Long): Boolean {
        return InuConfig.prefs.getBoolean(removeThemeKey(currentAccount, dialogId), false)
    }

    @JvmStatic
    fun finalizeMessageMenu(
        items: ArrayList<CharSequence>,
        options: ArrayList<Int>,
        icons: ArrayList<Int>,
        activity: ChatActivity,
        selectedObject: MessageObject,
        selectedObjectGroup: MessageObject.GroupedMessages?,
        dialogId: Long,
        noforwards: Boolean,
        allowSendActions: Boolean
    ) {
        if (allowSendActions && !noforwards && activity.currentChat != null && !ChatObject.isChannelAndNotMegaGroup(activity.currentChat)) {
            items.add(LocaleController.getString(R.string.InuReplyIn))
            options.add(OPTION_REPLY_IN)
            icons.add(R.drawable.menu_reply)
        }

        if (TranslateHelper.isManuallyAffected(selectedObject, selectedObjectGroup)) {
            val idx = options.indexOf(ChatActivity.OPTION_TRANSLATE)
            if (idx >= 0) {
                items.removeAt(idx); options.removeAt(idx); icons.removeAt(idx)
            }
            items.add(LocaleController.getString(R.string.ShowOriginalButton))
            options.add(OPTION_TRANSLATE_REVERT)
            icons.add(R.drawable.msg_translate)
        } else if (
            !options.contains(ChatActivity.OPTION_TRANSLATE) &&
            TranslateHelper.hasTranslatableWebPage(selectedObject)
        ) {
            items.add(LocaleController.getString(R.string.TranslateMessage))
            options.add(ChatActivity.OPTION_TRANSLATE)
            icons.add(R.drawable.msg_translate)
        }

        if (!selectedObject.messageOwner.summarizedOpen && InuConfig.HIDE_MESSAGE_SUMMARY.value && TranslateController.isSummarizable(selectedObject)) {
            items.add(LocaleController.getString(R.string.InuSummarize))
            options.add(OPTION_SUMMARIZE)
            icons.add(R.drawable.magic_stick_solar)
        }

        if (allowSendActions && !noforwards && dialogId != UserConfig.getInstance(activity.currentAccount).clientUserId) {
            items.add(LocaleController.getString(R.string.InuSaveToSavedMessages))
            options.add(OPTION_SAVE)
            icons.add(R.drawable.msg_saved)
        }

        if (options.contains(ChatActivity.OPTION_FORWARD)) {
            items.add(LocaleController.getString(R.string.InuForwardNoQuote))
            options.add(OPTION_FORWARD_NO_QUOTE)
            icons.add(R.drawable.msg_forward_noquote)
        }

        if (allowSendActions && isMenuItemEnabled(MessageMenuConfig.Item.REPEAT) &&
            canRepeatMessage(activity, selectedObject, selectedObjectGroup)
        ) {
            items.add(LocaleController.getString(R.string.InuRepeat))
            options.add(OPTION_REPEAT)
            icons.add(R.drawable.msg_retry)
        }

        val chatInfo = activity.currentChatInfo
        if (chatInfo != null && chatInfo.can_view_stats && selectedObject.id > 0 && !selectedObject.isStory) {
            items.add(LocaleController.getString(R.string.Statistics))
            options.add(ChatActivity.OPTION_STATISTICS)
            icons.add(R.drawable.msg_stats)
        }

        if (activity.isFiltered) {
            items.add(LocaleController.getString(R.string.InuShowInChat))
            options.add(OPTION_SHOW_IN_CHAT)
            icons.add(R.drawable.msg_openin)
        }

        if (isMenuItemEnabled(MessageMenuConfig.Item.REMOVE_FROM_CACHE) &&
            hasCachedFile(selectedObject, selectedObjectGroup)
        ) {
            items.add(LocaleController.getString(R.string.InuRemoveFromCache))
            options.add(OPTION_REMOVE_FROM_CACHE)
            icons.add(R.drawable.msg_clear)
        }

        // stock OPTION_COPY only covers text/caption — add a fallback that copies the media file URI
        if (!noforwards && !options.contains(ChatActivity.OPTION_COPY) &&
            mediaFileForCopy(activity.currentAccount, selectedObject) != null
        ) {
            items.add(LocaleController.getString(R.string.Copy))
            options.add(OPTION_COPY_MEDIA)
            icons.add(R.drawable.msg_copy)
        }

        if (!noforwards &&
            (selectedObject.isSticker || selectedObject.isAnimatedSticker) &&
            selectedObject.messageOwner?.media is TLRPC.TL_messageMediaDocument
        ) {
            val index = options.indexOf(ChatActivity.OPTION_SAVE_TO_DOWNLOADS_OR_MUSIC)
            if (index >= 0) {
                options[index] = OPTION_SAVE_STICKER_TO_DOWNLOADS
            } else {
                items.add(LocaleController.getString(R.string.SaveToDownloads))
                options.add(OPTION_SAVE_STICKER_TO_DOWNLOADS)
                icons.add(R.drawable.msg_download)
            }
        }

        if (!options.contains(ChatActivity.OPTION_COPY_LINK) &&
            !selectedObject.isEphemeral && !selectedObject.isSponsored && !activity.isInScheduleMode &&
            ChatObject.isChannel(activity.currentChat) && !ChatObject.isMonoForum(activity.currentChat) &&
            selectedObject.dialogId == dialogId
        ) {
            items.add(LocaleController.getString(R.string.CopyLink))
            options.add(ChatActivity.OPTION_COPY_LINK)
            icons.add(R.drawable.msg_link)
        }

        items.add(LocaleController.getString(R.string.InuMessageDetails))
        options.add(OPTION_DETAILS)
        icons.add(R.drawable.msg_info)

        reservePluginItems(items, options, icons, activity, selectedObject, selectedObjectGroup)
        applyMessageMenuOrder(items, options, icons)
    }

    /**
     * one message menu's plugin rows, from the gesture that reserved them to the tap that
     * dispatches one.
     *
     * A value rather than fields on this object because the two things that can end the wait - the
     * render landing and [PluginActions.RENDER_BUDGET_MS] expiring - both settle it, and whichever
     * loses has to be able to tell that it lost. [done] is that answer, and it is per menu because
     * the ui thread can be building the next menu while the render for the previous one is still
     * on its way back.
     */
    private class MessageMenu(
        val surface: ActionSurface,
        val registered: Map<ActionKey, RegisteredActionRow>,
    ) {
        var rows: List<ActionRow> = emptyList()
        val cells = HashMap<Int, ActionBarMenuSubItem>()
        var actionsCell: ActionBarMenuSubItem? = null
        var done = false
        var pending: Runnable? = null
    }

    private var messageMenu: MessageMenu? = null

    private fun reservePluginItems(
        items: ArrayList<CharSequence>,
        options: ArrayList<Int>,
        icons: ArrayList<Int>,
        activity: ChatActivity,
        selectedObject: MessageObject,
        selectedObjectGroup: MessageObject.GroupedMessages?,
    ) {
        messageMenu = null

        if (!InuConfig.PLUGINS_ENABLED.value) return
        val registeredRows = PluginActions.registeredRows(PluginActions.KIND_MESSAGE)
            .filter { PluginActions.isEnabled(it.key) }
        val registered = registeredRows.map { it.key }
        if (registered.isEmpty()) return

        val messages = (selectedObjectGroup?.messages ?: listOf(selectedObject))
            .sortedWith(compareBy<MessageObject> { it.messageOwner.date }.thenBy { it.dialogId }.thenBy { it.id })
            .map { it.messageOwner }
        val menu = MessageMenu(
            ActionSurface.message(
                activity.currentAccount,
                activity.dialogId,
                activity.topicId,
                MessageActionSource.BUBBLE,
                messages,
            ),
            registeredRows.associateBy { it.key },
        )
        messageMenu = menu
        for (key in registered.filter(PluginActions::isPinned)) {
            items.add(menu.registered[key]?.text ?: "…")
            options.add(PluginActions.optionIdFor(key))
            icons.add(R.drawable.msg_settings_old)
        }
        if (registered.any { !PluginActions.isPinned(it) }) {
            items.add(LocaleController.getString(R.string.InuActions))
            options.add(OPTION_PLUGIN_ACTIONS)
            icons.add(R.drawable.msg_settings_old)
        }
        PluginActions.render(PluginActions.KIND_MESSAGE, menu.surface) { rows ->
            if (messageMenu !== menu || menu.done) return@render
            menu.rows = rows
            finishPluginItems(menu)
        }
    }

    @JvmStatic
    fun bindMenuCell(cell: ActionBarMenuSubItem, option: Int) {
        if (option == OPTION_PLUGIN_ACTIONS) {
            val menu = messageMenu ?: return
            menu.actionsCell = cell
            cell.setRightIcon(R.drawable.msg_arrowright)
            if (menu.done && menu.rows.none { PluginActions.isEnabled(it.key) && !PluginActions.isPinned(it.key) }) {
                cell.visibility = View.GONE
            }
            return
        }
        if (option < PluginActions.OPTION_BASE) return
        val menu = messageMenu ?: return
        val key = PluginActions.keyForOption(option)
        val cached = key?.let(menu.registered::get)
        if (cached != null) {
            PluginIcons.setIcon(cell, cached.text ?: "…", cached.icon, cached.owner, R.drawable.msg_settings_old)
        }
        // a menu the budget already gave up on is built *after* it settled, so its cells have
        // nobody left to fill them in and would sit there reading "…"
        if (menu.done) bindRow(menu, option, cell) else menu.cells[option] = cell
    }

    /**
     * the menu is built by the same gesture that opens it and a row's label can only be had from
     * globalQueue, so the rows are reserved at their measured size and the show is parked until they
     * are filled in. Stock already parks it for language detection ([onLangDetectionDone]), and past
     * [PluginActions.RENDER_BUDGET_MS] the reserved rows are dropped rather than shown blank.
     */
    @JvmStatic
    fun gateMessageMenu(showMenu: Runnable): Runnable = Runnable {
        val menu = messageMenu
        if (menu == null || menu.done) {
            showMenu.run()
            return@Runnable
        }
        menu.pending = showMenu
        AndroidUtilities.runOnUIThread({
            if (menu.pending !== showMenu || menu.done) return@runOnUIThread
            finishPluginItems(menu)
        }, PluginActions.RENDER_BUDGET_MS)
    }

    private fun bindRow(menu: MessageMenu, option: Int, cell: ActionBarMenuSubItem) {
        val row = PluginActions.rowAt(menu.rows, option)
        if (row == null || !PluginActions.isEnabled(row.key) || !PluginActions.isPinned(row.key)) {
            cell.visibility = View.GONE
            return
        }
        PluginIcons.setIcon(cell, row.text, row.icon, row.owner, R.drawable.msg_settings_old)
    }

    private fun finishPluginItems(menu: MessageMenu) {
        menu.done = true
        for ((option, cell) in menu.cells) bindRow(menu, option, cell)
        if (menu.rows.none { PluginActions.isEnabled(it.key) && !PluginActions.isPinned(it.key) }) {
            menu.actionsCell?.visibility = View.GONE
        }
        menu.actionsCell = null
        // the cells are the popup's views and this is the last thing that binds them: a menu is
        // built in one turn and filled in the next, so holding them past here keeps the closed
        // popup's view tree - and the ChatActivity behind it - reachable until the next menu opens
        menu.cells.clear()
        val pending = menu.pending ?: return
        menu.pending = null
        pending.run()
    }

    private fun dispatchPluginItem(option: Int): Boolean {
        val menu = messageMenu ?: return true
        val row = PluginActions.rowAt(menu.rows, option) ?: return true
        PluginActions.dispatch(row, menu.surface)
        return true
    }


    private fun applyMessageMenuOrder(
        items: ArrayList<CharSequence>,
        options: ArrayList<Int>,
        icons: ArrayList<Int>,
    ) {
        data class Row(val label: CharSequence, val option: Int, val icon: Int)

        val rows = options.indices.map { Row(items[it], options[it], icons[it]) }
        val entries = InuConfig.MESSAGE_MENU_ITEMS.value
        val enabledRows = reorderByMenu(rows, entries) {
            MessageMenuConfig.Item.forOption(it.option)
        }
        val pluginKeys = enabledRows.mapNotNull { PluginActions.keyForOption(it.option) }
        val mainOrder = PluginActions.mainOrder(
            PluginActions.KIND_MESSAGE,
            entries.filter { !it.bottom && it.enabled }.map { it.item.key },
            pluginKeys,
        ) + entries.filter { it.bottom && it.enabled }.map { PluginActions.builtInOrderKey(it.item.key) }
        val ordered = reorderByKeys(enabledRows, mainOrder) { row ->
            MessageMenuConfig.Item.forOption(row.option)?.let { PluginActions.builtInOrderKey(it.key) }
                ?: PluginActions.keyForOption(row.option)?.let(PluginActions::pluginOrderKey)
        }

        items.clear(); options.clear(); icons.clear()
        for (row in ordered) {
            items.add(row.label); options.add(row.option); icons.add(row.icon)
        }
    }

    /**
     * Builds the bottom action row from the user's config and strips whatever it resolves to
     * from the inline lists, so nothing is duplicated in the vertical menu. Scrim popup only —
     * the shared todo/poll menus must keep these inline.
     *
     * Each entry is `{option, icon, enabled}` with `enabled == 0` for a greyed slot placeholder
     * (its fallback chain matched nothing). Returns an empty list when disabled → stock-identical.
     */
    @JvmStatic
    fun extractBottomMenu(
        items: ArrayList<CharSequence>,
        options: ArrayList<Int>,
        icons: ArrayList<Int>,
    ): ArrayList<IntArray> {
        val result = ArrayList<IntArray>()
        if (!InuConfig.MESSAGE_MENU_BOTTOM_ROW.value) return result

        for (entry in InuConfig.MESSAGE_MENU_ITEMS.value.filter { it.bottom && it.enabled }) {
            val option = if (entry.item.isSlot) resolveSlot(entry.item, options)
            else options.firstOrNull { MessageMenuConfig.Item.forOption(it) == entry.item }
            if (option == null) {
                // slots keep their place as a greyed placeholder NagramX-style; customs are dropped
                if (entry.item.isSlot) result.add(intArrayOf(-1, entry.item.iconRes, 0))
                continue
            }
            val index = options.indexOf(option)
            result.add(intArrayOf(option, icons[index], 1))
            items.removeAt(index); options.removeAt(index); icons.removeAt(index)
        }
        return result
    }

    private fun resolveSlot(item: MessageMenuConfig.Item, options: List<Int>): Int? {
        if (item == MessageMenuConfig.Item.SLOT_REPLY) {
            if (ChatActivity.OPTION_REPLY in options) return ChatActivity.OPTION_REPLY
        }
        if (item == MessageMenuConfig.Item.SLOT_COPY) {
            if (ChatActivity.OPTION_COPY in options) return ChatActivity.OPTION_COPY
            if (OPTION_COPY_MEDIA in options) return OPTION_COPY_MEDIA
        }
        if (item == MessageMenuConfig.Item.SLOT_DELETE) {
            if (ChatActivity.OPTION_DELETE in options) return ChatActivity.OPTION_DELETE
            if (ChatActivity.OPTION_COPY_LINK in options) return ChatActivity.OPTION_COPY_LINK
        }
        if (item == MessageMenuConfig.Item.SLOT_EDIT_FORWARD) {
            if (ChatActivity.OPTION_EDIT in options) return ChatActivity.OPTION_EDIT
            if (ChatActivity.OPTION_FORWARD in options) return ChatActivity.OPTION_FORWARD
        }
        return null
    }

    /**
     * Renders the menu's bottom region in one shared block: a single gap, then the optional
     * icon-button row, then the seen/reactions row (kept bottommost) via [viewsAdder]. No-op —
     * and so stock-identical — when neither the button row nor [viewsAdder] is present.
     *
     * @return true when content was added; callers use this to suppress redundant separators
     *         that stock adds further down (e.g. the gap above the emoji-packs row).
     */
    @JvmStatic
    fun addBottomRegion(
        activity: ChatActivity,
        popupLayout: ActionBarPopupWindow.ActionBarPopupWindowLayout,
        context: Context,
        resourcesProvider: Theme.ResourcesProvider?,
        bottom: List<IntArray>,
        viewsAdder: Runnable?,
        selectedObject: MessageObject?,
        selectedObjectGroup: MessageObject.GroupedMessages?,
    ): Boolean {
        val rowAtTop = bottom.isNotEmpty() && InuConfig.MESSAGE_MENU_QUICK_ACTIONS_TOP.value
        if ((bottom.isEmpty() || rowAtTop) && viewsAdder == null) return false

        popupLayout.addView(
            ActionBarPopupWindow.GapView(context, resourcesProvider),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8)
        )
        if (bottom.isNotEmpty() && !rowAtTop) {
            popupLayout.addView(
                buildQuickRow(activity, popupLayout, context, resourcesProvider, bottom, selectedObject, selectedObjectGroup),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
            )
        }
        viewsAdder?.run()
        return true
    }

    @JvmStatic
    fun addTopRow(
        activity: ChatActivity,
        popupLayout: ActionBarPopupWindow.ActionBarPopupWindowLayout,
        context: Context,
        resourcesProvider: Theme.ResourcesProvider?,
        bottom: List<IntArray>,
        selectedObject: MessageObject?,
        selectedObjectGroup: MessageObject.GroupedMessages?,
    ) {
        if (bottom.isEmpty() || !InuConfig.MESSAGE_MENU_QUICK_ACTIONS_TOP.value) return
        val hasFollowingGap = popupLayout.itemsCount > 1 && popupLayout.getItemAt(1) is ActionBarPopupWindow.GapView
        if (!hasFollowingGap) {
            popupLayout.inu_addViewToTop(
                ActionBarPopupWindow.GapView(context, resourcesProvider),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8)
            )
        }
        popupLayout.inu_addViewToTop(
            buildQuickRow(activity, popupLayout, context, resourcesProvider, bottom, selectedObject, selectedObjectGroup),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )
    }

    private fun buildQuickRow(
        activity: ChatActivity,
        popupLayout: ActionBarPopupWindow.ActionBarPopupWindowLayout,
        context: Context,
        resourcesProvider: Theme.ResourcesProvider?,
        bottom: List<IntArray>,
        selectedObject: MessageObject?,
        selectedObjectGroup: MessageObject.GroupedMessages?,
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val pad = AndroidUtilities.dp(8f)
        val options = bottom.map { it[0] }
        bottom.forEachIndexed { index, entry ->
            val (option, icon, enabled) = entry
            val button = ImageView(context).apply {
                setPadding(pad, pad, pad, pad)
                scaleType = ImageView.ScaleType.CENTER
                setImageResource(icon)
                colorFilter = PorterDuffColorFilter(
                    Theme.getColor(Theme.key_actionBarDefaultSubmenuItemIcon, resourcesProvider),
                    PorterDuff.Mode.MULTIPLY
                )
                if (enabled == 0) {
                    alpha = 0.4f
                } else {
                    ScaleStateListAnimator.apply(this, .1f, 1.5f)
                    setOnClickListener {
                        if (!onMenuOptionClick(activity, popupLayout, it, option, selectedObject, selectedObjectGroup)) {
                            activity.processSelectedOption(option)
                        }
                    }
                    setOnLongClickListener {
                        onMenuOptionLongClick(activity, popupLayout, it, options, index, selectedObject, selectedObjectGroup)
                    }
                }
            }
            row.addView(button, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER, 6, 6, 6, 6))
        }
        return row
    }

    /** @return true if the option was handled */
    @JvmStatic
    fun processMenuOption(
        option: Int,
        activity: ChatActivity,
        selectedObject: MessageObject,
        selectedObjectGroup: MessageObject.GroupedMessages?
    ): Boolean {
        if (option >= PluginActions.OPTION_BASE) return dispatchPluginItem(option)
        when (option) {
            OPTION_SAVE -> {
                val messages = ArrayList<MessageObject>()
                if (selectedObjectGroup != null) {
                    messages.addAll(selectedObjectGroup.messages)
                } else {
                    messages.add(selectedObject)
                }
                forwardToSavedMessages(activity, messages)
            }

            OPTION_REPLY_IN -> {
                var replyMsg = selectedObject
                if (replyMsg.groupId != 0L) {
                    val group = activity.getGroup(replyMsg.groupId)
                    if (group != null) {
                        replyMsg = group.captionMessage ?: replyMsg
                    }
                }
                val args = Bundle().apply {
                    putBoolean("onlySelect", true)
                    putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD)
                    putBoolean("quote", true)
                    putBoolean("reply_to", true)
                    val author = DialogObject.getPeerDialogId(selectedObject.fromPeer)
                    if (author != 0L && author != activity.dialogId && author != UserConfig.getInstance(activity.currentAccount).clientUserId && author > 0) {
                        putLong("reply_to_author", author)
                    }
                    putInt("messagesCount", 1)
                    putBoolean("canSelectTopics", true)
                }
                val fragment = DialogsActivity(args)
                // set replyingMessageObject only when a dialog is selected, not before,
                // so the reply panel doesn't linger if the user presses back
                val capturedReply = replyMsg
                fragment.setDelegate { dlg, dids, message, param, notifyFlag, scheduleDate, scheduleRepeatPeriod, topicsFragment ->
                    activity.replyingMessageObject = capturedReply
                    val result = activity.didSelectDialogs(
                        dlg,
                        dids,
                        message,
                        param,
                        notifyFlag,
                        scheduleDate,
                        scheduleRepeatPeriod,
                        topicsFragment
                    )
                    activity.replyingMessageObject = null
                    result
                }
                activity.presentFragment(fragment)
            }

            OPTION_DETAILS -> {
                activity.presentFragment(MessageDetailsActivity(selectedObject, selectedObjectGroup))
            }

            OPTION_SHOW_JSON -> {
                WebAppHelper.openTlViewer(activity, selectedObject.currentEvent ?: selectedObject.messageOwner)
            }

            OPTION_FORWARD_NO_QUOTE -> {
                activity.processSelectedOption(ChatActivity.OPTION_FORWARD)
                pendingHideAuthor = true
            }

            OPTION_REPLY_IN_DMS -> {
                if (!replyInDms(activity, selectedObject)) {
                    processMenuOption(OPTION_REPLY_IN, activity, selectedObject, selectedObjectGroup)
                }
            }

            ChatActivity.OPTION_TRANSLATE -> TranslateHelper.triggerTranslate(activity, selectedObject, selectedObjectGroup)

            OPTION_TRANSLATE_REVERT -> TranslateHelper.revert(activity, selectedObjectGroup?.captionMessage ?: selectedObject)

            OPTION_SUMMARIZE -> {
                val cell = activity.findMessageCell(selectedObject.id, false) as? ChatMessageCell ?: return true
                cell.delegate?.didPressSummarize(cell, false)
            }

            OPTION_REMOVE_FROM_CACHE -> {
                val parent = activity.parentActivity
                if (parent != null &&
                    (Build.VERSION.SDK_INT <= 28 || BuildVars.NO_SCOPED_STORAGE) &&
                    parent.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                ) {
                    parent.requestPermissions(
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        BasePermissionsActivity.REQUEST_CODE_EXTERNAL_STORAGE,
                    )
                    return true
                }
                val targets = if (selectedObjectGroup != null) ArrayList(selectedObjectGroup.messages) else listOf(selectedObject)
                clearMessageCaches(activity, targets)
            }

            OPTION_SHOW_IN_CHAT -> openInNewChat(activity, activity.dialogId, selectedObject.id)

            OPTION_REPEAT -> {
                val available = availableRepeatModes(activity, selectedObject, selectedObjectGroup)
                val preferred = InuConfig.REPEAT_MODE.value
                val mode = if (preferred in available) preferred else available.firstOrNull() ?: return true
                repeatMessage(activity, selectedObject, selectedObjectGroup, mode == InuConfig.RepeatModeItem.COPY)
            }

            OPTION_REPEAT_COPY -> repeatMessage(activity, selectedObject, selectedObjectGroup, true)

            OPTION_REPEAT_FORWARD -> repeatMessage(activity, selectedObject, selectedObjectGroup, false)

            OPTION_COPY_MEDIA -> {
                val file = mediaFileForCopy(activity.currentAccount, selectedObject)
                if (file != null && InuUtils.copyFileUriToClipboard(file)) {
                    val bulletinRes = if (selectedObject.isPhoto) R.string.InuPhotoCopied else R.string.InuFrameCopied
                    BulletinFactory.of(activity)
                        .createCopyBulletin(LocaleController.getString(bulletinRes))
                        .show()
                }
            }

            OPTION_SAVE_STICKER_TO_DOWNLOADS -> {
                val parent = activity.parentActivity ?: return true
                val document = selectedObject.document ?: return true
                StickerDownloadHelper.saveStickerToDownloads(
                    parent,
                    activity.currentAccount,
                    document,
                    selectedObject,
                    activity.resourceProvider,
                )
            }

            else -> return false
        }
        return true
    }

    private fun canRepeatMessage(
        activity: ChatActivity,
        selected: MessageObject,
        group: MessageObject.GroupedMessages?,
    ): Boolean {
        if (activity.chatMode != ChatActivity.MODE_DEFAULT) return false
        if (activity.currentEncryptedChat != null) return false
        val chat = activity.currentChat
        if (chat != null && !ChatObject.canSendMessages(chat)) return false
        if (selected.id <= 0 || selected.isSponsored || selected.isExpiredStory) return false
        return availableRepeatModes(activity, selected, group).isNotEmpty()
    }

    /** [InuConfig.RepeatModeItem] COPY/FORWARD values usable for this message, in menu order */
    private fun availableRepeatModes(
        activity: ChatActivity,
        selected: MessageObject,
        group: MessageObject.GroupedMessages?,
    ): List<Int> {
        val canForward = canForwardRepeat(activity, selected)
        val hasReply = getPendingReply(activity) != null
        val hasCopyTarget = repeatCopyTarget(activity, selected, group) != null
        val modes = ArrayList<Int>(2)
        if (hasCopyTarget || (canForward && !(hasReply && group != null && !group.isDocuments))) {
            modes.add(InuConfig.RepeatModeItem.COPY)
        }
        if (canForward) modes.add(InuConfig.RepeatModeItem.FORWARD)
        return modes
    }

    private fun canForwardRepeat(activity: ChatActivity, selected: MessageObject): Boolean {
        val noforwards = activity.isPeerNoForwards || selected.messageOwner?.noforwards == true
        return !noforwards && selected.canForwardMessage()
    }

    private fun repeatCopyTarget(
        activity: ChatActivity,
        selected: MessageObject,
        group: MessageObject.GroupedMessages?,
    ): MessageObject? {
        val target = if (group != null && !group.isDocuments) {
            group.messages.singleOrNull { !it.messageOwner?.message.isNullOrEmpty() }
        } else {
            selected.takeIf { !it.messageOwner?.message.isNullOrEmpty() || it.isAnyKindOfSticker }
        } ?: return null
        if (target.isAnyKindOfSticker) {
            if (target.document == null) return null
            val chat = activity.currentChat
            if (chat != null && !ChatObject.canSendStickers(chat)) return null
        }
        return target
    }

    private fun repeatMessage(
        activity: ChatActivity,
        selected: MessageObject,
        group: MessageObject.GroupedMessages?,
        copy: Boolean,
    ) {
        val replyTo = if (copy) getPendingReply(activity) else null
        val quote = if (replyTo != null) activity.replyingQuote else null
        if (quote != null && quote.outdated) {
            activity.showQuoteMessageUpdate()
            return
        }
        if (copy && (replyTo != null || !canForwardRepeat(activity, selected))) {
            val target = repeatCopyTarget(activity, selected, group)
                ?: selected.takeIf { replyTo != null }
                ?: return
            sendAsCopy(activity, target, replyTo, quote)
            if (replyTo != null) activity.afterMessageSend()
            return
        }
        val messages = group?.messages?.let { ArrayList<MessageObject>(it) } ?: arrayListOf(selected)
        activity.forwardMessages(messages, copy, false, true, 0, 0L)
    }

    private fun sendAsCopy(
        activity: ChatActivity,
        target: MessageObject,
        replyTo: MessageObject?,
        quote: ChatActivity.ReplyQuote?,
    ) {
        val helper = SendMessagesHelper.getInstance(activity.currentAccount)
        val did = activity.dialogId
        val threadMsg = activity.threadMessage
        val mono = activity.sendMonoForumPeerId
        val suggest = activity.sendMessageSuggestionParams
        val msg = target.messageOwner

        if (target.isAnyKindOfSticker) {
            helper.sendSticker(
                target.document, null, did, replyTo, threadMsg, null, quote, null,
                true, 0, 0, false, null, activity.messageChatSendParams, 0L, mono, suggest,
            )
            return
        }

        val media = msg.media
        if (media != null
            && media !is TLRPC.TL_messageMediaEmpty
            && media !is TLRPC.TL_messageMediaWebPage
            && media !is TLRPC.TL_messageMediaGame
            && media !is TLRPC.TL_messageMediaInvoice
        ) {
            val params: SendMessagesHelper.SendMessageParams? = when {
                media.photo is TLRPC.TL_photo -> SendMessagesHelper.SendMessageParams.of(
                    media.photo as TLRPC.TL_photo, null, did, replyTo, threadMsg,
                    msg.message, msg.entities, null, null, true, 0, 0,
                    media.ttl_seconds, target, false,
                )
                media.document is TLRPC.TL_document -> SendMessagesHelper.SendMessageParams.of(
                    media.document as TLRPC.TL_document, null, msg.attachPath, did, replyTo, threadMsg,
                    msg.message, msg.entities, null, null, true, 0, 0,
                    media.ttl_seconds, target, null, false,
                )
                media is TLRPC.TL_messageMediaVenue || media is TLRPC.TL_messageMediaGeo ->
                    SendMessagesHelper.SendMessageParams.of(media, did, replyTo, threadMsg, null, null, true, 0, 0)
                media.phone_number != null -> {
                    val user = TLRPC.TL_userContact_old2()
                    user.phone = media.phone_number
                    user.first_name = media.first_name
                    user.last_name = media.last_name
                    user.id = media.user_id
                    SendMessagesHelper.SendMessageParams.of(user, did, replyTo, threadMsg, null, null, true, 0, 0)
                }
                else -> null
            }
            if (params != null) {
                params.replyQuote = quote
                params.monoForumPeer = mono
                params.suggestionParams = suggest
                helper.sendMessage(params)
                return
            }
        }

        if (msg.message != null) {
            var webPage: TLRPC.WebPage? = null
            if (media is TLRPC.TL_messageMediaWebPage) {
                webPage = media.webpage
            }
            val params = SendMessagesHelper.SendMessageParams.of(
                msg.message, did, replyTo, threadMsg,
                webPage, webPage != null, msg.entities, null, null, true, 0, 0, null, false,
            )
            params.replyQuote = quote
            params.monoForumPeer = mono
            params.suggestionParams = suggest
            helper.sendMessage(params)
            return
        }

        activity.forwardMessages(arrayListOf(target), true, false, true, 0, 0L)
    }

    private fun getPendingReply(activity: ChatActivity): MessageObject? {
        val reply = activity.replyingMessageObject ?: return null
        if (reply === activity.threadMessage || reply.isTopicMainMessage) return null
        return reply
    }

    // photo → full cached photo file; video/gif/round → cached poster thumb (matches photo viewer's "copy frame" intent).
    // null when nothing is cached locally — the menu entry isn't added in that case.
    private fun mediaFileForCopy(currentAccount: Int, message: MessageObject): File? {
        if (message.isSticker || message.isAnimatedSticker) return null
        val loader = FileLoader.getInstance(currentAccount)
        if (message.isPhoto) {
            return loader.getPathToMessage(message.messageOwner)?.takeIf { it.exists() }
        }
        if (message.isVideo || message.isGif || message.isRoundVideo) {
            val thumb = FileLoader.getClosestPhotoSizeWithSize(message.photoThumbs, AndroidUtilities.getPhotoSize(true))
                ?: return null
            return loader.getPathToAttach(thumb, true)?.takeIf { it.exists() }
        }
        return null
    }

    // opens dialogId scrolled to messageId in a fresh activity, keeping the current one on the backstack
    @JvmStatic
    fun openInNewChat(activity: ChatActivity, dialogId: Long, messageId: Int) {
        val args = Bundle()
        if (dialogId > 0) {
            args.putLong("user_id", dialogId)
        } else {
            args.putLong("chat_id", -dialogId)
        }
        args.putInt("message_id", messageId)
        args.putBoolean("need_remove_previous_same_chat_activity", false)
        activity.presentFragment(ChatActivity(args))
    }

    @JvmStatic
    fun maybeConfirmReaction(
        fragment: ChatActivity,
        cell: View?,
        message: MessageObject,
        reactionsLayout: ReactionsContainerLayout?,
        fromView: View?,
        x: Float,
        y: Float,
        visibleReaction: ReactionsLayoutInBubble.VisibleReaction,
        fromDoubleTap: Boolean,
        bigEmoji: Boolean,
        addToRecent: Boolean,
        withoutAnimation: Boolean,
    ): Boolean {
        if (!InuConfig.CONFIRM_REACTION_NON_MEMBER.value) return false
        if (skipNextReactionConfirm) {
            skipNextReactionConfirm = false
            return false
        }

        val chat = fragment.currentChat ?: return false
        if (ChatObject.isChannelAndNotMegaGroup(chat)) return false
        if (!ChatObject.isNotInChat(chat)) return false
        if (message.hasChosenReaction(visibleReaction)) return false
        // skip for auto-forwarded messages
        if (message.messageOwner?.fwd_from?.channel_post != null && message.messageOwner?.fwd_from?.saved_from_msg_id != null) return false

        AlertDialog.Builder(fragment.context)
            .setTitle(LocaleController.getString(R.string.InuConfirmReactionTitle))
            .setMessage(run {
                val emojiToken = "🐶" // placeholder, replaced by AnimatedEmojiSpan for custom emojis
                val emojiText = visibleReaction.emojicon ?: emojiToken
                val raw = LocaleController.formatString(R.string.InuConfirmReactionText, emojiText, chat.title ?: "")
                val text = AndroidUtilities.replaceTags(raw)
                if (visibleReaction.emojicon == null && visibleReaction.documentId != 0L) {
                    val idx = text.toString().indexOf(emojiToken)
                    if (idx >= 0) {
                        text.setSpan(
                            AnimatedEmojiSpan(visibleReaction.documentId, null),
                            idx,
                            idx + emojiToken.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                }
                text
            })
            .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ ->
                skipNextReactionConfirm = true
                fragment.selectReaction(
                    cell,
                    message,
                    reactionsLayout,
                    fromView,
                    x,
                    y,
                    visibleReaction,
                    fromDoubleTap,
                    bigEmoji,
                    addToRecent,
                    withoutAnimation
                )
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show()
        return true
    }

    @JvmStatic
    fun isEffectivelyInChat(chat: TLRPC.Chat?): Boolean {
        if (chat == null) return false
        if (!ChatObject.isNotInChat(chat)) return true
        if (!InuConfig.SEND_TO_DISCUSS_WITHOUT_JOIN.value) return false
        if (chat.join_to_send) return false
        return chat.megagroup && chat.has_link
    }

    @JvmStatic
    fun shouldForceHideBottomBar(activity: ChatActivity?): Boolean {
        if (activity == null) return false
        if (activity.isReport) return false
        val chatMode = activity.chatMode
        if (chatMode == ChatActivity.MODE_PINNED) return InuConfig.HIDE_BOTTOM_BAR_PINNED.value

        val user = activity.currentUser
        if (user != null && UserObject.isReplyUser(user) && InuConfig.HIDE_BOTTOM_BAR_REPLIES.value) return true

        val chat = activity.currentChat ?: return false
        if (ChatObject.isMonoForum(chat)) return false
        // stock skips the JOIN bar in non-forum threads w/o join_to_send (e.g. channel-post comments) —
        // don't force-hide there, or we'd also hide the chat input
        if (activity.isThreadChat && !chat.join_to_send && !ChatObject.isForum(chat)) return false
        val member = ChatObject.isInChat(chat)
        if (
            ChatObject.canSendMessages(chat) &&
            // canSendMessages reflects server-side permissions, but stock still shows the JOIN bar for non-members
            // unless we actually let them write (discuss-without-join). also covers the join_request edge case.
            (member || isEffectivelyInChat(chat))
        ) return false

        if (ChatObject.isChannelAndNotMegaGroup(chat)) {
            if (member && InuConfig.HIDE_BOTTOM_BAR_JOINED.value) return true
            if (!member && InuConfig.HIDE_BOTTOM_BAR_NON_JOINED.value) return true
        } else if (!member && InuConfig.HIDE_BOTTOM_BAR_NON_JOINED_GROUPS.value) {
            return true
        }

        return false
    }

    private fun isMenuItemEnabled(item: MessageMenuConfig.Item): Boolean =
        InuConfig.MESSAGE_MENU_ITEMS.value.any { it.item == item && it.enabled }

    private fun messageDocuments(message: MessageObject): List<TLRPC.Document> {
        val out = ArrayList<TLRPC.Document>(2)
        message.getDocument()?.let { out.add(it) }
        message.messageOwner?.media?.alt_documents?.let { out.addAll(it) }
        return out
    }

    private fun partialDownloadFiles(doc: TLRPC.Document): List<File> {
        val cacheDir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE) ?: return emptyList()
        val base = "${doc.dc_id}_${doc.id}"
        return listOf(
            File(cacheDir, "${base}.temp"),
            File(cacheDir, "${base}.temp.enc"),
            File(cacheDir, "${base}_64.pt"),
            File(cacheDir, "${base}_64.preload"),
            File(cacheDir, "${base}_64.iv"),
            File(cacheDir, "${base}_64.iv.enc"),
        )
    }

    private fun hasPartialDownload(message: MessageObject): Boolean {
        val cacheDir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE) ?: return false
        return messageDocuments(message).any { doc ->
            val base = "${doc.dc_id}_${doc.id}"
            File(cacheDir, "$base.temp").exists() || File(cacheDir, "$base.temp.enc").exists()
        }
    }

    private fun hasCachedFile(selected: MessageObject, group: MessageObject.GroupedMessages?): Boolean {
        val list = group?.messages ?: listOf(selected)
        return list.any { it.mediaExists || it.attachPathExists || hasPartialDownload(it) }
    }

    private fun cachedFilesForMessage(currentAccount: Int, message: MessageObject): List<File> {
        val owner = message.messageOwner ?: return emptyList()
        val loader = FileLoader.getInstance(currentAccount)
        val out = ArrayList<File>(8)
        owner.attachPath?.takeIf { it.isNotEmpty() }?.let { out.add(File(it)) }
        loader.getPathToMessage(owner)?.let { out.add(it) }
        for (doc in messageDocuments(message)) {
            loader.getPathToAttach(doc, false)?.let { out.add(it) }
            loader.getPathToAttach(doc, true)?.let { out.add(it) }
            out.addAll(partialDownloadFiles(doc))
        }
        return out
    }

    private fun clearMessageCaches(activity: ChatActivity, messages: List<MessageObject>) {
        val account = activity.currentAccount
        val loader = FileLoader.getInstance(account)
        // cancel in-progress downloads on the UI thread (cheap) so the loader doesn't race the delete
        for (msg in messages) {
            msg.getDocument()?.let { loader.cancelLoadFile(it, true) }
            FileLoader.getClosestPhotoSizeWithSize(msg.photoThumbs, AndroidUtilities.getPhotoSize(true))
                ?.let { loader.cancelLoadFile(it, true) }
        }
        Utilities.globalQueue.postRunnable {
            for (msg in messages) {
                for (file in cachedFilesForMessage(account, msg)) {
                    runCatching {
                        MediaSendDebugHelper.onLocalFileDeleted(file, "clearMessageCaches")
                        if (file.exists() && !file.delete()) file.deleteOnExit()
                    }
                }
                msg.checkMediaExistance()
            }
            AndroidUtilities.runOnUIThread {
                for (msg in messages) {
                    msg.loadingCancelled = true
                    val cell = activity.findMessageCell(msg.id, false) as? ChatMessageCell ?: continue
                    cell.updateButtonState(false, true, false)
                }
                BulletinFactory.of(activity)
                    .createSimpleBulletin(R.raw.ic_delete, LocaleController.getString(R.string.InuCacheRemoved))
                    .show()
            }
        }
    }

    @JvmStatic
    fun maybeHandleFileClick(activity: ChatActivity, message: MessageObject): Boolean {
        val name = message.documentName ?: return false
        val isSettings = name.endsWith(SettingsBackupHelper.FILENAME_SUFFIX)
        val isPlugin = !isSettings && PluginManager.isEngineEnabled() && PluginImportHelper.isPluginFileName(name)
        val isFont = !isSettings && !isPlugin && FontImportHelper.isFontFileName(name)
        if (!isSettings && !isPlugin && !isFont) return false
        val attach = message.messageOwner?.attachPath?.takeIf { it.isNotEmpty() }?.let { File(it) }
        val file = attach?.takeIf { it.exists() }
            ?: FileLoader.getInstance(activity.currentAccount).getPathToMessage(message.messageOwner)
                ?.takeIf { it.exists() }
            ?: return false
        when {
            isSettings -> SettingsBackupHelper.startImportFromFile(activity, file)
            isPlugin -> PluginImportHelper.startImportFromFile(activity, file)
            else -> FontImportHelper.startImportFromFile(activity, message, file, name)
        }
        return true
    }

    @JvmStatic
    fun maybeHandleInlineButtonLongTap(
        activity: ChatActivity,
        cell: ChatMessageCell,
        button: TL_keyboard.KeyboardButtonProto,
    ): Boolean {
        val callback = TLKeyboardHelper.getType(button, TL_keyboard.TL_inlineButtonTypeCallback::class.java)
            ?: return false
        if (activity.parentActivity == null) return false

        val text = button.text ?: ""
        val data = callback.data?.let { String(it, Charsets.UTF_8) } ?: ""
        runCatching {
            cell.performHapticFeedback(
                HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
            )
        }
        val items = arrayOf<CharSequence>(
            LocaleController.formatString(R.string.InuCopyButtonText, text),
            LocaleController.getString(R.string.InuCopyCallbackData),
        )
        BottomSheet.Builder(activity.parentActivity, false, activity.themeDelegate).apply {
            setTitle(data)
            setTitleMultipleLines(true)
            setItems(items) { _, which ->
                val (payload, toast) = when (which) {
                    0 -> text to R.string.TextCopied
                    else -> data to R.string.InuCallbackDataCopied
                }
                AndroidUtilities.addToClipboard(payload)
                BulletinFactory.of(activity)
                    .createCopyBulletin(LocaleController.getString(toast))
                    .show()
            }
            activity.showDialog(create())
        }

        return true
    }

    @JvmStatic
    fun maybeHandleMentionLongTap(
        activity: ChatActivity,
        enterView: ChatActivityEnterView?,
        user: TLRPC.User,
        start: Int,
        len: Int,
    ): Boolean {
        if (enterView == null) return false
        if (enterView.editField == null) return false
        if (user.bot_inline_placeholder != null) return false
        val userId = user.id

        showInputDialog(
            fragment = activity,
            title = LocaleController.getString(R.string.InuMentionInsertTitle),
            initialText = UserObject.getUserName(user),
            selectAll = true,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        ) { text ->
            if (text.isEmpty()) return@showInputDialog false
            val spannable = SpannableString("$text ")
            spannable.setSpan(
                URLSpanUserMention("" + userId, 3),
                0, text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            enterView.replaceWithText(start, len, spannable, false)
            true
        }
        return true
    }

    @JvmStatic
    fun formatForwardHeaderLine(line: CharSequence, messageObject: MessageObject): CharSequence {
        if (messageObject.type == MessageObject.TYPE_STORY) return line
        if (isCompactForward(messageObject)) return line
        val suffix = getForwardTimeSuffix(messageObject)
        if (isIconForward(messageObject)) {
            val sb = SpannableStringBuilder()
            appendTimeIcon(sb, R.drawable.mini_forwarded, sizeDp = COMPACT_FORWARD_ICON_SIZE, translateYDp = 1f)
            suffix?.let { sb.append(" ").append(it) }
            return sb
        }
        if (suffix == null) return line
        return SpannableStringBuilder(line).append(" • ").append(suffix)
    }

    @JvmStatic
    fun isCompactForward(messageObject: MessageObject?): Boolean {
        if (!supportsCustomForwardHeader(messageObject)) return false
        return InuConfig.FORWARD_HEADER_MODE.value == InuConfig.ForwardHeaderModeItem.COMPACT ||
            InuConfig.FORWARD_HEADER_MODE.value == InuConfig.ForwardHeaderModeItem.ICON &&
            !InuConfig.SHOW_FORWARD_TIME.value
    }

    @JvmStatic
    fun getForwardLineCount(messageObject: MessageObject?): Int = if (isCompactForward(messageObject)) 1 else 2

    @JvmStatic
    fun getCompactForwardPrefixWidth(messageObject: MessageObject?): Int {
        if (!isCompactForward(messageObject)) return 0
        return AndroidUtilities.dp(COMPACT_FORWARD_ICON_SIZE) +
            Theme.chat_forwardNamePaint.measureText(" ").roundToInt()
    }

    @JvmStatic
    fun maybeCompactForwardLine(name: CharSequence, maxWidth: Int, messageObject: MessageObject): CharSequence {
        if (!isCompactForward(messageObject)) return name
        val withoutSuffix = maxWidth - getCompactForwardPrefixWidth(messageObject)
        var suffix = getForwardTimeSuffix(messageObject)?.let { " • $it" } ?: ""
        var available = withoutSuffix - Theme.chat_forwardNamePaint.measureText(suffix)
        if (available < AndroidUtilities.dp(COMPACT_FORWARD_MIN_NAME_WIDTH)) {
            suffix = ""
            available = withoutSuffix.toFloat()
        }
        val sb = SpannableStringBuilder()
        appendTimeIcon(sb, R.drawable.mini_forwarded, sizeDp = COMPACT_FORWARD_ICON_SIZE, translateYDp = 1f)
        sb.append(" ")
        sb.append(TextUtils.ellipsize(name, Theme.chat_forwardNamePaint, available, TextUtils.TruncateAt.END))
        sb.append(suffix)
        return sb
    }

    @JvmStatic
    fun maybeCompactForwardLayouts(layouts: Array<StaticLayout>, width: Int, messageObject: MessageObject) {
        if (!isCompactForward(messageObject)) return
        layouts[0] = layouts[1]
        layouts[1] = StaticLayout(
            "",
            Theme.chat_forwardNamePaint,
            width,
            Layout.Alignment.ALIGN_NORMAL,
            1f,
            0f,
            false,
        )
    }

    @JvmStatic
    fun getCompactForwardHintShift(messageObject: MessageObject?): Int {
        if (!isCompactForward(messageObject)) return 0
        val stockHeight = AndroidUtilities.dp(4f) + Theme.chat_forwardNamePaint.textSize.toInt() * 2
        return (stockHeight / 2f + AndroidUtilities.dpf2(1.33f)).roundToInt()
    }

    @JvmStatic
    fun getForwardAccessibilityPrefix(messageObject: MessageObject?): String {
        if (!isCompactForward(messageObject) && !isIconForward(messageObject)) return ""
        return LocaleController.getString(R.string.ForwardedFrom) + " "
    }

    private fun isIconForward(messageObject: MessageObject?): Boolean {
        return supportsCustomForwardHeader(messageObject) &&
            InuConfig.FORWARD_HEADER_MODE.value == InuConfig.ForwardHeaderModeItem.ICON &&
            InuConfig.SHOW_FORWARD_TIME.value
    }

    private fun supportsCustomForwardHeader(messageObject: MessageObject?): Boolean {
        if (messageObject == null || messageObject.type == MessageObject.TYPE_STORY) return false
        val fwd = messageObject.messageOwner?.fwd_from ?: return false
        return fwd.psa_type.isNullOrEmpty()
    }

    private fun getForwardTimeSuffix(messageObject: MessageObject): String? {
        if (!InuConfig.SHOW_FORWARD_TIME.value) return null
        val fwd = messageObject.messageOwner.fwd_from ?: return null
        if (fwd.date == 0) return null

        val origMs = fwd.date * 1000L
        val msgMs = messageObject.messageOwner.date * 1000L
        val time = LocaleController.getInstance().formatterDay.format(origMs)
        return if (isSameDay(origMs, msgMs)) {
            time
        } else {
            "${LocaleController.getInstance().formatterYearMax.format(origMs)} $time"
        }
    }

    private fun isSameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    @JvmStatic
    fun shouldHideFadeView(): Boolean = InuConfig.HIDE_FADE_VIEW.value

    @JvmStatic
    fun shouldForceHideBotCommands(activity: ChatActivity?): Boolean {
        if (activity == null) return false
        val chat = activity.currentChat
        val user = activity.currentUser

        if (chat != null && !ChatObject.isChannelAndNotMegaGroup(chat) && InuConfig.HIDE_BOT_SLASH_GROUPS.value) return true
        if (user != null && UserObject.isBot(user) && InuConfig.HIDE_BOT_SLASH_BOTS.value) return true
        return false
    }

    @JvmStatic
    fun handleCalendarJumpToBeginning(fallback: MessagesStorage.IntCallback, dismiss: Runnable) {
        dismiss.run()
        val activity = LaunchActivity.getLastFragment() as? ChatActivity
        if (activity == null) {
            // non-ChatActivity callsites (e.g. admin log) ignore the date in their callback anyway
            fallback.run(0)
            return
        }
        jumpToBeginning(activity)
    }

    @JvmStatic
    fun jumpToBeginning(activity: ChatActivity) {
        if (activity.isThreadChat || activity.isTopic) {
            activity.scrollToMessageId(activity.threadMessageId.toInt(), 0, false, 0, true, 0)
            return
        }
        if (DialogObject.isEncryptedDialog(activity.dialogId)) return
        // date=1 routes through stock's loadMessages-by-date path — handles merged dialogs,
        // loading state, and the "already at end" case
        activity.jumpToDate(1)
    }

    @JvmStatic
    fun onFragmentDestroy(activity: ChatActivity) {
        ChatActionsHelper.onFragmentDestroy(activity)
        TranslateHelper.resetForDialog(activity.dialogId)
    }

    @JvmField
    var pendingHideAuthor: Boolean = false

    @JvmField
    var pendingHideCaption: Boolean = false

    @JvmStatic
    fun clearForwardFlags() {
        pendingHideAuthor = false
        pendingHideCaption = false
    }

    @JvmStatic
    fun applyForwardOptionsToPreview(params: MessagePreviewParams?) {
        if (params == null) return
        if (pendingHideCaption || pendingHideAuthor) params.hideForwardSendersName = true
        if (pendingHideCaption) params.hideCaption = true
        clearForwardFlags()
    }

    @JvmStatic
    fun cycleForwardModeOnBar(activity: ChatActivity): Boolean {
        val params = activity.messagePreviewParams ?: return false
        val messages = params.forwardMessages?.messages ?: return false
        if (messages.isEmpty()) return false

        val hideAuthor = params.hideForwardSendersName
        val hideCaption = params.hideCaption
        when {
            !hideAuthor && !hideCaption -> {
                params.hideForwardSendersName = true
                params.hideCaption = false
            }

            hideAuthor && !hideCaption -> {
                if (params.hasCaption) {
                    params.hideForwardSendersName = true
                    params.hideCaption = true
                } else {
                    params.hideForwardSendersName = false
                    params.hideCaption = false
                }
            }

            else -> {
                params.hideForwardSendersName = false
                params.hideCaption = false
            }
        }
        activity.showFieldPanelForForward(true, messages)
        return true
    }

    @JvmStatic
    fun forwardBarTitle(params: MessagePreviewParams?, fallback: CharSequence): CharSequence {
        if (params == null) return fallback
        val res = when {
            params.hideForwardSendersName && params.hideCaption -> R.string.InuHiddenSendersAndCaptionDescription
            params.hideCaption -> R.string.InuHiddenCaptionDescription
            params.hideForwardSendersName -> R.string.HiddenSendersNameDescription
            else -> return fallback
        }
        return LocaleController.getString(res)
    }

    /** @return true if the fork consumed the click (stock must not process the option) */
    @JvmStatic
    fun onMenuOptionClick(
        activity: ChatActivity,
        popupLayout: ActionBarPopupWindow.ActionBarPopupWindowLayout,
        cell: View,
        option: Int,
        message: MessageObject?,
        group: MessageObject.GroupedMessages?,
    ): Boolean {
        if (message == null) return false
        if (option == OPTION_PLUGIN_ACTIONS) {
            val menu = messageMenu ?: return true
            val rows = PluginActions.orderRows(
                PluginActions.KIND_MESSAGE,
                menu.rows.filter { PluginActions.isEnabled(it.key) },
                false,
            )
            if (rows.isEmpty()) return true
            return openLongTapSubmenu(activity, popupLayout, cell) { submenu ->
                for (row in rows) {
                    PluginIcons.addMenuItem(
                        submenu,
                        row.text,
                        row.icon,
                        row.owner,
                        R.drawable.msg_settings_old,
                    ) {
                        PluginActions.dispatch(row, menu.surface)
                        activity.closeMenu()
                    }
                }
            }
        }
        if (option != OPTION_REPEAT) return false
        if (InuConfig.REPEAT_MODE.value != InuConfig.RepeatModeItem.ASK) return false
        // with a single mode available there's nothing to ask about
        if (availableRepeatModes(activity, message, group).size < 2) return false
        return openLongTapSubmenu(activity, popupLayout, cell) { swb ->
            swb.add(R.drawable.msg_copy, LocaleController.getString(R.string.Copy)) {
                activity.processSelectedOption(OPTION_REPEAT_COPY)
            }
            swb.add(R.drawable.msg_forward, LocaleController.getString(R.string.Forward)) {
                activity.processSelectedOption(OPTION_REPEAT_FORWARD)
            }
        }
    }

    @JvmStatic
    fun onMenuOptionLongClick(
        activity: ChatActivity,
        popupLayout: ActionBarPopupWindow.ActionBarPopupWindowLayout,
        cell: View,
        options: List<Int>,
        index: Int,
        message: MessageObject?,
        group: MessageObject.GroupedMessages?,
    ): Boolean {
        if (message == null || index >= options.size) return false
        return when (options[index]) {
            ChatActivity.OPTION_FORWARD -> when (InuConfig.FORWARD_LONG_TAP_ACTION.value) {
                InuConfig.ForwardLongTapItem.OFF -> false
                InuConfig.ForwardLongTapItem.CHOOSE_MODE -> openLongTapSubmenu(activity, popupLayout, cell) { swb ->
                    swb.add(R.drawable.msg_forward, LocaleController.getString(R.string.Forward)) {
                        activity.processSelectedOption(ChatActivity.OPTION_FORWARD)
                    }
                    swb.add(lottieIcon(R.raw.name_hide), LocaleController.getString(R.string.InuForwardWithoutAuthor)) {
                        activity.processSelectedOption(ChatActivity.OPTION_FORWARD)
                        pendingHideAuthor = true
                    }
                    if (hasCaption(message, group)) {
                        swb.add(lottieIcon(R.raw.caption_hide), LocaleController.getString(R.string.InuForwardWithoutCaption)) {
                            activity.processSelectedOption(ChatActivity.OPTION_FORWARD)
                            pendingHideCaption = true
                        }
                    }
                }

                InuConfig.ForwardLongTapItem.WITHOUT_AUTHOR -> {
                    activity.processSelectedOption(ChatActivity.OPTION_FORWARD)
                    pendingHideAuthor = true
                    true
                }

                InuConfig.ForwardLongTapItem.WITHOUT_CAPTION -> {
                    activity.processSelectedOption(ChatActivity.OPTION_FORWARD)
                    if (hasCaption(message, group)) pendingHideCaption = true else pendingHideAuthor = true
                    true
                }

                else -> false
            }

            // tap repeats in the configured mode, long-tap in the other one (ask mode already offers both)
            OPTION_REPEAT -> {
                val preferred = InuConfig.REPEAT_MODE.value
                if (preferred == InuConfig.RepeatModeItem.ASK) return false
                val opposite = if (preferred == InuConfig.RepeatModeItem.COPY) {
                    InuConfig.RepeatModeItem.FORWARD
                } else {
                    InuConfig.RepeatModeItem.COPY
                }
                if (opposite !in availableRepeatModes(activity, message, group)) return false
                activity.processSelectedOption(
                    if (opposite == InuConfig.RepeatModeItem.COPY) OPTION_REPEAT_COPY else OPTION_REPEAT_FORWARD
                )
                true
            }

            OPTION_DETAILS -> {
                activity.processSelectedOption(OPTION_SHOW_JSON)
                true
            }

            ChatActivity.OPTION_REPLY -> {
                val noforwards = activity.isPeerNoForwards || (message.messageOwner != null && message.messageOwner.noforwards)
                if (noforwards) return false
                when (InuConfig.REPLY_LONG_TAP_ACTION.value) {
                    InuConfig.ReplyLongTapItem.OFF -> false
                    InuConfig.ReplyLongTapItem.CHOOSE_MODE -> openLongTapSubmenu(activity, popupLayout, cell) { swb ->
                        swb.add(R.drawable.menu_reply, LocaleController.getString(R.string.Reply)) {
                            activity.processSelectedOption(ChatActivity.OPTION_REPLY)
                        }
                        swb.add(R.drawable.menu_reply, LocaleController.getString(R.string.InuReplyIn)) {
                            activity.processSelectedOption(OPTION_REPLY_IN)
                        }
                        if (canReplyInDms(activity, message)) {
                            swb.add(R.drawable.msg_mention, LocaleController.getString(R.string.InuReplyInDms)) {
                                activity.processSelectedOption(OPTION_REPLY_IN_DMS)
                            }
                        }
                    }

                    InuConfig.ReplyLongTapItem.REPLY_IN -> {
                        activity.processSelectedOption(OPTION_REPLY_IN)
                        true
                    }

                    InuConfig.ReplyLongTapItem.REPLY_IN_DMS -> {
                        val target = if (canReplyInDms(activity, message)) OPTION_REPLY_IN_DMS else OPTION_REPLY_IN
                        activity.processSelectedOption(target)
                        true
                    }

                    else -> false
                }
            }

            else -> false
        }
    }


    private inline fun openLongTapSubmenu(
        activity: ChatActivity,
        popupLayout: ActionBarPopupWindow.ActionBarPopupWindowLayout,
        anchorCell: View,
        fill: (ItemOptions) -> Unit,
    ): Boolean {
        val swipeBack = popupLayout.swipeBack ?: return false
        val rp = activity.resourceProvider
        val swb = ItemOptions.swipeback(popupLayout, rp)
        val foregroundIndex = popupLayout.addViewToSwipeBack(swb.linearLayout)
        (swb.linearLayout.layoutParams as? FrameLayout.LayoutParams)?.gravity = Gravity.TOP
        swipeBack.inu_pinnedScrimForegroundIndex = foregroundIndex

        // anchorCell may be a narrow bottom-row button; size the submenu to the full menu width
        val menuWidthPx = popupLayout.measuredWidth - popupLayout.paddingLeft - popupLayout.paddingRight
        swb.setMinWidth((menuWidthPx / AndroidUtilities.density).roundToInt())
        swb.add(R.drawable.ic_ab_back, LocaleController.getString(R.string.Back)) { swipeBack.closeForeground() }
        swb.addGap()
        fill(swb)

        swipeBack.inu_setForegroundOffsetY(foregroundIndex, computeSubmenuOffsetY(swipeBack, anchorCell, swb.linearLayout))
        swipeBack.openForeground(foregroundIndex)
        return true
    }

    private fun computeSubmenuOffsetY(
        swipeBack: PopupSwipeBackLayout,
        anchorCell: View,
        submenu: LinearLayout,
    ): Int {
        var anchorY = 0f
        var v: View = anchorCell
        while (v !== swipeBack) {
            anchorY += v.y
            if (v is ScrollView) anchorY -= v.scrollY
            v = v.parent as? View ?: return 0
        }
        val spec = MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(1000f), MeasureSpec.AT_MOST)
        submenu.measure(spec, spec)
        val slack = (swipeBack.measuredHeight - submenu.measuredHeight).coerceAtLeast(0)
        var headerHeight = 0
        for (i in 0 until 2.coerceAtMost(submenu.childCount)) {
            headerHeight += submenu.getChildAt(i).measuredHeight
        }
        return (anchorY.toInt() - headerHeight).coerceIn(0, slack)
    }

    private fun hasCaption(selected: MessageObject, group: MessageObject.GroupedMessages?): Boolean =
        group?.messages?.any { !it.caption.isNullOrEmpty() } ?: !selected.caption.isNullOrEmpty()

    private fun canReplyInDms(activity: ChatActivity, selected: MessageObject): Boolean {
        val authorId = DialogObject.getPeerDialogId(selected.fromPeer)
        if (authorId <= 0) return false
        val selfId = UserConfig.getInstance(activity.currentAccount).clientUserId
        if (authorId == selfId) return false
        if (authorId == activity.dialogId) return false
        return activity.currentChat != null
    }

    private fun replyInDms(activity: ChatActivity, selected: MessageObject): Boolean {
        val authorId = DialogObject.getPeerDialogId(selected.fromPeer)
        if (authorId <= 0) return false
        val selfId = UserConfig.getInstance(activity.currentAccount).clientUserId
        if (authorId == selfId) return false

        val replyTarget = if (selected.groupId != 0L) {
            activity.getGroup(selected.groupId)?.captionMessage ?: selected
        } else selected

        val args = Bundle().apply { putLong("user_id", authorId) }
        val chat = ChatActivity(args)
        if (!activity.presentFragment(chat, false)) return false
        chat.replyingMessageObject = replyTarget
        chat.showFieldPanelForReply(replyTarget)
        return true
    }

    private fun lottieIcon(rawRes: Int): RLottieDrawable {
        val size = AndroidUtilities.dp(24f)
        return RLottieDrawable(rawRes, rawRes.toString(), size, size).apply {
            setCurrentFrame(0)
        }
    }

    @JvmStatic
    fun maybeHandleEditDoneLongTap(
        fragment: ChatActivity?,
        anchor: View,
        editTextCaption: EditTextCaption?,
        messageObject: MessageObject?,
        groupedMessages: MessageObject.GroupedMessages?,
    ): Boolean {
        if (fragment == null || messageObject == null) return false
        val text = editTextCaption?.text
        if (!messageObject.isMediaEmpty || groupedMessages != null) return false
        if (text.isNullOrEmpty()) return false
        val hasUrl = runCatching { AndroidUtilities.WEB_URL.matcher(text).find() }.getOrDefault(false)
        if (!hasUrl) return false

        ItemOptions.makeOptions(fragment, anchor, false, false, true)
            .forceTop(true)
            .add(R.drawable.msg_retry, LocaleController.getString(R.string.InuRefetchWebPreview)) {
                fragment.inu_refetchWebPreview()
            }
            .show()
        return true
    }

    @JvmStatic
    fun getDialogsTitle(account: Int): String {
        val user = UserConfig.getInstance(account).getCurrentUser()
        return when (InuConfig.DIALOGS_TITLE_TEXT.value) {
            InuConfig.DialogsTitleTextItem.USERNAME -> {
                val username = UserObject.getPublicUsername(user)
                if (username.isNullOrEmpty()) getFirstNameOrDefault(user) else "@$username"
            }

            InuConfig.DialogsTitleTextItem.FIRST_NAME -> getFirstNameOrDefault(user)
            InuConfig.DialogsTitleTextItem.CHATS -> LocaleController.getString(R.string.Chats)
            else -> LocaleController.getString(R.string.AppName)
        }
    }

    private fun getFirstNameOrDefault(user: TLRPC.User?): String {
        if (user == null) return LocaleController.getString(R.string.AppName)
        val name = UserObject.getFirstName(user)
        return if (name.isNullOrBlank()) LocaleController.getString(R.string.AppName) else name
    }
}
