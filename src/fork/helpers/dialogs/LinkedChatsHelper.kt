package desu.inugram.helpers.dialogs

import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
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
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBarMenuSubItem
import org.telegram.ui.ActionBar.ActionBarPopupWindow
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Adapters.DialogsAdapter
import org.telegram.ui.Adapters.DialogsSearchAdapter
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.Forum.ForumUtilities
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.DialogsActivity

object LinkedChatsHelper {
    class LinkedDialog(@JvmField val account: Int, val link: Link, dialog: TLRPC.Dialog) : TLRPC.TL_dialog() {
        @JvmField
        val key = "$account:${dialog.id}"

        init {
            id = dialog.id
            last_message_date = dialog.last_message_date
        }
    }

    data class Link(val hostUserId: Long, val sourceUserId: Long, val dialogId: Long) {
        var searchDate = 0
    }

    class LinksItem : InuConfig.Item<List<Link>>("linked_chats_list", emptyList(), false) {
        override val prefType = InuConfig.PrefType.STRING

        override fun read(prefs: SharedPreferences): List<Link> = prefs.getString(key, "")!!.split(',').mapNotNull {
            val parts = it.split(':').mapNotNull(String::toLongOrNull)
            if (parts.size < 3) return@mapNotNull null
            Link(parts[0], parts[1], parts[2]).apply { searchDate = parts.getOrNull(3)?.toInt() ?: 0 }
        }

        override fun SharedPreferences.Editor.write() {
            putString(key, value.joinToString(",") { "${it.hostUserId}:${it.sourceUserId}:${it.dialogId}:${it.searchDate}" })
        }
    }

    private var links: List<Link>
        get() = InuConfig.LINKED_CHATS_LIST.value
        set(value) {
            InuConfig.LINKED_CHATS_LIST.value = value
        }

    private val requestedDialogs = HashSet<String>()
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
        val filter = when (dialogsType) {
            DialogsActivity.DIALOGS_TYPE_DEFAULT -> null
            DialogsActivity.DIALOGS_TYPE_FOLDER1 -> MessagesController.getInstance(account).selectedDialogFilter[0] ?: return emptyList()
            DialogsActivity.DIALOGS_TYPE_FOLDER2 -> MessagesController.getInstance(account).selectedDialogFilter[1] ?: return emptyList()
            else -> return emptyList()
        }
        val result = ArrayList<LinkedDialog>()
        for (link in getLinks(account)) {
            val source = findAccount(link.sourceUserId) ?: continue
            val dialog = MessagesController.getInstance(source).dialogs_dict.get(link.dialogId)
            if (dialog == null) {
                requestDialog(source, link.dialogId)
                continue
            }
            if (filter != null && !filter.includesDialog(AccountInstance.getInstance(source), link.dialogId, dialog)) continue
            result.add(LinkedDialog(source, link, dialog))
        }
        result.sortByDescending { it.last_message_date }
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

    private fun requestDialog(account: Int, dialogId: Long) {
        if (!requestedDialogs.add("$account:$dialogId")) return
        DialogsActivity.loadDialogs(AccountInstance.getInstance(account))
        MessagesController.getInstance(account).inu_loadDialog(dialogId) {
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload)
        }
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

    private fun getLinkedItem(adapter: RecyclerView.Adapter<*>, position: Int): LinkedDialog? = when (adapter) {
        is DialogsAdapter -> adapter.getItem(position)
        is DialogsSearchAdapter -> adapter.getItem(position)
        else -> null
    } as? LinkedDialog

    @JvmStatic
    fun onItemClick(fragment: DialogsActivity, adapter: RecyclerView.Adapter<*>, position: Int): Boolean {
        val dialog = getLinkedItem(adapter, position) ?: return false
        if (fragment.actionBar.isActionModeShowed) return true
        val controller = MessagesController.getInstance(dialog.account)
        val args = Bundle()
        if (DialogObject.isUserDialog(dialog.id)) {
            args.putLong("user_id", dialog.id)
        } else {
            args.putLong("chat_id", -dialog.id)
        }
        if (!controller.checkCanOpenChat(args, fragment)) return true
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
        if (adapter is DialogsSearchAdapter) {
            setSearchDate(adapter, dialog, (System.currentTimeMillis() / 1000).toInt())
        }
        fragment.presentFragment(chatActivity)
        return true
    }

    @JvmStatic
    fun onItemLongClick(fragment: DialogsActivity, view: View, adapter: RecyclerView.Adapter<*>, position: Int): Boolean {
        val dialog = getLinkedItem(adapter, position) ?: return false
        if (fragment.actionBar.isActionModeShowed) return true
        if (adapter is DialogsSearchAdapter) {
            if (adapter.isSearchWas) return true
            fragment.showDialog(
                AlertDialog.Builder(fragment.parentActivity)
                    .setTitle(LocaleController.getString(R.string.ClearSearchSingleAlertTitle))
                    .setMessage(LocaleController.formatString(R.string.ClearSearchSingleChatAlertText, DialogObject.getName(getPeer(dialog))))
                    .setPositiveButton(LocaleController.getString(R.string.ClearSearchRemove)) { _, _ ->
                        setSearchDate(adapter, dialog, 0)
                    }
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .create()
            )
            return true
        }
        ItemOptions.makeOptions(fragment, view)
            .setScrimViewBackground(ColorDrawable(fragment.getThemedColor(Theme.key_windowBackgroundWhite)))
            .add(R.drawable.msg_delete, LocaleController.getString(R.string.InuLinkedChatsRemove), true) {
                links = links - dialog.link
                fragment.didReceivedNotification(NotificationCenter.dialogsNeedReload, fragment.currentAccount)
            }
            .show()
        return true
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
