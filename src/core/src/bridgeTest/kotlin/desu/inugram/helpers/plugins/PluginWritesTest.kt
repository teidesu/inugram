package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.tg.PluginWrites
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_update

/**
 * The host half of the `Account` write surface: the two rules every write carries, what each one
 * builds, and what it answers with.
 */
class PluginWritesTest {
    private val self = 100L
    private val alice = 222L
    private val channel = 1001L
    private val group = 2002L
    private val secret = 0x4000000000000000L or 7L

    @Before
    fun setUp() {
        resetBridge()
        val config = UserConfig.getInstance(0)
        config.clientUserId = self
        config.currentUser = user(self)
        val controller = MessagesController.getInstance(0)
        controller.inu_putUser(user(alice))
        controller.inu_putChat(broadcast(channel))
        controller.inu_putChat(basicGroup(group))
    }

    private fun user(id: Long) = TLRPC.TL_user().apply {
        this.id = id
        access_hash = id * 10
    }

    private fun broadcast(id: Long) = TLRPC.TL_channel().apply {
        this.id = id
        access_hash = id * 10
        broadcast = true
    }

    private fun basicGroup(id: Long) = TLRPC.TL_chat().apply { this.id = id }

    private fun granted(vararg extra: String) = startPlugin(
        "writes",
        "account.write(send,edit,delete,forward,react,read,typing,draft)",
        "account.read(messages)",
        *extra,
    )

    private fun write(
        plugin: Plugin,
        op: Int,
        arg: JSONObject,
        values: Array<String> = emptyArray(),
        requestId: Long = 1L,
        account: Int = 0,
    ): String? = plugin.js.listener!!.accountWrite(account, requestId, op, arg.toString(), values)

    private fun send(peer: String, text: String = "hi") = JSONObject()
        .put("peer", peer)
        .put("text", text)

    /** the wire the engine was settled with, once the queues have run */
    private fun settled(plugin: Plugin): String {
        drain()
        return plugin.js.writeResults.last().resultWire
    }

    private fun updatesWith(message: TLRPC.Message): TLRPC.TL_updates {
        val updates = TLRPC.TL_updates()
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
    fun `a plugin's own send never re-enters the interceptor chains`() {
        // the loop this exists to prevent: one plugin rewrites every sendMessage, another sends one
        val watcher = startPlugin("watcher", "interceptRpc(messages.sendMessage)")
        assertNull(watcher.interceptRpc("messages.sendMessage"))
        val plugin = granted()

        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send("D$alice")))
        drain()

