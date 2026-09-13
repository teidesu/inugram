package desu.inugram.helpers.plugins.telegram

import desu.inugram.core.plugins.PluginRefusal
import desu.inugram.core.plugins.PluginWire.refuse
import android.util.Log
import desu.inugram.core.plugins.PluginWire
import desu.inugram.core.plugins.ScopeMatch
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.WritesListener
import desu.inugram.helpers.plugins.tl.TlHandles
import desu.inugram.helpers.plugins.tl.TlJson
import desu.inugram.helpers.plugins.tl.TlReflect
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_update

/**
 * Kotlin side of the `Account` write surface (rust: `writes.rs`), and where the media transfers
 * ([PluginMedia]) settle.
 *
 * **One send path, carrying both rules `common.d.ts` states for this block.** Every write builds a
 * request and hands it to [send], which goes out through [PluginRpc.sendWithoutInterceptors], so
 * nothing a plugin sends re-enters the interceptor chains; every one names its peer through
 * [writePeer], which refuses an encrypted dialog id. Structural rather than repeated: a write that
 * skipped either would have to build its own request *and* its own peer.
 *
 * Called on [EngineDispatch.scheduler] from a JNI upcall, and like [PluginReads] never answers inline.
 * What a write resolves with is minted read-only: a sent message is the app's own state the moment
 * `processUpdates` has applied it.
 */
object PluginWrites {
    private const val TAG = "InuPluginWrites"

    // keep in sync with rust `writes::OP_*` and `writes.js`
    const val OP_SEND_MESSAGE = 0
    const val OP_SEND_MEDIA = 1
    const val OP_SEND_MULTI_MEDIA = 2
    const val OP_EDIT_MESSAGE = 3
    const val OP_DELETE_MESSAGES = 4
    const val OP_FORWARD_MESSAGES = 5
    const val OP_SET_REACTION = 6
    const val OP_READ_HISTORY = 7
    const val OP_SEND_TYPING = 8
    const val OP_SET_DRAFT = 9
    const val OP_DOWNLOAD_MEDIA = 10
    const val OP_DOWNLOAD_MEDIA_TO_FILE = 11
    const val OP_UPLOAD_FILE = 12
    const val OP_SET_SEND_MEDIA = 13

    /** batch results join their handles with this; keep in sync with rust `writes::SEPARATOR` */
    const val LIST_SEPARATOR = "\n"

    private val GRANT_BY_OP = mapOf(
        OP_SEND_MESSAGE to ("account.write" to "send"),
        OP_SEND_MEDIA to ("account.write" to "send"),
        OP_SEND_MULTI_MEDIA to ("account.write" to "send"),
        OP_UPLOAD_FILE to ("account.write" to "send"),
        OP_SET_SEND_MEDIA to ("account.write" to "send"),
        OP_EDIT_MESSAGE to ("account.write" to "edit"),
        OP_DELETE_MESSAGES to ("account.write" to "delete"),
        OP_FORWARD_MESSAGES to ("account.write" to "forward"),
        OP_SET_REACTION to ("account.write" to "react"),
        OP_READ_HISTORY to ("account.write" to "read"),
        OP_SEND_TYPING to ("account.write" to "typing"),
        OP_SET_DRAFT to ("account.write" to "draft"),
        OP_DOWNLOAD_MEDIA to ("account.read" to "messages"),
        OP_DOWNLOAD_MEDIA_TO_FILE to ("account.read" to "messages"),
    )

    fun listenerFor(session: PluginSession): WritesListener =
        object : WritesListener {
            override fun accountWrite(
                accountId: Int,
                requestId: Long,
                op: Int,
                arg: String,
                values: Array<String>,
            ): String? = write(session, accountId, requestId, op, arg, values)

            override fun messageFile(accountId: Int, value: String): String =
                PluginMedia.messageFile(session, accountId, value)
        }

