package desu.inugram.helpers

import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.View.MeasureSpec
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.edit
import desu.inugram.InuConfig
import desu.inugram.ui.MessageDetailsActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.FileLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagePreviewParams
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.LaunchActivity
import org.telegram.ui.ActionBar.ActionBarMenu
import org.telegram.ui.ActionBar.ActionBarPopupWindow
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ChatActivityEnterView
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.EditTextCaption
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RLottieDrawable
import org.telegram.ui.Components.URLSpanUserMention
import org.telegram.ui.Components.UndoView
import org.telegram.ui.DialogsActivity
import java.io.File
import java.util.Calendar
import kotlin.math.roundToInt

object ChatHelper {
    const val OPTION_SAVE = 501
    const val OPTION_DETAILS = 502
    const val OPTION_REPLY_IN = 503
    const val ACTION_OPEN_IN_DISCUSSION = 504
    const val OPTION_SHOW_IN_CHAT = 505
    const val ACTION_SHOW_PINNED_PANEL = 506
    const val ACTION_PINNED_UNPIN_ALL = 507
    const val ACTION_SELECT_RANGE = 1500
    const val OPTION_TRANSLATE_REVERT = 508

    private fun removeWallpaperKey(currentAccount: Int, dialogId: Long) = "remove_wallpaper:$currentAccount:$dialogId"
    private fun removeThemeKey(currentAccount: Int, dialogId: Long) = "remove_theme:$currentAccount:$dialogId"
    private fun hidePinnedPanelKey(currentAccount: Int, dialogId: Long) = "hide_pinned_panel:$currentAccount:$dialogId"

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
    fun isPinnedPanelHidden(currentAccount: Int, dialogId: Long): Boolean =
        InuConfig.prefs.getBoolean(hidePinnedPanelKey(currentAccount, dialogId), false)

    @JvmStatic
    fun onPinnedPanelLongPressed(activity: ChatActivity): Boolean {
        toggleDialogBool(hidePinnedPanelKey(activity.currentAccount, activity.dialogId))
        activity.wasManualScroll = true
        activity.updatePinnedMessageView(true)
        return true
    }

    @JvmStatic
    fun showPinnedPanel(activity: ChatActivity) {
        val key = hidePinnedPanelKey(activity.currentAccount, activity.dialogId)
        if (!InuConfig.prefs.getBoolean(key, false)) return
        InuConfig.prefs.edit { remove(key) }
        activity.wasManualScroll = true
        activity.updatePinnedMessageView(true)
    }

