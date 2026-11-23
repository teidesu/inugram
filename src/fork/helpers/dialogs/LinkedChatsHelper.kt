package desu.inugram.helpers.dialogs

import android.content.SharedPreferences
import android.graphics.Canvas
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.InuConfig
import desu.inugram.helpers.security.PasscodeHelper
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.ImageReceiver
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.NotificationsController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBarMenuSubItem
import org.telegram.ui.ActionBar.ActionBarPopupWindow
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Adapters.DialogsSearchAdapter
import org.telegram.ui.Cells.DialogCell
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.Forum.ForumUtilities
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.DialogsActivity

object LinkedChatsHelper {
    class LinkedDialog(account: Int, val link: Link, val scope: Int, dialog: TLRPC.Dialog) : DialogEntriesHelper.Entry(account, "$account:${dialog.id}") {
        init {
            id = dialog.id
            last_message_date = dialog.last_message_date
            pinned = scope in link.pins
        }

        override fun isForum(): Boolean = MessagesController.getInstance(account).isForum(id)

        override fun bind(cell: DialogCell, dialogsType: Int, folderId: Int) {
            cell.inu_setEntry(this, true)
            cell.setDialog(this, dialogsType, folderId)
        }

        override fun onClick(fragment: DialogsActivity, adapter: RecyclerView.Adapter<*>, view: View) {
            val chatActivity = createChatActivity(fragment, this) ?: return
            if (adapter is DialogsSearchAdapter) {
                setSearchDate(adapter, this, (System.currentTimeMillis() / 1000).toInt())
            }
            fragment.presentFragment(chatActivity)
        }

        override fun onLongClick(fragment: DialogsActivity, adapter: RecyclerView.Adapter<*>, view: View) {
            if (adapter is DialogsSearchAdapter) {
                if (adapter.isSearchWas) return
                fragment.showDialog(
                    AlertDialog.Builder(fragment.parentActivity)
                        .setTitle(LocaleController.getString(R.string.ClearSearchSingleAlertTitle))
                        .setMessage(LocaleController.formatString(R.string.ClearSearchSingleChatAlertText, DialogObject.getName(getPeer(this))))
                        .setPositiveButton(LocaleController.getString(R.string.ClearSearchRemove)) { _, _ ->
                            setSearchDate(adapter, this, 0)
                        }
                        .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                        .create()
                )
                return
            }
            if (view is DialogCell) showChatPreview(fragment, view, this)
        }
    }

    data class Link(val hostUserId: Long, val sourceUserId: Long, val dialogId: Long) {
        var searchDate = 0
        val pins = HashMap<Int, Int>()
    }

    class LinksItem : InuConfig.Item<List<Link>>("linked_chats_list", emptyList(), false) {
        override val prefType = InuConfig.PrefType.STRING

        override fun read(prefs: SharedPreferences): List<Link> = prefs.getString(key, "")!!.split(',').mapNotNull {
            val parts = it.split(':')
            val ids = parts.take(3).mapNotNull(String::toLongOrNull)
            if (ids.size < 3) return@mapNotNull null
            Link(ids[0], ids[1], ids[2]).apply {
                searchDate = parts.getOrNull(3)?.toIntOrNull() ?: 0
                parts.getOrNull(4)?.split(';')?.forEach { pin ->
                    val (scope, date) = pin.split('=').mapNotNull(String::toIntOrNull).takeIf { it.size == 2 } ?: return@forEach
                    pins[scope] = date
                }
            }
        }

        override fun SharedPreferences.Editor.write() {
            putString(key, value.joinToString(",") { link ->
                val pins = link.pins.entries.joinToString(";") { "${it.key}=${it.value}" }
                "${link.hostUserId}:${link.sourceUserId}:${link.dialogId}:${link.searchDate}:$pins"
            })
        }
    }

    private var links: List<Link>
        get() = InuConfig.LINKED_CHATS_LIST.value
        set(value) {
            InuConfig.LINKED_CHATS_LIST.value = value
        }

    private val pendingRecents = HashSet<Link>()
    private val resumedFragments = HashSet<BaseFragment>()

    private fun findAccount(userId: Long): Int? = (0 until UserConfig.MAX_ACCOUNT_COUNT).firstOrNull {
        val config = UserConfig.getInstance(it)
        config.isClientActivated && config.clientUserId == userId && !PasscodeHelper.isAccountHidden(it)
    }