    private fun write(
        session: PluginSession,
        accountId: Int,
        requestId: Long,
        op: Int,
        arg: String,
        values: Array<String>,
    ): String? {
        val grant = GRANT_BY_OP[op] ?: return PluginWire.encodePluginError("internal", "account write: unknown op $op")
        // the engine's own check_grant already ran in native; this is the second gate, on the side that owns the data
        if (!session.permissions.allows(grant.first, grant.second, ScopeMatch.EXACT)) {
            return PluginWire.encodeNotGranted(grant.first, grant.second)
        }
        val controller = PeerSpecs.controllerFor(accountId)
            ?: return PluginWire.encodePluginError("not-found", "account write: no account is logged in as #$accountId")
        return try {
            val json = JSONObject(arg)
            val call = Call(session, controller, accountId, requestId, json, values)
            when (op) {
                OP_SEND_MESSAGE -> sendMessage(call)
                OP_SEND_MEDIA, OP_SEND_MULTI_MEDIA -> PluginMedia.sendMedia(call, album = op == OP_SEND_MULTI_MEDIA)
                OP_EDIT_MESSAGE -> editMessage(call)
                OP_DELETE_MESSAGES -> deleteMessages(call)
                OP_FORWARD_MESSAGES -> forwardMessages(call)
                OP_SET_REACTION -> setReaction(call)
                OP_READ_HISTORY -> readHistory(call)
                OP_SEND_TYPING -> sendTyping(call)
                OP_SET_DRAFT -> setDraft(call)
                OP_DOWNLOAD_MEDIA -> PluginMedia.download(call, toFile = false)
                OP_DOWNLOAD_MEDIA_TO_FILE -> PluginMedia.download(call, toFile = true)
                OP_UPLOAD_FILE -> PluginMedia.uploadFile(call)
                OP_SET_SEND_MEDIA -> PluginSendMorph.setMedia(call)
                else -> PluginWire.encodePluginError("internal", "account write: unknown op $op")
            }
        } catch (e: PluginRefusal) {
            e.wire
        } catch (e: Exception) {
            PluginWire.encodePluginError("internal", "account write: ${e.message ?: e.toString()}")
        }
    }

    /**
     * a channel the app only saw referenced from someone else's message has no `access_hash`, so
     * stock addresses it as `inputPeerChannelFromMessage` - a *sibling* of `TL_inputPeerChannel`
     * rather than a subclass. Missing it picks the peerless `messages.*` rpc, whose ids then address
     * the user's own message-id space.
     */
    private fun isChannelPeer(peer: TLObject): Boolean =
        peer is TLRPC.TL_inputPeerChannel || peer is TLRPC.TL_inputPeerChannelFromMessage


    internal class Call(
        val session: PluginSession,
        val controller: MessagesController,
        val accountId: Int,
        val requestId: Long,
        json: JSONObject,
        val values: Array<String>,
    ) : JsonArgs(json) {
        fun peer(key: String = "peer", kind: Int = PeerSpecs.KIND_PEER): TLObject =
            writePeer(controller, accountId, json.optString(key), kind)

        // android's org.json answers `optString` with the four characters "null" for a json null, where the reference implementation the bridge tests run against answers the fallback
        fun text(): String = if (json.isNull("text")) "" else json.optString("text")

        fun ids(): List<Int> = ints("ids")
    }

    /**
     * every write's single exit, through the bypass lease: without it a plugin that rewrites sends
     * and a plugin that sends are an infinite loop. The delegate runs on [Utilities.stageQueue],
     * where stock answers one from and the only queue `processUpdates` may run on, so the app
     * applies what a plugin sent where it applies what the ui sent.
     */
    internal fun send(call: Call, request: TLObject, produce: (TLObject?) -> String): String? {
        // stock's own call sites set the optional bits by hand, `serializeToStream` recomputing only the boolean ones - so a request built here goes out without its `reply_to`/`entities` unless the words are synced
        TlReflect.syncFlagsDeep(request)
        val flags = ConnectionsManager.RequestFlagFailOnServerErrors
        PluginRpc.sendWithoutInterceptors(call.accountId, request, flags) { response, error ->
            // stageQueue frees the response the moment this returns, before [answer]'s runnable reads it on the plugin queue, so ownership moves here
            response?.disableFree = true
            if (response is TLRPC.Updates) {
                // `processUpdates` removes the entries it applied from this very list, and the answer below is built out of it
                val sent = ArrayList(response.updates)
                try {
                    MessagesController.getInstance(call.accountId).processUpdates(response, false)
                } catch (e: Throwable) {
                    Log.e(TAG, "applying what a plugin sent failed", e)
                }
                response.updates = sent
            }
            answer(call, release = { PluginRpc.releaseUnowned(response) }) {
                if (error != null) PluginWire.encodeRpcError(error.code, error.text ?: "")
                else produce(response)
            }
        }
        return null
    }

