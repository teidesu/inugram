package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.telegram.PluginRpc
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.telegram.tgnet.TLRPC

class PluginRpcSendChainTest {
    @Before
    fun setUp() = resetBridge()

    @Test
    fun interceptSendMessage_and_interceptRpc_over_the_send_methods_do_not_imply_each_other() {
        assertPluginError("not-granted", startPlugin("p", "interceptRpc(messages.sendMessage)").interceptSendMessage())
        assertPluginError("not-granted", startPlugin("q", "interceptSendMessage").interceptRpc("messages.sendMessage"))
    }

    @Test
    fun a_send_registration_lands_in_all_four_send_chains_and_never_in_a_secret_chat_s() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())
        plugin.js.onDispatchRpc = { plugin.next(it.dispatchId, it.requestWire) }

        for (request in listOf(
            TLRPC.TL_messages_sendMessage(),
            TLRPC.TL_messages_sendMedia(),
            TLRPC.TL_messages_sendMultiMedia(),
            TLRPC.TL_messages_editMessage(),
        )) {
            assertTrue(sendThroughPlugins(request), "${request.javaClass.simpleName} was not intercepted")
            drain()
        }
        assertEquals(false, sendThroughPlugins(TLRPC.TL_messages_sendEncrypted()))
        drain()
        assertEquals(4, plugin.js.dispatches.size)
    }

    @Test
    fun send_filters_skip_the_engine_until_the_request_matches() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage(filterJson = """{"text":{"source":"^\\.stats$","flags":"i"},"isEdit":false}"""))
        plugin.js.onDispatchRpc = { plugin.complete(it.dispatchId, "R-1000:MESSAGE_DROPPED_BY_PLUGIN") }

        val ordinary = TLRPC.TL_messages_sendMessage().apply { message = "hello" }
        assertEquals(false, sendThroughPlugins(ordinary), "a rejected filter must stay on the Java fast path")
        drain()
        assertEquals(0, plugin.js.dispatches.size)

        val edit = TLRPC.TL_messages_editMessage().apply { message = ".stats" }
        assertEquals(false, sendThroughPlugins(edit), "isEdit=false must reject edits before entering the engine")
        drain()
        assertEquals(0, plugin.js.dispatches.size)

        val command = TLRPC.TL_messages_sendMessage().apply { message = ".STATS" }
        assertTrue(sendThroughPlugins(command))
        drain()
        assertEquals(1, plugin.js.dispatches.size)
    }

    @Test
    fun a_send_interceptor_has_a_60_second_chain_deadline() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())
        plugin.js.onDispatchRpc = {}
        var completed = false

        assertTrue(sendThroughPlugins(TLRPC.TL_messages_sendMessage()) { _, _ -> completed = true })
        drain()
        // the deadline clock is real uptime plus the offset, so leave margin for real time spent draining
        TestQueues.advanceBy(55_000)

        assertEquals(false, completed)
        assertEquals(1, plugin.js.dispatches.size)

        TestQueues.advanceBy(5_001)
        assertEquals(true, completed)
    }

    @Test
    fun a_raw_interceptor_and_a_send_interceptor_interleave_in_plugin_list_order() {
        val raw = startPlugin("a", "interceptRpc(messages.sendMessage)")
        val wrapped = startPlugin("b", "interceptSendMessage")
        assertNull(raw.interceptRpc("messages.sendMessage"))
        assertNull(wrapped.interceptSendMessage())
        val order = ArrayList<String>()
        for (plugin in listOf(raw, wrapped)) {
            plugin.js.onDispatchRpc = {
                order.add(plugin.manifest.name)
                plugin.next(it.dispatchId, it.requestWire)
            }
        }

        assertTrue(sendThroughPlugins(TLRPC.TL_messages_sendMessage()))
        drain()
        assertEquals(listOf("a", "b"), order)

        setInstalledPlugins(listOf(wrapped, raw))
        PluginRpc.refreshChainOrder()
        drain()
        order.clear()
        assertTrue(sendThroughPlugins(TLRPC.TL_messages_sendMessage(), token = 12))
        drain()
        assertEquals(listOf("b", "a"), order)
    }

    @Test
    fun a_stage_that_settles_with_an_error_fails_the_send_and_nothing_goes_out() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())
        plugin.js.onDispatchRpc = { plugin.complete(it.dispatchId, "R-1000:MESSAGE_DROPPED_BY_PLUGIN") }
        var error: TLRPC.TL_error? = null

        assertTrue(sendThroughPlugins(TLRPC.TL_messages_sendMessage()) { _, e -> error = e })
        drain()

        assertNull(connections(0).lastSent(), "a dropped send must not reach the network")
        assertEquals("MESSAGE_DROPPED_BY_PLUGIN", assertNotNull(error).text)
    }

    @Test
    fun stopping_a_plugin_takes_its_send_stages_out_of_every_chain() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())
        plugin.js.onDispatchRpc = { plugin.next(it.dispatchId, it.requestWire) }

        EngineDispatch.scheduler.postRunnable { detachPlugin(plugin) }
        drain()

        val request = TLRPC.TL_messages_sendMessage()
        assertEquals(false, sendThroughPlugins(request))
        drain()
        assertEquals(0, plugin.js.dispatches.size)
    }
}
