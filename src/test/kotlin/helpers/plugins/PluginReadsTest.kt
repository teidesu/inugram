package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.telegram.PeerSpecs
import desu.inugram.helpers.plugins.telegram.PluginReads
import desu.inugram.helpers.plugins.tl.TlHandles
import java.util.ArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.telegram.SQLite.SQLiteDatabase
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC

/**
 * The host half of the `Account` read surface: what each op answers, what it refuses, and that
 * everything it hands out is a read-only view the takeover filter still covers.
 */
class PluginReadsTest {
    private val self = 100L
    private val alice = 222L
    private val channel = 1001L

    @Before
    fun setUp() {
        resetBridge()
        TestApp.signInAs(0, user(self, "selfuser"))
        TestApp.putUser(user(alice, "alice"))
        TestApp.putChat(broadcast(channel, "newschan"))
        TestApp.putChat(basicGroup(2002L))
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

    private fun reads(plugin: Plugin): ReadsListener = plugin.js.listener!!

    private fun read(plugin: Plugin, op: Int, arg: String = "", account: Int = 0): String =
        reads(plugin).accountRead(account, op, arg)

    private fun preview(plugin: Plugin, message: TLRPC.Message, hideSpoilers: Boolean = false): JSONObject {
        val flag = if (hideSpoilers) "1" else "0"
        val wire = (plugin.tl() as TlHandles).mintWireForPlugin(message, readOnly = true)
        val answer = read(plugin, PluginReads.OP_MESSAGE_PREVIEW, "$flag\n$wire")
        assertTrue(answer.startsWith("J"), "a preview crosses as text plus entities: $answer")
        return JSONObject(answer.drop(1))
    }

    private fun previewText(plugin: Plugin, message: TLRPC.Message, hideSpoilers: Boolean = false): String =
        preview(plugin, message, hideSpoilers).getString("text")

    private fun fieldOf(plugin: Plugin, wire: String, key: String): String =
        plugin.tl().tlGet(handleId(wire), key)

    private fun granted(vararg extra: String) =
        startPlugin("reads", "account.read(self,peers,dialogs,messages)", *extra)

    @Test
    fun no_read_reaches_an_encrypted_dialog_whichever_op_names_it() {
        // common.d.ts: "secret chats, which plugin code never reaches at all". every op resolves its
        // target through one function, so this is the whole surface, not the ops that remembered
        // the draft scope too, so every op reaches dialog resolution rather than stopping at its gate
        val plugin = granted("account.read(draft)")
        val secret = 0x4000000000000000L or 7L
        // seeded, or every op answers "N" for absence and the test cannot tell a refusal from a
        // cache miss: it would pass just as well with no guard at all
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
        // the sign bit set means a channel, not a secret chat, and stock's own rule says so
        assertEquals("N", read(plugin, PluginReads.OP_DIALOG, "D-100500"), "a cache miss, not a refusal")
        assertNotEquals("", read(plugin, PluginReads.OP_INPUT_PEER, "D4242"))
    }

    @Test
    fun a_peer_spec_is_resolved_the_three_ways_the_prelude_can_write_one() {
        val plugin = granted()
        for (spec in listOf("D$alice", "Ualice")) {
            assertEquals("user", stringOf(fieldOf(plugin, read(plugin, PluginReads.OP_USER, spec), "_")), spec)
        }
        assertEquals(
            "user",
            stringOf(fieldOf(plugin, read(plugin, PluginReads.OP_ME), "_")),
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

    /**
     * stock answers `getUser(0)` with the logged-in user, which would be `getMe` behind a grant that
     * does not cover it
     */
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
        assertEquals("N", read(plugin, PluginReads.OP_USER, "D-$channel"))
        assertEquals("channel", stringOf(fieldOf(plugin, read(plugin, PluginReads.OP_PEER, "D-$channel"), "_")))
    }

    @Test
    fun a_batch_answers_one_wire_per_element_and_keeps_its_misses_in_place() {
        val plugin = granted()
        val wires = read(plugin, PluginReads.OP_USERS, "D$alice\nD4242\nS").split("\n")
        assertEquals(3, wires.size)
        assertEquals("user", stringOf(fieldOf(plugin, wires[0], "_")))
        assertEquals("N", wires[1])
        assertEquals("user", stringOf(fieldOf(plugin, wires[2], "_")))
        assertEquals("", read(plugin, PluginReads.OP_USERS, ""), "an empty batch is an empty answer")
    }

    /** its own batch and its own kind: the user in it is a miss rather than the user anyway */
    @Test
    fun a_chat_batch_answers_for_chats_alone() {
        val plugin = granted()
        val wires = read(plugin, PluginReads.OP_CHATS, "D-$channel\nD$alice\nD-4242").split("\n")
        assertEquals(3, wires.size)
        assertEquals("channel", stringOf(fieldOf(plugin, wires[0], "_")))
        assertEquals("N", wires[1])
        assertEquals("N", wires[2])
        assertTrue(handleOf(wires[0]).readOnly)
        assertEquals("", read(plugin, PluginReads.OP_CHATS, ""), "an empty batch is an empty answer")
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
        assertEquals("dialog", stringOf(fieldOf(plugin, dialog, "_")))
        val message = read(plugin, PluginReads.OP_MESSAGE, "S\n7")
        assertEquals("hi", stringOf(fieldOf(plugin, message, "message")))
        assertEquals("N", read(plugin, PluginReads.OP_MESSAGE, "S\n8"), "neither memory nor sqlite has it")
    }

    /**
     * The half memory cannot answer: a synchronous read blocks on stock's storage queue rather than
     * reporting a miss for every message that is not a chat list's own last one.
     */
    @Test
    fun a_message_only_sqlite_has_is_read_from_disk_synchronously() {
        val plugin = granted()
        val mid = 987655
        onStorage { database ->
            val state = database.executeFast(
                "REPLACE INTO messages_v2 (mid, uid, read_state, send_state, date, data, out, ttl, media, imp, " +
                    "mention, forwards, thread_reply_id, is_channel, reply_to_message_id, group_id, reply_to_story_id) " +
                    "VALUES(?, ?, 0, 0, 0, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)",
            )
            state.bindInteger(1, mid)
            state.bindLong(2, self)
            state.bindTlObject(
                3,
                TLRPC.TL_message().apply { id = mid; message = "from disk"; peer_id = peerUser(self) }.synced(),
            )
            state.step()
            state.dispose()
        }
        try {
            assertEquals(
                "from disk",
                stringOf(fieldOf(plugin, read(plugin, PluginReads.OP_MESSAGE, "S\n$mid"), "message")),
            )
            // the row is not a channel's, so the common box reaches it with no peer named
            assertEquals(
                "from disk",
                stringOf(fieldOf(plugin, read(plugin, PluginReads.OP_MESSAGE, "D0\n$mid"), "message")),
            )
        } finally {
            onStorage { it.executeFast("DELETE FROM messages_v2 WHERE mid = $mid").stepThis().dispose() }
        }
    }

    /** stock's own queue, which the harness does not replace: this really runs against the database */
    private fun onStorage(block: (SQLiteDatabase) -> Unit) {
        val storage = MessagesStorage.getInstance(0)
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        storage.storageQueue.postRunnable {
            try {
                block(assertNotNull(storage.getDatabase(), "the test process has no database"))
            } catch (e: Throwable) {
                failure = e
            } finally {
                latch.countDown()
            }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS), "the storage queue never ran")
        failure?.let { throw it }
    }

    @Test
    fun the_common_box_answers_without_a_peer_and_a_channel_is_not_in_it() {
        val plugin = granted()
        cacheMessage(self, TLRPC.TL_message().apply { id = 7; message = "hi"; peer_id = peerUser(self) }.synced())

        assertEquals(
            "hi",
            stringOf(fieldOf(plugin, read(plugin, PluginReads.OP_MESSAGE, "D0\n7"), "message")),
            "a user dialog's ids are common-box ids",
        )
        assertEquals("N", read(plugin, PluginReads.OP_MESSAGE, "D0\n8"))

        // a channel numbers its own messages, so the common box must not hand one back for an id
        // that only collides with it
        cacheMessage(-1001L, TLRPC.TL_message().apply { id = 9; message = "chan"; peer_id = peerChannel(1001L) }.synced())
        assertEquals("N", read(plugin, PluginReads.OP_MESSAGE, "D0\n9"))
        assertEquals(
            "chan",
            stringOf(fieldOf(plugin, read(plugin, PluginReads.OP_MESSAGE, "D-1001\n9"), "message")),
            "naming the channel still reads it",
        )
    }

    @Test
    fun a_message_batch_keeps_its_misses_in_place_too() {
        val plugin = granted()
        cacheMessage(self, TLRPC.TL_message().apply { id = 7; message = "hi" }.synced())
        val wires = read(plugin, PluginReads.OP_MESSAGES, "S\n7\n8").split("\n")
        assertEquals(2, wires.size)
        assertEquals("hi", stringOf(fieldOf(plugin, wires[0], "message")))
        assertEquals("N", wires[1])
    }

    /**
     * a draft rides on the dialog row as much as on `getDraft`, so the rule is on the field: a scope
     * that another read hands over for free is two scopes with one meaning between them.
     */
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
        assertEquals("N", fieldOf(plugin, wire, "draft"), "a hidden field reads as absent, never as an error")
        assertEquals(0, plugin.tl().tlHas(handle, "draft"), "and `in` agrees with the read")
        assertTrue("draft" !in plugin.tl().tlOwnKeys(handle)!!.split(","))
        assertTrue(!JSONObject(plugin.tl().tlCopy(handle)!!).has("draft"), "including in a toJSON snapshot")
        assertEquals(7, (PluginWire.decode(fieldOf(plugin, wire, "top_message")) as PluginWire.Value.IntNum).value.toInt())

        val allowed = startPlugin("drafts", "account.read(dialogs,draft)")
        val row = read(allowed, PluginReads.OP_DIALOG, "D$alice")
        assertEquals("unsent", stringOf(fieldOf(allowed, fieldOf(allowed, row, "draft"), "message")))
    }

