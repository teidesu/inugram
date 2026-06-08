package desu.inugram.helpers.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.edit
import desu.inugram.InuConfig
import desu.inugram.helpers.menu.ChatMenuConfig
import desu.inugram.helpers.menu.reorderByMenu
import desu.inugram.ui.showInputDialog
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.BuildVars
import org.telegram.messenger.ChatObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBarMenu
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.BasePermissionsActivity
import org.telegram.ui.Cells.CheckBoxCell
import org.telegram.ui.ChannelAdminLogActivity
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.TranslateAlert2
import org.telegram.ui.RestrictedLanguagesSelectActivity
import org.telegram.ui.ActionBar.Theme
import desu.inugram.helpers.translate.TranslateHelper

/**
 * Owns the customizable chat-header overflow menu, the message-selection action bar
 * (extra buttons + overflow), and the per-dialog pinned-panel hide toggle.
 */
object ChatActionsHelper {
    // header overflow custom actions
    const val ACTION_OPEN_IN_DISCUSSION = 504
    const val ACTION_SHOW_PINNED_PANEL = 506
    const val ACTION_PINNED_UNPIN_ALL = 507
    const val ACTION_RECENT_ACTIONS = 513
    const val ACTION_GO_TO_BEGINNING = 514
    const val ACTION_GO_TO_MESSAGE = 515
    const val ACTION_DELETE_OWN_MESSAGES = 516

    // selection action mode
    const val ACTION_SELECT_RANGE = 1500
    const val ACTION_SELECTION_MENU = 1501
    const val ACTION_SEL_SAVE = 1502
    const val ACTION_SEL_TRANSLATE = 1503
    const val ACTION_SEL_GALLERY = 1504
    const val ACTION_SEL_PIN = 1505
    const val ACTION_SEL_UNPIN = 1506

    // --- chat header menu ---

    @JvmStatic
    fun reorder(lazyList: ArrayList<ActionBarMenuItem.Item>) {
        val entries = InuConfig.CHAT_MENU_ITEMS.value
        val ordered = reorderByMenu(lazyList, entries) { ChatMenuConfig.Item.forId(it.id) }
        lazyList.clear()
        lazyList.addAll(ordered)
    }

    @JvmStatic
    fun addItems(activity: ChatActivity, headerItem: ActionBarMenuItem) {
        if (activity.currentEncryptedChat == null) {
            headerItem.lazilyAddSubItem(
                ACTION_SHOW_PINNED_PANEL, R.drawable.msg_pin,
                LocaleController.getString(R.string.InuShowPinnedPanel),
            )
            headerItem.setSubItemShown(ACTION_SHOW_PINNED_PANEL, false)
        }
        if (canViewAdminLog(activity.currentChat)) {
            headerItem.lazilyAddSubItem(
                ACTION_RECENT_ACTIONS, R.drawable.msg_log,
                LocaleController.getString(R.string.EventLog),
            )
        }
        headerItem.lazilyAddSubItem(
            ACTION_GO_TO_BEGINNING, R.drawable.msg_go_up,
            LocaleController.getString(R.string.InuJumpToBeginning),
        )
        headerItem.lazilyAddSubItem(
            ACTION_GO_TO_MESSAGE, R.drawable.msg_message,
            LocaleController.getString(R.string.InuGoToMessage),
        )
        if (DeleteOwnMessagesHelper.isApplicable(activity.currentChat)) {
            headerItem.lazilyAddSubItem(
                ACTION_DELETE_OWN_MESSAGES, R.drawable.msg_delete,
                LocaleController.getString(R.string.InuDeleteOwnMessages),
            )
        }
    }

