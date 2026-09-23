package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.telegram.PluginReads
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_forum

class PluginReadsAsyncTest {
    private val self = 100L
    private val alice = 222L
    private val channel = 1001L
    private val forum = 3003L
    private val group = 2002L

    @Before
    fun setUp() {
        resetBridge()
        TestApp.signInAs(0, user(self, "selfuser"))
        TestApp.putUser(user(alice, "alice"))
        TestApp.putChat(broadcast(channel, "newschan"))
        TestApp.putChat(basicGroup(group))
        TestApp.putChat(broadcast(forum, "forumchan").apply { megagroup = true; this.forum = true })
    }

    private fun granted(vararg extra: String) =
        startPlugin("async-reads", "account.read(self,peers,dialogs,messages,history,draft)", *extra)

    private fun reads(plugin: Plugin): ReadsListener = plugin.js.listener!!

    private fun fetch(
        plugin: Plugin,
        op: Int,
        peer: String,
        args: String = "{}",
        cursor: String = "",
        requestId: Long = 1L,
        account: Int = 0,
    ): String? = reads(plugin).accountFetch(account, requestId, op, peer, args, cursor)

    private fun settled(plugin: Plugin): String {
        drain()
        return plugin.js.readResults.single().resultWire
    }

    private fun answerWith(response: TLObject?, error: TLRPC.TL_error? = null) {
        connections().lastSent()!!.answer(response, error, 0L)
    }

    private fun awaitSent() = awaitValue("the fetch never reached the network") { connections().lastSent() }

    private fun message(id: Int, peer: TLRPC.Peer, text: String, date: Int = 0) =
        TLRPC.TL_message().apply {
            this.id = id
            peer_id = peer
            message = text
            this.date = date
        }.synced()

    private fun answerMessages(vararg messages: TLRPC.Message) {
        answerWith(TLRPC.TL_messages_messages().apply { this.messages.addAll(messages) })
    }

    private fun awaitSettled(plugin: Plugin) =
        awaitValue("the fetch never settled") { plugin.js.readResults.singleOrNull()?.resultWire }

    @Test
    fun a_message_only_sqlite_has_is_read_from_disk_without_a_request() {
        val plugin = granted()
        val mid = 987654
        storeMessageOnDisk(alice, message(mid, peerUser(alice), "from disk"))
        try {
            assertNull(fetch(plugin, PluginReads.OP_FETCH_MESSAGES, "D$alice", "{\"ids\":[$mid]}"))
            assertEquals("from disk", decodeString(readTlField(plugin, awaitSettled(plugin), "message")))
            assertTrue(connections().sent.isEmpty(), "what sqlite already had must not cost a request")

            plugin.js.readResults.clear()
            assertNull(fetch(plugin, PluginReads.OP_FETCH_MESSAGES, "D0", "{\"ids\":[$mid]}", requestId = 2L))
            assertEquals("from disk", decodeString(readTlField(plugin, awaitSettled(plugin), "message")))
            assertTrue(connections().sent.isEmpty())
        } finally {
            deleteMessageOnDisk(mid)
        }
    }

    @Test
    fun getMessages_answers_from_memory_without_sending_anything() {
        val plugin = granted()
        TestApp.cacheDialogMessage(0, self, message(7, peerUser(self), "hi"))

        assertNull(fetch(plugin, PluginReads.OP_FETCH_MESSAGES, "S", "{\"ids\":[7]}"))
        drain()
        assertTrue(connections().sent.isEmpty(), "what memory already had must not cost a request")
        assertEquals("hi", decodeString(readTlField(plugin, settled(plugin), "message")))
    }

    @Test
    fun an_uncached_message_is_fetched_and_a_miss_stays_null_in_place() {
        val plugin = granted()
        assertNull(fetch(plugin, PluginReads.OP_FETCH_MESSAGES, "D$alice", "{\"ids\":[7,8]}"))

        val sent = awaitSent()
        val request = sent.request as TLRPC.TL_messages_getMessages
        assertEquals(listOf(7, 8), request.id.toList(), "a user dialog's ids are common-box ids")

        answerMessages(message(7, peerUser(alice), "found"))
        val wires = settled(plugin).split("\n")
        assertEquals(2, wires.size)
        assertEquals("found", decodeString(readTlField(plugin, wires[0], "message")))
        assertEquals("N", wires[1], "an id the account cannot see is a null in its own slot")
    }

