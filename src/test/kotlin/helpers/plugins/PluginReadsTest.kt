package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.telegram.PeerSpecs
import desu.inugram.helpers.plugins.telegram.PluginReads
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.TLRPC

class PluginReadsTest {
    private val self = 100L
    private val alice = 222L
    private val channel = 1001L
    private val forum = 3003L

    @Before
    fun setUp() {
        resetBridge()
        TestApp.signInAs(0, user(self, "selfuser"))
        TestApp.putUser(user(alice, "alice"))
        TestApp.putChat(broadcast(channel, "newschan"))
        TestApp.putChat(basicGroup(2002L))
    }

    private fun reads(plugin: Plugin): ReadsListener = plugin.js.listener!!

    private fun read(plugin: Plugin, op: Int, arg: String = "", account: Int = 0): String =
        reads(plugin).accountRead(account, op, arg)

    private fun preview(plugin: Plugin, message: TLRPC.Message, hideSpoilers: Boolean = false): JSONObject {
        val flag = if (hideSpoilers) "1" else "0"
        val wire = plugin.session!!.tl.mintWireForPlugin(message, readOnly = true)
        val answer = read(plugin, PluginReads.OP_MESSAGE_PREVIEW, "$flag\n$wire")
        assertTrue(answer.startsWith("J"), "a preview crosses as text plus entities: $answer")
        return JSONObject(answer.drop(1))
    }

    private fun granted(vararg extra: String) =
        startPlugin("reads", "account.read(self,peers,dialogs,messages)", *extra)

    @Test
    fun no_read_reaches_an_encrypted_dialog_whichever_op_names_it() {
        val plugin = granted("account.read(draft)")
        val secret = 0x4000000000000000L or 7L
        // seeded, or a refusal is indistinguishable from a miss
        cacheDialog(secret, 3)
        cacheMessage(secret, TLRPC.TL_message().apply { id = 3; message = "e2e plaintext" }.synced())
        val ops = listOf(
            PluginReads.OP_DIALOG,
            PluginReads.OP_MESSAGE,
            PluginReads.OP_DRAFT,
            PluginReads.OP_PEER,
            PluginReads.OP_INPUT_PEER,
        )
        for (op in ops) {
            val arg = if (op == PluginReads.OP_MESSAGE) "D$secret\n7" else "D$secret"
            assertEquals("N", read(plugin, op, arg), "op $op answered for an encrypted dialog")
        }
    }

    @Test
    fun an_ordinary_dialog_id_is_not_mistaken_for_an_encrypted_one() {
        val plugin = granted()
        assertEquals("N", read(plugin, PluginReads.OP_DIALOG, "D-100500"), "a cache miss, not a refusal")
        assertNotEquals("", read(plugin, PluginReads.OP_INPUT_PEER, "D4242"))
    }

    @Test
    fun a_peer_spec_is_resolved_the_three_ways_the_prelude_can_write_one() {
        val plugin = granted()
        for (spec in listOf("D$alice", "Ualice")) {
            assertEquals("user", decodeString(readTlField(plugin, read(plugin, PluginReads.OP_USER, spec), "_")), spec)
        }
        assertEquals(
            "user",
            decodeString(readTlField(plugin, read(plugin, PluginReads.OP_ME), "_")),
            "self is read off UserConfig rather than the entity cache",
        )
    }

    @Test
    fun a_miss_is_null_rather_than_an_error() {
        val plugin = granted()
        for (op in listOf(PluginReads.OP_USER, PluginReads.OP_CHAT, PluginReads.OP_PEER, PluginReads.OP_DIALOG)) {
            assertEquals("N", read(plugin, op, "D4242"), "op $op")
        }
        assertEquals("N", read(plugin, PluginReads.OP_USER, "Unobody"))
        assertEquals("N", read(plugin, PluginReads.OP_MESSAGE, "D4242\n7"))
    }

    // stock answers `getUser(0)` with the logged-in user
    @Test
    fun a_zero_dialog_id_is_a_miss_not_the_logged_in_user() {
        val plugin = startPlugin("peers", "account.read(peers)")
        assertEquals("N", read(plugin, PluginReads.OP_USER, "D0"))
        assertEquals("N", read(plugin, PluginReads.OP_PEER, "D0"))
    }

