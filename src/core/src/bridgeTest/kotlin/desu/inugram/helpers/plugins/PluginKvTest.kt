package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.TlWire
import desu.inugram.helpers.plugins.api.PluginKv
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.Test

/**
 * `inu.kv`, whose whole policy is one quota. The store itself is android's, but the arithmetic that
 * decides whether a write is allowed is a sum over a map, and the number it compares against is
 * stated in `common.d.ts` where a plugin can read it.
 */
class PluginKvTest {
    private val install = "0123456789abcdef0123456789abcdef"
    private val other = "fedcba9876543210fedcba9876543210"

    @Before
    fun setUp() = resetBridge()

    private fun op(op: Int, key: String = "", value: String = "", id: String = install): String =
        PluginKv.handleOp(id, op, key, value)

    private fun get(key: String, id: String = install): TlWire.Value = TlWire.decode(op(PluginKv.OP_GET, key, id = id))

    private fun set(key: String, value: String, id: String = install): TlWire.Value =
        TlWire.decode(op(PluginKv.OP_SET, key, value, id))

    private fun keys(id: String = install): List<String> {
        val json = (TlWire.decode(op(PluginKv.OP_KEYS, id = id)) as TlWire.Value.Json).json
        return org.json.JSONArray(json).let { array -> (0 until array.length()).map { array.getString(it) } }
    }

    private fun quotaError(wire: TlWire.Value): TlWire.Value.PluginErr {
        assertTrue(wire is TlWire.Value.PluginErr, "expected a PluginError, got $wire")
        assertEquals("quota-exceeded", (wire as TlWire.Value.PluginErr).code)
        return wire
    }

    /** the documented ceiling, read where a plugin reads it rather than off the constant it checks */
    private fun statedQuota(): Long = statedNumber(contract(), "{} MB per-plugin quota") * 1024 * 1024

    @Test
    fun `a value survives the round trip and a missing key is null`() {
        assertEquals(TlWire.Value.Null, get("absent"))
        assertEquals(TlWire.Value.Null, set("k", "v"))
        assertEquals("v", (get("k") as TlWire.Value.Str).value)

        assertEquals(TlWire.Value.Null, TlWire.decode(op(PluginKv.OP_DEL, "k")))
        assertEquals(TlWire.Value.Null, get("k"))
    }

    @Test
    fun `keys, getAll, insertAll and clear see the same store`() {
        set("a", "1")
        val inserted = JSONObject().put("b", "2").put("c", "3")
        assertEquals(TlWire.Value.Null, TlWire.decode(op(PluginKv.OP_INSERT_ALL, "", inserted.toString())))

        assertEquals(listOf("a", "b", "c"), keys().sorted())
        val all = JSONObject((TlWire.decode(op(PluginKv.OP_GET_ALL)) as TlWire.Value.Json).json)
        assertEquals("2", all.getString("b"))

        assertEquals(TlWire.Value.Null, TlWire.decode(op(PluginKv.OP_CLEAR)))
        assertEquals(emptyList(), keys())
    }

    @Test
    fun `has and usage read the same store the other ops write`() {
        fun has(key: String) = (TlWire.decode(op(PluginKv.OP_HAS, key)) as TlWire.Value.Json).json.toBoolean()
        fun usage() = (TlWire.decode(op(PluginKv.OP_USAGE)) as TlWire.Value.Json).json.toLong()

        assertEquals(0L, usage())
        assertEquals(false, has("k"))
        set("k", "value")
        assertEquals(true, has("k"))
        assertEquals(6L, usage(), "the key's bytes count, exactly as they do against the quota")

        TlWire.decode(op(PluginKv.OP_DEL, "k"))
        assertEquals(false, has("k"))
        assertEquals(0L, usage())
    }