    @Test
    fun a_channel_names_itself_because_its_ids_mean_nothing_without_it() {
        val plugin = granted()
        assertNull(fetch(plugin, PluginReads.OP_FETCH_MESSAGES, "D-$channel", "{\"ids\":[9]}"))

        val request = awaitSent().request as TLRPC.TL_channels_getMessages
        assertEquals(channel, (request.channel as TLRPC.TL_inputChannel).channel_id)
        assertEquals(listOf(9), request.id.toList())

        answerMessages(message(9, peerChannel(channel), "chan"))
        assertEquals("chan", decodeString(readTlField(plugin, settled(plugin), "message")))
    }

    @Test
    fun the_common_box_sends_no_peer_at_all() {
        val plugin = granted()
        assertNull(fetch(plugin, PluginReads.OP_FETCH_MESSAGES, "D0", "{\"ids\":[7]}"))

        val request = awaitSent().request as TLRPC.TL_messages_getMessages
        assertEquals(listOf(7), request.id.toList())

        answerMessages(message(7, peerUser(alice), "boxed"))
        assertEquals("boxed", decodeString(readTlField(plugin, settled(plugin), "message")))
    }

    @Test
    fun a_message_from_another_dialog_is_not_the_answer() {
        val plugin = granted()
        assertNull(fetch(plugin, PluginReads.OP_FETCH_MESSAGES, "D$alice", "{\"ids\":[7]}"))
        awaitSent()

        answerMessages(message(7, peerUser(self), "someone else's"))
        assertEquals("N", settled(plugin))
    }

    @Test
    fun getUserFull_answers_from_the_app_s_own_cache_without_sending_anything() {
        val plugin = granted()
        val full = TLRPC.TL_userFull().apply { id = alice; about = "bio" }.synced()
        TestApp.putUserFull(alice, full)

        assertNull(fetch(plugin, PluginReads.OP_USER_FULL, "D$alice"))
        assertTrue(connections().sent.isEmpty())
        assertEquals("bio", decodeString(readTlField(plugin, settled(plugin), "about")))
    }

    @Test
    fun an_uncached_full_user_is_fetched_and_comes_back_read_only() {
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
        assertTrue(decodeHandle(wire).readOnly)
        assertEquals("bio", decodeString(readTlField(plugin, wire, "about")))
    }

