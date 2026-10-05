package desu.inugram.helpers.plugins.telegram

import desu.inugram.InuConfig
import desu.inugram.core.plugins.DispatchDeadline
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.PluginLog
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.SessionResource
import desu.inugram.helpers.plugins.TranslationListener
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlJson
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.tgnet.TLRPC

/** `registerTranslationProvider`. Translations run on [EngineDispatch.scheduler] */
object PluginTranslation : SessionResource {
    class Provider(val session: PluginSession, val token: Int, val id: String, val name: String) {
        /** what the user's choice is stored as: it outlives reloads, unlike [token] */
        val key: String get() = "${session.plugin.id}:$id"
    }

    private class Pending(val session: PluginSession, val done: (ArrayList<TLRPC.TL_textWithEntities>?) -> Unit) {
        lateinit var deadline: DispatchDeadline
    }

    private const val BUDGET_MS = 30_000L

    @Volatile var providers: List<Provider> = emptyList()
        private set

    // scheduler only
    private var nextDispatchId = 1L
    private val pending = HashMap<Long, Pending>()

    fun listenerFor(session: PluginSession): TranslationListener {
        val onHost = EngineDispatch.createHostDispatcher { session.isCurrent() }
        return object : TranslationListener {
            override fun translationRegister(token: Int, id: String, name: String): String? {
                providers = providers.filter { it.session !== session || it.id != id } + Provider(session, token, id, name)
                return null
            }

            override fun translationUnregister(token: Int) =
                onHost { providers = providers.filter { it.session !== session || it.token != token } }

            override fun translationResult(dispatchId: Long, wire: String) =
                EngineDispatch.scheduler.postRunnable { settle(session, dispatchId, wire) }
        }
    }

    /** a removed plugin's provider gives the choice back to Telegram */
    fun retainInstalls(live: Set<String>) {
        val key = InuConfig.TRANSLATION_PROVIDER.value
        if (key.isNotEmpty() && key.substringBefore(':') !in live) InuConfig.TRANSLATION_PROVIDER.value = ""
    }

    fun findProvider(key: String): Provider? = providers.firstOrNull { it.key == key && it.session.canDispatch() }

    override fun detach(session: PluginSession) {
        providers = providers.filter { it.session !== session }
        for ((dispatchId, request) in pending.filterValues { it.session === session }) {
            pending.remove(dispatchId)
            request.deadline.cancel()
            request.done(null)
        }
    }

    /**
     * scheduler only. [done] runs exactly once on the scheduler, with null for a failure the plugin's log
     * explains, unless [cancel]led first. It may run before this returns. Returns the id [cancel] takes
     */
    fun translate(
        key: String,
        texts: List<TLRPC.TL_textWithEntities>,
        from: List<String?>,
        toLang: String,
        tone: String?,
        done: (ArrayList<TLRPC.TL_textWithEntities>?) -> Unit,
    ): Long {
        val provider = findProvider(key)
        if (provider == null) {
            PluginLog.HOST.w("translation", "the translation provider $key is not running")
            done(null)
            return 0
        }
        val policy = TlFilter.policyFor(provider.session.permissions)
        val request = JSONObject()
            .put("texts", JSONArray(texts.map { text ->
                JSONObject()
                    .put("text", text.text.orEmpty())
                    .put("entities", JSONArray(text.entities.orEmpty().map { TlJson.toJson(it, policy) }))
            }))
            .put("from", JSONArray(from.map { it ?: JSONObject.NULL }))
            .put("to", toLang)
        if (tone != null) request.put("tone", tone)
        val dispatchId = nextDispatchId++
        val entry = Pending(provider.session, done)
        entry.deadline = DispatchDeadline(EngineDispatch.scheduler, BUDGET_MS) { expire(dispatchId) }
        pending[dispatchId] = entry
        entry.deadline.resume()
        provider.session.engine.dispatchTranslation(provider.token, dispatchId, request.toString())
        return dispatchId
    }

    /** scheduler only */
    fun cancel(dispatchId: Long) {
        val entry = pending.remove(dispatchId) ?: return
        entry.deadline.cancel()
        entry.session.engine.abandonTranslation(dispatchId, false)
    }

    private fun expire(dispatchId: Long) {
        val entry = pending.remove(dispatchId) ?: return
        entry.session.log.w("translation", "a translation ran past its ${BUDGET_MS}ms budget")
        entry.session.engine.abandonTranslation(dispatchId, true)
        entry.done(null)
    }

    private fun settle(session: PluginSession, dispatchId: Long, wire: String) {
        val entry = pending.remove(dispatchId) ?: return
        entry.deadline.cancel()
        if (wire.firstOrNull() != 'S') return entry.done(null)
        val texts = try {
            readTexts(JSONArray(wire.substring(1)))
        } catch (e: Exception) {
            session.log.e("translation", "unreadable translation", e)
            return entry.done(null)
        }
        entry.done(texts)
    }

    private fun readTexts(json: JSONArray): ArrayList<TLRPC.TL_textWithEntities> {
        val out = ArrayList<TLRPC.TL_textWithEntities>(json.length())
        for (index in 0 until json.length()) {
            val item = json.getJSONObject(index)
            out += TLRPC.TL_textWithEntities().apply {
                text = item.getString("text")
                entities = PluginCompose.readEntities(item.optJSONArray("entities"))
            }
        }
        return out
    }
}
