package desu.inugram.helpers.plugins.io

import desu.inugram.core.plugins.PluginPermissions
import desu.inugram.core.plugins.ScopeMatch
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.FetchListener
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginDispatch
import desu.inugram.helpers.plugins.QuickJs
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import org.telegram.messenger.Utilities

/**
 * The transport behind the global `fetch` (rust: `fetch.rs`), and the only place the two egress
 * rules `common.d.ts` states can be enforced.
 *
 * **Every redirect hop is screened, not just the url the plugin passed**
 * (`instanceFollowRedirects` off, [runExchange] walking the chain itself): a client that follows
 * them for us checks the grant once, which turns any open redirect on an allowed host into a proxy
 * to everything else, and the response then looks like it came from the allowed host.
 *
 * **Addresses are screened after resolution, and *every* answer must be public.** A name is not an
 * address, and the resolver picks per connection. What remains is the rebinding window between this
 * resolution and the socket's own, which needs a pinned-address socket with `Host`/SNI set by hand
 * to close - documented rather than pretended away.
 *
 * Only a body the plugin is actually handed stays charged against [BODY_BUDGET_BYTES]: a counter
 * nothing decrements is one a remote server drives to the ceiling by answering every request with a
 * 302 and a big body.
 */
object PluginFetch {
    /** past this, a chain is a loop somebody else is running. Same number chromium uses. */
    private const val MAX_REDIRECTS = 20

    /** what one response body may be, matching rust `blob::BUILD_LIMIT_BYTES` */
    const val MAX_BODY_BYTES = 32L * 1024 * 1024

    /** a body file lives until the engine stops, so without a ceiling a plugin polling an endpoint fills the cache partition */
    const val BODY_BUDGET_BYTES = 256L * 1024 * 1024

    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 60_000

    private const val BODIES_DIR = "fetch"

    // the plugin's own `timeout`/`AbortSignal` is the engine's business; this only keeps a socket
    // that answers nothing at all from holding a worker forever
    private val transfers by lazy {
        Executors.newFixedThreadPool(4, ThreadFactory { r ->
            Thread(r, "inuPluginFetch").apply { isDaemon = true }
        })
    }

    private val flights = ConcurrentHashMap<String, Flight>()
    private val used = ConcurrentHashMap<String, AtomicLong>()

    /** four hops run at once on [transfers], and two reading the same clock would write one file */
    private val nextBody = AtomicLong(1)

    /**
     * [connection] only exists once a worker opened one, and everything before that (the queue hop,
     * the pool dispatch, the name resolution) is time an abort has to be *asked for* rather than
     * delivered - hence [cancelled] being read at the top of every hop and again once the name has
     * resolved, both in [runExchange] so a test can reach them.
     */
    class Flight {
        @Volatile var cancelled = false
        @Volatile var connection: HttpURLConnection? = null

        fun cancel() {
            cancelled = true
            // the worker is blocked in read(); this is what makes it return
            runCatching { connection?.disconnect() }
        }
    }

    fun listenerFor(plugin: Plugin, engine: QuickJs): FetchListener =
        object : FetchListener {
            override fun fetch(requestId: Long, url: String, specJson: String, body: ByteArray?): String? {
                val spec = try {
                    Spec.parse(specJson)
                } catch (e: Exception) {
                    return PluginWire.encodePluginError("invalid-argument", "fetch: ${e.message}")
                }
                val bodiesDir = bodiesDir(plugin.id)
                    ?: return PluginWire.encodePluginError("internal", "fetch: there is nowhere to put a response body")
                val key = flightKey(plugin.id, engine, requestId)
                val flight = Flight()
                flights[key] = flight
                val permissions = plugin.permissions
                transfers.execute {
                    val delivery = try {
                        exchange(permissions, plugin.id, url, spec, body, bodiesDir, flight)
                    } catch (e: Throwable) {
                        Delivery(PluginWire.encodePluginError("internal", "fetch: ${e.message ?: e.toString()}"), null)
                    }
                    flights.remove(key)
                    Utilities.globalQueue.postRunnable { deliver(plugin, engine, requestId, delivery, flight) }
                }
                return null
            }

