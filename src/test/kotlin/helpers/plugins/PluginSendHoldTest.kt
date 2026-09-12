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

/**
 * The grace window a send waits out before it is drawn, when a middleware could still drop it. What
 * is asserted here is what the user sees: a dropped send never reaches the screen at all, one the
 * chain keeps does, and a send no registered filter could claim is drawn before `sendMessage` has
 * even returned.
 */
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

    private fun user(id: Long) = TLRPC.TL_user().apply {
        this.id = id
        access_hash = id * 10
    }

    /** the notification the bubble is drawn off, so a message that flashed is one this recorded */
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

    /** the composer reconciles across its own storage thread, which the queue recorders do not stand in for */
    private fun waitFor(what: String, condition: () -> Boolean) {
        repeat(100) {
            settle()
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError(what)
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

    /**
     * the filter is read off the text alone, which the composer already has - so a send the
     * registered middleware could not claim pays nothing, not even a queue hop
     */
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

    /**
     * a middleware that answers out of a promise says so at registration, and a verdict nothing can
     * expect in time is not one to wait for
     */
    @Test
    fun a_deferred_middleware_holds_nothing_back() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage(filterJson = """{"deferred":true}"""))
        dropping(plugin)

        onUi {
            SendMessagesHelper.getInstance(0).sendMessage(SendMessageParams.of("ordinary", alice))
            assertEquals(listOf("ordinary"), drawn, "an async middleware was waited on")
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
