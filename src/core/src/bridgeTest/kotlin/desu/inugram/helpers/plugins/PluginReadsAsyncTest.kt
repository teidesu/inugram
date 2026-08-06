package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.TlWire
import desu.inugram.helpers.plugins.tg.PluginReads
import desu.inugram.helpers.plugins.tg.PluginRpc
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_forum

/**
 * The host half of the reads that may go to the network: which request each one sends, what the
 * answer's wire looks like, how a page's cursor is derived, and that the takeover filter still runs
 * over everything they materialize.
 */
class PluginReadsAsyncTest {
    private val self = 100L
    private val alice = 222L
    private val channel = 1001L
    private val forum = 3003L
    private val group = 2002L

    @Before
    fun setUp() {
        resetBridge()
        val config = UserConfig.getInstance(0)
        config.clientUserId = self
        config.currentUser = user(self, "selfuser")
        val controller = MessagesController.getInstance(0)
        controller.inu_putUser(user(alice, "alice"))
        controller.inu_putChat(broadcast(channel, "newschan"))
        controller.inu_putChat(basicGroup(group))
        controller.inu_putChat(broadcast(forum, "forumchan").apply { megagroup = true; this.forum = true })
    }

    private fun user(id: Long, username: String?) = TLRPC.TL_user().apply {
        this.id = id
        this.username = username
        access_hash = id * 10
    }

    private fun broadcast(id: Long, username: String?) = TLRPC.TL_channel().apply {
        this.id = id
        this.username = username
        access_hash = id * 10
        broadcast = true
    }

    private fun basicGroup(id: Long) = TLRPC.TL_chat().apply { this.id = id }

    private fun granted(vararg extra: String) =
        startPlugin("async-reads", "account.read(self,peers,dialogs,messages,history,draft)", *extra)

    private fun reads(plugin: Plugin): QuickJs.ReadsListener = plugin.js.readsListener!!

    private fun fetch(plugin: Plugin, op: Int, arg: String, requestId: Long = 1L, account: Int = 0): String? =
        reads(plugin).accountFetch(account, requestId, op, arg)

    /** what the engine hands back to JS once the whole exchange has settled */
    private fun settled(plugin: Plugin): String {
        drain()
        return plugin.js.fetchResults.single().resultWire
    }

    private fun answerWith(response: TLObject?, error: TLRPC.TL_error? = null) {
        connections().lastSent()!!.answer(response, error, 0L)
    }

    private fun fieldOf(plugin: Plugin, wire: String, key: String): String =
        plugin.tl().tlGet(handleId(wire), key)

    @Test
    fun `every async read checks its own scope on the side that owns the data`() {
        val plugin = startPlugin("narrow", "account.read(peers)")
        assertPluginError("not-granted", fetch(plugin, PluginReads.OP_HISTORY, "S\n10\n0\n0\n0\n0"))
        assertPluginError("not-granted", fetch(plugin, PluginReads.OP_DIALOGS, "0\n10\n"))
        assertPluginError("not-granted", fetch(plugin, PluginReads.OP_TOPICS, "D-$forum\n10\n"))
        assertPluginError("not-granted", reads(plugin).accountRead(0, PluginReads.OP_DRAFT, "S\n0"))
        assertTrue(connections().sent.isEmpty(), "a refused read must not reach the network")
    }

    @Test
    fun `the refusal names the grant that would have allowed it`() {
        val plugin = startPlugin("none")
        val decoded = TlWire.decode(fetch(plugin, PluginReads.OP_HISTORY, "S\n10\n0\n0\n0\n0")!!)
        assertEquals("account.read(history)", (decoded as TlWire.Value.PluginErr).grant)
    }

    /**
     * `getUserFull` on yourself is the one read `account.read(self)` alone opens, and only for the
     * spec that says "myself" - the engine gates before any peer is resolved, so a dialog id that
     * happens to be yours cannot be the same thing
     */
    @Test
    fun `getUserFull on yourself needs only the self scope, and only spelled as yourself`() {
        val plugin = startPlugin("selfonly", "account.read(self)")
        assertNull(fetch(plugin, PluginReads.OP_USER_FULL, "S"), "'me' is allowed under self alone")
        assertPluginError("not-granted", fetch(plugin, PluginReads.OP_USER_FULL, "D$self"))
        assertPluginError("not-granted", fetch(plugin, PluginReads.OP_USER_FULL, "D$alice"))
        // and it buys that one op, not every op that can be pointed at yourself
        assertPluginError("not-granted", fetch(plugin, PluginReads.OP_CHAT_FULL, "S"))
        assertPluginError("not-granted", fetch(plugin, PluginReads.OP_HISTORY, "S\n10\n0\n0\n0\n0"))
        assertPluginError("not-granted", fetch(plugin, PluginReads.OP_TOPICS, "S\n10\n"))
    }