            override fun abort(requestId: Long) {
                flights.remove(flightKey(plugin.id, engine, requestId))?.cancel()
            }
        }

    class Delivery(val wire: String, private val body: Hop?) {
        /** nobody took the body, so the file goes and the budget it holds comes back */
        fun drop() {
            body?.discard()
        }
    }

    /**
     * both ways an answer reaches nobody, and both must drop the body rather than leave it charged:
     * the plugin reloaded onto another engine whose request ids restart (identity, not just null),
     * or it aborted - which it does *after* settling its own promise.
     */
    fun deliver(plugin: Plugin, engine: QuickJs, requestId: Long, delivery: Delivery, flight: Flight) {
        if (!PluginDispatch.isLive(plugin, engine) || flight.cancelled) delivery.drop()
        else engine.fetchResult(requestId, delivery.wire)
    }

    /** [PluginBlobs.wipe] already deletes the tree; this is what gives the budget back */
    fun wipe(installId: String) {
        used.remove(installId)
        for ((key, flight) in flights) {
            if (key.startsWith("$installId:")) {
                flights.remove(key)
                flight.cancel()
            }
        }
    }

    /** a reload restarts request ids at 1, so two engines of one plugin can have a request #1 in the air, and the older finishing would take the newer one's entry off the map */
    private fun flightKey(installId: String, engine: QuickJs, requestId: Long) =
        "$installId:${System.identityHashCode(engine)}:$requestId"

    fun budgetFor(installId: String): AtomicLong = used.getOrPut(installId) { AtomicLong(0) }

