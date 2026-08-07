package desu.inugram.helpers.plugins.tg

import android.util.SparseArray
import desu.inugram.core.plugins.ApiFilter
import desu.inugram.core.plugins.DeserializeGuards
import desu.inugram.core.plugins.ScopeMatch
import desu.inugram.core.plugins.TlCtorIds
import desu.inugram.core.plugins.TlFlags
import desu.inugram.core.plugins.TlNames
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.DeserializeListener
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginDispatch
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlHandles
import desu.inugram.helpers.plugins.tl.TlJson
import java.lang.reflect.Field
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

/**
 * `inu.interceptDeserialize`, declarative tier (rust: `deserialize.rs`). A rule crosses once, at
 * registration, and is evaluated here alone: no plugin JS runs per object.
 *
 * **[apply] is called from stock's `TLObject.TLdeserialize`, once per TL object the app parses** -
 * every rpc response, every update, every row read back out of the local database - so its steady
 * state is one volatile read.
 *
 * **A rewritten object is written back to sqlite**, which is what the refusals here are about:
 * stock re-serializes its live objects into `users`/`chats`/`messages_v2`/`dialogs`, so a rule
 * reaches disk on the first save after it fires and a row the server never re-sends keeps it for
 * good. Hence [DeserializeGuards]. Three more, each the deserialize-side half of a rule holding
 * elsewhere: secret chats are stripped by constructor id rather than name (`TL_message_secret` is a
 * `TL_message` and rides inside `idsOf("message")`), a `when` may only name a field the plugin
 * could read, and the takeover surface is not a rule target.
 */
object PluginDeserialize {
    /** written on globalQueue under [lock] by publishing a fresh snapshot, read from every thread that deserializes */
    @JvmField
    @Volatile
    var rules: Compiled? = null

    @JvmField
    @Volatile
    var middleware: CompiledMiddleware? = null

    /** stock's own gate: one volatile read is what the app pays for this api when nobody uses it */
    @JvmField
    @Volatile
    var hot: Boolean = false

    /** the app's network and storage threads block here, so past it the object is delivered as parsed */
    const val MIDDLEWARE_BUDGET_MS = 250L

    internal class Term(val name: String, val type: Class<*>, val value: Any?)

    internal class Rule(val whenTerms: Array<Term>, val setTerms: Array<Term>)

    class Compiled internal constructor(private val byCtorId: SparseArray<Array<Rule>>) {
        internal fun rulesFor(ctorId: Int): Array<Rule>? = byCtorId.get(ctorId)
    }

    private class Registration(val rules: Map<Int, List<Rule>>)

    internal class Middleware(val plugin: Plugin, val engine: QuickJs, val callbackId: Int)

    class CompiledMiddleware internal constructor(private val byCtorId: SparseArray<Array<Middleware>>) {
        internal fun listenersFor(ctorId: Int): Array<Middleware>? = byCtorId.get(ctorId)
    }

    private val lock = Any()
    private val live = LinkedHashMap<Pair<QuickJs, Int>, Registration>()
    private val liveMiddleware = LinkedHashMap<Pair<QuickJs, Int>, Pair<Middleware, Set<Int>>>()

    /** [constructor] is the id read off the wire, which is what the table is keyed by - reading it back off the class would cost a reflective lookup per object */
    @JvmStatic
    fun apply(obj: TLObject, constructor: Int) {
        rules?.rulesFor(constructor)?.let { applyRules(obj, it) }
        middleware?.listenersFor(constructor)?.let { runMiddleware(obj, it) }
    }

    private fun applyRules(obj: TLObject, matching: Array<Rule>) {
        val fields = TlJson.publicFields(obj.javaClass)
        for (rule in matching) {
            try {
                if (!matches(obj, fields, rule)) continue
                rewrite(obj, fields, rule)
            } catch (e: Throwable) {
                // a term is type-checked at registration, so this is unreachable for an accepted rule - and a half-parsed object is worse than a rule that did nothing
                android.util.Log.d("inugram", "plugin deserialize rule failed: ${e.message}")
            }
        }
    }

