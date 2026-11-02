package desu.inugram.helpers.plugins.telegram

import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View
import desu.inugram.core.plugins.DispatchDeadline
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.SessionResource
import desu.inugram.helpers.plugins.TextEntitiesListener
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlJson
import desu.inugram.helpers.plugins.ui.PluginUi
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.utils.code.highlight.CodeHighlighting
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.browser.Browser
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.ScrimOptions
import org.telegram.ui.Components.TextStyleSpan
import org.telegram.ui.Components.URLSpanMono
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * `registerTextEntity`. Rust matches the patterns without the engine lease while the app builds a
 * message's text, and the spans carry what a tap hands back to the engine. A change rebuilds the
 * messages on screen, like a filter change does.
 */
object PluginTextEntities : SessionResource {
    private const val TAKEN_STYLES = TextStyleSpan.FLAG_STYLE_MONO or TextStyleSpan.FLAG_STYLE_CODE
    private const val TAP_BUDGET_MS = 30_000L

    /** a callback done within this never shows the loading link */
    private const val LOADING_DELAY_MS = 150L

    private class Entry(val session: PluginSession, val needs: Int)

    /** spans outlive the session in cached messages, so they must not keep it alive */
    internal class Tap(
        val session: WeakReference<PluginSession>,
        val token: Int,
        val account: Int,
        val dialogId: Long,
        val owner: TLRPC.Message,
        val match: JSONArray,
        val groups: JSONObject,
    )

    /** ui thread only. The progress holds the chat, so it is let go once over */
    private class Loading(private var progress: Browser.Progress?) {
        private var shown = false
        private val show = Runnable {
            shown = true
            progress?.init()
        }

        /** a link it replaces stays loading until this one shows */
        fun start(replacing: Boolean, onCancel: () -> Unit) {
            progress?.onCancel {
                progress = null
                AndroidUtilities.cancelRunOnUIThread(show)
                onCancel()
            }
            if (replacing) show.run() else AndroidUtilities.runOnUIThread(show, LOADING_DELAY_MS)
        }

        fun finish() {
            val progress = progress ?: return
            this.progress = null
            AndroidUtilities.cancelRunOnUIThread(show)
            if (shown) progress.end()
        }
    }

    /** the tapped link in a chat, where the tap shows loading and anchors its menus; the tap may outlive the chat */
    private class ChatLink(val loading: Loading, fragment: ChatActivity, cell: ChatMessageCell, val span: Span, val tap: Tap) {
        private val fragment = WeakReference(fragment)
        private val cell = WeakReference(cell)

        fun isIn(chat: ChatActivity): Boolean = fragment.get() === chat

        /** the chat and the cell, while the link is still on screen */
        fun findShownAnchor(): Pair<ChatActivity, ChatMessageCell>? {
            val fragment = fragment.get()?.takeIf { it.parentActivity != null } ?: return null
            val cell = cell.get() ?: return null
            val message = cell.messageObject
            if (!cell.isAttachedToWindow || message == null || message.id != tap.owner.id || message.dialogId != tap.dialogId) return null
            return fragment to cell
        }
    }

    private class PendingTap(val session: PluginSession) {
        lateinit var deadline: DispatchDeadline
    }

    private val nextTapId = AtomicLong()

    // scheduler only
    private val pendingTaps = HashMap<Long, PendingTap>()

    /** added before the tap reaches the engine, removed once it is over */
    private val chatLinks = ConcurrentHashMap<Long, ChatLink>()

    /** written before [generation], so a reader that sees a generation sees its entries */
    @Volatile private var entries: List<Entry> = emptyList()

    /** never 0, which marks a message whose text was not built here */
    @Volatile private var generation = 1

    fun listenerFor(session: PluginSession): TextEntitiesListener = object : TextEntitiesListener {
        override fun textEntitiesChanged(needs: Int) = update { current ->
            val others = current.filter { it.session !== session }
            if (needs == 0) others else others + Entry(session, needs)
        }

        override fun textEntitySettled(tapId: Long) = EngineDispatch.scheduler.postRunnable { finishTap(tapId) }

        /** read here rather than on the ui thread, where the tap may be over already */
        override fun uiOpenTextEntityMenu(menuId: Long, tapId: Long, itemsJson: String): String? {
            val link = chatLinks[tapId]
            return PluginUi.openMenu(session, menuId, itemsJson) { menu -> showMenu(menu, link) }
        }
    }

