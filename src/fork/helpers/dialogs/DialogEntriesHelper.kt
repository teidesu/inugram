package desu.inugram.helpers.dialogs

// #if PLUGINS
import desu.inugram.helpers.plugins.ui.PluginDialogEntries
// #endif
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.ImageReceiver
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Adapters.DialogsAdapter
import org.telegram.ui.Adapters.DialogsSearchAdapter
import org.telegram.ui.Cells.DialogCell
import org.telegram.ui.DialogsActivity

object DialogEntriesHelper {
    /** a chat list row that is not one of the account's own dialogs; `pinned` and `last_message_date` place it */
    abstract class Entry(@JvmField val account: Int, @JvmField val key: String) : TLRPC.TL_dialog() {
        open fun isForum(): Boolean = false

        /** a tap that leaves the list hides the ripple, so only such rows wait out the tap timeout before showing it */
        open fun opensScreen(): Boolean = true

        /** false rebinds the row: stock rows refresh from the controller on their own, a snapshot does not */
        open fun hasSameContent(other: Entry): Boolean = true

        abstract fun bind(cell: DialogCell, dialogsType: Int, folderId: Int)

        /** replaces the placeholder of a row drawn from a [DialogCell.CustomDialog] */
        open fun applyAvatar(image: ImageReceiver) = Unit

        abstract fun onClick(fragment: DialogsActivity, adapter: RecyclerView.Adapter<*>, view: View)

        abstract fun onLongClick(fragment: DialogsActivity, adapter: RecyclerView.Adapter<*>, view: View)
    }

    @JvmStatic
    fun getEntries(account: Int, dialogsType: Int, folderId: Int, onlySelect: Boolean): List<Entry> {
        val entries = ArrayList<Entry>(LinkedChatsHelper.getDialogs(account, dialogsType, folderId, onlySelect))
        // #if PLUGINS
        entries += PluginDialogEntries.getEntries(account, dialogsType, folderId, onlySelect)
        // #endif
        entries.sortWith(compareByDescending<Entry> { it.pinned }.thenByDescending { if (it.pinned) 0 else it.last_message_date })
        return entries
    }

    @JvmStatic
    fun delaysPressedState(child: View): Boolean = (child as? DialogCell)?.inu_getEntry()?.opensScreen() ?: true

    private val requestedDialogs = HashSet<String>()

    /** ui thread; once per dialog, reloads the list when it arrives */
    @JvmStatic
    fun requestDialog(account: Int, dialogId: Long) {
        if (!requestedDialogs.add("$account:$dialogId")) return
        DialogsActivity.loadDialogs(AccountInstance.getInstance(account))
        MessagesController.getInstance(account).inu_loadDialog(dialogId) {
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload)
        }
    }

    private fun getEntry(adapter: RecyclerView.Adapter<*>, position: Int): Entry? = when (adapter) {
        is DialogsAdapter -> adapter.getItem(position)
        is DialogsSearchAdapter -> adapter.getItem(position)
        else -> null
    } as? Entry

    @JvmStatic
    fun onItemClick(fragment: DialogsActivity, view: View, adapter: RecyclerView.Adapter<*>, position: Int): Boolean {
        val entry = getEntry(adapter, position) ?: return false
        if (!fragment.actionBar.isActionModeShowed) entry.onClick(fragment, adapter, view)
        return true
    }

    @JvmStatic
    fun onItemLongClick(fragment: DialogsActivity, view: View, adapter: RecyclerView.Adapter<*>, position: Int): Boolean {
        val entry = getEntry(adapter, position) ?: return false
        if (!fragment.actionBar.isActionModeShowed) entry.onLongClick(fragment, adapter, view)
        return true
    }
}
