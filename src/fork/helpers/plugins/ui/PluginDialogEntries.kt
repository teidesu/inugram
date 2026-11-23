package desu.inugram.helpers.plugins.ui

import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.dialogs.DialogEntriesHelper
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.SessionResource
import desu.inugram.helpers.plugins.telegram.PeerSpecs
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ImageReceiver
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.DialogCell
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.DialogsActivity
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** `ui.addDialogEntry`: rows merged into the chat lists of an account, or of every account */
object PluginDialogEntries : SessionResource {
    /** [peer] is a simple dialog id, 0 for a row drawn from the other fields */
    private class Content(
        val peer: Long,
        val title: String,
        val text: String,
        val icon: String?,
        val date: Int,
        val unreadCount: Int,
        val pinned: Boolean,
        val muted: Boolean,
        val verified: Boolean,
    )

    /** what a tap's anchor opens its menus at, while the cell still shows the entry */
    private class Tap(val id: Long, val key: String, fragment: DialogsActivity, cell: View) {
        val fragment = WeakReference(fragment)
        val cell = WeakReference(cell)
    }

    /** [account] -1 shows in every account; [chatFolders] are dialog filter ids, 0 for "All chats" */
    private class Registered(
        val session: PluginSession,
        val token: Int,
        val account: Int,
        val chatFolders: Set<Int>,
        @Volatile var content: Content,
    ) {
        @Volatile var tap: Tap? = null
    }

    private class Entry(account: Int, val registered: Registered, val content: Content) :
        DialogEntriesHelper.Entry(account, "plugin:${registered.session.plugin.id}:${registered.token}") {
        init {
            id = content.peer
            pinned = content.pinned
            last_message_date = if (content.peer != 0L) MessagesController.getInstance(account).dialogs_dict.get(content.peer)?.last_message_date ?: 0 else content.date
        }

        override fun isForum(): Boolean = content.peer != 0L && MessagesController.getInstance(account).isForum(content.peer)

        override fun opensScreen(): Boolean = false

        override fun hasSameContent(other: DialogEntriesHelper.Entry): Boolean = other is Entry && other.content === content

        override fun bind(cell: DialogCell, dialogsType: Int, folderId: Int) {
            cell.inu_setEntry(this, false)
            if (content.peer != 0L) {
                cell.setDialog(this, dialogsType, folderId)
                return
            }
            cell.setDialog(DialogCell.CustomDialog().also {
                it.id = registered.token
                it.name = content.title
                it.message = content.text
                it.date = content.date
                it.unread_count = content.unreadCount
                it.pinned = content.pinned
                it.muted = content.muted
                it.verified = content.verified
            })
        }

        override fun applyAvatar(image: ImageReceiver) {
            val spec = content.icon ?: return
            if (spec[0] == 'p') {
                val (accountId, dialogId) = PluginIcons.parseAvatarSpec(spec) ?: return
                val peer = MessagesController.getInstance(accountId).getUserOrChat(dialogId) ?: return
                image.setCurrentAccount(accountId)
                image.setForUserOrChat(peer, AvatarDrawable().apply { setInfo(accountId, peer) })
                return
            }
            val context = image.parentView?.context ?: return
            val glyph = PluginIcons.resolveImmediateDrawable(context, spec, registered.session.engine) ?: return
            image.setImageBitmap(PluginChatHistory.DiscIconDrawable(glyph))
        }

        override fun onClick(fragment: DialogsActivity, adapter: RecyclerView.Adapter<*>, view: View) = dispatch(fragment, view, false)

        override fun onLongClick(fragment: DialogsActivity, adapter: RecyclerView.Adapter<*>, view: View) = dispatch(fragment, view, true)

        private fun dispatch(fragment: DialogsActivity, view: View, secondary: Boolean) {
            val session = registered.session
            val tap = Tap(nextTapId.getAndIncrement(), key, fragment, view)
            registered.tap = tap
            EngineDispatch.onEngine(session) { session.engine.dialogEntryClick(registered.token, tap.id, secondary) }
        }
    }

    private val entries = CopyOnWriteArrayList<Registered>()
    private val nextTapId = AtomicLong(1)