    @JvmStatic
    fun handleClick(id: Int, activity: ChatActivity): Boolean {
        when (id) {
            ACTION_SHOW_PINNED_PANEL -> showPinnedPanel(activity)
            ACTION_RECENT_ACTIONS -> {
                val chat = activity.currentChat
                if (chat != null) activity.presentFragment(ChannelAdminLogActivity(chat))
            }

            ACTION_GO_TO_BEGINNING -> ChatHelper.jumpToBeginning(activity)
            ACTION_GO_TO_MESSAGE -> showGoToMessageDialog(activity)
            ACTION_DELETE_OWN_MESSAGES -> DeleteOwnMessagesHelper.start(activity)
            ACTION_PINNED_UNPIN_ALL -> activity.bottomOverlayChatText?.callOnClick()
            ACTION_OPEN_IN_DISCUSSION -> openInDiscussionGroup(activity)

            ACTION_SELECT_RANGE -> fillSelectionGaps(activity)
            ACTION_SEL_SAVE -> saveSelectionToSavedMessages(activity)
            ACTION_SEL_TRANSLATE -> translateSelection(activity)
            ACTION_SEL_GALLERY -> saveSelectionToGallery(activity)
            ACTION_SEL_PIN -> pinSelection(activity)
            ACTION_SEL_UNPIN -> unpinSelection(activity)

            else -> return false
        }
        return true
    }

    private fun openInDiscussionGroup(activity: ChatActivity) {
        val chat = activity.currentChat ?: return
        val args = Bundle().apply {
            putLong("chat_id", chat.id)
            putInt("message_id", activity.threadId.toInt())
        }
        activity.presentFragment(ChatActivity(args))
    }

    private fun canViewAdminLog(chat: TLRPC.Chat?): Boolean {
        if (chat == null) return false
        if (!ChatObject.isChannel(chat) && !chat.gigagroup) return false
        return chat.creator || chat.admin_rights != null
    }

    private fun showGoToMessageDialog(activity: ChatActivity) {
        showInputDialog(
            fragment = activity,
            title = LocaleController.getString(R.string.InuGoToMessage),
            hint = LocaleController.getString(R.string.InuGoToMessagePrompt),
            inputType = InputType.TYPE_CLASS_NUMBER,
        ) { text ->
            val id = text.toIntOrNull() ?: return@showInputDialog false
            if (id <= 0) return@showInputDialog false
            activity.scrollToMessageId(id, 0, true, 0, true, 0)
            true
        }
    }

    // --- pinned panel ---
    // reuses stock's "pin_<dialogId>" key in notifications settings: panel hides
    // when the stored id matches the top pin, so a new pin reopens it automatically.

    private fun stockPinKey(dialogId: Long) = "pin_$dialogId"

    @JvmStatic
    fun onPinnedPanelLongPressed(activity: ChatActivity): Boolean {
        if (activity.pinnedMessageIds.isEmpty()) return false
        val prefs = MessagesController.getNotificationsSettings(activity.currentAccount)
        prefs.edit { putInt(stockPinKey(activity.dialogId), activity.pinnedMessageIds[0]) }
        activity.wasManualScroll = true
        activity.updatePinnedMessageView(true)
        BulletinFactory.createUnpinAllMessagesBulletin(
            activity, 0, true,
            { showPinnedPanel(activity) },
            null,
            activity.resourceProvider,
        )?.show()
        return true
    }

    @JvmStatic
    fun showPinnedPanel(activity: ChatActivity) {
        val prefs = MessagesController.getNotificationsSettings(activity.currentAccount)
        prefs.edit { remove(stockPinKey(activity.dialogId)) }
        activity.wasManualScroll = true
        activity.updatePinnedMessageView(true)
    }

    @JvmStatic
    fun updateShowPinnedMenuItem(activity: ChatActivity, hasPinnedMessages: Boolean) {
        val headerItem = activity.headerItem ?: return
        if (!hasPinnedMessages) {
            headerItem.setSubItemShown(ACTION_SHOW_PINNED_PANEL, false)
            return
        }
        val prefs = MessagesController.getNotificationsSettings(activity.currentAccount)
        val hidden = prefs.getInt(stockPinKey(activity.dialogId), 0) == activity.pinnedMessageIds[0]
        headerItem.setSubItemShown(ACTION_SHOW_PINNED_PANEL, hidden)
    }

