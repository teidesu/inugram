package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.telegram.PeerSpecs
import desu.inugram.helpers.plugins.telegram.PluginReads
import java.util.ArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
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
    fun every_op_checks_its_own_scope_on_the_side_that_owns_the_data() {
        val plugin = startPlugin("narrow", "account.read(peers)")
        assertPluginError("not-granted", read(plugin, PluginReads.OP_ME))
        assertPluginError("not-granted", read(plugin, PluginReads.OP_DIALOG, "S"))
        assertPluginError("not-granted", read(plugin, PluginReads.OP_MESSAGE, "S\n7"))
        assertEquals("N", read(plugin, PluginReads.OP_USER, "D4242"), "the granted scope still answers")
    }

    @Test
    fun the_refusal_names_the_grant_that_would_have_allowed_it() {
        val plugin = startPlugin("none")
        val decoded = PluginWire.decode(read(plugin, PluginReads.OP_ME)) as PluginWire.Value.PluginErr
        assertEquals("account.read(self)", decoded.grant)
    }

    /**
     * naming yourself is naming your identity, whatever read it is the peer of - `Account.userId`
     * and `inu.accounts()` are behind the same scope, and a plugin holding one handle per slot
     * would otherwise rebuild that list out of `getUser('me').id`
     */
    @Test
    fun naming_yourself_takes_the_self_scope_on_top_of_the_reads_own() {
        val plugin = startPlugin("noself", "account.read(peers,dialogs,messages,history,draft)")
        cacheDialog(self, 7)
        cacheMessage(self, TLRPC.TL_message().apply { id = 7; message = "hi" }.synced())
        TestApp.putDraft(self, 0L, TLRPC.TL_draftMessage().apply {
            message = "unsent"
        }.synced())

        val ops = listOf(
            PluginReads.OP_USER to "S",
            PluginReads.OP_PEER to "S",
            PluginReads.OP_USERS to "D$alice\nS",
            PluginReads.OP_DIALOG to "S",
            PluginReads.OP_MESSAGE to "S\n7",
            PluginReads.OP_DRAFT to "S\n0",
            PluginReads.OP_INPUT_PEER to "S\n${PeerSpecs.KIND_PEER}",
        )
        for ((op, arg) in ops) {
            val decoded = PluginWire.decode(read(plugin, op, arg))
            assertTrue(
                decoded is PluginWire.Value.PluginErr && decoded.grant == "account.read(self)",
                "op $op answered for 'me' without the self scope: $decoded",
            )
        }
        assertPluginError("not-granted", reads(plugin).accountFetch(0, 1L, PluginReads.OP_HISTORY, "S\n10\n0\n0\n0\n0"))
        assertPluginError("not-granted", resolve(plugin, "S"))

        // an id is not an identity: being told *which* peer is you is the part that is gated
        assertEquals("user", stringOf(fieldOf(plugin, read(plugin, PluginReads.OP_USER, "D$self"), "_")))
        assertEquals("user", stringOf(fieldOf(plugin, read(plugin, PluginReads.OP_USER, "D$alice"), "_")))
        assertEquals("dialog", stringOf(fieldOf(plugin, read(plugin, PluginReads.OP_DIALOG, "D$self"), "_")))
    }

    @Test
    fun the_reads_own_scope_is_checked_before_the_self_rule() {
        val plugin = startPlugin("peers", "account.read(peers)")
        val decoded = PluginWire.decode(read(plugin, PluginReads.OP_DIALOG, "S")) as PluginWire.Value.PluginErr
        assertEquals("account.read(dialogs)", decoded.grant)
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

        val narrow = startPlugin("nochats", "account.read(dialogs)")
        assertPluginError("not-granted", read(narrow, PluginReads.OP_CHATS, "D-$channel"))
    }

    private fun cacheDialog(dialogId: Long, topMessage: Int) {
        val controller = MessagesController.getInstance(0)
        controller.dialogs_dict.put(dialogId, TLRPC.TL_dialog().apply {
            peer = peerUser(dialogId)
            top_message = topMessage
        })
    }

    private fun cacheMessage(dialogId: Long, message: TLRPC.Message) {
        val controller = MessagesController.getInstance(0)
        controller.dialogMessage.put(dialogId, arrayListOf(MessageObject(0, message, false, false)))
    }

    @Test
    fun a_dialog_and_its_cached_message_read_back() {
        val plugin = granted()
        cacheDialog(self, 7)
        cacheMessage(self, TLRPC.TL_message().apply { id = 7; message = "hi" }.synced())

        val dialog = read(plugin, PluginReads.OP_DIALOG, "S")
        assertEquals("dialog", stringOf(fieldOf(plugin, dialog, "_")))
        val message = read(plugin, PluginReads.OP_MESSAGE, "S\n7")
        assertEquals("hi", stringOf(fieldOf(plugin, message, "message")))
        assertEquals("N", read(plugin, PluginReads.OP_MESSAGE, "S\n8"), "only what the app has in memory")
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

        val settled = plugin.js.peerResults.single()
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
        val decoded = PluginWire.decode(plugin.js.peerResults.single().resultWire) as PluginWire.Value.RpcError
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
        assertPluginError("invalid-argument", plugin.js.peerResults.single().resultWire)
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
        plugin.engine = RecordingQuickJs()
        attachBridge(plugin, plugin.js)

        connections().lastSent()!!.answer(TLRPC.TL_contacts_resolvedPeer(), null, 0L)
        drain()
        assertTrue(stale.peerResults.isEmpty())
        assertTrue(plugin.js.peerResults.isEmpty())
    }

    @Test
    fun a_slot_nobody_is_logged_into_answers_not_found_rather_than_reading_slot_zero() {
        val plugin = granted()
        assertPluginError("not-found", read(plugin, PluginReads.OP_USER, "D$alice", account = 3))
        assertPluginError("not-found", reads(plugin).resolvePeer(3, 1L, "Utelegram", PeerSpecs.KIND_PEER))
        assertTrue(connections(3).sent.isEmpty())
    }
}
