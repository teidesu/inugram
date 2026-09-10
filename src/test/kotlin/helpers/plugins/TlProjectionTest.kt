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

/**
 * `TlHandles.project`: an object that is nothing but scalars rides along whole, and every value in
 * it must read exactly as the same field would on its own, because rust serves them from the cache
 * without ever asking. Anything with an object field carries its type name and no more - a child is
 * its own handle, minted when the field is read.
 */
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

    /** nothing but scalars, and its flags gate most of them */
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

    /** a boolean's bit is its value, so an unset one reads `null` rather than `false`, on both paths */
    @Test
    fun a_long_is_a_string_and_a_cleared_bit_is_null() {
        val handles = TlHandles(POLICY)
        val peer = projected(TLRPC.TL_peerUser().apply { user_id = 5_000_000_001L }.synced())
        assertEquals("5000000001", peer.getString("user_id"))

        val handle = handles.mintForPlugin(rights(), readOnly = true)
        val projection = JSONObject(handles.project(handle))
        assertEquals(900, projection.getInt("until_date"))
        assertEquals(true, projection.get("view_messages"))
        assertTrue(projection.has("send_messages") && projection.isNull("send_messages"))
        assertEquals(PluginWire.Value.Null, PluginWire.decode(handles.tlGet(handle, "send_messages")))
    }

    /**
     * reflecting a parent's fields to find a child costs more than the crossing it would save, so
     * an object with any child at all carries its type and nothing else
     */
    @Test
    fun an_object_with_children_carries_its_type_alone() {
        assertEquals(setOf("_"), projected(dialog()).keys().asSequence().toSet())
        assertEquals("dialog", projected(dialog()).getString("_"))
        val message = TLRPC.TL_message().apply {
            id = 1
            message = "hello"
            peer_id = TLRPC.TL_peerUser().apply { user_id = 1L }
        }.synced()
        assertEquals(setOf("_"), projected(message).keys().asSequence().toSet())
    }

    /** a nested object is a handle and only a handle: not its fields, and not its type either */
    @Test
    fun a_child_read_carries_no_projection() {
        val handles = TlHandles(POLICY)
        val root = handles.mintForPlugin(dialog(), readOnly = true)
        for (key in listOf("peer", "draft")) {
            val wire = handles.tlGet(root, key)
            assertEquals(-1, wire.indexOf(PluginWire.PROJECTION_SEPARATOR), "$key: $wire")
            assertTrue(PluginWire.decode(wire) is PluginWire.Value.Handle, wire)
        }
    }

    /**
     * the child a read answers with is a handle on the app's own object, not a copy of its values:
     * a write through it has to land in java, and the next read has to answer with what landed.
     */
    @Test
    fun a_write_through_a_child_lands_in_java() {
        val handles = TlHandles(TlFilter.Policy(takeover = false, drafts = true))
        val message = TLRPC.TL_message().apply {
            id = 4
            message = "hi"
            peer_id = TLRPC.TL_peerUser().apply { user_id = 11L }
        }.synced()
        val root = handles.mintForPlugin(message, readOnly = false)
        val child = (PluginWire.decode(handles.tlGet(root, "peer_id")) as PluginWire.Value.Handle)
        assertTrue(!child.readOnly, "a writable parent answers with a writable child")

        assertNull(handles.tlSet(child.id, "user_id", PluginWire.encodeJson("\"77\"")))
        assertEquals(77L, (message.peer_id as TLRPC.TL_peerUser).user_id, "the write must reach the app's object")
        assertEquals(PluginWire.Value.Str("77"), PluginWire.decode(handles.tlGet(child.id, "user_id")))
    }

    /** an interceptor's view caches nothing, so nothing rides along for a write to go stale against */
    @Test
    fun a_dispatch_scoped_read_carries_no_projection() {
        val handles = TlHandles(TlFilter.Policy(takeover = false, drafts = true))
        val message = TLRPC.TL_message().apply {
            id = 4
            peer_id = TLRPC.TL_peerUser().apply { user_id = 11L }
        }.synced()
        val root = handles.mintForScope(message, TlHandles.newScope())
        val wire = handles.tlGet(root, "peer_id")
        assertEquals(-1, wire.indexOf(PluginWire.PROJECTION_SEPARATOR), wire)

        val child = (PluginWire.decode(wire) as PluginWire.Value.Handle).id
        assertNull(handles.tlSet(child, "user_id", PluginWire.encodeJson("\"77\"")))
        assertEquals(77L, (message.peer_id as TLRPC.TL_peerUser).user_id)
    }

    @Test
    fun a_plugin_read_answers_a_projected_handle() {
        val plugin = startPlugin("projection", "account.read(self,peers,dialogs)")
        val handles = TlHandles.attach(plugin, POLICY)
        val wire = desu.inugram.helpers.plugins.telegram.PluginReads.mint(handles, dialog())
        val separator = wire.indexOf(PluginWire.PROJECTION_SEPARATOR)
        assertTrue(separator > 0, wire)
        val handle = PluginWire.decode(wire) as PluginWire.Value.Handle
        assertTrue(handle.readOnly)
        assertEquals("dialog", JSONObject(wire.substring(separator + 1)).getString("_"))
    }
}
