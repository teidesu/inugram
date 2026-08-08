package desu.inugram.helpers.plugins

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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The two egress rules, which only exist here: the engine checks the first url's grant and nothing
 * else, so a redirect that is screened once and an address that is checked as a literal are both
 * failures nothing else in this codebase can see.
 */
class PluginFetchTest {
    @Before
    fun setUp() = resetBridge()

    private fun grants(vararg tokens: String) = PluginPermissions.parse(tokens.toList())

    private fun v4(a: Int, b: Int, c: Int, d: Int) =
        byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    /** `1234:5678::` style, written as the 16 bytes the resolver hands over */
    private fun v6(vararg words: Int): ByteArray {
        val out = ByteArray(16)
        for (i in words.indices) {
            out[i * 2] = (words[i] shr 8).toByte()
            out[i * 2 + 1] = words[i].toByte()
        }
        return out
    }

    private fun answers(vararg addresses: ByteArray): (String) -> List<ByteArray> = { addresses.toList() }

    private val public4 = v4(93, 184, 216, 34)

    private fun codeOf(wire: String?): String? {
        val decoded = PluginWire.decode(wire ?: return null)
        return (decoded as PluginWire.Value.PluginErr).code
    }

    @Test
    fun every_private_loopback_and_link_local_v4_range_is_refused() {
        for (address in listOf(
            v4(127, 0, 0, 1),
            v4(127, 255, 255, 254),
            v4(0, 0, 0, 0),
            v4(10, 0, 0, 5),
            v4(172, 16, 0, 1),
            v4(172, 31, 255, 255),
            v4(192, 168, 1, 1),
            v4(169, 254, 169, 254),
            v4(100, 64, 0, 1),
            v4(192, 0, 0, 1),
            v4(198, 18, 0, 1),
            v4(224, 0, 0, 1),
            v4(255, 255, 255, 255),
        )) {
            assertTrue(PluginFetch.isBlockedAddress(address), address.joinToString("."))
        }
    }

    @Test
    fun an_ordinary_public_address_is_not_refused() {
        for (address in listOf(public4, v4(8, 8, 8, 8), v4(1, 1, 1, 1), v4(172, 32, 0, 1), v4(100, 63, 0, 1))) {
            assertFalse(PluginFetch.isBlockedAddress(address), address.joinToString("."))
        }
    }

    @Test
    fun the_v6_ranges_are_refused_too() {
        assertTrue(PluginFetch.isBlockedAddress(v6(0, 0, 0, 0, 0, 0, 0, 1)), "::1")
        assertTrue(PluginFetch.isBlockedAddress(v6(0, 0, 0, 0, 0, 0, 0, 0)), "::")
        assertTrue(PluginFetch.isBlockedAddress(v6(0xfd00, 0, 0, 0, 0, 0, 0, 1)), "unique local")
        assertTrue(PluginFetch.isBlockedAddress(v6(0xfe80, 0, 0, 0, 0, 0, 0, 1)), "link-local")
        assertTrue(PluginFetch.isBlockedAddress(v6(0xff02, 0, 0, 0, 0, 0, 0, 1)), "multicast")
        assertFalse(PluginFetch.isBlockedAddress(v6(0x2606, 0x4700, 0, 0, 0, 0, 0, 1)), "a public v6 address")
    }

    /** all three shapes reach the v4 address they carry, so all three are checked as one */
    @Test
    fun a_v4_address_wrapped_in_a_v6_one_is_unwrapped_before_it_is_judged() {
        val mapped = v6(0, 0, 0, 0, 0, 0xffff, 0x7f00, 0x0001)
        assertTrue(PluginFetch.isBlockedAddress(mapped), "::ffff:127.0.0.1")
        val mappedPublic = v6(0, 0, 0, 0, 0, 0xffff, 0x5db8, 0xd822)
        assertFalse(PluginFetch.isBlockedAddress(mappedPublic), "::ffff:93.184.216.34")

        val nat64 = v6(0x0064, 0xff9b, 0, 0, 0, 0, 0xa9fe, 0xa9fe)
        assertTrue(PluginFetch.isBlockedAddress(nat64), "64:ff9b::169.254.169.254")

        val compatible = v6(0, 0, 0, 0, 0, 0, 0x0a00, 0x0001)
        assertTrue(PluginFetch.isBlockedAddress(compatible), "::10.0.0.1")
    }

