package desu.inugram.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import desu.inugram.InuConfig
import desu.inugram.ui.drawer.DrawerAddCell
import desu.inugram.ui.drawer.DrawerLayoutAdapter
import desu.inugram.ui.drawer.DrawerProfileCell
import desu.inugram.ui.drawer.DrawerSwipeController
import desu.inugram.ui.drawer.DrawerUserCell
import desu.inugram.ui.drawer.SideMenultItemAnimator
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.ui.AccountFrozenAlert
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.DrawerLayoutContainer
import org.telegram.ui.ActionBar.INavigationLayout
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.CallLogActivity
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.ContactsActivity
import org.telegram.ui.DialogsActivity
import org.telegram.ui.GroupCreateActivity
import org.telegram.ui.LaunchActivity
import org.telegram.ui.LoginActivity
import org.telegram.ui.MainTabsActivity
import org.telegram.ui.ProfileActivity
import org.telegram.ui.SettingsActivity

@SuppressLint("StaticFieldLeak")
object DrawerHelper {

    private var adapter: DrawerLayoutAdapter? = null
    private var sideMenu: RecyclerListView? = null
    private var sideMenuContainer: FrameLayout? = null
    private var themeObserverInstalled = false
    private var themeObserver: NotificationCenter.NotificationCenterDelegate? = null

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
        val newAdapter = DrawerLayoutAdapter(context, itemAnimator, drawerLayoutContainer)
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
    }

    private fun applySideMenuColors(sm: RecyclerListView) {
        val bg = Theme.getColor(Theme.key_chats_menuBackground)
        sm.setBackgroundColor(bg)
        sm.setGlowColor(bg)
        sm.setListSelectorColor(Theme.getColor(Theme.key_listSelector))
    }

    private fun installThemeObserver() {
        if (themeObserverInstalled) return
        val obs = NotificationCenter.NotificationCenterDelegate { id, _, _ ->
            if (id == NotificationCenter.didSetNewTheme || id == NotificationCenter.reloadInterface) {
                refreshTheme()
            }
        }
        themeObserver = obs
        NotificationCenter.getGlobalInstance().addObserver(obs, NotificationCenter.didSetNewTheme)
        NotificationCenter.getGlobalInstance().addObserver(obs, NotificationCenter.reloadInterface)
        themeObserverInstalled = true
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
        view: android.view.View,
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

        when (val id = adapter.getId(position)) {
            16 -> { // My Profile
                val args = Bundle()
                args.putLong("user_id", UserConfig.getInstance(account).getClientUserId())
                args.putBoolean("my_profile", true)
                nav.presentFragment(ProfileActivity(args))
                close()
            }

            2 -> { // New Group — mirrors the "New Group" row in ContactsActivity.
                if (MessagesController.getInstance(account).isFrozen) {
                    AccountFrozenAlert.show(account)
                } else {
                    nav.presentFragment(GroupCreateActivity(Bundle()))
                    close()
                }
            }

            17 -> { // New Message — swapped in for New Group when a compose draft is pending.
                (nav.lastFragment as? DialogsActivity)?.openWriteContacts()
                close()
            }

            6 -> { // Contacts
                val args = Bundle()
                args.putBoolean("needPhonebook", true)
                nav.presentFragment(ContactsActivity(args))
                close()
            }

            10 -> { // Calls
                nav.presentFragment(CallLogActivity())
                close()
            }

            11 -> { // Saved Messages: ChatActivity expects user_id, not dialog_id.
                val args = Bundle()
                args.putLong("user_id", UserConfig.getInstance(account).getClientUserId())
                nav.presentFragment(org.telegram.ui.ChatActivity(args))
                close()
            }

            8 -> { // Settings
                nav.presentFragment(SettingsActivity())
                close()
            }

            else -> close() // Unknown id — avoid getting stuck.
        }
    }

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
}
