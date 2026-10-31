package desu.inugram.helpers.plugins.telegram

import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.view.ViewGroup
import android.widget.TextView
import android.util.LruCache
import androidx.collection.LongSparseArray
import desu.inugram.helpers.chat.BlockedMessagesHelper
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.MessageFiltersListener
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.SessionResource
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlJson
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DialogObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBarPopupWindow
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.LayoutHelper

/**
 * `filterMessages`. Rust matches the rules without the engine lease; callbacks run on the engine
 * thread and their outcomes are cached per plugin. Incoming messages wait for them in [awaitVerdicts]
 * before the ui gets them; anything else shows what is known and is rebuilt when the outcome lands.
 * A rule change keeps the old outcomes on screen until the new ones arrive.
 */
object PluginMessageFilters : SessionResource {
    class Verdict(
        val generation: Int,
        val owner: TLRPC.Message,
        val editDate: Int,
        val hide: Boolean,
        val spoiler: Boolean,
        val rewrite: TLRPC.TL_textWithEntities?,
        val rewriteBy: String?,
    )

    private data class Key(val account: Int, val dialogId: Long, val id: Int)

    private class Outcome(
        val generation: Int,
        val editDate: Int,
        val hide: Boolean,
        val spoiler: Boolean,
        val rewrite: TLRPC.TL_textWithEntities?,
    ) {
        val isVisible get() = hide || spoiler || rewrite != null
    }

    private class Job(val key: Key, val owner: TLRPC.Message, val callbackIds: IntArray, val generation: Int)

    private class Rules(val session: PluginSession, val needs: Int, val generation: Int) {
        val outcomes = LruCache<Key, Outcome>(OUTCOME_CACHE_SIZE)

        /** guarded by [lock]: [queue] waits for the engine, [pending] also holds the running jobs */
        val queue = LinkedHashMap<Key, Job>()
        val pending = HashSet<Key>()
        var scheduled = false
    }

    // keep in sync with rust `filters::NEEDS_*` and `filters::MATCHED_*`
    private const val NEEDS_ENTITIES = 2
    private const val NEEDS_BUTTONS = 4
    private const val MATCHED_SPOILER = 1
    private const val MATCHED_HIDE = 2

    private const val OUTCOME_CACHE_SIZE = 4096
    private const val REVERTED_CACHE_SIZE = 1024
    private const val AWAIT_BUDGET_MS = 150L

    private val lock = ReentrantLock()
    private val settled = lock.newCondition()

    /** written before [generation], so a reader that sees a generation sees its rules */
    @Volatile private var entries: List<Rules> = emptyList()
    @Volatile private var generation = 0

    private val reverted = LruCache<Key, Boolean>(REVERTED_CACHE_SIZE)
    private val pendingNotify = HashSet<Int>()

    val isActive: Boolean get() = entries.isNotEmpty()

    fun listenerFor(session: PluginSession): MessageFiltersListener = object : MessageFiltersListener {
        override fun messageFiltersChanged(needs: Int) {
            update { current ->
                val previous = current.find { it.session === session }
                val others = current.filter { it !== previous }
                if (needs == 0 || !session.permissions.has("filterMessages")) {
                    others
                } else {
                    val rules = Rules(session, needs, (previous?.generation ?: 0) + 1)
                    previous?.outcomes?.snapshot()?.forEach { (key, outcome) -> rules.outcomes.put(key, outcome) }
                    others + rules
                }
            }
        }
    }

    fun refreshOrder() = update { it }

    override fun detach(session: PluginSession) = update { current -> current.filter { it.session !== session } }

    private fun update(change: (List<Rules>) -> List<Rules>) {
        synchronized(this) {
            val order = PluginManager.orderIndex()
            val updated = change(entries).sortedBy { order[it.session.plugin] ?: Int.MAX_VALUE }
            if (updated == entries) return
            entries = updated
            generation++
        }
        lock.withLock { settled.signalAll() }
        notifyChanged((0 until UserConfig.MAX_ACCOUNT_COUNT).filter { UserConfig.getInstance(it).isClientActivated })
    }

    private fun notifyChanged(accounts: Collection<Int>) {
        val post = synchronized(pendingNotify) {
            val wasEmpty = pendingNotify.isEmpty()
            pendingNotify += accounts
            wasEmpty && pendingNotify.isNotEmpty()
        }
        if (!post) return
        AndroidUtilities.runOnUIThread {
            val accounts = synchronized(pendingNotify) { pendingNotify.toList().also { pendingNotify.clear() } }
            accounts.forEach(BlockedMessagesHelper::notifyChanged)
        }
    }

    @JvmStatic
    fun getRewrite(message: MessageObject): TLRPC.TL_textWithEntities? = getVerdict(message)?.rewrite

