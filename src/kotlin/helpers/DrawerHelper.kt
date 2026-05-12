package desu.inugram.helpers

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.DrawerLayoutContainer
import org.telegram.ui.ActionBar.INavigationLayout
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.CallLogActivity
import org.telegram.ui.Cells.DividerCell
import org.telegram.ui.Cells.DrawerActionCell
import org.telegram.ui.Cells.DrawerAddCell
import org.telegram.ui.Cells.DrawerProfileCell
import org.telegram.ui.Cells.DrawerUserCell
import org.telegram.ui.Cells.EmptyCell
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.SideMenultItemAnimator
import org.telegram.ui.ContactsActivity
import org.telegram.ui.ProfileActivity
import org.telegram.ui.SettingsActivity
import java.util.Collections

/**
 * Owns the side hamburger drawer for OLD_LAYOUT mode.
 * Called from LaunchActivity and DialogsActivity.
 */
object DrawerHelper {

    // -----------------------------------------------------------------------
    // Adapter item constants (mirror DrawerLayoutAdapter IDs from 11.14)
    // -----------------------------------------------------------------------
    private const val ID_MY_PROFILE = 16
    private const val ID_NEW_GROUP = 2
    private const val ID_CONTACTS = 6
    private const val ID_CALLS = 10
    private const val ID_SAVED = 11
    private const val ID_SETTINGS = 8
    private const val ID_INVITE = 7
    private const val ID_HELP = 13

    // -----------------------------------------------------------------------
    // State kept per-app-launch (single drawer instance per process)
    // -----------------------------------------------------------------------
    private var adapter: DrawerAdapter? = null

    // -----------------------------------------------------------------------
    // Entry points called from LaunchActivity patch
    // -----------------------------------------------------------------------

    /**
     * Call from LaunchActivity.onCreate() after setting up actionBarLayout,
     * when OLD_LAYOUT is enabled.
     */
    @JvmStatic
    fun setup(
        context: Context,
        drawerLayoutContainer: DrawerLayoutContainer,
        actionBarLayout: INavigationLayout,
    ) {
        val itemAnimator = SideMenultItemAnimator(false)
        val sideMenuContainer = FrameLayout(context)

        val sideMenu = RecyclerListView(context)
        sideMenu.layoutManager = LinearLayoutManager(context)
        val newAdapter = DrawerAdapter(context, itemAnimator, drawerLayoutContainer)
        adapter = newAdapter
        sideMenu.adapter = newAdapter
        sideMenu.setVerticalScrollBarEnabled(false)
        sideMenu.clipToPadding = false

        sideMenuContainer.addView(sideMenu, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        // Width = min(320dp, screenMin - 56dp) — classic drawer peek rule
        val lp = FrameLayout.LayoutParams(
            minOf(dp(320f), AndroidUtilities.getRealScreenSize().let {
                minOf(it.x, it.y)
            } - dp(56f)),
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

        drawerLayoutContainer.inu_setDrawerLayout(sideMenuContainer, sideMenu, lp)
        drawerLayoutContainer.inu_setAllowOpenDrawer(true, false)

        sideMenu.setOnItemClickListener { view, position ->
            val id = newAdapter.getId(position)
            handleItemClick(id, drawerLayoutContainer, actionBarLayout)
        }
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
                drawerLayoutContainer.inu_closeDrawer(false)
            }
            ID_NEW_GROUP -> {
                // Route to new group creation — use ContactsActivity in select mode
                val args = Bundle()
                args.putBoolean("onlyUsers", true)
                args.putBoolean("destroyAfterSelect", true)
                args.putBoolean("createGroupAfter", true)
                nav.presentFragment(ContactsActivity(args))
                drawerLayoutContainer.inu_closeDrawer(false)
            }
            ID_CONTACTS -> {
                val args = Bundle()
                args.putBoolean("needPhonebook", true)
                nav.presentFragment(ContactsActivity(args))
                drawerLayoutContainer.inu_closeDrawer(false)
            }
            ID_CALLS -> {
                nav.presentFragment(CallLogActivity())
                drawerLayoutContainer.inu_closeDrawer(false)
            }
            ID_SAVED -> {
                val args = Bundle()
                args.putLong("dialog_id", UserConfig.getInstance(account).getClientUserId())
                nav.presentFragment(
                    org.telegram.ui.ChatActivity(args)
                )
                drawerLayoutContainer.inu_closeDrawer(false)
            }
            ID_SETTINGS -> {
                nav.presentFragment(SettingsActivity())
                drawerLayoutContainer.inu_closeDrawer(false)
            }
            ID_INVITE -> {
                // Share invite link
                try {
                    val link = MessagesController.getInstance(account).linkPrefix + "/joinchat/" +
                        "not_available" // stock does this via a different path; skip for now
                } catch (_: Exception) {}
                drawerLayoutContainer.inu_closeDrawer(false)
            }
            ID_HELP -> {
                val args = Bundle()
                args.putLong("dialog_id", 777000L) // Telegram Support bot
                nav.presentFragment(org.telegram.ui.ChatActivity(args))
                drawerLayoutContainer.inu_closeDrawer(false)
            }
        }
    }