    private fun bodiesDir(installId: String): File? {
        val root = PluginBlobs.dirFor(installId)
        if (root.isEmpty()) return null
        val dir = File(root, BODIES_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        return dir
    }

    class Spec(val method: String, val headers: Map<String, List<String>>, val redirect: String) {
        companion object {
            /** rfc7230's token, which is what a header name and a method are allowed to be */
            private val TOKEN = Regex("^[!#$%&'*+\\-.^_`|~0-9a-zA-Z]+$")

            /**
             * headers the transport owns: one of these set from a plugin either does nothing or
             * makes the request lie about its own framing. Android's `HttpURLConnection` is okhttp,
             * which has no restricted-name list of its own and supplies `Host` only when it is
             * absent, so a forged one does go on the wire.
             */
            private val RESERVED = setOf(
                "host", "content-length", "connection", "transfer-encoding", "upgrade", "keep-alive", "te", "trailer",
            )

            private val REDIRECT_MODES = setOf("follow", "manual", "error")

            /**
             * `fetch.js` checks all of this too, for the error message - but it is evaluated into
             * the plugin's own realm and hands the spec over as text, so its checks are advisory and
             * these are the ones that decide. Same reasoning as `api::json_stringify` not reading
             * `globalThis.JSON`: a refusal a plugin can reassign is not a refusal.
             */
            fun parse(json: String): Spec {
                val obj = JSONObject(json)
                val headers = LinkedHashMap<String, List<String>>()
                val raw = obj.optJSONObject("headers")
                if (raw != null) {
                    for (key in raw.keys()) {
                        require(TOKEN.matches(key)) { "'$key' is not a header name" }
                        val name = key.lowercase()
                        require(name !in RESERVED) { "the '$key' header belongs to the transport" }
                        require(name !in headers) { "the '$key' header is repeated" }
                        val values = raw.getJSONArray(key)
                        headers[name] = (0 until values.length()).map {
                            val value = values.getString(it)
                            // a newline in a value is a second header, and a request nobody wrote
                            require(value.none { c -> c < ' ' && c != '\t' || c == '\u007f' }) {
                                "the '$key' header has a control character in it"
                            }
                            value
                        }
                    }
                }
                val method = obj.optString("method", "GET")
                require(TOKEN.matches(method)) { "'$method' is not a method" }
                val redirect = obj.optString("redirect", "follow")
                require(redirect in REDIRECT_MODES) { "'$redirect' is not a redirect mode" }
                return Spec(method, headers, redirect)
            }
        }
    }

    /** kept close to rust `fetch::parse_target`, which pre-flights the same thing; this side is the authority, being the one that connects */
    fun hostOf(url: String): String? {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        // `http://allowed.com@127.0.0.1/` reads as one host and connects to another
        if (uri.rawUserInfo != null) return null
        val host = uri.host ?: return null
        return host.removeSurrounding("[", "]").trimEnd('.').lowercase().ifEmpty { null }
    }

    /**
     * every range a plugin has no business reaching from a grant reading "arbitrary http": loopback
     * and link-local (the device's own services), the private ones (the user's lan), and the
     * shared/benchmark/multicast ones.
     *
     * Fail-closed on an address length this does not recognise.
     */
    fun isBlockedAddress(raw: ByteArray): Boolean {
        when (raw.size) {
            4 -> {
                val a = raw[0].toInt() and 0xff
                val b = raw[1].toInt() and 0xff
                return when {
                    a == 0 -> true // "this network"
                    a == 10 -> true
                    a == 127 -> true // loopback
                    a == 100 && b in 64..127 -> true // carrier-grade nat
                    a == 169 && b == 254 -> true // link-local, and the metadata services on it
                    a == 172 && b in 16..31 -> true
                    a == 192 && b == 0 -> true // ietf protocol assignments, incl. 192.0.0.0/24
                    a == 192 && b == 168 -> true
                    a == 198 && b in 18..19 -> true // benchmarking
                    a >= 224 -> true // multicast, reserved, broadcast
                    else -> false
                }
            }
            16 -> {
                val v4 = embeddedIpv4(raw)
                if (v4 != null) return isBlockedAddress(v4)
                val first = raw[0].toInt() and 0xff
                if (first == 0xff) return true // multicast
                if (first and 0xfe == 0xfc) return true // unique local, fc00::/7
                if (first == 0xfe && (raw[1].toInt() and 0xc0) == 0x80) return true // link-local, fe80::/10
                // ::1 and :: - both are this device
                if (raw.take(15).all { it.toInt() == 0 }) return true
                return false
            }
            else -> return true
        }
    }

    /** the three v6 shapes carrying a v4 address - `::ffff:a.b.c.d`, `::a.b.c.d`, `64:ff9b::/96` - each of which reaches the v4 address it embeds */
    private fun embeddedIpv4(raw: ByteArray): ByteArray? {
        val tail = raw.copyOfRange(12, 16)
        val mapped = raw.take(10).all { it.toInt() == 0 } &&
            (raw[10].toInt() and 0xff) == 0xff && (raw[11].toInt() and 0xff) == 0xff
        if (mapped) return tail
        val nat64 = (raw[0].toInt() and 0xff) == 0x00 && (raw[1].toInt() and 0xff) == 0x64 &&
            (raw[2].toInt() and 0xff) == 0xff && (raw[3].toInt() and 0xff) == 0x9b &&
            raw.copyOfRange(4, 12).all { it.toInt() == 0 }
        if (nat64) return tail
        val compatible = raw.take(12).all { it.toInt() == 0 } && tail.any { it.toInt() != 0 }
        if (compatible) return tail
        return null
    }

    private fun refuse(code: String, message: String, grant: String? = null) =
        PluginWire.encodePluginError(code, message, grant = grant)

    /** [resolve] is a parameter so the rule can be tested against addresses rather than against whatever dns says today */
    fun screenHop(
        permissions: PluginPermissions,
        url: String,
        resolve: (String) -> List<ByteArray>,
    ): String? {
        val host = hostOf(url)
            ?: return refuse("invalid-argument", "fetch: '$url' is not an http(s) url this api will follow")
        if (!permissions.allows("fetch", host, ScopeMatch.DOMAIN)) {
            return refuse("not-granted", "missing grant: fetch($host)", grant = "fetch($host)")
        }
        val addresses = try {
            resolve(host)
        } catch (e: Exception) {
            return refuse("network", "fetch: '$host' does not resolve")
        }
        if (addresses.isEmpty()) return refuse("network", "fetch: '$host' does not resolve")
        for (address in addresses) {
            if (isBlockedAddress(address)) {
                return refuse(
                    "forbidden",
                    "fetch: '$host' resolves onto a loopback, link-local or private address, which this api does not reach",
                )
            }
        }
        return null
    }

    class Hop(
        val status: Int,
        val statusText: String,
        val headers: Map<String, List<String>>,
        val bodyFile: File?,
        val contentType: String,
        val bodyBytes: Long,
        private val budget: AtomicLong,
    ) {
        val location: String? get() = headers.entries.firstOrNull { it.key.equals("location", true) }?.value?.firstOrNull()

        private var discarded = false

        fun discard() {
            if (discarded) return
            discarded = true
            bodyFile?.delete()
            budget.addAndGet(-bodyBytes)
        }
    }

    fun interface Transport {
        fun exchange(url: String, method: String, headers: Map<String, List<String>>, body: ByteArray?): Hop
    }

    sealed class Outcome {
        class Answer(val hop: Hop, val finalUrl: String) : Outcome()
        class Refused(val wire: String) : Outcome()
    }

    /**
     * the method and body are dropped on the hops where every client drops them, so a `POST` body is
     * never replayed to a host the plugin did not name. [Flight.cancelled] is read before each hop:
     * an abort landing during hop 1 must not be followed by twenty more.
     */
    fun runExchange(
        permissions: PluginPermissions,
        startUrl: String,
        spec: Spec,
        body: ByteArray?,
        resolve: (String) -> List<ByteArray>,
        transport: Transport,
        flight: Flight,
    ): Outcome {
        var url = startUrl
        var method = spec.method
        var payload = body
        var hops = 0
        while (true) {
            if (flight.cancelled) return aborted()
            screenHop(permissions, url, resolve)?.let { return Outcome.Refused(it) }
            // again on the far side of the screen: resolving the name is the longest stretch of a
            // hop nothing else looks at, and there is no socket yet for `Flight.cancel` to reach
            if (flight.cancelled) return aborted()
            val hop = transport.exchange(url, method, spec.headers, payload)
            val location = hop.location
            if (hop.status !in 300..399 || location == null || spec.redirect == "manual") {
                return Outcome.Answer(hop, url)
            }
            hop.discard()
            if (spec.redirect == "error") {
                return Outcome.Refused(refuse("network", "fetch: the server redirected and redirect was 'error'"))
            }
            if (++hops > MAX_REDIRECTS) {
                return Outcome.Refused(refuse("network", "fetch: too many redirects"))
            }
            val next = try {
                URI(url).resolve(location).toString()
            } catch (e: Exception) {
                return Outcome.Refused(refuse("invalid-argument", "fetch: the server redirected to '$location'"))
            }
            if (hop.status == 303 || (hop.status in 301..302 && method != "GET" && method != "HEAD")) {
                method = "GET"
                payload = null
            }
            url = next
        }
    }

    private fun aborted(): Outcome.Refused = Outcome.Refused(refuse("aborted", "fetch: the request was aborted"))

    private fun exchange(
        permissions: PluginPermissions,
        installId: String,
        url: String,
        spec: Spec,
        body: ByteArray?,
        bodiesDir: File,
        flight: Flight,
    ): Delivery {
        val budget = budgetFor(installId)
        val transport = Transport { hopUrl, method, headers, payload ->
            send(hopUrl, method, headers, payload, bodiesDir, budget, flight)
        }
        val outcome = try {
            runExchange(permissions, url, spec, body, ::resolveAddresses, transport, flight)
        } catch (e: BodyTooBig) {
            return Delivery(
                PluginWire.encodePluginError(
                    "quota-exceeded",
                    e.message ?: "fetch: the response is too big",
                    usage = e.usage,
                    quota = e.quota,
                ),
                null,
            )
        } catch (e: Exception) {
            if (flight.cancelled) {
                return Delivery(PluginWire.encodePluginError("aborted", "fetch: the request was aborted"), null)
            }
            return Delivery(PluginWire.encodePluginError("network", "fetch: ${e.message ?: e.toString()}"), null)
        }
        return when (outcome) {
            is Outcome.Refused -> Delivery(outcome.wire, null)
            is Outcome.Answer -> Delivery(describe(outcome), outcome.hop)
        }
    }

    private fun describe(answer: Outcome.Answer): String {
        val hop = answer.hop
        val headers = JSONObject()
        for ((name, values) in hop.headers) {
            headers.put(name, org.json.JSONArray(values))
        }
        val json = JSONObject()
            .put("status", hop.status)
            .put("statusText", hop.statusText)
            .put("url", answer.finalUrl)
            .put("headers", headers)
        hop.bodyFile?.let {
            json.put("body", JSONObject().put("path", it.absolutePath).put("type", hop.contentType))
        }
        return PluginWire.encodeJson(json.toString())
    }

    private fun resolveAddresses(host: String): List<ByteArray> =
        InetAddress.getAllByName(host).map { it.address }

    class BodyTooBig(message: String, val usage: Long, val quota: Long) : Exception(message)

    /** split off [send], which opens a real socket: the one line that matters here is `instanceFollowRedirects`, the whole reason [runExchange] exists */
    fun prepareConnection(connection: HttpURLConnection, method: String, headers: Map<String, List<String>>) {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = method
        for ((name, values) in headers) {
            for (value in values) connection.addRequestProperty(name, value)
        }
    }

    private fun send(
        url: String,
        method: String,
        headers: Map<String, List<String>>,
        body: ByteArray?,
        bodiesDir: File,
        budget: AtomicLong,
        flight: Flight,
    ): Hop {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        flight.connection = connection
        try {
            prepareConnection(connection, method, headers)
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val headerFields = LinkedHashMap<String, List<String>>()
            for ((name, values) in connection.headerFields) {
                // the status line comes back under a null key
                if (name == null) continue
                headerFields[name.lowercase()] = values
            }
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            // always a file, even for a 204: a body that exists as an empty `Blob` is one shape
            // fewer for a plugin to branch on than a `null` body
            val file = File(bodiesDir, "b${nextBody.getAndIncrement()}-$status")
            return Hop(
                status = status,
                statusText = connection.responseMessage ?: "",
                headers = headerFields,
                bodyFile = file,
                contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty(),
                bodyBytes = drainTo(stream, file, budget, flight),
                budget = budget,
            )
        } finally {
            flight.connection = null
            connection.disconnect()
        }
    }

    /** refused past the ceilings *while* reading rather than after, so a server announcing nothing and sending forever is stopped rather than measured */
    fun drainTo(stream: InputStream?, file: File, budget: AtomicLong, flight: Flight): Long {
        if (stream == null) {
            file.writeBytes(ByteArray(0))
            return 0
        }
        var written = 0L
        // what this transfer has actually added to [budget], which is what a failure gives back.
        // never derived from [written]: the two diverge for exactly one chunk, the one a ceiling
        // refuses, and refunding that chunk would credit the plugin bytes it was never charged
        var charged = 0L
        try {
            file.outputStream().use { out ->
                stream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (flight.cancelled) throw InterruptedException("aborted")
                        val read = input.read(buffer)
                        if (read <= 0) break
                        written += read
                        if (written > MAX_BODY_BYTES) {
                            throw BodyTooBig(
                                "fetch: a response body is capped at $MAX_BODY_BYTES bytes",
                                written,
                                MAX_BODY_BYTES,
                            )
                        }
                        // charged as it is read rather than measured against a read-only view of the
                        // counter: four of these run at once on the pool, and a check that only reads
                        // lets every one of them pass the same headroom and overshoot by its own size
                        val held = budget.addAndGet(read.toLong())
                        charged += read
                        if (held > BODY_BUDGET_BYTES) {
                            throw BodyTooBig(
                                "fetch: this plugin is already holding $BODY_BUDGET_BYTES bytes of fetched content",
                                held,
                                BODY_BUDGET_BYTES,
                            )
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: Throwable) {
            budget.addAndGet(-charged)
            file.delete()
            throw e
        }
        return written
    }
}
