package desu.inugram.helpers.plugins.telegram

import android.text.SpannableStringBuilder
import android.text.Spanned
import desu.inugram.core.plugins.PluginRefusal
import desu.inugram.core.plugins.PluginWire.refuse
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.EngineDispatch
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
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_forum
import org.telegram.ui.Components.TypefaceSpan
import org.telegram.ui.Components.URLSpanNoUnderlineBold

/**
 * JNI upcalls run on [EngineDispatch.scheduler]. Async ops must settle through [answer]: settling
 * inline reenters the engine during an upcall and aborts the process.
 *
 * Sync getters read `dialogs_dict` and `dialogMessage` off the ui thread, as stock does. The ui
 * thread mutates them, so a read that loses a race returns `null` like a cache miss.
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

    /** keep in sync with `reads.js` */
    private const val ARCHIVE_ONLY = 1
    private const val ARCHIVE_KEEP = 2

    /** every folder id including `0` is a real one */
    private const val NO_CHAT_FOLDER = -1

    private const val PAGE_LIMIT = 100

    /**
     * telegram numbers user chats and basic groups from one sequence per account, so such an id names a
     * message on its own. No dialog has this id.
     */
    private const val COMMON_BOX = 0L

    /** mirrors rust `arguments::ARRAY_LIMIT` */
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
        return EngineDispatch.produceWire("account read") {
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
                    inputPeerWire(controller, accountId, spec, rest.toIntOrNull() ?: PeerSpecs.KIND_PEER, session.tl.policy)
                }
                OP_DRAFT -> {
                    val (spec, rest) = PeerSpecs.splitOnce(arg)
                    draftWire(controller, accountId, spec, rest.toLongOrNull() ?: 0L, session.tl.policy)
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
                    previewMessage(handles, accountId, wire, hideSpoilers = flag != "0", policy = session.tl.policy)
                }
                else -> PluginWire.encodeError("account read: unknown op $op")
            }
        }
    }

    /** `generateLayout = false` skips the text layout, the expensive half */
    private fun previewMessage(
        handles: TlHandles,
        accountId: Int,
        wire: String,
        hideSpoilers: Boolean,
        policy: TlFilter.Policy,
    ): String {
        val message = handles.objectFromWire(wire, allowReadOnly = true) as? TLRPC.Message
            ?: refuse("invalid-argument", "previewMessage: expected a message")
        val preview = MessageObject(accountId, message, false, false).messageText ?: ""
        val text = preview.toString()
        val json = JSONObject()
        // stock writes a plain text message's preview as the unspanned text itself
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

    private fun ownEntities(message: TLRPC.Message, policy: TlFilter.Policy): JSONArray? {
        val entities = message.entities?.takeIf { it.isNotEmpty() } ?: return null
        val out = JSONArray()
        for (entity in entities) TlJson.valueToJson(entity, policy)?.let { out.put(it) }
        return out.takeIf { it.length() > 0 }
    }

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
     * a service message preview is formatted with android spans, read back through the composer's
     * converter, so markdown parsing is off. [TypefaceSpan] is the composer's bold marker, not [MessageObject]'s.
     */
    private fun spanEntities(accountId: Int, preview: CharSequence, policy: TlFilter.Policy): JSONArray? {
        if (preview !is Spanned) return null
        val copy = arrayOf<CharSequence>(SpannableStringBuilder(preview))
        val entities = ArrayList<TLRPC.MessageEntity>()
        MediaDataController.getInstance(accountId).getEntities(copy, true, false)?.let { entities.addAll(it) }
        for (span in preview.getSpans(0, preview.length, TypefaceSpan::class.java)) {
            if (span.isBold) entities.add(boldOver(preview, span))
        }
        // stock writes names in service messages as this `URLSpan` carrying a peer id, which `getEntities` has no case for
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
     * `NotificationsController.replaceSpoilers` is private. Stock only applies it where the preview is the
     * raw text: entity offsets count against that text.
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

    internal fun mint(handles: TlHandles, value: TLObject?, fields: List<String>? = null): String {
        if (value == null) return PluginWire.encodeNull()
        return handles.mintWireForPlugin(value, readOnly = true, fields = fields)
    }

    internal fun mintEach(handles: TlHandles, values: List<TLObject?>, fields: List<String>? = null): String =
        values.joinToString(PeerSpecs.LIST_SEPARATOR) { mint(handles, it, fields) }

    private fun findUser(controller: MessagesController, accountId: Int, spec: String): TLRPC.User? {
        val id = PeerSpecs.resolveDialogId(controller, accountId, spec) ?: return null
        // stock's getUser(0) answers with the logged-in user
        if (id <= 0) return null
        if (id == UserConfig.getInstance(accountId).getClientUserId()) {
            return controller.getUser(id) ?: UserConfig.getInstance(accountId).getCurrentUser()
        }
        return controller.getUser(id)
    }

    private fun findChat(controller: MessagesController, accountId: Int, spec: String): TLRPC.Chat? {
        val id = PeerSpecs.resolveDialogId(controller, accountId, spec) ?: return null
        if (id >= 0) return null
        return controller.getChat(-id)
    }

    private fun findPeer(controller: MessagesController, accountId: Int, spec: String): TLObject? {
        val id = PeerSpecs.resolveDialogId(controller, accountId, spec) ?: return null
        if (id == 0L) return null
        if (id > 0) return findUser(controller, accountId, spec)
        return controller.getUserOrChat(id)
    }

    private fun findDialog(controller: MessagesController, accountId: Int, spec: String): TLRPC.Dialog? {
        val id = PeerSpecs.resolveDialogId(controller, accountId, spec) ?: return null
        return controller.dialogs_dict.get(id)
    }

    /**
     * not the dialog's `notify_settings`: that is only the override, and muted also depends on the
     * account's per-peer-kind default and on the topic
     */
    private fun isMuted(controller: MessagesController, accountId: Int, spec: String, topicId: Long): Boolean {
        val id = PeerSpecs.resolveDialogId(controller, accountId, spec) ?: return false
        return controller.isDialogMuted(id, topicId)
    }

    /** [TopicsController.findTopic] takes the bare chat id, not the dialog id */
    private fun findTopic(
        controller: MessagesController,
        accountId: Int,
        spec: String,
        topicId: Long,
    ): TLRPC.TL_forumTopic? {
        val id = PeerSpecs.resolveDialogId(controller, accountId, spec) ?: return null
        if (id >= 0) return null
        return controller.topicsController.findTopic(-id, topicId)
    }

    /**
     * [MessagesController] only holds each dialog's last message. The storage read blocks this turn on
     * `storageQueue`, and is the same reader the async path uses.
     */
    private fun findMessage(
        controller: MessagesController,
        accountId: Int,
        spec: String,
        messageId: Int?,
    ): TLRPC.Message? {
        if (messageId == null) return null
        val named = PeerSpecs.resolveDialogId(controller, accountId, spec) ?: return null
        val dialogId = named.takeIf { it != COMMON_BOX }
        cachedMessage(controller, dialogId, messageId)?.let { return it }
        return onStorageQueue(accountId) { readStoredMessages(accountId, dialogId, listOf(messageId)) }[messageId]
    }

    /** never call from `storageQueue` itself: it would wait on its own thread */
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
     * [MessagesController.dialogMessagesByIds] is keyed by bare message id, and channel ids collide there
     * with every other channel's and the common box. A hit only counts if it belongs to the asked
     * dialog, and a common-box read refuses channel messages.
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
        return byId.takeIf { MessageObject.getChannelId(it) == 0L }
    }

    private fun inputPeerWire(
        controller: MessagesController,
        accountId: Int,
        spec: String,
        kind: Int,
        policy: TlFilter.Policy,
        missing: String = PluginWire.encodeNull(),
    ): String = when (val built = PeerSpecs.buildInputPeer(controller, accountId, spec, kind)) {
        is PeerSpecs.Built.Missing -> missing
        is PeerSpecs.Built.WrongKind -> PeerSpecs.wrongKind(spec, built.kind)
        is PeerSpecs.Built.Peer -> PluginWire.encodeJson(TlJson.toJson(built.value, policy).toString())
    }

    private fun draftWire(
        controller: MessagesController,
        accountId: Int,
        spec: String,
        topicId: Long,
        policy: TlFilter.Policy,
    ): String {
        val dialogId = PeerSpecs.resolveDialogId(controller, accountId, spec) ?: return PluginWire.encodeNull()
        if (dialogId == 0L) return PluginWire.encodeNull()
        val draft = MediaDataController.getInstance(accountId).getDraft(dialogId, topicId)
        if (draft == null || draft is TLRPC.TL_draftMessageEmpty) return PluginWire.encodeNull()
        val snapshot = TlJson.toJson(draft, policy)
        val json = JSONObject()
        json.put("text", snapshot.optString("message"))
        snapshot.optJSONArray("entities")?.let { json.put("entities", it) }
        return PluginWire.encodeJson(json.toString())
    }

    /** the server only hands out `access_hash` attached to an entity, so an uncached id cannot be resolved */
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
        // the promise settles once; a request stock keeps re-sending would never answer
        val flags = ConnectionsManager.RequestFlagFailOnServerErrors
        // bypass lease: a plugin intercepting contacts.resolveUsername that resolves from its own middleware would recurse without bound
        PluginRpc.sendWithoutInterceptors(accountId, request, flags) { response, error ->
            EngineDispatch.settle(session, QuickJs.SETTLE_READS, requestId, "resolvePeer") {
                settleWire(controller, accountId, response, error, spec, kind, session.tl.policy)
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
        if (error != null) return PluginWire.encodeRpcError(error.code, error.text ?: "")
        val nothing = PluginWire.encodePluginError("not-found", "resolvePeer: nothing resolved for ${PeerSpecs.describeSpec(spec)}")
        val resolved = response as? TLRPC.TL_contacts_resolvedPeer ?: return nothing
        controller.putUsers(resolved.users, false)
        controller.putChats(resolved.chats, false)
        return inputPeerWire(controller, accountId, spec, kind, policy, missing = nothing)
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
        return EngineDispatch.produceWire("account fetch") {
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
        }
    }

    private fun notCached(spec: String): Nothing = refuse("not-found", "${PeerSpecs.describeSpec(spec)} is not cached")

    /** rust hands this back opaque. `0` means both no cursor and the first page, as telegram spells it */
    @JvmInline
    private value class Cursor(private val fields: List<String>) {
        fun int(index: Int): Int = fields.getOrNull(index)?.toIntOrNull() ?: 0

        fun long(index: Int): Long = fields.getOrNull(index)?.toLongOrNull() ?: 0L
    }

    private class Fetch(
        session: PluginSession,
        controller: MessagesController,
        accountId: Int,
        requestId: Long,
        val spec: String,
        args: JSONObject,
        cursor: String,
    ) : AccountCall(session, controller, accountId, requestId, args, QuickJs.SETTLE_READS, "account fetch") {
        val limit: Int get() = int("limit").takeIf { it in 1..PAGE_LIMIT } ?: PAGE_LIMIT

        val from = Cursor(cursor.split(','))

        fun peer(spec: String = this.spec, kind: Int = PeerSpecs.KIND_PEER): TLObject =
            PeerSpecs.requireInputPeer(controller, accountId, spec, kind)

        fun cache(users: ArrayList<TLRPC.User>, chats: ArrayList<TLRPC.Chat>) {
            controller.putUsers(users, false)
            controller.putChats(chats, false)
        }
    }

    private fun fetchUserFull(call: Fetch): String? {
        val dialogId = PeerSpecs.resolveDialogId(call.controller, call.accountId, call.spec)
        val cached = dialogId?.takeIf { it > 0 }?.let { call.controller.getUserFull(it) }
        if (cached != null) {
            call.answer { mint(call.handles, cached) }
            return null
        }
        val request = TLRPC.TL_users_getFullUser()
        request.id = call.peer(kind = PeerSpecs.KIND_USER) as TLRPC.InputUser
        return PluginWrites.send(call, request) { response ->
            val full = (response as? TLRPC.TL_users_userFull) ?: return@send PluginWire.encodeNull()
            call.cache(full.users, full.chats)
            mint(call.handles, full.full_user)
        }
    }

    private fun fetchChatFull(call: Fetch): String? {
        val spec = call.spec
        val dialogId = PeerSpecs.resolveDialogId(call.controller, call.accountId, spec) ?: notCached(spec)
        if (dialogId >= 0) throw PluginRefusal(PeerSpecs.wrongKind(spec, PeerSpecs.KIND_CHANNEL))
        val cached = call.controller.getChatFull(-dialogId)
        if (cached != null) {
            call.answer { mint(call.handles, cached) }
            return null
        }
        val chat = call.controller.getChat(-dialogId) ?: notCached(spec)
        // a basic group has no `InputChannel`
        val request: TLObject = if (ChatObject.isChannel(chat)) {
            TLRPC.TL_channels_getFullChannel().apply {
                channel = call.peer(kind = PeerSpecs.KIND_CHANNEL) as TLRPC.InputChannel
            }
        } else {
            TLRPC.TL_messages_getFullChat().apply { chat_id = -dialogId }
        }
        return PluginWrites.send(call, request) { response ->
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
        // the same rpc the app sends when a forum topic is opened
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
        return PluginWrites.send(call, request) { response ->
            val messages = (response as? TLRPC.messages_Messages) ?: return@send ""
            call.cache(messages.users, messages.chats)
            mintEach(call.handles, messages.messages)
        }
    }

    private fun fetchMessages(call: Fetch): String? {
        val spec = call.spec
        val named = PeerSpecs.resolveDialogId(call.controller, call.accountId, spec) ?: notCached(spec)
        val dialogId = named.takeIf { it != COMMON_BOX }
        val ids = call.ints("ids")
        loadLocalMessages(call.accountId, dialogId, ids) { known ->
            val missing = ids.filterNot(known::containsKey)
            if (missing.isEmpty()) {
                answerMessages(call, ids, known)
            } else {
                requestMessages(call, dialogId, ids, known, missing)
            }
        }
        return null
    }

    /** [done] runs inline when every id is cached, else on the engine queue */
    internal fun loadLocalMessages(accountId: Int, dialogId: Long?, ids: List<Int>, done: (Map<Int, TLRPC.Message>) -> Unit) {
        val controller = MessagesController.getInstance(accountId)
        val cached = ids.mapNotNull { id -> cachedMessage(controller, dialogId, id)?.let { id to it } }.toMap()
        if (cached.size == ids.size) return done(cached)
        MessagesStorage.getInstance(accountId).storageQueue.postRunnable {
            val stored = readStoredMessages(accountId, dialogId, ids.filterNot(cached::containsKey))
            EngineDispatch.scheduler.postRunnable { done(cached + stored) }
        }
    }

    private fun answerMessages(call: Fetch, ids: List<Int>, found: Map<Int, TLRPC.Message>) {
        call.answer { mintEach(call.handles, ids.map(found::get)) }
    }

    /** `messages_v2` is keyed by `(mid, uid)` and carries `is_channel`, which is how stock reads a common-box message by id */
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
            return found
        }
        return found
    }

    /** channel ids mean nothing without the channel. A named peer still filters results, so a foreign id answers `null` */
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
            PluginWrites.send(call, request) { response ->
                val messages = response as? TLRPC.messages_Messages ?: return@send mintEach(call.handles, ids.map(known::get))
                call.cache(messages.users, messages.chats)
                // an invisible id comes back as `messageEmpty`
                val fetched = messages.messages
                    .filter { it !is TLRPC.TL_messageEmpty && (dialogId == null || MessageObject.getDialogId(it) == dialogId) }
                    .associateBy { it.id }
                mintEach(call.handles, ids.map { known[it] ?: fetched[it] })
            }
        } catch (e: PluginRefusal) {
            call.answer { e.wire }
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
        // a cursor whose peer left the cache pages from the date alone, not from a zero `access_hash`
        request.offset_peer =
            when (val built = PeerSpecs.buildInputPeer(call.controller, call.accountId, "${PeerSpecs.SPEC_DIALOG_ID}$offsetDialog", PeerSpecs.KIND_PEER)) {
                is PeerSpecs.Built.Peer -> built.value as TLRPC.InputPeer
                else -> TLRPC.TL_inputPeerEmpty()
            }
        return PluginWrites.send(call, request) { response ->
            val page = (response as? TLRPC.messages_Dialogs) ?: return@send PeerSpecs.LIST_SEPARATOR
            call.cache(page.users, page.chats)
            val last = page.dialogs.lastOrNull()
            // a non-slice answer is the whole list, and a short slice is its end
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
        val dialogId = PeerSpecs.resolveDialogId(call.controller, call.accountId, spec) ?: notCached(spec)
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
        return PluginWrites.send(call, request) { response ->
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
     * async because the ui thread rebuilds `allDialogs`, `dialogsByFolder` and `dialogFilters` in place
     * (`sortDialogs` clears and refills), so they are copied on the ui thread.
     */
    private fun fetchCachedDialogs(call: Fetch): String? {
        val archive = call.int("archive")
        val chatFolderId = if (call.json.isNull("chatFolderId")) NO_CHAT_FOLDER else call.int("chatFolderId")
        val limit = call.int("limit")
        val fields = call.strings("fields")
        AndroidUtilities.runOnUIThread {
            val picked = if (chatFolderId != NO_CHAT_FOLDER) {
                // `getDialogFilters` answers with the frozen list while the user drags tabs, which is what the tabs show
                call.controller.getDialogFilters().firstOrNull { it.id == chatFolderId }?.dialogs
            } else {
                when (archive) {
                    ARCHIVE_ONLY -> call.controller.getDialogs(1)
                    ARCHIVE_KEEP -> call.controller.allDialogs
                    else -> call.controller.getDialogs(0)
                }
            }
            val dialogs = picked?.let { copyDialogs(call.accountId, it, limit) }
            call.answer {
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
        val policy = call.handles.policy
        AndroidUtilities.runOnUIThread {
            val json = chatFoldersJson(call.controller, call.accountId, policy)
            call.answer { PluginWire.encodeJson(json) }
        }
        return null
    }

    /**
     * ui thread only. `TL_dialogFolder` is the archive row, not a dialog. Secret chats are dropped:
     * [PeerSpecs.resolveDialogId] refuses them, and their ids are positive.
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

    /** ui thread only */
    private fun chatFoldersJson(controller: MessagesController, accountId: Int, policy: TlFilter.Policy): String {
        val out = JSONArray()
        for (folder in controller.getDialogFilters()) {
            val title = JSONObject().put("text", folder.name.orEmpty())
            if (folder.entities.isNotEmpty()) {
                val entities = JSONArray()
                for (entity in folder.entities) entities.put(TlJson.toJson(entity, policy))
                title.put("entities", entities)
            }
            val pinned = JSONArray()
            // the value is the pin position
            val pins = ArrayList<Long>(folder.pinnedDialogs.size())
            for (index in 0 until folder.pinnedDialogs.size()) pins.add(folder.pinnedDialogs.keyAt(index))
            pins.sortBy { folder.pinnedDialogs.get(it, Int.MAX_VALUE) }
            for (id in pins) {
                if (DialogObject.isEncryptedDialog(id) || ParanoiaHelper.isHidden(accountId, id)) continue
                pinned.put(PeerSpecs.toMarkedPeerId(MessagesController.getInstance(accountId), id))
            }
            out.put(
                JSONObject()
                    .put("id", folder.id)
                    .put("title", title)
                    .put("emoticon", folder.inu_emoticon?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
                    // stock writes -1 for no colour
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