    /**
     * blocks the parsing thread until the middlewares are done or [MIDDLEWARE_BUDGET_MS] is up.
     * Both halves are forced: an engine may only be entered from a globalQueue runnable, and the app
     * is about to *use* this object, so there is nowhere to hand the answer back to later.
     *
     * A parse already *on* globalQueue skips the middleware entirely - that is a plugin reaching
     * stock through `unsafe.jvm`, and waiting there is waiting on ourselves.
     */
    private fun runMiddleware(obj: TLObject, listeners: Array<Middleware>) {
        if (Utilities.globalQueue as Any === Thread.currentThread()) return
        val latch = CountDownLatch(1)
        // read by the runnable to stop at the next engine boundary once nobody is waiting: a middleware entered before the budget ran out still finishes
        val waiting = AtomicBoolean(true)
        Utilities.globalQueue.postRunnable {
            try {
                for (listener in listeners) {
                    if (!waiting.get()) break
                    val tl = PluginRpc.tableFor(listener.plugin) ?: continue
                    if (!PluginDispatch.isLive(listener.plugin, listener.engine)) continue
                    val scopeId = TlHandles.newScope()
                    try {
                        val handle = tl.mintForDeserialize(obj, scopeId)
                        listener.engine.dispatchDeserialize(
                            listener.callbackId,
                            PluginWire.encodeHandle(vector = false, id = handle, readOnly = false),
                        )
                    } finally {
                        tl.releaseScope(scopeId)
                    }
                }
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(MIDDLEWARE_BUDGET_MS, TimeUnit.MILLISECONDS)) waiting.set(false)
    }

    private fun matches(obj: TLObject, fields: Map<String, Field>, rule: Rule): Boolean {
        for (term in rule.whenTerms) {
            val field = fields[term.name] ?: return false
            if (field.type !== term.type) return false
            if (field.get(obj) != term.value) return false
        }
        return true
    }

    private fun rewrite(obj: TLObject, fields: Map<String, Field>, rule: Rule) {
        for (term in rule.setTerms) {
            val field = fields[term.name] ?: continue
            if (field.type !== term.type) continue
            field.set(obj, term.value)
            // only this field's bit: a wholesale recompute would flag the placeholders stock parks in untouched optional slots
            TlJson.syncFlagBit(obj, term.name)
        }
    }

    fun listenerFor(plugin: Plugin, engine: QuickJs): DeserializeListener =
        object : DeserializeListener {
            override fun onDeserializeRegister(callbackId: Int, rulesJson: String): String? =
                register(plugin, engine, callbackId, rulesJson)

            override fun onDeserializeUnregister(callbackId: Int) = unregister(engine, callbackId)

            override fun onDeserializeMiddlewareRegister(callbackId: Int, typesJson: String): String? =
                registerMiddleware(plugin, engine, callbackId, typesJson)

            override fun onDeserializeMiddlewareUnregister(callbackId: Int) =
                unregisterMiddleware(engine, callbackId)
        }

    internal fun detach(engine: QuickJs) {
        synchronized(lock) {
            var changed = live.keys.removeAll { it.first === engine }
            changed = liveMiddleware.keys.removeAll { it.first === engine } || changed
            if (changed) publish()
        }
    }

    private fun register(plugin: Plugin, engine: QuickJs, callbackId: Int, rulesJson: String): String? {
        // the engine's own check_grant already ran; this is the second gate, on the side that owns the data
        val permissions = plugin.permissions
        val policy = TlFilter.policyFor(permissions)
        val compiled = HashMap<Int, MutableList<Rule>>()
        val array = try {
            JSONArray(rulesJson)
        } catch (e: JSONException) {
            return "internal: malformed rule set (${e.message})"
        }
        for (index in 0 until array.length()) {
            val rule = array.optJSONObject(index) ?: return "internal: rules[$index] is not an object"
            val types = rule.optJSONArray("type") ?: return "internal: rules[$index].type is not an array"
            for (typeIndex in 0 until types.length()) {
                val name = types.optString(typeIndex)
                if (!permissions.allows("interceptDeserialize", name, ScopeMatch.EXACT)) {
                    return PluginWire.encodeNotGranted("interceptDeserialize", name)
                }
                compileType(name, rule, policy, compiled)?.let { return it }
            }
        }
        synchronized(lock) {
            live[engine to callbackId] = Registration(compiled.mapValues { it.value.toList() })
            publish()
        }
        return null
    }

    private class Reachable(val ids: Set<Int>, val error: String?)

    /** the refusals both tiers share, all about *which object* may be reached rather than what may be done with it */
    private fun reachableIds(name: String, policy: TlFilter.Policy): Reachable {
        val declared = TlCtorIds.idsOf(name)
            ?: return Reachable(emptySet(), refuse("invalid-argument", "no TL constructor is named '$name'"))
        if (name in TlCtorIds.methodNames) {
            return Reachable(
                emptySet(),
                refuse(
                    "invalid-argument",
                    "'$name' is an rpc method: the app serializes one and never parses one, so a rule " +
                        "on it could only ever do nothing. use interceptRpc",
                ),
            )
        }
        if (policy.takeover && (name.startsWith("auth.") || name in ApiFilter.HIDDEN_FIELDS)) {
            return Reachable(
                emptySet(),
                refuse("forbidden", "'$name' is an account-takeover surface and is not a rule target"),
            )
        }
        val ids = declared - secretCtorIds
        if (ids.isEmpty()) {
            return Reachable(
                emptySet(),
                refuse("forbidden", "'$name' is secret-chat traffic, which no plugin api reaches"),
            )
        }
        return Reachable(ids, null)
    }

    private fun compileType(
        name: String,
        rule: JSONObject,
        policy: TlFilter.Policy,
        into: HashMap<Int, MutableList<Rule>>,
    ): String? {
        val reachable = reachableIds(name, policy)
        reachable.error?.let { return it }
        val ids = reachable.ids
        val cls = TlJson.classOf(name)
            ?: return refuse("invalid-argument", "no TL constructor is named '$name'")

        val whenTerms = ArrayList<Term>()
        val setTerms = ArrayList<Term>()
        readTerms(rule.optJSONObject("when"), cls, policy, matching = true, whenTerms)?.let { return it }
        readTerms(rule.optJSONObject("set"), cls, policy, matching = false, setTerms)?.let { return it }

        val compiled = Rule(whenTerms.toTypedArray(), setTerms.toTypedArray())
        for (id in ids) into.getOrPut(id) { ArrayList() }.add(compiled)
        return null
    }

    private fun readTerms(
        terms: JSONObject?,
        cls: Class<out TLObject>,
        policy: TlFilter.Policy,
        matching: Boolean,
        out: MutableList<Term>,
    ): String? {
        if (terms == null) return null
        val fields = TlJson.publicFields(cls)
        val tlName = TlNames.classNameToTlName(cls)
        val keys = terms.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (TlFlags.isFlagWord(cls, key)) {
                return refuse(
                    "forbidden",
                    "'$key' on '$tlName' is the wire's own bookkeeping and is managed by the bridge; " +
                        "set the optional fields instead",
                )
            }
            // a filtered field reads as absent everywhere else, and a plugin that can tell "not there" from "there but not for you" has learnt what the filter withholds. Only the draft rule reaches this
            if (TlFilter.hidesField(policy, cls, key)) {
                return refuse("invalid-argument", "'$tlName' has no field '$key'")
            }
            val field = fields[key]
                ?: return refuse("invalid-argument", "'$tlName' has no field '$key'")
            if (matching && hidesValue(policy, cls, key)) {
                return refuse(
                    "forbidden",
                    "'$key' on '$tlName' is filtered for this plugin, and matching on a value is " +
                        "reading it",
                )
            }
            if (!matching) {
                if (policy.takeover && TlFilter.decidesRedaction(cls, key)) {
                    return refuse(
                        "forbidden",
                        "'$key' is sealed while api filtering is on: login code redaction is keyed on it",
                    )
                }
                if (DeserializeGuards.isProtectedField(key)) {
                    return refuse("forbidden", DeserializeGuards.protectedFieldReason(key))
                }
            }
            val term = terms.get(key)
            if (term === JSONObject.NULL && TlFlags.gateOf(cls, key) == null) {
                return refuse(
                    "invalid-argument",
                    "'$key' on '$tlName' is not optional on the wire, so it cannot be cleared",
                )
            }
            val value = coerce(term, field.type)
                ?: return refuse("invalid-argument", "'$key' on '$tlName' ${describe(field.type)}")
            out.add(Term(key, field.type, value.value))
        }
        return null
    }

