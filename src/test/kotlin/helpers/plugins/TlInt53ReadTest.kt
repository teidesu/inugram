package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlHandles
import desu.inugram.helpers.plugins.tl.TlJson
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONArray
import org.junit.Before
import org.junit.Test
import org.telegram.tgnet.TLRPC

class TlInt53ReadTest {
    private val POLICY = TlFilter.Policy(takeover = false, drafts = true)

    @Before
    fun setUp() = resetBridge()

    private fun inputPeer(): TLRPC.TL_inputPeerUser = TLRPC.TL_inputPeerUser().apply {
        user_id = 5_000_000_001L
        access_hash = Long.MIN_VALUE
    }.synced()

    private fun channelFull(): TLRPC.TL_channelFull = TLRPC.TL_channelFull().apply {
        id = 1_000_000_007L
        recent_requesters.add(5_000_000_001L)
        recent_requesters.add(42L)
    }.synced()

    @Test
    fun a_vector_of_int53_longs_hands_its_elements_out_as_numbers() {
        val handles = TlHandles(POLICY)
        val handle = handles.mintForPlugin(channelFull(), readOnly = true)
        val vector = PluginWire.decode(handles.tlGet(handle, "recent_requesters")) as PluginWire.Value.Handle
        assertTrue(vector.vector)
        assertEquals(PluginWire.Value.IntNum(5_000_000_001L), PluginWire.decode(handles.tlGet(vector.id, "0")))
        assertEquals(JSONArray("[5000000001,42]").toString(), handles.tlCopy(vector.id))
    }

    @Test
    fun a_snapshot_carries_the_same_shapes_a_projection_does() {
        val snapshot = TlJson.toJson(channelFull(), POLICY)
        assertEquals(1_000_000_007L, snapshot.get("id"))
        assertEquals(5_000_000_001L, snapshot.getJSONArray("recent_requesters").get(0))
    }

    @Test
    fun a_long_takes_a_safe_integer_or_a_decimal_string_and_refuses_the_rest() {
        val handles = TlHandles(POLICY)
        val target = inputPeer()
        val handle = handles.mintForPlugin(target, readOnly = false)

        assertNull(handles.tlSet(handle, "user_id", PluginWire.encodeJson("9007199254740991")))
        assertEquals(9_007_199_254_740_991L, target.user_id)
        assertNull(handles.tlSet(handle, "access_hash", PluginWire.encodeJson("\"-9223372036854775808\"")))
        assertEquals(Long.MIN_VALUE, target.access_hash)

        for (unsafe in listOf("9007199254740993", "1.5", "1e300", "99999999999999999999")) {
            val refusal = handles.tlSet(handle, "access_hash", PluginWire.encodeJson(unsafe))
            assertNotNull(refusal, unsafe)
            assertTrue(refusal.contains("safe integer"), refusal)
        }
        assertEquals(Long.MIN_VALUE, target.access_hash, "a refused write leaves the field alone")
    }
}
