package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlHandles
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.telegram.tgnet.TLRPC

class TlProjectionTest {
    private val POLICY = TlFilter.Policy(takeover = true, drafts = true)

    @Before
    fun setUp() = resetBridge()

    private fun dialog(): TLRPC.TL_dialog = TLRPC.TL_dialog().apply {
        id = 5_000_000_001L
        peer = TLRPC.TL_peerUser().apply { user_id = 5_000_000_001L }
        top_message = 7
        unread_count = 3
        pinned = true
        draft = TLRPC.TL_draftMessage().apply { message = "unsent" }
        folder_id = 1
    }.synced()

    private fun rights(): TLRPC.TL_chatBannedRights = TLRPC.TL_chatBannedRights().apply {
        view_messages = true
        until_date = 900
    }.synced()

    private fun projected(target: org.telegram.tgnet.TLObject, handles: TlHandles = TlHandles(POLICY)): JSONObject =
        JSONObject(handles.project(handles.mintForPlugin(target, readOnly = true)))

    @Test
    fun every_projected_scalar_reads_as_the_field_itself_would() {
        val handles = TlHandles(POLICY)
        val target = rights()
        val handle = handles.mintForPlugin(target, readOnly = true)
        val projection = JSONObject(handles.project(handle))
        assertEquals("chatBannedRights", projection.getString("_"))
        var checked = 0
        for (key in projection.keys()) {
            if (key == "_") continue
            checked++
            val expected = PluginWire.decode(handles.tlGet(handle, key))
            val got = projection.get(key)
            when (expected) {
                is PluginWire.Value.Str -> assertEquals(expected.value, got, key)
                is PluginWire.Value.IntNum -> assertEquals(expected.value, (got as Number).toLong(), key)
                is PluginWire.Value.Bool -> assertEquals(expected.value, got, key)
                is PluginWire.Value.Null -> assertEquals(JSONObject.NULL, got, key)
                else -> error("$key crossed as $expected, which a projection must not carry")
            }
        }
        assertTrue(checked > 10, "a chatBannedRights is 20-odd scalars, checked $checked")
    }

    @Test
    fun a_long_is_a_number_only_when_int53_and_a_cleared_bit_is_null() {
        val handles = TlHandles(POLICY)
        val inputPeer = TLRPC.TL_inputPeerUser().apply { user_id = 5_000_000_001L; access_hash = Long.MIN_VALUE }.synced()
        val peer = JSONObject(handles.project(handles.mintForPlugin(inputPeer, readOnly = true), listOf("user_id", "access_hash")))
        assertEquals(5_000_000_001L, peer.get("user_id"))
        assertEquals(Long.MIN_VALUE.toString(), peer.get("access_hash"))

        val handle = handles.mintForPlugin(rights(), readOnly = true)
        val projection = JSONObject(handles.project(handle))
        assertEquals(900, projection.getInt("until_date"))
        assertEquals(true, projection.get("view_messages"))
        assertTrue(projection.has("send_messages") && projection.isNull("send_messages"))
        assertEquals(PluginWire.Value.Null, PluginWire.decode(handles.tlGet(handle, "send_messages")))
    }

    @Test
    fun an_object_with_children_carries_its_type_alone() {
        assertEquals(setOf("_"), projected(dialog()).keys().asSequence().toSet())
        val message = TLRPC.TL_message().apply {
            id = 1
            message = "hello"
            peer_id = TLRPC.TL_peerUser().apply { user_id = 1L }
        }.synced()
        assertEquals(setOf("_"), projected(message).keys().asSequence().toSet())
    }

    @Test
    fun a_child_read_carries_no_projection() {
        val handles = TlHandles(POLICY)
        val root = handles.mintForPlugin(dialog(), readOnly = true)
        for (key in listOf("peer", "draft")) {
            val wire = handles.tlGet(root, key)
            assertEquals(-1, wire.indexOf('|'), "$key: $wire")
            assertTrue(PluginWire.decode(wire) is PluginWire.Value.Handle, wire)
        }
    }

    /** dispatch views cache nothing, so a write cannot go stale against them */
    @Test
    fun a_dispatch_scoped_read_carries_no_projection() {
        val handles = TlHandles(TlFilter.Policy(takeover = false, drafts = true))
        val message = TLRPC.TL_message().apply {
            id = 4
            peer_id = TLRPC.TL_peerUser().apply { user_id = 11L }
        }.synced()
        val root = handles.mintForScope(message, TlHandles.newScope())
        val wire = handles.tlGet(root, "peer_id")
        assertEquals(-1, wire.indexOf('|'), wire)

        val child = (PluginWire.decode(wire) as PluginWire.Value.Handle).id
        assertNull(handles.tlSet(child, "user_id", PluginWire.encodeJson("\"77\"")))
        assertEquals(77L, (message.peer_id as TLRPC.TL_peerUser).user_id)
    }

    @Test
    fun naming_fields_carries_exactly_those_it_can() {
        val handles = TlHandles(POLICY)
        val handle = handles.mintForPlugin(dialog(), readOnly = true)
        val projection = JSONObject(handles.project(handle, listOf("top_message", "unread_count", "pinned", "peer")))
        assertEquals("dialog", projection.getString("_"))
        assertEquals(setOf("_", "top_message", "unread_count", "pinned"), projection.keys().asSequence().toSet())
        for (key in listOf("top_message", "unread_count", "pinned")) {
            val expected = PluginWire.decode(handles.tlGet(handle, key))
            when (expected) {
                is PluginWire.Value.IntNum -> assertEquals(expected.value, projection.getLong(key), key)
                is PluginWire.Value.Bool -> assertEquals(expected.value, projection.get(key), key)
                else -> error("$key crossed as $expected")
            }
        }
        assertTrue(PluginWire.decode(handles.tlGet(handle, "peer")) is PluginWire.Value.Handle)
    }

    @Test
    fun naming_fields_narrows_a_fully_scalar_object_too() {
        val handles = TlHandles(POLICY)
        val handle = handles.mintForPlugin(rights(), readOnly = true)
        val projection = JSONObject(handles.project(handle, listOf("until_date")))
        assertEquals(setOf("_", "until_date"), projection.keys().asSequence().toSet())
        assertEquals(900, projection.getInt("until_date"))
    }

    @Test
    fun a_name_the_class_does_not_have_is_left_to_the_read() {
        val handles = TlHandles(POLICY)
        val handle = handles.mintForPlugin(dialog(), readOnly = true)
        assertEquals(setOf("_"), JSONObject(handles.project(handle, listOf("not_a_field"))).keys().asSequence().toSet())
        assertEquals(setOf("_"), JSONObject(handles.project(handle, emptyList())).keys().asSequence().toSet())
    }

    @Test
    fun a_plugin_read_answers_a_projected_handle() {
        val plugin = startPlugin("projection", "account.read(self,peers,dialogs)")
        val handles = plugin.session!!.tl
        val wire = desu.inugram.helpers.plugins.telegram.PluginReads.mint(handles, dialog())
        val separator = wire.indexOf('|')
        assertTrue(separator > 0, wire)
        val handle = PluginWire.decode(wire) as PluginWire.Value.Handle
        assertTrue(handle.readOnly)
        assertEquals("dialog", JSONObject(wire.substring(separator + 1)).getString("_"))
    }
}
