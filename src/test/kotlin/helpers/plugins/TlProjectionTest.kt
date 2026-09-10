package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlHandles
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.telegram.tgnet.TLRPC

/**
 * `TlHandles.projectScalars`: the scalars a handle carries must read exactly as the same field
 * would on its own, because rust serves them from the cache without ever asking.
 */
class TlProjectionTest {
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

    private fun projected(policy: TlFilter.Policy, target: org.telegram.tgnet.TLObject): JSONObject {
        val handles = TlHandles(policy)
        return JSONObject(handles.projectScalars(handles.mintForPlugin(target, readOnly = true)))
    }

    private fun field(handles: TlHandles, target: org.telegram.tgnet.TLObject, key: String): PluginWire.Value =
        PluginWire.decode(handles.tlGet(handles.mintForPlugin(target, readOnly = true), key))

    @Test
    fun every_projected_scalar_reads_as_the_field_itself_would() {
        val policy = TlFilter.Policy(takeover = true, drafts = true)
        val handles = TlHandles(policy)
        val target = dialog()
        val projection = projected(policy, target)
        assertEquals("dialog", projection.getString("_"))
        for (key in projection.keys()) {
            if (key == "_" || projection.get(key) is JSONObject) continue
            val expected = field(handles, target, key)
            val got = projection.get(key)
            when (expected) {
                is PluginWire.Value.Str -> assertEquals(expected.value, got, key)
                is PluginWire.Value.IntNum -> assertEquals(expected.value, (got as Number).toLong(), key)
                is PluginWire.Value.Bool -> assertEquals(expected.value, got, key)
                is PluginWire.Value.Null -> assertEquals(JSONObject.NULL, got, key)
                else -> error("$key crossed as $expected, which a projection must not carry")
            }
        }
    }

    /** a boolean's bit is its value, so an unset one reads `null` rather than `false`, on both paths */
    @Test
    fun a_long_is_a_string_and_a_cleared_bit_is_null() {
        val policy = TlFilter.Policy(takeover = true, drafts = true)
        val bare = TLRPC.TL_dialog().apply {
            id = 5_000_000_001L
            peer = TLRPC.TL_peerUser().apply { user_id = 5_000_000_001L }
            top_message = 7
        }.synced()
        val projection = projected(policy, bare)
        assertEquals("5000000001", projection.getString("id"))
        assertEquals(7, projection.getInt("top_message"))
        assertTrue(projection.has("pinned") && projection.isNull("pinned"))
        assertTrue(projection.has("folder_id") && projection.isNull("folder_id"))
        assertEquals(PluginWire.Value.Null, field(TlHandles(policy), bare, "pinned"))
    }

    /**
     * a peer is nothing but scalars, so it rides along as a view of its own; a draft is not, and
     * stays the lazy handle it was - carrying its type name, the field most read off a nested object
     */
    @Test
    fun a_child_of_scalars_rides_along_and_anything_deeper_does_not() {
        val policy = TlFilter.Policy(takeover = true, drafts = true)
        val handles = TlHandles(policy)
        val target = dialog()
        val projection = JSONObject(handles.projectScalars(handles.mintForPlugin(target, readOnly = true)))

        val peer = projection.getJSONObject("peer")
        assertEquals("peerUser", peer.getString("_"))
        assertEquals("5000000001", peer.getString("user_id"))
        val handle = peer.getString("@h")
        assertTrue(handle.startsWith("OR"), handle)
        // the same handle a read would have answered with, and it answers the same fields
        val child = handle.drop(2).toLong()
        assertEquals(PluginWire.Value.Str("peerUser"), PluginWire.decode(handles.tlGet(child, "_")))
        assertEquals(PluginWire.Value.Str("5000000001"), PluginWire.decode(handles.tlGet(child, "user_id")))

        assertFalse(projection.has("draft"), "an object with children of its own stays lazy")
        val draftWire = handles.tlGet(handles.mintForPlugin(target, readOnly = true), "draft")
        val separator = draftWire.indexOf(PluginWire.PROJECTION_SEPARATOR)
        assertTrue(separator > 0, draftWire)
        assertEquals("draftMessage", JSONObject(draftWire.substring(separator + 1)).getString("_"))
    }

    @Test
    fun nothing_nested_and_nothing_hidden_is_projected() {
        val withDrafts = projected(TlFilter.Policy(takeover = true, drafts = true), dialog())
        assertFalse(withDrafts.has("draft"), "an object with children of its own stays a lazy handle")
        assertFalse(withDrafts.has("flags"), "flag words are never a field")
        val message = TLRPC.TL_message().apply {
            id = 1
            message = "hello"
            peer_id = TLRPC.TL_peerUser().apply { user_id = 1L }
        }.synced()
        val filtered = projected(TlFilter.Policy(takeover = true, drafts = false), message)
        val unfiltered = projected(TlFilter.Policy(takeover = false, drafts = true), message)
        assertEquals("hello", unfiltered.getString("message"))
        assertEquals(unfiltered.getString("message"), filtered.getString("message"), "an ordinary message is not redacted")
    }

    /**
     * the child a projection carries is a handle on the app's own object, not a copy of its values:
     * a write through it has to land in java, and the next read has to answer with what landed.
     */
    @Test
    fun a_write_through_a_projected_child_lands_in_java() {
        val handles = TlHandles(TlFilter.Policy(takeover = false, drafts = true))
        val message = TLRPC.TL_message().apply {
            id = 4
            message = "hi"
            peer_id = TLRPC.TL_peerUser().apply { user_id = 11L }
        }.synced()
        val projection = JSONObject(handles.projectScalars(handles.mintForPlugin(message, readOnly = false)))
        val child = projection.getJSONObject("peer_id").getString("@h")
        assertTrue(child.startsWith("OW"), "a writable parent carries a writable child: $child")

        val handle = child.drop(2).toLong()
        assertNull(handles.tlSet(handle, "user_id", PluginWire.encodeJson("\"77\"")))
        assertEquals(77L, (message.peer_id as TLRPC.TL_peerUser).user_id, "the write must reach the app's object")
        assertEquals(PluginWire.Value.Str("77"), PluginWire.decode(handles.tlGet(handle, "user_id")))
    }

    /** an interceptor's view caches nothing, so nothing rides along for a write to go stale against */
    @Test
    fun a_dispatch_scoped_child_carries_no_projection() {
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
        val handles = desu.inugram.helpers.plugins.tl.TlHandles.attach(plugin, TlFilter.Policy(takeover = true, drafts = true))
        val wire = desu.inugram.helpers.plugins.telegram.PluginReads.mint(handles, dialog())
        val separator = wire.indexOf(PluginWire.PROJECTION_SEPARATOR)
        assertTrue(separator > 0, wire)
        val handle = PluginWire.decode(wire) as PluginWire.Value.Handle
        assertTrue(handle.readOnly)
        assertEquals("dialog", JSONObject(wire.substring(separator + 1)).getString("_"))
    }
}
