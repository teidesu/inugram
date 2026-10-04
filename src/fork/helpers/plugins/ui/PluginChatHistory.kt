package desu.inugram.helpers.plugins.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.SparseArray
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.chat.ChatHelper
import org.telegram.messenger.ChatObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.SessionResource
import desu.inugram.helpers.plugins.telegram.PeerSpecs
import desu.inugram.helpers.plugins.tl.TlJson
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.tgnet.SerializedData
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBarMenuSubItem
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.LaunchActivity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A [ChatActivity] in `inu_MODE_PLUGIN_HISTORY` whose pages come from the plugin. Like stock's hashtag
 * search, every entry gets an id of this screen's own (older pages count down, newer ones up) with the
 * plugin's id kept in `realId`, so one message may appear several times and messages of many dialogs
 * never collide. Screen state is ui-thread only; page decoding runs on the plugin queue.
 */
object PluginChatHistory : SessionResource {
    // keep in sync with rust `api::ui::history::OP_*`
    private const val OP_APPEND = 0
    private const val OP_REPLACE = 1
    private const val OP_REMOVE = 2
    private const val OP_UNREAD_COUNT = 3
    private const val OP_BUTTON = 4

    private const val ARG_TOKEN = "inu_plugin_history"
    /** header sub-item ids; rust caps a menu at 30 items */
    private const val MENU_ITEM_BASE = 800_000
    private const val MENU_ITEM_LIMIT = 30
    private const val ID_BASE = Int.MAX_VALUE / 2

    private class Screen(
        val session: PluginSession,
        val historyId: Long,
        val token: Long,
        val accountId: Int,
        val title: CharSequence,
        val subtitle: CharSequence?,
        val synthetic: Boolean,
        val avatars: Boolean,
        val iconSpec: String?,
        val reportsRead: Boolean,
        var buttonText: CharSequence?,
        val hasMenu: Boolean,
    ) {
        var activity: ChatActivity? = null
        val idsByKey = HashMap<String, Int>()
        val keysById = SparseArray<String>()
        val syntheticIds = HashSet<Int>()
        var pendingMenu = 0L
        var shownMenu = 0L
        val menuCells = ArrayList<ActionBarMenuSubItem>()
        var readId = Int.MIN_VALUE
        var readReportScheduled = false
        var nextOlderId = ID_BASE
        var nextNewerId = ID_BASE + 1
        var loaded = false
        var olderCursor: String? = null
        var newerCursor: String? = null
        var olderEnd = false
        var newerEnd = true
    }

    private class Load(val screen: Screen, val loadIndex: Int, val newer: Boolean, val first: Boolean, val jump: Boolean = false)

    private class MenuItem(val text: CharSequence, val icon: String?, val danger: Boolean, val checked: Boolean?)

    private class Entry(val key: String?, val message: MessageObject, val synthetic: Boolean)

    private data class HistoryKey(val session: PluginSession, val historyId: Long)

    private val byHistory = ConcurrentHashMap<HistoryKey, Screen>()
    private val byToken = ConcurrentHashMap<Long, Screen>()
    private val byActivity = HashMap<ChatActivity, Screen>()
    private val pendingLoads = ConcurrentHashMap<Long, Load>()
    private val pendingMenus = ConcurrentHashMap<Long, Screen>()
    private val nextToken = AtomicLong(1)
    private val nextRequestId = AtomicLong(1)