    /** `forbidden` rather than the `not-found` [PeerSpecs.dialogIdOf] would answer: a secret chat is not a peer that might resolve later */
    internal fun writePeer(
        controller: MessagesController,
        accountId: Int,
        spec: String,
        kind: Int = PeerSpecs.KIND_PEER,
    ): TLObject {
        val named = spec.takeIf { it.length > 1 && it[0] == PeerSpecs.SPEC_DIALOG_ID }?.substring(1)?.toLongOrNull()
        if (named != null && DialogObject.isEncryptedDialog(named)) {
            refuse("forbidden", "secret chats are never reachable from a plugin")
        }
        return when (val built = PeerSpecs.buildInputPeer(controller, accountId, spec, kind)) {
            is PeerSpecs.Built.Missing -> refuse(
                "not-found",
                "${PeerSpecs.describeSpec(spec)} is not cached; resolve it with resolvePeer() first",
            )
            is PeerSpecs.Built.WrongKind -> throw PluginRefusal(PeerSpecs.wrongKind(spec, built.kind))
            is PeerSpecs.Built.Peer -> built.value
        }
    }

    /** [release] gives back whatever the settle borrowed, and runs on the stale path too: an obligation dropped because the plugin reloaded is still an obligation */
    internal fun answer(call: Call, release: () -> Unit = {}, produce: () -> String) {
        EngineDispatch.settle(call.session, QuickJs.SETTLE_WRITES, call.requestId, "account write", release) {
            try {
                produce()
            } catch (e: PluginRefusal) {
                e.wire
            }
        }
    }

    /**
     * A handle is resolved rather than rebuilt, so a message named for a download is the app's own
     * instance - which is what lets stock refresh its file reference from it.
     *
     * A read-only handle is refused: whatever comes back becomes part of a request [send] walks
     * with [TlReflect.syncFlagsDeep], so accepting one would rewrite the flag word of an object the app
     * owns - and a `show_previews = false` whose bit is set reads as absent. [readValue] is the
     * counterpart for the ops that only *name* an object.
     */
    internal fun tlValue(handles: TlHandles, wire: String): TLObject {
        val decoded = PluginWire.decode(wire)
        if (decoded is PluginWire.Value.Handle && handles.isReadOnly(decoded.id)) {
            refuse("forbidden", TlHandles.READ_ONLY_MESSAGE)
        }
        return readValue(handles, decoded)
    }

    /** read-only is the normal shape here: everything an `Account` hands over is read-only, so the message a download names is one */
    internal fun readValue(handles: TlHandles, wire: String): TLObject = readValue(handles, PluginWire.decode(wire))

    private fun readValue(handles: TlHandles, decoded: PluginWire.Value): TLObject = when (decoded) {
        is PluginWire.Value.Handle -> handles.resolveTlObject(decoded.id)
            ?: refuse("handle-expired", PluginWire.HANDLE_EXPIRED_MESSAGE)
        is PluginWire.Value.Json -> TlJson.fromJson(JSONObject(decoded.json))
        else -> refuse("invalid-argument", "expected a TL object")
    }

    private fun sendMessage(call: Call): String? {
        if (call.optedIn("optimistic") && PluginOptimisticSend.canSend(call)) {
            return PluginOptimisticSend.sendText(call) { answerRefusals(call) { sendMessageRequest(call) } }
        }
        return sendMessageRequest(call)
    }

    /**
     * a path the composer handed back runs on the plugin queue with nothing above it to catch a
     * [PluginRefusal], and a write that refuses still owes its promise an answer
     */
    internal fun answerRefusals(call: Call, block: () -> Unit) {
        try {
            block()
        } catch (e: PluginRefusal) {
            answer(call) { e.wire }
        }
    }

    private fun sendMessageRequest(call: Call): String? {
        val request = TLRPC.TL_messages_sendMessage()
        request.peer = call.peer() as TLRPC.InputPeer
        request.message = call.text()
        request.random_id = Utilities.random.nextLong()
        request.silent = call.flag("silent")
        request.no_webpage = call.flag("noWebpage")
        request.clear_draft = call.flag("clearDraft")
        request.schedule_date = call.int("scheduleDate")
        request.entities = entitiesOf(call.json)
        request.reply_to = replyTo(call)
        request.send_as = sendAs(call)
        return send(call, request) { response -> messageWire(call, response, request.random_id, request.message) }
    }

    private fun editMessage(call: Call): String? {
        val request = TLRPC.TL_messages_editMessage()
        request.peer = call.peer() as TLRPC.InputPeer
        request.id = call.int("id")
        request.message = call.text()
        request.no_webpage = call.flag("noWebpage")
        request.entities = entitiesOf(call.json)
        return send(call, request) { response -> messageWire(call, response, 0L, request.message) }
    }

