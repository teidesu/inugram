package desu.inugram.helpers

import android.Manifest
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.os.Build
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
import org.telegram.messenger.BuildVars
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
import org.telegram.messenger.TranslateController
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBarMenu
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.ActionBarPopupWindow
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.BasePermissionsActivity
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ChatActivityEnterView
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.EditTextCaption
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.PopupSwipeBackLayout
import org.telegram.ui.Components.RLottieDrawable
import org.telegram.ui.Components.TranslateAlert2
import org.telegram.ui.Components.URLSpanUserMention
import org.telegram.ui.Components.UndoView
import org.telegram.ui.DialogsActivity
import org.telegram.ui.LaunchActivity
import org.telegram.ui.RestrictedLanguagesSelectActivity
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
    const val ACTION_SELECTION_MENU = 1501
    const val ACTION_SEL_SAVE = 1502
    const val ACTION_SEL_TRANSLATE = 1503
    const val ACTION_SEL_GALLERY = 1504
    const val OPTION_TRANSLATE_REVERT = 508
    const val OPTION_FORWARD_NO_QUOTE = 509
    const val OPTION_REPLY_IN_DMS = 510
    const val OPTION_SUMMARIZE = 511
    const val OPTION_REMOVE_FROM_CACHE = 512

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
        if (!noforwards && activity.currentChat != null && !ChatObject.isChannelAndNotMegaGroup(activity.currentChat)) {
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

        if (!noforwards && dialogId != UserConfig.getInstance(activity.currentAccount).clientUserId) {
            items.add(LocaleController.getString(R.string.InuSaveToSavedMessages))
            options.add(OPTION_SAVE)
            icons.add(R.drawable.msg_saved)
        }

        if (options.contains(ChatActivity.OPTION_FORWARD)) {
            items.add(LocaleController.getString(R.string.InuForwardNoQuote))
            options.add(OPTION_FORWARD_NO_QUOTE)
            icons.add(R.drawable.msg_forward)
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

        items.add(LocaleController.getString(R.string.InuMessageDetails))
        options.add(OPTION_DETAILS)
        icons.add(R.drawable.msg_info)

        applyMessageMenuOrder(items, options, icons)
    }


    private fun applyMessageMenuOrder(
        items: ArrayList<CharSequence>,
        options: ArrayList<Int>,
        icons: ArrayList<Int>,
    ) {
        data class Row(val label: CharSequence, val option: Int, val icon: Int)

        val byItem = HashMap<MessageMenuConfig.Item, ArrayList<Row>>()
        // unknown rows attached to the closest preceding known Item (null = head)
        val unknownAfter = HashMap<MessageMenuConfig.Item?, ArrayList<Row>>()
        var lastKnown: MessageMenuConfig.Item? = null
        for (i in options.indices) {
            val row = Row(items[i], options[i], icons[i])
            val cfgItem = MessageMenuConfig.Item.forOption(options[i])
            if (cfgItem != null) {
                byItem.getOrPut(cfgItem) { ArrayList() }.add(row)
                lastKnown = cfgItem
            } else {
                unknownAfter.getOrPut(lastKnown) { ArrayList() }.add(row)
            }
        }

        val ordered = ArrayList<Row>(items.size)
        unknownAfter.remove(null)?.let { ordered.addAll(it) }
        for (entry in InuConfig.MESSAGE_MENU_ITEMS.value) {
            val rows = byItem.remove(entry.item)
            if (rows != null && entry.enabled) ordered.addAll(rows)
            unknownAfter.remove(entry.item)?.let { ordered.addAll(it) }
        }
        // items absent from saved order (e.g. enum extended after save) — append at end
        for ((item, rows) in byItem) {
            ordered.addAll(rows)
            unknownAfter.remove(item)?.let { ordered.addAll(it) }
        }
        // unknowns anchored to a disabled-and-missing item (shouldn't happen, but safe): append
        for ((_, rows) in unknownAfter) ordered.addAll(rows)

        items.clear(); options.clear(); icons.clear()
        for (r in ordered) {
            items.add(r.label); options.add(r.option); icons.add(r.icon)
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
                forwardToSavedMessages(activity, messages)
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

            OPTION_FORWARD_NO_QUOTE -> {
                activity.processSelectedOption(ChatActivity.OPTION_FORWARD)
                pendingHideAuthor = true
            }

            OPTION_REPLY_IN_DMS -> {
                if (!replyInDms(activity, selectedObject)) {
                    processMenuOption(OPTION_REPLY_IN, activity, selectedObject, selectedObjectGroup)
                }
            }

            OPTION_TRANSLATE_REVERT -> TranslateHelper.revert(activity, selectedObjectGroup?.captionMessage ?: selectedObject)

            OPTION_SUMMARIZE -> {
                val cell = activity.findMessageCell(selectedObject.id, false) as? ChatMessageCell ?: return true
                cell.delegate?.didPressSummarize(cell, false)
            }

            OPTION_REMOVE_FROM_CACHE -> {
                val parent = activity.parentActivity
                if (parent != null && Build.VERSION.SDK_INT >= 23 &&
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
    fun isEffectivelyInChat(chat: TLRPC.Chat?): Boolean {
        if (chat == null) return false
        if (!ChatObject.isNotInChat(chat)) return true
        if (!InuConfig.SEND_TO_DISCUSS_WITHOUT_JOIN.value) return false
        if (chat.join_to_send) return false
        return chat.megagroup && chat.has_link
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

        val overflow = actionMode.addItemWithWidth(
            ACTION_SELECTION_MENU,
            R.drawable.ic_ab_other,
            AndroidUtilities.dp(54f),
            LocaleController.getString(R.string.AccDescrMoreOptions),
        )
        overflow.addSubItem(
            ACTION_SEL_SAVE,
            R.drawable.msg_saved,
            LocaleController.getString(R.string.InuSaveToSavedMessages),
        )
        overflow.addSubItem(
            ACTION_SEL_TRANSLATE,
            R.drawable.msg_translate,
            LocaleController.getString(R.string.TranslateMessage),
        )
        overflow.addSubItem(
            ACTION_SEL_GALLERY,
            R.drawable.msg_download,
            LocaleController.getString(R.string.SaveToGallery),
        )
        activity.actionModeViews.add(overflow)
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

        val overflow = actionMode.getItem(ACTION_SELECTION_MENU) as? ActionBarMenuItem ?: return
        var any = false
        var hasText = false
        var hasMedia = false
        var allForwardable = true
        forEachSelectedMessage(activity) { msg ->
            any = true
            if (!msg.messageOwner?.message.isNullOrEmpty()) hasText = true
            if (msg.isPhoto || msg.isVideo) hasMedia = true
            if (!msg.canForwardMessage()) allForwardable = false
        }
        val selfId = UserConfig.getInstance(activity.currentAccount).clientUserId
        val canSave = any && allForwardable && !activity.isPeerNoForwards && activity.dialogId != selfId
        val canTranslate = any && hasText && InuConfig.IN_PLACE_TRANSLATION.value
        overflow.setSubItemShown(ACTION_SEL_SAVE, canSave)
        overflow.setSubItemShown(ACTION_SEL_TRANSLATE, canTranslate)
        overflow.setSubItemShown(ACTION_SEL_GALLERY, hasMedia)
        actionMode.setItemVisibility(
            ACTION_SELECTION_MENU,
            if (canSave || canTranslate || hasMedia) View.VISIBLE else View.GONE,
        )
    }

    @JvmStatic
    fun handleActionModeClick(id: Int, activity: ChatActivity): Boolean {
        when (id) {
            ACTION_SELECT_RANGE -> fillSelectionGaps(activity)
            ACTION_SEL_SAVE -> saveSelectionToSavedMessages(activity)
            ACTION_SEL_TRANSLATE -> translateSelection(activity)
            ACTION_SEL_GALLERY -> saveSelectionToGallery(activity)
            else -> return false
        }
        return true
    }

    private inline fun forEachSelectedMessage(activity: ChatActivity, action: (MessageObject) -> Unit) {
        // index 1 (merged dialog) first, then 0; SparseArray iteration is id-ascending within each
        for (a in 1 downTo 0) {
            val arr = activity.selectedMessagesIds[a]
            for (i in 0 until arr.size()) action(arr.valueAt(i))
        }
    }

    private fun collectSelected(activity: ChatActivity): ArrayList<MessageObject> {
        val out = ArrayList<MessageObject>()
        forEachSelectedMessage(activity) { out.add(it) }
        return out
    }

    private fun forwardToSavedMessages(activity: ChatActivity, messages: ArrayList<MessageObject>) {
        if (messages.isEmpty()) return
        val selfId = UserConfig.getInstance(activity.currentAccount).clientUserId
        SendMessagesHelper.getInstance(activity.currentAccount)
            .sendMessage(messages, selfId, false, false, true, 0, 0L)
        activity.createUndoView()
        activity.undoView.showWithAction(selfId, UndoView.ACTION_FWD_MESSAGES, messages.size)
    }

    private fun saveSelectionToSavedMessages(activity: ChatActivity) {
        forwardToSavedMessages(activity, collectSelected(activity))
        activity.clearSelectionMode()
    }

    private fun translateSelection(activity: ChatActivity) {
        if (!InuConfig.IN_PLACE_TRANSLATION.value) return
        val toLang = TranslateAlert2.getToLanguage()
        val toLangDefault = LocaleController.getInstance().currentLocale.language
        val restricted = RestrictedLanguagesSelectActivity.getRestrictedLanguages()
        val seenGroups = HashSet<Long>()
        var anyStarted = false
        for (msg in collectSelected(activity)) {
            val groupId = msg.groupId
            val group = if (groupId != 0L) {
                if (!seenGroups.add(groupId)) continue
                activity.getGroup(groupId)
            } else {
                null
            }
            val target = group?.captionMessage?.takeIf { !it.messageOwner?.message.isNullOrEmpty() } ?: msg
            val fromLang = target.messageOwner?.originalLanguage
            if (fromLang != null && restricted.contains(fromLang)) continue
            // mirror the message menu: a message already in the target language is translated to the app locale
            val toLangValue = if (fromLang == toLang) toLangDefault else toLang
            if (fromLang != null && fromLang == toLangValue) continue
            if (TranslateHelper.startTranslate(activity, msg, group, fromLang, toLangValue)) {
                anyStarted = true
            }
        }
        activity.clearSelectionMode()
        if (!anyStarted) {
            BulletinFactory.of(activity)
                .createErrorBulletin(LocaleController.getString(R.string.InuNothingToTranslate))
                .show()
        }
    }

    private fun saveSelectionToGallery(activity: ChatActivity) {
        val parent = activity.parentActivity ?: return
        if (Build.VERSION.SDK_INT >= 23 && (Build.VERSION.SDK_INT <= 28 || BuildVars.NO_SCOPED_STORAGE) &&
            parent.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            parent.requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                BasePermissionsActivity.REQUEST_CODE_EXTERNAL_STORAGE,
            )
            return
        }
        var photos = 0
        var videos = 0
        for (msg in collectSelected(activity)) {
            when {
                msg.isPhoto -> photos++
                msg.isVideo -> videos++
                else -> continue
            }
            activity.saveMessageToGallery(msg)
        }
        val count = photos + videos
        if (count > 0) {
            val type = when {
                videos == 0 -> BulletinFactory.FileType.PHOTOS
                photos == 0 -> BulletinFactory.FileType.VIDEOS
                else -> BulletinFactory.FileType.MEDIA
            }
            BulletinFactory.of(activity).createDownloadBulletin(type, count, activity.resourceProvider).show()
        }
        activity.clearSelectionMode()
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
            ChatActivity.OPTION_FORWARD -> handleForwardLongTap(activity, popupLayout, cell, selectedObject, selectedObjectGroup)
            ChatActivity.OPTION_REPLY -> handleReplyLongTap(activity, popupLayout, cell, selectedObject, selectedObjectGroup)
            else -> false
        }
    }

    private fun handleReplyLongTap(
        activity: ChatActivity,
        popupLayout: ActionBarPopupWindow.ActionBarPopupWindowLayout,
        cell: View,
        selected: MessageObject,
        group: MessageObject.GroupedMessages?,
    ): Boolean = when (InuConfig.REPLY_LONG_TAP_ACTION.value) {
        InuConfig.ReplyLongTapItem.OFF -> false
        InuConfig.ReplyLongTapItem.CHOOSE_MODE -> openReplySubmenu(activity, popupLayout, cell, selected)
        InuConfig.ReplyLongTapItem.REPLY_IN -> {
            activity.processSelectedOption(OPTION_REPLY_IN)
            true
        }

        InuConfig.ReplyLongTapItem.REPLY_IN_DMS -> {
            val target = if (canReplyInDms(activity, selected)) OPTION_REPLY_IN_DMS else OPTION_REPLY_IN
            activity.processSelectedOption(target)
            true
        }

        else -> false
    }

    private fun handleForwardLongTap(
        activity: ChatActivity,
        popupLayout: ActionBarPopupWindow.ActionBarPopupWindowLayout,
        cell: View,
        selected: MessageObject,
        group: MessageObject.GroupedMessages?,
    ): Boolean = when (InuConfig.FORWARD_LONG_TAP_ACTION.value) {
        InuConfig.ForwardLongTapItem.OFF -> false
        InuConfig.ForwardLongTapItem.CHOOSE_MODE -> openForwardSubmenu(activity, popupLayout, cell, selected, group)
        InuConfig.ForwardLongTapItem.WITHOUT_AUTHOR -> {
            activity.processSelectedOption(ChatActivity.OPTION_FORWARD)
            pendingHideAuthor = true
            true
        }

        InuConfig.ForwardLongTapItem.WITHOUT_CAPTION -> {
            activity.processSelectedOption(ChatActivity.OPTION_FORWARD)
            if (hasCaption(selected, group)) pendingHideCaption = true else pendingHideAuthor = true
            true
        }

        else -> false
    }

    private fun openForwardSubmenu(
        activity: ChatActivity,
        popupLayout: ActionBarPopupWindow.ActionBarPopupWindowLayout,
        anchorCell: View,
        selected: MessageObject,
        group: MessageObject.GroupedMessages?,
    ): Boolean = openLongTapSubmenu(activity, popupLayout, anchorCell) { swb ->
        swb.add(R.drawable.msg_forward, LocaleController.getString(R.string.Forward)) {
            activity.processSelectedOption(ChatActivity.OPTION_FORWARD)
        }
        swb.add(lottieIcon(R.raw.name_hide), LocaleController.getString(R.string.InuForwardWithoutAuthor)) {
            activity.processSelectedOption(ChatActivity.OPTION_FORWARD)
            pendingHideAuthor = true
        }
        if (hasCaption(selected, group)) {
            swb.add(lottieIcon(R.raw.caption_hide), LocaleController.getString(R.string.InuForwardWithoutCaption)) {
                activity.processSelectedOption(ChatActivity.OPTION_FORWARD)
                pendingHideCaption = true
            }
        }
    }

    private fun openReplySubmenu(
        activity: ChatActivity,
        popupLayout: ActionBarPopupWindow.ActionBarPopupWindowLayout,
        anchorCell: View,
        selected: MessageObject,
    ): Boolean = openLongTapSubmenu(activity, popupLayout, anchorCell) { swb ->
        swb.add(R.drawable.menu_reply, LocaleController.getString(R.string.Reply)) {
            activity.processSelectedOption(ChatActivity.OPTION_REPLY)
        }
        swb.add(R.drawable.menu_reply, LocaleController.getString(R.string.InuReplyIn)) {
            activity.processSelectedOption(OPTION_REPLY_IN)
        }
        if (canReplyInDms(activity, selected)) {
            swb.add(R.drawable.msg_mention, LocaleController.getString(R.string.InuReplyInDms)) {
                activity.processSelectedOption(OPTION_REPLY_IN_DMS)
            }
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
        (swb.linearLayout.layoutParams as? android.widget.FrameLayout.LayoutParams)?.gravity = Gravity.TOP
        swipeBack.inu_pinnedScrimForegroundIndex = foregroundIndex

        swb.setMinWidth((anchorCell.width / AndroidUtilities.density).roundToInt())
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
}
