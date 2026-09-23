package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.telegram.PluginWrites
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_update

class PluginWritesTest {
    private val self = 100L
    private val alice = 222L
    private val channel = 1001L
    private val group = 2002L
    private val secret = 0x4000000000000000L or 7L

    @Before
    fun setUp() {
        resetBridge()
        TestApp.signInAs(0, user(self))
        TestApp.putUser(user(alice))
        TestApp.putChat(broadcast(channel))
        TestApp.putChat(basicGroup(group))
    }

    private fun granted(vararg extra: String) = startPlugin(
        "writes",
        "account.write(send,edit,delete,forward,react,read,typing,draft)",
        "account.read(messages)",
        *extra,
    )

    private fun send(peer: String, text: String = "hi") = JSONObject()
        .put("peer", peer)
        .put("text", text)
        .put("optimistic", false)

    private fun settled(plugin: Plugin): String {
        drain()
        return plugin.js.writeResults.last().resultWire
    }

    // mirrors the `disableFree` check of a stock subclass owning a `NativeByteBuffer`
    class CountingUpdates : TLRPC.TL_updates() {
        var freeCount = 0

        override fun freeResources() {
            if (disableFree) return
            freeCount++
            super.freeResources()
        }
    }

    private fun updatesWith(message: TLRPC.Message): CountingUpdates {
        val updates = CountingUpdates()
        updates.updates.add(TL_update.TL_updateNewMessage().apply { this.message = message })
        return updates
    }

    private fun sentMessage(id: Int, text: String) = TLRPC.TL_message().apply {
        this.id = id
        message = text
        peer_id = peerUser(alice)
        out = true
    }.synced()