    /** a field whose *value* [TlFilter] rewrites per object rather than hiding outright, so whether it exists is decidable here but what it says is not */
    private fun hidesValue(policy: TlFilter.Policy, cls: Class<*>, key: String): Boolean =
        policy.takeover &&
            key == ApiFilter.REDACTED_MESSAGE_FIELD &&
            (
                TLRPC.Message::class.java.isAssignableFrom(cls) ||
                    TLRPC.Updates::class.java.isAssignableFrom(cls)
                )

    private class Coerced(val value: Any?)

    /**
     * `Field.set` on a primitive needs the exactly-matching boxed width and a primitive has no null,
     * so both are decided here rather than at [apply]. A `long` takes a string as well as a number:
     * a js number stops being exact at 2^53 and TL ids run past it.
     */
    private fun coerce(value: Any, type: Class<*>): Coerced? {
        // `null` clears a string, which is a value; clearing a nested object, vector or byte string is a structural rewrite of the row
        if (value === JSONObject.NULL) return if (type == String::class.java) Coerced(null) else null
        return when (type) {
            java.lang.Long.TYPE -> when (value) {
                is Number -> Coerced(value.toLong())
                is String -> value.toLongOrNull()?.let { Coerced(it) }
                else -> null
            }
            Integer.TYPE -> (value as? Number)?.let { Coerced(it.toInt()) }
            java.lang.Short.TYPE -> (value as? Number)?.let { Coerced(it.toShort()) }
            java.lang.Byte.TYPE -> (value as? Number)?.let { Coerced(it.toByte()) }
            java.lang.Double.TYPE -> (value as? Number)?.let { Coerced(it.toDouble()) }
            java.lang.Float.TYPE -> (value as? Number)?.let { Coerced(it.toFloat()) }
            java.lang.Boolean.TYPE -> (value as? Boolean)?.let { Coerced(it) }
            String::class.java -> (value as? String)?.let { Coerced(it) }
            else -> null
        }
    }

