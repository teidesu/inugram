package desu.inugram.helpers.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.edit
import desu.inugram.InuConfig
import desu.inugram.helpers.cloud.SettingsBackupHelper
import desu.inugram.helpers.menu.MessageMenuConfig
import desu.inugram.helpers.menu.reorderByMenu
import desu.inugram.helpers.translate.TranslateHelper
import desu.inugram.ui.MessageDetailsActivity
import desu.inugram.ui.showInputDialog
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
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBarPopupWindow
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.BasePermissionsActivity
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.AnimatedEmojiSpan
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ChatActivityEnterView
import org.telegram.ui.Components.EditTextCaption
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.PopupSwipeBackLayout
import org.telegram.ui.Components.RLottieDrawable
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble
import org.telegram.ui.Components.ReactionsContainerLayout
import org.telegram.ui.Components.URLSpanUserMention
import org.telegram.ui.Components.UndoView
import org.telegram.ui.DialogsActivity
import org.telegram.ui.LaunchActivity
import java.io.File
import java.util.Calendar
import kotlin.math.roundToInt

object ChatHelper {
    private var skipNextReactionConfirm = false

    const val OPTION_SAVE = 501
    const val OPTION_DETAILS = 502
    const val OPTION_REPLY_IN = 503
    const val OPTION_SHOW_IN_CHAT = 505
    const val OPTION_TRANSLATE_REVERT = 508
    const val OPTION_FORWARD_NO_QUOTE = 509
    const val OPTION_REPLY_IN_DMS = 510
    const val OPTION_SUMMARIZE = 511
    const val OPTION_REMOVE_FROM_CACHE = 512

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

        val rows = options.indices.map { Row(items[it], options[it], icons[it]) }
        val ordered = reorderByMenu(rows, InuConfig.MESSAGE_MENU_ITEMS.value) {
            MessageMenuConfig.Item.forOption(it.option)
        }

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
        val chatMode = activity.chatMode
        if (chatMode == ChatActivity.MODE_PINNED) return InuConfig.HIDE_BOTTOM_BAR_PINNED.value

        val user = activity.currentUser
        if (user != null && UserObject.isReplyUser(user) && InuConfig.HIDE_BOTTOM_BAR_REPLIES.value) return true

        val chat = activity.currentChat ?: return false
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
        (swb.linearLayout.layoutParams as? FrameLayout.LayoutParams)?.gravity = Gravity.TOP
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
