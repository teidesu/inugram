package desu.inugram.helpers.plugins.telegram

import desu.inugram.core.plugins.DispatchDeadline
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.PluginLog
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.SendsListener
import desu.inugram.helpers.plugins.SessionResource
import desu.inugram.helpers.plugins.ui.PluginUi
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.tgnet.TLRPC

/**
 * `interceptSendMessage`. The stages see a send one after another as json and each hands it back,
 * see `send_message.js`; [PluginCompose] runs them on what the user asked to send, before the app
 * acts on it, and [PluginRpc] on a media send once it is uploaded.
 *
 * Stages run on [EngineDispatch.scheduler].
 */
object PluginSends : SessionResource {
    private class Interceptor(
        val session: PluginSession,
        val callbackId: Int,
        val uploaded: Boolean,
        val text: Pattern?,
        val textIsSticky: Boolean,
        val hasText: Boolean?,
        val peers: Set<Long>,
        val peerTypes: Int,
        val media: Boolean?,
        val mediaKinds: Set<String>?,
        val forward: Boolean?,
    ) {
        fun applies(uploaded: Boolean, probe: Probe): Boolean =
            this.uploaded == uploaded && session.canDispatch() &&
                (hasText == null || hasText == !probe.text.isNullOrEmpty()) &&
                matchesPeer(probe) &&
                (media == null || media == probe.media) &&
                (mediaKinds == null || probe.kinds.any { it in mediaKinds }) &&
                (forward == null || forward == probe.forward) &&
                matchesText(probe.text)

        private fun matchesPeer(probe: Probe): Boolean {
            if (peers.isEmpty() && peerTypes == 0) return true
            if (probe.peer in peers) return true
            val controller = PeerSpecs.controllerFor(probe.account) ?: return false
            return peerTypes != 0 && PluginUi.canSelectPeer(controller.getUserOrChat(PeerSpecs.toSimpleDialogId(probe.peer)), peerTypes)
        }

        private fun matchesText(value: String?): Boolean {
            if (text == null) return true
            value ?: return false
            // android runs java.util.regex on icu natively: no deadline can reach it, and its backtracking
            // stack overflows as a RuntimeException
            return try {
                val matcher = text.matcher(value)
                if (textIsSticky) matcher.lookingAt() else matcher.find()
            } catch (e: RuntimeException) {
                session.log.w("sends", "send filter /${text.pattern()}/ failed, letting the message through to the interceptor", e)
                true
            } catch (e: StackOverflowError) {
                session.log.w("sends", "send filter /${text.pattern()}/ overflowed the stack, letting the message through to the interceptor")
                true
            }
        }
    }

    /** what a filter matches: [peer] is marked, [text] is null for a forward without a comment */
    internal class Probe(val account: Int, val peer: Long, val text: String?, val media: Boolean, val kinds: Set<String>, val forward: Boolean)

    /** a media item as a stage sees it, and which of the items the send started with it is, -1 for one a stage added */
    internal class Item(val json: JSONObject, val from: Int)

    internal sealed interface Outcome {
        /** [message] carries no media; [items] are its media */
        class Send(val message: JSONObject, val items: List<Item>) : Outcome
        object Dropped : Outcome
        class Failed(val reason: String) : Outcome
    }

    private class Pipeline(
        val account: Int,
        val uploaded: Boolean,
        var message: JSONObject,
        var items: List<Item>,
        val kinds: List<String?>,
        val done: (Outcome) -> Unit,
    ) {
        val deadline = DispatchDeadline(EngineDispatch.scheduler, BUDGET_MS) { expire(this) }
        var index = 0
        var dispatchId = 0L
        var stage: Interceptor? = null
    }

    private const val BUDGET_MS = 60_000L
    private val NESTED_QUANTIFIER = Regex("""(?<!\\)[+*}]\)+[+*{]""")

    @Volatile private var interceptors: List<Interceptor> = emptyList()

    // scheduler only
    private var nextDispatchId = 1L
    private val pending = HashMap<Long, Pipeline>()

    fun listenerFor(session: PluginSession): SendsListener {
        val onHost = EngineDispatch.createHostDispatcher { session.isCurrent() }
        return object : SendsListener {
            override fun onSendRegister(callbackId: Int, filterJson: String): String? = register(session, callbackId, filterJson)

            override fun onSendUnregister(callbackId: Int) =
                onHost { publish(interceptors.filter { it.session !== session || it.callbackId != callbackId }) }

            override fun onSendVerdict(dispatchId: Long, verdict: String) =
                EngineDispatch.scheduler.postRunnable { settle(dispatchId, verdict) }
        }
    }

    fun refreshOrder() {
        EngineDispatch.scheduler.postRunnable { publish(interceptors) }
    }