    @JvmStatic
    fun updateShowPinnedMenuItem(activity: ChatActivity, hasPinnedMessages: Boolean) {
        val headerItem = activity.headerItem ?: return
        val shouldShow = hasPinnedMessages && isPinnedPanelHidden(activity.currentAccount, activity.dialogId)
        headerItem.setSubItemShown(ACTION_SHOW_PINNED_PANEL, shouldShow)
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
        noforwards: Boolean
    ) {
        if (!noforwards && dialogId != UserConfig.getInstance(activity.currentAccount).clientUserId) {
            val forwardIdx = options.indexOf(ChatActivity.OPTION_FORWARD)
            val insertIdx = if (forwardIdx >= 0) forwardIdx + 1 else items.size
            items.add(insertIdx, LocaleController.getString(R.string.InuSaveToSavedMessages))
            options.add(insertIdx, OPTION_SAVE)
            icons.add(insertIdx, R.drawable.msg_saved)
        }

        if (!noforwards && activity.currentChat != null && !ChatObject.isChannelAndNotMegaGroup(activity.currentChat)) {
            val replyIdx = options.indexOf(ChatActivity.OPTION_REPLY)
            val insertIdx = if (replyIdx >= 0) replyIdx + 1 else items.size
            items.add(insertIdx, LocaleController.getString(R.string.InuReplyIn))
            options.add(insertIdx, OPTION_REPLY_IN)
            icons.add(insertIdx, R.drawable.menu_reply)
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

        items.add(LocaleController.getString(R.string.InuMessageDetails))
        options.add(OPTION_DETAILS)
        icons.add(R.drawable.msg_info)

        if (
            !options.contains(ChatActivity.OPTION_TRANSLATE) &&
            !TranslateHelper.isManuallyAffected(selectedObject, selectedObjectGroup) &&
            TranslateHelper.hasTranslatableWebPage(selectedObject)
        ) {
            val pinIdx = options.indexOf(ChatActivity.OPTION_PIN)
            val insertIdx = if (pinIdx >= 0) pinIdx + 1 else options.size
            items.add(insertIdx, LocaleController.getString(R.string.TranslateMessage))
            options.add(insertIdx, ChatActivity.OPTION_TRANSLATE)
            icons.add(insertIdx, R.drawable.msg_translate)
        }

        if (TranslateHelper.isManuallyAffected(selectedObject, selectedObjectGroup)) {
            val idx = options.indexOf(ChatActivity.OPTION_TRANSLATE)
            if (idx >= 0) {
                items.removeAt(idx)
                options.removeAt(idx)
                icons.removeAt(idx)
            }
            val insertIdx = if (idx >= 0) idx else {
                val pinIdx = options.indexOf(ChatActivity.OPTION_PIN)
                if (pinIdx >= 0) pinIdx + 1 else options.size
            }
            items.add(insertIdx, LocaleController.getString(R.string.ShowOriginalButton))
            options.add(insertIdx, OPTION_TRANSLATE_REVERT)
            icons.add(insertIdx, R.drawable.msg_translate)
        }
    }

    /** @return true if the option was handled */
    @JvmStatic
    fun processMenuOption(
        option: Int,
        activity: ChatActivity,
        selectedObject: MessageObject,
        selectedObjectGroup: MessageObject.GroupedMessages?
    ): Boolean {
        when (option) {
            OPTION_SAVE -> {
                val messages = ArrayList<MessageObject>()
                if (selectedObjectGroup != null) {
                    messages.addAll(selectedObjectGroup.messages)
                } else {
                    messages.add(selectedObject)
                }
                val selfId = UserConfig.getInstance(activity.currentAccount).clientUserId
                SendMessagesHelper.getInstance(activity.currentAccount)
                    .sendMessage(messages, selfId, false, false, true, 0, 0, null, -1, 0, 0, null)
                activity.createUndoView()
                activity.undoView.showWithAction(selfId, UndoView.ACTION_FWD_MESSAGES, messages.size)
            }

            OPTION_REPLY_IN -> {
                var replyMsg = selectedObject
                if (replyMsg.groupId != 0L) {
                    val group = activity.getGroup(replyMsg.groupId)
                    if (group != null) {
                        replyMsg = group.captionMessage
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

            OPTION_TRANSLATE_REVERT -> TranslateHelper.revert(activity, selectedObjectGroup?.captionMessage ?: selectedObject)

            OPTION_SHOW_IN_CHAT -> {
                val args = Bundle()
                val peerId = activity.dialogId
                if (peerId > 0) {
                    args.putLong("user_id", peerId)
                } else {
                    args.putLong("chat_id", -peerId)
                }
                args.putInt("message_id", selectedObject.id)
                args.putBoolean("need_remove_previous_same_chat_activity", false)
                activity.presentFragment(ChatActivity(args))
            }

            else -> return false
        }
        return true
    }

    @JvmStatic
    fun isEffectivelyInChat(chat: TLRPC.Chat?, chatInfo: TLRPC.ChatFull?): Boolean {
        if (chat == null) return false
        if (!ChatObject.isNotInChat(chat)) return true
        if (chat.join_to_send) return false
        return chat.megagroup && chatInfo != null && chatInfo.linked_chat_id != 0L
    }

    @JvmStatic
    fun openInDiscussionGroup(activity: ChatActivity) {
        val chat = activity.currentChat ?: return
        val threadId = activity.threadId
        val args = Bundle().apply {
            putLong("chat_id", chat.id)
            putInt("message_id", threadId.toInt())
        }
        activity.presentFragment(ChatActivity(args))
    }

    @JvmStatic
    @JvmOverloads
    fun shouldForceHideBottomBar(
        chat: TLRPC.Chat?,
        user: TLRPC.User? = null,
        chatMode: Int = ChatActivity.MODE_DEFAULT
    ): Boolean {
        if (chatMode == ChatActivity.MODE_PINNED) return InuConfig.HIDE_BOTTOM_BAR_PINNED.value

        if (user != null && UserObject.isReplyUser(user) && InuConfig.HIDE_BOTTOM_BAR_REPLIES.value) return true

        if (chat == null) return false
        if (!ChatObject.isChannelAndNotMegaGroup(chat)) return false
        if (ChatObject.canSendMessages(chat)) return false
        val member = ChatObject.isInChat(chat)

        if (member && InuConfig.HIDE_BOTTOM_BAR_JOINED.value) return true
        if (!member && InuConfig.HIDE_BOTTOM_BAR_NON_JOINED.value) return true

        return false
    }

    @JvmStatic
    fun maybeHandleFileClick(activity: ChatActivity, message: MessageObject): Boolean {
        val name = message.documentName ?: return false
        if (!name.endsWith(SettingsBackupHelper.FILENAME_SUFFIX)) return false
        val attach = message.messageOwner?.attachPath?.takeIf { it.isNotEmpty() }?.let { File(it) }
        val file = attach?.takeIf { it.exists() }
            ?: FileLoader.getInstance(activity.currentAccount).getPathToMessage(message.messageOwner)
                ?.takeIf { it.exists() }
            ?: return false
        SettingsBackupHelper.startImportFromFile(activity, file)
        return true
    }

    @JvmStatic
    fun maybeHandleInlineButtonLongTap(
        activity: ChatActivity,
        cell: ChatMessageCell,
        button: TLRPC.KeyboardButton,
    ): Boolean {
        if (button !is TLRPC.TL_keyboardButtonCallback) return false
        if (activity.parentActivity == null) return false

        val text = button.text ?: ""
        val data = button.data?.let { String(it, Charsets.UTF_8) } ?: ""
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

        return true;
    }

    @JvmStatic
    fun maybeHandleMentionLongTap(
        activity: ChatActivity,
        enterView: ChatActivityEnterView?,
        user: TLRPC.User,
        start: Int,
        len: Int,
    ): Boolean {
        val ctx = activity.parentActivity ?: return false
        if (enterView == null) return false
        if (enterView.editField == null) return false
        if (user.bot_inline_placeholder != null) return false
        val userId = user.id

        val editText = EditTextBoldCursor(ctx).apply {
            background = null
            setLineColors(
                Theme.getColor(Theme.key_dialogInputField),
                Theme.getColor(Theme.key_dialogInputFieldActivated),
                Theme.getColor(Theme.key_text_RedBold),
            )
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            maxLines = 1
            setLines(1)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.LEFT or Gravity.TOP
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            setCursorSize(AndroidUtilities.dp(20f))
            setCursorWidth(1.5f)
            setPadding(0, AndroidUtilities.dp(4f), 0, 0)
            val defaultName = UserObject.getUserName(user)
            setText(defaultName)
            setSelection(0, defaultName.length)
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                editText,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, Gravity.TOP or Gravity.LEFT, 24, 6, 24, 0),
            )
        }

        val onSubmit = onSubmit@{
            val text = editText.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                AndroidUtilities.shakeView(editText)
                return@onSubmit false
            }
            val spannable = SpannableString("$text ")
            spannable.setSpan(
                URLSpanUserMention("" + userId, 3),
                0, text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            enterView.replaceWithText(start, len, spannable, false)
            true
        }

        val dialog = AlertDialog.Builder(ctx, activity.themeDelegate)
            .setTitle(LocaleController.getString(R.string.InuMentionInsertTitle))
            .setView(container)
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ -> }
            .create()
        editText.setOnEditorActionListener { _, _, _ ->
            if (onSubmit()) dialog.dismiss()
            true
        }
        dialog.setOnShowListener {
            AndroidUtilities.runOnUIThread {
                editText.requestFocus()
                AndroidUtilities.showKeyboard(editText)
            }
        }
        activity.showDialog(dialog)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
            if (onSubmit()) dialog.dismiss()
        }
        return true
    }