    // --- selection action mode ---

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
        overflow.addSubItem(
            ACTION_SEL_PIN,
            R.drawable.msg_pin,
            LocaleController.getString(R.string.PinMessage),
        )
        overflow.addSubItem(
            ACTION_SEL_UNPIN,
            R.drawable.msg_unpin,
            LocaleController.getString(R.string.UnpinMessage),
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
        var canPin = false
        var canUnpin = false
        var allForwardable = true
        forEachSelectedMessage(activity) { msg ->
            any = true
            if (!msg.messageOwner?.message.isNullOrEmpty()) hasText = true
            if (msg.isPhoto || msg.isVideo) hasMedia = true
            if (canPinMessage(activity, msg)) {
                if (isPinnedMessage(activity, msg)) {
                    canUnpin = true
                } else {
                    canPin = true
                }
            }
            if (!msg.canForwardMessage()) allForwardable = false
        }
        val selfId = UserConfig.getInstance(activity.currentAccount).clientUserId
        val canSave = any && allForwardable && !activity.isPeerNoForwards && activity.dialogId != selfId
        val canTranslate = any && hasText && InuConfig.IN_PLACE_TRANSLATION.value
        overflow.setSubItemShown(ACTION_SEL_SAVE, canSave)
        overflow.setSubItemShown(ACTION_SEL_TRANSLATE, canTranslate)
        overflow.setSubItemShown(ACTION_SEL_GALLERY, hasMedia)
        overflow.setSubItemShown(ACTION_SEL_PIN, canPin)
        overflow.setSubItemShown(ACTION_SEL_UNPIN, canUnpin)
        actionMode.setItemVisibility(
            ACTION_SELECTION_MENU,
            if (canSave || canTranslate || hasMedia || canPin || canUnpin) View.VISIBLE else View.GONE,
        )
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

    private fun saveSelectionToSavedMessages(activity: ChatActivity) {
        ChatHelper.forwardToSavedMessages(activity, collectSelected(activity))
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

    private fun pinSelection(activity: ChatActivity) {
        val messages = ArrayList<MessageObject>()
        val seenIds = HashSet<Int>()
        forEachSelectedMessage(activity) { msg ->
            if (seenIds.add(msg.id) && canPinMessage(activity, msg) && !isPinnedMessage(activity, msg)) {
                messages.add(msg)
            }
        }
        if (messages.isEmpty()) return
        showPinMessagesAlert(activity, messages)
        activity.clearSelectionMode()
    }

    private fun unpinSelection(activity: ChatActivity) {
        val messages = ArrayList<MessageObject>()
        val seenIds = HashSet<Int>()
        forEachSelectedMessage(activity) { msg ->
            if (seenIds.add(msg.id) && canPinMessage(activity, msg) && isPinnedMessage(activity, msg)) {
                messages.add(msg)
            }
        }
        if (messages.isEmpty()) return
        unpinMessages(activity, messages)
        activity.clearSelectionMode()
    }

    private fun canPinMessage(activity: ChatActivity, msg: MessageObject): Boolean {
        if (activity.isInScheduleMode || activity.isThreadChat && !activity.isTopic) return false
        val chat = activity.currentChat
        val user = activity.currentUser
        val allowPin = when {
            chat != null -> msg.dialogId == activity.dialogId &&
                ChatObject.canPinMessages(chat) &&
                !chat.monoforum
            activity.currentEncryptedChat == null -> {
                val info = activity.getCurrentUserInfo()
                !UserObject.isDeleted(user) && info != null && info.can_pin_message
            }
            else -> false
        }
        if (UserObject.isReplyUser(activity.dialogId) || activity.dialogId == UserObject.VERIFY) return false
        return allowPin &&
            msg.id > 0 &&
            (msg.messageOwner.action == null || msg.messageOwner.action is TLRPC.TL_messageActionEmpty) &&
            !msg.isExpiredStory &&
            msg.type != MessageObject.TYPE_STORY_MENTION
    }

    private fun isPinnedMessage(activity: ChatActivity, msg: MessageObject): Boolean {
        return msg.dialogId == activity.dialogId && activity.pinnedMessageIds.contains(msg.id) && !msg.isExpiredStory
    }

    private fun showPinMessagesAlert(activity: ChatActivity, messages: ArrayList<MessageObject>) {
        val parent = activity.parentActivity ?: return
        val ids = ArrayList<Int>()
        for (msg in messages) {
            if (canPinMessage(activity, msg) && !isPinnedMessage(activity, msg) && !ids.contains(msg.id)) {
                ids.add(msg.id)
            }
        }
        if (ids.isEmpty()) return

        val builder = AlertDialog.Builder(parent, activity.themeDelegate)
        builder.setTitle(LocaleController.getString(R.string.PinMessageAlertTitle))
        builder.setMessage(LocaleController.formatString(R.string.InuPinMessagesAlert, ids.size))
        builder.setDimAlpha(.5f)

        val user = activity.currentUser
        val chat = activity.currentChat
        val showPeerCheckbox = user != null && !UserObject.isUserSelf(user)
        val showNotifyCheckbox = user == null && chat != null && (ChatObject.isChannel(chat) && chat.megagroup || !ChatObject.isChannel(chat))
        val checks = booleanArrayOf(showNotifyCheckbox, user == null)
        if (showPeerCheckbox || showNotifyCheckbox) {
            val checkIndex = if (showPeerCheckbox) 1 else 0
            val text = if (showPeerCheckbox) {
                LocaleController.formatString("PinAlsoFor", R.string.PinAlsoFor, UserObject.getFirstName(user!!))
            } else {
                LocaleController.getString(R.string.PinNotify)
            }
            val frameLayout = FrameLayout(parent)
            val cell = CheckBoxCell(parent, 1, activity.themeDelegate)
            cell.background = Theme.getSelectorDrawable(false)
            cell.setText(text, "", checks[checkIndex], false)
            cell.setPadding(AndroidUtilities.dp(if (LocaleController.isRTL) 16f else 8f), 0, AndroidUtilities.dp(if (LocaleController.isRTL) 8f else 16f), 0)
            frameLayout.addView(cell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP or Gravity.LEFT))
            cell.setOnClickListener {
                checks[checkIndex] = !checks[checkIndex]
                cell.setChecked(checks[checkIndex], true)
            }
            builder.setCustomViewOffset(if (showPeerCheckbox) 6 else 9)
            builder.setView(frameLayout)
        }

        builder.setPositiveButton(LocaleController.getString(R.string.PinMessage)) { _, _ ->
            val controller = MessagesController.getInstance(activity.currentAccount)
            for (id in ids) {
                controller.pinMessage(activity.currentChat, activity.currentUser, id, false, !checks[1], checks[0])
            }
            val bulletin = BulletinFactory.createPinMessageBulletin(activity, activity.themeDelegate)
            bulletin.show()
            bulletin.layout.postDelayed({
                try {
                    bulletin.layout.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                } catch (_: Exception) {
                }
            }, 550)
        }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null)
        activity.showDialog(builder.create())
    }

    private fun unpinMessages(activity: ChatActivity, messages: ArrayList<MessageObject>) {
        val controller = MessagesController.getInstance(activity.currentAccount)
        val seenIds = HashSet<Int>()
        for (msg in messages) {
            if (seenIds.add(msg.id) && canPinMessage(activity, msg) && isPinnedMessage(activity, msg)) {
                controller.pinMessage(activity.currentChat, activity.currentUser, msg.id, true, false, false)
            }
        }
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
        val candidates = ArrayList<MessageObject>()
        forEachGapMessage(activity, info) {
            candidates.add(it)
            true
        }
        if (candidates.isEmpty()) return
        for (i in candidates.indices) {
            activity.addToSelectedMessages(candidates[i], false, i == candidates.size - 1)
        }
        activity.updateActionModeTitle()
        activity.updateVisibleRows()
    }
}