    @Test
    fun the_entity_getters_do_not_cross_kinds() {
        val plugin = granted()
        assertEquals("N", read(plugin, PluginReads.OP_CHAT, "D$alice"))
        assertEquals("N", read(plugin, PluginReads.OP_USER, "D${PeerSpecs.ZERO_CHANNEL_ID - channel}"))
        assertEquals("channel", decodeString(readTlField(plugin, read(plugin, PluginReads.OP_PEER, "D${PeerSpecs.ZERO_CHANNEL_ID - channel}"), "_")))
    }

    @Test
    fun a_batch_answers_one_wire_per_element_of_its_own_kind_and_keeps_its_misses_in_place() {
        val plugin = granted()
        val users = read(plugin, PluginReads.OP_USERS, "D$alice\nD4242\nS").split("\n")
        assertEquals(3, users.size)
        assertEquals("user", decodeString(readTlField(plugin, users[0], "_")))
        assertEquals("N", users[1])
        assertEquals("user", decodeString(readTlField(plugin, users[2], "_")))
        assertEquals("", read(plugin, PluginReads.OP_USERS, ""), "an empty batch is an empty answer")

        val chats = read(plugin, PluginReads.OP_CHATS, "D${PeerSpecs.ZERO_CHANNEL_ID - channel}\nD$alice\nD-4242").split("\n")
        assertEquals(3, chats.size)
        assertEquals("channel", decodeString(readTlField(plugin, chats[0], "_")))
        assertEquals("N", chats[1])
        assertEquals("N", chats[2])
        assertTrue(decodeHandle(chats[0]).readOnly)
    }

    private fun cacheDialog(dialogId: Long, topMessage: Int) {
        val controller = MessagesController.getInstance(0)
        controller.dialogs_dict.put(dialogId, TLRPC.TL_dialog().apply {
            peer = peerUser(dialogId)
            top_message = topMessage
        })
    }

    private fun cacheMessage(dialogId: Long, message: TLRPC.Message) =
        TestApp.cacheDialogMessage(0, dialogId, message)

    @Test
    fun a_dialog_and_its_cached_message_read_back() {
        val plugin = granted()
        cacheDialog(self, 7)
        cacheMessage(self, TLRPC.TL_message().apply { id = 7; message = "hi" }.synced())

        val dialog = read(plugin, PluginReads.OP_DIALOG, "S")
        assertEquals("dialog", decodeString(readTlField(plugin, dialog, "_")))
        val message = read(plugin, PluginReads.OP_MESSAGE, "S\n7")
        assertEquals("hi", decodeString(readTlField(plugin, message, "message")))
        assertEquals("N", read(plugin, PluginReads.OP_MESSAGE, "S\n8"), "neither memory nor sqlite has it")

        val wires = read(plugin, PluginReads.OP_MESSAGES, "S\n7\n8").split("\n")
        assertEquals(2, wires.size)
        assertEquals("hi", decodeString(readTlField(plugin, wires[0], "message")))
        assertEquals("N", wires[1])
    }

    @Test
    fun a_message_only_sqlite_has_is_read_from_disk_synchronously() {
        val plugin = granted()
        val mid = 987655
        storeMessageOnDisk(self, TLRPC.TL_message().apply { id = mid; message = "from disk"; peer_id = peerUser(self) }.synced())
        try {
            assertEquals(
                "from disk",
                decodeString(readTlField(plugin, read(plugin, PluginReads.OP_MESSAGE, "S\n$mid"), "message")),
            )
            assertEquals(
                "from disk",
                decodeString(readTlField(plugin, read(plugin, PluginReads.OP_MESSAGE, "D0\n$mid"), "message")),
            )
        } finally {
            deleteMessageOnDisk(mid)
        }
    }

    @Test
    fun the_common_box_answers_without_a_peer_and_a_channel_is_not_in_it() {
        val plugin = granted()
        cacheMessage(self, TLRPC.TL_message().apply { id = 7; message = "hi"; peer_id = peerUser(self) }.synced())

        assertEquals(
            "hi",
            decodeString(readTlField(plugin, read(plugin, PluginReads.OP_MESSAGE, "D0\n7"), "message")),
            "a user dialog's ids are common-box ids",
        )
        assertEquals("N", read(plugin, PluginReads.OP_MESSAGE, "D0\n8"))

        cacheMessage(-1001L, TLRPC.TL_message().apply { id = 9; message = "chan"; peer_id = peerChannel(1001L) }.synced())
        assertEquals("N", read(plugin, PluginReads.OP_MESSAGE, "D0\n9"))
        assertEquals(
            "chan",
            decodeString(readTlField(plugin, read(plugin, PluginReads.OP_MESSAGE, "D-1001\n9"), "message")),
            "naming the channel still reads it",
        )
    }

