package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginRefusal
import desu.inugram.core.plugins.PluginPermissions
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.io.PluginFetch
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

class PluginFetchTest {
    @Before
    fun setUp() = resetBridge()

    private fun grants(vararg tokens: String) = PluginPermissions.parse(tokens.toList())

    private fun readErrorCode(wire: String?): String? {
        val decoded = PluginWire.decode(wire ?: return null)
        return (decoded as PluginWire.Value.PluginErr).code
    }

    @Test
    fun a_spec_groups_repeated_names_and_refuses_a_line_break_that_got_past_the_engine() {
        val spec = PluginFetch.Spec.of("POST", "manual", arrayOf("x-one", "a", "x-many", "b", "x-many", "c"))
        assertEquals("POST", spec.method)
        assertEquals("manual", spec.redirect)
        assertEquals(mapOf("x-one" to listOf("a"), "x-many" to listOf("b", "c")), spec.headers)
        assertFailsWith<PluginRefusal> { PluginFetch.Spec.of("GET", "follow", arrayOf("x-one", "a\r\nhost: b")) }
    }

    private class Recorder(private val script: Map<String, PluginFetch.Hop>) : PluginFetch.Transport {

        val urls = ArrayList<String>()

        var whileServing: ((String) -> Unit)? = null

        override fun exchange(
            url: String,
            method: String,
            headers: Map<String, List<String>>,
            body: ByteArray?,
        ): PluginFetch.Hop {
            urls.add("$method $url" + if (body == null) "" else " +body")
            whileServing?.invoke(url)
            return script[url] ?: okHop()
        }
    }

    private fun spec(redirect: String = "follow", method: String = "GET") =
        PluginFetch.Spec(method, emptyMap(), redirect)

    private fun exchange(
        permissions: PluginPermissions,
        url: String,
        transport: Recorder,
        redirect: String = "follow",
        method: String = "GET",
        body: ByteArray? = null,
        flight: PluginFetch.Flight = PluginFetch.Flight(),
    ) = PluginFetch.runExchange(permissions, url, spec(redirect, method), body, transport, flight)

    @Test
    fun a_redirect_off_the_granted_domain_fails_the_request_and_is_never_sent() {
        val transport = Recorder(mapOf("https://example.com/open" to hop(302, "https://evil.com/steal")))
        val outcome = exchange(grants("fetch(example.com)"), "https://example.com/open", transport)

        assertEquals("not-granted", readErrorCode((outcome as PluginFetch.Outcome.Refused).wire))
        assertEquals(listOf("GET https://example.com/open"), transport.urls)
    }

    @Test
    fun every_hop_of_a_chain_that_stays_in_scope_is_screened_and_followed() {
        val transport = Recorder(
            mapOf(
                "https://example.com/a" to hop(302, "https://one.example.com/b"),
                "https://one.example.com/b" to hop(301, "/c"),
                "https://one.example.com/c" to hop(200),
            ),
        )
        val outcome = exchange(grants("fetch(example.com)"), "https://example.com/a", transport)

        val answer = outcome as PluginFetch.Outcome.Answer
        assertEquals(200, answer.hop.status)
        assertEquals("https://one.example.com/c", answer.finalUrl, "the final url is the last hop's")
        assertEquals(
            listOf("GET https://example.com/a", "GET https://one.example.com/b", "GET https://one.example.com/c"),
            transport.urls,
        )
    }

    @Test
    fun manual_hands_the_redirect_back_and_error_refuses_it() {
        val script = mapOf("https://example.com/a" to hop(302, "https://example.com/b"))
        val manual = exchange(grants("fetch"), "https://example.com/a", Recorder(script), redirect = "manual")
        assertEquals(302, (manual as PluginFetch.Outcome.Answer).hop.status)

        val refused = exchange(grants("fetch"), "https://example.com/a", Recorder(script), redirect = "error")
        assertEquals("network", readErrorCode((refused as PluginFetch.Outcome.Refused).wire))
    }

