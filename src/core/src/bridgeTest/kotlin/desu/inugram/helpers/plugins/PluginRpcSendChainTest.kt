package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.tg.PluginRpc
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
 * own, which is what buys it the budget, the collapse, the cancel handling and the bypass lease for
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
    fun `the registration is gated on the api's own grant, not on the four methods`() {
        val plugin = startPlugin("p", "interceptRpc(messages.sendMessage)")
        assertPluginError("not-granted", plugin.interceptSendMessage())

        val granted = startPlugin("q", "interceptSendMessage")
        assertNull(granted.interceptSendMessage())
    }

    /** and the other way round: holding the api does not buy the raw form over those methods */
    @Test
    fun `interceptSendMessage does not imply interceptRpc over the same methods`() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertPluginError("not-granted", plugin.interceptRpc("messages.sendMessage"))
    }

    @Test
    fun `a send registration lands in all four send chains`() {
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
    fun `a send a plugin made itself never re-enters the chain`() {
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
    fun `a raw interceptor and a send interceptor interleave in plugin-list order`() {
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

        PluginManager.installed = listOf(wrapped, raw)
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
    fun `a stage that settles with an error fails the send and nothing goes out`() {
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
    fun `a secret chat send is not one of the four and never reaches a middleware`() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())

        val request = TLRPC.TL_messages_sendEncrypted()
        assertEquals(false, send(request), "an e2e send is not intercepted at all")
        drain()
        assertEquals(0, plugin.js.dispatches.size)
    }

    @Test
    fun `stopping a plugin takes its send stages out of every chain`() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())
        plugin.js.onDispatchRpc = { plugin.next(it.dispatchId, it.requestWire) }

        Utilities.globalQueue.postRunnable { PluginRpc.detach(plugin) }
        drain()

        val request = TLRPC.TL_messages_sendMessage()
        assertEquals(false, send(request))
        drain()
        assertEquals(0, plugin.js.dispatches.size)
    }
}