    private fun deleteMessages(call: Call): String? {
        val ids = call.ids()
        if (ids.isEmpty()) refuse("invalid-argument", "deleteMessages: no message ids")
        // a channel deletes by its own id and everything else by the message id alone. The peer is still resolved either way, so a write into a secret chat is refused before the shape is chosen
        val peer = call.peer()
        val request: TLObject = if (isChannelPeer(peer)) {
            TLRPC.TL_channels_deleteMessages().apply {
                channel = call.peer(kind = PeerSpecs.KIND_CHANNEL) as TLRPC.InputChannel
                id.addAll(ids)
            }
        } else {
            TLRPC.TL_messages_deleteMessages().apply {
                revoke = call.flag("revoke")
                id.addAll(ids)
            }
        }
        return send(call, request) { PluginWire.encodeNull() }
    }

    private fun forwardMessages(call: Call): String? {
        val ids = call.ids()
        if (ids.isEmpty()) refuse("invalid-argument", "forwardMessages: no message ids")
        val request = TLRPC.TL_messages_forwardMessages()
        request.from_peer = call.peer() as TLRPC.InputPeer
        request.to_peer = writePeer(call.controller, call.accountId, call.json.optString("toPeer")) as TLRPC.InputPeer
        request.id.addAll(ids)
        for (unused in ids) request.random_id.add(Utilities.random.nextLong())
        request.silent = call.flag("silent")
        request.drop_author = call.flag("dropAuthor")
        request.drop_media_captions = call.flag("dropCaption")
        request.schedule_date = call.int("scheduleDate")
        request.top_msg_id = call.int("topicId")
        return send(call, request) { response -> messagesWire(call, response) }
    }

    private fun setReaction(call: Call): String? {
        val request = TLRPC.TL_messages_sendReaction()
        request.peer = call.peer() as TLRPC.InputPeer
        request.msg_id = call.int("id")
        request.big = call.flag("big")
        val reactions = call.json.optJSONArray("reactions") ?: JSONArray()
        for (index in 0 until reactions.length()) {
            val one = reactions.optJSONObject(index) ?: continue
            val custom = one.optString("customEmojiId").toLongOrNull()
            request.reaction.add(
                if (custom != null) TLRPC.TL_reactionCustomEmoji().apply { document_id = custom }
                else TLRPC.TL_reactionEmoji().apply { emoticon = one.optString("emoji") },
            )
        }
        return send(call, request) { PluginWire.encodeNull() }
    }

    private fun readHistory(call: Call): String? {
        val peer = call.peer() as TLRPC.InputPeer
        val maxId = call.int("maxId")
        val topicId = call.int("topicId")
        // a topic is a thread, and marking one read is the rpc the app sends when a forum topic scrolls to the bottom
        val request: TLObject = when {
            topicId > 0 -> TLRPC.TL_messages_readDiscussion().apply {
                this.peer = peer
                msg_id = topicId
                read_max_id = maxId
            }
            isChannelPeer(peer) -> TLRPC.TL_channels_readHistory().apply {
                channel = call.peer(kind = PeerSpecs.KIND_CHANNEL) as TLRPC.InputChannel
                max_id = maxId
            }
            else -> TLRPC.TL_messages_readHistory().apply {
                this.peer = peer
                max_id = maxId
            }
        }
        return send(call, request) { PluginWire.encodeNull() }
    }

    private fun sendTyping(call: Call): String? {
        val request = TLRPC.TL_messages_setTyping()
        request.peer = call.peer() as TLRPC.InputPeer
        request.top_msg_id = call.int("topicId")
        request.action = typingAction(call.json.optString("action"))
        return send(call, request) { PluginWire.encodeNull() }
    }

    private fun setDraft(call: Call): String? {
        val request = TLRPC.TL_messages_saveDraft()
        request.peer = call.peer() as TLRPC.InputPeer
        request.message = call.text()
        request.entities = entitiesOf(call.json)
        request.reply_to = replyTo(call)
        return send(call, request) { PluginWire.encodeNull() }
    }

    /** keep the names in step with `TYPING_ACTIONS` in `writes.js`, which refuses the rest before they cross */
    private fun typingAction(name: String): TLRPC.SendMessageAction = when (name) {
        "typing" -> TLRPC.TL_sendMessageTypingAction()
        "cancel" -> TLRPC.TL_sendMessageCancelAction()
        "recordVideo" -> TLRPC.TL_sendMessageRecordVideoAction()
        "uploadVideo" -> TLRPC.TL_sendMessageUploadVideoAction()
        "recordVoice" -> TLRPC.TL_sendMessageRecordAudioAction()
        "uploadVoice" -> TLRPC.TL_sendMessageUploadAudioAction()
        "uploadPhoto" -> TLRPC.TL_sendMessageUploadPhotoAction()
        "uploadDocument" -> TLRPC.TL_sendMessageUploadDocumentAction()
        "chooseSticker" -> TLRPC.TL_sendMessageChooseStickerAction()
        "chooseContact" -> TLRPC.TL_sendMessageChooseContactAction()
        else -> refuse("invalid-argument", "sendTyping: unknown action '$name'")
    }