    @Test
    fun an_address_of_a_shape_this_does_not_know_is_refused_rather_than_allowed() {
        assertTrue(PluginFetch.isBlockedAddress(ByteArray(0)))
        assertTrue(PluginFetch.isBlockedAddress(ByteArray(6)))
    }

    @Test
    fun the_host_is_the_one_the_request_connects_to_never_the_one_it_reads_as() {
        assertEquals("example.com", PluginFetch.hostOf("https://example.com/x"))
        assertEquals("example.com", PluginFetch.hostOf("https://EXAMPLE.com.:8443/x?q=1"))
        assertEquals("::1", PluginFetch.hostOf("http://[::1]:8080/x"))
        assertNull(PluginFetch.hostOf("https://allowed.com@127.0.0.1/x"), "userinfo hides the real host")
        assertNull(PluginFetch.hostOf("file:///etc/hosts"))
        assertNull(PluginFetch.hostOf("content://media/external/1"))
        assertNull(PluginFetch.hostOf("ftp://example.com/x"))
        assertNull(PluginFetch.hostOf("not a url"))
    }

    @Test
    fun a_host_the_grant_does_not_cover_is_refused_before_it_is_resolved() {
        var resolved = false
        val wire = PluginFetch.screenHop(grants("fetch(example.com)"), "https://evil.com/x") {
            resolved = true
            listOf(public4)
        }
        assertEquals("not-granted", codeOf(wire))
        assertFalse(resolved, "a refused host must not even be looked up")
    }

    @Test
    fun a_granted_host_that_resolves_into_a_private_range_is_refused() {
        val wire = PluginFetch.screenHop(grants("fetch"), "https://localtest.me/x", answers(v4(127, 0, 0, 1)))
        assertEquals("forbidden", codeOf(wire))
    }

    /**
     * the resolver picks per connection, so a name with one private answer among its public ones is
     * not a name to connect to: taking "the first address" would make the refusal a coin flip
     */
    @Test
    fun one_private_answer_among_several_refuses_the_whole_name() {
        val wire = PluginFetch.screenHop(grants("fetch"), "https://mixed.example/x", answers(public4, v4(10, 0, 0, 1)))
        assertEquals("forbidden", codeOf(wire))
    }

    @Test
    fun a_granted_public_host_passes() {
        assertNull(PluginFetch.screenHop(grants("fetch(example.com)"), "https://api.example.com/x", answers(public4)))
    }

    @Test
    fun a_name_that_does_not_resolve_fails_rather_than_being_sent() {
        val wire = PluginFetch.screenHop(grants("fetch"), "https://nx.example/x") { emptyList() }
        assertEquals("network", codeOf(wire))
        val threw = PluginFetch.screenHop(grants("fetch"), "https://nx.example/x") { throw java.net.UnknownHostException() }
        assertEquals("network", codeOf(threw))
    }

    /**
     * `fetch.js` states these refusals too, but it is evaluated into the plugin's own realm and the
     * spec reaches this side as text, so this is the check that decides. Android's
     * `HttpURLConnection` is okhttp, which has no restricted-name list of its own: a forged `Host`
     * or `Transfer-Encoding` goes on the wire.
     */
    @Test
    fun a_spec_naming_a_header_the_transport_owns_is_refused() {
        for (name in listOf(
            "host", "Host", "content-length", "connection", "transfer-encoding", "upgrade", "keep-alive", "te",
            "trailer",
        )) {
            assertFailsWith<IllegalArgumentException>(name) {
                PluginFetch.Spec.parse("""{"method":"GET","headers":{"$name":["x"]},"redirect":"follow"}""")
            }
        }
    }

    @Test
    fun a_spec_whose_header_name_value_method_or_redirect_mode_is_malformed_is_refused() {
        for (json in listOf(
            """{"headers":{"x y":["a"]}}""",
            """{"headers":{"x-one:":["a"]}}""",
            """{"headers":{"":["a"]}}""",
            """{"headers":{"x-one":["a\r\nx-two: b"]}}""",
            """{"headers":{"x-one":["a"],"X-One":["b"]}}""",
            """{"method":"GET /x HTTP/1.1"}""",
            """{"redirect":"whatever"}""",
        )) {
            assertFailsWith<IllegalArgumentException>(json) { PluginFetch.Spec.parse(json) }
        }
    }

    @Test
    fun an_ordinary_spec_parses_lowercasing_names_and_keeping_repeats() {
        val spec = PluginFetch.Spec.parse(
            """{"method":"POST","headers":{"X-One":["a"],"x-many":["b","c"]},"redirect":"manual"}""",
        )
        assertEquals("POST", spec.method)
        assertEquals("manual", spec.redirect)
        assertEquals(mapOf("x-one" to listOf("a"), "x-many" to listOf("b", "c")), spec.headers)
    }