    @JvmStatic
    fun getEntries(account: Int, dialogsType: Int, folderId: Int, onlySelect: Boolean): List<DialogEntriesHelper.Entry> {
        if (folderId != 0 || onlySelect) return emptyList()
        val controller = MessagesController.getInstance(account)
        val chatFolder = when (dialogsType) {
            DialogsActivity.DIALOGS_TYPE_DEFAULT -> 0
            DialogsActivity.DIALOGS_TYPE_FOLDER1 -> controller.selectedDialogFilter[0]?.id
            DialogsActivity.DIALOGS_TYPE_FOLDER2 -> controller.selectedDialogFilter[1]?.id
            else -> null
        } ?: return emptyList()
        return entries.mapNotNull { registered ->
            val content = registered.content
            if (registered.account != -1 && registered.account != account || chatFolder !in registered.chatFolders || !registered.session.canDispatch()) return@mapNotNull null
            if (content.peer != 0L && controller.dialogs_dict.get(content.peer) == null) {
                DialogEntriesHelper.requestDialog(account, content.peer)
                return@mapNotNull null
            }
            Entry(account, registered, content)
        }
    }

    /** plugin queue */
    fun set(session: PluginSession, token: Int, json: String): String? {
        val options = try {
            JSONObject(json)
        } catch (_: Exception) {
            return PluginWire.encodePluginError("invalid-argument", "addDialogEntry: malformed content")
        }
        val content = Content(
            if (options.has("peer")) PeerSpecs.toSimpleDialogId(options.getLong("peer")) else 0L,
            options.optString("title"),
            options.optString("text").replace('\n', ' '),
            options.optString("icon").takeIf { options.has("icon") },
            options.optInt("date"),
            options.optInt("unreadCount"),
            options.optBoolean("pinned"),
            options.optBoolean("muted"),
            options.optBoolean("verified"),
        )
        val existing = entries.find { it.session === session && it.token == token }
        if (existing != null) {
            existing.content = content
            notifyChanged(existing.account)
            return null
        }
        val account = options.optInt("account", -1)
        if (account != -1 && PeerSpecs.controllerFor(account) == null) {
            return PluginWire.encodePluginError("not-found", "addDialogEntry: account #$account is not logged in")
        }
        val chatFolders = options.optJSONArray("chatFolders")?.let { ids -> (0 until ids.length()).mapTo(HashSet()) { ids.getInt(it) } } ?: setOf(0)
        entries += Registered(session, token, account, chatFolders, content)
        notifyChanged(account)
        return null
    }

    /** plugin queue */
    fun remove(session: PluginSession, token: Int) {
        val removed = entries.find { it.session === session && it.token == token } ?: return
        entries -= removed
        notifyChanged(removed.account)
    }

    /** plugin queue */
    fun openMenu(session: PluginSession, menuId: Long, tapId: Long, itemsJson: String): String? {
        val tap = entries.firstNotNullOfOrNull { registered -> registered.tap?.takeIf { registered.session === session && it.id == tapId } }
        return PluginUi.openMenu(session, menuId, itemsJson) { menu ->
            val fragment = tap?.fragment?.get()
            val cell = tap?.cell?.get()
            if (fragment == null || cell !is DialogCell || fragment.parentActivity == null || !cell.isAttachedToWindow || cell.inu_getEntry()?.key != tap.key) {
                return@openMenu menu.settle(-1)
            }
            menu.fill(ItemOptions.makeOptions(fragment, cell).setLongPressSelectionEnabled(false)) {}
                .setScrimViewBackground(ColorDrawable(fragment.getThemedColor(Theme.key_windowBackgroundWhite)))
                .setOnDismiss { menu.settle(-1) }
                .show()
        }
    }

    override fun detach(session: PluginSession) {
        val removed = entries.filter { it.session === session }
        if (removed.isEmpty()) return
        entries -= removed.toSet()
        removed.map { it.account }.distinct().forEach(::notifyChanged)
    }

    private fun notifyChanged(account: Int) = AndroidUtilities.runOnUIThread {
        val accounts = if (account == -1) (0 until UserConfig.MAX_ACCOUNT_COUNT).filter { UserConfig.getInstance(it).isClientActivated } else listOf(account)
        for (it in accounts) NotificationCenter.getInstance(it).postNotificationName(NotificationCenter.dialogsNeedReload)
    }
}
