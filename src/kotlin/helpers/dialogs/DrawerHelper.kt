package desu.inugram.helpers.dialogs

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.InuConfig
import desu.inugram.helpers.plugins.ui.ActionRow
import desu.inugram.helpers.dialogs.DrawerHelper.setupMainFragment
import desu.inugram.helpers.plugins.ui.PluginActions
import desu.inugram.helpers.update.UpdateHelper
import desu.inugram.ui.drawer.DrawerAddCell
import desu.inugram.ui.drawer.DrawerLayoutAdapter
import desu.inugram.ui.drawer.DrawerProfileCell
import desu.inugram.ui.drawer.DrawerProxyCell
import desu.inugram.ui.drawer.DrawerSwipeController
import desu.inugram.ui.drawer.DrawerUserCell
import desu.inugram.ui.drawer.SideMenultItemAnimator
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.DialogObject
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLoader
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_stars
import org.telegram.ui.AccountFrozenAlert
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.DrawerLayoutContainer
import org.telegram.ui.ActionBar.INavigationLayout
import org.telegram.ui.ActionBar.MenuDrawable
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.CallLogActivity
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.AnimatedEmojiDrawable
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.ContactsActivity
import org.telegram.ui.DialogsActivity
import org.telegram.ui.GroupCreateActivity
import org.telegram.ui.IUpdateLayout
import org.telegram.ui.LaunchActivity
import org.telegram.ui.LoginActivity
import org.telegram.ui.MainTabsActivity
import org.telegram.ui.ProfileActivity
import org.telegram.ui.ProxyListActivity
import org.telegram.ui.SelectAnimatedEmojiDialog
import org.telegram.ui.SettingsActivity
import org.telegram.ui.Stars.StarGiftSheet
import org.telegram.ui.Stars.StarsController
import org.telegram.ui.UpdateLayoutWrapper

@SuppressLint("StaticFieldLeak")
object DrawerHelper {

    private var adapter: DrawerLayoutAdapter? = null
    private var sideMenu: RecyclerListView? = null
    private var sideMenuContainer: FrameLayout? = null
    private var themeObserver: NotificationCenter.NotificationCenterDelegate? = null
    private var proxyObserver: NotificationCenter.NotificationCenterDelegate? = null
    private var updateLayout: IUpdateLayout? = null
    private var updateObserver: NotificationCenter.NotificationCenterDelegate? = null
    private var updateObserverAccount: Int = -1
    private var menuDrawableRef: MenuDrawable? = null
    private var statusPopup: SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow? = null

    @JvmStatic
    @JvmOverloads
    fun createMainFragment(args: Bundle? = null): BaseFragment {
        if (MainTabsHelper.isHidden) return DialogsActivity(args)
        val main = MainTabsActivity()
        if (args != null) main.prepareDialogsActivity(args)
        return main
    }

    /** Root fragment on startup: stock `addFragmentToStack` + navigation drawer wiring. */
    @JvmStatic
    fun setupMainFragment(activity: LaunchActivity, layout: INavigationLayout, dlc: DrawerLayoutContainer) {
        layout.addFragmentToStack(createMainFragment())
        if (InuConfig.NAVIGATION_DRAWER.value) setup(activity, dlc, layout)
    }

    /** Push the main fragment, forwarding a pending search query when tabs are present. */
    @JvmStatic
    fun addMainFragmentToStack(layout: INavigationLayout, searchQuery: String?) {
        val main = createMainFragment()
        val dialogs = if (main is MainTabsActivity) main.prepareDialogsActivity(null) else main as DialogsActivity
        if (searchQuery != null) dialogs.setInitialSearchString(searchQuery)
        layout.addFragmentToStack(main, INavigationLayout.FORCE_NOT_ATTACH_VIEW)
        ensureSetup(layout)
    }