    private fun showMenu(menu: PluginUi.Menu, link: ChatLink?) {
        if (link == null) return menu.settle(-1)
        val (fragment, cell) = link.findShownAnchor() ?: return menu.settle(-1)
        link.loading.finish()
        val dialog = object : ScrimOptions(fragment.context, fragment.resourceProvider) {
            override fun dismiss() {
                menu.settle(-1)
                super.dismiss()
            }

            override fun dismissFast() {
                menu.settle(-1)
                super.dismissFast()
            }
        }
        val options = menu.fill(ItemOptions.makeOptions(fragment, cell, true)) {}
        options.setOnDismiss(dialog::dismissFast)
        dialog.setItemOptions(options)
        dialog.setScrim(cell, link.span, null)
        fragment.showScrimOptions(dialog)
        // showDialog refuses mid-transition and ScrimOptions.show refuses an unsafe context, neither dismisses
        if (!dialog.isShowing) menu.settle(-1)
    }

    /**
     * Stock stops only the link still loading when the chat is left or another link is tapped; a tap whose
     * menu ended its loading stops here
     */
    @JvmStatic
    fun abandonChatTaps(chat: ChatActivity) {
        for ((tapId, link) in chatLinks) {
            if (link.isIn(chat)) EngineDispatch.scheduler.postRunnable { abandonTap(tapId, false) }
        }
    }

    fun refreshOrder() = update { it }

    override fun detach(session: PluginSession) {
        update { current -> current.filter { it.session !== session } }
        for (tapId in pendingTaps.filterValues { it.session === session }.keys) finishTap(tapId)
    }

    private fun dispatchTap(session: PluginSession, tap: Tap, tapId: Long, secondary: Boolean) {
        if (!session.canDispatch()) return finishLink(tapId)
        val json = try {
            JSONObject()
                .put("accountId", tap.account)
                .put("dialogId", PeerSpecs.toMarkedPeerId(MessagesController.getInstance(tap.account), tap.dialogId))
                .put("message", TlJson.toJson(tap.owner, TlFilter.policyFor(session.permissions)))
                .put("match", tap.match)
                .put("groups", tap.groups)
                .put("anchored", chatLinks.containsKey(tapId))
        } catch (e: Exception) {
            session.log.e("textEntity", "could not read the tapped message", e)
            return finishLink(tapId)
        }
        val entry = PendingTap(session)
        entry.deadline = DispatchDeadline(EngineDispatch.scheduler, TAP_BUDGET_MS) { abandonTap(tapId, true) }
        pendingTaps[tapId] = entry
        entry.deadline.resume()
        session.engine.dispatchTextEntity(tap.token, secondary, tapId, json.toString())
    }

    private fun abandonTap(tapId: Long, timedOut: Boolean) {
        val entry = pendingTaps[tapId] ?: return
        if (timedOut) entry.session.log.w("textEntity", "a tap ran past its ${TAP_BUDGET_MS}ms budget")
        finishTap(tapId)
        entry.session.engine.abandonTextEntityTap(tapId, timedOut)
    }

    private fun finishTap(tapId: Long) {
        val entry = pendingTaps.remove(tapId) ?: return
        entry.deadline.cancel()
        finishLink(tapId)
    }

    private fun finishLink(tapId: Long) {
        val link = chatLinks.remove(tapId) ?: return
        AndroidUtilities.runOnUIThread(link.loading::finish)
    }

    private fun update(change: (List<Entry>) -> List<Entry>) {
        synchronized(this) {
            val order = PluginManager.orderIndex()
            val updated = change(entries).sortedBy { order[it.session.plugin] ?: Int.MAX_VALUE }
            if (updated == entries) return
            entries = updated
            generation++
        }
        PluginMessageFilters.notifyAllChanged()
    }

    @JvmStatic
    fun isStale(message: MessageObject): Boolean =
        message.inu_textEntitiesGeneration != 0 && message.inu_textEntitiesGeneration != generation