    @Test
    fun `getUserFull answers from the app's own cache without sending anything`() {
        val plugin = granted()
        val full = TLRPC.TL_userFull().apply { id = alice; about = "bio" }.synced()
        MessagesController.getInstance(0).fullUsers.put(alice, full)

        assertNull(fetch(plugin, PluginReads.OP_USER_FULL, "D$alice"))
        assertTrue(connections().sent.isEmpty())
        assertEquals("bio", stringOf(fieldOf(plugin, settled(plugin), "about")))
    }

    @Test
    fun `an uncached full user is fetched and comes back read-only`() {
        val plugin = granted()
        assertNull(fetch(plugin, PluginReads.OP_USER_FULL, "D$alice"))

        val sent = connections().lastSent()!!
        assertTrue(sent.request is TLRPC.TL_users_getFullUser)
        assertEquals(alice, ((sent.request as TLRPC.TL_users_getFullUser).id as TLRPC.TL_inputUser).user_id)

        answerWith(TLRPC.TL_users_userFull().apply {
            full_user = TLRPC.TL_userFull().apply { id = alice; about = "bio" }.synced()
            users.add(user(alice, "alice"))
        })
        val wire = settled(plugin)
        assertTrue(handleOf(wire).readOnly)
        assertEquals("bio", stringOf(fieldOf(plugin, wire, "about")))
    }

    @Test
    fun `a full chat is one rpc for a channel and another for a basic group`() {
        val plugin = granted()
        assertNull(fetch(plugin, PluginReads.OP_CHAT_FULL, "D-$channel"))
        assertTrue(connections().lastSent()!!.request is TLRPC.TL_channels_getFullChannel)

        val second = granted()
        assertNull(fetch(second, PluginReads.OP_CHAT_FULL, "D-$group"))
        val request = connections().lastSent()!!.request
        assertTrue(request is TLRPC.TL_messages_getFullChat)
        assertEquals(group, (request as TLRPC.TL_messages_getFullChat).chat_id)
    }

    @Test
    fun `asking for a chat's full info about a user says which mistake it was`() {
        val plugin = granted()
        assertPluginError("invalid-argument", fetch(plugin, PluginReads.OP_CHAT_FULL, "D$alice"))
        assertPluginError("not-found", fetch(plugin, PluginReads.OP_CHAT_FULL, "D-4242"))
        assertTrue(connections().sent.isEmpty())
    }

    @Test
    fun `a server error rejects with the server's own code`() {
        val plugin = granted()
        fetch(plugin, PluginReads.OP_USER_FULL, "D$alice")
        answerWith(null, TLRPC.TL_error().apply { code = 420; text = "FLOOD_WAIT_5" })
        val decoded = TlWire.decode(settled(plugin)) as TlWire.Value.RpcError
        assertEquals(420, decoded.code)
        assertEquals("FLOOD_WAIT_5", decoded.text)
    }

    private fun message(id: Int, dialogId: Long, text: String, date: Int = 0) = TLRPC.TL_message().apply {
        this.id = id
        message = text
        this.date = date
        peer_id = peerUser(dialogId)
    }.synced()

    @Test
    fun `getHistory sends messages_getHistory and answers one wire per message`() {
        val plugin = granted()
        assertNull(fetch(plugin, PluginReads.OP_HISTORY, "D$alice\n7\n42\n0\n0\n0"))

        val request = connections().lastSent()!!.request as TLRPC.TL_messages_getHistory
        assertEquals(alice, (request.peer as TLRPC.TL_inputPeerUser).user_id)
        assertEquals(7, request.limit)
        assertEquals(42, request.offset_id)

        answerWith(TLRPC.TL_messages_messagesSlice().apply {
            messages.add(message(9, alice, "hi"))
            messages.add(message(8, alice, "there"))
        })
        val wires = settled(plugin).split("\n")
        assertEquals(2, wires.size)
        assertEquals("hi", stringOf(fieldOf(plugin, wires[0], "message")))
        assertEquals("there", stringOf(fieldOf(plugin, wires[1], "message")))
    }