    @Test
    fun a_dialog_row_carries_no_draft_without_the_draft_scope() {
        val plugin = granted()
        MessagesController.getInstance(0).dialogs_dict.put(alice, TLRPC.TL_dialog().apply {
            peer = peerUser(alice)
            top_message = 7
            draft = TLRPC.TL_draftMessage().apply { message = "unsent" }.synced()
        }.synced())

        val wire = read(plugin, PluginReads.OP_DIALOG, "D$alice")
        val handle = handleId(wire)
        assertEquals("N", readTlField(plugin, wire, "draft"), "a hidden field reads as absent, never as an error")
        assertEquals(0, plugin.tl().tlHas(handle, "draft"), "and `in` agrees with the read")
        assertTrue("draft" !in plugin.tl().tlOwnKeys(handle)!!.split(","))
        assertTrue(!JSONObject(plugin.tl().tlCopy(handle)!!).has("draft"), "including in a toJSON snapshot")
        assertEquals(7, (PluginWire.decode(readTlField(plugin, wire, "top_message")) as PluginWire.Value.IntNum).value.toInt())

        val allowed = startPlugin("drafts", "account.read(dialogs,draft)")
        val row = read(allowed, PluginReads.OP_DIALOG, "D$alice")
        assertEquals("unsent", decodeString(readTlField(allowed, readTlField(allowed, row, "draft"), "message")))
    }

    @Test
    fun everything_read_off_an_account_refuses_writes() {
        val plugin = granted()
        val wire = read(plugin, PluginReads.OP_USER, "D$alice")
        assertTrue(decodeHandle(wire).readOnly, "the handle itself is minted read-only")
        assertPluginError("forbidden", plugin.tl().tlSet(handleId(wire), "username", "J\"mallory\""))
    }

    @Test
    fun a_login_code_is_redacted_out_of_a_message_read_through_a_getter() {
        val plugin = granted()
        cacheMessage(self, serviceMessage("Login code: 12345", id = 7))
        val wire = read(plugin, PluginReads.OP_MESSAGE, "S\n7")
        assertEquals("Login code: *****", decodeString(readTlField(plugin, wire, "message")))
    }

    @Test
    fun the_filter_is_off_for_a_plugin_that_disabled_it() {
        val plugin = granted("unsafe.disableApiFiltering")
        cacheMessage(self, serviceMessage("Login code: 12345", id = 7))
        val wire = read(plugin, PluginReads.OP_MESSAGE, "S\n7")
        assertEquals("Login code: 12345", decodeString(readTlField(plugin, wire, "message")))
    }

    private fun inputPeer(plugin: Plugin, spec: String, kind: Int): String =
        read(plugin, PluginReads.OP_INPUT_PEER, "$spec\n$kind")

    @Test
    fun an_input_peer_is_answered_without_serializing_the_entity_behind_it() {
        val plugin = granted()
        assertEquals("inputPeerSelf", decodeJson(inputPeer(plugin, "S", PeerSpecs.KIND_PEER)).getString("_"))
        assertEquals("inputUserSelf", decodeJson(inputPeer(plugin, "S", PeerSpecs.KIND_USER)).getString("_"))
        val peer = decodeJson(inputPeer(plugin, "D$alice", PeerSpecs.KIND_PEER))
        assertEquals("inputPeerUser", peer.getString("_"))
        assertEquals("2220", peer.getString("access_hash"), "the cached entity's own hash, not a zero")
    }

    @Test
    fun an_uncached_peer_has_no_input_peer_however_willing_stock_is_to_build_one() {
        val plugin = granted()
        assertEquals("N", inputPeer(plugin, "D4242", PeerSpecs.KIND_PEER))
        assertEquals("N", inputPeer(plugin, "Unobody", PeerSpecs.KIND_PEER))
    }