    private fun getOtherAccounts(account: Int): List<Int> {
        val accounts = (0 until UserConfig.MAX_ACCOUNT_COUNT).filterTo(ArrayList()) {
            it != account && UserConfig.getInstance(it).isClientActivated && !PasscodeHelper.isAccountHidden(it)
        }
        AccountOrderHelper.sort(accounts)
        return accounts
    }

    private fun getLinks(host: Int): List<Link> {
        val hostUserId = UserConfig.getInstance(host).clientUserId
        return links.filter { it.hostUserId == hostUserId }
    }

    private fun getLoadedDialog(link: Link): TLRPC.Dialog? =
        findAccount(link.sourceUserId)?.let { MessagesController.getInstance(it).dialogs_dict.get(link.dialogId) }

    private fun canLink(dialogId: Long): Boolean =
        !DialogObject.isEncryptedDialog(dialogId) && !DialogObject.isFolderDialogId(dialogId)

    @JvmStatic
    fun getDialogs(account: Int, dialogsType: Int, folderId: Int, onlySelect: Boolean): List<LinkedDialog> {
        if (!InuConfig.LINKED_CHATS.value || folderId != 0 || onlySelect) return emptyList()
        val controller = MessagesController.getInstance(account)
        if (!controller.dialogsLoaded) return emptyList()
        val filter = when (dialogsType) {
            DialogsActivity.DIALOGS_TYPE_DEFAULT -> null
            DialogsActivity.DIALOGS_TYPE_FOLDER1 -> controller.selectedDialogFilter[0] ?: return emptyList()
            DialogsActivity.DIALOGS_TYPE_FOLDER2 -> controller.selectedDialogFilter[1] ?: return emptyList()
            else -> return emptyList()
        }
        val scope = filter?.id ?: 0
        val result = ArrayList<LinkedDialog>()
        var pinsPruned = false
        for (link in getLinks(account)) {
            if (controller.dialogFiltersLoaded) {
                pinsPruned = link.pins.keys.removeAll { it != 0 && controller.dialogFiltersById.get(it) == null } || pinsPruned
            }
            val source = findAccount(link.sourceUserId) ?: continue
            val dialog = MessagesController.getInstance(source).dialogs_dict.get(link.dialogId)
            if (dialog == null) {
                DialogEntriesHelper.requestDialog(source, link.dialogId)
                continue
            }
            if (filter != null && !filter.includesDialog(AccountInstance.getInstance(source), link.dialogId, dialog)) continue
            result.add(LinkedDialog(source, link, scope, dialog))
        }
        if (pinsPruned) InuConfig.LINKED_CHATS_LIST.save()
        result.sortWith(compareByDescending<LinkedDialog> { it.link.pins[scope] ?: Int.MIN_VALUE }.thenByDescending { it.last_message_date })
        return result
    }

    @JvmStatic
    fun getPeer(dialog: LinkedDialog): TLObject? {
        val controller = MessagesController.getInstance(dialog.account)
        return if (DialogObject.isUserDialog(dialog.id)) controller.getUser(dialog.id) else controller.getChat(-dialog.id)
    }

    private fun setSearchDate(adapter: DialogsSearchAdapter, dialog: LinkedDialog, date: Int) {
        dialog.link.searchDate = date
        InuConfig.LINKED_CHATS_LIST.save()
        adapter.loadRecentSearch()
    }

    @JvmStatic
    fun addRecentSearch(account: Int, dialogsType: Int, recents: MutableList<DialogsSearchAdapter.RecentSearchObject>) {
        val dialogs = getDialogs(account, dialogsType, 0, false).filter { it.link.searchDate != 0 }
        pendingRecents += getLinks(account).filter { it.searchDate != 0 && getLoadedDialog(it) == null }
        for (dialog in dialogs) {
            val recent = DialogsSearchAdapter.RecentSearchObject()
            recent.`object` = dialog
            // 0 would match getSearchForumDialogId() outside of a forum search and get filtered out
            recent.did = Long.MIN_VALUE
            recent.date = dialog.link.searchDate
            val index = recents.indexOfFirst { it.date < recent.date }
            recents.add(if (index < 0) recents.size else index, recent)
        }
    }

