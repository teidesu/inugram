package desu.inugram.helpers.plugins.telegram

import android.text.SpannableStringBuilder
import android.text.Spanned
import desu.inugram.core.plugins.PluginRefusal
import desu.inugram.core.plugins.PluginWire.refuse
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.ReadsListener
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlHandles
import desu.inugram.helpers.plugins.tl.TlJson
import desu.inugram.helpers.plugins.tl.TlReflect
import desu.inugram.helpers.security.ParanoiaHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_forum
import org.telegram.ui.Components.TypefaceSpan
import org.telegram.ui.Components.URLSpanNoUnderlineBold

/**
 * Kotlin side of the `Account` read surface (rust: `reads.rs`). [read] is a lookup in what
 * [MessagesController] already holds; [resolve] and [fetch] may go to the network.
 *
 * Called on [EngineDispatch.scheduler] from a JNI upcall, so **an asynchronous op never answers
 * inline**: settling re-enters the engine, which from inside an upcall is a process abort.
 * Everything goes through [answer], which posts.
 *
 * What is handed out is read-only and plugin-lifetime, the objects belonging to the app's own
 * caches. Some members are `internal` because [PluginWrites] is the other half of the same surface.
 *
 * **The caches are read off the UI thread on purpose.** `dialogs_dict`/`dialogMessage` are
 * `LongSparseArray`s the app mutates from it, and stock reads them off-thread itself. A
 * *synchronous* getter cannot hop, so a lost race answers `null` - already a legal answer, a miss
 * and a nonexistent peer being the same thing.
 */
object PluginReads {
    // keep in sync with rust `reads::OP_*`
    const val OP_ME = 0
    const val OP_USER = 1
    const val OP_CHAT = 2
    const val OP_PEER = 3
    const val OP_DIALOG = 4
    const val OP_MESSAGE = 5
    const val OP_USERS = 6
    const val OP_CHATS = 7
    const val OP_MESSAGES = 8
    const val OP_INPUT_PEER = 9
    const val OP_DRAFT = 10
    const val OP_USER_FULL = 11
    const val OP_CHAT_FULL = 12
    const val OP_HISTORY = 13
    const val OP_DIALOGS = 14
    const val OP_TOPICS = 15
    const val OP_DIALOGS_CACHED = 16
    const val OP_CHAT_FOLDERS = 17
    const val OP_FETCH_MESSAGES = 18
    const val OP_DIALOG_MUTED = 19
    const val OP_TOPIC = 20
    const val OP_MESSAGE_PREVIEW = 21

    /** stock's own `NotificationsController.spoilerChars`, in its order */
    private val SPOILER_CHARS = charArrayOf('\u280C', '\u2862', '\u2891', '\u2828', '\u2825', '\u282E', '\u2851')

    /** what `archive` selects past exclude (`0`), keep in sync with `reads.js` */
    private const val ARCHIVE_ONLY = 1
    private const val ARCHIVE_KEEP = 2

    /** `chatFolderId` is optional, and every folder id including `0` is a real one */
    private const val NO_CHAT_FOLDER = -1

    /** what telegram itself accepts for one page, and what an omitted `limit` asks for */
    private const val PAGE_LIMIT = 100

    /**
     * The dialog id the message reads read as the common message box rather than as a dialog:
     * telegram numbers every user chat and basic group out of one sequence per account, so an id
     * from one of those names a message on its own. No dialog has it, and nothing else accepts it.
     */
    private const val COMMON_BOX = 0L

    /** the cap `common.d.ts` states for every api array, mirrored from rust `arguments::ARRAY_LIMIT` */
    private const val ARRAY_LIMIT = 65536

    fun listenerFor(session: PluginSession): ReadsListener =
        object : ReadsListener {
            override fun accountRead(accountId: Int, op: Int, arg: String): String =
                read(session, accountId, op, arg)

            override fun resolvePeer(accountId: Int, requestId: Long, spec: String, kind: Int): String? =
                resolve(session, accountId, requestId, spec, kind)

            override fun accountFetch(accountId: Int, requestId: Long, op: Int, peer: String, args: String, cursor: String): String? =
                fetch(session, accountId, requestId, op, peer, args, cursor)
        }

