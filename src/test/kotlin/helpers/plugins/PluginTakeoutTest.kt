package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.telegram.TakeoutSession
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.telegram.tgnet.SerializedData
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

/**
 * The takeout and raw halves of `invokeRpc`, which are the only requests the bridge builds out of
 * classes stock does not have. What they must get right is the wire, so every assertion here is on
 * the bytes the request serializes to rather than on its fields.
 */
class PluginTakeoutTest {
    private val selfId = 100L

    @Before
    fun setUp() {
        resetBridge()
        TestApp.signInAs(0, TLRPC.TL_user().apply { id = selfId })
    }

    private fun granted(vararg extra: String) =
        startPlugin("takeout", listOf("takeout", "unsafe.invokeRaw") + extra) {}

    private fun rpc(plugin: Plugin) = plugin.js.listener!!

    private fun serialize(request: TLObject): ByteArray {
        val data = SerializedData(request.objectSize)
        request.serializeToStream(data)
        return data.toByteArray()
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun initOptions(vararg pairs: Pair<String, Any>): String =
        JSONObject().apply { for ((key, value) in pairs) put(key, value) }.toString()

    @Test
    fun an_init_writes_the_flags_its_options_asked_for() {
        val plugin = granted()
        assertNull(rpc(plugin).onTakeout(1L, 0, RpcListener.OP_TAKEOUT_INIT, "", initOptions("messageUsers" to true)))
        drain()

        // account.initTakeoutSession#8ef3eab0, flags = message_users only
        assertEquals("b0eaf38e02000000", hex(serialize(connections().lastSent()!!.request)))
    }

    @Test
    fun a_file_size_is_what_asks_for_files_at_all() {
        val plugin = granted()
        assertNull(
            rpc(plugin).onTakeout(
                1L,
                0,
                RpcListener.OP_TAKEOUT_INIT,
                "",
                initOptions("contacts" to true, "fileMaxSize" to 1500000),
            ),
        )
        drain()

        // flags = contacts | files, then file_max_size as an int64
        assertEquals("b0eaf38e" + "21000000" + "60e3160000000000", hex(serialize(connections().lastSent()!!.request)))
    }

    @Test
    fun an_init_resolves_with_the_session_id_as_a_string() {
        val plugin = granted()
        rpc(plugin).onTakeout(7L, 0, RpcListener.OP_TAKEOUT_INIT, "", initOptions())
        drain()
        connections().lastSent()!!.answer(TakeoutSession().apply { id = 8123456789L }, null)
        drain()

        val invoke = plugin.js.invokes.last()
        assertEquals(7L, invoke.invokeId)
        assertEquals(PluginWire.Value.Str("8123456789"), PluginWire.decode(invoke.resultWire))
    }

    @Test
    fun a_wrapped_call_carries_the_session_id_and_then_the_query() {
        val plugin = granted("invokeRpc(users.getUsers)")
        val query = JSONObject().put("_", "users.getUsers").toString()
        assertNull(
            rpc(plugin).onTakeout(1L, 0, RpcListener.OP_TAKEOUT_INVOKE, "8123456789", PluginWire.encodeJson(query)),
        )
        drain()

        // invokeWithTakeout#aca9fd2e, the id as an int64, then the query exactly as it serializes alone
        val expected = "2efda9ac" + "151d32e401000000" + hex(serialize(TLRPC.TL_users_getUsers()))
        assertEquals(expected, hex(serialize(connections().lastSent()!!.request)))
    }

    @Test
    fun a_finish_is_itself_wrapped() {
        val plugin = granted()
        assertNull(rpc(plugin).onTakeout(1L, 0, RpcListener.OP_TAKEOUT_FINISH, "77", "1"))
        drain()

        // invokeWithTakeout(77, account.finishTakeoutSession#1d2652ee with success set)
        assertEquals("2efda9ac" + "4d00000000000000" + "ee52261d" + "01000000", hex(serialize(connections().lastSent()!!.request)))

        connections().lastSent()!!.answer(TLRPC.TL_boolTrue(), null)
        drain()
        assertEquals(PluginWire.Value.Bool(true), PluginWire.decode(plugin.js.invokes.last().resultWire))
    }

    /** the session is the plugin's to name, but the call it wraps is still one the host checks */
    @Test
    fun a_wrapped_call_the_plugin_may_not_make_is_refused_before_it_is_sent() {
        val plugin = granted()
        val query = PluginWire.encodeJson(JSONObject().put("_", "users.getUsers").toString())
        val refusal = rpc(plugin).onTakeout(1L, 0, RpcListener.OP_TAKEOUT_INVOKE, "77", query)
        assertEquals("not-granted", (PluginWire.decode(assertNotNull(refusal)) as PluginWire.Value.PluginErr).code)
        drain()
        assertNull(connections().lastSent())
    }

    @Test
    fun takeout_ops_need_the_takeout_grant() {
        val plugin = startPlugin("no-takeout", "invokeRpc")
        val refusal = rpc(plugin).onTakeout(1L, 0, RpcListener.OP_TAKEOUT_INIT, "", initOptions())
        assertEquals("takeout", (PluginWire.decode(assertNotNull(refusal)) as PluginWire.Value.PluginErr).grant)
        drain()
        assertNull(connections().lastSent())
    }

    @Test
    fun a_session_id_that_is_not_a_number_is_refused() {
        val plugin = granted()
        val refusal = rpc(plugin).onTakeout(1L, 0, RpcListener.OP_TAKEOUT_FINISH, "not-an-id", "1")
        assertEquals("invalid-argument", (PluginWire.decode(assertNotNull(refusal)) as PluginWire.Value.PluginErr).code)
        drain()
        assertNull(connections().lastSent())
    }

    @Test
    fun raw_bytes_are_sent_verbatim_and_the_response_comes_back_whole() {
        val plugin = granted()
        val method = serialize(TLRPC.TL_users_getUsers())
        assertNull(rpc(plugin).onInvokeRaw(9L, 0, method))
        drain()

        val sent = connections().lastSent()!!
        assertEquals(hex(method), hex(serialize(sent.request)))

        // the response is parsed the way stock parses one: magic first, then the request's own
        // deserializer over the rest of the buffer
        val answered = serialize(TLRPC.TL_boolTrue())
        val buffer = SerializedData(answered)
        val response = sent.request.deserializeResponse(buffer, buffer.readInt32(true), true)

        sent.answer(response, null)
        drain()
        // bytes settle through their own entry, never as a wire string
        assertEquals(hex(answered), hex(plugin.js.invokeBytes.last().response))
    }

    @Test
    fun raw_needs_its_own_grant_and_refuses_a_takeover_method() {
        val ungranted = startPlugin("no-raw", "invokeRpc")
        assertEquals(
            "unsafe.invokeRaw",
            (PluginWire.decode(assertNotNull(rpc(ungranted).onInvokeRaw(1L, 0, ByteArray(4)))) as PluginWire.Value.PluginErr).grant,
        )

        val plugin = granted()
        // auth.exportLoginToken#b7e085fe, a takeover method the filter refuses by its constructor alone
        val takeover = byteArrayOf(0xfe.toByte(), 0x85.toByte(), 0xe0.toByte(), 0xb7.toByte())
        val refusal = rpc(plugin).onInvokeRaw(2L, 0, takeover)
        assertEquals("forbidden", (PluginWire.decode(assertNotNull(refusal)) as PluginWire.Value.PluginErr).code)
        drain()
        assertNull(connections().lastSent())
    }

    /** the whole point of the api: stock has no class for it, so nothing but the bytes can describe it */
    @Test
    fun a_constructor_this_build_has_never_heard_of_still_goes_out() {
        val plugin = granted()
        val method = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x07)
        assertNull(rpc(plugin).onInvokeRaw(1L, 0, method))
        drain()
        assertEquals(hex(method), hex(serialize(connections().lastSent()!!.request)))
    }

    @Test
    fun a_payload_too_short_to_name_a_constructor_is_refused() {
        val plugin = granted()
        val refusal = rpc(plugin).onInvokeRaw(1L, 0, byteArrayOf(1, 2))
        assertEquals("invalid-argument", (PluginWire.decode(assertNotNull(refusal)) as PluginWire.Value.PluginErr).code)
        drain()
        assertNull(connections().lastSent())
    }
}