    fun open(session: PluginSession, historyId: Long, optionsJson: String): String? {
        val options = try {
            JSONObject(optionsJson)
        } catch (_: Exception) {
            return PluginWire.encodePluginError("invalid-argument", "openChatHistory: malformed options")
        }
        val accountId = if (options.has("account")) options.getInt("account") else org.telegram.messenger.UserConfig.selectedAccount
        if (PeerSpecs.controllerFor(accountId) == null) {
            return PluginWire.encodePluginError("not-found", "openChatHistory: account #$accountId is not logged in")
        }
        val screen = Screen(
            session,
            historyId,
            nextToken.getAndIncrement(),
            accountId,
            PluginText.formatted(options.getString("title"), options.optJSONArray("titleEntities")),
            options.optString("subtitle").takeIf { options.has("subtitle") }
                ?.let { PluginText.formatted(it, options.optJSONArray("subtitleEntities")) },
            options.optBoolean("synthetic"),
            options.optBoolean("avatars", true),
            options.optString("icon").takeIf { options.has("icon") },
            options.optBoolean("read"),
            options.optJSONObject("button")?.takeIf { it.has("text") }?.let { PluginText.formatted(it.getString("text"), it.optJSONArray("textEntities")) },
            options.optBoolean("menu"),
        )
        val key = HistoryKey(session, historyId)
        byHistory[key] = screen
        byToken[screen.token] = screen
        val opened = android.os.SystemClock.uptimeMillis()
        AndroidUtilities.runOnUIThread {
            session.log.d("chatHistory", "presenting ${android.os.SystemClock.uptimeMillis() - opened} ms after open")
            val current = LaunchActivity.getSafeLastFragment()
            val next = ChatActivity(Bundle().apply {
                putInt("chatMode", ChatActivity.inu_MODE_PLUGIN_HISTORY)
                putLong(ARG_TOKEN, screen.token)
            })
            next.setCurrentAccount(accountId)
            if (current == null || !session.isCurrent() || !current.presentFragment(next)) finish(screen)
        }
        return null
    }

    /** ui thread, from [ChatActivity.onFragmentCreate] */
    @JvmStatic
    fun attach(activity: ChatActivity, arguments: Bundle): Boolean {
        val screen = byToken[arguments.getLong(ARG_TOKEN)] ?: return false
        if (screen.activity != null) return false
        screen.activity = activity
        byActivity[activity] = screen
        return true
    }

    @JvmStatic
    fun onDestroyed(activity: ChatActivity) {
        byActivity[activity]?.let(::finish)
    }

    @JvmStatic
    fun title(activity: ChatActivity): CharSequence = byActivity[activity]?.title ?: ""

    @JvmStatic
    fun subtitle(activity: ChatActivity): CharSequence? = byActivity[activity]?.subtitle

    @JvmStatic
    fun hasAvatar(activity: ChatActivity): Boolean = byActivity[activity]?.iconSpec != null

    /** the bar stock uses for Mute/Join/Unpin all, lent to the plugin when it declared a button */
    @JvmStatic
    fun buttonText(activity: ChatActivity): CharSequence? = byActivity[activity]?.buttonText

    @JvmStatic
    fun onButtonClick(activity: ChatActivity) {
        val screen = byActivity[activity] ?: return
        EngineDispatch.onEngine(screen.session) { screen.session.engine.chatHistoryButtonClick(screen.historyId) }
    }

    /** ui thread, once the header exists: a peer draws as itself, any other icon as a glyph on a disc */
    @JvmStatic
    fun hasMenu(activity: ChatActivity): Boolean = byActivity[activity]?.hasMenu == true

    /**
     * ui thread, from the three-dot tap. Takes it over when the screen has a menu: the plugin builds the
     * items, then [menu] fills the stock dropdown and opens it. Taps while a request is out are swallowed.
     */
    @JvmStatic
    fun requestMenu(activity: ChatActivity): Boolean {
        val screen = byActivity[activity] ?: return false
        if (!screen.hasMenu) return false
        if (screen.pendingMenu != 0L) return true
        val requestId = nextRequestId.getAndIncrement()
        screen.pendingMenu = requestId
        pendingMenus[requestId] = screen
        EngineDispatch.onEngine(screen.session, onDropped = { pendingMenus.remove(requestId) }) {
            screen.session.engine.chatHistoryMenu(screen.historyId, requestId)
        }
        return true
    }