    private class Recorder(private val script: Map<String, PluginFetch.Hop>) : PluginFetch.Transport {

        val urls = ArrayList<String>()

        /** what the transport does while it is serving a hop, e.g. an abort landing mid-chain */
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
        resolve: (String) -> List<ByteArray> = answersFor(),
        flight: PluginFetch.Flight = PluginFetch.Flight(),
    ) = PluginFetch.runExchange(permissions, url, spec(redirect, method), body, resolve, transport, flight)

    private fun answersFor(vararg private: String): (String) -> List<ByteArray> = { host ->
        if (host in private) listOf(v4(127, 0, 0, 1)) else listOf(public4)
    }

    /**
     * the open-proxy shape: an allowed host redirects somewhere the grant never named. A client
     * that follows redirects itself checks the first url only, and the response then looks like it
     * came from the host that was allowed.
     */
    @Test
    fun a_redirect_off_the_granted_domain_fails_the_request_and_is_never_sent() {
        val transport = Recorder(mapOf("https://example.com/open" to hop(302, "https://evil.com/steal")))
        val outcome = exchange(grants("fetch(example.com)"), "https://example.com/open", transport)

        assertEquals("not-granted", codeOf((outcome as PluginFetch.Outcome.Refused).wire))
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
        val screened = ArrayList<String>()
        val outcome = PluginFetch.runExchange(
            grants("fetch(example.com)"),
            "https://example.com/a",
            spec(),
            null,
            { host -> screened.add(host); listOf(public4) },
            transport,
            PluginFetch.Flight(),
        )

        val answer = outcome as PluginFetch.Outcome.Answer
        assertEquals(200, answer.hop.status)
        assertEquals("https://one.example.com/c", answer.finalUrl, "the final url is the last hop's")
        assertEquals(listOf("example.com", "one.example.com", "one.example.com"), screened)
        assertEquals(3, transport.urls.size)
    }

    /**
     * the address is re-resolved per hop for the same reason the grant is re-checked: a host inside
     * the granted domain can still point at the device itself
     */
    @Test
    fun a_hop_whose_host_resolves_into_a_private_range_is_refused_mid_chain() {
        val transport = Recorder(mapOf("https://example.com/a" to hop(302, "https://internal.example.com/b")))
        val outcome = exchange(
            grants("fetch(example.com)"),
            "https://example.com/a",
            transport,
            resolve = answersFor("internal.example.com"),
        )

        assertEquals("forbidden", codeOf((outcome as PluginFetch.Outcome.Refused).wire))
        assertEquals(listOf("GET https://example.com/a"), transport.urls, "the second hop never went out")
    }

    @Test
    fun manual_hands_the_redirect_back_and_error_refuses_it() {
        val script = mapOf("https://example.com/a" to hop(302, "https://example.com/b"))
        val manual = exchange(grants("fetch"), "https://example.com/a", Recorder(script), redirect = "manual")
        assertEquals(302, (manual as PluginFetch.Outcome.Answer).hop.status)

        val refused = exchange(grants("fetch"), "https://example.com/a", Recorder(script), redirect = "error")
        assertEquals("network", codeOf((refused as PluginFetch.Outcome.Refused).wire))
    }

    @Test
    fun a_chain_that_never_ends_is_stopped_after_the_number_of_hops_the_contract_states() {
        val transport = Recorder(mapOf("https://example.com/loop" to hop(302, "https://example.com/loop")))
        val outcome = exchange(grants("fetch"), "https://example.com/loop", transport)

        assertEquals("network", codeOf((outcome as PluginFetch.Outcome.Refused).wire))
        // the first request is not a hop, so the chain is the stated number of them plus it
        assertEquals(statedNumber(contract(), "longer than {} hops") + 1, transport.urls.size.toLong())
    }

    /**
     * what every http client does, and here it also means a body is never replayed to a host the
     * plugin did not name
     */
    @Test
    fun a_see_other_turns_the_request_into_a_bodyless_GET() {
        val transport = Recorder(mapOf("https://example.com/post" to hop(303, "https://example.com/done")))
        exchange(
            grants("fetch"),
            "https://example.com/post",
            transport,
            method = "POST",
            body = byteArrayOf(1, 2, 3),
        )

        assertEquals(
            listOf("POST https://example.com/post +body", "GET https://example.com/done"),
            transport.urls,
        )
    }