    /** a stopped plugin's stage is skipped, never failed: the user's send is not the plugin's to lose */
    override fun detach(session: PluginSession) {
        publish(interceptors.filter { it.session !== session })
        for ((dispatchId, pipeline) in pending.filterValues { it.stage?.session === session }) {
            pending.remove(dispatchId)
            session.engine.abandonSendDispatch(dispatchId, PluginRpc.ABANDONED_WIRE)
            advance(pipeline)
        }
    }

    internal val hasInterceptors: Boolean get() = interceptors.isNotEmpty()

    internal fun mayIntercept(uploaded: Boolean, probe: Probe): Boolean =
        interceptors.any { it.applies(uploaded, probe) }

    /**
     * Runs the stages over [message], whose `media` are [items] of [kinds], null where the json tells.
     * False when no stage would see it. [done] runs on the scheduler
     */
    internal fun run(
        account: Int,
        uploaded: Boolean,
        message: JSONObject,
        items: List<JSONObject>,
        kinds: List<String?>,
        done: (Outcome) -> Unit,
    ): Boolean {
        val started = items.mapIndexed { at, json -> Item(json, at) }
        if (!mayIntercept(uploaded, createProbe(account, message, started, kinds))) return false
        EngineDispatch.scheduler.postRunnable {
            val pipeline = Pipeline(account, uploaded, message, started, kinds, done)
            pipeline.deadline.resume()
            advance(pipeline)
        }
        return true
    }

    /** a forward sent without a comment has no text, so no text filter can match it */
    private fun createProbe(account: Int, message: JSONObject, items: List<Item>, kinds: List<String?>): Probe {
        val forward = !message.isNull("forward")
        val text = message.getJSONObject("text").getString("text").takeUnless { it.isEmpty() && forward }
        val read = readMediaKinds(items.map { it.json }, items.map { kinds.getOrNull(it.from) })
        return Probe(account, message.getLong("peer"), text, items.isNotEmpty(), read, forward)
    }

    internal fun readMediaKinds(items: List<JSONObject>, kinds: List<String?>): Set<String> =
        items.withIndex().mapNotNullTo(HashSet()) { (at, json) -> kinds.getOrNull(at) ?: readMediaKind(json) }

    /** a document named by id alone, like one a plugin added, has none */
    internal fun readMediaKind(item: JSONObject): String? = when (item.optString("_")) {
        "localMedia" -> item.getString("kind")
        "inputMediaUploadedPhoto", "inputMediaPhoto", "inputMediaPhotoExternal" -> "photo"
        "inputMediaGeoPoint", "inputMediaGeoLive" -> "location"
        "inputMediaVenue" -> "venue"
        "inputMediaContact" -> "contact"
        "inputMediaPoll" -> "poll"
        else -> null
    }

    /** `message.js`'s `mediaType` */
    internal fun readMediaKind(media: TLRPC.MessageMedia?): String? = when (media) {
        is TLRPC.TL_messageMediaPhoto -> "photo"
        is TLRPC.TL_messageMediaDocument -> readDocumentKind(media.document)
        is TLRPC.TL_messageMediaGeo, is TLRPC.TL_messageMediaGeoLive -> "location"
        is TLRPC.TL_messageMediaVenue -> "venue"
        is TLRPC.TL_messageMediaContact -> "contact"
        is TLRPC.TL_messageMediaPoll -> "poll"
        else -> null
    }

    /** `message.js`'s `documentMediaType` */
    internal fun readDocumentKind(document: TLRPC.Document?): String {
        var video = false
        var animated = false
        for (attribute in document?.attributes.orEmpty()) {
            when (attribute) {
                is TLRPC.TL_documentAttributeSticker, is TLRPC.TL_documentAttributeCustomEmoji -> return "sticker"
                is TLRPC.TL_documentAttributeAudio -> return if (attribute.voice) "voice" else "music"
                is TLRPC.TL_documentAttributeVideo -> if (attribute.round_message) return "roundVideo" else video = true
                is TLRPC.TL_documentAttributeAnimated -> animated = true
            }
        }
        return if (animated) "gif" else if (video) "video" else "document"
    }

    /** `common.d.ts` promises the user's drag order. Stable sort keeps one plugin's stages in registration order */
    private fun publish(updated: List<Interceptor>) {
        val order = PluginManager.orderIndex()
        interceptors = updated.sortedBy { order[it.session.plugin] ?: Int.MAX_VALUE }
    }