    @Test
    fun a_full_chat_is_one_rpc_for_a_channel_and_another_for_a_basic_group() {
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
    fun asking_for_a_chat_s_full_info_about_a_user_says_which_mistake_it_was() {
        val plugin = granted()
        assertPluginError("invalid-argument", fetch(plugin, PluginReads.OP_CHAT_FULL, "D$alice"))
        assertPluginError("not-found", fetch(plugin, PluginReads.OP_CHAT_FULL, "D-4242"))
        assertTrue(connections().sent.isEmpty())
    }

    @Test
    fun a_server_error_rejects_with_the_server_s_own_code() {
        val plugin = granted()
        fetch(plugin, PluginReads.OP_USER_FULL, "D$alice")
        answerWith(null, TLRPC.TL_error().apply { code = 420; text = "FLOOD_WAIT_5" })
        val decoded = PluginWire.decode(settled(plugin)) as PluginWire.Value.RpcError
        assertEquals(420, decoded.code)
        assertEquals("FLOOD_WAIT_5", decoded.text)
    }

    @Test
    fun getHistory_sends_messages_getHistory_and_answers_one_wire_per_message() {
        val plugin = granted()
        assertNull(fetch(plugin, PluginReads.OP_HISTORY, "D$alice", "{\"limit\":7,\"offsetId\":42}"))

        val request = connections().lastSent()!!.request as TLRPC.TL_messages_getHistory
        assertEquals(alice, (request.peer as TLRPC.TL_inputPeerUser).user_id)
        assertEquals(7, request.limit)
        assertEquals(42, request.offset_id)

        answerWith(TLRPC.TL_messages_messagesSlice().apply {
            messages.add(message(9, peerUser(alice), "hi"))
            messages.add(message(8, peerUser(alice), "there"))
        })
        val wires = settled(plugin).split("\n")
        assertEquals(2, wires.size)
        assertEquals("hi", decodeString(readTlField(plugin, wires[0], "message")))
        assertEquals("there", decodeString(readTlField(plugin, wires[1], "message")))
    }

    @Test
    fun an_empty_history_is_an_empty_wire_rather_than_one_empty_element() {
        val plugin = granted()
        fetch(plugin, PluginReads.OP_HISTORY, "D$alice", "{\"limit\":0}")
        answerWith(TLRPC.TL_messages_messages())
        assertEquals("", settled(plugin))
    }

    @Test
    fun a_topic_s_history_is_messages_getReplies() {
        val plugin = granted()
        fetch(plugin, PluginReads.OP_HISTORY, "D-$forum", "{\"limit\":10,\"topicId\":5}")
        val request = connections().lastSent()!!.request as TLRPC.TL_messages_getReplies
        assertEquals(5, request.msg_id)
        assertEquals(10, request.limit)
    }

    @Test
    fun a_limit_past_what_telegram_accepts_is_clamped_rather_than_sent() {
        val plugin = granted()
        fetch(plugin, PluginReads.OP_HISTORY, "D$alice", "{\"limit\":5000}")
        assertEquals(100, (connections().lastSent()!!.request as TLRPC.TL_messages_getHistory).limit)
    }

    @Test
    fun an_argument_past_int32_is_refused_rather_than_wrapped_or_read_as_zero() {
        val plugin = granted()
        assertPluginError("invalid-argument", fetch(plugin, PluginReads.OP_HISTORY, "D$alice", "{\"offsetId\":2147483648}"))
        assertPluginError("invalid-argument", fetch(plugin, PluginReads.OP_FETCH_MESSAGES, "D$alice", "{\"ids\":[7,1e21]}"))
        assertTrue(connections().sent.isEmpty())
    }

    @Test
    fun history_for_a_peer_with_nothing_cached_is_refused_before_it_is_sent() {
        val plugin = granted()
        assertPluginError("not-found", fetch(plugin, PluginReads.OP_HISTORY, "D4242", "{\"limit\":10}"))
        assertTrue(connections().sent.isEmpty())
    }

    // the server omits `from_id` in a 1:1 dialog, and stock backfills it only when loading from cache
    @Test
    fun a_login_code_with_no_sender_field_is_redacted_and_your_own_text_in_that_chat_is_not() {
        val plugin = granted()
        TestApp.putUser(account = 0, user = user(777000L, "telegram"))
        assertNull(fetch(plugin, PluginReads.OP_HISTORY, "D777000", "{\"limit\":10}"))
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
        assertEquals("Login code: *****", decodeString(readTlField(plugin, wires[0], "message")))
        assertEquals("mine, and also 12345", decodeString(readTlField(plugin, wires[1], "message")))
    }

    private fun dialog(dialogId: Long, topMessage: Int) = TLRPC.TL_dialog().apply {
        peer = peerUser(dialogId)
        top_message = topMessage
    }

    @Test
    fun a_full_page_of_dialogs_carries_a_cursor_built_from_its_last_row() {
        val plugin = granted()
        assertNull(fetch(plugin, PluginReads.OP_DIALOGS, "", "{\"folderId\":1,\"limit\":2}"))

        val request = connections().lastSent()!!.request as TLRPC.TL_messages_getDialogs
        assertEquals(1, request.folder_id)
        assertEquals(2, request.limit)
        assertTrue(request.offset_peer is TLRPC.TL_inputPeerEmpty, "an unpaged call starts from the top")

        answerWith(TLRPC.TL_messages_dialogsSlice().apply {
            dialogs.add(dialog(self, 4))
            dialogs.add(dialog(alice, 9))
            messages.add(message(9, peerUser(alice), "hi", date = 1715540640))
        })
        val wire = settled(plugin)
        val lines = wire.split("\n")
        assertEquals("1715540640,9,$alice", lines[0], "the cursor is the last row's date, id and peer")
        assertEquals(3, lines.size)
        assertTrue(decodeHandle(lines[1]).readOnly)
    }

    @Test
    fun a_short_slice_and_a_non_slice_both_end_the_list() {
        val plugin = granted()
        fetch(plugin, PluginReads.OP_DIALOGS, "", "{\"limit\":5}")
        answerWith(TLRPC.TL_messages_dialogsSlice().apply { dialogs.add(dialog(alice, 9)) })
        assertEquals("", settled(plugin).split("\n")[0], "a slice shorter than the limit is the end")

        val second = granted()
        fetch(second, PluginReads.OP_DIALOGS, "", "{\"limit\":1}")
        answerWith(TLRPC.TL_messages_dialogs().apply { dialogs.add(dialog(alice, 9)) })
        assertEquals("", settled(second).split("\n")[0], "a non-slice answer is the whole list")
    }

    @Test
    fun a_cursor_s_offsets_are_what_the_next_page_is_asked_with() {
        val plugin = granted()
        assertNull(fetch(plugin, PluginReads.OP_DIALOGS, "", "{\"limit\":2}", "1715540640,9,$alice"))
        val request = connections().lastSent()!!.request as TLRPC.TL_messages_getDialogs
        assertEquals(1715540640, request.offset_date)
        assertEquals(9, request.offset_id)
        assertEquals(alice, (request.offset_peer as TLRPC.TL_inputPeerUser).user_id)
    }

    // stock's getInputPeer would invent a zero access_hash the server refuses
    @Test
    fun a_cursor_whose_peer_left_the_cache_pages_from_the_date_alone() {
        val plugin = granted()
        fetch(plugin, PluginReads.OP_DIALOGS, "", "{\"limit\":2}", "1715540640,9,4242")
        val request = connections().lastSent()!!.request as TLRPC.TL_messages_getDialogs
        assertTrue(request.offset_peer is TLRPC.TL_inputPeerEmpty)
        assertEquals(1715540640, request.offset_date)
    }

    @Test
    fun a_paged_dialog_read_projects_the_fields_it_was_asked_for() {
        val plugin = granted()
        fetch(plugin, PluginReads.OP_DIALOGS, "", "{\"limit\":5,\"fields\":[\"top_message\",\"peer\"]}")
        answerWith(TLRPC.TL_messages_dialogs().apply { dialogs.add(dialog(alice, 9)) })
        val element = settled(plugin).substringAfter("\n")
        val projection = JSONObject(element.substringAfter('|'))
        assertEquals(9, projection.getInt("top_message"))
        assertEquals(setOf("_", "top_message"), projection.keys().asSequence().toSet())
        val peerWire = readTlField(plugin, element, "peer")
        val userId = plugin.tl().tlGet(handleId(peerWire), "user_id")
        assertEquals(PluginWire.Value.IntNum(alice), PluginWire.decode(userId))
    }

    @Test
    fun an_empty_page_is_a_bare_separator_rather_than_one_empty_element() {
        val plugin = granted()
        fetch(plugin, PluginReads.OP_DIALOGS, "", "{\"limit\":5}")
        answerWith(TLRPC.TL_messages_dialogs())
        assertEquals("\n", settled(plugin))
    }

    @Test
    fun getTopics_refuses_anything_that_is_not_a_forum_before_it_sends() {
        val plugin = granted()
        assertPluginError("invalid-argument", fetch(plugin, PluginReads.OP_TOPICS, "D-$channel", "{\"limit\":10}"))
        assertPluginError("invalid-argument", fetch(plugin, PluginReads.OP_TOPICS, "D$alice", "{\"limit\":10}"))
        assertPluginError("not-found", fetch(plugin, PluginReads.OP_TOPICS, "D-4242", "{\"limit\":10}"))
        assertTrue(connections().sent.isEmpty())
    }

    @Test
    fun a_page_of_topics_carries_a_cursor_built_from_its_last_topic() {
        val plugin = granted()
        assertNull(fetch(plugin, PluginReads.OP_TOPICS, "D-$forum", "{\"limit\":2}"))

        val request = connections().lastSent()!!.request as TL_forum.TL_messages_getForumTopics
        assertEquals(forum, (request.peer as TLRPC.TL_inputPeerChannel).channel_id)
        assertEquals(2, request.limit)

        answerWith(TLRPC.TL_messages_forumTopics().apply {
            topics.add(TLRPC.TL_forumTopic().apply { id = 1; top_message = 3; title = "General" }.synced())
            topics.add(TLRPC.TL_forumTopic().apply { id = 7; top_message = 11; title = "Chat" }.synced())
            messages.add(message(11, peerUser(-forum), "hi", date = 1715540000))
        })
        val lines = settled(plugin).split("\n")
        assertEquals("1715540000,11,7", lines[0])
        assertEquals(3, lines.size)
        assertEquals("Chat", decodeString(readTlField(plugin, lines[2], "title")))
    }

    @Test
    fun a_topic_cursor_s_offsets_are_what_the_next_page_is_asked_with() {
        val plugin = granted()
        fetch(plugin, PluginReads.OP_TOPICS, "D-$forum", "{\"limit\":2}", "1715540000,11,7")
        val request = connections().lastSent()!!.request as TL_forum.TL_messages_getForumTopics
        assertEquals(1715540000, request.offset_date)
        assertEquals(11, request.offset_id)
        assertEquals(7, request.offset_topic)
    }

    @Test
    fun an_answer_that_arrives_after_a_reload_settles_nothing() {
        val plugin = granted()
        fetch(plugin, PluginReads.OP_USER_FULL, "D$alice")
        val stale = plugin.js
        plugin.session = PluginSession(plugin, RecordingQuickJs())
        attachBridge(plugin.session!!)

        answerWith(TLRPC.TL_users_userFull().apply { full_user = TLRPC.TL_userFull() })
        drain()
        assertTrue(stale.readResults.isEmpty())
        assertTrue(plugin.js.readResults.isEmpty())
    }
}