    @JvmStatic
    fun getVerdict(message: MessageObject): Verdict? {
        val generation = generation
        val entries = entries
        val owner = message.messageOwner
        if (entries.isEmpty() || owner == null || !isFilterable(message, owner)) return null
        if (BlockedMessagesHelper.isRevealed(message.currentAccount, message.dialogId)) return null
        message.inu_filterVerdict?.let { cached ->
            if (cached.generation == generation && cached.owner === owner && cached.editDate == owner.edit_date) return cached
        }
        return computeVerdict(message, owner, entries, generation).also { message.inu_filterVerdict = it }
    }

    /**
     * Holds an incoming batch back until its callbacks answer, at most [AWAIT_BUDGET_MS], then bakes the
     * outcomes into the text. For the threads that build messages before posting them to the ui
     */
    @JvmStatic
    fun awaitVerdicts(messages: List<MessageObject>?) {
        if (messages.isNullOrEmpty() || entries.isEmpty()) return
        if (Looper.myLooper() === Looper.getMainLooper() || EngineDispatch.isEngineThread()) return
        val keys = messages.mapNotNull { message ->
            getVerdict(message)?.let { Key(message.currentAccount, message.dialogId, it.owner.id) }
        }
        if (keys.isEmpty()) return
        val deadline = SystemClock.uptimeMillis() + AWAIT_BUDGET_MS
        lock.withLock {
            while (keys.any { key -> entries.any { key in it.pending } }) {
                val left = deadline - SystemClock.uptimeMillis()
                if (left <= 0 || !settled.await(left, TimeUnit.MILLISECONDS)) break
            }
        }
        for (message in messages) BlockedMessagesHelper.rebuildIfStale(message)
    }

    @JvmStatic
    fun awaitVerdicts(vararg byDialog: LongSparseArray<ArrayList<MessageObject>>?) {
        if (entries.isEmpty()) return
        val messages = ArrayList<MessageObject>()
        for (group in byDialog) {
            if (group == null) continue
            for (i in 0 until group.size()) messages.addAll(group.valueAt(i))
        }
        awaitVerdicts(messages)
    }