    @JvmStatic
    fun addActionModeItems(activity: ChatActivity, actionMode: ActionBarMenu, anchorAfterId: Int) {
        val item = actionMode.addItemWithWidth(
            ACTION_SELECT_RANGE,
            R.drawable.msg_select_between_solar,
            AndroidUtilities.dp(54f),
            LocaleController.getString(R.string.InuSelectRange),
        )
        val anchor = actionMode.getItem(anchorAfterId)
        if (anchor != null) {
            val targetIndex = actionMode.indexOfChild(anchor) + 1
            actionMode.removeView(item)
            actionMode.addView(item, targetIndex)
        }
        activity.actionModeViews.add(item)
    }

    @JvmStatic
    fun shouldAnimateEditButton(activity: ChatActivity): Boolean {
        val item = activity.actionBar?.createActionMode()?.getItem(ACTION_SELECT_RANGE) ?: return true
        return item.visibility != View.VISIBLE
    }

    @JvmStatic
    fun updateActionModeVisibility(activity: ChatActivity) {
        val actionMode = activity.actionBar?.createActionMode() ?: return
        actionMode.setItemVisibility(
            ACTION_SELECT_RANGE,
            if (hasUnselectedGap(activity)) View.VISIBLE else View.GONE,
        )
    }

    @JvmStatic
    fun handleActionModeClick(id: Int, activity: ChatActivity): Boolean {
        if (id != ACTION_SELECT_RANGE) return false
        fillSelectionGaps(activity)
        return true
    }

