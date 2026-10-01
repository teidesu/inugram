package desu.inugram.helpers.plugins.telegram

import desu.inugram.core.plugins.DispatchDeadline
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.PluginLog
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.SendsListener
import desu.inugram.helpers.plugins.SessionResource
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import org.json.JSONArray
import org.json.JSONObject

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
    ) {
        fun applies(uploaded: Boolean, text: String?): Boolean =
            this.uploaded == uploaded && session.canDispatch() && matchesText(text)

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

    internal fun mayIntercept(uploaded: Boolean, text: String?): Boolean =
        interceptors.any { it.applies(uploaded, text) }

    /**
     * Runs the stages over [message], whose `media` are [items]. False when no stage would see it.
     * [done] runs on the scheduler
     */
    internal fun run(account: Int, uploaded: Boolean, message: JSONObject, items: List<JSONObject>, done: (Outcome) -> Unit): Boolean {
        if (!mayIntercept(uploaded, readFilteredText(message))) return false
        EngineDispatch.scheduler.postRunnable {
            val pipeline = Pipeline(account, uploaded, message, items.mapIndexed { at, json -> Item(json, at) }, done)
            pipeline.deadline.resume()
            advance(pipeline)
        }
        return true
    }

    /** a forward sent without a comment has no text, so no text filter can match it */
    private fun readFilteredText(message: JSONObject): String? =
        message.getJSONObject("text").getString("text").takeUnless { it.isEmpty() && !message.isNull("forward") }

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
        val interceptor = Interceptor(session, callbackId, filter.optString("stage") == "uploaded", pattern, 'y' in flags)
        publish(interceptors + interceptor)
        return null
    }

    private fun advance(pipeline: Pipeline) {
        val stages = interceptors.filter { it.uploaded == pipeline.uploaded }
        val text = readFilteredText(pipeline.message)
        val next = stages.drop(pipeline.index).indexOfFirst { it.applies(pipeline.uploaded, text) }
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
