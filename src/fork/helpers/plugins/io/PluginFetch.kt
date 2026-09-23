package desu.inugram.helpers.plugins.io

import desu.inugram.helpers.plugins.SessionResource
import desu.inugram.core.plugins.OwnerRegistry
import desu.inugram.core.plugins.PluginRefusal
import desu.inugram.core.plugins.EgressPolicy
import desu.inugram.core.plugins.PluginPermissions
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.FetchListener
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.QuickJs
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

object PluginFetch : SessionResource {
    private const val MAX_REDIRECTS = 20
    const val MAX_BODY_BYTES = 32L * 1024 * 1024
    const val BODY_BUDGET_BYTES = 256L * 1024 * 1024

    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 60_000

    private const val BODIES_DIR = "fetch"

    private val transfers by lazy {
        Executors.newFixedThreadPool(4, ThreadFactory { r ->
            Thread(r, "inuPluginFetch").apply { isDaemon = true }
        })
    }

    private class InFlight(val requestId: Long, val flight: Flight)

    private val flights = OwnerRegistry<PluginSession, InFlight>()
    private val used = ConcurrentHashMap<String, AtomicLong>()
    private val nextBody = AtomicLong(1)

    class Flight {
        @Volatile var cancelled = false
        @Volatile var connection: HttpURLConnection? = null

        fun cancel() {
            cancelled = true
            // the worker is blocked in read()
            runCatching { connection?.disconnect() }
        }
    }

    fun listenerFor(session: PluginSession): FetchListener =
        object : FetchListener {
            override fun fetch(
                requestId: Long,
                url: String,
                method: String,
                redirect: String,
                headers: Array<String>,
                body: ByteArray?,
            ): String? {
                val spec = Spec.of(method, redirect, headers)
                val bodiesDir = bodiesDir(session.plugin.id)
                    ?: return PluginWire.encodePluginError("internal", "fetch: there is nowhere to put a response body")
                val flight = Flight()
                flights.add(session, InFlight(requestId, flight))
                transfers.execute {
                    val delivery = try {
                        exchange(session.permissions, session.plugin.id, url, spec, body, bodiesDir, flight)
                    } catch (e: Throwable) {
                        Delivery(PluginWire.encodePluginError("internal", "fetch: ${e.message ?: e.toString()}"), null)
                    }
                    flights.remove(session) { it.flight === flight }
                    EngineDispatch.scheduler.postRunnable { deliver(session, requestId, delivery, flight) }
                }
                return null
            }

            override fun abort(requestId: Long) {
                flights.remove(session) { it.requestId == requestId }?.flight?.cancel()
            }
        }

    class Delivery(val wire: String, private val body: Hop?) {
        fun drop() {
            body?.discard()
        }
    }

    fun deliver(session: PluginSession, requestId: Long, delivery: Delivery, flight: Flight) {
        if (!session.isCurrent() || flight.cancelled) delivery.drop()
        else session.engine.settle(QuickJs.SETTLE_FETCH, requestId, delivery.wire)
    }

    override fun detach(session: PluginSession) {
        for (inFlight in flights.take(session)) inFlight.flight.cancel()
    }

    fun wipe(installId: String) {
        // [PluginBlobs.wipe] already deletes the tree
        used.remove(installId)
        for (inFlight in flights.takeWhere { it.plugin.id == installId }) inFlight.flight.cancel()
    }

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
            fun of(method: String, redirect: String, pairs: Array<String>): Spec {
                val headers = LinkedHashMap<String, MutableList<String>>()
                for (at in 0 until pairs.size / 2) {
                    val value = pairs[at * 2 + 1]
                    // okhttp would put these on the wire as a second header line
                    if (value.any { it == '\r' || it == '\n' }) {
                        throw PluginRefusal(PluginWire.encodePluginError("internal", "fetch: a header value with a line break reached the transport"))
                    }
                    headers.getOrPut(pairs[at * 2]) { ArrayList() }.add(value)
                }
                return Spec(method, headers, redirect)
            }
        }
    }

    /** rust `url::parse_http_url` pre-flights the same; this side connects, so it is the authority */
    private fun refuse(code: String, message: String, grant: String? = null) =
        PluginWire.encodePluginError(code, message, grant = grant)

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

    /** method and body are dropped where every client drops them, so a `POST` body never reaches an unnamed host */
    fun runExchange(
        permissions: PluginPermissions,
        startUrl: String,
        spec: Spec,
        body: ByteArray?,
        transport: Transport,
        flight: Flight,
    ): Outcome {
        var url = startUrl
        var method = spec.method
        var payload = body
        var hops = 0
        while (true) {
            if (flight.cancelled) return aborted()
            EgressPolicy.screenHop(permissions, url)?.let { return Outcome.Refused(it) }
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
            runExchange(permissions, url, spec, body, transport, flight)
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

    class BodyTooBig(message: String, val usage: Long, val quota: Long) : Exception(message)

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
            // always a file, even for a 204, so a body is always a `Blob`
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

    /** refused while reading, so a server sending forever is stopped */
    fun drainTo(stream: InputStream?, file: File, budget: AtomicLong, flight: Flight): Long {
        if (stream == null) {
            file.writeBytes(ByteArray(0))
            return 0
        }
        var written = 0L
        // never derived from [written]: they diverge by the one refused chunk, which was never charged
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
                        // four of these run at once, so a read-only check would let each overshoot the same headroom
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