    @Test
    fun a_temporary_redirect_keeps_the_method_and_the_body() {
        val transport = Recorder(mapOf("https://example.com/post" to hop(307, "https://example.com/done")))
        exchange(
            grants("fetch"),
            "https://example.com/post",
            transport,
            method = "POST",
            body = byteArrayOf(1, 2, 3),
        )

        assertEquals(
            listOf("POST https://example.com/post +body", "POST https://example.com/done +body"),
            transport.urls,
        )
    }

    /**
     * the shape the scheme check is the only thing standing in front of: a hop that leaves http
     * entirely. `URI.resolve` hands back the absolute `file:` url, and if [PluginFetch.hostOf] ever
     * grew a fallback for a scheme it does not know, a granted host redirecting to
     * `file:///data/data/org.telegram.messenger/shared_prefs/` would be handed to the plugin as a
     * `Blob` with nothing noticing.
     */
    @Test
    fun a_redirect_off_http_entirely_is_refused_and_never_followed() {
        for (location in listOf("file:///etc/hosts", "content://media/external/x", "jar:file:///etc/hosts!/x")) {
            val transport = Recorder(mapOf("https://example.com/a" to hop(302, location)))
            val outcome = exchange(grants("fetch"), "https://example.com/a", transport)

            val refused = outcome as? PluginFetch.Outcome.Refused
                ?: error("'$location' was followed rather than refused")
            assertEquals("invalid-argument", codeOf(refused.wire), location)
            assertEquals(listOf("GET https://example.com/a"), transport.urls, "'$location' must not go out")
        }
    }

    /**
     * `FetchHost::abort` says the request is stopped, and the window it is asked for in is the whole
     * of the queue hop, the pool dispatch and the name resolution - none of which the socket
     * `Flight.cancel` disconnects exists for yet.
     */
    @Test
    fun an_abort_before_the_first_hop_stops_the_request_without_sending_it() {
        val flight = PluginFetch.Flight()
        flight.cancel()
        val transport = Recorder(emptyMap())
        val outcome = exchange(grants("fetch"), "https://example.com/a", transport, flight = flight)

        assertEquals("aborted", codeOf((outcome as PluginFetch.Outcome.Refused).wire))
        assertTrue(transport.urls.isEmpty(), "a cancelled request must not reach the transport")
    }

    /**
     * the same window one step later: resolving the name is where a hop spends its time, so the
     * abort the plugin asked for during it has to be seen before the socket rather than after.
     */
    @Test
    fun an_abort_that_lands_while_the_name_resolves_stops_the_request_without_sending_it() {
        val flight = PluginFetch.Flight()
        val transport = Recorder(emptyMap())
        val outcome = exchange(
            grants("fetch"),
            "https://example.com/a",
            transport,
            resolve = { flight.cancel(); listOf(public4) },
            flight = flight,
        )

        assertEquals("aborted", codeOf((outcome as PluginFetch.Outcome.Refused).wire))
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

        assertEquals("aborted", codeOf((outcome as PluginFetch.Outcome.Refused).wire))
        assertEquals(listOf("GET https://example.com/a"), transport.urls, "the second hop never went out")
        assertEquals(100L, budget.get(), "the abandoned hop's body is not left charged")
    }

    /** a hop's body is content nobody asked for, and it is a file on the user's device */
    @Test
    fun the_body_of_a_redirect_that_was_followed_is_deleted_and_its_bytes_come_back() {
        val budget = AtomicLong(0)
        val intermediate = bodyHop(302, "https://example.com/b", 500, budget)
        val transport = Recorder(
            mapOf(
                "https://example.com/a" to intermediate,
                "https://example.com/b" to bodyHop(200, null, 100, budget),
            ),
        )
        assertEquals(600L, budget.get(), "both hops were read before the chain got to choose")

        val outcome = exchange(grants("fetch"), "https://example.com/a", transport)

        assertEquals(200, (outcome as PluginFetch.Outcome.Answer).hop.status)
        assertFalse(intermediate.bodyFile!!.exists())
        assertEquals(100L, budget.get(), "only the body the plugin is handed stays charged")
    }

    /**
     * the shape that makes this a remote decision: a granted server answering every request with a
     * 302 and a body drives the counter to its ceiling in a handful of chains, and nothing else
     * ever decrements it
     */
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

