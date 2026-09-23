package desu.inugram.helpers.plugins.telegram

import desu.inugram.core.plugins.PluginRefusal
import desu.inugram.core.plugins.PluginWire.refuse
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.WritesListener
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

/** results are read-only: sent messages become app-owned once `processUpdates` applies them */
object PluginWrites {

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
        val controller = PeerSpecs.controllerFor(accountId)
            ?: return PeerSpecs.noAccountWire("account write", accountId)
        return EngineDispatch.produceWire("account write") {
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
        }
    }

    /**
     * an unhashed channel is `inputPeerChannelFromMessage`, a sibling of `TL_inputPeerChannel`, not a
     * subclass. Missing it picks the `messages.*` rpc, addressing the user's own message-id space.
     */
    private fun isChannelPeer(peer: TLObject): Boolean =
        peer is TLRPC.TL_inputPeerChannel || peer is TLRPC.TL_inputPeerChannelFromMessage

    internal class Call(
        session: PluginSession,
        controller: MessagesController,
        accountId: Int,
        requestId: Long,
        json: JSONObject,
        val values: Array<String>,
    ) : AccountCall(session, controller, accountId, requestId, json, QuickJs.SETTLE_WRITES, "account write") {
        fun peer(key: String = "peer", kind: Int = PeerSpecs.KIND_PEER): TLObject =
            writePeer(controller, accountId, json.optString(key), kind)

        // android's org.json answers `optString` with "null" for a json null, unlike the reference implementation tests run on
        fun text(): String = if (json.isNull("text")) "" else json.optString("text")

        fun ids(): List<Int> = ints("ids")
    }

    /**
     * bypass lease, or a rewriting plugin and a sending plugin loop. The delegate runs on
     * [Utilities.stageQueue], the only queue `processUpdates` may run on.
     */
    internal fun send(call: AccountCall, request: TLObject, produce: (TLObject?) -> String): String? {
        // stock call sites set optional flag bits by hand; `serializeToStream` recomputes only the boolean ones
        TlReflect.syncFlagsDeep(request)
        val flags = ConnectionsManager.RequestFlagFailOnServerErrors
        PluginRpc.sendWithoutInterceptors(call.accountId, request, flags) { response, error ->
            // stageQueue frees the response when this returns, before [answer]'s runnable reads it
            response?.disableFree = true
            if (response is TLRPC.Updates) {
                // `processUpdates` removes the applied entries from this list
                val sent = ArrayList(response.updates)
                try {
                    MessagesController.getInstance(call.accountId).processUpdates(response, false)
                } catch (e: Throwable) {
                    call.session.log.e("writes", "applying what a plugin sent failed", e)
                }
                response.updates = sent
            }
            call.answer(release = { PluginRpc.releaseUnowned(response) }) {
                if (error != null) PluginWire.encodeRpcError(error.code, error.text ?: "")
                else produce(response)
            }
        }
        return null
    }

    /** `forbidden`, not `not-found`: a secret chat will never resolve */
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
        return PeerSpecs.requireInputPeer(controller, accountId, spec, kind)
    }

    private fun sendMessage(call: Call): String? {
        if (call.optedIn("optimistic") && PluginOptimisticSend.canSend(call)) {
            return PluginOptimisticSend.sendText(call) { answerRefusals(call) { sendMessageRequest(call) } }
        }
        return sendMessageRequest(call)
    }

    /** composer callbacks run with nothing above to catch a [PluginRefusal], and the promise still needs an answer */
    internal fun answerRefusals(call: Call, block: () -> Unit) {
        try {
            block()
        } catch (e: PluginRefusal) {
            call.answer { e.wire }
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
        request.entities = readEntities(call.json)
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
        request.entities = readEntities(call.json)
        return send(call, request) { response -> messageWire(call, response, 0L, request.message) }
    }

    private fun deleteMessages(call: Call): String? {
        val ids = call.ids()
        if (ids.isEmpty()) refuse("invalid-argument", "deleteMessages: no message ids")
        // the peer resolves either way so a secret-chat write is refused before the shape is chosen
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
        // the rpc the app sends when a forum topic scrolls to the bottom
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
        request.entities = readEntities(call.json)
        request.reply_to = replyTo(call)
        return send(call, request) { PluginWire.encodeNull() }
    }

    /** keep in step with `TYPING_ACTIONS` in `writes.js` */
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
            // a topic post with no reply of its own replies to the topic's root, which is what lands it in the topic
            reply_to_msg_id = if (message != 0) message else topic
            top_msg_id = topic
        }
    }

    internal fun sendAs(call: Call): TLRPC.InputPeer? {
        if (call.json.isNull("sendAs")) return null
        return writePeer(call.controller, call.accountId, call.json.optString("sendAs")) as TLRPC.InputPeer
    }

    internal fun readEntities(json: JSONObject): ArrayList<TLRPC.MessageEntity> {
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

    /** `updateShortSentMessage` carries only what changed; the rest comes from the request, as the app does */
    internal fun messageWire(call: Call, response: TLObject?, randomId: Long, text: String): String {
        val handles = call.session.tl
        val message = when (response) {
            is TLRPC.TL_updateShortSentMessage -> shortSentMessage(call, response, randomId, text)
            is TLRPC.Updates -> response.updates.firstNotNullOfOrNull { extractUpdateMessage(it) }
            else -> null
        }
        // `Promise<Message>`: no message is a failure, not a null
        if (message == null) refuse("internal", "the server accepted the request without answering with a message")
        return PluginReads.mint(handles, message)
    }

    internal fun messagesWire(call: Call, response: TLObject?): String {
        val handles = call.session.tl
        val updates = (response as? TLRPC.Updates)?.updates ?: return ""
        return PluginReads.mintEach(handles, updates.mapNotNull { extractUpdateMessage(it) })
    }

    private fun extractUpdateMessage(update: TLRPC.Update): TLRPC.Message? = when (update) {
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
        val dialogId = PeerSpecs.resolveDialogId(call.controller, call.accountId, call.json.optString("peer")) ?: 0L
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
        message.peer_id = call.controller.getPeer(dialogId)
        message.dialog_id = dialogId
        TlReflect.syncFlags(message)
        return message
    }
}