    @JvmStatic
    fun clearRecentSearch(account: Int) {
        getLinks(account).forEach { it.searchDate = 0 }
        InuConfig.LINKED_CHATS_LIST.save()
    }

    @JvmStatic
    fun addSearchResults(account: Int, dialogsType: Int, query: String?, results: MutableList<Any?>, names: MutableList<CharSequence?>) {
        val q = query?.trim()?.lowercase()?.removePrefix("@")
        if (q.isNullOrEmpty()) return
        val translit = LocaleController.getInstance().getTranslitString(q)
        val matched = getDialogs(account, dialogsType, 0, false).filter { dialog ->
            val peer = getPeer(dialog) ?: return@filter false
            val name = DialogObject.getName(peer).orEmpty().lowercase()
            val username = DialogObject.getPublicUsername(peer)?.lowercase()
            username?.startsWith(q) == true || (name.split(' ') + name).any { it.startsWith(q) || it.startsWith(translit) }
        }
        results.addAll(0, matched)
        names.addAll(0, arrayOfNulls<CharSequence>(matched.size).asList())
    }

    @JvmStatic
    fun canAdd(account: Int, dialogIds: List<Long>): Boolean =
        InuConfig.LINKED_CHATS.value && dialogIds.any(::canLink) && getOtherAccounts(account).isNotEmpty()