    @Test
    fun a_chain_that_never_ends_is_stopped_after_twenty_hops() {
        val transport = Recorder(mapOf("https://example.com/loop" to hop(302, "https://example.com/loop")))
        val outcome = exchange(grants("fetch"), "https://example.com/loop", transport)

        assertEquals("network", readErrorCode((outcome as PluginFetch.Outcome.Refused).wire))
        assertEquals(21, transport.urls.size)
    }

    @Test
    fun a_see_other_drops_the_body_and_a_temporary_redirect_keeps_it() {
        for ((status, second) in listOf(303 to "GET https://example.com/done", 307 to "POST https://example.com/done +body")) {
            val transport = Recorder(mapOf("https://example.com/post" to hop(status, "https://example.com/done")))
            exchange(grants("fetch"), "https://example.com/post", transport, method = "POST", body = byteArrayOf(1, 2, 3))
            assertEquals(listOf("POST https://example.com/post +body", second), transport.urls, "$status")
        }
    }

    @Test
    fun a_redirect_off_http_entirely_is_refused_and_never_followed() {
        for (location in listOf("file:///etc/hosts", "content://media/external/x", "jar:file:///etc/hosts!/x")) {
            val transport = Recorder(mapOf("https://example.com/a" to hop(302, location)))
            val outcome = exchange(grants("fetch"), "https://example.com/a", transport)

            val refused = outcome as? PluginFetch.Outcome.Refused
                ?: error("'$location' was followed rather than refused")
            assertEquals("invalid-argument", readErrorCode(refused.wire), location)
            assertEquals(listOf("GET https://example.com/a"), transport.urls, "'$location' must not go out")
        }
    }

    @Test
    fun an_abort_before_the_first_hop_stops_the_request_without_sending_it() {
        val flight = PluginFetch.Flight()
        flight.cancel()
        val transport = Recorder(emptyMap())
        val outcome = exchange(grants("fetch"), "https://example.com/a", transport, flight = flight)

        assertEquals("aborted", readErrorCode((outcome as PluginFetch.Outcome.Refused).wire))
        assertTrue(transport.urls.isEmpty(), "a cancelled request must not reach the transport")
    }

    @Test
    fun an_abort_mid_chain_stops_the_redirects_rather_than_following_twenty_more() {
        val budget = AtomicLong(0)
        val flight = PluginFetch.Flight()
        val transport = Recorder(
            mapOf(
                "https://example.com/a" to bodyHop(302, "https://example.com/b", 500, budget),
                "https://example.com/b" to bodyHop(200, null, 100, budget),
            ),
        )
        transport.whileServing = { url -> if (url.endsWith("/a")) flight.cancel() }
        val outcome = exchange(grants("fetch"), "https://example.com/a", transport, flight = flight)

        assertEquals("aborted", readErrorCode((outcome as PluginFetch.Outcome.Refused).wire))
        assertEquals(listOf("GET https://example.com/a"), transport.urls, "the second hop never went out")
        assertEquals(100L, budget.get(), "the abandoned hop's body is not left charged")
    }

    @Test
    fun a_chain_of_redirects_that_all_carry_bodies_leaves_nothing_behind() {
        val budget = AtomicLong(0)
        val script = HashMap<String, PluginFetch.Hop>()
        for (i in 0 until 10) {
            script["https://example.com/$i"] = bodyHop(302, "https://example.com/${i + 1}", 1000, budget)
        }
        script["https://example.com/10"] = bodyHop(200, null, 7, budget)
        val transport = Recorder(script)

        val outcome = exchange(grants("fetch"), "https://example.com/0", transport)

        assertEquals(200, (outcome as PluginFetch.Outcome.Answer).hop.status)
        assertEquals(7L, budget.get())
        assertTrue((0 until 10).none { script.getValue("https://example.com/$it").bodyFile!!.exists() })
    }