    @Test
    fun narrowing_to_the_wrong_kind_says_so_instead_of_answering_nothing() {
        val plugin = granted()
        assertPluginError("invalid-argument", inputPeer(plugin, "D$alice", PeerSpecs.KIND_CHANNEL))
        assertPluginError("invalid-argument", inputPeer(plugin, "D${PeerSpecs.ZERO_CHANNEL_ID - channel}", PeerSpecs.KIND_USER))
        assertPluginError("invalid-argument", inputPeer(plugin, "S", PeerSpecs.KIND_CHANNEL))
        assertPluginError("invalid-argument", inputPeer(plugin, "D-2002", PeerSpecs.KIND_CHANNEL))
    }

    private fun resolve(plugin: Plugin, spec: String, kind: Int = PeerSpecs.KIND_PEER, requestId: Long = 1L): String? =
        reads(plugin).resolvePeer(0, requestId, spec, kind)

    @Test
    fun only_a_username_can_be_looked_up() {
        val plugin = granted()
        assertPluginError("not-found", resolve(plugin, "D4242"))
        assertPluginError("not-found", resolve(plugin, "S"))
        assertTrue(connections().sent.isEmpty(), "nothing was sent for a peer that cannot be looked up")
    }

    @Test
    fun resolving_a_username_sends_one_request_and_settles_from_its_answer() {
        val plugin = granted()
        assertNull(resolve(plugin, "Utelegram"))

        val sent = connections().lastSent()!!
        assertTrue(sent.request is TLRPC.TL_contacts_resolveUsername)
        assertEquals("telegram", (sent.request as TLRPC.TL_contacts_resolveUsername).username)

        sent.answer(
            TLRPC.TL_contacts_resolvedPeer().apply {
                peer = peerChannel(3003L)
                chats.add(broadcast(3003L, "telegram"))
            },
            null,
            0L,
        )
        drain()

        val settled = plugin.js.readResults.single()
        assertEquals(1L, settled.requestId)
        assertEquals("inputPeerChannel", decodeJson(settled.resultWire).getString("_"))
        assertEquals(
            "inputPeerChannel",
            decodeJson(inputPeer(plugin, "Utelegram", PeerSpecs.KIND_PEER)).getString("_"),
            "and the synchronous half answers for it afterwards",
        )
    }

    @Test
    fun a_username_that_resolves_to_the_wrong_kind_is_an_invalid_argument() {
        val plugin = granted()
        resolve(plugin, "Utelegram", PeerSpecs.KIND_USER)
        connections().lastSent()!!.answer(
            TLRPC.TL_contacts_resolvedPeer().apply {
                peer = peerChannel(3003L)
                chats.add(broadcast(3003L, "telegram"))
            },
            null,
            0L,
        )
        drain()
        assertPluginError("invalid-argument", plugin.js.readResults.single().resultWire)
    }

    @Test
    fun a_preview_masks_a_spoiler_only_when_told_to_and_keeps_the_entities_outside_it() {
        val plugin = granted()
        val message = TLRPC.TL_message().apply {
            id = 1
            message = "ab cd"
            peer_id = peerUser(self)
            entities.add(TLRPC.TL_messageEntityBold().apply { offset = 0; length = 2 })
            entities.add(TLRPC.TL_messageEntitySpoiler().apply { offset = 3; length = 2 })
        }.synced()

        val plain = preview(plugin, message)
        assertEquals("ab cd", plain.getString("text"))
        assertEquals(2, assertNotNull(plain.optJSONArray("entities")).length())

        val masked = preview(plugin, message, hideSpoilers = true)
        assertEquals("ab \u280C\u2862", masked.getString("text"))
        val entities = assertNotNull(masked.optJSONArray("entities"), "the bold outside the spoiler survives")
        assertEquals(1, entities.length())
        assertEquals("messageEntityBold", entities.getJSONObject(0).getString("_"))
    }

    @Test
    fun a_preview_that_is_not_the_message_text_is_left_unmasked() {
        val plugin = granted()
        val message = TLRPC.TL_message().apply {
            id = 1
            message = "ab cd"
            peer_id = peerUser(self)
            // a thumbless photo is a message no server sends
            media = TLRPC.TL_messageMediaGeo().apply { geo = TLRPC.TL_geoPoint() }
            entities.add(TLRPC.TL_messageEntitySpoiler().apply { offset = 0; length = 5 })
        }.synced()
        val drawn = preview(plugin, message, hideSpoilers = true).getString("text")
        assertFalse(drawn.any { it in '\u2800'..'\u28FF' }, "a media label carries no spoiler of its own: $drawn")
    }