    @Test
    fun a_plugin_s_own_send_never_re_enters_the_interceptor_chains() {
        val watcher = startPlugin("watcher", "interceptRpc(messages.sendMessage)")
        assertNull(watcher.interceptRpc("messages.sendMessage"))
        val plugin = granted()

        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send("D$alice")))
        drain()

        assertTrue(watcher.js.dispatches.isEmpty(), "the write walked into a chain")
        assertEquals(1, connections().sent.size, "the request did not go out")
        assertEquals(
            "messages.sendMessage",
            desu.inugram.helpers.plugins.tl.TlNames.classNameToTlName(connections().lastSent()!!.request.javaClass),
        )
    }

    // on CONNECTION_NOT_INITED stock re-sends the same instance with a fresh token, without the delegate
    @Test
    fun the_lease_outlives_the_first_send_and_ends_with_the_delegate() {
        val plugin = granted()
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send("D$alice")))
        drain()
        val sent = connections().lastSent()!!

        val watcher = startPlugin("watcher", "interceptRpc(messages.sendMessage)")
        assertNull(watcher.interceptRpc("messages.sendMessage"))
        connections().inu_retryNotInited(sent, 5)
        drain()
        assertTrue(watcher.js.dispatches.isEmpty(), "the retry of a plugin's own send walked into a chain")

        sent.answer(updatesWith(sentMessage(7, "hi")), null, 0L)
        drain()

        connections().sendRequestInternal(sent.request, null, null, null, null, 0, 0, 0, false, 6)
        drain()
        assertEquals(1, watcher.js.dispatches.size, "the lease outlived the flight")
    }

    @Test
    fun no_write_reaches_a_secret_chat_whichever_op_names_it() {
        val plugin = granted()
        val ops = mapOf(
            PluginWrites.OP_SEND_MESSAGE to send("D$secret"),
            PluginWrites.OP_EDIT_MESSAGE to send("D$secret").put("id", "1"),
            PluginWrites.OP_DELETE_MESSAGES to send("D$secret").put("ids", listOf("1").toJsonArray()),
            PluginWrites.OP_SET_REACTION to send("D$secret").put("id", "1"),
            PluginWrites.OP_READ_HISTORY to send("D$secret"),
            PluginWrites.OP_SEND_TYPING to send("D$secret").put("action", "typing"),
            PluginWrites.OP_SET_DRAFT to send("D$secret"),
            PluginWrites.OP_FORWARD_MESSAGES to
                send("D$secret").put("toPeer", "D$alice").put("ids", listOf("1").toJsonArray()),
        )
        for ((op, arg) in ops) {
            assertPluginError("forbidden", write(plugin, op, arg))
        }
        assertPluginError(
            "forbidden",
            write(
                plugin,
                PluginWrites.OP_FORWARD_MESSAGES,
                send("D$alice").put("toPeer", "D$secret").put("ids", listOf("1").toJsonArray()),
            ),
        )
        assertTrue(connections().sent.isEmpty(), "a refused write still went out")
    }

    @Test
    fun an_uncached_peer_is_not_found_with_nothing_sent() {
        val plugin = granted()
        assertPluginError("not-found", write(plugin, PluginWrites.OP_SEND_MESSAGE, send("D4242424242")))
        assertTrue(connections().sent.isEmpty())
    }

    @Test
    fun an_unknown_typing_action_is_refused_rather_than_sent_as_typing() {
        val plugin = granted()
        assertPluginError("invalid-argument", write(plugin, PluginWrites.OP_SEND_TYPING, send("D$alice").put("action", "dancing")))
        assertNull(write(plugin, PluginWrites.OP_SEND_TYPING, send("D$alice").put("action", "typing")))
        drain()
        assertEquals(1, connections().sent.size, "only the known action went out")
    }

    @Test
    fun a_send_carries_its_text_entities_reply_and_schedule() {
        val plugin = granted()
        val entity = JSONObject().put("_", "messageEntityBold").put("offset", 0).put("length", 2)
        val arg = send("D$alice")
            .put("entities", listOf(entity).toJsonArray())
            .put("replyTo", "5")
            .put("topicId", "9")
            .put("silent", true)
            .put("scheduleDate", "1700")
            .put("noWebpage", true)
            .put("clearDraft", true)
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, arg))
        drain()

        val request = connections().lastSent()!!.request as TLRPC.TL_messages_sendMessage
        assertEquals("hi", request.message)
        assertEquals(alice, (request.peer as TLRPC.TL_inputPeerUser).user_id)
        assertEquals(1, request.entities.size)
        assertTrue(request.entities[0] is TLRPC.TL_messageEntityBold)
        assertEquals(5, (request.reply_to as TLRPC.TL_inputReplyToMessage).reply_to_msg_id)
        assertEquals(9, (request.reply_to as TLRPC.TL_inputReplyToMessage).top_msg_id)
        assertTrue(request.silent && request.no_webpage && request.clear_draft)
        assertEquals(1700, request.schedule_date)
        assertTrue(request.random_id != 0L, "a send with no random_id is a duplicate waiting to happen")
    }

    @Test
    fun a_topic_post_with_no_reply_of_its_own_replies_to_the_topic_s_root() {
        val plugin = granted()
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send("D-$channel").put("topicId", "12")))
        drain()
        val reply = (connections().lastSent()!!.request as TLRPC.TL_messages_sendMessage).reply_to
            as TLRPC.TL_inputReplyToMessage
        assertEquals(12, reply.reply_to_msg_id)
        assertEquals(12, reply.top_msg_id)
    }

    @Test
    fun a_channel_deletes_through_its_own_rpc_and_everything_else_through_the_bare_one() {
        val plugin = granted()
        val ids = listOf("3", "4").toJsonArray()
        assertNull(
            write(plugin, PluginWrites.OP_DELETE_MESSAGES, send("D-$channel").put("ids", ids).put("revoke", true)),
        )
        drain()
        val channels = connections().lastSent()!!.request as TLRPC.TL_channels_deleteMessages
        assertEquals(channel, (channels.channel as TLRPC.TL_inputChannel).channel_id)
        assertEquals(listOf(3, 4), channels.id)

        assertNull(write(plugin, PluginWrites.OP_DELETE_MESSAGES, send("D$alice").put("ids", ids).put("revoke", true), requestId = 2))
        drain()
        val plain = connections().lastSent()!!.request as TLRPC.TL_messages_deleteMessages
        assertTrue(plain.revoke)
        assertEquals(listOf(3, 4), plain.id)
    }

    // stock addresses a min channel as `inputPeerChannelFromMessage`, not `TL_inputPeerChannel`, and
    // the peerless `messages.deleteMessages` would delete that id in the user's own id space
    @Test
    fun a_min_channel_still_deletes_and_reads_through_the_channel_rpcs() {
        val plugin = granted()
        val min = 3003L
        TestApp.putChat(account = 0, chat = 
            TLRPC.TL_channel().apply {
                id = min
                broadcast = true
                access_hash = 0
                fromMessageDialogId = alice
                fromMessageId = 55
            },
        )
        assertTrue(
            MessagesController.getInstance(0).getInputPeer(-min) is TLRPC.TL_inputPeerChannelFromMessage,
            "the fixture is not a min channel, so this test proves nothing",
        )

        assertNull(
            write(plugin, PluginWrites.OP_DELETE_MESSAGES, send("D-$min").put("ids", listOf("42").toJsonArray())),
        )
        drain()
        assertTrue(
            connections().lastSent()!!.request is TLRPC.TL_channels_deleteMessages,
            "a min channel deleted through the peerless rpc, which deletes id 42 in another chat",
        )

        assertNull(write(plugin, PluginWrites.OP_READ_HISTORY, send("D-$min").put("maxId", "9"), requestId = 2))
        drain()
        assertTrue(
            connections().lastSent()!!.request is TLRPC.TL_channels_readHistory,
            "a min channel read through the non-channel rpc, which the server rejects",
        )
    }

    @Test
    fun marking_a_topic_read_is_the_thread_rpc_and_a_channel_its_own() {
        val plugin = granted()
        assertNull(
            write(plugin, PluginWrites.OP_READ_HISTORY, send("D-$channel").put("maxId", "9").put("topicId", "3")),
        )
        drain()
        val discussion = connections().lastSent()!!.request as TLRPC.TL_messages_readDiscussion
        assertEquals(3, discussion.msg_id)
        assertEquals(9, discussion.read_max_id)

        assertNull(write(plugin, PluginWrites.OP_READ_HISTORY, send("D-$channel").put("maxId", "9"), requestId = 2))
        drain()
        assertEquals(9, (connections().lastSent()!!.request as TLRPC.TL_channels_readHistory).max_id)

        assertNull(write(plugin, PluginWrites.OP_READ_HISTORY, send("D$alice").put("maxId", "9"), requestId = 3))
        drain()
        assertEquals(9, (connections().lastSent()!!.request as TLRPC.TL_messages_readHistory).max_id)
    }

    @Test
    fun a_reaction_is_an_emoji_or_a_custom_one_and_clearing_is_an_empty_list() {
        val plugin = granted()
        val reactions = listOf(
            JSONObject().put("emoji", "👍"),
            JSONObject().put("customEmojiId", "555"),
        ).toJsonArray()
        assertNull(
            write(plugin, PluginWrites.OP_SET_REACTION, send("D$alice").put("id", "7").put("reactions", reactions)),
        )
        drain()
        val request = connections().lastSent()!!.request as TLRPC.TL_messages_sendReaction
        assertEquals(7, request.msg_id)
        assertEquals("👍", (request.reaction[0] as TLRPC.TL_reactionEmoji).emoticon)
        assertEquals(555L, (request.reaction[1] as TLRPC.TL_reactionCustomEmoji).document_id)
    }

    @Test
    fun clearing_a_draft_is_an_empty_text_rather_than_a_missing_call() {
        val plugin = granted()
        val arg = JSONObject().put("peer", "D$alice").put("text", JSONObject.NULL)
        assertNull(write(plugin, PluginWrites.OP_SET_DRAFT, arg))
        drain()
        assertEquals("", (connections().lastSent()!!.request as TLRPC.TL_messages_saveDraft).message)
    }

    @Test
    fun a_short_sent_update_is_rebuilt_from_the_request_rather_than_answered_as_nothing() {
        val plugin = granted()
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send("D$alice", "rebuild me")))
        drain()
        val short = TLRPC.TL_updateShortSentMessage()
        short.id = 909
        short.date = 1700
        connections().lastSent()!!.answer(short, null, 0L)

        val handle = decodeHandle(settled(plugin))
        assertEquals("I909", plugin.tl().tlGet(handle.id, "id"))
        assertEquals("Srebuild me", plugin.tl().tlGet(handle.id, "message"))
        assertEquals("I1700", plugin.tl().tlGet(handle.id, "date"))
    }

    @Test
    fun a_write_may_name_you_and_what_it_answers_names_you_back_under_the_write_scope_alone() {
        val plugin = startPlugin("writes", "account.write(send)")
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send("S", "note to self")))
        drain()
        val short = TLRPC.TL_updateShortSentMessage()
        short.id = 5
        connections().lastSent()!!.answer(short, null, 0L)

        val handle = decodeHandle(settled(plugin))
        assertEquals("I100", plugin.tl().tlGet(handle.id, "from_id").let {
            plugin.tl().tlGet(decodeHandle(it).id, "user_id")
        })
    }

    @Test
    fun an_answer_with_no_message_in_it_fails_rather_than_resolving_with_null() {
        val plugin = granted()
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send("D$alice")))
        drain()
        connections().lastSent()!!.answer(TLRPC.TL_updates(), null, 0L)
        assertPluginError("internal", settled(plugin))
    }

    @Test
    fun a_server_error_reaches_the_plugin_as_an_RpcError() {
        val plugin = granted()
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send("D$alice")))
        drain()
        val error = TLRPC.TL_error().apply { code = 420; text = "FLOOD_WAIT_3" }
        connections().lastSent()!!.answer(null, error, 0L)
        assertEquals("R420:FLOOD_WAIT_3", settled(plugin))
    }

    @Test
    fun nothing_settles_inside_the_upcall() {
        val plugin = granted()
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send("D$alice")))
        drain()
        connections().lastSent()!!.answer(updatesWith(sentMessage(1, "hi")), null, 0L)
        // settling inside the upcall is a same-engine re-entry, which aborts the process
        assertTrue(plugin.js.writeResults.isEmpty(), "the write settled before the queue ran")
        drain()
        assertEquals(1, plugin.js.writeResults.size)
    }

    // stock frees a response when the delegate returns, and the settle reads it a queue hop later
    @Test
    fun the_response_outlives_the_queue_hop_the_settle_takes_and_is_freed_exactly_once() {
        val plugin = granted()
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send("D$alice")))
        drain()
        val updates = updatesWith(sentMessage(7, "hi"))
        var freedWhenSettled = -1
        plugin.js.onWriteResult = { freedWhenSettled = updates.freeCount }

        connections().lastSent()!!.answer(updates, null, 0L)
        drain()

        assertEquals(0, freedWhenSettled, "the response was freed before the settle read it")
        assertEquals(1, updates.freeCount, "the settle owes exactly one free of what it took over")
    }

    @Test
    fun a_settle_for_an_engine_the_plugin_no_longer_runs_on_is_dropped() {
        val plugin = granted()
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send("D$alice")))
        drain()
        val stale = plugin.js
        connections().lastSent()!!.answer(updatesWith(sentMessage(1, "hi")), null, 0L)
        plugin.session = PluginSession(plugin, RecordingQuickJs())
        drain()
        assertTrue(stale.writeResults.isEmpty(), "a stale settle reached a dead engine")
    }

    @Test
    fun an_unknown_op_is_refused_rather_than_gated_on_a_fallback_scope() {
        val plugin = granted()
        assertPluginError("internal", write(plugin, 99, send("D$alice")))
    }

    // stock `processUpdates` removes the entries it applied from `updates.updates`
    @Test
    fun the_app_applies_what_the_plugin_sent_and_the_read_only_answer_outlives_it() {
        val plugin = granted()
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send("D$alice")))
        settle()
        val updates = updatesWith(sentMessage(77, "hi"))
        val carried = updates.updates
        connections().lastSent()!!.answer(updates, null, 0L)
        settle()

        assertTrue(carried.isEmpty(), "the app never applied the batch: $carried")
        val handle = decodeHandle(settled(plugin))
        assertTrue(handle.readOnly, "a sent message is app state and must not be writable")
        assertEquals("I77", plugin.tl().tlGet(handle.id, "id"))
        assertEquals("Shi", plugin.tl().tlGet(handle.id, "message"))
    }
}