    @JvmStatic
    fun addToPreviewMenu(
        fragment: DialogsActivity,
        menu: ActionBarPopupWindow.ActionBarPopupWindowLayout,
        dialogIds: List<Long>,
    ) {
        val swipeBack = menu.swipeBack ?: return
        val source = fragment.currentAccount
        val sourceUserId = UserConfig.getInstance(source).clientUserId
        val ids = dialogIds.filter(::canLink)
        val options = ItemOptions.swipeback(menu, fragment.resourceProvider)
        options.add(R.drawable.ic_ab_back, LocaleController.getString(R.string.Back)) { swipeBack.closeForeground() }
        options.addGap()
        for (host in getOtherAccounts(source)) {
            val hostUserId = UserConfig.getInstance(host).clientUserId
            val toggled = ids.map { Link(hostUserId, sourceUserId, it) }
            var linked = links.containsAll(toggled)
            lateinit var item: ActionBarMenuSubItem
            options.addAccount(host, false) {
                linked = !linked
                links = if (linked) links + (toggled - links.toSet()) else links - toggled.toSet()
                item.setChecked(linked)
            }
            item = options.last
            item.makeCheckView(2)
            item.textView.setPadding(dp(43f), 0, dp(43f), 0)
            item.textView.maxWidth = dp(43f + 180 + 43)
            item.setChecked(linked)
        }
        val foregroundIndex = menu.addViewToSwipeBack(options.linearLayout)
        (options.linearLayout.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.TOP
        swipeBack.inu_pinnedScrimForegroundIndex = foregroundIndex
        swipeBack.inu_pinnedForegroundAtBackgroundTop = true
        val item = ActionBarMenuSubItem(menu.context, false, false)
        item.setTextAndIcon(LocaleController.getString(R.string.InuLinkedChatsAdd), R.drawable.msg_openprofile)
        item.minimumWidth = 160
        item.setOnClickListener { swipeBack.openForeground(foregroundIndex) }
        menu.addView(item)
    }

    private fun createChatActivity(fragment: BaseFragment, dialog: LinkedDialog): ChatActivity? {
        val controller = MessagesController.getInstance(dialog.account)
        val args = Bundle()
        if (DialogObject.isUserDialog(dialog.id)) {
            args.putLong("user_id", dialog.id)
        } else {
            args.putLong("chat_id", -dialog.id)
        }
        if (!controller.checkCanOpenChat(args, fragment)) return null
        val chat = controller.getChat(-dialog.id)
        if (chat != null && chat.monoforum) {
            args.putInt("chatMode", ChatActivity.MODE_SUGGESTIONS)
            args.putBoolean("isSubscriberSuggestions", !ChatObject.canManageMonoForum(dialog.account, chat))
        }
        val chatActivity = ChatActivity(args)
        chatActivity.setCurrentAccount(dialog.account)
        if (chat != null && chat.forum && ChatObject.areTabsEnabled(chat)) {
            ForumUtilities.applyTopic(chatActivity, MessagesStorage.TopicKey.of(dialog.id, controller.getForumLastTopicId(chat.id)))
        }
        return chatActivity
    }

    private fun showChatPreview(fragment: DialogsActivity, cell: DialogCell, dialog: LinkedDialog) {
        val chatActivity = createChatActivity(fragment, dialog) ?: return
        val controller = MessagesController.getInstance(dialog.account)
        val interactive = InuConfig.INTERACTIVE_CHAT_PREVIEW.value
        val menu = ActionBarPopupWindow.ActionBarPopupWindowLayout(
            fragment.parentActivity, R.drawable.popup_fixed_alert4, fragment.resourceProvider,
            ActionBarPopupWindow.ActionBarPopupWindowLayout.FLAG_SHOWN_FROM_BOTTOM,
        )
        fun addItem(icon: Int, text: String, finish: Boolean = true, action: () -> Unit) = ActionBarMenuSubItem(fragment.parentActivity, false, false).apply {
            setTextAndIcon(text, icon)
            minimumWidth = 160
            setOnClickListener {
                action()
                if (finish) fragment.finishPreviewFragment()
            }
            menu.addView(this)
        }
        fun reloadDialogs() = fragment.didReceivedNotification(NotificationCenter.dialogsNeedReload, fragment.currentAccount)

        if (interactive) {
            addItem(R.drawable.msg_discussion, LocaleController.getString(R.string.SavedOpenChatNoCaps), finish = false) {
                chatActivity.parentLayout?.expandPreviewFragment()
            }
        }
        val hasUnread = cell.hasUnread
        addItem(
            if (hasUnread) R.drawable.msg_markread else R.drawable.msg_markunread,
            LocaleController.getString(if (hasUnread) R.string.MarkAsRead else R.string.MarkAsUnread),
        ) {
            if (!hasUnread) {
                controller.markDialogAsUnread(dialog.id, null, 0)
                return@addItem
            }
            val source = controller.dialogs_dict.get(dialog.id) ?: return@addItem
            if (controller.isForum(dialog.id) || controller.isMonoForumWithManageRights(dialog.id)) {
                controller.markAllTopicsAsRead(dialog.id)
            }
            controller.markMentionsAsRead(dialog.id, 0)
            controller.markDialogAsRead(dialog.id, source.top_message, source.top_message, source.last_message_date, false, 0, 0, true, 0)
        }
        addItem(
            if (dialog.pinned) R.drawable.msg_unpin else R.drawable.msg_pin,
            LocaleController.getString(if (dialog.pinned) R.string.UnpinFromTop else R.string.PinToTop),
        ) {
            if (dialog.pinned) {
                dialog.link.pins.remove(dialog.scope)
            } else {
                dialog.link.pins[dialog.scope] = (System.currentTimeMillis() / 1000).toInt()
            }
            InuConfig.LINKED_CHATS_LIST.save()
            reloadDialogs()
        }
        if (!DialogObject.isUserDialog(dialog.id) || !UserObject.isUserSelf(controller.getUser(dialog.id))) {
            val muted = controller.isDialogMuted(dialog.id, 0)
            addItem(
                if (muted) R.drawable.msg_unmute else R.drawable.msg_mute,
                LocaleController.getString(if (muted) R.string.Unmute else R.string.Mute),
            ) {
                NotificationsController.getInstance(dialog.account).setDialogNotificationsSettings(
                    dialog.id, 0,
                    if (muted) NotificationsController.SETTING_MUTE_UNMUTE else NotificationsController.SETTING_MUTE_FOREVER,
                )
                BulletinFactory.createMuteBulletin(fragment, !muted, null).show()
            }
        }
        addItem(R.drawable.msg_delete, LocaleController.getString(R.string.InuLinkedChatsRemove)) {
            links = links - dialog.link
            reloadDialogs()
        }.apply {
            setIconColor(fragment.getThemedColor(Theme.key_text_RedRegular))
            setTextColor(fragment.getThemedColor(Theme.key_text_RedBold))
            setSelectorColorKey(Theme.key_text_RedBold, .12f)
        }
        for (i in 0 until menu.itemsCount) {
            (menu.getItemAt(i) as ActionBarMenuSubItem).updateSelectorBackground(i == 0, i == menu.itemsCount - 1)
        }

        fragment.prepareBlurBitmap()
        fragment.parentLayout.setHighlightActionButtons(true)
        if (AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y) {
            fragment.presentFragmentAsPreview(chatActivity)
        } else {
            fragment.presentFragmentAsPreviewWithMenu(chatActivity, menu)
            chatActivity.allowExpandPreviewByClick = !interactive
        }
    }

    @JvmStatic
    fun inheritAccount(parent: BaseFragment?, fragment: BaseFragment?) {
        if (!InuConfig.LINKED_CHATS.value || parent == null || fragment == null || fragment.fragmentView != null) return
        val account = parent.currentAccount
        if (account != UserConfig.selectedAccount && fragment.currentAccount == UserConfig.selectedAccount) {
            fragment.setCurrentAccount(account)
        }
    }

    fun onFragmentResume(fragment: BaseFragment) {
        val account = fragment.currentAccount
        if (!InuConfig.LINKED_CHATS.value || account == UserConfig.selectedAccount || !resumedFragments.add(fragment)) return
        ConnectionsManager.getInstance(account).setAppPaused(false, false)
    }

    fun onFragmentPause(fragment: BaseFragment) {
        if (!resumedFragments.remove(fragment)) return
        ConnectionsManager.getInstance(fragment.currentAccount).setAppPaused(true, false)
    }

    class Observer(private val fragment: DialogsActivity) : NotificationCenter.NotificationCenterDelegate {
        private val accounts = getOtherAccounts(fragment.currentAccount)

        private fun hasLinks(source: Int): Boolean {
            val sourceUserId = UserConfig.getInstance(source).clientUserId
            return InuConfig.LINKED_CHATS.value && getLinks(fragment.currentAccount).any { it.sourceUserId == sourceUserId }
        }

        // a partial resume wakes the paused connection for about 10 seconds without marking the account online
        private val keepAwake = object : Runnable {
            override fun run() {
                if (!ApplicationLoader.mainInterfacePaused) {
                    accounts.filter(::hasLinks).forEach { ConnectionsManager.getInstance(it).resumeNetworkMaybe() }
                }
                AndroidUtilities.runOnUIThread(this, 8000)
            }
        }

        init {
            for (account in accounts) {
                val center = NotificationCenter.getInstance(account)
                center.addObserver(this, NotificationCenter.dialogsNeedReload)
                center.addObserver(this, NotificationCenter.updateInterfaces)
            }
            keepAwake.run()
        }

        fun detach() {
            AndroidUtilities.cancelRunOnUIThread(keepAwake)
            for (account in accounts) {
                val center = NotificationCenter.getInstance(account)
                center.removeObserver(this, NotificationCenter.dialogsNeedReload)
                center.removeObserver(this, NotificationCenter.updateInterfaces)
            }
        }

        override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
            if (!hasLinks(account)) return
            if (pendingRecents.removeAll { getLoadedDialog(it) != null }) {
                NotificationCenter.getInstance(fragment.currentAccount).postNotificationName(NotificationCenter.needReloadRecentDialogsSearch)
            }
            fragment.didReceivedNotification(id, fragment.currentAccount, *args)
        }
    }

    class Badge(cell: View) {
        private val image = ImageReceiver(cell).apply { setRoundRadius(dp(10f)) }
        private val avatar = AvatarDrawable()

        fun setAccount(account: Int) {
            val user = UserConfig.getInstance(account).currentUser
            avatar.setInfo(account, user)
            image.setCurrentAccount(account)
            image.setForUserOrChat(user, avatar)
        }

        fun attach() = image.onAttachedToWindow()

        fun detach() = image.onDetachedFromWindow()

        fun draw(canvas: Canvas, avatarImage: ImageReceiver, backgroundColor: Int) {
            val cx = avatarImage.imageX + dp(8f)
            val cy = avatarImage.imageY + dp(8f)
            canvas.drawCircle(cx, cy, dp(12f).toFloat(), Theme.fillingPaint(backgroundColor))
            image.setImageCoords(cx - dp(10f), cy - dp(10f), dp(20f).toFloat(), dp(20f).toFloat())
            image.draw(canvas)
        }
    }
}