    @Test
    fun discarding_the_same_hop_twice_does_not_credit_it_twice() {
        val budget = AtomicLong(0)
        val hop = bodyHop(200, null, 400, budget)

        hop.discard()
        assertEquals(0L, budget.get())
        hop.discard()
        assertEquals(0L, budget.get(), "a second discard is not a second refund")
    }

    @Test
    fun an_answer_no_engine_is_waiting_for_is_dropped_and_its_bytes_come_back() {
        val budget = AtomicLong(0)
        val kept = bodyHop(200, null, 900, budget)
        val plugin = startPlugin("fetch-reload", "fetch")
        val stale = plugin.session!!
        plugin.session = PluginSession(plugin, RecordingQuickJs())

        PluginFetch.deliver(stale, 7, PluginFetch.Delivery("J{}", kept), PluginFetch.Flight())

        assertTrue((stale.engine as RecordingQuickJs).httpResults.isEmpty(), "a stale engine must not be settled")
        assertTrue(plugin.js.httpResults.isEmpty(), "and neither must the new one, whose ids restart")
        assertFalse(kept.bodyFile!!.exists())
        assertEquals(0L, budget.get())
    }

    @Test
    fun an_answer_the_engine_took_keeps_its_body_and_its_charge() {
        val budget = AtomicLong(0)
        val kept = bodyHop(200, null, 900, budget)
        val plugin = startPlugin("fetch-live", "fetch")

        PluginFetch.deliver(plugin.session!!, 7, PluginFetch.Delivery("J{}", kept), PluginFetch.Flight())

        assertEquals(listOf(7L), plugin.js.httpResults.map { it.requestId })
        assertTrue(kept.bodyFile!!.exists(), "the blob the plugin is handed is over this file")
        assertEquals(900L, budget.get())
    }

    /** the engine settles an aborted promise itself before telling the host */
    @Test
    fun an_answer_for_a_request_the_plugin_aborted_is_dropped_rather_than_settled() {
        val budget = AtomicLong(0)
        val kept = bodyHop(200, null, 900, budget)
        val plugin = startPlugin("fetch-aborted", "fetch")
        val flight = PluginFetch.Flight()
        flight.cancel()

        PluginFetch.deliver(plugin.session!!, 7, PluginFetch.Delivery("J{}", kept), flight)

        assertTrue(plugin.js.httpResults.isEmpty())
        assertFalse(kept.bodyFile!!.exists())
        assertEquals(0L, budget.get())
    }

    @Test
    fun each_install_holds_its_own_budget_and_wiping_one_drops_it() {
        val one = PluginFetch.budgetFor("a".repeat(32))
        one.set(1234)
        assertEquals(0L, PluginFetch.budgetFor("b".repeat(32)).get(), "a budget is not shared")
        assertEquals(1234L, PluginFetch.budgetFor("a".repeat(32)).get(), "and the same id names the same one")

        PluginFetch.wipe("a".repeat(32))
        assertEquals(0L, PluginFetch.budgetFor("a".repeat(32)).get())
    }

    private class EndlessStream(private val limit: Long) : InputStream() {
        var served = 0L
            private set

