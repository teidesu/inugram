package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlHandles
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

/**
 * `TlHandles.readField` is the same read as [TlHandles.tlGet] with the name resolved to an ordinal
 * and the answer written as bytes. The two must therefore answer identically for every field of
 * every shape, because which one rust takes is its own business - a plugin sees one surface.
 *
 * What it declines to serve it answers [TlHandles.ORDINAL_FALLBACK] to, and rust reads that field
 * by name instead, so a refusal is only ever a slower read.
 */
class TlOrdinalReadTest {
    private val POLICY = TlFilter.Policy(takeover = true, drafts = true)

    @Before
    fun setUp() = resetBridge()

    private val buffer: ByteBuffer = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)

    private fun message(): TLRPC.TL_message = TLRPC.TL_message().apply {
        id = 4
        message = "hello"
        date = 1_700_000_000
        out = true
        peer_id = TLRPC.TL_peerUser().apply { user_id = 5_000_000_001L }
        entities.add(TLRPC.TL_messageEntityBold().apply { offset = 0; length = 2 })
    }.synced()

    /** every scalar kind on one object: a long, an int, a bool, a string, a double and bytes */
    private fun rights(): TLRPC.TL_chatBannedRights = TLRPC.TL_chatBannedRights().apply {
        view_messages = true
        until_date = 900
    }.synced()

    private fun readBoth(handles: TlHandles, handle: Long, target: TLObject, key: String): Pair<PluginWire.Value, PluginWire.Value> {
        val byName = PluginWire.decode(handles.tlGet(handle, key))
        val classId = handles.classIdOf(target.javaClass)
        val ordinal = handles.resolveField(classId, key)
        assertNotEquals(TlHandles.ORDINAL_FALLBACK, ordinal, "no ordinal for '$key'")
        val written = handles.readField(handle, classId, ordinal, buffer)
        assertNotEquals(TlHandles.ORDINAL_FALLBACK, written, "readField declined '$key'")
        return byName to decodeBinary(written)
    }

    /** the format `TlHandles` writes and `proxy.rs` reads, restated so a change to either shows up here */
    private fun decodeBinary(written: Int): PluginWire.Value {
        buffer.position(0).limit(written)
        val value = when (val tag = buffer.get().toInt()) {
            0 -> PluginWire.Value.Null
            1 -> PluginWire.Value.Bool(buffer.get().toInt() != 0)
            2 -> PluginWire.Value.IntNum(buffer.int.toLong())
            // a tl long crosses as a string, so this is what the by-name path spells too
            3 -> PluginWire.Value.Str(buffer.long.toString())
            4 -> PluginWire.Value.DoubleNum(buffer.double)
            5 -> PluginWire.Value.Str(readUtf8())
            6 -> PluginWire.Value.Bytes(
                android.util.Base64.encodeToString(ByteArray(buffer.int).also { buffer.get(it) }, android.util.Base64.NO_WRAP),
            )
            7 -> buffer.get().toInt().let { flags ->
                PluginWire.Value.Handle(vector = flags and 1 != 0, id = buffer.long, readOnly = flags and 2 != 0)
                    .also { buffer.int }
            }
            else -> error("unknown tag $tag")
        }
        assertEquals(written, buffer.position(), "the reply carried bytes nothing read")
        buffer.limit(buffer.capacity())
        return value
    }

    private fun readUtf8(): String = String(ByteArray(buffer.int).also { buffer.get(it) }, Charsets.UTF_8)

    @Test
    fun every_field_reads_the_same_by_ordinal_as_by_name() {
        val handles = TlHandles(POLICY)
        var checked = 0
        for (target in listOf(message(), rights(), TLRPC.TL_peerUser().apply { user_id = 7L }.synced())) {
            val handle = handles.mintForPlugin(target, readOnly = true)
            for (key in desu.inugram.helpers.plugins.tl.TlReflect.fieldInfos(target.javaClass).keys) {
                val (byName, byOrdinal) = readBoth(handles, handle, target, key)
                checked++
                if (byName is PluginWire.Value.Handle) {
                    // a child is minted per read, so the ids differ by construction: what must match
                    // is the shape, the mode, and the object on the far side of it
                    val other = byOrdinal as PluginWire.Value.Handle
                    assertEquals(byName.vector, other.vector, key)
                    assertEquals(byName.readOnly, other.readOnly, key)
                    if (!byName.vector) {
                        assertEquals(handles.tlGet(byName.id, "_"), handles.tlGet(other.id, "_"), key)
                    }
                } else {
                    assertEquals(byName, byOrdinal, key)
                }
            }
        }
        assertTrue(checked > 40, "a message, a bannedRights and a peer are 40-odd fields, checked $checked")
    }

    /** the flag word, a cleared bit and a field the api filter hides all read as absent on both paths */
    @Test
    fun an_absent_field_is_null_on_both_paths() {
        val handles = TlHandles(POLICY)
        val target = rights()
        val handle = handles.mintForPlugin(target, readOnly = true)
        for (key in listOf("flags", "send_messages", "send_media")) {
            val (byName, byOrdinal) = readBoth(handles, handle, target, key)
            assertEquals(PluginWire.Value.Null, byName, key)
            assertEquals(PluginWire.Value.Null, byOrdinal, key)
        }
    }

    /** a child handle names its class, so the read after it can take the ordinal path too */
    @Test
    fun a_child_handle_names_a_class_its_own_fields_answer_for() {
        val handles = TlHandles(POLICY)
        val message = message()
        val handle = handles.mintForPlugin(message, readOnly = true)
        val wire = handles.tlGet(handle, "peer_id")
        val child = PluginWire.decode(wire) as PluginWire.Value.Handle
        val classId = wire.substringAfter(PluginWire.CLASS_SEPARATOR).toInt()
        assertEquals(handles.classIdOf(TLRPC.TL_peerUser::class.java), classId)

        val ordinal = handles.resolveField(classId, "user_id")
        val written = handles.readField(child.id, classId, ordinal, buffer)
        assertEquals(PluginWire.Value.Str("5000000001"), decodeBinary(written))
    }

    @Test
    fun a_read_it_will_not_serve_falls_back_rather_than_answering_wrongly() {
        val handles = TlHandles(POLICY)
        val message = message()
        val handle = handles.mintForPlugin(message, readOnly = true)
        val classId = handles.classIdOf(message.javaClass)

        assertEquals(TlHandles.ORDINAL_FALLBACK, handles.resolveField(classId, "not_a_field"))
        assertEquals(TlHandles.ORDINAL_FALLBACK, handles.resolveField(Int.MAX_VALUE, "id"))

        val ordinal = handles.resolveField(classId, "id")
        assertEquals(TlHandles.ORDINAL_FALLBACK, handles.readField(handle, classId, Int.MAX_VALUE, buffer), "unknown ordinal")
        assertEquals(TlHandles.ORDINAL_FALLBACK, handles.readField(9999L, classId, ordinal, buffer), "unknown handle")

        // the guard that matters: a handle whose class is not the one the ordinal was resolved
        // against must refuse, or a forged id would read whatever field sits at that index
        val other = handles.mintForPlugin(TLRPC.TL_peerUser().apply { user_id = 7L }.synced(), readOnly = true)
        assertEquals(TlHandles.ORDINAL_FALLBACK, handles.readField(other, classId, ordinal, buffer), "class mismatch")
    }

    /** a value larger than the buffer is refused whole, never written short */
    @Test
    fun an_oversized_value_falls_back() {
        val handles = TlHandles(POLICY)
        val message = TLRPC.TL_message().apply {
            id = 1
            message = "x".repeat(200)
            peer_id = TLRPC.TL_peerUser().apply { user_id = 1L }
        }.synced()
        val handle = handles.mintForPlugin(message, readOnly = true)
        val classId = handles.classIdOf(message.javaClass)
        val ordinal = handles.resolveField(classId, "message")
        val small = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(TlHandles.ORDINAL_FALLBACK, handles.readField(handle, classId, ordinal, small))
        assertEquals(PluginWire.Value.Str("x".repeat(200)), PluginWire.decode(handles.tlGet(handle, "message")))
    }

    /** a writable parent hands out a writable child on this path too, and the write still lands in java */
    @Test
    fun a_write_through_an_ordinal_read_child_lands_in_java() {
        val handles = TlHandles(TlFilter.Policy(takeover = false, drafts = true))
        val message = message()
        val handle = handles.mintForPlugin(message, readOnly = false)
        val classId = handles.classIdOf(message.javaClass)
        val ordinal = handles.resolveField(classId, "peer_id")
        val child = decodeBinary(handles.readField(handle, classId, ordinal, buffer)) as PluginWire.Value.Handle
        assertTrue(!child.readOnly)
        assertEquals(null, handles.tlSet(child.id, "user_id", PluginWire.encodeJson("\"77\"")))
        assertEquals(77L, (message.peer_id as TLRPC.TL_peerUser).user_id)
    }
}
