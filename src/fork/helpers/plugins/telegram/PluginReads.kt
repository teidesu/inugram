package desu.inugram.helpers.plugins.telegram

import desu.inugram.core.plugins.PluginWire
import desu.inugram.core.plugins.ScopeMatch
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.ReadsListener
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlHandles
import desu.inugram.helpers.plugins.tl.TlJson
import desu.inugram.helpers.plugins.tl.TlReflect
import org.json.JSONObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_forum

/**
 * Kotlin side of the `Account` read surface (rust: `reads.rs`). [read] is a lookup in what
 * [MessagesController] already holds; [resolve] and [fetch] may go to the network.
 *
 * Called on [Utilities.globalQueue] from a JNI upcall, so **an asynchronous op never answers
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

    private val SCOPE_BY_OP = mapOf(
        OP_ME to "self",
        OP_USER to "peers",
        OP_CHAT to "peers",
        OP_PEER to "peers",
        OP_DIALOG to "dialogs",
        OP_MESSAGE to "messages",
        OP_USERS to "peers",
        OP_CHATS to "peers",
        OP_MESSAGES to "messages",
        OP_INPUT_PEER to "peers",
        OP_DRAFT to "draft",
        OP_USER_FULL to "peers",
        OP_CHAT_FULL to "peers",
        OP_HISTORY to "history",
        OP_DIALOGS to "dialogs",
        OP_TOPICS to "dialogs",
    )

    /** what telegram itself accepts for one page, and what an omitted `limit` asks for */
    private const val PAGE_LIMIT = 100

    fun listenerFor(plugin: Plugin, engine: QuickJs): ReadsListener =
        object : ReadsListener {
            override fun accountRead(accountId: Int, op: Int, arg: String): String =
                read(plugin, engine, accountId, op, arg)

            override fun resolvePeer(accountId: Int, requestId: Long, spec: String, kind: Int): String? =
                resolve(plugin, engine, accountId, requestId, spec, kind)

            override fun accountFetch(accountId: Int, requestId: Long, op: Int, arg: String): String? =
                fetch(plugin, engine, accountId, requestId, op, arg)
        }

    private fun read(plugin: Plugin, engine: QuickJs, accountId: Int, op: Int, arg: String): String {
        val scope = SCOPE_BY_OP[op] ?: return PluginWire.encodeError("account read: unknown op $op")
        // the engine's own check_grant already ran in native; this is the same belt-and-braces
        // second gate PluginKv keeps, on the side that owns the data
        if (!plugin.permissions.allows("account.read", scope, ScopeMatch.EXACT)) {
            return PluginWire.encodeNotGranted("account.read", scope)
        }
        if (!allowsSelf(plugin, arg)) return PluginWire.encodeNotGranted("account.read", "self")
        val handles = engine.listener?.tl as? TlHandles
            ?: return PluginWire.encodePluginError("internal", "account read: no handle table")
        val controller = PeerSpecs.controllerFor(accountId)
            ?: return PluginWire.encodePluginError("not-found", "account read: no account is logged in as #$accountId")
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
                    inputPeerWire(controller, accountId, spec, rest.toIntOrNull() ?: PeerSpecs.KIND_PEER, policyOf(plugin))
                }
                OP_DRAFT -> {
                    val (spec, rest) = PeerSpecs.splitOnce(arg)
                    draftWire(controller, accountId, spec, rest.toLongOrNull() ?: 0L, policyOf(plugin))
                }
                else -> PluginWire.encodeError("account read: unknown op $op")
            }
        } catch (e: Exception) {
            // a LongSparseArray read that raced the app's own writer lands here, and so does a
            // reflection failure inside a mint: neither is a plugin's doing, and neither is worth
            // taking the app down over
            PluginWire.encodePluginError("internal", "account read: ${e.message ?: e.toString()}")
        }
    }

    internal fun mint(handles: TlHandles, value: TLObject?): String =
        if (value == null) PluginWire.encodeNull()
        else PluginWire.encodeHandle(vector = false, id = handles.mintForPlugin(value, readOnly = true), readOnly = true)

    internal fun mintEach(handles: TlHandles, values: List<TLObject?>): String =
        values.joinToString(PeerSpecs.LIST_SEPARATOR) { mint(handles, it) }

    /**
     * answering `'me'` tells a plugin *which* peer you are - the identity `account.read(self)` gates
     * on. Without it a plugin holding one `Account` per slot rebuilds `inu.accounts()` out of
     * `getUser('me').id`. Checked after the op's own scope, and mirrored in `reads.rs`.
     */
    private fun allowsSelf(plugin: Plugin, arg: String): Boolean =
        !PeerSpecs.namesSelf(arg) || plugin.permissions.allows("account.read", "self", ScopeMatch.EXACT)

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

    /** the app keeps whole histories in sqlite and only the chat list's own messages in memory; `getHistory` is the one that goes to disk */
    private fun findMessage(
        controller: MessagesController,
        accountId: Int,
        spec: String,
        messageId: Int?,
    ): TLRPC.Message? {
        if (messageId == null) return null
        val id = PeerSpecs.dialogIdOf(controller, accountId, spec) ?: return null
        val cached = controller.dialogMessage.get(id) ?: return null
        for (message in cached) {
            if (message != null && message.getId() == messageId) return message.messageOwner
        }
        return null
    }

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

    private fun policyOf(plugin: Plugin): TlFilter.Policy = TlFilter.policyFor(plugin.permissions)

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
        plugin: Plugin,
        engine: QuickJs,
        accountId: Int,
        requestId: Long,
        spec: String,
        kind: Int,
    ): String? {
        if (!plugin.permissions.allows("account.read", "peers", ScopeMatch.EXACT)) return PluginWire.encodeNotGranted("account.read", "peers")
        if (!allowsSelf(plugin, spec)) return PluginWire.encodeNotGranted("account.read", "self")
        val controller = PeerSpecs.controllerFor(accountId)
            ?: return PluginWire.encodePluginError("not-found", "resolvePeer: no account is logged in as #$accountId")
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
            EngineDispatch.settle(
                plugin,
                engine,
                "resolvePeer",
                produce = { settleWire(controller, accountId, response, error, spec, kind, policyOf(plugin)) },
                deliver = { engine.resolvePeerResult(requestId, it) },
            )
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
        plugin: Plugin,
        engine: QuickJs,
        accountId: Int,
        requestId: Long,
        op: Int,
        arg: String,
    ): String? {
        val scope = SCOPE_BY_OP[op] ?: return PluginWire.encodePluginError("internal", "account fetch: unknown op $op")
        if (!allowsFetch(plugin, op, arg)) return PluginWire.encodeNotGranted("account.read", scope)
        if (!allowsSelf(plugin, arg)) return PluginWire.encodeNotGranted("account.read", "self")
        val controller = PeerSpecs.controllerFor(accountId)
            ?: return PluginWire.encodePluginError("not-found", "account fetch: no account is logged in as #$accountId")
        val call = Fetch(plugin, engine, controller, accountId, requestId, PeerSpecs.splitList(arg))
        return try {
            when (op) {
                OP_USER_FULL -> fetchUserFull(call)
                OP_CHAT_FULL -> fetchChatFull(call)
                OP_HISTORY -> fetchHistory(call)
                OP_DIALOGS -> fetchDialogs(call)
                OP_TOPICS -> fetchTopics(call)
                else -> PluginWire.encodePluginError("internal", "account fetch: unknown op $op")
            }
        } catch (e: NotResolved) {
            e.wire
        } catch (e: Exception) {
            PluginWire.encodePluginError("internal", "account fetch: ${e.message ?: e.toString()}")
        }
    }

    /** `getUserFull` on *yourself* is the one read allowed under `account.read(self)` alone, and "yourself" is the spec rather than a dialog id that happens to be yours */
    private fun allowsFetch(plugin: Plugin, op: Int, arg: String): Boolean {
        if (op == OP_USER_FULL && arg.length == 1 && arg[0] == PeerSpecs.SPEC_SELF &&
            plugin.permissions.allows("account.read", "self", ScopeMatch.EXACT)
        ) {
            return true
        }
        val scope = SCOPE_BY_OP[op] ?: return false
        return plugin.permissions.allows("account.read", scope, ScopeMatch.EXACT)
    }

    /** after a reload the plugin runs on a new engine whose request ids restart, so a stale settle must not reach it */
    private fun answer(call: Fetch, produce: () -> String) {
        EngineDispatch.settle(
            call.plugin,
            call.engine,
            "account fetch",
            produce = produce,
            deliver = { call.engine.accountFetchResult(call.requestId, it) },
        )
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
                if (error != null) PluginWire.encodeRpcError(error.code, error.text ?: "")
                else produce(response)
            }
        }
        return null
    }

    private fun clampLimit(raw: String?): Int {
        val limit = raw?.toIntOrNull() ?: 0
        return if (limit in 1..PAGE_LIMIT) limit else PAGE_LIMIT
    }

    private class NotResolved(val wire: String) : Exception()

    private fun refuse(code: String, message: String): Nothing =
        throw NotResolved(PluginWire.encodePluginError(code, message))

    private fun notCached(spec: String): Nothing = refuse("not-found", "${PeerSpecs.describeSpec(spec)} is not cached")

    private class Fetch(
        val plugin: Plugin,
        val engine: QuickJs,
        val controller: MessagesController,
        val accountId: Int,
        val requestId: Long,
        val parts: List<String>,
    ) {
        val handles: TlHandles get() = TlHandles.of(engine)

        val spec: String get() = parts.firstOrNull().orEmpty()

        val limit: Int get() = clampLimit(parts.getOrNull(1))

        val offsets: List<String> get() = parts.getOrNull(2).orEmpty().split(',')

        fun int(index: Int): Int = parts.getOrNull(index)?.toIntOrNull() ?: 0

        fun offset(index: Int): Int = offsets.getOrNull(index)?.toIntOrNull() ?: 0

        fun peer(spec: String = this.spec, kind: Int = PeerSpecs.KIND_PEER): TLObject =
            when (val built = PeerSpecs.buildInputPeer(controller, accountId, spec, kind)) {
                is PeerSpecs.Built.Missing -> refuse(
                    "not-found",
                    "${PeerSpecs.describeSpec(spec)} is not cached; resolve it with resolvePeer() first",
                )
                is PeerSpecs.Built.WrongKind -> throw NotResolved(PeerSpecs.wrongKind(spec, built.kind))
                is PeerSpecs.Built.Peer -> built.value
            }

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
        if (dialogId >= 0) throw NotResolved(PeerSpecs.wrongKind(spec, PeerSpecs.KIND_CHANNEL))
        val cached = call.controller.getChatFull(-dialogId)
        if (cached != null) {
            answer(call) { mint(call.handles, cached) }
            return null
        }
        val chat = call.controller.getChat(-dialogId) ?: notCached(spec)
        // a basic group has no `InputChannel` and is asked about by its bare id, which is the whole
        // reason this is two rpcs rather than one
        val request: TLObject = if (chat.broadcast || chat.megagroup) {
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
        val offsetId = call.int(2)
        val minId = call.int(3)
        val maxId = call.int(4)
        val topicId = call.int(5)
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

    private fun fetchDialogs(call: Fetch): String? {
        val pageLimit = call.limit
        val request = TLRPC.TL_messages_getDialogs()
        request.folder_id = call.int(0)
        request.limit = pageLimit
        request.offset_date = call.offset(0)
        request.offset_id = call.offset(1)
        val offsetDialog = call.offsets.getOrNull(2)?.toLongOrNull() ?: 0L
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
                val dialogId = peerDialogId(last.peer)
                val date = page.messages.firstOrNull {
                    it.id == last.top_message && peerDialogId(it.peer_id) == dialogId
                }?.date ?: 0
                "$date,${last.top_message},$dialogId"
            }
            cursor + PeerSpecs.LIST_SEPARATOR + mintEach(call.handles, page.dialogs)
        }
    }

    private fun fetchTopics(call: Fetch): String? {
        val spec = call.spec
        val dialogId = PeerSpecs.dialogIdOf(call.controller, call.accountId, spec) ?: notCached(spec)
        val chat = if (dialogId < 0) call.controller.getChat(-dialogId) ?: notCached(spec) else null
        if (chat == null || !chat.forum) refuse("invalid-argument", "${PeerSpecs.describeSpec(spec)} is not a forum")
        val pageLimit = call.limit
        val request = TL_forum.TL_messages_getForumTopics()
        request.peer = call.peer() as TLRPC.InputPeer
        request.limit = pageLimit
        request.offset_date = call.offset(0)
        request.offset_id = call.offset(1)
        request.offset_topic = call.offset(2)
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

    private fun peerDialogId(peer: TLRPC.Peer?): Long = when {
        peer == null -> 0L
        peer.user_id != 0L -> peer.user_id
        peer.chat_id != 0L -> -peer.chat_id
        peer.channel_id != 0L -> -peer.channel_id
        else -> 0L
    }
}