        assertTrue(watcher.js.dispatches.isEmpty(), "the write walked into a chain")
        assertEquals(1, connections().sent.size, "the request did not go out")
        assertEquals(
            "messages.sendMessage",
            desu.inugram.core.plugins.TlNames.classNameToTlName(connections().lastSent()!!.request.javaClass),
        )
    }

    /**
     * the scenario the lease exists for: on CONNECTION_NOT_INITED stock re-sends the very request
     * instance it was handed, with a fresh token and without invoking the delegate. A lease the
     * first send consumed would let that retry walk into a chain over a request the write is still
     * holding.
     */
    @Test
    fun `the lease outlives the first send and ends with the delegate`() {
        val plugin = granted()
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send("D$alice")))
        drain()
        val sent = connections().lastSent()!!

        val watcher = startPlugin("watcher", "interceptRpc(messages.sendMessage)")
        assertNull(watcher.interceptRpc("messages.sendMessage"))
        // still in flight: stock's own re-send of this instance is ours, not an app request
        connections().inu_retryNotInited(sent, 5)
        drain()
        assertTrue(watcher.js.dispatches.isEmpty(), "the retry of a plugin's own send walked into a chain")

        sent.answer(updatesWith(sentMessage(7, "hi")), null, 0L)
        drain()

        // answered, so nothing further can be a re-send of it: the same instance is now an ordinary
        // app request and the interceptor sees it
        connections().sendRequestInternal(sent.request, null, null, null, null, 0, 0, 0, false, 6)
        drain()
        assertEquals(1, watcher.js.dispatches.size, "the lease outlived the flight")
    }

    @Test
    fun `no write reaches a secret chat, whichever op names it`() {
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
        // and one *into* a secret chat, where the source resolves perfectly well
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
    fun `an uncached peer is not-found with nothing sent`() {
        val plugin = granted()
        assertPluginError("not-found", write(plugin, PluginWrites.OP_SEND_MESSAGE, send("D4242424242")))
        assertTrue(connections().sent.isEmpty())
    }

    @Test
    fun `every op is gated on its own scope, on the side that owns the data`() {
        val plugin = startPlugin("writes", "account.write(send)")
        val refused = mapOf(
            PluginWrites.OP_EDIT_MESSAGE to "edit",
            PluginWrites.OP_DELETE_MESSAGES to "delete",
            PluginWrites.OP_FORWARD_MESSAGES to "forward",
            PluginWrites.OP_SET_REACTION to "react",
            PluginWrites.OP_READ_HISTORY to "read",
            PluginWrites.OP_SEND_TYPING to "typing",
            PluginWrites.OP_SET_DRAFT to "draft",
        )
        for ((op, scope) in refused) {
            val wire = write(plugin, op, send("D$alice"))
            assertPluginError("not-granted", wire)
            assertTrue(wire!!.contains("account.write($scope)"), "op $op named the wrong grant: $wire")
        }
        // the one it does hold reaches the network
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send("D$alice")))
        drain()
        assertEquals(1, connections().sent.size)
    }

    @Test
    fun `a send carries its text, entities, reply and schedule`() {
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
    fun `a topic post with no reply of its own replies to the topic's root`() {
        val plugin = granted()
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send("D-$channel").put("topicId", "12")))
        drain()
        val reply = (connections().lastSent()!!.request as TLRPC.TL_messages_sendMessage).reply_to
            as TLRPC.TL_inputReplyToMessage
        assertEquals(12, reply.reply_to_msg_id)
        assertEquals(12, reply.top_msg_id)
    }

    @Test
    fun `a channel deletes through its own rpc and everything else through the bare one`() {
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

    /**
     * a channel the app only ever saw quoted in someone else's message is a `min` chat: stock gives
     * it no `access_hash` and addresses it as `inputPeerChannelFromMessage`, a sibling of
     * `TL_inputPeerChannel`. Taking the peerless `messages.deleteMessages` branch for one does not
     * fail: those ids address the user's *own* message-id space, so the server deletes whatever
     * message carries that id in a private chat, revoked for the other party too.
     */
    @Test
    fun `a min channel still deletes and reads through the channel rpcs`() {
        val plugin = granted()
        val min = 3003L
        MessagesController.getInstance(0).inu_putChat(
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
    fun `marking a topic read is the thread rpc, and a channel its own`() {
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
    fun `a reaction is an emoji or a custom one, and clearing is an empty list`() {
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
    fun `clearing a draft is an empty text rather than a missing call`() {
        val plugin = granted()
        val arg = JSONObject().put("peer", "D$alice").put("text", JSONObject.NULL)
        assertNull(write(plugin, PluginWrites.OP_SET_DRAFT, arg))
        drain()
        assertEquals("", (connections().lastSent()!!.request as TLRPC.TL_messages_saveDraft).message)
    }

    @Test
    fun `a send answers with the message the server made, read-only`() {
        val plugin = granted()
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send("D$alice")))
        drain()
        connections().lastSent()!!.answer(updatesWith(sentMessage(77, "hi")), null, 0L)

        val wire = settled(plugin)
        val handle = handleOf(wire)
        assertTrue(handle.readOnly, "a sent message is app state and must not be writable")
        assertEquals("I77", plugin.tl().tlGet(handle.id, "id"))
        assertEquals("Shi", plugin.tl().tlGet(handle.id, "message"))
    }

    @Test
    fun `the app applies what the plugin sent, exactly where it applies what the ui sent`() {
        val plugin = granted()
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send("D$alice")))
        drain()
        val updates = updatesWith(sentMessage(77, "hi"))
        connections().lastSent()!!.answer(updates, null, 0L)
        drain()
        assertEquals(listOf(updates), MessagesController.getInstance(0).processed)
    }

    @Test
    fun `a short sent update is rebuilt from the request rather than answered as nothing`() {
        val plugin = granted()
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send("D$alice", "rebuild me")))
        drain()
        val short = TLRPC.TL_updateShortSentMessage()
        short.id = 909
        short.date = 1700
        connections().lastSent()!!.answer(short, null, 0L)

        val handle = handleOf(settled(plugin))
        assertEquals("I909", plugin.tl().tlGet(handle.id, "id"))
        assertEquals("Srebuild me", plugin.tl().tlGet(handle.id, "message"))
        assertEquals("I1700", plugin.tl().tlGet(handle.id, "date"))
    }

    @Test
    fun `a write may name you, and what it answers names you back, under the write scope alone`() {
        // the read side gates naming yourself behind `account.read(self)`; the write side does not,
        // and the contract says why - a send to yourself is Saved Messages rather than a lookup of
        // who you are, and every send's answer carries you as its sender whatever the bridge does
        val plugin = startPlugin("writes", "account.write(send)")
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send("S", "note to self")))
        drain()
        val short = TLRPC.TL_updateShortSentMessage()
        short.id = 5
        connections().lastSent()!!.answer(short, null, 0L)

        val handle = handleOf(settled(plugin))
        assertEquals("S100", plugin.tl().tlGet(handle.id, "from_id").let {
            plugin.tl().tlGet(handleOf(it).id, "user_id")
        })
    }

    @Test
    fun `an answer with no message in it fails rather than resolving with null`() {
        val plugin = granted()
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send("D$alice")))
        drain()
        connections().lastSent()!!.answer(TLRPC.TL_updates(), null, 0L)
        assertPluginError("internal", settled(plugin))
    }

    @Test
    fun `a server error reaches the plugin as an RpcError`() {
        val plugin = granted()
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send("D$alice")))
        drain()
        val error = TLRPC.TL_error().apply { code = 420; text = "FLOOD_WAIT_3" }
        connections().lastSent()!!.answer(null, error, 0L)
        assertEquals("R420:FLOOD_WAIT_3", settled(plugin))
    }

    @Test
    fun `nothing settles inside the upcall`() {
        val plugin = granted()
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send("D$alice")))
        drain()
        connections().lastSent()!!.answer(updatesWith(sentMessage(1, "hi")), null, 0L)
        // the delegate has run, but the engine is only entered from a globalQueue runnable: settling
        // from inside the upcall is the same-engine re-entry that aborts the process
        assertTrue(plugin.js.writeResults.isEmpty(), "the write settled before the queue ran")
        drain()
        assertEquals(1, plugin.js.writeResults.size)
    }

    /**
     * stock frees a response's buffers the moment the delegate returns, on stageQueue - and the
     * settle reads that response a globalQueue hop later. Every sibling crossing takes the response
     * over for the hop and performs the one free itself; this one is no different.
     */
    @Test
    fun `the response outlives the queue hop the settle takes, and is freed exactly once`() {
        val plugin = granted()
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send("D$alice")))
        drain()
        val updates = updatesWith(sentMessage(7, "hi"))
        var freedWhenSettled = -1
        plugin.js.onWriteResult = { freedWhenSettled = updates.inu_freeCount }

        connections().lastSent()!!.answer(updates, null, 0L)
        drain()

        assertEquals(0, freedWhenSettled, "the response was freed before the settle read it")
        assertEquals(1, updates.inu_freeCount, "the settle owes exactly one free of what it took over")
    }

    @Test
    fun `a settle for an engine the plugin no longer runs on is dropped`() {
        val plugin = granted()
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send("D$alice")))
        drain()
        val stale = plugin.js
        connections().lastSent()!!.answer(updatesWith(sentMessage(1, "hi")), null, 0L)
        // a reload between the answer and the settle: request ids restart on the new engine
        plugin.engine = QuickJs()
        drain()
        assertTrue(stale.writeResults.isEmpty(), "a stale settle reached a dead engine")
    }

    @Test
    fun `an unknown op is refused rather than gated on a fallback scope`() {
        val plugin = granted()
        assertPluginError("internal", write(plugin, 99, send("D$alice")))
    }
}