    /** the drop path in `attach`: after a reload the answer belongs to an engine that is gone */
    @Test
    fun an_answer_no_engine_is_waiting_for_is_dropped_and_its_bytes_come_back() {
        val budget = AtomicLong(0)
        val kept = bodyHop(200, null, 900, budget)
        val plugin = startPlugin("fetch-reload", "fetch")
        val stale = plugin.js
        plugin.engine = RecordingQuickJs()

        PluginFetch.deliver(plugin, stale, 7, PluginFetch.Delivery("J{}", kept), PluginFetch.Flight())

        assertTrue(stale.httpResults.isEmpty(), "a stale engine must not be settled")
        assertTrue(plugin.js.httpResults.isEmpty(), "and neither must the new one, whose ids restart")
        assertFalse(kept.bodyFile!!.exists())
        assertEquals(0L, budget.get())
    }

    @Test
    fun an_answer_the_engine_took_keeps_its_body_and_its_charge() {
        val budget = AtomicLong(0)
        val kept = bodyHop(200, null, 900, budget)
        val plugin = startPlugin("fetch-live", "fetch")

        PluginFetch.deliver(plugin, plugin.js, 7, PluginFetch.Delivery("J{}", kept), PluginFetch.Flight())

        assertEquals(listOf(7L), plugin.js.httpResults.map { it.requestId })
        assertTrue(kept.bodyFile!!.exists(), "the blob the plugin is handed is over this file")
        assertEquals(900L, budget.get())
    }

    /**
     * an abort that lands once the body is already on disk: the engine settled its own promise
     * before it told the host to stop, so it would throw the answer away, and a body nothing can
     * ever be a `Blob` over must not stay charged
     */
    @Test
    fun an_answer_for_a_request_the_plugin_aborted_is_dropped_rather_than_settled() {
        val budget = AtomicLong(0)
        val kept = bodyHop(200, null, 900, budget)
        val plugin = startPlugin("fetch-aborted", "fetch")
        val flight = PluginFetch.Flight()
        flight.cancel()

        PluginFetch.deliver(plugin, plugin.js, 7, PluginFetch.Delivery("J{}", kept), flight)

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

    /**
     * serves [limit] bytes of nothing in particular, counting what it handed over: a real body of
     * this size would be a server that announces no length and sends forever, and the point of the
     * ceiling is that it stops one *while* reading rather than measuring it afterwards.
     */
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

    private fun drainInto(stream: InputStream, budget: AtomicLong): Pair<File, Long> {
        val file = File.createTempFile("inu-drain", ".bin").apply { deleteOnExit() }
        return file to PluginFetch.drainTo(stream, file, budget, PluginFetch.Flight())
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
        val (file, written) = drainInto(EndlessStream(1000), budget)

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

    /**
     * every other assertion about these two is written in terms of the constant, so raising either
     * to its maximum leaves the suite green. What a plugin can read is `common.d.ts`, so that is
     * what they are held to.
     */
    @Test
    fun the_two_body_ceilings_are_the_numbers_the_contract_states() {
        val mb = 1024L * 1024L
        assertEquals(statedNumber(contract(), "{} MB in each direction") * mb, PluginFetch.MAX_BODY_BYTES)
        assertEquals(statedNumber(contract(), "hold at most {} MB of fetched content") * mb, PluginFetch.BODY_BUDGET_BYTES)
    }

    /**
     * the one line of [PluginFetch.send] the `Transport` double cannot reach, and the whole of the
     * per-hop screening: with the jdk following redirects itself, a granted `example.com` answering
     * `302 http://169.254.169.254/latest/meta-data/` is screened once for `example.com` and the
     * metadata service's response comes back looking like it came from the host that was allowed.
     */
    @Test
    fun a_connection_this_api_opens_never_follows_a_redirect_on_its_own() {
        val connection = URI("http://example.invalid/x").toURL().openConnection() as HttpURLConnection
        assertTrue(connection.instanceFollowRedirects, "the jdk default this has to undo")

        PluginFetch.prepareConnection(connection, "POST", mapOf("x-inu" to listOf("1")))

        assertFalse(connection.instanceFollowRedirects, "a hop the redirect loop never sees is a hop nothing screens")
        assertEquals("POST", connection.requestMethod)
        assertEquals("1", connection.getRequestProperty("x-inu"))
    }

    private fun hop(status: Int, location: String? = null, body: File? = null) = okHop(status, location, body)

    /** a hop the way [PluginFetch.drainTo] leaves one: a real file, charged against [budget] */
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
