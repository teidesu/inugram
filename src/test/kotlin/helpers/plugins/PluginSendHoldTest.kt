package desu.inugram.helpers.plugins

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.MessageObject
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.SendMessagesHelper.SendMessageParams
import org.telegram.tgnet.TLRPC

class PluginSendHoldTest {
    private val self = 100L
    private val alice = 222L
    private val drawn = ArrayList<String>()
    private var observer: NotificationCenter.NotificationCenterDelegate? = null

    @Before
    fun setUp() {
        resetBridge()
        TestApp.signInAs(0, user(self))
        TestApp.putUser(user(alice))
        watchDraws()
    }

    @After
    fun tearDown() = onUi {
        observer?.let { NotificationCenter.getInstance(0).removeObserver(it, NotificationCenter.didReceiveNewMessages) }
        observer = null
    }

    private fun watchDraws() = onUi {
        val delegate = NotificationCenter.NotificationCenterDelegate { _, _, args ->
            @Suppress("UNCHECKED_CAST")
            val messages = args.getOrNull(1) as? ArrayList<MessageObject> ?: return@NotificationCenterDelegate
            messages.mapTo(drawn) { it.messageOwner.message }
        }
        observer = delegate
        NotificationCenter.getInstance(0).addObserver(delegate, NotificationCenter.didReceiveNewMessages)
    }

    private fun compose(text: String) = onUi {
        SendMessagesHelper.getInstance(0).sendMessage(SendMessageParams.of(text, alice))
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        awaitValue(what) { condition().takeIf { it } }
    }

    private fun dropping(plugin: Plugin) {
        plugin.js.onDispatchRpc = { plugin.complete(it.dispatchId, "R-1000:MESSAGE_DROPPED_BY_PLUGIN") }
    }

    private fun filtered(source: String): String = """{"text":{"source":"$source","flags":""}}"""

    @Test
    fun a_send_a_middleware_drops_is_never_drawn() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage(filterJson = filtered("^\\\\.drop")))
        dropping(plugin)

        compose(".drop me")
        waitFor("the middleware was never asked") { plugin.js.dispatches.isNotEmpty() }
        Thread.sleep(400)
        settle()

        assertEquals(emptyList(), drawn, "a dropped send reached the screen")
        assertNull(connections().lastSent(), "a dropped send must not reach the network")
    }

    @Test
    fun a_send_the_chain_keeps_is_drawn_once_it_passes_through() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage(filterJson = filtered("^\\\\.keep")))
        plugin.js.onDispatchRpc = { plugin.next(it.dispatchId, it.requestWire) }

        compose(".keep me")
        waitFor("the send was never drawn") { drawn.contains(".keep me") }
        assertTrue(connections().lastSent()?.request is TLRPC.TL_messages_sendMessage)
    }

    @Test
    fun a_send_no_filter_could_claim_is_drawn_before_the_composer_returns() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage(filterJson = filtered("^\\\\.drop")))
        dropping(plugin)

        onUi {
            SendMessagesHelper.getInstance(0).sendMessage(SendMessageParams.of("ordinary", alice))
            assertEquals(listOf("ordinary"), drawn, "an unclaimable send waited on the grace window")
        }
    }

    @Test
    fun a_quick_verdict_is_held_regardless_of_callback_syntax() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())
        dropping(plugin)

        onUi {
            SendMessagesHelper.getInstance(0).sendMessage(SendMessageParams.of("ordinary", alice))
            assertTrue(drawn.isEmpty(), "the message was drawn before the verdict could arrive")
        }
    }

    @Test
    fun a_send_is_drawn_on_the_spot_when_nothing_intercepts_one() {
        onUi {
            SendMessagesHelper.getInstance(0).sendMessage(SendMessageParams.of("ordinary", alice))
            assertEquals(listOf("ordinary"), drawn, "a send waited on a grace window with no middleware to wait for")
        }
    }
}