    @Test
    fun `insertAll refuses a value that is not a string, and writes nothing`() {
        val decoded = TlWire.decode(op(PluginKv.OP_INSERT_ALL, "", JSONObject().put("n", 5).toString()))
        assertTrue(decoded is TlWire.Value.Error, "got $decoded")
        assertEquals(emptyList(), keys())
    }

    @Test
    fun `an unknown op is an error rather than a silent no-op`() {
        assertTrue(TlWire.decode(op(99)) is TlWire.Value.Error)
    }

    /** the store is keyed by install id, which is what makes a rename keep its data and a squatter get none */
    @Test
    fun `two installs do not see each other's store`() {
        set("k", "mine")
        assertEquals(TlWire.Value.Null, get("k", id = other))
        set("k", "theirs", id = other)
        assertEquals("mine", (get("k") as TlWire.Value.Str).value)

        PluginKv.wipe(other)
        assertEquals(TlWire.Value.Null, get("k", id = other))
        assertEquals("mine", (get("k") as TlWire.Value.Str).value)
    }

    @Test
    fun `a write past the quota is refused, carrying the usage and the quota it hit`() {
        val quota = statedQuota()
        val error = quotaError(set("big", "x".repeat((quota + 1).toInt())))

        assertEquals(quota, error.quota)
        assertTrue(error.usage!! > quota, "usage was ${error.usage}")
        assertEquals(TlWire.Value.Null, get("big"), "a refused write must not land")
    }

    @Test
    fun `a write that exactly fills the quota is allowed`() {
        val quota = statedQuota()
        assertEquals(TlWire.Value.Null, set("k", "x".repeat((quota - 1).toInt())))
        assertEquals(quota - 1, (get("k") as TlWire.Value.Str).value.length.toLong())
    }

    /** the key counts too: the store's size is what a plugin holds, not what its values hold */
    @Test
    fun `the key's own bytes count against the quota`() {
        val quota = statedQuota()
        val key = "k".repeat(16)
        quotaError(set(key, "x".repeat((quota - 15).toInt())))
    }

    /**
     * a rewrite is charged by difference, or a store sitting at the cap could never be shrunk: the
     * naive sum counts the old value and the new one at once and refuses every replacement.
     */
    @Test
    fun `replacing a value charges the difference rather than both copies`() {
        val quota = statedQuota()
        val big = "x".repeat((quota - 1).toInt())
        assertEquals(TlWire.Value.Null, set("k", big))
        assertEquals(TlWire.Value.Null, set("k", big), "the same value again is not twice the store")
        assertEquals(TlWire.Value.Null, set("k", "small"))
    }

    @Test
    fun `insertAll is measured as one write and lands as nothing when it does not fit`() {
        val quota = statedQuota()
        val half = "x".repeat((quota / 2).toInt())
        val batch = JSONObject().put("a", half).put("b", half).put("c", half)

        val error = quotaError(TlWire.decode(op(PluginKv.OP_INSERT_ALL, "", batch.toString())))
        assertEquals(quota, error.quota)
        assertEquals(emptyList(), keys(), "a batch that does not fit writes none of itself")
    }

    @Test
    fun `a store cleared under the cap accepts a write that was refused before`() {
        val quota = statedQuota()
        assertEquals(TlWire.Value.Null, set("a", "x".repeat((quota - 1).toInt())))
        quotaError(set("b", "y"))

        assertEquals(TlWire.Value.Null, TlWire.decode(op(PluginKv.OP_DEL, "a")))
        assertEquals(TlWire.Value.Null, set("b", "y"))
    }

    @Test
    fun `an id that is not one never becomes a prefs file name`() {
        for (id in listOf("../../etc", "", "not-hex-at-all-not-hex-at-all-xx", install.uppercase(), install + "0")) {
            val decoded = TlWire.decode(PluginKv.handleOp(id, PluginKv.OP_SET, "k", "v"))
            assertTrue(decoded is TlWire.Value.Error, "'$id' was accepted: $decoded")
        }
    }
}