    /** for a message whose text is not rebuilt, so it stops being stale */
    fun markCurrent(message: MessageObject) {
        if (message.inu_textEntitiesGeneration != 0) message.inu_textEntitiesGeneration = generation
    }

    /**
     * Runs after the stock entities, so a match never lands on a link or code; on overlap the
     * earlier plugin wins. Answers whether [text] has links
     */
    @JvmStatic
    fun apply(message: MessageObject, text: CharSequence, hasLinks: Boolean): Boolean {
        message.inu_textEntitiesGeneration = generation
        if (entries.isEmpty()) return hasLinks
        val owner = message.messageOwner
        if (text !is Spannable || text.isEmpty() || owner == null || !isEligible(message, owner)) return hasLinks
        val entries = entries.filter { it.session.canDispatch() }
        if (entries.isEmpty()) return hasLinks
        val probe = PluginMessageFilters.readProbe(owner, entries.fold(0) { needs, entry -> needs or entry.needs })
        val json = QuickJs.matchTextEntities(entries.map { it.session.engine }, text.toString(), probe) ?: return hasLinks
        val matches = JSONArray(json)
        var added = false
        for (i in 0 until matches.length()) {
            val match = matches.getJSONObject(i)
            val start = match.getInt("start")
            val end = match.getInt("end")
            if (end > text.length || isTaken(text, start, end)) continue
            val url = if (match.has("url")) match.getString("url") else null
            val tap = if (url != null) {
                null
            } else {
                Tap(
                    WeakReference(entries[match.getInt("engine")].session), match.getInt("token"), message.currentAccount,
                    message.dialogId, owner, match.getJSONArray("match"), match.getJSONObject("groups"),
                )
            }
            text.setSpan(Span(url, tap), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            added = true
        }
        return hasLinks || added
    }

    /**
     * Secret chats are never handed to plugins, and service messages carry login codes. The admin log
     * reads every link it is handed as a url
     */
    private fun isEligible(message: MessageObject, owner: TLRPC.Message): Boolean =
        message.eventId == 0L && !DialogObject.isEncryptedDialog(message.dialogId) && !TlFilter.isServiceMessage(owner)

    private fun isTaken(text: Spannable, start: Int, end: Int): Boolean =
        text.getSpans(start, end, ClickableSpan::class.java).isNotEmpty() ||
            text.getSpans(start, end, URLSpanMono::class.java).isNotEmpty() ||
            text.getSpans(start, end, CodeHighlighting.Span::class.java).isNotEmpty() ||
            text.getSpans(start, end, TextStyleSpan::class.java).any { it.styleFlags and TAKEN_STYLES != 0 }

    /** chat bubbles open [url] through the stock link flow, with its confirmation and long-tap menu */
    class Span internal constructor(@JvmField val url: String?, private val tap: Tap?) : ClickableSpan() {
        override fun onClick(widget: View) {
            if (url != null) Browser.openUrl(widget.context, url) else press(false, null, null, null, false)
        }

        /**
         * UI thread. A long tap without a `secondaryCallback` runs the `callback`. In a chat, [progress] shows the
         * link loading while the callback's promise runs, and its cancel aborts the callback's signal. [replacing]
         * when another link is shown loading
         */
        fun press(secondary: Boolean, chat: ChatActivity?, cell: ChatMessageCell?, progress: Browser.Progress?, replacing: Boolean) {
            val tap = tap ?: return
            val session = tap.session.get() ?: return
            val tapId = nextTapId.incrementAndGet()
            if (chat != null && cell != null && progress != null) {
                abandonChatTaps(chat)
                val loading = Loading(progress)
                chatLinks[tapId] = ChatLink(loading, chat, cell, this, tap)
                loading.start(replacing) { EngineDispatch.scheduler.postRunnable { abandonTap(tapId, false) } }
            }
            EngineDispatch.onEngine(session, onDropped = { finishLink(tapId) }) {
                dispatchTap(session, tap, tapId, secondary)
            }
        }

        override fun updateDrawState(paint: TextPaint) {
            paint.color = paint.linkColor
        }
    }
}