    @Test
    fun everything_read_off_an_account_refuses_writes() {
        val plugin = granted()
        val wire = read(plugin, PluginReads.OP_USER, "D$alice")
        assertTrue(handleOf(wire).readOnly, "the handle itself is minted read-only")
        assertPluginError("forbidden", plugin.tl().tlSet(handleId(wire), "username", "J\"mallory\""))
    }

    /** the filter lives at materialization, so a getter cannot become a way around it by existing */
    @Test
    fun a_login_code_is_redacted_out_of_a_message_read_through_a_getter() {
        val plugin = granted()
        cacheMessage(self, serviceMessage("Login code: 12345", id = 7))
        val wire = read(plugin, PluginReads.OP_MESSAGE, "S\n7")
        assertEquals("Login code: *****", stringOf(fieldOf(plugin, wire, "message")))
    }

    @Test
    fun the_filter_is_off_for_a_plugin_that_disabled_it() {
        val plugin = granted("unsafe.disableApiFiltering")
        cacheMessage(self, serviceMessage("Login code: 12345", id = 7))
        val wire = read(plugin, PluginReads.OP_MESSAGE, "S\n7")
        assertEquals("Login code: 12345", stringOf(fieldOf(plugin, wire, "message")))
    }

    private fun inputPeer(plugin: Plugin, spec: String, kind: Int): String =
        read(plugin, PluginReads.OP_INPUT_PEER, "$spec\n$kind")