    private fun read(session: PluginSession, accountId: Int, op: Int, arg: String): String {
        val handles = session.tl
        val controller = PeerSpecs.controllerFor(accountId)
            ?: return PeerSpecs.noAccountWire("account read", accountId)
        return try {
            when (op) {
                OP_ME -> mint(handles, UserConfig.getInstance(accountId).getCurrentUser())
                OP_USER -> mint(handles, findUser(controller, accountId, arg))
                OP_CHAT -> mint(handles, findChat(controller, accountId, arg))
                OP_PEER -> mint(handles, findPeer(controller, accountId, arg))
                OP_DIALOG -> mint(handles, findDialog(controller, accountId, arg))
                OP_MESSAGE -> {
                    val (spec, rest) = PeerSpecs.splitOnce(arg)
                    mint(handles, findMessage(controller, accountId, spec, rest.toIntOrNull()))
                }
                OP_USERS -> mintEach(handles, PeerSpecs.splitList(arg).map { findUser(controller, accountId, it) })
                OP_CHATS -> mintEach(handles, PeerSpecs.splitList(arg).map { findChat(controller, accountId, it) })
                OP_MESSAGES -> {
                    val (spec, rest) = PeerSpecs.splitOnce(arg)
                    mintEach(handles, PeerSpecs.splitList(rest).map { findMessage(controller, accountId, spec, it.toIntOrNull()) })
                }
                OP_INPUT_PEER -> {
                    val (spec, rest) = PeerSpecs.splitOnce(arg)
                    inputPeerWire(controller, accountId, spec, rest.toIntOrNull() ?: PeerSpecs.KIND_PEER, policyOf(session))
                }
                OP_DRAFT -> {
                    val (spec, rest) = PeerSpecs.splitOnce(arg)
                    draftWire(controller, accountId, spec, rest.toLongOrNull() ?: 0L, policyOf(session))
                }
                OP_DIALOG_MUTED -> {
                    val (spec, rest) = PeerSpecs.splitOnce(arg)
                    PluginWire.encodeBool(isMuted(controller, accountId, spec, rest.toLongOrNull() ?: 0L))
                }
                OP_TOPIC -> {
                    val (spec, rest) = PeerSpecs.splitOnce(arg)
                    mint(handles, findTopic(controller, accountId, spec, rest.toLongOrNull() ?: 0L))
                }
                OP_MESSAGE_PREVIEW -> {
                    val (flag, wire) = PeerSpecs.splitOnce(arg)
                    previewMessage(handles, accountId, wire, hideSpoilers = flag != "0", policy = policyOf(session))
                }
                else -> PluginWire.encodeError("account read: unknown op $op")
            }
        } catch (e: PluginRefusal) {
            e.wire
        } catch (e: Exception) {
            // a LongSparseArray read that raced the app's own writer lands here, and so does a
            // reflection failure inside a mint: neither is a plugin's doing, and neither is worth
            // taking the app down over
            PluginWire.encodePluginError("internal", "account read: ${e.message ?: e.toString()}")
        }
    }

    /**
     * The line the app itself would show for this message in a dialog row or a notification: a
     * media label, a service message written out, or the text. Only [MessageObject] knows it, and
     * only building one computes it; `generateLayout = false` leaves out the text layout, which is
     * the expensive half and nothing a caller here can see.
     */
    private fun previewMessage(
        handles: TlHandles,
        accountId: Int,
        wire: String,
        hideSpoilers: Boolean,
        policy: TlFilter.Policy,
    ): String {
        val message = handles.objectFromWire(wire, "previewMessage") as? TLRPC.Message
            ?: refuse("invalid-argument", "previewMessage: expected a message")
        val preview = MessageObject(accountId, message, false, false).messageText ?: ""
        val text = preview.toString()
        val json = JSONObject()
        // the app writes an ordinary text message's preview as the text itself, unspanned, so the
        // formatting is the message's own entities rather than anything the preview carries
        val own = message.message.takeIf { !it.isNullOrEmpty() && it == text }
        val entities = if (own != null) ownEntities(message, policy) else spanEntities(accountId, preview, policy)
        if (hideSpoilers && own != null) {
            val (masked, ranges) = maskSpoilers(message, text)
            json.put("text", masked)
            dropMasked(entities, ranges)?.let { json.put("entities", it) }
        } else {
            json.put("text", text)
            entities?.let { json.put("entities", it) }
        }
        return PluginWire.encodeJson(json.toString())
    }

    /** the message's own entities, which are the preview's exactly when the preview is its text */
    private fun ownEntities(message: TLRPC.Message, policy: TlFilter.Policy): JSONArray? {
        val entities = message.entities?.takeIf { it.isNotEmpty() } ?: return null
        val out = JSONArray()
        for (entity in entities) TlJson.valueToJson(entity, policy)?.let { out.put(it) }
        return out.takeIf { it.length() > 0 }
    }

    /** an entity over a masked range would be describing text that is no longer there */
    private fun dropMasked(entities: JSONArray?, masked: List<IntRange>): JSONArray? {
        if (entities == null || masked.isEmpty()) return entities
        val out = JSONArray()
        for (index in 0 until entities.length()) {
            val entity = entities.optJSONObject(index) ?: continue
            val offset = entity.optInt("offset")
            val length = entity.optInt("length")
            if (masked.any { offset < it.last + 1 && it.first < offset + length }) continue
            out.put(entity)
        }
        return out.takeIf { it.length() > 0 }
    }

    /**
     * A preview that is *not* the message's text - a service message written out - is formatted
     * with android spans rather than entities, so this reads them back out through stock's own
     * converter. That one is the composer's, so markdown parsing is off: the text is the app's
     * output, not something a user typed. [TypefaceSpan] is the one it does not carry, being the
     * composer's bold marker rather than the one [MessageObject] writes.
     */
    private fun spanEntities(accountId: Int, preview: CharSequence, policy: TlFilter.Policy): JSONArray? {
        if (preview !is Spanned) return null
        val copy = arrayOf<CharSequence>(SpannableStringBuilder(preview))
        val entities = ArrayList<TLRPC.MessageEntity>()
        MediaDataController.getInstance(accountId).getEntities(copy, true, false)?.let { entities.addAll(it) }
        for (span in preview.getSpans(0, preview.length, TypefaceSpan::class.java)) {
            if (span.isBold) entities.add(boldOver(preview, span))
        }
        // the span stock writes a name with in a service message, and one `getEntities` has no case
        // for: it is a `URLSpan` carrying a peer id rather than a link, and bold is all of it a
        // preview can carry
        for (span in preview.getSpans(0, preview.length, URLSpanNoUnderlineBold::class.java)) {
            entities.add(boldOver(preview, span))
        }
        val out = JSONArray()
        for (entity in entities) {
            if (entity.offset < 0 || entity.length <= 0) continue
            TlJson.valueToJson(entity, policy)?.let { out.put(it) }
        }
        return out.takeIf { it.length() > 0 }
    }