    private data class GapInfo(val targetIndex: Int, val minId: Int, val maxId: Int)

    private fun gapInfo(activity: ChatActivity): GapInfo? {
        val a = activity.selectedMessagesIds[0].size()
        val b = activity.selectedMessagesIds[1].size()
        val targetIndex = when {
            a >= 2 && b == 0 -> 0
            b >= 2 && a == 0 -> 1
            else -> return null
        }
        val arr = activity.selectedMessagesIds[targetIndex]
        var minId = Int.MAX_VALUE
        var maxId = Int.MIN_VALUE
        for (i in 0 until arr.size()) {
            val id = arr.keyAt(i)
            if (id < minId) minId = id
            if (id > maxId) maxId = id
        }
        if (minId >= maxId) return null
        return GapInfo(targetIndex, minId, maxId)
    }

    private inline fun forEachGapMessage(
        activity: ChatActivity,
        info: GapInfo,
        action: (MessageObject) -> Boolean,
    ) {
        val arr = activity.selectedMessagesIds[info.targetIndex]
        val dialogId = activity.dialogId
        for (msg in activity.messages) {
            val msgIndex = if (msg.dialogId == dialogId) 0 else 1
            if (msgIndex != info.targetIndex) continue
            val id = msg.id
            if (id <= info.minId || id >= info.maxId) continue
            if (arr.indexOfKey(id) >= 0) continue
            if (!action(msg)) return
        }
    }

    private fun hasUnselectedGap(activity: ChatActivity): Boolean {
        if (activity.selectedMessagesIds[0].size() + activity.selectedMessagesIds[1].size() >= 100) return false
        val info = gapInfo(activity) ?: return false
        var found = false
        forEachGapMessage(activity, info) {
            found = true
            false
        }
        return found
    }

    private fun fillSelectionGaps(activity: ChatActivity) {
        val info = gapInfo(activity) ?: return
        val arr = activity.selectedMessagesIds[info.targetIndex]
        val candidates = ArrayList<MessageObject>()
        forEachGapMessage(activity, info) {
            candidates.add(it)
            true
        }
        if (candidates.isEmpty()) return

        val cap = 100 - arr.size()
        if (cap <= 0) {
            showSelectRangeCappedBulletin(activity)
            return
        }
        if (candidates.size <= cap) {
            for (i in candidates.indices) {
                activity.addToSelectedMessages(candidates[i], false, i == candidates.size - 1)
            }
            activity.updateActionModeTitle()
            activity.updateVisibleRows()
            return
        }

        val edgeIdToRemove = if (candidates[cap].id < candidates[cap - 1].id) info.minId else info.maxId
        val edgeMsg = arr.get(edgeIdToRemove) ?: run {
            showSelectRangeCappedBulletin(activity)
            return
        }
        activity.addToSelectedMessages(edgeMsg, false, false)
        for (i in 0..cap) {
            activity.addToSelectedMessages(candidates[i], false, i == cap)
        }
        activity.updateActionModeTitle()
        activity.updateVisibleRows()
        activity.scrollToMessageId(candidates[cap].id, 0, false, 0, true, 0)
        showSelectRangeCappedBulletin(activity)
    }

    private fun showSelectRangeCappedBulletin(activity: ChatActivity) {
        BulletinFactory.of(activity)
            .createSimpleBulletin(R.raw.error, LocaleController.formatString(R.string.InuSelectRangeLimit, 100))
            .show()
    }

    @JvmStatic
    fun maybeAppendForwardTime(forwardedString: String, messageObject: MessageObject): String {
        if (!InuConfig.SHOW_FORWARD_TIME.value) return forwardedString
        val fwd = messageObject.messageOwner.fwd_from ?: return forwardedString
        if (fwd.date == 0) return forwardedString

        val origMs = fwd.date * 1000L
        val msgMs = messageObject.messageOwner.date * 1000L
        val time = LocaleController.getInstance().formatterDay.format(origMs)
        val suffix = if (isSameDay(origMs, msgMs)) {
            time
        } else {
            "${LocaleController.getInstance().formatterYearMax.format(origMs)} $time"
        }
        return "$forwardedString • $suffix"
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
        if (activity == null) return false;
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
        if (activity.isThreadChat || activity.isTopic) {
            activity.scrollToMessageId(activity.threadMessageId.toInt(), 0, false, 0, true, 0)
            return
        }
        if (DialogObject.isEncryptedDialog(activity.dialogId)) return

        val account = activity.currentAccount
        val peer = MessagesController.getInstance(account).getInputPeer(activity.dialogId) ?: return
        val req = TLRPC.TL_messages_getHistory().apply {
            this.peer = peer
            offset_id = 1
            add_offset = -1
            limit = 1
        }
        ConnectionsManager.getInstance(account).sendRequest(req) { response, _ ->
            val res = response as? TLRPC.messages_Messages ?: return@sendRequest
            val first = res.messages.minByOrNull { it.id } ?: return@sendRequest
            AndroidUtilities.runOnUIThread {
                activity.scrollToMessageId(first.id, 0, false, 0, true, 0)
            }
        }
    }

