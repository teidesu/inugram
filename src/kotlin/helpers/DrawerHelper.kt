package desu.inugram.helpers

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.DrawerLayoutContainer
import org.telegram.ui.ActionBar.INavigationLayout
import org.telegram.ui.ActionBar.Theme
import desu.inugram.ui.drawer.DrawerLayoutAdapter
import org.telegram.ui.Components.RecyclerListView
import desu.inugram.ui.drawer.SideMenultItemAnimator
import org.telegram.ui.ContactsActivity
import org.telegram.ui.ProfileActivity
import org.telegram.ui.SettingsActivity

object DrawerHelper {

    private var adapter: DrawerLayoutAdapter? = null

    @JvmStatic
    fun setup(
        context: Context,
        drawerLayoutContainer: DrawerLayoutContainer,
        actionBarLayout: INavigationLayout,
    ) {
        val itemAnimator = SideMenultItemAnimator(null)
        val newAdapter = DrawerLayoutAdapter(context, itemAnimator, drawerLayoutContainer)
        adapter = newAdapter

        val sideMenu = RecyclerListView(context)
        sideMenu.layoutManager = LinearLayoutManager(context)
        sideMenu.setItemAnimator(itemAnimator)
        sideMenu.adapter = newAdapter
        sideMenu.setVerticalScrollBarEnabled(false)
        sideMenu.clipToPadding = false

        sideMenu.setOnItemClickListener { view, position ->
            val id = newAdapter.getId(position)
            handleItemClick(id, position, view, drawerLayoutContainer, actionBarLayout, newAdapter)
        }

        val sideMenuContainer = FrameLayout(context)
        sideMenuContainer.setBackgroundColor(Theme.getColor(Theme.key_chats_menuBackground))
        sideMenuContainer.addView(sideMenu, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        val width = minOf(
            dp(320f),
            android.util.DisplayMetrics().also {
                (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                    .defaultDisplay.getMetrics(it)
            }.let { minOf(it.widthPixels, it.heightPixels) } - dp(56f)
        )

        val lp = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)
        drawerLayoutContainer.inu_setDrawerLayout(sideMenuContainer, sideMenu, lp)
        drawerLayoutContainer.inu_setAllowOpenDrawer(true, false)
    }

    @JvmStatic
    fun handleItemClick(
        id: Int,
        position: Int,
        view: android.view.View,
        drawerLayoutContainer: DrawerLayoutContainer,
        nav: INavigationLayout,
        adapter: DrawerLayoutAdapter,
    ) {
        val account = UserConfig.selectedAccount

        // Account row taps
        val firstAccount = adapter.firstAccountPosition
        val lastAccount = adapter.lastAccountPosition
        if (firstAccount != androidx.recyclerview.widget.RecyclerView.NO_POSITION
            && position in firstAccount..lastAccount) {
            drawerLayoutContainer.inu_closeDrawer(false)
            return
        }

        when (id) {
            16 -> { // My Profile
                val args = Bundle()
                args.putLong("user_id", UserConfig.getInstance(account).getClientUserId())
                args.putBoolean("my_profile", true)
                nav.presentFragment(ProfileActivity(args))
                drawerLayoutContainer.inu_closeDrawer(false)
            }
            2 -> { // New Group
                val args = Bundle()
                args.putBoolean("onlyUsers", true)
                args.putBoolean("destroyAfterSelect", true)
                args.putBoolean("createGroupAfter", true)
                nav.presentFragment(ContactsActivity(args))
                drawerLayoutContainer.inu_closeDrawer(false)
            }
            6 -> { // Contacts
                val args = Bundle()
                args.putBoolean("needPhonebook", true)
                nav.presentFragment(ContactsActivity(args))
                drawerLayoutContainer.inu_closeDrawer(false)
            }
            10 -> { // Calls
                nav.presentFragment(CallLogActivity())
                drawerLayoutContainer.inu_closeDrawer(false)
            }
            11 -> { // Saved Messages
                val args = Bundle()
                args.putLong("dialog_id", UserConfig.getInstance(account).getClientUserId())
                nav.presentFragment(org.telegram.ui.ChatActivity(args))
                drawerLayoutContainer.inu_closeDrawer(false)
            }
            8 -> { // Settings
                nav.presentFragment(SettingsActivity())
                drawerLayoutContainer.inu_closeDrawer(false)
            }
            else -> {
                // bot items, emoji status, etc — handled by adapter click
                if (adapter.click(view, position)) {
                    drawerLayoutContainer.inu_closeDrawer(false)
                }
            }
        }
    }

    @JvmStatic
    fun notifyDataChanged() {
        adapter?.notifyDataSetChanged()
    }
}