    /**
     * Wire the side drawer onto the activity's container once (idempotent), or
     * refresh its contents if already wired. Needed for login/relogin flows that
     * present the main fragment outside [setupMainFragment] — without this the
     * post-login `DialogsActivity` has no drawer.
     */
    @JvmStatic
    fun ensureSetup(layout: INavigationLayout?) {
        if (!InuConfig.NAVIGATION_DRAWER.value || layout == null) return
        val dlc = layout.drawerLayoutContainer ?: return
        if (dlc.inu_drawer == null) {
            setup(dlc.context, dlc, layout)
        } else {
            notifyDataChanged()
            rebindPerAccountObservers()
            refreshUpdateState()
        }
    }

    fun refreshUpdateState() {
        if (!InuConfig.NAVIGATION_DRAWER.value) return
        updateLayout?.updateAppUpdateViews(UserConfig.selectedAccount, false)
        sideMenu?.let { applySideMenuBottomPadding(it) }
        refreshMenuButton(false)
    }

    @JvmStatic
    fun setup(
        context: Context,
        drawerLayoutContainer: DrawerLayoutContainer,
        actionBarLayout: INavigationLayout,
    ) {
        watchGlobalActions()
        val sm = object : RecyclerListView(context) {
            override fun findChildViewUnder(x: Float, y: Float): View? {
                for (i in 0 until childCount) {
                    val child = getChildAt(i)
                    if (child is DrawerProfileCell &&
                        x >= child.left && x <= child.right &&
                        y >= child.top && y <= child.bottom
                    ) {
                        return child
                    }
                }
                return super.findChildViewUnder(x, y)
            }
        }
        sm.layoutManager = LinearLayoutManager(context)
        val itemAnimator = SideMenultItemAnimator(sm)
        val newAdapter = DrawerLayoutAdapter(context, itemAnimator, drawerLayoutContainer, ::applyProxyEnabled)
        adapter = newAdapter
        sideMenu = sm
        sm.setItemAnimator(itemAnimator)
        sm.adapter = newAdapter
        sm.setVerticalScrollBarEnabled(false)
        sm.clipToPadding = false
        applySideMenuColors(sm)

        sm.setOnItemClickListener { view, position ->
            handleItemClick(position, view, drawerLayoutContainer, actionBarLayout, newAdapter)
        }

        attachAccountReorder(sm, newAdapter)

        val container = FrameLayout(context)
        sideMenuContainer = container
        container.setBackgroundColor(Theme.getColor(Theme.key_chats_menuBackground))
        container.addView(
            sm, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )

        val width = minOf(
            dp(320f),
            minOf(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y) - dp(56f)
        )

        val lp = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)
        val controller = DrawerSwipeController(drawerLayoutContainer)
        drawerLayoutContainer.inu_drawer = controller
        controller.setDrawerLayout(container, sm, lp)
        controller.setAllowOpenDrawer(true, false)