    @Test
    fun `an empty history is an empty wire rather than one empty element`() {
        val plugin = granted()
        fetch(plugin, PluginReads.OP_HISTORY, "D$alice\n0\n0\n0\n0\n0")
        answerWith(TLRPC.TL_messages_messages())
        assertEquals("", settled(plugin))
    }

    /** a topic is a thread, which is a different rpc rather than a filter on the same one */
    @Test
    fun `a topic's history is messages_getReplies`() {
        val plugin = granted()
        fetch(plugin, PluginReads.OP_HISTORY, "D-$forum\n10\n0\n0\n0\n5")
        val request = connections().lastSent()!!.request as TLRPC.TL_messages_getReplies
        assertEquals(5, request.msg_id)
        assertEquals(10, request.limit)
    }

    @Test
    fun `a limit past what telegram accepts is clamped rather than sent`() {
        val plugin = granted()
        fetch(plugin, PluginReads.OP_HISTORY, "D$alice\n5000\n0\n0\n0\n0")
        assertEquals(100, (connections().lastSent()!!.request as TLRPC.TL_messages_getHistory).limit)
    }

    @Test
    fun `history for a peer with nothing cached is refused before it is sent`() {
        val plugin = granted()
        assertPluginError("not-found", fetch(plugin, PluginReads.OP_HISTORY, "D4242\n10\n0\n0\n0\n0"))
        assertTrue(connections().sent.isEmpty())
    }

    /**
     * the highest-value assertion here: `getHistory` is a *new* materialization path, and the whole
     * point of the filter living inside [TlHandles] is that adding one cannot open a hole
     */
    @Test
    fun `a login code fetched from the service peer comes back redacted`() {
        val plugin = granted()
        fetch(plugin, PluginReads.OP_HISTORY, "D777000\n10\n0\n0\n0\n0")
        MessagesController.getInstance(0).inu_putUser(user(777000L, "telegram"))
        // the peer is only cached after the send, so re-issue it now that it resolves
        resetSends()
        assertNull(fetch(plugin, PluginReads.OP_HISTORY, "D777000\n10\n0\n0\n0\n0", requestId = 2L))
        answerWith(TLRPC.TL_messages_messages().apply {
            messages.add(serviceMessage("Login code: 12345", id = 9))
        })
        drain()
        val wire = plugin.js.fetchResults.single().resultWire
        assertEquals("Login code: *****", stringOf(fieldOf(plugin, wire, "message")))
    }

    /**
     * the shape this path actually answers with: the server omits `from_id` in a 1:1 dialog and
     * stock backfills it only when loading from its own cache, so a fetched login code is exactly
     * the case a filter keyed on `from_id` alone reads in clear. the outgoing message is the
     * counterexample that keeps the first assertion honest - the rule is the sender, not the dialog.
     */
    @Test
    fun `a login code with no sender field is redacted, and your own text in that chat is not`() {
        val plugin = granted()
        MessagesController.getInstance(0).inu_putUser(user(777000L, "telegram"))
        assertNull(fetch(plugin, PluginReads.OP_HISTORY, "D777000\n10\n0\n0\n0\n0"))
        answerWith(TLRPC.TL_messages_messages().apply {
            messages.add(TLRPC.TL_message().apply {
                id = 9
                message = "Login code: 12345"
                peer_id = peerUser(777000L)
            }.synced())
            messages.add(TLRPC.TL_message().apply {
                id = 10
                message = "mine, and also 12345"
                out = true
                peer_id = peerUser(777000L)
            }.synced())
        })
        val wires = settled(plugin).split("\n")
        assertEquals("Login code: *****", stringOf(fieldOf(plugin, wires[0], "message")))
        assertEquals("mine, and also 12345", stringOf(fieldOf(plugin, wires[1], "message")))
    }

    @Test
    fun `the filter is off for a plugin that disabled it`() {
        val plugin = granted("unsafe.disableApiFiltering")
        MessagesController.getInstance(0).inu_putUser(user(777000L, "telegram"))
        assertNull(fetch(plugin, PluginReads.OP_HISTORY, "D777000\n10\n0\n0\n0\n0"))
        answerWith(TLRPC.TL_messages_messages().apply {
            messages.add(serviceMessage("Login code: 12345", id = 9))
        })
        assertEquals("Login code: 12345", stringOf(fieldOf(plugin, settled(plugin), "message")))
    }

