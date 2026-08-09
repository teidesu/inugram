package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
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
    // a fresh store per test: these are real SharedPreferences, and `apply()` reaches disk
    // asynchronously, so a store one test filled is still in the cache when the next one starts.
    // JUnit builds one instance per test method, so an id minted here is that method's alone.
    private val install = freshInstallId()
    private val other = freshInstallId()

    @Before
    fun setUp() = resetBridge()

    private fun op(op: Int, key: String = "", value: String = "", id: String = install): String =
        PluginKv.handleOp(id, op, key, value)

    private fun get(key: String, id: String = install): PluginWire.Value = PluginWire.decode(op(PluginKv.OP_GET, key, id = id))

    private fun set(key: String, value: String, id: String = install): PluginWire.Value =
        PluginWire.decode(op(PluginKv.OP_SET, key, value, id))

    private fun keys(id: String = install): List<String> {
        val json = (PluginWire.decode(op(PluginKv.OP_KEYS, id = id)) as PluginWire.Value.Json).json
        return org.json.JSONArray(json).let { array -> (0 until array.length()).map { array.getString(it) } }
    }

    private fun quotaError(wire: PluginWire.Value): PluginWire.Value.PluginErr {
        assertTrue(wire is PluginWire.Value.PluginErr, "expected a PluginError, got $wire")
        assertEquals("quota-exceeded", (wire as PluginWire.Value.PluginErr).code)
        return wire
    }

    private fun statedQuota(): Long = 1024L * 1024L

    @Test
    fun a_value_survives_the_round_trip_and_a_missing_key_is_null() {
        assertEquals(PluginWire.Value.Null, get("absent"))
        assertEquals(PluginWire.Value.Null, set("k", "v"))
        assertEquals("v", (get("k") as PluginWire.Value.Str).value)

        assertEquals(PluginWire.Value.Null, PluginWire.decode(op(PluginKv.OP_DEL, "k")))
        assertEquals(PluginWire.Value.Null, get("k"))
    }

    @Test
    fun keys_getAll_insertAll_and_clear_see_the_same_store() {
        set("a", "1")
        val inserted = JSONObject().put("b", "2").put("c", "3")
        assertEquals(PluginWire.Value.Null, PluginWire.decode(op(PluginKv.OP_INSERT_ALL, "", inserted.toString())))

        assertEquals(listOf("a", "b", "c"), keys().sorted())
        val all = JSONObject((PluginWire.decode(op(PluginKv.OP_GET_ALL)) as PluginWire.Value.Json).json)
        assertEquals("2", all.getString("b"))

        assertEquals(PluginWire.Value.Null, PluginWire.decode(op(PluginKv.OP_CLEAR)))
        assertEquals(emptyList(), keys())
    }

    @Test
    fun has_and_usage_read_the_same_store_the_other_ops_write() {
        fun has(key: String) = (PluginWire.decode(op(PluginKv.OP_HAS, key)) as PluginWire.Value.Json).json.toBoolean()
        fun usage() = (PluginWire.decode(op(PluginKv.OP_USAGE)) as PluginWire.Value.Json).json.toLong()

        assertEquals(0L, usage())
        assertEquals(false, has("k"))
        set("k", "value")
        assertEquals(true, has("k"))
        assertEquals(6L, usage(), "the key's bytes count, exactly as they do against the quota")

        PluginWire.decode(op(PluginKv.OP_DEL, "k"))
        assertEquals(false, has("k"))
        assertEquals(0L, usage())
    }

    @Test
    fun insertAll_refuses_a_value_that_is_not_a_string_and_writes_nothing() {
        val decoded = PluginWire.decode(op(PluginKv.OP_INSERT_ALL, "", JSONObject().put("n", 5).toString()))
        assertTrue(decoded is PluginWire.Value.Error, "got $decoded")
        assertEquals(emptyList(), keys())
    }

    @Test
    fun an_unknown_op_is_an_error_rather_than_a_silent_no_op() {
        assertTrue(PluginWire.decode(op(99)) is PluginWire.Value.Error)
    }

    /** the store is keyed by install id, which is what makes a rename keep its data and a squatter get none */
    @Test
    fun two_installs_do_not_see_each_other_s_store() {
        set("k", "mine")
        assertEquals(PluginWire.Value.Null, get("k", id = other))
        set("k", "theirs", id = other)
        assertEquals("mine", (get("k") as PluginWire.Value.Str).value)

        PluginKv.wipe(other)
        assertEquals(PluginWire.Value.Null, get("k", id = other))
        assertEquals("mine", (get("k") as PluginWire.Value.Str).value)
    }

    @Test
    fun a_write_past_the_quota_is_refused_carrying_the_usage_and_the_quota_it_hit() {
        val quota = statedQuota()
        val error = quotaError(set("big", "x".repeat((quota + 1).toInt())))

        assertEquals(quota, error.quota)
        assertTrue(error.usage!! > quota, "usage was ${error.usage}")
        assertEquals(PluginWire.Value.Null, get("big"), "a refused write must not land")
    }

    @Test
    fun a_write_that_exactly_fills_the_quota_is_allowed() {
        val quota = statedQuota()
        assertEquals(PluginWire.Value.Null, set("k", "x".repeat((quota - 1).toInt())))
        assertEquals(quota - 1, (get("k") as PluginWire.Value.Str).value.length.toLong())
    }

    /** the key counts too: the store's size is what a plugin holds, not what its values hold */
    @Test
    fun the_key_s_own_bytes_count_against_the_quota() {
        val quota = statedQuota()
        val key = "k".repeat(16)
        quotaError(set(key, "x".repeat((quota - 15).toInt())))
    }

    /**
     * a rewrite is charged by difference, or a store sitting at the cap could never be shrunk: the
     * naive sum counts the old value and the new one at once and refuses every replacement.
     */
    @Test
    fun replacing_a_value_charges_the_difference_rather_than_both_copies() {
        val quota = statedQuota()
        val big = "x".repeat((quota - 1).toInt())
        assertEquals(PluginWire.Value.Null, set("k", big))
        assertEquals(PluginWire.Value.Null, set("k", big), "the same value again is not twice the store")
        assertEquals(PluginWire.Value.Null, set("k", "small"))
    }

    @Test
    fun insertAll_is_measured_as_one_write_and_lands_as_nothing_when_it_does_not_fit() {
        val quota = statedQuota()
        val half = "x".repeat((quota / 2).toInt())
        val batch = JSONObject().put("a", half).put("b", half).put("c", half)

        val error = quotaError(PluginWire.decode(op(PluginKv.OP_INSERT_ALL, "", batch.toString())))
        assertEquals(quota, error.quota)
        assertEquals(emptyList(), keys(), "a batch that does not fit writes none of itself")
    }

    @Test
    fun a_store_cleared_under_the_cap_accepts_a_write_that_was_refused_before() {
        val quota = statedQuota()
        assertEquals(PluginWire.Value.Null, set("a", "x".repeat((quota - 1).toInt())))
        quotaError(set("b", "y"))

        assertEquals(PluginWire.Value.Null, PluginWire.decode(op(PluginKv.OP_DEL, "a")))
        assertEquals(PluginWire.Value.Null, set("b", "y"))
    }

    @Test
    fun an_id_that_is_not_one_never_becomes_a_prefs_file_name() {
        // a literal for the uppercase case: `freshInstallId` is a formatted number, so it carries no
        // letter to change often enough for `install.uppercase()` to be the same string
        val mixed = "0123456789abcdef0123456789abcdef"
        for (id in listOf("../../etc", "", "not-hex-at-all-not-hex-at-all-xx", mixed.uppercase(), install + "0")) {
            val decoded = PluginWire.decode(PluginKv.handleOp(id, PluginKv.OP_SET, "k", "v"))
            assertTrue(decoded is PluginWire.Value.Error, "'$id' was accepted: $decoded")
        }
    }
}