    /** plugin queue; `S` + items json or `E` + message */
    fun menu(requestId: Long, wire: String) {
        val screen = pendingMenus.remove(requestId) ?: return
        val items = if (wire.startsWith("S")) {
            try {
                val array = JSONArray(wire.substring(1))
                (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    MenuItem(
                        PluginText.formatted(o.getString("text"), o.optJSONArray("textEntities")),
                        o.optString("icon").takeIf { o.has("icon") },
                        o.optBoolean("danger"),
                        if (o.has("checked")) o.getBoolean("checked") else null,
                    )
                }
            } catch (e: Exception) {
                screen.session.log.e("chatHistory", "bad menu", e)
                null
            }
        } else {
            screen.session.log.e("chatHistory", "menu failed: ${wire.substring(1)}")
            null
        }
        AndroidUtilities.runOnUIThread {
            if (screen.pendingMenu != requestId) return@runOnUIThread
            screen.pendingMenu = 0L
            val activity = screen.activity ?: return@runOnUIThread
            val headerItem = activity.headerItem ?: return@runOnUIThread
            if (items.isNullOrEmpty() || activity.parentActivity == null || headerItem.isSubMenuShowing) return@runOnUIThread
            for (cell in screen.menuCells) PluginIcons.clearIcon(cell.imageView)
            screen.menuCells.clear()
            headerItem.removeAllSubItems()
            items.forEachIndexed { index, item ->
                val cell = headerItem.addSubItem(MENU_ITEM_BASE + index, 0, null, item.text, true, item.checked != null)
                item.checked?.let(cell::setChecked)
                if (item.icon != null) PluginIcons.setIcon(cell, item.text, item.icon, screen.session.engine, 0)
                if (item.danger) cell.setColors(Theme.getColor(Theme.key_text_RedRegular), Theme.getColor(Theme.key_text_RedRegular))
                screen.menuCells.add(cell)
            }
            screen.shownMenu = requestId
            headerItem.toggleSubMenu()
        }
    }

    /** ui thread, from the activity's item clicks */
    @JvmStatic
    fun onMenuItemClick(activity: ChatActivity, id: Int): Boolean {
        val index = id - MENU_ITEM_BASE
        if (index !in 0 until MENU_ITEM_LIMIT) return false
        val screen = byActivity[activity] ?: return false
        val menuRequest = screen.shownMenu
        EngineDispatch.onEngine(screen.session) { screen.session.engine.chatHistoryMenuClick(screen.historyId, menuRequest, index) }
        return true
    }

    @JvmStatic
    fun applyAvatar(activity: ChatActivity, view: BackupImageView) {
        val screen = byActivity[activity] ?: return
        val spec = screen.iconSpec ?: return
        if (spec[0] == 'p') {
            val (accountId, dialogId) = PluginIcons.parseAvatarSpec(spec) ?: return
            val avatar = AvatarDrawable()
            val peer = MessagesController.getInstance(accountId).getUserOrChat(dialogId)
            if (peer != null) {
                avatar.setInfo(accountId, peer)
                view.setForUserOrChat(peer, avatar)
            } else {
                avatar.setInfo(dialogId, "", "")
                view.setImageDrawable(avatar)
            }
            return
        }
        val glyph = PluginIcons.resolveImmediateDrawable(view.context, spec, screen.session.engine) ?: return
        view.setImageDrawable(DiscIconDrawable(glyph))
    }