    /** Returns true if the drawer is currently open. Used by DialogsActivity back handling. */
    @JvmStatic
    fun isDrawerOpen(drawerLayoutContainer: DrawerLayoutContainer): Boolean =
        drawerLayoutContainer.inu_isDrawerOpened()

    @JvmStatic
    fun closeDrawer(drawerLayoutContainer: DrawerLayoutContainer) {
        drawerLayoutContainer.inu_closeDrawer(false)
    }

    // -----------------------------------------------------------------------
    // Adapter
    // -----------------------------------------------------------------------

    class DrawerAdapter(
        private val context: Context,
        private val itemAnimator: SideMenultItemAnimator,
        private val drawerLayoutContainer: DrawerLayoutContainer,
    ) : RecyclerListView.SelectionAdapter() {

        private val items = mutableListOf<Item?>()
        private val accountNumbers = mutableListOf<Int>()
        private var accountsShown: Boolean =
            UserConfig.getActivatedAccountsCount() > 1 &&
                MessagesController.getGlobalMainSettings().getBoolean("accountsShown", true)

        var profileCell: DrawerProfileCell? = null

        init {
            resetItems()
        }

        private fun accountRowsCount(): Int {
            var count = accountNumbers.size + 1
            if (accountNumbers.size < UserConfig.MAX_ACCOUNT_COUNT) count++
            return count
        }

        override fun getItemCount(): Int {
            var count = items.size + 2
            if (accountsShown) count += accountRowsCount()
            return count
        }

        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean =
            holder.itemViewType in setOf(3, 4, 5, 6)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view: View = when (viewType) {
                0 -> DrawerProfileCell(context, drawerLayoutContainer).also { profileCell = it }
                2 -> DividerCell(context)
                3 -> DrawerActionCell(context)
                4 -> DrawerUserCell(context)
                5 -> DrawerAddCell(context)
                else -> EmptyCell(context, dp(8f))
            }
            view.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            return RecyclerListView.Holder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder.itemViewType) {
                0 -> (holder.itemView as DrawerProfileCell).setUser(
                    MessagesController.getInstance(UserConfig.selectedAccount)
                        .getUser(UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId()),
                    accountsShown,
                )
                3 -> {
                    var pos = position - 2
                    if (accountsShown) pos -= accountRowsCount()
                    items.getOrNull(pos)?.bind(holder.itemView as DrawerActionCell)
                    (holder.itemView as DrawerActionCell).setPadding(0, 0, 0, 0)
                }
                4 -> (holder.itemView as DrawerUserCell).setAccount(accountNumbers[position - 2])
            }
        }

        override fun getItemViewType(i: Int): Int {
            if (i == 0) return 0
            if (i == 1) return 1
            var pos = i - 2
            if (accountsShown) {
                if (pos < accountNumbers.size) return 4
                val addCount = if (accountNumbers.size < UserConfig.MAX_ACCOUNT_COUNT) {
                    if (pos == accountNumbers.size) return 5
                    if (pos == accountNumbers.size + 1) return 2
                    0
                } else {
                    if (pos == accountNumbers.size) return 2
                    0
                }
                pos -= accountRowsCount()
            }
            if (pos < 0 || pos >= items.size || items[pos] == null) return 2
            return 3
        }

        fun getId(position: Int): Int {
            var pos = position - 2
            if (accountsShown) pos -= accountRowsCount()
            if (pos < 0 || pos >= items.size) return -1
            return items[pos]?.id ?: -1
        }

        fun setAccountsShown(value: Boolean, animated: Boolean) {
            if (accountsShown == value || itemAnimator.isRunning) return
            accountsShown = value
            profileCell?.setAccountsShown(accountsShown, animated)
            MessagesController.getGlobalMainSettings().edit()
                .putBoolean("accountsShown", accountsShown).apply()
            if (animated) {
                itemAnimator.setShouldClipChildren(false)
                if (accountsShown) notifyItemRangeInserted(2, accountRowsCount())
                else notifyItemRangeRemoved(2, accountRowsCount())
            } else {
                notifyDataSetChanged()
            }
        }

        override fun notifyDataSetChanged() {
            resetItems()
            super.notifyDataSetChanged()
        }

        private fun resetItems() {
            accountNumbers.clear()
            for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
                if (UserConfig.getInstance(a).isClientActivated) accountNumbers.add(a)
            }
            accountNumbers.sortWith { o1, o2 ->
                val l1 = UserConfig.getInstance(o1).loginTime
                val l2 = UserConfig.getInstance(o2).loginTime
                l1.compareTo(l2)
            }

            items.clear()
            if (!UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated) return

            val eventType = Theme.getEventType()
            val contactsIcon: Int
            val callsIcon: Int
            val savedIcon: Int
            val settingsIcon: Int
            val inviteIcon: Int
            val helpIcon: Int
            val newGroupIcon: Int
            when (eventType) {
                0 -> { // New Year
                    newGroupIcon = R.drawable.msg_groups_ny; contactsIcon = R.drawable.msg_contacts_ny
                    callsIcon = R.drawable.msg_calls_ny; savedIcon = R.drawable.msg_saved_ny
                    settingsIcon = R.drawable.msg_settings_ny; inviteIcon = R.drawable.msg_invite_ny
                    helpIcon = R.drawable.msg_help_ny
                }
                1 -> { // Valentine's
                    newGroupIcon = R.drawable.msg_groups_14; contactsIcon = R.drawable.msg_contacts_14
                    callsIcon = R.drawable.msg_calls_14; savedIcon = R.drawable.msg_saved_14
                    settingsIcon = R.drawable.msg_settings_14; inviteIcon = R.drawable.msg_secret_ny
                    helpIcon = R.drawable.msg_help
                }
                2 -> { // Halloween
                    newGroupIcon = R.drawable.msg_groups_hw; contactsIcon = R.drawable.msg_contacts_hw
                    callsIcon = R.drawable.msg_calls_hw; savedIcon = R.drawable.msg_saved_hw
                    settingsIcon = R.drawable.msg_settings_hw; inviteIcon = R.drawable.msg_invite_hw
                    helpIcon = R.drawable.msg_help_hw
                }
                else -> {
                    newGroupIcon = R.drawable.msg_groups; contactsIcon = R.drawable.msg_contacts
                    callsIcon = R.drawable.msg_calls; savedIcon = R.drawable.msg_saved
                    settingsIcon = R.drawable.msg_settings_old; inviteIcon = R.drawable.msg_invite
                    helpIcon = R.drawable.msg_help
                }
            }

            items.add(Item(ID_MY_PROFILE, getString(R.string.MyProfile), R.drawable.left_status_profile))
            items.add(null) // divider
            items.add(Item(ID_NEW_GROUP, getString(R.string.NewGroup), newGroupIcon))
            items.add(Item(ID_CONTACTS, getString(R.string.Contacts), contactsIcon))
            items.add(Item(ID_CALLS, getString(R.string.Calls), callsIcon))
            items.add(Item(ID_SAVED, getString(R.string.SavedMessages), savedIcon))
            items.add(Item(ID_SETTINGS, getString(R.string.Settings), settingsIcon))
            items.add(null) // divider
            items.add(Item(ID_INVITE, getString(R.string.InviteFriends), inviteIcon))
            items.add(Item(ID_HELP, getString(R.string.TelegramFeatures), helpIcon))
        }

        data class Item(val id: Int, val text: CharSequence, val icon: Int) {
            fun bind(cell: DrawerActionCell) {
                cell.setTextAndIcon(text, icon)
            }
        }
    }
}
