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
import desu.inugram.helpers.dialogs.DrawerHelper.setupMainFragment
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
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLoader
import org.telegram.messenger.LocaleController.getString
import org.telegram.tgnet.ConnectionsManager
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.ui.AccountFrozenAlert
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.DrawerLayoutContainer
import org.telegram.ui.ActionBar.INavigationLayout
import org.telegram.ui.ActionBar.MenuDrawable
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.CallLogActivity
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.ItemOptions
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
import org.telegram.ui.SettingsActivity
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

    @JvmStatic
    @JvmOverloads
    fun createMainFragment(args: Bundle? = null): BaseFragment {
        if (InuConfig.NAVIGATION_DRAWER.value) return DialogsActivity(args)
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
            sideMenu?.let { applySideMenuBottomPadding(it) }
            updateLayout?.updateAppUpdateViews(UserConfig.selectedAccount, false)
            refreshMenuButton(false)
        }
    }

    @JvmStatic
    fun setup(
        context: Context,
        drawerLayoutContainer: DrawerLayoutContainer,
        actionBarLayout: INavigationLayout,
    ) {
        val sm = RecyclerListView(context)
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

        // Profile cell (position 0): toggle accounts list. The arrow is purely
        // a rotation indicator — clicks come in on the whole cell.
        if (position == 0) {
            if (view is DrawerProfileCell) {
                adapter.setAccountsShown(!adapter.isAccountsShown(), true)
            }
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

        when (adapter.getId(position)) {
            ITEM_MY_PROFILE -> {
                val args = Bundle()
                args.putLong("user_id", UserConfig.getInstance(account).getClientUserId())
                args.putBoolean("my_profile", true)
                nav.presentFragment(ProfileActivity(args))
                close()
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
                (nav.lastFragment as? DialogsActivity)?.openWriteContacts()
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

    // Stock DrawerLayoutAdapter item IDs — these are stable identifiers from the stock drawer.
    private const val ITEM_MY_PROFILE = 16
    private const val ITEM_NEW_GROUP = 2
    private const val ITEM_NEW_MESSAGE = 17
    private const val ITEM_CONTACTS = 6
    private const val ITEM_CALLS = 10
    private const val ITEM_SAVED_MESSAGES = 11
    private const val ITEM_SETTINGS = 8
    private const val ITEM_PROXY = DrawerLayoutAdapter.ITEM_PROXY

    @JvmStatic
    fun notifyDataChanged() {
        adapter?.notifyDataSetChanged()
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
