package desu.inugram.helpers

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.DrawerLayoutContainer
import org.telegram.ui.ActionBar.INavigationLayout
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.CallLogActivity
import org.telegram.ui.Cells.DividerCell
import org.telegram.ui.Cells.TextCell
import org.telegram.ui.ContactsActivity
import org.telegram.ui.ProfileActivity
import org.telegram.ui.SettingsActivity

/**
 * Owns the side hamburger drawer for OLD_LAYOUT mode.
 * Called from LaunchActivity (via old-layout-nav patch) and DialogsActivity.
 */
object DrawerHelper {

    private const val ID_MY_PROFILE = 1
    private const val ID_NEW_GROUP = 2
    private const val ID_CONTACTS = 3
    private const val ID_CALLS = 4
    private const val ID_SAVED = 5
    private const val ID_SETTINGS = 6

    @JvmStatic
    fun setup(
        context: Context,
        drawerLayoutContainer: DrawerLayoutContainer,
        actionBarLayout: INavigationLayout,
    ) {
        val adapter = DrawerAdapter(context)

        val sideMenu = org.telegram.ui.Components.RecyclerListView(context)
        sideMenu.layoutManager = LinearLayoutManager(context)
        sideMenu.adapter = adapter
        sideMenu.setVerticalScrollBarEnabled(false)
        sideMenu.clipToPadding = false
        sideMenu.setOnItemClickListener { _, position ->
            handleItemClick(adapter.getId(position), drawerLayoutContainer, actionBarLayout)
        }

        val sideMenuContainer = FrameLayout(context)
        sideMenuContainer.setBackgroundColor(Theme.getColor(Theme.key_chats_menuBackground))
        sideMenuContainer.addView(sideMenu, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        val width = minOf(dp(320f), android.util.DisplayMetrics().also {
            (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                .defaultDisplay.getMetrics(it)
        }.let { minOf(it.widthPixels, it.heightPixels) } - dp(56f))

        val lp = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)
        drawerLayoutContainer.inu_setDrawerLayout(sideMenuContainer, sideMenu, lp)
        drawerLayoutContainer.inu_setAllowOpenDrawer(true, false)
    }

    @JvmStatic
    fun handleItemClick(id: Int, drawerLayoutContainer: DrawerLayoutContainer, nav: INavigationLayout) {
        val account = UserConfig.selectedAccount
        when (id) {
            ID_MY_PROFILE -> {
                val args = Bundle()
                args.putLong("user_id", UserConfig.getInstance(account).getClientUserId())
                args.putBoolean("my_profile", true)
                nav.presentFragment(ProfileActivity(args))
            }
            ID_NEW_GROUP -> {
                val args = Bundle()
                args.putBoolean("onlyUsers", true)
                args.putBoolean("destroyAfterSelect", true)
                args.putBoolean("createGroupAfter", true)
                nav.presentFragment(ContactsActivity(args))
            }
            ID_CONTACTS -> {
                val args = Bundle()
                args.putBoolean("needPhonebook", true)
                nav.presentFragment(ContactsActivity(args))
            }
            ID_CALLS -> nav.presentFragment(CallLogActivity())
            ID_SAVED -> {
                val args = Bundle()
                args.putLong("dialog_id", UserConfig.getInstance(account).getClientUserId())
                nav.presentFragment(org.telegram.ui.ChatActivity(args))
            }
            ID_SETTINGS -> nav.presentFragment(SettingsActivity())
        }
        drawerLayoutContainer.inu_closeDrawer(false)
    }

    private data class Item(val id: Int, val text: String, val icon: Int)

    private fun buildItems(): List<Item?> {
        val account = UserConfig.selectedAccount
        if (!UserConfig.getInstance(account).isClientActivated) return emptyList()
        return listOf(
            Item(ID_MY_PROFILE, getString(R.string.MyProfile), R.drawable.outline_profile_settings),
            null, // divider
            Item(ID_NEW_GROUP, getString(R.string.NewGroup), R.drawable.outline_groups_24),
            Item(ID_CONTACTS, getString(R.string.Contacts), R.drawable.tabs_contacts_24),
            Item(ID_CALLS, getString(R.string.Calls), R.drawable.tabs_calls_24),
            Item(ID_SAVED, getString(R.string.SavedMessages), R.drawable.outline_saved_24),
            Item(ID_SETTINGS, getString(R.string.Settings), R.drawable.settings_account),
        )
    }

    private class DrawerAdapter(val context: Context) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items: List<Item?> = buildItems()

        fun getId(position: Int): Int = items.getOrNull(position)?.id ?: -1

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = if (items[position] == null) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view: View = if (viewType == 0) {
                TextCell(context)
            } else {
                DividerCell(context)
            }
            view.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position] ?: return
            val cell = holder.itemView as TextCell
            val isLast = position == items.size - 1 || items.getOrNull(position + 1) == null
            cell.setTextAndIcon(item.text, item.icon, !isLast)
        }
    }
}