    /** a 24dp glyph tinted white on the saved-messages blue disc, like stock's own placeholder avatars */
    private class DiscIconDrawable(private val glyph: Drawable) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.getColor(Theme.key_avatar_backgroundSaved) }

        init {
            glyph.colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_avatar_text), PorterDuff.Mode.SRC_IN)
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), minOf(b.width(), b.height()) / 2f, paint)
            val half = AndroidUtilities.dp(GLYPH_DP) / 2
            val cx = b.centerX()
            val cy = b.centerY()
            glyph.setBounds(cx - half, cy - half, cx + half, cy + half)
            glyph.draw(canvas)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            glyph.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private const val GLYPH_DP = 24f

    /** stock's `isChat`: sender avatars and names, as in a group */
    @JvmStatic
    fun showsAvatars(activity: ChatActivity): Boolean = byActivity[activity]?.avatars == true

    /** real messages can be shown where they live; synthetic ones exist only on this screen */
    @JvmStatic
    fun showsInChat(activity: ChatActivity, message: MessageObject?): Boolean {
        val screen = byActivity[activity] ?: return false
        return message != null && message.id !in screen.syntheticIds
    }

    @JvmStatic
    fun isSynthetic(activity: ChatActivity, message: MessageObject?): Boolean {
        val screen = byActivity[activity] ?: return false
        return message == null || message.id in screen.syntheticIds
    }

    /** entries are copies under host ids: only options that never send those ids survive */
    private val LOCAL_MESSAGE_OPTIONS = setOf(
        ChatActivity.OPTION_COPY, ChatActivity.OPTION_SAVE_TO_GALLERY, ChatActivity.OPTION_SAVE_TO_GALLERY2,
        ChatActivity.OPTION_SAVE_TO_DOWNLOADS_OR_MUSIC, ChatActivity.OPTION_SHARE, ChatActivity.OPTION_ADD_TO_STICKERS_OR_MASKS,
        ChatActivity.OPTION_ADD_TO_GIFS, ChatActivity.OPTION_ADD_STICKER_TO_FAVORITES, ChatActivity.OPTION_DELETE_STICKER_FROM_FAVORITES,
        ChatActivity.OPTION_APPLY_LOCALIZATION_OR_THEME, ChatActivity.OPTION_OPEN_PROFILE, ChatActivity.OPTION_TRANSLATE,
        ChatHelper.OPTION_TRANSLATE_REVERT, ChatHelper.OPTION_DETAILS, ChatHelper.OPTION_SHOW_JSON, ChatHelper.OPTION_REMOVE_FROM_CACHE,
        ChatHelper.OPTION_COPY_MEDIA, ChatHelper.OPTION_SAVE_STICKER_TO_DOWNLOADS, ChatHelper.OPTION_PLUGIN_ACTIONS,
    )

    /** routed to `realId` and the message's own chat, so they work on real entries */
    private val REAL_MESSAGE_OPTIONS = setOf(
        ChatActivity.OPTION_FORWARD, ChatHelper.OPTION_FORWARD_NO_QUOTE, ChatHelper.OPTION_SAVE,
        ChatHelper.OPTION_REPLY_IN, ChatHelper.OPTION_SHOW_IN_CHAT,
        ChatActivity.OPTION_VIEW_REPLIES_OR_THREAD, ChatActivity.OPTION_COPY_LINK,
    )

    /** stock gates view thread and copy link on the screen's chat, which this screen has none of */
    @JvmStatic
    fun filterMessageMenu(
        activity: ChatActivity,
        selectedObject: MessageObject,
        selectedObjectGroup: MessageObject.GroupedMessages?,
        items: ArrayList<CharSequence>,
        options: ArrayList<Int>,
        icons: ArrayList<Int>,
    ) {
        val real = showsInChat(activity, selectedObject)
        val allowed = LOCAL_MESSAGE_OPTIONS + if (real) REAL_MESSAGE_OPTIONS else emptySet()
        for (idx in options.indices.reversed()) {
            if (options[idx] !in allowed) {
                items.removeAt(idx); options.removeAt(idx); icons.removeAt(idx)
            }
        }
        if (!real) return
        val chat = MessagesController.getInstance(activity.currentAccount).getChat(-selectedObject.dialogId)
        val primary = selectedObjectGroup?.findPrimaryMessageObject() ?: selectedObject
        if (chat != null && (chat.has_link || primary.hasReplies()) && chat.megagroup && primary.canViewThread()) {
            items.add(if (primary.hasReplies()) LocaleController.formatPluralString("ViewReplies", primary.repliesCount) else LocaleController.getString(R.string.ViewThread))
            options.add(ChatActivity.OPTION_VIEW_REPLIES_OR_THREAD)
            icons.add(R.drawable.msg_viewreplies)
        }
        if (ChatObject.isChannel(chat) && !ChatObject.isMonoForum(chat)) {
            items.add(LocaleController.getString(R.string.CopyLink))
            options.add(ChatActivity.OPTION_COPY_LINK)
            icons.add(R.drawable.msg_link)
        }
    }

    /** a copy under the message's real id, for sending it on to another chat */
    @JvmStatic
    fun realMessage(message: MessageObject): MessageObject {
        val owner = message.messageOwner
        if (owner.realId == 0) return message
        val data = SerializedData()
        owner.serializeToStream(data)
        val stream = SerializedData(data.toByteArray())
        val copy = TLRPC.Message.TLdeserialize(stream, stream.readInt32(false), false)
        copy.id = owner.realId
        copy.dialog_id = owner.dialog_id
        return MessageObject(message.currentAccount, copy, true, true)
    }

    @JvmStatic
    fun realMessages(messages: ArrayList<MessageObject>) {
        messages.replaceAll { realMessage(it) }
    }

    /**
     * ui thread, per visible cell on every visible-part pass; the newest entry seen so far goes to the plugin
     * once the pass settles. The plugin owns what "read" means for its sources.
     */
    @JvmStatic
    fun onVisible(activity: ChatActivity, id: Int) {
        val screen = byActivity[activity] ?: return
        if (!screen.reportsRead || id <= screen.readId) return
        screen.readId = id
        if (screen.readReportScheduled) return
        screen.readReportScheduled = true
        AndroidUtilities.runOnUIThread({
            screen.readReportScheduled = false
            val key = screen.keysById[screen.readId] ?: return@runOnUIThread
            EngineDispatch.onEngine(screen.session) { screen.session.engine.chatHistoryRead(screen.historyId, key) }
        }, READ_REPORT_DELAY_MS)
    }

    private const val READ_REPORT_DELAY_MS = 300L

    /** ui thread. [loadIndex] is the one the activity added to its `waitingForLoad` */
    @JvmStatic
    fun load(activity: ChatActivity, loadIndex: Int, newer: Boolean) = load(activity, loadIndex, newer, false)

    /** ui thread: the pagedown button with newer pages left asks for the newest page, `(null, 'newer')`, like stock's jump */
    @JvmStatic
    fun reload(activity: ChatActivity, loadIndex: Int) {
        val screen = byActivity[activity] ?: return
        screen.idsByKey.clear()
        screen.keysById.clear()
        screen.syntheticIds.clear()
        screen.olderCursor = null
        screen.newerCursor = null
        screen.loaded = false
        load(activity, loadIndex, false, true)
    }

    private fun load(activity: ChatActivity, loadIndex: Int, newer: Boolean, jump: Boolean) {
        val screen = byActivity[activity] ?: return
        val load = Load(screen, loadIndex, newer, !newer && !screen.loaded, jump)
        screen.loaded = true
        val requestId = nextRequestId.getAndIncrement()
        pendingLoads[requestId] = load
        val cursor = if (newer) screen.newerCursor else screen.olderCursor
        EngineDispatch.onEngine(screen.session, onDropped = { pendingLoads.remove(requestId) }) {
            screen.session.engine.chatHistoryLoad(screen.historyId, requestId, cursor, newer || jump)
        }
    }

    /** plugin queue; `S` + page json or `E` + message */
    fun page(requestId: Long, wire: String) {
        val load = pendingLoads.remove(requestId) ?: return
        var entries: List<Entry> = emptyList()
        var next: String? = null
        var newer: String? = null
        var firstUnread: String? = null
        var unreadCount: Int? = null
        if (wire.startsWith("S")) {
            try {
                val started = android.os.SystemClock.uptimeMillis()
                val page = JSONObject(wire.substring(1))
                entries = decodeEntries(load.screen, page)
                load.screen.session.log.d("chatHistory", "decoded ${entries.size} entries in ${android.os.SystemClock.uptimeMillis() - started} ms")
                next = page.optString("next").takeIf { page.has("next") }
                newer = page.optString("newer").takeIf { page.has("newer") }
                firstUnread = page.optString("firstUnread").takeIf { page.has("firstUnread") }
                unreadCount = if (page.has("unreadCount")) page.getInt("unreadCount") else null
            } catch (e: Exception) {
                load.screen.session.log.e("chatHistory", "bad page", e)
                entries = emptyList()
            }
        } else {
            load.screen.session.log.e("chatHistory", "load failed: ${wire.substring(1)}")
        }
        AndroidUtilities.runOnUIThread { deliver(load, entries, next, newer, firstUnread, unreadCount) }
    }

    /** [firstUnread] is read on the first page only: stock draws its "Unread messages" line before that entry and scrolls there */
    private fun deliver(load: Load, entries: List<Entry>, next: String?, newer: String?, firstUnread: String?, unreadCount: Int?) {
        val screen = load.screen
        val activity = screen.activity ?: return
        val messages = ArrayList<MessageObject>(entries.size)
        for (entry in if (load.newer) entries.asReversed() else entries) {
            val message = entry.message
            val key = entry.key ?: "${message.dialogId}:${message.id}"
            if (screen.idsByKey.containsKey(key)) continue
            val id = if (load.newer) screen.nextNewerId++ else screen.nextOlderId--
            screen.idsByKey[key] = id
            screen.keysById.put(id, key)
            assignId(screen, entry, id)
            messages.add(message)
        }
        if (load.newer) messages.reverse()
        val end = next == null || messages.isEmpty()
        if (load.newer) {
            screen.newerCursor = next
            screen.newerEnd = end
        } else {
            screen.olderCursor = next
            screen.olderEnd = end
            if (load.first) {
                screen.newerCursor = newer
                screen.newerEnd = newer == null || load.jump
            }
        }
        val loadType = if (load.first && !load.jump) 2 else if (load.newer) 1 else 0
        val firstUnreadId = if (loadType == 2 && firstUnread != null) screen.idsByKey[firstUnread] ?: 0 else 0
        activity.inu_pluginHistoryEnds(screen.olderEnd, screen.newerEnd)
        NotificationCenter.getInstance(screen.accountId).postNotificationName(
            NotificationCenter.messagesDidLoad,
            0L, messages.size, messages, false, firstUnreadId, 0, if (firstUnreadId != 0) unreadCount ?: 0 else 0, 0, loadType, true,
            activity.classGuid, load.loadIndex, 0, 0, ChatActivity.inu_MODE_PLUGIN_HISTORY,
        )
        activity.inu_pluginHistoryEnds(screen.olderEnd, screen.newerEnd)
        unreadCount?.let(activity::inu_setPluginUnreadCount)
    }

    private fun assignId(screen: Screen, entry: Entry, id: Int) {
        if (entry.synthetic) screen.syntheticIds.add(id) else screen.syntheticIds.remove(id)
        val message = entry.message
        message.messageOwner.realId = message.messageOwner.id
        message.messageOwner.id = id
    }

    /** plugin queue */
    fun update(session: PluginSession, historyId: Long, op: Int, json: String): String? {
        val screen = byHistory[HistoryKey(session, historyId)]
            ?: return PluginWire.encodePluginError("handle-expired", "openChatHistory: this chat history has been closed")
        if (op == OP_BUTTON) {
            val text = try {
                if (json == "null") null else JSONObject(json).let { PluginText.formatted(it.getString("text"), it.optJSONArray("textEntities")) }
            } catch (e: Exception) {
                return PluginWire.encodePluginError("invalid-argument", "setButton: ${e.message}")
            }
            AndroidUtilities.runOnUIThread {
                screen.buttonText = text
                screen.activity?.updateBottomOverlay()
            }
            return null
        }
        if (op == OP_UNREAD_COUNT) {
            val count = json.toIntOrNull() ?: return PluginWire.encodePluginError("invalid-argument", "setUnreadCount: not a number")
            AndroidUtilities.runOnUIThread { screen.activity?.inu_setPluginUnreadCount(count) }
            return null
        }
        val entries: List<Entry>
        val keys: List<String>
        var unreadCount: Int? = null
        try {
            if (op == OP_REMOVE) {
                val array = JSONArray(json)
                keys = (0 until array.length()).map { array.getString(it) }
                entries = emptyList()
            } else {
                val page = JSONObject(json)
                entries = decodeEntries(screen, page)
                if (page.has("unreadCount")) unreadCount = page.getInt("unreadCount")
                keys = emptyList()
            }
        } catch (e: Exception) {
            return PluginWire.encodePluginError("invalid-argument", "openChatHistory: ${e.message}")
        }
        AndroidUtilities.runOnUIThread {
            val activity = screen.activity ?: return@runOnUIThread
            when (op) {
                OP_APPEND -> {
                    val messages = ArrayList<MessageObject>()
                    for (entry in entries.asReversed()) {
                        val key = entry.key ?: "${entry.message.dialogId}:${entry.message.id}"
                        if (screen.idsByKey.containsKey(key)) continue
                        val id = screen.nextNewerId++
                        screen.idsByKey[key] = id
                        screen.keysById.put(id, key)
                        assignId(screen, entry, id)
                        messages.add(entry.message)
                    }
                    if (messages.isNotEmpty()) activity.processNewMessages(messages)
                }
                OP_REPLACE -> {
                    val messages = ArrayList<MessageObject>()
                    for (entry in entries) {
                        val key = entry.key ?: "${entry.message.dialogId}:${entry.message.id}"
                        val id = screen.idsByKey[key] ?: continue
                        assignId(screen, entry, id)
                        messages.add(entry.message)
                    }
                    if (messages.isNotEmpty()) activity.replaceMessageObjects(messages, 0, false)
                }
                OP_REMOVE -> {
                    val ids = ArrayList<Int>()
                    for (key in keys) screen.idsByKey.remove(key)?.let { ids.add(it); screen.keysById.remove(it); screen.syntheticIds.remove(it) }
                    if (ids.isNotEmpty()) activity.processDeletedMessages(ids, 0L, false)
                }
            }
            unreadCount?.let(activity::inu_setPluginUnreadCount)
        }
        return null
    }

    /** plugin queue */
    fun close(session: PluginSession, historyId: Long) {
        val screen = byHistory[HistoryKey(session, historyId)] ?: return
        AndroidUtilities.runOnUIThread {
            val activity = screen.activity
            if (activity != null) activity.removeSelfFromStack() else finish(screen)
        }
    }

    /** ui thread; idempotent, also reached from [ChatActivity.onFragmentDestroy] */
    private fun finish(screen: Screen) {
        if (byHistory.remove(HistoryKey(screen.session, screen.historyId)) == null) return
        byToken.remove(screen.token)
        screen.activity?.let(byActivity::remove)
        screen.activity = null
        for (cell in screen.menuCells) PluginIcons.clearIcon(cell.imageView)
        screen.menuCells.clear()
        EngineDispatch.onEngine(screen.session) { screen.session.engine.chatHistoryClosed(screen.historyId) }
    }

    override fun detach(session: PluginSession) {
        AndroidUtilities.runOnUIThread {
            for (screen in byHistory.values.filter { it.session === session }) {
                val activity = screen.activity
                finish(screen)
                activity?.removeSelfFromStack()
            }
        }
    }

    /**
     * plugin queue: decodes and caches peers so the layouts built here can name them. `searchType` as in
     * stock's hashtag search: channel posts draw their avatar too, and the share side button stays off
     */
    private fun decodeEntries(screen: Screen, page: JSONObject): List<Entry> {
        val controller = MessagesController.getInstance(screen.accountId)
        val users = ArrayList<TLRPC.User>()
        val chats = ArrayList<TLRPC.Chat>()
        page.optJSONArray("users")?.let { array ->
            for (i in 0 until array.length()) users.add(TlJson.fromJson(array.getJSONObject(i)) as? TLRPC.User ?: throw IllegalArgumentException("'users' must hold TL users"))
        }
        page.optJSONArray("chats")?.let { array ->
            for (i in 0 until array.length()) chats.add(TlJson.fromJson(array.getJSONObject(i)) as? TLRPC.Chat ?: throw IllegalArgumentException("'chats' must hold TL chats"))
        }
        controller.putUsers(users, false)
        controller.putChats(chats, false)
        val array = page.getJSONArray("entries")
        return (0 until array.length()).map { i ->
            val entry = array.getJSONObject(i)
            val message = TlJson.fromJson(entry.getJSONObject("message")) as? TLRPC.Message
                ?: throw IllegalArgumentException("'message' must be a TL message")
            Entry(
                entry.optString("key").takeIf { entry.has("key") },
                MessageObject(screen.accountId, message, null, null, null, null, null, true, true, 0, false, false, false, ChatActivity.SEARCH_MY_MESSAGES),
                entry.optBoolean("synthetic", screen.synthetic),
            )
        }
    }
}