    private fun jsonOf(wire: String): JSONObject = JSONObject((PluginWire.decode(wire) as PluginWire.Value.Json).json)

    @Test
    fun an_input_peer_is_answered_without_serializing_the_entity_behind_it() {
        val plugin = granted()
        assertEquals("inputPeerSelf", jsonOf(inputPeer(plugin, "S", PeerSpecs.KIND_PEER)).getString("_"))
        assertEquals("inputUserSelf", jsonOf(inputPeer(plugin, "S", PeerSpecs.KIND_USER)).getString("_"))
        val peer = jsonOf(inputPeer(plugin, "D$alice", PeerSpecs.KIND_PEER))
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
        assertPluginError("invalid-argument", inputPeer(plugin, "D-$channel", PeerSpecs.KIND_USER))
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
        assertEquals("inputPeerChannel", jsonOf(settled.resultWire).getString("_"))
        assertEquals(
            "inputPeerChannel",
            jsonOf(inputPeer(plugin, "Utelegram", PeerSpecs.KIND_PEER)).getString("_"),
            "and the synchronous half answers for it afterwards",
        )
    }

    @Test
    fun a_server_error_rejects_with_the_server_s_own_code() {
        val plugin = granted()
        resolve(plugin, "Utelegram")
        connections().lastSent()!!.answer(null, TLRPC.TL_error().apply { code = 420; text = "FLOOD_WAIT_5" }, 0L)
        drain()
        val decoded = PluginWire.decode(plugin.js.readResults.single().resultWire) as PluginWire.Value.RpcError
        assertEquals(420, decoded.code)
        assertEquals("FLOOD_WAIT_5", decoded.text)
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

    /**
     * a reload gives the plugin a new engine whose request ids restart, so a settle for the old one
     * must not land on it
     */
    @Test
    fun an_answer_that_arrives_after_a_reload_settles_nothing() {
        val plugin = granted()
        resolve(plugin, "Utelegram")
        val stale = plugin.js
        plugin.session = PluginSession(plugin, RecordingQuickJs())
        attachBridge(plugin.session!!)

        connections().lastSent()!!.answer(TLRPC.TL_contacts_resolvedPeer(), null, 0L)
        drain()
        assertTrue(stale.readResults.isEmpty())
        assertTrue(plugin.js.readResults.isEmpty())
    }

    /** the wording is stock's own, so only a device can say what it is */
    @Test
    fun a_message_preview_is_the_line_the_app_itself_would_draw() {
        val plugin = granted()
        val message = TLRPC.TL_message().apply {
            id = 1
            message = "plain text"
            peer_id = peerUser(self)
        }.synced()
        assertEquals("plain text", previewText(plugin, message))
    }

    /** what `NotificationsController` shows in its own notification, when it is asked for */
    @Test
    fun a_preview_masks_a_spoiler_only_when_it_is_told_to() {
        val plugin = granted()
        val message = TLRPC.TL_message().apply {
            id = 1
            message = "ab cd"
            peer_id = peerUser(self)
            entities.add(TLRPC.TL_messageEntitySpoiler().apply { offset = 3; length = 2 })
        }.synced()
        assertEquals("ab \u280C\u2862", previewText(plugin, message, hideSpoilers = true))
        assertEquals("ab cd", previewText(plugin, message))
    }

    /** the offsets are counted against the message's own text, which a media label is not */
    @Test
    fun a_preview_that_is_not_the_message_text_is_left_unmasked() {
        val plugin = granted()
        val message = TLRPC.TL_message().apply {
            id = 1
            message = "ab cd"
            peer_id = peerUser(self)
            media = TLRPC.TL_messageMediaPhoto()
            entities.add(TLRPC.TL_messageEntitySpoiler().apply { offset = 0; length = 5 })
        }.synced()
        val drawn = previewText(plugin, message, hideSpoilers = true)
        assertFalse(drawn.any { it in '\u2800'..'\u28FF' }, "a media label carries no spoiler of its own: $drawn")
    }

    /** an ordinary message's preview is its text, unspanned, so the formatting is the message's own */
    @Test
    fun a_preview_of_a_text_message_carries_that_message_s_entities() {
        val plugin = granted()
        val message = TLRPC.TL_message().apply {
            id = 1
            message = "bold text"
            peer_id = peerUser(self)
            entities.add(TLRPC.TL_messageEntityBold().apply { offset = 0; length = 4 })
        }.synced()
        val drawn = preview(plugin, message)
        assertEquals("bold text", drawn.getString("text"))
        val entities = assertNotNull(drawn.optJSONArray("entities"), "the message's own entities are the preview's")
        assertEquals(1, entities.length())
        assertEquals("messageEntityBold", entities.getJSONObject(0).getString("_"))
    }

    /** the masked characters are gone, so an entity still covering them describes nothing */
    @Test
    fun masking_a_spoiler_drops_the_entities_over_it() {
        val plugin = granted()
        val message = TLRPC.TL_message().apply {
            id = 1
            message = "ab cd"
            peer_id = peerUser(self)
            entities.add(TLRPC.TL_messageEntityBold().apply { offset = 0; length = 2 })
            entities.add(TLRPC.TL_messageEntitySpoiler().apply { offset = 3; length = 2 })
        }.synced()
        val drawn = preview(plugin, message, hideSpoilers = true)
        assertEquals("ab \u280C\u2862", drawn.getString("text"))
        val entities = assertNotNull(drawn.optJSONArray("entities"), "the bold outside the spoiler survives")
        assertEquals(1, entities.length())
        assertEquals("messageEntityBold", entities.getJSONObject(0).getString("_"))
    }

    /** the app writes a service message with spans, which are the only formatting it has there */
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
}