    @Test
    fun a_preview_carries_the_entities_the_app_spanned_it_with() {
        val plugin = granted()
        val message = TLRPC.TL_messageService().apply {
            id = 1
            peer_id = peerUser(self)
            from_id = peerUser(self)
            action = TLRPC.TL_messageActionChatAddUser().apply { users.add(self) }
        }.synced()
        val drawn = preview(plugin, message)
        assertTrue(drawn.getString("text").isNotEmpty(), "a service message writes itself out")
        val entities = drawn.optJSONArray("entities")
        assertNotNull(entities, "the names the app made bold reach the plugin as entities")
        assertTrue(
            (0 until entities.length()).any { entities.getJSONObject(it).getString("_") == "messageEntityBold" },
            "expected a bold name: $entities",
        )
    }

    @Test
    fun a_preview_needs_a_message_and_says_so() {
        val plugin = granted()
        assertPluginError("invalid-argument", read(plugin, PluginReads.OP_MESSAGE_PREVIEW, "0\nJ{\"_\":\"user\"}"))
    }

    @Test
    fun a_slot_nobody_is_logged_into_answers_not_found_rather_than_reading_slot_zero() {
        val plugin = granted()
        assertPluginError("not-found", read(plugin, PluginReads.OP_USER, "D$alice", account = 3))
        assertPluginError("not-found", reads(plugin).resolvePeer(3, 1L, "Utelegram", PeerSpecs.KIND_PEER))
        assertTrue(connections(3).sent.isEmpty())
    }

    private fun draft(plugin: Plugin, spec: String, topicId: Long = 0L): String =
        read(plugin, PluginReads.OP_DRAFT, "$spec\n$topicId")

    @Test
    fun a_draft_reads_back_as_the_text_and_entities_the_input_field_would_show() {
        val plugin = granted("account.read(draft)")
        TestApp.putDraft(alice, 0L, TLRPC.TL_draftMessage().apply {
            message = "hello world"
            entities.add(TLRPC.TL_messageEntityBold().apply { offset = 0; length = 5 })
        }.synced())

        val json = decodeJson(draft(plugin, "D$alice"))
        assertEquals("hello world", json.getString("text"))
        assertEquals(1, json.getJSONArray("entities").length())
        assertEquals("messageEntityBold", json.getJSONArray("entities").getJSONObject(0).getString("_"))
    }

    @Test
    fun a_draft_with_no_entities_carries_none_and_no_draft_at_all_is_null() {
        val plugin = granted("account.read(draft)")
        TestApp.putDraft(alice, 0L, TLRPC.TL_draftMessage().apply {
            message = "plain"
        }.synced())
        val json = decodeJson(draft(plugin, "D$alice"))
        assertEquals("plain", json.getString("text"))
        assertTrue(json.isNull("entities") && !json.has("entities"))

        assertEquals("N", draft(plugin, "D${PeerSpecs.ZERO_CHANNEL_ID - channel}"), "a chat with no draft")
        TestApp.putDraft(self, 0L, TLRPC.TL_draftMessageEmpty())
        assertEquals("N", draft(plugin, "S"), "a cleared draft is the same as none")
    }

    @Test
    fun a_topic_s_draft_is_its_own() {
        val plugin = granted("account.read(draft)")
        TestApp.putChat(broadcast(forum, "forumchan").apply { megagroup = true; this.forum = true })
        TestApp.putDraft(-forum, 0L, TLRPC.TL_draftMessage().apply { message = "root" }.synced())
        TestApp.putDraft(-forum, 7L, TLRPC.TL_draftMessage().apply { message = "in the topic" }.synced())
        assertEquals("root", decodeJson(draft(plugin, "D${PeerSpecs.ZERO_CHANNEL_ID - forum}")).getString("text"))
        assertEquals("in the topic", decodeJson(draft(plugin, "D${PeerSpecs.ZERO_CHANNEL_ID - forum}", topicId = 7L)).getString("text"))
    }
}
