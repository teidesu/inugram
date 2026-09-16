package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlHandles
import desu.inugram.helpers.plugins.tl.TlJson
import desu.inugram.helpers.plugins.tl.TlReflect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.telegram.tgnet.TLRPC

/**
 * A long the int53 table marks crosses as a js number on every path a plugin reads it through - by
 * name, by ordinal, in a projection, as a vector element and in a `toJSON` snapshot - and every other
 * long stays a decimal string on all of them.
 */
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
    fun the_table_marks_ids_and_leaves_hashes_alone() {
        val infos = TlReflect.fieldInfos(TLRPC.TL_inputPeerUser::class.java)
        assertTrue(infos.getValue("user_id").isInt53)
        assertTrue(!infos.getValue("access_hash").isInt53)
        assertTrue(TlReflect.fieldInfos(TLRPC.TL_channelFull::class.java).getValue("recent_requesters").isInt53)
    }

    @Test
    fun by_name_an_int53_long_is_a_number_and_a_hash_a_string() {
        val handles = TlHandles(POLICY)
        val handle = handles.mintForPlugin(inputPeer(), readOnly = true)
        assertEquals(PluginWire.Value.IntNum(5_000_000_001L), PluginWire.decode(handles.tlGet(handle, "user_id")))
        assertEquals(PluginWire.Value.Str(Long.MIN_VALUE.toString()), PluginWire.decode(handles.tlGet(handle, "access_hash")))
    }

    @Test
    fun by_ordinal_an_int53_long_takes_its_own_tag() {
        val handles = TlHandles(POLICY)
        val target = inputPeer()
        val handle = handles.mintForPlugin(target, readOnly = true)
        val classId = handles.classIdOf(target.javaClass)
        val buffer = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN)

        val userId = handles.resolveField(classId, "user_id")
        assertNotEquals(TlHandles.ORDINAL_FALLBACK, handles.readField(handle, classId, userId, buffer))
        assertEquals(TAG_INT53, buffer.get(0))
        assertEquals(5_000_000_001L, buffer.getLong(1))

        val hash = handles.resolveField(classId, "access_hash")
        assertNotEquals(TlHandles.ORDINAL_FALLBACK, handles.readField(handle, classId, hash, buffer))
        assertEquals(TAG_LONG, buffer.get(0))
    }

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
    fun a_projection_and_a_snapshot_carry_the_same_shapes() {
        val handles = TlHandles(POLICY)
        // InputPeer declares an object `peer`, so only a projection that names its fields carries them
        val projection = JSONObject(handles.project(handles.mintForPlugin(inputPeer(), readOnly = true), listOf("user_id", "access_hash")))
        assertEquals(5_000_000_001L, projection.get("user_id"))
        assertEquals(Long.MIN_VALUE.toString(), projection.get("access_hash"))

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

    private companion object {
        // mirrored in TlHandles.kt and src/native/src/api/tl/proxy.rs
        const val TAG_LONG = 3.toByte()
        const val TAG_INT53 = 9.toByte()
    }
}
