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
import androidx.collection.LongSparseArray
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
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.tgnet.SerializedData
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBarMenuSubItem
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BulletinFactory
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
    private const val OP_SCROLL = 5

    private const val ARG_TOKEN = "inu_plugin_history"
    /** header sub-item ids; rust caps a menu at 30 items */
    private const val MENU_ITEM_BASE = 800_000
    private const val MENU_ITEM_LIMIT = 30
    private const val ID_BASE = Int.MAX_VALUE / 2
    /** ids between resets: stock keeps stale ones (return-to-message), which must never name a newer entry */
    private const val WINDOW_GAP = 100_000

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
        val hasHeaderTap: Boolean,
        var buttonText: CharSequence?,
        val hasMenu: Boolean,
    ) : NotificationCenter.NotificationCenterDelegate {
        var activity: ChatActivity? = null
        val idsByKey = HashMap<String, Int>()
        val keysById = SparseArray<String>()
        val messagesById = SparseArray<MessageObject>()
        val replyKeysById = SparseArray<String>()
        val syntheticIds = HashSet<Int>()
        val jumps = SparseArray<Jump>()
        /** entries a reset dropped, kept by key so replies to them still draw their header */
        var previews = HashMap<String, MessageObject>()
        var pendingMenu = 0L
        var shownMenu = 0L
        val menuCells = ArrayList<ActionBarMenuSubItem>()
        var readId = Int.MIN_VALUE
        var readReportScheduled = false
        var pendingScrollKey: String? = null
        var nextOlderId = ID_BASE
        var nextNewerId = ID_BASE + 1
        var loaded = false
        var shown = false
        var olderCursor: String? = null
        var newerCursor: String? = null
        var olderEnd = false
        var newerEnd = true

        /** stock's reply loader fills our entries in place but announces them for its own dialog only */
        override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
            val owners = args[2] as? LongSparseArray<*> ?: return
            for (i in 0 until owners.size()) {
                val byId = owners.valueAt(i) as SparseArray<*>
                for (j in 0 until byId.size()) {
                    if ((byId.valueAt(j) as List<*>).any { it is MessageObject && messagesById[it.id] === it }) {
                        activity?.updateVisibleRows()
                        return
                    }
                }
            }
        }
    }

    /**
     * a `loadMore` page, or a `loadAround` one when [initial] or [reset]. [focusId] marks a jump's answer for the reserved
     * id of [jump]; [reset] drops every entry once accepted
     */
    private class Load(
        val screen: Screen,
        val loadIndex: Int,
        val newer: Boolean = false,
        val initial: Boolean = false,
        val reset: Boolean = false,
        val focusId: Int = 0,
        val jump: Jump? = null,
    )

    /**
     * what a reserved id stands for until stock asks to load around it. [byMessage] keys are `${markedPeer}:${messageId}`
     * of a telegram reply; [forced] ones come from a reset, which stock scrolls back to without a not-found check
     */
    private class Jump(val key: String, val byMessage: Boolean, val forced: Boolean, val fromId: Int = 0, reply: TLRPC.MessageReplyHeader? = null) {
        val taskId = reply?.takeIf { it.flags and 2048 != 0 }?.todo_item_id
        val pollOption = if (taskId == null) reply?.poll_option else null
        val quote = if (taskId == null && pollOption == null && reply?.quote == true) reply.quote_text else null
        val quoteOffset = reply?.takeIf { quote != null && it.flags and 1024 != 0 }?.quote_offset ?: -1
    }

    private class MenuItem(val text: CharSequence, val icon: String?, val danger: Boolean, val checked: Boolean?)

    /** [replyPreview] draws the reply header while the [replyTo] entry is not loaded */
    private class Entry(val key: String, val replyTo: String?, val replyPreview: MessageObject?, val message: MessageObject, val synthetic: Boolean)

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
            options.optBoolean("headerTap"),
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
        NotificationCenter.getInstance(screen.accountId).addObserver(screen, NotificationCenter.replyMessagesDidLoad)
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

    /**
     * ui thread; stock's reply tap handling, with reply targets resolved through entry keys. A real reply without a
     * message id (only `reply_from`) is left to stock, which reveals its quote or reports it private
     */
    @JvmStatic
    fun handleReplyTap(activity: ChatActivity, message: MessageObject): Boolean {
        val screen = byActivity[activity] ?: return false
        val key = getReplyKey(screen, message)
        if (key == null) return message.id in screen.syntheticIds
        jumpTo(screen, activity, Jump(key, screen.replyKeysById[message.id] == null, false, message.id, message.messageOwner.reply_to))
        return true
    }

    /** ui thread. An entry not loaded gets a reserved id for stock to load around, see [loadAround] */
    private fun jumpTo(screen: Screen, activity: ChatActivity, jump: Jump) {
        var id = screen.idsByKey[jump.key]
        if (id == jump.fromId) return
        if (id == null) {
            id = reserveWindow(screen)
            screen.jumps.put(id, jump)
        }
        activity.highlightTaskId = jump.taskId
        activity.highlightPollOptionId = jump.pollOption
        if (jump.quote != null) {
            activity.highlightMessageQuoteFirst = true
            activity.highlightMessageQuote = jump.quote
            activity.highlightMessageQuoteOffset = jump.quoteOffset
            activity.showNoQuoteAlert = true
        }
        activity.scrollToMessageId(id, jump.fromId, jump.fromId != 0, 0, false, 0, jump.taskId, jump.pollOption, null)
    }

    /** a telegram reply opens its source chat, anything else is not found */
    private fun failJump(activity: ChatActivity, jump: Jump) {
        if (!jump.byMessage) {
            BulletinFactory.of(activity).createErrorBulletin(LocaleController.getString(R.string.MessageNotFound)).show()
            return
        }
        LaunchActivity.instance?.openMessage(
            PeerSpecs.toSimpleDialogId(jump.key.substringBeforeLast(':').toLong()), jump.key.substringAfterLast(':').toInt(),
            jump.quote, null, 0, jump.quoteOffset, jump.taskId, jump.pollOption,
        )
    }

    /** a fresh id region above every id handed out so far; its base is free for a focused entry */
    private fun reserveWindow(screen: Screen): Int {
        val base = if (screen.nextNewerId > Int.MAX_VALUE - 2 * WINDOW_GAP) ID_BASE else screen.nextNewerId + WINDOW_GAP
        screen.nextNewerId = base + WINDOW_GAP
        return base
    }

    /**
     * ui thread, from stock's [ChatActivity.scrollToMessageId] when [id] is not loaded: a reserved jump asks the plugin
     * for a page around its target, anything else (a stale id) reloads the newest page
     */
    @JvmStatic
    fun loadAround(activity: ChatActivity, loadIndex: Int, id: Int) {
        val screen = byActivity[activity] ?: return
        val jump = screen.jumps[id]
        screen.jumps.remove(id)
        if (jump == null) return reload(activity, loadIndex)
        requestAround(Load(screen, loadIndex, reset = true, focusId = id, jump = jump), (if (jump.byMessage) "M" else "K") + jump.key)
    }

    private fun requestAround(load: Load, target: String) {
        val screen = load.screen
        val requestId = nextRequestId.getAndIncrement()
        pendingLoads[requestId] = load
        EngineDispatch.onEngine(screen.session, onDropped = { pendingLoads.remove(requestId) }) {
            screen.session.engine.chatHistoryLoadAround(screen.historyId, requestId, target)
        }
    }

    @JvmStatic
    fun hasHeaderTap(activity: ChatActivity): Boolean = byActivity[activity]?.hasHeaderTap == true

    @JvmStatic
    fun handleHeaderTap(activity: ChatActivity): Boolean {
        val screen = byActivity[activity]?.takeIf { it.hasHeaderTap } ?: return false
        EngineDispatch.onEngine(screen.session) { screen.session.engine.chatHistoryHeaderTap(screen.historyId) }
        return true
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
                    if (o.optBoolean("separator")) return@map null
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
                if (item == null) {
                    headerItem.addColoredGap()
                    return@forEachIndexed
                }
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
        ChatActivity.OPTION_VIEW_REPLIES_OR_THREAD, ChatActivity.OPTION_COPY_LINK, ChatActivity.OPTION_SEND_MONEY,
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

    /** ui thread. [loadIndex] is the one the activity added to its `waitingForLoad`; the first one places the screen */
    @JvmStatic
    fun load(activity: ChatActivity, loadIndex: Int, newer: Boolean) {
        val screen = byActivity[activity] ?: return
        if (!screen.loaded) {
            screen.loaded = true
            return requestAround(Load(screen, loadIndex, initial = true), "I")
        }
        val load = Load(screen, loadIndex, newer)
        val cursor = (if (newer) screen.newerCursor else screen.olderCursor)
            ?: return AndroidUtilities.runOnUIThread { deliver(load, emptyList(), null, null, null, null) }
        val requestId = nextRequestId.getAndIncrement()
        pendingLoads[requestId] = load
        EngineDispatch.onEngine(screen.session, onDropped = { pendingLoads.remove(requestId) }) {
            screen.session.engine.chatHistoryLoadMore(screen.historyId, requestId, cursor, newer)
        }
    }

    /** ui thread: the pagedown button with newer pages left asks for the newest page, like stock's jump */
    @JvmStatic
    fun reload(activity: ChatActivity, loadIndex: Int) {
        val screen = byActivity[activity] ?: return
        screen.loaded = true
        requestAround(Load(screen, loadIndex, reset = true), "L")
    }

    /** plugin queue; `S` + page json, `N` for no page, or `E` + message */
    fun page(requestId: Long, wire: String) {
        val load = pendingLoads.remove(requestId) ?: return
        var entries: List<Entry> = emptyList()
        var older: String? = null
        var newer: String? = null
        var unreadCount: Int? = null
        var focus: String? = null
        if (wire.startsWith("S")) {
            try {
                val started = android.os.SystemClock.uptimeMillis()
                val page = JSONObject(wire.substring(1))
                entries = decodeEntries(load.screen, page)
                load.screen.session.log.d("chatHistory", "decoded ${entries.size} entries in ${android.os.SystemClock.uptimeMillis() - started} ms")
                val more = !load.initial && !load.reset
                fun getCursor(name: String) = page.optString(name).takeIf { page.has(name) }
                older = getCursor(if (more && !load.newer) "next" else "older")
                newer = getCursor(if (more && load.newer) "next" else "newer")
                unreadCount = if (page.has("unreadCount")) page.getInt("unreadCount") else null
                focus = page.optString("focus").takeIf { page.has("focus") }
            } catch (e: Exception) {
                load.screen.session.log.e("chatHistory", "bad page", e)
                entries = emptyList()
            }
        } else if (wire.startsWith("E")) {
            load.screen.session.log.e("chatHistory", "load failed: ${wire.substring(1)}")
        }
        AndroidUtilities.runOnUIThread { deliver(load, entries, older, newer, unreadCount, focus) }
    }

    /**
     * The initial page's [focus] is its first unread entry: stock draws its "Unread messages" line before it and scrolls there.
     * A page stock no longer waits for (a reset since, a cancelled jump) is dropped before it touches any entry
     */
    private fun deliver(load: Load, entries: List<Entry>, older: String?, newer: String?, unreadCount: Int?, focus: String?) {
        val screen = load.screen
        val activity = screen.activity ?: return
        if (!activity.inu_acceptsPluginLoad(load.loadIndex)) return
        val jump = load.jump
        val focusAt = entries.indexOfFirst { it.key == focus }
        if (jump != null && focusAt < 0) {
            val empty = screen.messagesById.size() == 0
            if (!jump.forced && (jump.byMessage || empty)) failJump(activity, jump)
            if (jump.forced || empty) return reload(activity, load.loadIndex)
            return postPage(screen, activity, load, ArrayList(), 3, 0, null)
        }
        if (load.reset) {
            val previews = HashMap<String, MessageObject>()
            for (i in 0 until screen.messagesById.size()) {
                val message = screen.messagesById.valueAt(i)
                previews[screen.keysById[message.id]] = message
                getReplyKey(screen, message)?.let { key -> screen.previews[key]?.let { previews.putIfAbsent(key, it) } }
            }
            screen.previews = previews
            screen.jumps.clear()
            for (i in 0 until screen.keysById.size()) screen.jumps.put(screen.keysById.keyAt(i), Jump(screen.keysById.valueAt(i), false, true))
            screen.idsByKey.clear()
            screen.keysById.clear()
            screen.messagesById.clear()
            screen.replyKeysById.clear()
            screen.syntheticIds.clear()
            if (jump == null) screen.nextOlderId = reserveWindow(screen)
        }
        val messages = ArrayList<MessageObject>(entries.size)
        if (jump != null) {
            entries.forEachIndexed { i, entry ->
                if (screen.idsByKey.containsKey(entry.key)) return@forEachIndexed
                assignEntry(screen, entry, load.focusId + focusAt - i)
                messages.add(entry.message)
            }
            screen.nextNewerId = maxOf(screen.nextNewerId, load.focusId + focusAt + 1)
            screen.nextOlderId = load.focusId - (entries.size - focusAt)
            screen.olderCursor = older
            screen.olderEnd = older == null
            screen.newerCursor = newer
            screen.newerEnd = newer == null
            return postPage(screen, activity, load, messages, 3, 0, unreadCount)
        }
        for (entry in if (load.newer) entries.asReversed() else entries) {
            if (screen.idsByKey.containsKey(entry.key)) continue
            assignEntry(screen, entry, if (load.newer) screen.nextNewerId++ else screen.nextOlderId--)
            messages.add(entry.message)
        }
        if (load.newer) {
            messages.reverse()
            screen.newerCursor = newer
            screen.newerEnd = newer == null || messages.isEmpty()
        } else {
            screen.olderCursor = older
            screen.olderEnd = older == null || messages.isEmpty()
            if (load.initial || load.reset) {
                screen.newerCursor = newer
                screen.newerEnd = newer == null
            }
        }
        val loadType = if (load.initial) 2 else if (load.newer) 1 else 0
        val firstUnreadId = if (load.initial && focus != null) screen.idsByKey[focus] ?: 0 else 0
        postPage(screen, activity, load, messages, loadType, firstUnreadId, unreadCount)
    }

    private fun postPage(screen: Screen, activity: ChatActivity, load: Load, messages: ArrayList<MessageObject>, loadType: Int, firstUnreadId: Int, unreadCount: Int?) {
        screen.shown = true
        activity.inu_pluginHistoryEnds(screen.olderEnd, screen.newerEnd)
        NotificationCenter.getInstance(screen.accountId).postNotificationName(
            NotificationCenter.messagesDidLoad,
            0L, messages.size, messages, false, firstUnreadId, 0, if (firstUnreadId != 0) unreadCount ?: 0 else 0, 0, loadType, true,
            activity.classGuid, load.loadIndex, 0, 0, ChatActivity.inu_MODE_PLUGIN_HISTORY,
        )
        if (resolveReplies(screen)) activity.updateVisibleRows()
        loadReplies(screen, activity, messages)
        activity.inu_pluginHistoryEnds(screen.olderEnd, screen.newerEnd)
        unreadCount?.let(activity::inu_setPluginUnreadCount)
        screen.pendingScrollKey?.let { key ->
            screen.pendingScrollKey = null
            jumpTo(screen, activity, Jump(key, false, false))
        }
    }

    private fun assignEntry(screen: Screen, entry: Entry, id: Int) {
        screen.idsByKey[entry.key] = id
        screen.keysById.put(id, entry.key)
        if (entry.synthetic) screen.syntheticIds.add(id) else screen.syntheticIds.remove(id)
        val message = entry.message
        message.messageOwner.realId = message.messageOwner.id
        message.messageOwner.id = id
        screen.messagesById.put(id, message)
        if (entry.replyTo != null) screen.replyKeysById.put(id, entry.replyTo) else screen.replyKeysById.remove(id)
        if (entry.replyPreview != null) screen.previews[entry.replyTo!!] = entry.replyPreview
    }

    /** an explicit `replyTo`, else a real message's telegram reply target under its default key */
    private fun getReplyKey(screen: Screen, message: MessageObject): String? {
        screen.replyKeysById[message.id]?.let { return it }
        if (message.id in screen.syntheticIds) return null
        val reply = message.messageOwner.reply_to ?: return null
        if (reply.reply_to_msg_id == 0) return null
        val peer = reply.reply_to_peer_id ?: message.messageOwner.peer_id ?: return null
        return "${PeerSpecs.toMarkedPeerId(peer)}:${reply.reply_to_msg_id}"
    }

    private fun resolveReplies(screen: Screen): Boolean {
        var changed = false
        for (i in 0 until screen.messagesById.size()) {
            val message = screen.messagesById.valueAt(i)
            val key = getReplyKey(screen, message)
            val target = key?.let(screen.idsByKey::get)?.takeIf { it != message.id }?.let(screen.messagesById::get) ?: key?.takeIf { it != screen.keysById[message.id] }?.let(screen.previews::get)
            if (message.replyMessageObject === target || target == null && screen.replyKeysById[message.id] == null) continue
            message.replyMessageObject = target
            message.applyTimestampsHighlightForReplyMsg()
            when (message.messageOwner.action) {
                is TLRPC.TL_messageActionPinMessage -> message.generatePinMessageText(null, null)
                is TLRPC.TL_messageActionGameScore -> message.generateGameMessageText(null)
                is TLRPC.TL_messageActionPaymentSent -> message.generatePaymentSentMessageText(null, false)
                is TLRPC.TL_messageActionPaymentSentMe -> message.generatePaymentSentMessageText(null, true)
                is TLRPC.TL_messageActionSuggestedPostApproval -> message.generateSuggestionApprovalMessageText()
            }
            changed = true
        }
        return changed
    }

    /** telegram reply targets missing from the feed, through stock's loader */
    private fun loadReplies(screen: Screen, activity: ChatActivity, messages: List<MessageObject>) {
        val loader = MediaDataController.getInstance(screen.accountId)
        messages.filter { it.replyMessageObject == null && it.id !in screen.syntheticIds && screen.replyKeysById[it.id] == null }
            .groupBy { it.dialogId }
            .forEach { (dialogId, group) -> loader.loadReplyMessagesForMessages(ArrayList(group), dialogId, 0, 0, null, activity.classGuid, null) }
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
        if (op == OP_SCROLL) {
            AndroidUtilities.runOnUIThread {
                val activity = screen.activity
                if (activity != null && screen.shown) {
                    screen.pendingScrollKey = null
                    jumpTo(screen, activity, Jump(json, false, false))
                } else {
                    screen.pendingScrollKey = json
                }
            }
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
                        if (screen.idsByKey.containsKey(entry.key)) continue
                        assignEntry(screen, entry, screen.nextNewerId++)
                        messages.add(entry.message)
                    }
                    if (messages.isNotEmpty()) activity.processNewMessages(messages)
                    loadReplies(screen, activity, messages)
                }
                OP_REPLACE -> {
                    val messages = ArrayList<MessageObject>()
                    for (entry in entries) {
                        assignEntry(screen, entry, screen.idsByKey[entry.key] ?: continue)
                        messages.add(entry.message)
                    }
                    if (messages.isNotEmpty()) activity.replaceMessageObjects(messages, 0, false)
                    loadReplies(screen, activity, messages)
                }
                OP_REMOVE -> {
                    val ids = ArrayList<Int>()
                    for (key in keys) screen.previews.remove(key)
                    for (key in keys) screen.idsByKey.remove(key)?.let {
                        ids.add(it)
                        screen.keysById.remove(it)
                        screen.messagesById.remove(it)
                        screen.replyKeysById.remove(it)
                        screen.syntheticIds.remove(it)
                    }
                    if (ids.isNotEmpty()) activity.processDeletedMessages(ids, 0L, false)
                }
            }
            if (resolveReplies(screen)) activity.updateVisibleRows()
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
        NotificationCenter.getInstance(screen.accountId).removeObserver(screen, NotificationCenter.replyMessagesDidLoad)
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
            val (key, message) = decodeMessage(screen, entry)
            val reply = entry.optJSONObject("replyTo")?.let { decodeMessage(screen, it) }
            Entry(
                key,
                reply?.first ?: entry.optString("replyTo").takeIf { entry.has("replyTo") },
                reply?.second,
                message,
                entry.optBoolean("synthetic", screen.synthetic),
            )
        }
    }

    private fun decodeMessage(screen: Screen, entry: JSONObject): Pair<String, MessageObject> {
        val message = TlJson.fromJson(entry.getJSONObject("message")) as? TLRPC.Message
            ?: throw IllegalArgumentException("'message' must be a TL message")
        val key = if (entry.has("key")) entry.getString("key")
        else "${PeerSpecs.toMarkedPeerId(message.peer_id ?: throw IllegalArgumentException("an entry without 'key' needs 'message.peer_id'"))}:${message.id}"
        return key to MessageObject(screen.accountId, message, null, null, null, null, null, true, true, 0, false, false, false, ChatActivity.SEARCH_MY_MESSAGES)
    }
}