    @JvmStatic
    fun onFragmentDestroy(activity: ChatActivity) {
        TranslateHelper.resetForDialog(activity.dialogId)
        TypingDraftHelper.forget(activity.currentAccount, activity.dialogId, activity.threadMessageId)
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
    fun onMenuOptionLongClick(
        activity: ChatActivity,
        popupLayout: ActionBarPopupWindow.ActionBarPopupWindowLayout,
        cell: View,
        options: List<Int>,
        index: Int,
        selectedObject: MessageObject?,
        selectedObjectGroup: MessageObject.GroupedMessages?,
    ): Boolean {
        if (selectedObject == null || index >= options.size) return false
        return when (options[index]) {
            ChatActivity.OPTION_FORWARD -> openForwardSubmenu(activity, popupLayout, cell, selectedObject, selectedObjectGroup)
            else -> false
        }
    }

    private fun openForwardSubmenu(
        activity: ChatActivity,
        popupLayout: ActionBarPopupWindow.ActionBarPopupWindowLayout,
        anchorCell: View,
        selected: MessageObject,
        group: MessageObject.GroupedMessages?,
    ): Boolean {
        val swipeBack = popupLayout.swipeBack ?: return false
        val rp = activity.resourceProvider
        val swb = ItemOptions.swipeback(popupLayout, rp)
        val foregroundIndex = popupLayout.addViewToSwipeBack(swb.linearLayout)
        swipeBack.inu_pinnedScrimForegroundIndex = foregroundIndex

        swb.setMinWidth((anchorCell.width / AndroidUtilities.density).roundToInt())
        swb.add(R.drawable.ic_ab_back, LocaleController.getString(R.string.Back)) { swipeBack.closeForeground() }
        swb.addGap()
        swb.add(R.drawable.msg_forward, LocaleController.getString(R.string.Forward)) {
            activity.processSelectedOption(ChatActivity.OPTION_FORWARD)
        }
        swb.add(lottieIcon(R.raw.name_hide), LocaleController.getString(R.string.InuForwardWithoutAuthor)) {
            activity.processSelectedOption(ChatActivity.OPTION_FORWARD)
            pendingHideAuthor = true
        }
        val hasCaption = group?.messages?.any { !it.caption.isNullOrEmpty() } ?: !selected.caption.isNullOrEmpty()
        if (hasCaption) {
            swb.add(lottieIcon(R.raw.caption_hide), LocaleController.getString(R.string.InuForwardWithoutCaption)) {
                activity.processSelectedOption(ChatActivity.OPTION_FORWARD)
                pendingHideCaption = true
            }
        }

        swipeBack.inu_setForegroundOffsetY(foregroundIndex, run {
            var anchorY = 0f
            var v: View = anchorCell
            while (v !== popupLayout) {
                anchorY += v.y
                if (v is ScrollView) anchorY -= v.scrollY
                v = v.parent as? View ?: return@run 0
            }
            val spec = MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(1000f), MeasureSpec.AT_MOST)
            swb.linearLayout.measure(spec, spec)
            val slack = (popupLayout.measuredHeight - swb.linearLayout.measuredHeight).coerceAtLeast(0)
            var headerHeight = 0
            for (i in 0 until 2.coerceAtMost(swb.linearLayout.childCount)) {
                headerHeight += swb.linearLayout.getChildAt(i).measuredHeight
            }
            val adjustedAnchorY = anchorY.toInt() - headerHeight

            if (popupLayout.shownFromBottom) {
                (adjustedAnchorY - slack).coerceIn(-slack, 0)
            } else {
                adjustedAnchorY.coerceIn(0, slack)
            }
        })
        swipeBack.openForeground(foregroundIndex)
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
}