    @JvmStatic
    fun addMenuFooter(
        activity: ChatActivity,
        popupLayout: ActionBarPopupWindow.ActionBarPopupWindowLayout,
        selected: MessageObject?,
        group: MessageObject.GroupedMessages?,
    ) {
        val messages = group?.messages ?: listOfNotNull(selected)
        val (message, verdict) = messages.firstNotNullOfOrNull { message -> getVerdict(message)?.takeIf { it.rewriteBy != null }?.let { message to it } } ?: return
        val context = activity.context ?: return
        val resources = activity.resourceProvider
        val revert = AndroidUtilities.replaceArrows(
            AndroidUtilities.replaceSingleTag(
                "**${LocaleController.getString(R.string.InuPluginRewriteRevert)}**",
                -1, AndroidUtilities.REPLACING_TAG_TYPE_LINK, null, resources,
            ),
            true,
        )
        val text = SpannableStringBuilder(LocaleController.formatString(R.string.InuPluginRewrittenBy, verdict.rewriteBy)).append(' ').append(revert)
        val footer = ChatActivity.createMenuTextOption(context, resources, text, 13) as ViewGroup
        (footer.getChildAt(0) as TextView).setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText, resources))
        footer.background = Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_dialogButtonSelector, resources), 0, 6)
        footer.setOnClickListener {
            activity.closeMenu()
            reverted.put(Key(message.currentAccount, message.dialogId, verdict.owner.id), true)
            synchronized(this) { generation++ }
            BlockedMessagesHelper.notifyChanged(message.currentAccount)
        }
        popupLayout.addView(ActionBarPopupWindow.GapView(context, resources), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8))
        popupLayout.addView(footer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
    }

    /**
     * Service messages carry login codes, and a rule is an oracle on them even without a callback.
     * Local, date and sponsored messages have no real id; secret chats are never handed to plugins.
     * What the user wrote is never filtered, save for their channel posts
     */
    private fun isFilterable(message: MessageObject, owner: TLRPC.Message): Boolean =
        owner !is TLRPC.TL_messageService && owner.id > 0 && message.storyItem == null &&
            !(owner.out && !owner.post) && message.dialogId != UserConfig.getInstance(message.currentAccount).clientUserId &&
            !DialogObject.isEncryptedDialog(message.dialogId) && !TlFilter.isServiceMessage(owner)

    private fun computeVerdict(message: MessageObject, owner: TLRPC.Message, entries: List<Rules>, generation: Int): Verdict {
        val needs = entries.fold(0) { needs, rules -> needs or rules.needs }
        val text = owner.message.orEmpty()
        val entitiesJson = if (needs and NEEDS_ENTITIES != 0 && !owner.entities.isNullOrEmpty()) {
            PluginCompose.encodeEntities(owner.entities).toString()
        } else {
            ""
        }
        val buttons = if (needs and NEEDS_BUTTONS != 0) readButtonUrls(owner) else ""
        val peer = owner.peer_id
        val sender = (owner.from_id ?: peer)?.let(PeerSpecs::toMarkedPeerId) ?: 0L
        val peerType = when {
            peer == null || peer.user_id != 0L -> 1
            peer.channel_id != 0L && owner.post -> 4
            else -> 2
        }
        val media = readMediaType(owner.media).orEmpty()
        val key = Key(message.currentAccount, message.dialogId, owner.id)

        var hide = false
        var spoiler = false
        var rewrite: TLRPC.TL_textWithEntities? = null
        var rewriteBy: String? = null
        val keepRewrites = reverted.get(key) == null
        for (rules in entries) {
            if (!rules.session.canDispatch()) continue
            val matched = rules.session.engine.matchMessageFilters(
                text, entitiesJson, buttons, sender, peer?.let(PeerSpecs::toMarkedPeerId) ?: 0L, peerType, media, owner.fwd_from != null,
            ) ?: continue
            if (matched[0] == MATCHED_HIDE) {
                hide = true
                break
            }
            if (matched[0] == MATCHED_SPOILER) spoiler = true
            if (matched.size == 1) continue
            val outcome = rules.outcomes.get(key)
            if (outcome == null || outcome.generation != rules.generation || outcome.editDate != owner.edit_date) {
                enqueue(rules, Job(key, owner, matched.copyOfRange(1, matched.size), rules.generation))
            }
            if (outcome == null) continue
            if (outcome.hide) {
                hide = true
                break
            }
            if (outcome.spoiler) spoiler = true
            if (keepRewrites && rewrite == null && outcome.rewrite != null) {
                rewrite = outcome.rewrite
                rewriteBy = rules.session.manifest.name
            }
        }
        return Verdict(generation, owner, owner.edit_date, hide, spoiler, rewrite, rewriteBy)
    }

    private fun enqueue(rules: Rules, job: Job) {
        lock.withLock {
            rules.queue[job.key] = job
            rules.pending += job.key
            if (rules.scheduled) return
            rules.scheduled = true
        }
        EngineDispatch.onEngine(rules.session, onDropped = { drop(rules) }) { drain(rules) }
    }

    private fun drop(rules: Rules) = lock.withLock {
        rules.queue.clear()
        rules.pending.clear()
        rules.scheduled = false
        settled.signalAll()
    }

    private fun drain(rules: Rules) {
        val jobs = lock.withLock {
            rules.scheduled = false
            rules.queue.values.toList().also { rules.queue.clear() }
        }
        val policy = TlFilter.policyFor(rules.session.permissions)
        val changed = HashSet<Int>()
        for (job in jobs) {
            val answer = if (rules.session.canDispatch()) {
                rules.session.engine.filterMessage(job.callbackIds, job.key.account, TlJson.toJson(job.owner, policy).toString())
            } else {
                ""
            }
            val outcome = readOutcome(rules.session, job, answer)
            val previous = rules.outcomes.put(job.key, outcome)
            if (outcome.isVisible || previous?.isVisible == true) {
                changed += job.key.account
                synchronized(this) { generation++ }
            }
            lock.withLock {
                if (job.key !in rules.queue) rules.pending -= job.key
                settled.signalAll()
            }
        }
        if (changed.isNotEmpty()) notifyChanged(changed)
    }

    private fun readOutcome(session: PluginSession, job: Job, answer: String): Outcome {
        val rewrite = if (answer.length > 1) readRewrite(session, answer.substring(1)) else null
        return Outcome(job.generation, job.owner.edit_date, answer.startsWith('H'), answer.startsWith('S'), rewrite)
    }

    private fun readRewrite(session: PluginSession, json: String): TLRPC.TL_textWithEntities? = try {
        val parsed = JSONObject(json)
        TLRPC.TL_textWithEntities().apply {
            text = AndroidUtilities.getSafeString(parsed.getString("text"))
            entities = PluginCompose.readEntities(parsed.optJSONArray("entities"))
        }
    } catch (e: Exception) {
        session.log.w("filters", "a rewrite did not read, showing the message's own text", e)
        null
    }

    @Suppress("DEPRECATION")
    private fun readButtonUrls(owner: TLRPC.Message): String {
        val markup = owner.reply_markup as? TLRPC.TL_replyInlineMarkup ?: return ""
        return markup.rows.flatMap { it.buttons }.mapNotNull { it.url?.takeIf(String::isNotEmpty) }.joinToString("\n")
    }

    private fun readMediaType(media: TLRPC.MessageMedia?): String? = when (media) {
        null, is TLRPC.TL_messageMediaEmpty -> null
        is TLRPC.TL_messageMediaStory -> "story"
        is TLRPC.TL_messageMediaGiveaway, is TLRPC.TL_messageMediaGiveawayResults -> "giveaway"
        is TLRPC.TL_messageMediaInvoice -> "invoice"
        else -> PluginSends.readMediaKind(media) ?: "other"
    }
}