    private fun resetSends() {
        connections().sent.clear()
    }

    private fun dialog(dialogId: Long, topMessage: Int) = TLRPC.TL_dialog().apply {
        peer = peerUser(dialogId)
        top_message = topMessage
    }

    @Test
    fun `a full page of dialogs carries a cursor built from its last row`() {
        val plugin = granted()
        assertNull(fetch(plugin, PluginReads.OP_DIALOGS, "1\n2\n"))

        val request = connections().lastSent()!!.request as TLRPC.TL_messages_getDialogs
        assertEquals(1, request.folder_id)
        assertEquals(2, request.limit)
        assertTrue(request.offset_peer is TLRPC.TL_inputPeerEmpty, "an unpaged call starts from the top")

        answerWith(TLRPC.TL_messages_dialogsSlice().apply {
            dialogs.add(dialog(self, 4))
            dialogs.add(dialog(alice, 9))
            messages.add(message(9, alice, "hi", date = 1715540640))
        })
        val wire = settled(plugin)
        val lines = wire.split("\n")
        assertEquals("1715540640,9,$alice", lines[0], "the cursor is the last row's date, id and peer")
        assertEquals(3, lines.size)
        assertTrue(handleOf(lines[1]).readOnly)
    }

    @Test
    fun `a short slice and a non-slice both end the list`() {
        val plugin = granted()
        fetch(plugin, PluginReads.OP_DIALOGS, "0\n5\n")
        answerWith(TLRPC.TL_messages_dialogsSlice().apply { dialogs.add(dialog(alice, 9)) })
        assertEquals("", settled(plugin).split("\n")[0], "a slice shorter than the limit is the end")

        val second = granted()
        fetch(second, PluginReads.OP_DIALOGS, "0\n1\n")
        answerWith(TLRPC.TL_messages_dialogs().apply { dialogs.add(dialog(alice, 9)) })
        assertEquals("", settled(second).split("\n")[0], "a non-slice answer is the whole list")
    }

    @Test
    fun `a cursor's offsets are what the next page is asked with`() {
        val plugin = granted()
        assertNull(fetch(plugin, PluginReads.OP_DIALOGS, "0\n2\n1715540640,9,$alice"))
        val request = connections().lastSent()!!.request as TLRPC.TL_messages_getDialogs
        assertEquals(1715540640, request.offset_date)
        assertEquals(9, request.offset_id)
        assertEquals(alice, (request.offset_peer as TLRPC.TL_inputPeerUser).user_id)
    }

    /** stock's own getInputPeer would invent a zero access_hash the server refuses */
    @Test
    fun `a cursor whose peer left the cache pages from the date alone`() {
        val plugin = granted()
        fetch(plugin, PluginReads.OP_DIALOGS, "0\n2\n1715540640,9,4242")
        val request = connections().lastSent()!!.request as TLRPC.TL_messages_getDialogs
        assertTrue(request.offset_peer is TLRPC.TL_inputPeerEmpty)
        assertEquals(1715540640, request.offset_date)
    }

    @Test
    fun `an empty page is a bare separator rather than one empty element`() {
        val plugin = granted()
        fetch(plugin, PluginReads.OP_DIALOGS, "0\n5\n")
        answerWith(TLRPC.TL_messages_dialogs())
        assertEquals("\n", settled(plugin))
    }

    @Test
    fun `getTopics refuses anything that is not a forum before it sends`() {
        val plugin = granted()
        assertPluginError("invalid-argument", fetch(plugin, PluginReads.OP_TOPICS, "D-$channel\n10\n"))
        assertPluginError("invalid-argument", fetch(plugin, PluginReads.OP_TOPICS, "D$alice\n10\n"))
        assertPluginError("not-found", fetch(plugin, PluginReads.OP_TOPICS, "D-4242\n10\n"))
        assertTrue(connections().sent.isEmpty())
    }

    @Test
    fun `a page of topics carries a cursor built from its last topic`() {
        val plugin = granted()
        assertNull(fetch(plugin, PluginReads.OP_TOPICS, "D-$forum\n2\n"))

        val request = connections().lastSent()!!.request as TL_forum.TL_messages_getForumTopics
        assertEquals(forum, (request.peer as TLRPC.TL_inputPeerChannel).channel_id)
        assertEquals(2, request.limit)

        answerWith(TLRPC.TL_messages_forumTopics().apply {
            topics.add(TLRPC.TL_forumTopic().apply { id = 1; top_message = 3; title = "General" }.synced())
            topics.add(TLRPC.TL_forumTopic().apply { id = 7; top_message = 11; title = "Chat" }.synced())
            messages.add(message(11, -forum, "hi", date = 1715540000))
        })
        val lines = settled(plugin).split("\n")
        assertEquals("1715540000,11,7", lines[0])
        assertEquals(3, lines.size)
        assertEquals("Chat", stringOf(fieldOf(plugin, lines[2], "title")))
    }