    private fun boldOver(preview: Spanned, span: Any): TLRPC.MessageEntity =
        TLRPC.TL_messageEntityBold().apply {
            offset = preview.getSpanStart(span)
            length = preview.getSpanEnd(span) - offset
        }

    /**
     * What `NotificationsController.replaceSpoilers` does to its own preview, which is private
     * there. Stock only reaches it where the preview *is* the raw text: entity offsets are counted
     * against that text, so a media label would be mangled by them.
     */
    private fun maskSpoilers(message: TLRPC.Message, preview: String): Pair<String, List<IntRange>> {
        val entities = message.entities
        if (preview.isEmpty() || message.message != preview || entities == null) return preview to emptyList()
        val chars = preview.toCharArray()
        val masked = ArrayList<IntRange>()
        for (entity in entities) {
            if (entity !is TLRPC.TL_messageEntitySpoiler) continue
            if (entity.offset < 0 || entity.offset + entity.length > chars.size) continue
            for (i in 0 until entity.length) chars[entity.offset + i] = SPOILER_CHARS[i % SPOILER_CHARS.size]
            masked.add(entity.offset until entity.offset + entity.length)
        }
        return String(chars) to masked
    }

    /** the plugin asked for this object, so its scalars go with the handle: reading them is what it will do next */
    internal fun mint(handles: TlHandles, value: TLObject?, fields: List<String>? = null): String {
        if (value == null) return PluginWire.encodeNull()
        return handles.mintWireForPlugin(value, readOnly = true, fields = fields)
    }

    internal fun mintEach(handles: TlHandles, values: List<TLObject?>, fields: List<String>? = null): String =
        values.joinToString(PeerSpecs.LIST_SEPARATOR) { mint(handles, it, fields) }

    private fun findUser(controller: MessagesController, accountId: Int, spec: String): TLRPC.User? {
        val id = PeerSpecs.dialogIdOf(controller, accountId, spec) ?: return null
        // stock's getUser(0) answers with the logged-in user, which would make an unresolvable spec read as getMe(); the *real* self id falls back to it deliberately
        if (id <= 0) return null
        if (id == UserConfig.getInstance(accountId).getClientUserId()) {
            return controller.getUser(id) ?: UserConfig.getInstance(accountId).getCurrentUser()
        }
        return controller.getUser(id)
    }

    private fun findChat(controller: MessagesController, accountId: Int, spec: String): TLRPC.Chat? {
        val id = PeerSpecs.dialogIdOf(controller, accountId, spec) ?: return null
        if (id >= 0) return null
        return controller.getChat(-id)
    }

    private fun findPeer(controller: MessagesController, accountId: Int, spec: String): TLObject? {
        val id = PeerSpecs.dialogIdOf(controller, accountId, spec) ?: return null
        if (id == 0L) return null
        if (id > 0) return findUser(controller, accountId, spec)
        return controller.getUserOrChat(id)
    }

    private fun findDialog(controller: MessagesController, accountId: Int, spec: String): TLRPC.Dialog? {
        val id = PeerSpecs.dialogIdOf(controller, accountId, spec) ?: return null
        return controller.dialogs_dict.get(id)
    }

    /**
     * Stock's own answer, not the dialog's `notify_settings`: that field is only the per-dialog
     * override, and whether it means muted depends on the account's default for that kind of peer,
     * and on the topic when there is one. A dialog the app does not know is not muted.
     */
    private fun isMuted(controller: MessagesController, accountId: Int, spec: String, topicId: Long): Boolean {
        val id = PeerSpecs.dialogIdOf(controller, accountId, spec) ?: return false
        return controller.isDialogMuted(id, topicId)
    }

    /**
     * The topics the app has already loaded for a forum, which is what the app itself draws from;
     * a topic it has never loaded is a miss rather than a fetch, the way every `*Cached` read here
     * behaves. [TopicsController.findTopic] takes the bare chat id, never the dialog id.
     */
    private fun findTopic(
        controller: MessagesController,
        accountId: Int,
        spec: String,
        topicId: Long,
    ): TLRPC.TL_forumTopic? {
        val id = PeerSpecs.dialogIdOf(controller, accountId, spec) ?: return null
        if (id >= 0) return null
        return controller.topicsController.findTopic(-id, topicId)
    }

    /**
     * Memory first, then the app's own sqlite, which is where a message a plugin asks for actually
     * lives: [MessagesController] holds only the chat list's own last message per dialog.
     *
     * **The storage read blocks** this turn on `storageQueue`, which is what lets a synchronous
     * read answer at all rather than only for the handful of messages memory holds. It is the same
     * reader the asynchronous path uses, so the two agree on what a stored message is.
     */
    private fun findMessage(
        controller: MessagesController,
        accountId: Int,
        spec: String,
        messageId: Int?,
    ): TLRPC.Message? {
        if (messageId == null) return null
        val named = PeerSpecs.dialogIdOf(controller, accountId, spec) ?: return null
        val dialogId = named.takeIf { it != COMMON_BOX }
        cachedMessage(controller, dialogId, messageId)?.let { return it }
        return onStorageQueue(accountId) { readStoredMessages(accountId, dialogId, listOf(messageId)) }[messageId]
    }