    internal fun replyTo(call: Call): TLRPC.InputReplyTo? {
        val message = call.int("replyTo")
        val topic = call.int("topicId")
        if (message == 0 && topic == 0) return null
        return TLRPC.TL_inputReplyToMessage().apply {
            // a post into a topic with no reply of its own replies to the topic's root message, which is what makes it land in the topic at all
            reply_to_msg_id = if (message != 0) message else topic
            top_msg_id = topic
        }
    }

    internal fun sendAs(call: Call): TLRPC.InputPeer? {
        if (call.json.isNull("sendAs")) return null
        return writePeer(call.controller, call.accountId, call.json.optString("sendAs")) as TLRPC.InputPeer
    }

    internal fun entitiesOf(json: JSONObject): ArrayList<TLRPC.MessageEntity> {
        val out = ArrayList<TLRPC.MessageEntity>()
        val array = json.optJSONArray("entities") ?: return out
        for (index in 0 until array.length()) {
            val one = array.optJSONObject(index) ?: refuse("invalid-argument", "entities[$index]: expected an object")
            val entity = TlJson.fromJson(one) as? TLRPC.MessageEntity
                ?: refuse("invalid-argument", "'${one.optString("_")}' is not a message entity")
            out.add(entity)
        }
        return out
    }

    /** `updateShortSentMessage` carries only what changed, so the rest is rebuilt from the request - as the app does, from the local message it had already drawn */
    internal fun messageWire(call: Call, response: TLObject?, randomId: Long, text: String): String {
        val handles = call.session.tl
        val message = when (response) {
            is TLRPC.TL_updateShortSentMessage -> shortSentMessage(call, response, randomId, text)
            is TLRPC.Updates -> response.updates.firstNotNullOfOrNull { messageOf(it) }
            else -> null
        }
        // the declared type is `Promise<Message>`, so an answer with no message in it is a failure rather than a null the plugin would have to guard against
        if (message == null) refuse("internal", "the server accepted the request without answering with a message")
        return PluginReads.mint(handles, message)
    }

    internal fun messagesWire(call: Call, response: TLObject?): String {
        val handles = call.session.tl
        val updates = (response as? TLRPC.Updates)?.updates ?: return ""
        return PluginReads.mintEach(handles, updates.mapNotNull { messageOf(it) })
    }

    private fun messageOf(update: TLRPC.Update): TLRPC.Message? = when (update) {
        is TL_update.TL_updateNewMessage -> update.message
        is TL_update.TL_updateNewChannelMessage -> update.message
        is TL_update.TL_updateEditMessage -> update.message
        is TL_update.TL_updateEditChannelMessage -> update.message
        else -> null
    }

    private fun shortSentMessage(
        call: Call,
        sent: TLRPC.TL_updateShortSentMessage,
        randomId: Long,
        text: String,
    ): TLRPC.Message {
        val message = TLRPC.TL_message()
        message.id = sent.id
        message.date = sent.date
        message.message = text
        message.media = sent.media
        message.entities = sent.entities
        message.ttl_period = sent.ttl_period
        message.out = true
        message.random_id = randomId
        message.from_id = TLRPC.TL_peerUser().apply { user_id = UserConfig.getInstance(call.accountId).getClientUserId() }
        message.peer_id = peerOfSpec(call)
        message.dialog_id = PeerSpecs.dialogIdOf(call.controller, call.accountId, call.json.optString("peer")) ?: 0L
        TlReflect.syncFlags(message)
        return message
    }

    private fun peerOfSpec(call: Call): TLRPC.Peer {
        val id = PeerSpecs.dialogIdOf(call.controller, call.accountId, call.json.optString("peer")) ?: 0L
        return when {
            id > 0 -> TLRPC.TL_peerUser().apply { user_id = id }
            call.controller.getChat(-id)?.let { it.broadcast || it.megagroup } == true ->
                TLRPC.TL_peerChannel().apply { channel_id = -id }
            else -> TLRPC.TL_peerChat().apply { chat_id = -id }
        }
    }
}
