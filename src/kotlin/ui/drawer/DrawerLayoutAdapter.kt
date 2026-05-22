package desu.inugram.ui.drawer

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.helpers.AccountOrderHelper
import desu.inugram.helpers.DialogsFabHelper
import desu.inugram.helpers.PasscodeHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.DrawerLayoutContainer
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.DividerCell
import org.telegram.ui.Cells.EmptyCell
import org.telegram.ui.Components.RecyclerListView

class DrawerLayoutAdapter(
    private val mContext: Context,
    private val itemAnimator: SideMenultItemAnimator,
    private val mDrawerLayoutContainer: DrawerLayoutContainer,
) : RecyclerListView.SelectionAdapter() {

    private val items = ArrayList<Item?>(11)
    private val accountNumbers = ArrayList<Int>()
    private var accountsShown: Boolean

    @JvmField var profileCell: DrawerProfileCell? = null

    init {
        accountsShown = UserConfig.getActivatedAccountsCount() > 1 &&
            MessagesController.getGlobalMainSettings().getBoolean("accountsShown", true)
        Theme.createCommonDialogResources(mContext)
        resetItems()
    }

    private fun getAccountRowsCount(): Int {
        var count = accountNumbers.size + 1
        if (canAddAccount()) count++
        return count
    }

    // accountNumbers excludes hidden accounts, so it can't gate the add-account
    // row — use the real activated count against the hard cap instead.
    private fun canAddAccount(): Boolean =
        UserConfig.getActivatedAccountsCount() < UserConfig.MAX_ACCOUNT_COUNT

    override fun getItemCount(): Int {
        var count = items.size + 2
        if (accountsShown) count += getAccountRowsCount()
        return count
    }

    fun setAccountsShown(value: Boolean, animated: Boolean) {
        if (accountsShown == value || itemAnimator.isRunning()) return
        accountsShown = value
        profileCell?.setAccountsShown(accountsShown, animated)
        MessagesController.getGlobalMainSettings().edit().putBoolean("accountsShown", accountsShown).commit()
        if (animated) {
            if (accountsShown) {
                notifyItemRangeInserted(2, getAccountRowsCount())
            } else {
                notifyItemRangeRemoved(2, getAccountRowsCount())
            }
        } else {
            notifyDataSetChanged()
        }
    }

    fun isAccountsShown(): Boolean = accountsShown

    override fun notifyDataSetChanged() {
        resetItems()
        super.notifyDataSetChanged()
    }

    override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean {
        val t = holder.itemViewType
        return t == 3 || t == 4 || t == 5 || t == 6
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view: View = when (viewType) {
            0 -> DrawerProfileCell(mContext, mDrawerLayoutContainer).also { profileCell = it }
            2 -> DividerCell(mContext)
            3 -> DrawerActionCell(mContext)
            4 -> DrawerUserCell(mContext)
            5 -> DrawerAddCell(mContext)
            else -> EmptyCell(mContext, AndroidUtilities.dp(8f))
        }
        view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return RecyclerListView.Holder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder.itemViewType) {
            0 -> {
                val cell = holder.itemView as DrawerProfileCell
                cell.setUser(
                    MessagesController.getInstance(UserConfig.selectedAccount)
                        .getUser(UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId()),
                    accountsShown
                )
            }
            3 -> {
                val cell = holder.itemView as DrawerActionCell
                var pos = position - 2
                if (accountsShown) pos -= getAccountRowsCount()
                items[pos]!!.bind(cell)
                cell.setPadding(0, 0, 0, 0)
            }
            4 -> {
                val cell = holder.itemView as DrawerUserCell
                cell.setAccount(accountNumbers[position - 2])
            }
        }
    }

    override fun getItemViewType(i: Int): Int {
        if (i == 0) return 0
        if (i == 1) return 1
        var idx = i - 2
        if (accountsShown) {
            if (idx < accountNumbers.size) return 4
            if (canAddAccount()) {
                if (idx == accountNumbers.size) return 5
                if (idx == accountNumbers.size + 1) return 2
            } else {
                if (idx == accountNumbers.size) return 2
            }
            idx -= getAccountRowsCount()
        }
        if (idx < 0 || idx >= items.size || items[idx] == null) return 2
        return 3
    }

    fun getId(position: Int): Int {
        var pos = position - 2
        if (accountsShown) pos -= getAccountRowsCount()
        if (pos < 0 || pos >= items.size) return -1
        return items[pos]?.id ?: -1
    }

    fun getAttachMenuBot(position: Int): TLRPC.TL_attachMenuBot? {
        var pos = position - 2
        if (accountsShown) pos -= getAccountRowsCount()
        if (pos < 0 || pos >= items.size) return null
        return items[pos]?.bot
    }

    private fun resetItems() {
        accountNumbers.clear()
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (UserConfig.getInstance(a).isClientActivated()
                && (a == UserConfig.selectedAccount || !PasscodeHelper.isAccountHidden(a))) {
                accountNumbers.add(a)
            }
        }
        AccountOrderHelper.sort(accountNumbers)

        items.clear()
        if (!UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated()) return

        val eventType = Theme.getEventType()
        val newGroupIcon: Int
        val contactsIcon: Int
        val callsIcon: Int
        val savedIcon: Int
        val settingsIcon: Int
        when (eventType) {
            0 -> {
                newGroupIcon = R.drawable.msg_groups_ny
                contactsIcon = R.drawable.msg_contacts_ny
                callsIcon = R.drawable.msg_calls_ny
                savedIcon = R.drawable.msg_saved_ny
                settingsIcon = R.drawable.msg_settings_ny
            }
            1 -> {
                newGroupIcon = R.drawable.msg_groups_14
                contactsIcon = R.drawable.msg_contacts_14
                callsIcon = R.drawable.msg_calls_14
                savedIcon = R.drawable.msg_saved_14
                settingsIcon = R.drawable.msg_settings_14
            }
            2 -> {
                newGroupIcon = R.drawable.msg_groups_hw
                contactsIcon = R.drawable.msg_contacts_hw
                callsIcon = R.drawable.msg_calls_hw
                savedIcon = R.drawable.msg_saved_hw
                settingsIcon = R.drawable.msg_settings_hw
            }
            else -> {
                newGroupIcon = R.drawable.msg_groups
                contactsIcon = R.drawable.msg_contacts
                callsIcon = R.drawable.msg_calls
                savedIcon = R.drawable.msg_saved
                settingsIcon = R.drawable.msg_settings_old
            }
        }

        items.add(Item(16, LocaleController.getString(R.string.MyProfile), R.drawable.left_status_profile))
        val menuBots = MediaDataController.getInstance(UserConfig.selectedAccount).getAttachMenuBots()
        if (menuBots?.bots != null) {
            for (bot in menuBots.bots) {
                if (bot.show_in_side_menu) {
                    items.add(Item(bot))
                }
            }
        }
        items.add(null) // divider
        // Mirrors the overflow menu: a pending compose-draft swaps "New Group" for "New Message".
        if (DialogsFabHelper.hasNewMessage()) {
            items.add(Item(17, LocaleController.getString(R.string.NewMessageTitle), R.drawable.menu_topic_add))
        } else {
            items.add(Item(2, LocaleController.getString(R.string.NewGroup), newGroupIcon))
        }
        items.add(Item(6, LocaleController.getString(R.string.Contacts), contactsIcon))
        items.add(Item(10, LocaleController.getString(R.string.Calls), callsIcon))
        items.add(Item(11, LocaleController.getString(R.string.SavedMessages), savedIcon))
        items.add(Item(8, LocaleController.getString(R.string.Settings), settingsIcon))
    }

    class Item private constructor(
        val id: Int,
        val text: CharSequence?,
        val icon: Int,
        val bot: TLRPC.TL_attachMenuBot?,
    ) {
        constructor(id: Int, text: CharSequence, icon: Int) : this(id, text, icon, null)
        constructor(bot: TLRPC.TL_attachMenuBot) : this((100 + (bot.bot_id shr 16)).toInt(), null, 0, bot)

        fun bind(actionCell: DrawerActionCell) {
            if (bot != null) actionCell.setBot(bot)
            else actionCell.setTextAndIcon(id, text!!, icon)
        }
    }
}