    /**
     * Runs [read] on the account's storage queue and waits for it. Only ever called from a plugin's
     * own turn - the plugin queue or a caller thread - never from `storageQueue` itself, which
     * would be waiting on the thread that has to run it.
     */
    private fun <T> onStorageQueue(accountId: Int, read: () -> T): T {
        val storage = MessagesStorage.getInstance(accountId)
        val latch = CountDownLatch(1)
        val answer = AtomicReference<T>()
        storage.storageQueue.postRunnable {
            try {
                answer.set(read())
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        return answer.get()
    }

    /**
     * What [MessagesController] holds is the chat list's own last message per dialog and nothing
     * else, in two views of the same objects: [MessagesController.dialogMessage] keyed by dialog,
     * and [MessagesController.dialogMessagesByIds] keyed by the bare message id.
     *
     * The second is the one the common box wants, and also the one that needs guarding: a channel
     * numbers its own messages from 1, so every channel's ids collide there with every other
     * channel's and with the common box. Whatever it answers therefore only counts once the dialog
     * it actually belongs to is the dialog that was asked about - and a common-box read
     * ([dialogId] null) refuses a channel message outright rather than handing back whichever one
     * happens to carry that number.
     */
    private fun cachedMessage(controller: MessagesController, dialogId: Long?, messageId: Int): TLRPC.Message? {
        if (dialogId != null) {
            val cached = controller.dialogMessage.get(dialogId)
            if (cached != null) {
                for (message in cached) {
                    if (message != null && message.getId() == messageId) return message.messageOwner
                }
            }
        }
        val byId = controller.dialogMessagesByIds.get(messageId)?.messageOwner ?: return null
        if (dialogId != null) return byId.takeIf { MessageObject.getDialogId(it) == dialogId }
        return byId.takeIf { isCommonBox(it) }
    }

    /** a channel's messages are numbered per channel; everything else shares one sequence per account */
    private fun isCommonBox(message: TLRPC.Message): Boolean = MessageObject.getChannelId(message) == 0L

    /** a fresh `InputPeer`/`InputUser`/`InputChannel` as plain json, never a handle over the whole user or chat behind it */
    private fun inputPeerWire(
        controller: MessagesController,
        accountId: Int,
        spec: String,
        kind: Int,
        policy: TlFilter.Policy,
    ): String = when (val built = PeerSpecs.buildInputPeer(controller, accountId, spec, kind)) {
        is PeerSpecs.Built.Missing -> PluginWire.encodeNull()
        is PeerSpecs.Built.WrongKind -> PeerSpecs.wrongKind(spec, built.kind)
        is PeerSpecs.Built.Peer -> PluginWire.encodeJson(TlJson.toJson(built.value, policy).toString())
    }

    private fun policyOf(session: PluginSession): TlFilter.Policy = TlFilter.policyFor(session.permissions)

    /** a `TextWithEntities`, the shape `setDraft` takes back: the rest of a draft is app state rather than the text the input field shows */
    private fun draftWire(
        controller: MessagesController,
        accountId: Int,
        spec: String,
        topicId: Long,
        policy: TlFilter.Policy,
    ): String {
        val dialogId = PeerSpecs.dialogIdOf(controller, accountId, spec) ?: return PluginWire.encodeNull()
        if (dialogId == 0L) return PluginWire.encodeNull()
        val draft = MediaDataController.getInstance(accountId).getDraft(dialogId, topicId)
        if (draft == null || draft is TLRPC.TL_draftMessageEmpty) return PluginWire.encodeNull()
        // through the one materialization point rather than field by field: this is the only text a
        // read hands over outside [TlHandles]/[TlJson], and a rule they gain later has to reach it
        val snapshot = TlJson.toJson(draft, policy)
        val json = JSONObject()
        json.put("text", snapshot.optString("message"))
        snapshot.optJSONArray("entities")?.let { json.put("entities", it) }
        return PluginWire.encodeJson(json.toString())
    }

    /** only for a username: an id with no cached entity has no `access_hash` anywhere reachable, the server handing those out attached to an entity rather than on request */
    private fun resolve(
        session: PluginSession,
        accountId: Int,
        requestId: Long,
        spec: String,
        kind: Int,
    ): String? {
        val controller = PeerSpecs.controllerFor(accountId)
            ?: return PeerSpecs.noAccountWire("resolvePeer", accountId)
        if (spec.isEmpty() || spec[0] != PeerSpecs.SPEC_USERNAME) {
            return PluginWire.encodePluginError(
                "not-found",
                "resolvePeer: this peer is not cached, and only a username can be looked up",
            )
        }
        val request = TLRPC.TL_contacts_resolveUsername()
        request.username = spec.substring(1)
        TlReflect.syncFlagsDeep(request)
        // fail rather than let stock retry a server error: the promise settles once, and a request the connection layer keeps re-sending is one this never answers
        val flags = ConnectionsManager.RequestFlagFailOnServerErrors
        // through the bypass lease, or a plugin holding interceptRpc(contacts.resolveUsername) that resolves from inside its own middleware dispatches into itself without bound
        PluginRpc.sendWithoutInterceptors(accountId, request, flags) { response, error ->
            EngineDispatch.settle(session, QuickJs.SETTLE_READS, requestId, "resolvePeer") {
                settleWire(controller, accountId, response, error, spec, kind, policyOf(session))
            }
        }
        return null
    }

    private fun settleWire(
        controller: MessagesController,
        accountId: Int,
        response: TLObject?,
        error: TLRPC.TL_error?,
        spec: String,
        kind: Int,
        policy: TlFilter.Policy,
    ): String {
        if (error != null) return encodeRpcErrorWire(error)
        val resolved = response as? TLRPC.TL_contacts_resolvedPeer
            ?: return PluginWire.encodePluginError("not-found", "resolvePeer: nothing resolved for ${PeerSpecs.describeSpec(spec)}")
        // into the app's own caches, so the synchronous half starts answering for this peer too
        controller.putUsers(resolved.users, false)
        controller.putChats(resolved.chats, false)
        return when (val built = PeerSpecs.buildInputPeer(controller, accountId, spec, kind)) {
            is PeerSpecs.Built.Missing -> PluginWire.encodePluginError(
                "not-found",
                "resolvePeer: nothing resolved for ${PeerSpecs.describeSpec(spec)}",
            )
            is PeerSpecs.Built.WrongKind -> PeerSpecs.wrongKind(spec, built.kind)
            is PeerSpecs.Built.Peer -> PluginWire.encodeJson(TlJson.toJson(built.value, policy).toString())
        }
    }

    private fun fetch(
        session: PluginSession,
        accountId: Int,
        requestId: Long,
        op: Int,
        peer: String,
        args: String,
        cursor: String,
    ): String? {
        val controller = PeerSpecs.controllerFor(accountId)
            ?: return PeerSpecs.noAccountWire("account fetch", accountId)
        return try {
            val call = Fetch(session, controller, accountId, requestId, peer, JSONObject(args), cursor)
            when (op) {
                OP_USER_FULL -> fetchUserFull(call)
                OP_CHAT_FULL -> fetchChatFull(call)
                OP_HISTORY -> fetchHistory(call)
                OP_DIALOGS -> fetchDialogs(call)
                OP_TOPICS -> fetchTopics(call)
                OP_DIALOGS_CACHED -> fetchCachedDialogs(call)
                OP_CHAT_FOLDERS -> fetchChatFolders(call)
                OP_FETCH_MESSAGES -> fetchMessages(call)
                else -> PluginWire.encodePluginError("internal", "account fetch: unknown op $op")
            }
        } catch (e: PluginRefusal) {
            e.wire
        } catch (e: Exception) {
            PluginWire.encodePluginError("internal", "account fetch: ${e.message ?: e.toString()}")
        }
    }


    /** after a reload the plugin runs on a new engine whose request ids restart, so a stale settle must not reach it */
    private fun answer(call: Fetch, produce: () -> String) {
        EngineDispatch.settle(call.session, QuickJs.SETTLE_READS, call.requestId, "account fetch", produce = produce)
    }

    /**
     * None of these responses owns a `NativeByteBuffer`, so crossing the queue hop needs no
     * `disableFree` the way an intercepted response does. It goes out through the same bypass lease
     * [PluginWrites.send] takes: a plugin holding `interceptRpc(messages.getHistory)` that calls
     * `getHistory` from its own middleware would otherwise dispatch into itself once per page.
     */
    private fun send(call: Fetch, request: TLObject, produce: (TLObject?) -> String): String? {
        TlReflect.syncFlagsDeep(request)
        val flags = ConnectionsManager.RequestFlagFailOnServerErrors
        PluginRpc.sendWithoutInterceptors(call.accountId, request, flags) { response, error ->
            answer(call) {
                if (error != null) encodeRpcErrorWire(error)
                else produce(response)
            }
        }
        return null
    }

    private fun clampLimit(limit: Int): Int = if (limit in 1..PAGE_LIMIT) limit else PAGE_LIMIT



    private fun notCached(spec: String): Nothing = refuse("not-found", "${PeerSpecs.describeSpec(spec)} is not cached")

    /**
     * a page cursor's payload, which rust mints and hands back opaque: what it means is decided
     * here and nowhere else, so a `0` is both "the caller named no cursor" and "this page starts
     * at the beginning", which telegram spells the same way.
     */
    @JvmInline
    private value class Cursor(private val fields: List<String>) {
        fun int(index: Int): Int = fields.getOrNull(index)?.toIntOrNull() ?: 0

        fun long(index: Int): Long = fields.getOrNull(index)?.toLongOrNull() ?: 0L
    }

    private class Fetch(
        val session: PluginSession,
        val controller: MessagesController,
        val accountId: Int,
        val requestId: Long,
        val spec: String,
        args: JSONObject,
        cursor: String,
    ) : JsonArgs(args) {
        val handles: TlHandles get() = session.tl

        val limit: Int get() = clampLimit(int("limit"))

        val from = Cursor(cursor.split(','))

        fun peer(spec: String = this.spec, kind: Int = PeerSpecs.KIND_PEER): TLObject =
            PeerSpecs.requireInputPeer(controller, accountId, spec, kind)

        fun cache(users: ArrayList<TLRPC.User>, chats: ArrayList<TLRPC.Chat>) {
            controller.putUsers(users, false)
            controller.putChats(chats, false)
        }
    }

    private fun fetchUserFull(call: Fetch): String? {
        val dialogId = PeerSpecs.dialogIdOf(call.controller, call.accountId, call.spec)
        val cached = dialogId?.takeIf { it > 0 }?.let { call.controller.getUserFull(it) }
        if (cached != null) {
            answer(call) { mint(call.handles, cached) }
            return null
        }
        val request = TLRPC.TL_users_getFullUser()
        request.id = call.peer(kind = PeerSpecs.KIND_USER) as TLRPC.InputUser
        return send(call, request) { response ->
            val full = (response as? TLRPC.TL_users_userFull) ?: return@send PluginWire.encodeNull()
            call.cache(full.users, full.chats)
            mint(call.handles, full.full_user)
        }
    }

    private fun fetchChatFull(call: Fetch): String? {
        val spec = call.spec
        val dialogId = PeerSpecs.dialogIdOf(call.controller, call.accountId, spec) ?: notCached(spec)
        if (dialogId >= 0) throw PluginRefusal(PeerSpecs.wrongKind(spec, PeerSpecs.KIND_CHANNEL))
        val cached = call.controller.getChatFull(-dialogId)
        if (cached != null) {
            answer(call) { mint(call.handles, cached) }
            return null
        }
        val chat = call.controller.getChat(-dialogId) ?: notCached(spec)
        // a basic group has no `InputChannel` and is asked about by its bare id, which is the whole
        // reason this is two rpcs rather than one
        val request: TLObject = if (ChatObject.isChannel(chat)) {
            TLRPC.TL_channels_getFullChannel().apply {
                channel = call.peer(kind = PeerSpecs.KIND_CHANNEL) as TLRPC.InputChannel
            }
        } else {
            TLRPC.TL_messages_getFullChat().apply { chat_id = -dialogId }
        }
        return send(call, request) { response ->
            val full = (response as? TLRPC.TL_messages_chatFull) ?: return@send PluginWire.encodeNull()
            call.cache(full.users, full.chats)
            mint(call.handles, full.full_chat)
        }
    }

    private fun fetchHistory(call: Fetch): String? {
        val peer = call.peer() as TLRPC.InputPeer
        val pageLimit = call.limit
        val offsetId = call.int("offsetId")
        val minId = call.int("minId")
        val maxId = call.int("maxId")
        val topicId = call.int("topicId")
        // a topic is a thread, and its history is `messages.getReplies` - the same rpc the app sends
        // when a forum topic is opened
        val request: TLObject = if (topicId > 0) {
            TLRPC.TL_messages_getReplies().apply {
                this.peer = peer
                msg_id = topicId
                offset_id = offsetId
                limit = pageLimit
                min_id = minId
                max_id = maxId
            }
        } else {
            TLRPC.TL_messages_getHistory().apply {
                this.peer = peer
                offset_id = offsetId
                limit = pageLimit
                min_id = minId
                max_id = maxId
            }
        }
        return send(call, request) { response ->
            val messages = (response as? TLRPC.messages_Messages) ?: return@send ""
            call.cache(messages.users, messages.chats)
            mintEach(call.handles, messages.messages)
        }
    }

    /**
     * Memory, then the app's sqlite, then the network for whatever neither had. Each step narrows
     * the id list it hands on, and exactly one [answer] is reached however far it gets.
     */
    private fun fetchMessages(call: Fetch): String? {
        val spec = call.spec
        val named = PeerSpecs.dialogIdOf(call.controller, call.accountId, spec) ?: notCached(spec)
        val dialogId = named.takeIf { it != COMMON_BOX }
        val ids = call.ints("ids")
        val cached = ids.mapNotNull { id -> cachedMessage(call.controller, dialogId, id)?.let { id to it } }.toMap()
        if (cached.size == ids.size) {
            answerMessages(call, ids, cached)
            return null
        }

        val accountId = call.accountId
        MessagesStorage.getInstance(accountId).storageQueue.postRunnable {
            val stored = readStoredMessages(accountId, dialogId, ids.filterNot(cached::containsKey))
            // back to the plugin queue before anything touches the controller, the handle table or an rpc
            EngineDispatch.scheduler.postRunnable {
                val known = cached + stored
                val missing = ids.filterNot(known::containsKey)
                if (missing.isEmpty()) {
                    answerMessages(call, ids, known)
                } else {
                    requestMessages(call, dialogId, ids, known, missing)
                }
            }
        }
        return null
    }

    /**
     * what the app already holds for [ids] - memory, then `messages_v2` - for a caller that needs it
     * now. Never reaches the network, so a miss is an answer rather than a wait: [done] runs on
     * globalQueue either way.
     */
    internal fun loadLocalMessages(accountId: Int, dialogId: Long, ids: List<Int>, done: (Map<Int, TLRPC.Message>) -> Unit) {
        val controller = MessagesController.getInstance(accountId)
        val cached = ids.mapNotNull { id -> cachedMessage(controller, dialogId, id)?.let { id to it } }.toMap()
        if (cached.size == ids.size) return done(cached)
        MessagesStorage.getInstance(accountId).storageQueue.postRunnable {
            val stored = readStoredMessages(accountId, dialogId, ids.filterNot(cached::containsKey))
            EngineDispatch.scheduler.postRunnable { done(cached + stored) }
        }
    }

    private fun answerMessages(call: Fetch, ids: List<Int>, found: Map<Int, TLRPC.Message>) {
        answer(call) { mintEach(call.handles, ids.map(found::get)) }
    }

    /**
     * `messages_v2` is keyed by `(mid, uid)` and carries `is_channel`, which is how stock itself
     * reads a common-box message by id alone. Every id here came through `toMessageId`, so it is an
     * integer before it reaches the statement.
     */
    private fun readStoredMessages(accountId: Int, dialogId: Long?, ids: List<Int>): Map<Int, TLRPC.Message> {
        if (ids.isEmpty()) return emptyMap()
        val database = MessagesStorage.getInstance(accountId).getDatabase() ?: return emptyMap()
        val selfId = UserConfig.getInstance(accountId).getClientUserId()
        val scope = if (dialogId != null) "uid = $dialogId" else "is_channel = 0"
        val found = HashMap<Int, TLRPC.Message>()
        try {
            val cursor = database.queryFinalized(
                "SELECT data, mid, date, uid FROM messages_v2 WHERE mid IN (${ids.joinToString(",")}) AND $scope",
            )
            try {
                while (cursor.next()) {
                    val data = cursor.byteBufferValue(0) ?: continue
                    val message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false)
                    message?.readAttachPath(data, selfId)
                    data.reuse()
                    if (message == null) continue
                    message.id = cursor.intValue(1)
                    message.date = cursor.intValue(2)
                    message.dialog_id = cursor.longValue(3)
                    found[message.id] = message
                }
            } finally {
                cursor.dispose()
            }
        } catch (e: Exception) {
            // stock's own thread, and a read that failed here is a miss the network step covers
            return found
        }
        return found
    }

    /**
     * `channels.getMessages` for a channel, because its ids mean nothing without it, and
     * `messages.getMessages` for everything else - including a named user or basic group, whose
     * ids are common-box ids anyway. A named peer still filters what comes back, so an id that
     * belongs to a different dialog answers `null` rather than that other message.
     */
    private fun requestMessages(
        call: Fetch,
        dialogId: Long?,
        ids: List<Int>,
        known: Map<Int, TLRPC.Message>,
        missing: List<Int>,
    ) {
        val chat = dialogId?.takeIf { it < 0 }?.let { call.controller.getChat(-it) }
        val request: TLObject = if (chat != null && ChatObject.isChannel(chat)) {
            TLRPC.TL_channels_getMessages().apply {
                channel = call.peer(kind = PeerSpecs.KIND_CHANNEL) as TLRPC.InputChannel
                id.addAll(missing)
            }
        } else {
            TLRPC.TL_messages_getMessages().apply { id.addAll(missing) }
        }
        try {
            send(call, request) { response ->
                val messages = response as? TLRPC.messages_Messages ?: return@send mintEach(call.handles, ids.map(known::get))
                call.cache(messages.users, messages.chats)
                // an id the account cannot see comes back as `messageEmpty`, which is a `null` in
                // the slot it was asked about rather than a message
                val fetched = messages.messages
                    .filter { it !is TLRPC.TL_messageEmpty && (dialogId == null || MessageObject.getDialogId(it) == dialogId) }
                    .associateBy { it.id }
                mintEach(call.handles, ids.map { known[it] ?: fetched[it] })
            }
        } catch (e: PluginRefusal) {
            answer(call) { e.wire }
        }
    }

    private fun fetchDialogs(call: Fetch): String? {
        val pageLimit = call.limit
        val fields = call.strings("fields")
        val from = call.from
        val request = TLRPC.TL_messages_getDialogs()
        request.folder_id = call.int("folderId")
        request.limit = pageLimit
        request.offset_date = from.int(0)
        request.offset_id = from.int(1)
        val offsetDialog = from.long(2)
        // through the one decoder, so a cursor whose peer left the cache pages from the date alone
        // rather than from the zero `access_hash` stock's own getInputPeer would invent
        request.offset_peer =
            when (val built = PeerSpecs.buildInputPeer(call.controller, call.accountId, "${PeerSpecs.SPEC_DIALOG_ID}$offsetDialog", PeerSpecs.KIND_PEER)) {
                is PeerSpecs.Built.Peer -> built.value as TLRPC.InputPeer
                else -> TLRPC.TL_inputPeerEmpty()
            }
        return send(call, request) { response ->
            val page = (response as? TLRPC.messages_Dialogs) ?: return@send PeerSpecs.LIST_SEPARATOR
            call.cache(page.users, page.chats)
            val last = page.dialogs.lastOrNull()
            // a non-slice answer *is* the whole list, and a short slice is its end
            val cursor = if (last == null || page !is TLRPC.TL_messages_dialogsSlice || page.dialogs.size < pageLimit) {
                ""
            } else {
                val dialogId = DialogObject.getPeerDialogId(last.peer)
                val date = page.messages.firstOrNull {
                    it.id == last.top_message && DialogObject.getPeerDialogId(it.peer_id) == dialogId
                }?.date ?: 0
                "$date,${last.top_message},$dialogId"
            }
            cursor + PeerSpecs.LIST_SEPARATOR + mintEach(call.handles, page.dialogs, fields)
        }
    }

    private fun fetchTopics(call: Fetch): String? {
        val spec = call.spec
        val dialogId = PeerSpecs.dialogIdOf(call.controller, call.accountId, spec) ?: notCached(spec)
        val chat = if (dialogId < 0) call.controller.getChat(-dialogId) ?: notCached(spec) else null
        if (!ChatObject.isForum(chat)) refuse("invalid-argument", "${PeerSpecs.describeSpec(spec)} is not a forum")
        val pageLimit = call.limit
        val request = TL_forum.TL_messages_getForumTopics()
        request.peer = call.peer() as TLRPC.InputPeer
        request.limit = pageLimit
        val from = call.from
        request.offset_date = from.int(0)
        request.offset_id = from.int(1)
        request.offset_topic = from.int(2)
        return send(call, request) { response ->
            val page = (response as? TLRPC.TL_messages_forumTopics) ?: return@send PeerSpecs.LIST_SEPARATOR
            call.cache(page.users, page.chats)
            val last = page.topics.lastOrNull()
            val cursor = if (last == null || page.topics.size < pageLimit) {
                ""
            } else {
                val date = page.messages.firstOrNull { it.id == last.top_message }?.date ?: last.date
                "$date,${last.top_message},${last.id}"
            }
            cursor + PeerSpecs.LIST_SEPARATOR + mintEach(call.handles, page.topics)
        }
    }

    /**
     * The chat list the app already holds. No network - but asynchronous all the same, because
     * `allDialogs`, `dialogsByFolder` and `dialogFilters` are plain `ArrayList`s the **ui thread**
     * rebuilds: `sortDialogs` clears every folder list and refills it, so a globalQueue reader
     * would be walking a list halfway through a rebuild. Hopping is what makes the read safe.
     *
     * The alternative - a snapshot the ui thread republishes - would put a copy of every list on
     * `dialogsNeedReload`, which fires on every batch of arriving messages whether or not any
     * plugin ever asks. This way the copy costs one hop per call and nothing at all when idle.
     *
     * `archive` and `chatFolderId` are exclusive, which `reads.js` refuses before this is reached:
     * a folder has already decided whether it shows archived chats, so honouring the default
     * `'exclude'` on top of one would quietly drop what that folder was set up to keep.
     */
    private fun fetchCachedDialogs(call: Fetch): String? {
        val archive = call.int("archive")
        val chatFolderId = if (call.json.isNull("chatFolderId")) NO_CHAT_FOLDER else call.int("chatFolderId")
        val limit = call.int("limit")
        val fields = call.strings("fields")
        AndroidUtilities.runOnUIThread {
            val picked = if (chatFolderId != NO_CHAT_FOLDER) {
                // `getDialogFilters`, not the field: it answers with the frozen list while the user
                // is dragging tabs about, which is the one the folder tabs are showing
                call.controller.getDialogFilters().firstOrNull { it.id == chatFolderId }?.dialogs
            } else {
                when (archive) {
                    ARCHIVE_ONLY -> call.controller.getDialogs(1)
                    ARCHIVE_KEEP -> call.controller.allDialogs
                    else -> call.controller.getDialogs(0)
                }
            }
            // the copy happens here, on the thread that owns the list; only the copy crosses back,
            // and `answer` mints it on the plugin queue where the handle table lives
            val dialogs = picked?.let { copyDialogs(call.accountId, it, limit) }
            answer(call) {
                if (dialogs == null) {
                    PluginWire.encodePluginError("not-found", "getDialogsCached: no chat folder #$chatFolderId")
                } else {
                    mintEach(call.handles, dialogs, fields)
                }
            }
        }
        return null
    }

    private fun fetchChatFolders(call: Fetch): String? {
        val policy = policyOf(call.session)
        AndroidUtilities.runOnUIThread {
            val json = chatFoldersJson(call.controller, call.accountId, policy)
            answer(call) { PluginWire.encodeJson(json) }
        }
        return null
    }

    /**
     * ui thread only. The archive row the app splices into `allDialogs` is a chat-list row rather
     * than a dialog - `TL_dialogFolder` has no peer at all - so it does not belong in an answer
     * typed as `tl.TypeDialog`.
     *
     * A secret chat goes for the reason every other read drops one: `common.d.ts` says plugin code
     * never reaches them, and [PeerSpecs.dialogIdOf] answers `null` for one, so a dialog handed out
     * here would carry an id nothing else on the surface accepts back. Their ids are *positive*,
     * so nothing about the shape of one keeps it out on its own.
     */
    private fun copyDialogs(accountId: Int, source: List<TLRPC.Dialog>, limit: Int): List<TLRPC.Dialog> {
        val cap = if (limit in 1 until ARRAY_LIMIT) limit else ARRAY_LIMIT
        val out = ArrayList<TLRPC.Dialog>(minOf(source.size, cap))
        for (dialog in source) {
            if (out.size == cap) break
            if (dialog == null) continue
            if (DialogObject.isFolderDialogId(dialog.id) || DialogObject.isEncryptedDialog(dialog.id)) continue
            if (ParanoiaHelper.isHidden(accountId, dialog.id)) continue
            out.add(dialog)
        }
        return out
    }

    /**
     * ui thread only. A chat folder is app state rather than a TL object - stock keeps its own
     * class for it, with the dialogs it currently resolves to - so it crosses as plain json.
     */
    private fun chatFoldersJson(controller: MessagesController, accountId: Int, policy: TlFilter.Policy): String {
        val out = JSONArray()
        for (folder in controller.getDialogFilters()) {
            val title = JSONObject().put("text", folder.name.orEmpty())
            if (folder.entities.isNotEmpty()) {
                // through the one materialization point every other read uses, so a filtering rule
                // it gains later reaches a folder title too
                val entities = JSONArray()
                for (entity in folder.entities) entities.put(TlJson.toJson(entity, policy))
                title.put("entities", entities)
            }
            val pinned = JSONArray()
            // the value is the pin position, so a plugin sees them in the order they are pinned in
            // rather than whatever order the sparse array happens to hold them
            val pins = ArrayList<Long>(folder.pinnedDialogs.size())
            for (index in 0 until folder.pinnedDialogs.size()) pins.add(folder.pinnedDialogs.keyAt(index))
            pins.sortBy { folder.pinnedDialogs.get(it, Int.MAX_VALUE) }
            for (id in pins) {
                if (DialogObject.isEncryptedDialog(id) || ParanoiaHelper.isHidden(accountId, id)) continue
                pinned.put(id)
            }
            out.put(
                JSONObject()
                    .put("id", folder.id)
                    .put("title", title)
                    .put("emoticon", folder.inu_emoticon?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
                    // stock writes -1 for "no colour", which is not an index into anything
                    .put("colorIndex", if (folder.color < 0) JSONObject.NULL else folder.color)
                    .put("unreadCount", folder.unreadCount)
                    .put("dialogCount", copyDialogs(accountId, folder.dialogs, 0).size)
                    .put("isDefault", folder.isDefault)
                    .put("isChatlist", folder.isChatlist)
                    .put("pinned", pinned),
            )
        }
        return out.toString()
    }

}