        override fun read(): Int {
            if (served >= limit) return -1
            served++
            return 0
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= limit) return -1
            val n = minOf(len.toLong(), limit - served).toInt()
            served += n
            return n
        }
    }

    @Test
    fun a_body_past_the_per_response_ceiling_is_refused_while_it_is_being_read() {
        val budget = AtomicLong(0)
        val stream = EndlessStream(PluginFetch.MAX_BODY_BYTES * 2)
        val file = File.createTempFile("inu-drain", ".bin").apply { deleteOnExit() }

        val tooBig = assertFailsWith<PluginFetch.BodyTooBig> {
            PluginFetch.drainTo(stream, file, budget, PluginFetch.Flight())
        }

        assertEquals(PluginFetch.MAX_BODY_BYTES, tooBig.quota)
        assertTrue(tooBig.usage > PluginFetch.MAX_BODY_BYTES, "${tooBig.usage}")
        assertTrue(
            stream.served <= PluginFetch.MAX_BODY_BYTES + 64 * 1024,
            "it read ${stream.served} bytes, so it measured the body instead of stopping it",
        )
        assertFalse(file.exists(), "a refused body leaves no file")
        assertEquals(0L, budget.get(), "and charges nothing")
    }

    @Test
    fun a_body_past_what_this_plugin_already_holds_is_refused_against_the_budget_s_own_number() {
        val budget = AtomicLong(PluginFetch.BODY_BUDGET_BYTES - 10)
        val stream = EndlessStream(100)
        val file = File.createTempFile("inu-drain", ".bin").apply { deleteOnExit() }

        val tooBig = assertFailsWith<PluginFetch.BodyTooBig> {
            PluginFetch.drainTo(stream, file, budget, PluginFetch.Flight())
        }

        assertEquals(PluginFetch.BODY_BUDGET_BYTES, tooBig.quota)
        assertFalse(file.exists())
        assertEquals(PluginFetch.BODY_BUDGET_BYTES - 10, budget.get(), "a refusal must not charge")
    }

    @Test
    fun a_body_inside_both_ceilings_is_charged_exactly_what_it_wrote() {
        val budget = AtomicLong(0)
        val file = File.createTempFile("inu-drain", ".bin").apply { deleteOnExit() }
        val written = PluginFetch.drainTo(EndlessStream(1000), file, budget, PluginFetch.Flight())

        assertEquals(1000L, written)
        assertEquals(1000L, budget.get())
        assertEquals(1000L, file.length())
    }

    @Test
    fun a_body_that_was_cancelled_mid_read_leaves_nothing_charged() {
        val budget = AtomicLong(0)
        val flight = PluginFetch.Flight()
        flight.cancel()
        val file = File.createTempFile("inu-drain", ".bin").apply { deleteOnExit() }

        assertFailsWith<InterruptedException> {
            PluginFetch.drainTo(EndlessStream(1000), file, budget, flight)
        }
        assertFalse(file.exists())
        assertEquals(0L, budget.get())
    }

    /** the jdk follows redirects by default, which would skip per-hop screening */
    @Test
    fun a_connection_this_api_opens_never_follows_a_redirect_on_its_own() {
        val connection = URI("http://example.invalid/x").toURL().openConnection() as HttpURLConnection
        assertTrue(connection.instanceFollowRedirects)

        PluginFetch.prepareConnection(connection, "POST", mapOf("x-inu" to listOf("1")))

        assertFalse(connection.instanceFollowRedirects, "a hop the redirect loop never sees is a hop nothing screens")
        assertEquals("POST", connection.requestMethod)
        assertEquals("1", connection.getRequestProperty("x-inu"))
    }

    private fun hop(status: Int, location: String? = null, body: File? = null) = okHop(status, location, body)

    private fun bodyHop(status: Int, location: String?, bytes: Int, budget: AtomicLong): PluginFetch.Hop {
        val file = File.createTempFile("inu-hop", ".bin").apply { deleteOnExit() }
        file.writeBytes(ByteArray(bytes))
        budget.addAndGet(bytes.toLong())
        return okHop(status, location, file, bytes.toLong(), budget)
    }
}

private fun okHop(
    status: Int = 200,
    location: String? = null,
    body: File? = null,
    bodyBytes: Long = 0,
    budget: AtomicLong = AtomicLong(0),
) = PluginFetch.Hop(
    status = status,
    statusText = "",
    headers = if (location == null) emptyMap() else mapOf("location" to listOf(location)),
    bodyFile = body,
    contentType = "text/plain",
    bodyBytes = bodyBytes,
    budget = budget,
)