    @Test
    fun `a topic cursor's offsets are what the next page is asked with`() {
        val plugin = granted()
        fetch(plugin, PluginReads.OP_TOPICS, "D-$forum\n2\n1715540000,11,7")
        val request = connections().lastSent()!!.request as TL_forum.TL_messages_getForumTopics
        assertEquals(1715540000, request.offset_date)
        assertEquals(11, request.offset_id)
        assertEquals(7, request.offset_topic)
    }

    private fun draft(plugin: Plugin, spec: String, topicId: Long = 0L): String =
        reads(plugin).accountRead(0, PluginReads.OP_DRAFT, "$spec\n$topicId")

    private fun jsonOf(wire: String): JSONObject = JSONObject((TlWire.decode(wire) as TlWire.Value.Json).json)

    @Test
    fun `a draft reads back as the text and entities the input field would show`() {
        val plugin = granted()
        MediaDataController.getInstance(0).inu_putDraft(alice, 0L, TLRPC.TL_draftMessage().apply {
            message = "hello world"
            entities.add(TLRPC.TL_messageEntityBold().apply { offset = 0; length = 5 })
        }.synced())

        val json = jsonOf(draft(plugin, "D$alice"))
        assertEquals("hello world", json.getString("text"))
        assertEquals(1, json.getJSONArray("entities").length())
        assertEquals("messageEntityBold", json.getJSONArray("entities").getJSONObject(0).getString("_"))
    }

    @Test
    fun `a draft with no entities carries none, and no draft at all is null`() {
        val plugin = granted()
        MediaDataController.getInstance(0).inu_putDraft(alice, 0L, TLRPC.TL_draftMessage().apply {
            message = "plain"
        }.synced())
        val json = jsonOf(draft(plugin, "D$alice"))
        assertEquals("plain", json.getString("text"))
        assertTrue(json.isNull("entities") && !json.has("entities"))

        assertEquals("N", draft(plugin, "D-$channel"), "a chat with no draft")
        MediaDataController.getInstance(0).inu_putDraft(self, 0L, TLRPC.TL_draftMessageEmpty())
        assertEquals("N", draft(plugin, "S"), "a cleared draft is the same as none")
    }

    @Test
    fun `a topic's draft is its own`() {
        val plugin = granted()
        val drafts = MediaDataController.getInstance(0)
        drafts.inu_putDraft(-forum, 0L, TLRPC.TL_draftMessage().apply { message = "root" }.synced())
        drafts.inu_putDraft(-forum, 7L, TLRPC.TL_draftMessage().apply { message = "in the topic" }.synced())
        assertEquals("root", jsonOf(draft(plugin, "D-$forum")).getString("text"))
        assertEquals("in the topic", jsonOf(draft(plugin, "D-$forum", topicId = 7L)).getString("text"))
    }

    /** a reload gives the plugin a new engine whose request ids restart */
    @Test
    fun `an answer that arrives after a reload settles nothing`() {
        val plugin = granted()
        fetch(plugin, PluginReads.OP_USER_FULL, "D$alice")
        val stale = plugin.js
        plugin.engine = QuickJs()
        PluginReads.attach(plugin, plugin.js)
        PluginRpc.attach(plugin, plugin.js)

        answerWith(TLRPC.TL_users_userFull().apply { full_user = TLRPC.TL_userFull() })
        drain()
        assertTrue(stale.fetchResults.isEmpty())
        assertTrue(plugin.js.fetchResults.isEmpty())
    }

    @Test
    fun `a slot nobody is logged into answers not-found rather than reading slot zero`() {
        val plugin = granted()
        assertPluginError("not-found", fetch(plugin, PluginReads.OP_HISTORY, "S\n10\n0\n0\n0\n0", account = 3))
        assertNotNull(fetch(plugin, PluginReads.OP_DIALOGS, "0\n10\n", account = 3))
        assertTrue(connections(3).sent.isEmpty())
    }
}