    private fun register(session: PluginSession, callbackId: Int, filterJson: String): String? {
        if (!session.permissions.has("interceptSendMessage")) return PluginWire.encodeNotGranted("interceptSendMessage")
        val filter = if (filterJson.isEmpty()) JSONObject() else JSONObject(filterJson)
        val regex = filter.optJSONObject("text")
        val flags = regex?.optString("flags").orEmpty()
        if (flags.any { it !in "dgimsuy" }) {
            return PluginWire.encodePluginError("invalid-argument", "unsupported regular expression flags '$flags'")
        }
        var patternFlags = 0
        if ('i' in flags) patternFlags = patternFlags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        if ('m' in flags) patternFlags = patternFlags or Pattern.MULTILINE
        if ('s' in flags) patternFlags = patternFlags or Pattern.DOTALL
        if ('u' in flags) patternFlags = patternFlags or Pattern.UNICODE_CHARACTER_CLASS
        val source = regex?.getString("source")
        if (source != null && NESTED_QUANTIFIER.containsMatchIn(source)) {
            session.log.w(
                "sends",
                "send filter /$source/ nests quantifiers and can backtrack catastrophically, freezing every send; rewrite it without a repeated group that ends in + or *",
            )
        }
        val pattern = try {
            source?.let { Pattern.compile(it, patternFlags) }
        } catch (e: PatternSyntaxException) {
            return PluginWire.encodePluginError("invalid-argument", "invalid text regular expression: ${e.description}")
        }
        val peers = filter.optJSONArray("peer")?.let { ids -> (0 until ids.length()).mapTo(HashSet()) { ids.getLong(it) } }.orEmpty()
        val peerTypes = filter.optJSONArray("peerType")?.let { types ->
            // the bits PluginUi.canSelectPeer reads
            (0 until types.length()).fold(0) { bits, at ->
                bits or when (types.getString(at)) {
                    "user" -> 1
                    "group" -> 2
                    else -> 4
                }
            }
        } ?: 0
        val media = filter.opt("media")
        val interceptor = Interceptor(
            session, callbackId, filter.optString("stage") == "uploaded", pattern, 'y' in flags,
            filter.opt("text") as? Boolean, peers, peerTypes,
            media as? Boolean, (media as? JSONArray)?.let { kinds -> (0 until kinds.length()).mapTo(HashSet()) { kinds.getString(it) } },
            if (filter.has("forward")) filter.getBoolean("forward") else null,
        )
        publish(interceptors + interceptor)
        return null
    }

    private fun advance(pipeline: Pipeline) {
        val stages = interceptors.filter { it.uploaded == pipeline.uploaded }
        val probe = createProbe(pipeline.account, pipeline.message, pipeline.items, pipeline.kinds)
        val next = stages.drop(pipeline.index).indexOfFirst { it.applies(pipeline.uploaded, probe) }
        if (next < 0) return finish(pipeline, Outcome.Send(pipeline.message, pipeline.items))
        val stage = stages[pipeline.index + next]
        pipeline.index += next + 1
        pipeline.stage = stage
        pipeline.dispatchId = nextDispatchId++
        pending[pipeline.dispatchId] = pipeline
        val message = JSONObject(pipeline.message.toString()).put("media", JSONArray(pipeline.items.map { it.json }))
        stage.session.engine.dispatchSend(stage.callbackId, pipeline.dispatchId, pipeline.account, message.toString())
    }

    private fun settle(dispatchId: Long, verdict: String) {
        val pipeline = pending.remove(dispatchId) ?: return
        val body = verdict.drop(1)
        when (verdict.firstOrNull()) {
            'D' -> finish(pipeline, Outcome.Dropped)
            'S' -> try {
                val message = JSONObject(body)
                val media = message.remove("media") as JSONArray
                pipeline.items = (0 until media.length()).map { at ->
                    val entry = media.getJSONObject(at)
                    val kept = entry.getInt("kept")
                    val json = entry.optJSONObject("tl") ?: entry.getJSONObject("local").put("_", "localMedia")
                    Item(json, if (kept < 0) -1 else pipeline.items[kept].from)
                }
                pipeline.message = message
                advance(pipeline)
            } catch (e: Exception) {
                finish(pipeline, Outcome.Failed("the message handed back did not read: ${e.message}"))
            }
            else -> finish(pipeline, Outcome.Failed(body))
        }
    }

    /** only the deepest stage is named: the message never reaches the next one */
    private fun expire(pipeline: Pipeline) {
        val dispatchId = pipeline.dispatchId
        if (pending.remove(dispatchId) !== pipeline) return
        val stage = pipeline.stage
        (stage?.session?.log ?: PluginLog.HOST).w("sends", "a send ran past its ${BUDGET_MS}ms budget and was not sent")
        stage?.session?.takeIf { it.isCurrent() }?.engine?.abandonSendDispatch(dispatchId, PluginRpc.TIMEOUT_WIRE)
        finish(pipeline, Outcome.Failed("the interceptors ran past their budget"))
    }

    private fun finish(pipeline: Pipeline, outcome: Outcome) {
        pipeline.deadline.cancel()
        pipeline.done(outcome)
    }
}