        installThemeObserver()
        installProxyObserver()
        installUpdateLayout(context as? Activity, container, sm)
    }

    private fun attachAccountReorder(sm: RecyclerListView, adapter: DrawerLayoutAdapter) {
        val callback = object : ItemTouchHelper.Callback() {
            override fun isLongPressDragEnabled() = true

            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                val dirs = if (vh.itemView is DrawerUserCell) ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0
                return makeMovementFlags(dirs, 0)
            }

            override fun onMove(rv: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                if (target.itemView !is DrawerUserCell) return false
                return adapter.swapAccounts(source.adapterPosition, target.adapterPosition)
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(vh, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    sm.cancelClickRunnables(false)
                    vh?.itemView?.isPressed = true
                }
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                vh.itemView.isPressed = false
                AccountOrderHelper.setVisibleOrder(adapter.accountNumbers)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(sm)
    }

    private fun installUpdateLayout(
        activity: Activity?,
        container: FrameLayout,
        sm: RecyclerListView,
    ) {
        if (activity == null) return
        // Stock UpdateLayoutWrapper: paints accent across the navbar inset, propagates
        // paddingBottom to the row so centered content stays in the visible 44dp.
        val wrapper = UpdateLayoutWrapper(activity)
        container.addView(
            wrapper,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        // UpdateLayoutWrapper.setPadding propagates to children — but only children that exist
        // at call time. The row is added later by UpdateLayout.createUpdateUI, so always
        // re-propagate on every inset dispatch instead of guarding by current value.
        wrapper.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, 0, 0, insets.systemWindowInsetBottom)
            v.requestLayout()
            insets
        }
        wrapper.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight)

        // Overwrites any prior UpdateLayout, releasing its Activity ref (Activity.recreate path).
        val ul = ApplicationLoader.applicationLoaderInstance
            ?.takeUpdateLayout(activity, wrapper) ?: return
        updateLayout = ul
        applySideMenuBottomPadding(sm)
        ul.updateAppUpdateViews(UserConfig.selectedAccount, false)

        // Observer lambda closes only over singleton state — registered once per process.
        if (updateObserver == null) {
            val obs = NotificationCenter.NotificationCenterDelegate { id, _, args ->
                val current = updateLayout ?: return@NotificationCenterDelegate
                when (id) {
                    NotificationCenter.appUpdateAvailable -> {
                        val animated = args.getOrNull(0) as? Boolean ?: true
                        current.updateAppUpdateViews(UserConfig.selectedAccount, animated)
                        sideMenu?.let { applySideMenuBottomPadding(it) }
                        refreshMenuButton(animated)
                    }

                    NotificationCenter.appUpdateLoading -> {
                        current.updateFileProgress(null)
                        current.updateAppUpdateViews(UserConfig.selectedAccount, true)
                        refreshMenuButton(true)
                    }

                    NotificationCenter.fileLoadProgressChanged -> {
                        current.updateFileProgress(args)
                        refreshMenuButton(true)
                    }

                    NotificationCenter.fileLoaded, NotificationCenter.fileLoadFailed -> {
                        val name = args.getOrNull(0) as? String ?: return@NotificationCenterDelegate
                        val doc = SharedConfig.pendingAppUpdate?.document ?: return@NotificationCenterDelegate
                        if (name == FileLoader.getAttachFileName(doc)) {
                            current.updateAppUpdateViews(UserConfig.selectedAccount, true)
                            refreshMenuButton(true)
                        }
                    }
                }
            }
            updateObserver = obs
            val global = NotificationCenter.getGlobalInstance()
            global.addObserver(obs, NotificationCenter.appUpdateAvailable)
            global.addObserver(obs, NotificationCenter.appUpdateLoading)
        }
        rebindPerAccountObservers()
        refreshMenuButton(false)
    }

    private fun rebindPerAccountObservers() {
        val obs = updateObserver ?: return
        val newAccount = UserConfig.selectedAccount
        if (updateObserverAccount == newAccount) return
        if (updateObserverAccount != -1) {
            val prev = NotificationCenter.getInstance(updateObserverAccount)
            prev.removeObserver(obs, NotificationCenter.fileLoadProgressChanged)
            prev.removeObserver(obs, NotificationCenter.fileLoaded)
            prev.removeObserver(obs, NotificationCenter.fileLoadFailed)
        }
        updateObserverAccount = newAccount
        val acct = NotificationCenter.getInstance(newAccount)
        acct.addObserver(obs, NotificationCenter.fileLoadProgressChanged)
        acct.addObserver(obs, NotificationCenter.fileLoaded)
        acct.addObserver(obs, NotificationCenter.fileLoadFailed)
    }

    /**
     * Updates the menu drawable used as a back-button in the drawer-mode DialogsActivity to reflect
     * the current pending-update state: exclamation when available, circular progress while
     * downloading. Mirrors stock Telegram 11.4.2's `updateMenuButton`.
     */
    @JvmStatic
    fun refreshMenuButton(drawable: MenuDrawable?, animated: Boolean) {
        // The patch seeds with a non-null drawable on DialogsActivity creation; we cache the
        // reference so notification observers can update the icon even when DialogsActivity
        // isn't the top fragment (e.g. user is in AboutActivity when the check completes).
        if (drawable != null) menuDrawableRef = drawable
        val d = drawable ?: menuDrawableRef ?: return
        val type: Int
        val downloadProgress: Float
        if (SharedConfig.isAppUpdateAvailable()) {
            val doc = SharedConfig.pendingAppUpdate.document
            val fileName = FileLoader.getAttachFileName(doc)
            if (UpdateHelper.isPendingStart || FileLoader.getInstance(UserConfig.selectedAccount).isLoadingFile(fileName)) {
                type = MenuDrawable.TYPE_UDPATE_DOWNLOADING
                downloadProgress = ImageLoader.getInstance().getFileProgress(fileName) ?: 0f
            } else {
                type = MenuDrawable.TYPE_UDPATE_AVAILABLE
                downloadProgress = 0f
            }
        } else {
            type = MenuDrawable.TYPE_DEFAULT
            downloadProgress = 0f
        }
        d.setType(type, animated)
        d.setUpdateDownloadProgress(downloadProgress, animated)
    }

    private fun refreshMenuButton(animated: Boolean) {
        refreshMenuButton(null, animated)
    }

    private fun applySideMenuBottomPadding(sm: RecyclerListView) {
        val extra = if (SharedConfig.isAppUpdateAvailable()) {
            dp(44f) + AndroidUtilities.navigationBarHeight
        } else 0
        sm.setPadding(sm.paddingLeft, sm.paddingTop, sm.paddingRight, extra)
    }

    private fun applySideMenuColors(sm: RecyclerListView) {
        val bg = Theme.getColor(Theme.key_chats_menuBackground)
        sm.setBackgroundColor(bg)
        sm.setGlowColor(bg)
        sm.setListSelectorColor(Theme.getColor(Theme.key_listSelector))
    }

    private fun installThemeObserver() {
        if (themeObserver != null) return
        val obs = NotificationCenter.NotificationCenterDelegate { id, _, _ ->
            if (id == NotificationCenter.didSetNewTheme || id == NotificationCenter.reloadInterface) {
                refreshTheme()
            }
        }
        themeObserver = obs
        NotificationCenter.getGlobalInstance().addObserver(obs, NotificationCenter.didSetNewTheme)
        NotificationCenter.getGlobalInstance().addObserver(obs, NotificationCenter.reloadInterface)
    }

    private fun installProxyObserver() {
        if (proxyObserver != null) return
        val obs = NotificationCenter.NotificationCenterDelegate { id, _, _ ->
            if (id == NotificationCenter.proxySettingsChanged) {
                adapter?.notifyDataSetChanged()
            }
        }
        proxyObserver = obs
        NotificationCenter.getGlobalInstance().addObserver(obs, NotificationCenter.proxySettingsChanged)
    }

    private fun applyProxyEnabled(enabled: Boolean) {
        val proxy = if (enabled) SharedConfig.currentProxy else null
        MessagesController.getGlobalMainSettings().edit()
            .putBoolean("proxy_enabled", enabled && proxy != null)
            .apply()
        if (proxy != null) {
            ConnectionsManager.setProxySettings(
                true, proxy.address, proxy.port,
                proxy.username, proxy.password, proxy.secret
            )
        } else {
            ConnectionsManager.setProxySettings(false, "", 0, "", "", "")
        }
        NotificationCenter.getGlobalInstance()
            .postNotificationName(NotificationCenter.proxySettingsChanged)
    }

    private fun refreshTheme() {
        sideMenuContainer?.setBackgroundColor(Theme.getColor(Theme.key_chats_menuBackground))
        sideMenu?.let { applySideMenuColors(it) }
        adapter?.notifyDataSetChanged()
        // Static sunDrawable persists across theme changes; notifyDataSetChanged
        // rebinds the cell but never re-syncs the day/night frame.
        adapter?.profileCell?.updateSunDrawable(Theme.isCurrentThemeDark())
    }

    fun dismissStatusPopup() {
        statusPopup?.dismiss()
        statusPopup = null
    }

    /**
     * Emoji status selector anchored to the drawer profile cell. Ported from
     * 11.14.1 stock LaunchActivity.showSelectStatusDialog, selection handling
     * mirrors 12.x DialogsActivity.showSelectStatusDialog (gift statuses).
     */
    fun showSelectStatusDialog(cell: DrawerProfileCell, drawerLayoutContainer: DrawerLayoutContainer) {
        if (statusPopup != null || SharedConfig.appLocked) return
        val fragment = drawerLayoutContainer.parentActionBarLayout?.lastFragment ?: return
        val account = UserConfig.selectedAccount
        val user = UserConfig.getInstance(account).getCurrentUser() ?: return
        val scrimDrawable = cell.status
        scrimDrawable.play()
        cell.getEmojiStatusLocation(AndroidUtilities.rectTmp2)
        val yoff = -(cell.height - AndroidUtilities.rectTmp2.centerY()) - dp(16f)
        var xoff = AndroidUtilities.rectTmp2.centerX()
        (cell.context as? Activity)?.window?.decorView?.rootWindowInsets?.let {
            xoff -= it.stableInsetLeft
        }
        val popupLayout = object : SelectAnimatedEmojiDialog(
            fragment,
            cell.context,
            true,
            xoff,
            SelectAnimatedEmojiDialog.TYPE_EMOJI_STATUS,
            null,
        ) {
            override fun onSettings() {
                drawerLayoutContainer.inu_drawer?.closeDrawer(false)
            }

            override fun willApplyEmoji(
                view: View?,
                documentId: Long?,
                document: TLRPC.Document?,
                gift: TL_stars.TL_starGiftUnique?,
                until: Int?,
            ): Boolean {
                if (gift != null) {
                    val savedStarGift = StarsController.getInstance(account).findUserStarGift(gift.id)
                    return savedStarGift == null || MessagesController.getGlobalMainSettings().getInt("statusgiftpage", 0) >= 2
                }
                return true
            }

            override fun onEmojiSelected(
                emojiView: View?,
                documentId: Long?,
                document: TLRPC.Document?,
                gift: TL_stars.TL_starGiftUnique?,
                until: Int?,
            ) {
                val emojiStatus: TLRPC.EmojiStatus
                if (documentId == null) {
                    emojiStatus = TLRPC.TL_emojiStatusEmpty()
                } else if (gift != null) {
                    val savedStarGift = StarsController.getInstance(account).findUserStarGift(gift.id)
                    if (savedStarGift != null && MessagesController.getGlobalMainSettings().getInt("statusgiftpage", 0) < 2) {
                        MessagesController.getGlobalMainSettings().edit()
                            .putInt("statusgiftpage", MessagesController.getGlobalMainSettings().getInt("statusgiftpage", 0) + 1)
                            .apply()
                        StarGiftSheet(cell.context, account, UserConfig.getInstance(account).getClientUserId(), null)
                            .set(savedStarGift, null)
                            .setupWearPage()
                            .show()
                        dismissStatusPopup()
                        return
                    }
                    val status = TLRPC.TL_inputEmojiStatusCollectible()
                    status.collectible_id = gift.id
                    if (until != null) {
                        status.flags = status.flags or 1
                        status.until = until
                    }
                    emojiStatus = status
                } else {
                    val status = TLRPC.TL_emojiStatus()
                    status.document_id = documentId
                    if (until != null) {
                        status.flags = status.flags or 1
                        status.until = until
                    }
                    emojiStatus = status
                }
                MessagesController.getInstance(account).updateEmojiStatus(emojiStatus, gift)
                dismissStatusPopup()
            }
        }
        if (DialogObject.getEmojiStatusUntil(user.emoji_status) > 0) {
            popupLayout.setExpireDateHint(DialogObject.getEmojiStatusUntil(user.emoji_status))
        }
        val giftId = (user.emoji_status as? TLRPC.TL_emojiStatusCollectible)?.collectible_id
        popupLayout.setSelected(giftId ?: (scrimDrawable.drawable as? AnimatedEmojiDrawable)?.documentId)
        popupLayout.setSaveState(2)
        popupLayout.setScrimDrawable(scrimDrawable, cell.getEmojiStatusDrawableParent())
        val popup = object : SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow(
            popupLayout,
            LayoutHelper.WRAP_CONTENT,
            LayoutHelper.WRAP_CONTENT,
        ) {
            override fun dismiss() {
                super.dismiss()
                statusPopup = null
            }
        }
        statusPopup = popup
        popup.showAsDropDown(cell, 0, yoff, Gravity.TOP)
        popup.dimBehind()
    }

    @JvmStatic
    fun handleItemClick(
        position: Int,
        view: View,
        drawerLayoutContainer: DrawerLayoutContainer,
        nav: INavigationLayout,
        adapter: DrawerLayoutAdapter,
    ) {
        val account = UserConfig.selectedAccount
        val close = { drawerLayoutContainer.inu_drawer?.closeDrawer(false) }

        if (position == 0) {
            return
        }

        // Account row tap: switch to that account.
        if (view is DrawerUserCell) {
            LaunchActivity.instance?.switchToAccount(view.accountNumber, true)
            close()
            return
        }

        // "Add account" row.
        if (view is DrawerAddCell) {
            val availableAccount = (UserConfig.MAX_ACCOUNT_COUNT - 1 downTo 0)
                .firstOrNull { !UserConfig.getInstance(it).isClientActivated }
            if (availableAccount != null) {
                nav.presentFragment(LoginActivity(availableAccount))
                close()
            }
            return
        }

        // Side-menu attach bot.
        adapter.getAttachMenuBot(position)?.let { bot ->
            val activity = LaunchActivity.instance ?: return
            LaunchActivity.showAttachMenuBot(activity, account, bot, null, true)
            close()
            return
        }

        val itemId = adapter.getId(position)
        if (itemId >= PluginActions.OPTION_BASE) {
            PluginActions.rowAt(globalActionRows, itemId)?.let {
                PluginActions.dispatch(it, PluginActions.Surface.global(account))
            }
            close()
            return
        }

        when (itemId) {
            ITEM_MY_PROFILE -> {
                openMyProfile(drawerLayoutContainer)
            }

            ITEM_NEW_GROUP -> {
                // mirrors the "New Group" row in ContactsActivity
                if (MessagesController.getInstance(account).isFrozen) {
                    AccountFrozenAlert.show(account)
                } else {
                    nav.presentFragment(GroupCreateActivity(Bundle()))
                    close()
                }
            }

            ITEM_NEW_MESSAGE -> {
                // swapped in for New Group when a compose draft is pending
                val top = nav.lastFragment
                val dialogs = if (top is MainTabsActivity) top.currentVisibleFragment else top
                (dialogs as? DialogsActivity)?.openWriteContacts()
                close()
            }

            ITEM_CONTACTS -> {
                val args = Bundle()
                args.putBoolean("needPhonebook", true)
                nav.presentFragment(ContactsActivity(args))
                close()
            }

            ITEM_CALLS -> {
                nav.presentFragment(CallLogActivity())
                close()
            }

            ITEM_SAVED_MESSAGES -> {
                // ChatActivity expects user_id, not dialog_id
                val args = Bundle()
                args.putLong("user_id", UserConfig.getInstance(account).getClientUserId())
                nav.presentFragment(ChatActivity(args))
                close()
            }

            ITEM_ARCHIVE -> {
                val args = Bundle()
                args.putInt("folderId", 1)
                nav.presentFragment(DialogsActivity(args))
                close()
            }

            ITEM_SETTINGS -> {
                nav.presentFragment(SettingsActivity())
                close()
            }

            ITEM_PROXY -> {
                nav.presentFragment(ProxyListActivity())
                close()
            }

            else -> close()
        }
    }

    fun openMyProfile(drawerLayoutContainer: DrawerLayoutContainer) {
        val args = Bundle()
        args.putLong("user_id", UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId())
        args.putBoolean("my_profile", true)
        drawerLayoutContainer.parentActionBarLayout.presentFragment(ProfileActivity(args))
        drawerLayoutContainer.inu_drawer?.closeDrawer(false)
    }

    // Stock DrawerLayoutAdapter item IDs — these are stable identifiers from the stock drawer.
    private const val ITEM_MY_PROFILE = 16
    private const val ITEM_NEW_GROUP = 2
    private const val ITEM_NEW_MESSAGE = 17
    private const val ITEM_CONTACTS = 6
    private const val ITEM_CALLS = 10
    private const val ITEM_SAVED_MESSAGES = 11
    private const val ITEM_SETTINGS = 8
    private const val ITEM_PROXY = DrawerLayoutAdapter.ITEM_PROXY
    private const val ITEM_ARCHIVE = DrawerLayoutAdapter.ITEM_ARCHIVE

    @JvmStatic
    fun notifyDataChanged() {
        adapter?.notifyDataSetChanged()
        refreshGlobalActionRows()
    }

    private var watchingActions = false

    private fun watchGlobalActions() {
        if (watchingActions) return
        watchingActions = true
        PluginActions.watchCounts { refreshGlobalActionRows() }
    }

    internal var globalActionRows: List<ActionRow> = emptyList()
        private set

    /**
     * a global action's row depends on nothing but the account, so the drawer renders on the same
     * signals it already rebuilds on rather than on being opened, and redraws only when the answer
     * differs from what is on screen. Re-entrancy is bounded by that: the redraw goes straight to
     * the adapter, and the render it does not schedule is what ends the cycle.
     *
     * Registering, unregistering or reloading is the other signal, and the only one that is not the
     * drawer's own: unlike every other menu this one is built once and outlives the gesture, so a
     * row a plugin adds while it is on screen would otherwise wait for an account switch.
     */
    private fun refreshGlobalActionRows() {
        if (globalActionRows.isEmpty() && !PluginActions.hasRows(PluginActions.KIND_GLOBAL)) return
        val surface = PluginActions.Surface.global(UserConfig.selectedAccount)
        PluginActions.render(PluginActions.KIND_GLOBAL, surface) { rows ->
            if (rows == globalActionRows) return@render
            globalActionRows = rows
            adapter?.notifyDataSetChanged()
        }
    }

    /** Old Layout back-button hook: toggles the side drawer. Returns false if unavailable. */
    @JvmStatic
    fun toggleDrawer(parentLayout: INavigationLayout?): Boolean {
        val controller = parentLayout?.drawerLayoutContainer?.inu_drawer ?: return false
        if (controller.isDrawerOpened) controller.closeDrawer(false) else controller.openDrawer(false)
        return true
    }

    @JvmStatic
    fun addDialogsActivityOptions(instance: DialogsActivity, io: ItemOptions) {
        val bottomTabsHidden = MainTabsHelper.isHidden

        if (bottomTabsHidden) {
            io.add(R.drawable.left_status_profile, getString(R.string.MyProfile)) {
                val args = Bundle()
                args.putLong("user_id", UserConfig.getInstance(instance.currentAccount).getClientUserId())
                args.putBoolean("my_profile", true)
                instance.presentFragment(ProfileActivity(args))
            }
        }

        if (bottomTabsHidden || MainTabsHelper.isContactsTabHidden) {
            io.add(R.drawable.msg_contacts, getString(R.string.Contacts)) {
                val args = Bundle()
                args.putBoolean("needPhonebook", true)
                instance.presentFragment(ContactsActivity(args))
            }
        }

        if (bottomTabsHidden) {
            io.add(R.drawable.msg_settings_old, getString(R.string.Settings)) {
                instance.presentFragment(SettingsActivity())
            }
        }
    }
}