    private fun describe(type: Class<*>): String = when (type) {
        java.lang.Long.TYPE, Integer.TYPE, java.lang.Short.TYPE, java.lang.Byte.TYPE ->
            "is a whole number, which the given value is not"
        java.lang.Double.TYPE, java.lang.Float.TYPE -> "is a number, which the given value is not"
        java.lang.Boolean.TYPE -> "is a boolean, which the given value is not"
        String::class.java -> "is a string, which the given value is not"
        else -> "is a ${type.simpleName}, which no constant can be"
    }

    private fun refuse(code: String, message: String): String =
        PluginWire.encodePluginError(code, "interceptDeserialize: $message")

    private fun unregister(engine: QuickJs, callbackId: Int) {
        synchronized(lock) {
            if (live.remove(engine to callbackId) != null) publish()
        }
    }

    /** a middleware names constructors and nothing else, and reads through a normal TL view where [TlFilter] and [DeserializeGuards] already decide what it may touch */
    private fun registerMiddleware(plugin: Plugin, engine: QuickJs, callbackId: Int, typesJson: String): String? {
        val permissions = plugin.permissions
        val policy = TlFilter.policyFor(permissions)
        val array = try {
            JSONArray(typesJson)
        } catch (e: JSONException) {
            return "internal: malformed constructor list (${e.message})"
        }
        val ids = HashSet<Int>()
        for (index in 0 until array.length()) {
            val name = array.optString(index)
            if (!permissions.allows("interceptDeserialize", name, ScopeMatch.EXACT)) {
                return PluginWire.encodeNotGranted("interceptDeserialize", name)
            }
            val reachable = reachableIds(name, policy)
            reachable.error?.let { return it }
            ids.addAll(reachable.ids)
        }
        synchronized(lock) {
            liveMiddleware[engine to callbackId] = Middleware(plugin, engine, callbackId) to ids
            publish()
        }
        return null
    }

    private fun unregisterMiddleware(engine: QuickJs, callbackId: Int) {
        synchronized(lock) {
            if (liveMiddleware.remove(engine to callbackId) != null) publish()
        }
    }

    /**
     * rebuilds the whole snapshot from every live registration. Copy-on-write, so [apply] never
     * takes [lock] and never sees a half-built table.
     */
    private fun publish() {
        rules = if (live.isEmpty()) null else {
            val merged = HashMap<Int, ArrayList<Rule>>()
            for (registration in live.values) {
                for ((id, list) in registration.rules) merged.getOrPut(id) { ArrayList() }.addAll(list)
            }
            val table = SparseArray<Array<Rule>>()
            for (id in merged.keys.sorted()) table.put(id, merged[id]!!.toTypedArray())
            Compiled(table)
        }
        middleware = if (liveMiddleware.isEmpty()) null else {
            val merged = HashMap<Int, ArrayList<Middleware>>()
            for ((listener, ids) in liveMiddleware.values) {
                for (id in ids) merged.getOrPut(id) { ArrayList() }.add(listener)
            }
            val table = SparseArray<Array<Middleware>>()
            for (id in merged.keys.sorted()) table.put(id, merged[id]!!.toTypedArray())
            CompiledMiddleware(table)
        }
        hot = rules != null || middleware != null
    }

    /**
     * every constructor a rule may not reach. The `encrypted*`/`decrypted*` families come off their
     * canonical names, but `TL_message_secret` extends `TL_message` and its layer variants are
     * folded into `message`'s id family with no name of their own - so those are swept off stock's
     * own classes, which is also what keeps a rebase that adds one from quietly widening the api.
     */
    private val secretCtorIds: Set<Int> by lazy {
        val out = HashSet<Int>()
        for (name in TlCtorIds.allNames) {
            if (DeserializeGuards.isSecretName(name)) out.addAll(TlCtorIds.idsOf(name).orEmpty())
        }
        for (cls in TLRPC::class.java.declaredClasses) {
            if (!cls.simpleName.startsWith("TL_message_secret")) continue
            try {
                out.add(cls.getDeclaredField("constructor").getInt(null))
            } catch (e: Exception) {
                // a secret class stock stopped declaring an id for cannot be deserialized either
            }
        }
        out
    }
}
