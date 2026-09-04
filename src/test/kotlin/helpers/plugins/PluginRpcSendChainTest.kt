package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.telegram.PluginRpc
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.Utilities
import org.telegram.tgnet.RequestDelegate
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

/**
 * `interceptSendMessage` is a *narrowing* of the `interceptRpc` chain rather than a chain of its
 * own, which is what buys it the collapse, cancel handling and bypass lease for
 * free. What this pins is the part of that claim only the host can answer: which grant it is gated
 * on, which methods it lands in, and that the two forms interleave in plugin-list order.
 */
class PluginRpcSendChainTest {
    @Before
    fun setUp() = resetBridge()

    private fun send(request: TLObject, token: Int = 11, onDone: (TLObject?, TLRPC.TL_error?) -> Unit = { _, _ -> }): Boolean =
        PluginRpc.maybeIntercept(
            connections(0),
            request,
            RequestDelegate { response, error -> onDone(response, error) },
            null, null, null,
            0, 0, 0, false, token, 0,
        )

    @Test
    fun the_registration_is_gated_on_the_api_s_own_grant_not_on_the_four_methods() {
        val plugin = startPlugin("p", "interceptRpc(messages.sendMessage)")
        assertPluginError("not-granted", plugin.interceptSendMessage())

        val granted = startPlugin("q", "interceptSendMessage")
        assertNull(granted.interceptSendMessage())
    }

    /** and the other way round: holding the api does not buy the raw form over those methods */
    @Test
    fun interceptSendMessage_does_not_imply_interceptRpc_over_the_same_methods() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertPluginError("not-granted", plugin.interceptRpc("messages.sendMessage"))
    }

    @Test
    fun a_send_registration_lands_in_all_four_send_chains() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())
        plugin.js.onDispatchRpc = { plugin.next(it.dispatchId, it.requestWire) }

        for (request in listOf(
            TLRPC.TL_messages_sendMessage(),
            TLRPC.TL_messages_sendMedia(),
            TLRPC.TL_messages_sendMultiMedia(),
            TLRPC.TL_messages_editMessage(),
        )) {
            assertTrue(send(request), "${request.javaClass.simpleName} was not intercepted")
            drain()
        }
        assertEquals(4, plugin.js.dispatches.size)
    }

    @Test
    fun send_filters_skip_the_engine_until_the_request_matches() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage(filterJson = """{"text":{"source":"^\\.stats$","flags":"i"},"isEdit":false}"""))
        plugin.js.onDispatchRpc = { plugin.complete(it.dispatchId, "R-1000:MESSAGE_DROPPED_BY_PLUGIN") }

        val ordinary = TLRPC.TL_messages_sendMessage().apply { message = "hello" }
        assertEquals(false, send(ordinary), "a rejected filter must stay on the Java fast path")
        drain()
        assertEquals(0, plugin.js.dispatches.size)

        val edit = TLRPC.TL_messages_editMessage().apply { message = ".stats" }
        assertEquals(false, send(edit), "isEdit=false must reject edits before entering the engine")
        drain()
        assertEquals(0, plugin.js.dispatches.size)

        val command = TLRPC.TL_messages_sendMessage().apply { message = ".STATS" }
        assertTrue(send(command))
        drain()
        assertEquals(1, plugin.js.dispatches.size)
    }

    @Test
    fun a_send_interceptor_has_a_60_second_chain_deadline() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())
        plugin.js.onDispatchRpc = {}
        var completed = false

        assertTrue(send(TLRPC.TL_messages_sendMessage()) { _, _ -> completed = true })
        drain()
        TestQueues.advanceBy(59_999)

        assertEquals(false, completed)
        assertEquals(1, plugin.js.dispatches.size)

        TestQueues.advanceBy(1)
        assertEquals(true, completed)
    }

    @Test
    fun a_send_a_plugin_made_itself_never_re_enters_the_chain() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())
        var answered = false

        Utilities.globalQueue.postRunnable {
            PluginRpc.sendWithoutInterceptors(0, TLRPC.TL_messages_sendMessage(), 0) { _, _ -> answered = true }
        }
        drain()

        assertEquals(0, plugin.js.dispatches.size, "a middleware that sends would otherwise re-enter itself")
        val sent = assertNotNull(connections(0).lastSent())
        sent.answer(null, null, 0L)
        drain()
        assertTrue(answered)
    }

    /**
     * the two forms are one list, so a plugin holding both sees them in the order the user dragged
     * its plugins into - not in the order the two apis happened to register
     */
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

        assertTrue(send(TLRPC.TL_messages_sendMessage()))
        drain()
        assertEquals(listOf("a", "b"), order)

        setInstalledPlugins(listOf(wrapped, raw))
        PluginRpc.refreshChainOrder()
        drain()
        order.clear()
        assertTrue(send(TLRPC.TL_messages_sendMessage(), token = 12))
        drain()
        assertEquals(listOf("b", "a"), order)
    }

    /**
     * the whole point of building it on the request chain: a dropped send is a stage that settled
     * with an error, so the request never reaches the network and the app is told it failed
     */
    @Test
    fun a_stage_that_settles_with_an_error_fails_the_send_and_nothing_goes_out() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())
        plugin.js.onDispatchRpc = { plugin.complete(it.dispatchId, "R-1000:MESSAGE_DROPPED_BY_PLUGIN") }
        var error: TLRPC.TL_error? = null

        assertTrue(send(TLRPC.TL_messages_sendMessage()) { _, e -> error = e })
        drain()

        assertNull(connections(0).lastSent(), "a dropped send must not reach the network")
        assertEquals("MESSAGE_DROPPED_BY_PLUGIN", assertNotNull(error).text)
    }

    @Test
    fun a_secret_chat_send_is_not_one_of_the_four_and_never_reaches_a_middleware() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())

        val request = TLRPC.TL_messages_sendEncrypted()
        assertEquals(false, send(request), "an e2e send is not intercepted at all")
        drain()
        assertEquals(0, plugin.js.dispatches.size)
    }

    @Test
    fun stopping_a_plugin_takes_its_send_stages_out_of_every_chain() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())
        plugin.js.onDispatchRpc = { plugin.next(it.dispatchId, it.requestWire) }

        Utilities.globalQueue.postRunnable { detachPlugin(plugin) }
        drain()

        val request = TLRPC.TL_messages_sendMessage()
        assertEquals(false, send(request))
        drain()
        assertEquals(0, plugin.js.dispatches.size)
    }
}
