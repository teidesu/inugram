package desu.inugram.helpers.plugins.tl

import android.util.Base64
import android.util.SparseArray
import desu.inugram.core.plugins.DeserializeGuards
import desu.inugram.core.plugins.TlFlags
import desu.inugram.core.plugins.TlNames
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.TlListener
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.Collections
import java.util.IdentityHashMap
import org.json.JSONArray
import org.json.JSONTokener
import org.telegram.tgnet.TLObject

/**
 * Handle table backing the live-proxy TL bridge (rust: `tl_proxy.rs`): JS holds a lazy `Proxy` over
 * a handle id whose traps round-trip through [TlListener], never an eager snapshot of the
 * object graph.
 *
 * **One instance per running plugin.** An id is a bare integer and rust re-emits any JS object
 * carrying the `inu.tl.handle` marker symbol, which a plugin can forge - a shared table would let
 * one plugin read another's objects by guessing an id.
 *
 * Touched only on [org.telegram.messenger.Utilities.globalQueue], so it needs no locking. Repeated
 * reads of one field mint a fresh handle each time; caching here would need an rquickjs
 * `Persistent`, and a GC root outliving the runtime aborts under `panic = "abort"`.
 *
 * Two lifetimes share the table: dispatch-scoped handles, hard-invalidated in bulk by
 * [releaseScope] whether or not JS still references them, and plugin-lifetime ones
 * ([mintForPlugin]) under a scope [releaseScope] is never called with.
 */
class TlHandles(private val policy: TlFilter.Policy) : TlListener {
    private class HandleEntry(
        val target: Any,
        val elementType: Type?,
        val scopeId: Long,
        val readOnly: Boolean,
        // set on an `interceptDeserialize` middleware scope and inherited by every child: the object is on its way into the app's database, so [DeserializeGuards] applies
        val guarded: Boolean = false,
        // an invokeRpc response, whose freeResources() stock skipped so this table could own it
        val owned: Boolean = false,
        // set only when target is a vector minted for a TLObject field, so a mutation through
        // this handle (push, length=) can resync the owning object's flag bit; syncFlagBit is a
        // no-op when the field isn't gated
        val flagOwner: Pair<TLObject, String>? = null,
    )

    private var nextHandle = 1L
    private val table = HashMap<Long, HandleEntry>()

    // which handles a scope minted, so [releaseScope] costs that scope rather than the whole table.
    // Not a nicety: `interceptDeserialize`'s middleware form opens a scope per object the *app*
    // parses, and a plugin also holding thousands of plugin-lifetime handles would pay a full walk
    // of them per parsed object
    private val handlesByScope = HashMap<Long, MutableList<Long>>()

    fun mintForScope(target: Any, scopeId: Long): Long =
        mint(target, elementType = null, scopeId = scopeId, readOnly = false)

    /** the object is one the app just parsed and is about to keep, so every write through it runs [DeserializeGuards] */
    fun mintForDeserialize(target: Any, scopeId: Long): Long =
        mint(target, elementType = null, scopeId = scopeId, readOnly = false, guarded = true)

    /** [owned] marks a target this table must [TLObject.freeResources] itself, stock's own free having been suppressed to hand it over */
    fun mintForPlugin(target: Any, readOnly: Boolean, owned: Boolean = false): Long =
        mint(target, elementType = null, scopeId = PLUGIN_SCOPE, readOnly = readOnly, owned = owned)

    private fun mint(
        target: Any,
        elementType: Type?,
        scopeId: Long,
        readOnly: Boolean,
        flagOwner: Pair<TLObject, String>? = null,
        owned: Boolean = false,
        guarded: Boolean = false,
    ): Long {
        val handle = nextHandle++
        table[handle] = HandleEntry(target, elementType, scopeId, readOnly, guarded, owned, flagOwner)
        if (scopeId != PLUGIN_SCOPE) handlesByScope.getOrPut(scopeId) { ArrayList() }.add(handle)
        return handle
    }

    fun releaseAll() {
        for (entry in table.values) freeIfOwned(entry)
        table.clear()
        handlesByScope.clear()
    }

    private fun freeIfOwned(entry: HandleEntry) {
        if (!entry.owned) return
        val target = entry.target as? TLObject ?: return
        // every freeResources() override early-returns while this is set, and it was set to stop stock freeing the response out from under us
        target.disableFree = false
        target.freeResources()
    }

    /** nothing is freed: only [mintForPlugin] sets `owned`, and it mints under [PLUGIN_SCOPE] */
    fun releaseScope(scopeId: Long) {
        val handles = handlesByScope.remove(scopeId) ?: return
        for (handle in handles) table.remove(handle)
    }

    fun resolveTlObject(handle: Long): TLObject? = table[handle]?.target as? TLObject

    /** consuming an app-owned object as a request or field value would alias it into a writable graph, where re-reading the field mints a writable child */
    fun isReadOnly(handle: Long): Boolean = table[handle]?.readOnly ?: false

    override fun tlGet(handle: Long, key: String): String {
        val entry = table[handle] ?: return PluginWire.encodeExpired()
        return when (val target = entry.target) {
            is TLObject -> getObjectField(entry, target, key)
            is ArrayList<*> -> getVectorProp(entry, target, key)
            else -> PluginWire.encodeError("internal: unsupported handle target ${target.javaClass}")
        }
    }

    override fun tlSet(handle: Long, key: String, valueWire: String): String? {
        val entry = table[handle] ?: return PluginWire.encodeExpired()
        // the rust traps already refuse a read-only view; this is the same refusal on the side that owns the mode, so a forged wire can't write either
        if (entry.readOnly) return PluginWire.encodePluginError("forbidden", READ_ONLY_MESSAGE)
        @Suppress("UNCHECKED_CAST")
        return try {
            when (val target = entry.target) {
                is TLObject -> setObjectField(entry, target, key, valueWire)
                is ArrayList<*> -> setVectorProp(entry, target as ArrayList<Any?>, key, valueWire)
                else -> "internal: unsupported handle target ${target.javaClass}"
            }
        } catch (e: Exception) {
            // a plugin picks the assigned value and rust re-emits whatever string it hangs off the marker symbol, so a malformed wire is plugin input, not a bug
            PluginWire.encodePluginError("invalid-argument", "assigning '$key': ${e.message ?: e.toString()}")
        }
    }

    override fun tlHas(handle: Long, key: String): Int {
        val entry = table[handle] ?: return -1
        val present = when (val target = entry.target) {
            is TLObject -> key == "_" || isVisibleField(target, key)
            is ArrayList<*> -> key == "length" || (key.toIntOrNull()?.let { it in 0 until target.size } ?: false)
            else -> false
        }
        return if (present) 1 else 0
    }

    override fun tlOwnKeys(handle: Long): String? {
        val target = table[handle]?.target as? TLObject ?: return null
        val fields = TlReflect.publicFields(target.javaClass).keys.filter { isVisibleField(target, it) }
        return (sequenceOf("_") + fields).joinToString(",")
    }

    /** `in` and `Object.keys` have to agree with reads: no flag words, no cleared-bit fields, nothing [TlFilter] hides */
    private fun isVisibleField(target: TLObject, key: String): Boolean {
        val cls = target.javaClass
        if (!TlReflect.publicFields(cls).containsKey(key)) return false
        if (TlFlags.isFlagWord(cls, key)) return false
        if (TlFilter.hidesField(policy, cls, key)) return false
        val gate = TlFlags.gateOf(cls, key) ?: return true
        return TlReflect.isBitSet(target, cls, gate)
    }

    override fun tlCopy(handle: Long): String? {
        val entry = table[handle] ?: return null
        return when (val target = entry.target) {
            is TLObject -> TlJson.toJson(target, policy).toString()
            is ArrayList<*> -> {
                val arr = JSONArray()
                for (item in target) if (item != null) TlJson.valueToJson(item, policy)?.let { arr.put(it) }
                arr.toString()
            }
            else -> null
        }
    }

    override fun tlRelease(handle: Long) {
        table.remove(handle)?.let { freeIfOwned(it) }
    }

    private fun getObjectField(entry: HandleEntry, target: TLObject, key: String): String {
        val cls = target.javaClass
        if (key == "_") return PluginWire.encodeString(TlNames.classNameToTlName(cls))
        if (TlFlags.isFlagWord(cls, key)) return PluginWire.encodeNull()
        // a filtered-out field reads as absent, exactly like a cleared flag bit, never as an error
        if (TlFilter.hidesField(policy, cls, key)) return PluginWire.encodeNull()
        val field = TlReflect.publicFields(cls)[key]
            ?: return PluginWire.encodeError("no such field '$key' on '${TlNames.classNameToTlName(cls)}'")
        // a field whose bit is clear isn't there, whatever the java slot happens to hold - stock
        // parks placeholders in some of them (`photo = new TL_photoEmpty()`)
        val gate = TlFlags.gateOf(cls, key)
        if (gate != null && !TlReflect.isBitSet(target, cls, gate)) return PluginWire.encodeNull()
        val value = try {
            field.get(target)
        } catch (e: Exception) {
            return PluginWire.encodeError(e.message ?: "reflection get failed")
        }
        val filtered = if (policy.takeover) TlFilter.filterFieldValue(target, key, value) else value
        return encodeFieldValue(
            entry,
            filtered,
            field.genericType,
            flagOwner = target to key,
            // the peer behind it decides redaction one level down (`m.from_id.user_id = 0`), so the child is sealed even when the parent is writable
            sealed = policy.takeover && TlFilter.decidesRedaction(cls, key),
        )
    }

    private fun setObjectField(entry: HandleEntry, target: TLObject, key: String, wire: String): String? {
        val cls = target.javaClass
        if (key == "_") return "cannot assign to '_'"
        if (entry.guarded && DeserializeGuards.isProtectedField(key)) {
            return PluginWire.encodePluginError("forbidden", DeserializeGuards.protectedFieldReason(key))
        }
        if (TlFlags.isFlagWord(cls, key)) {
            return "'$key' on '${TlNames.classNameToTlName(cls)}' is managed by the bridge - set the optional fields instead"
        }
        // the same refusal a nonexistent field gets: without this the write lands on the app's live object while every read path still reports the field absent
        if (TlFilter.hidesField(policy, cls, key)) {
            return "no such field '$key' on '${TlNames.classNameToTlName(cls)}'"
        }
        if (policy.takeover && TlFilter.decidesRedaction(cls, key)) {
            return PluginWire.encodePluginError("forbidden", "'$key' is sealed while api filtering is on: login code redaction is keyed on it")
        }
        val field = TlReflect.publicFields(cls)[key]
            ?: return "no such field '$key' on '${TlNames.classNameToTlName(cls)}'"
        val gated = TlFlags.gateOf(cls, key) != null
        val resolved = resolveSetValue(
            PluginWire.decode(wire),
            field.genericType,
            field.type,
            key,
            allowPrimitiveClear = gated,
            guarded = entry.guarded,
        )
        if (resolved.isError) return resolved.error
        return try {
            field.set(target, resolved.value)
            // only this field's bit: the object is live, and its untouched fields may hold placeholders a wholesale recompute would flag
            TlReflect.syncFlagBit(target, key)
            null
        } catch (e: Exception) {
            e.message ?: "reflection set failed"
        }
    }

    private fun getVectorProp(entry: HandleEntry, target: ArrayList<*>, key: String): String {
        if (key == "length") return PluginWire.encodeInt(target.size.toLong())
        val index = key.toIntOrNull() ?: return PluginWire.encodeError("no such property '$key' on a TL vector")
        if (index < 0 || index >= target.size) return PluginWire.encodeError("vector index out of range: $index")
        return encodeFieldValue(entry, target[index], entry.elementType ?: Any::class.java)
    }

    private fun setVectorProp(entry: HandleEntry, target: ArrayList<Any?>, key: String, wire: String): String? {
        if (key == "length") {
            // `vec.length = n` always crosses as a `J`-tagged JSON number, never a raw `I` tag, so `Value.IntNum` alone is unreachable
            val decoded = PluginWire.decode(wire)
            val newLength = when (decoded) {
                is PluginWire.Value.IntNum -> decoded.value.toInt()
                is PluginWire.Value.Json -> (JSONTokener(decoded.json).nextValue() as? Number)?.toInt()
                else -> null
            } ?: return "vector length must be an integer"
            if (newLength < 0 || newLength > target.size) return "vector length can only shrink (${target.size} -> $newLength not allowed)"
            while (target.size > newLength) target.removeAt(target.size - 1)
            entry.flagOwner?.let { (obj, name) -> TlReflect.syncFlagBit(obj, name) }
            return null
        }
        val index = key.toIntOrNull() ?: return "no such property '$key' on a TL vector"
        if (index < 0 || index > target.size) return "vector index out of range: $index"
        val elementType = entry.elementType ?: return "vector element type is unknown"
        val resolved =
            resolveSetValue(PluginWire.decode(wire), elementType, rawClassOf(elementType), "[$index]", guarded = entry.guarded)
        if (resolved.isError) return resolved.error
        if (index == target.size) target.add(resolved.value) else target[index] = resolved.value
        entry.flagOwner?.let { (obj, name) -> TlReflect.syncFlagBit(obj, name) }
        return null
    }

    private fun encodeFieldValue(
        entry: HandleEntry,
        value: Any?,
        declaredType: Type,
        flagOwner: Pair<TLObject, String>? = null,
        sealed: Boolean = false,
    ): String {
        if (value == null) return PluginWire.encodeNull()
        val readOnly = entry.readOnly || sealed
        return when (value) {
            is Long -> PluginWire.encodeString(value.toString())
            is Int -> PluginWire.encodeInt(value.toLong())
            is Short -> PluginWire.encodeInt(value.toLong())
            is Byte -> PluginWire.encodeInt(value.toLong())
            is Double -> PluginWire.encodeDouble(value)
            is Float -> PluginWire.encodeDouble(value.toDouble())
            is Boolean -> PluginWire.encodeBool(value)
            is String -> PluginWire.encodeString(value)
            is ByteArray -> PluginWire.encodeBytes(Base64.encodeToString(value, Base64.NO_WRAP))
            // a child MUST inherit its parent's scope, and its mode unless [sealed] tightens it:
            // rust infers a view's lifetime from the entry point rather than carrying it on the
            // wire, so another scope would let releaseScope kill a plugin-lifetime view, and a
            // writable child of a read-only parent would be a mutable alias of an app object
            is TLObject -> PluginWire.encodeHandle(
                vector = false,
                id = mint(value, null, entry.scopeId, readOnly, guarded = entry.guarded),
                readOnly = readOnly,
            )
            is ArrayList<*> -> PluginWire.encodeHandle(
                vector = true,
                id = mint(value, elementTypeOf(declaredType), entry.scopeId, readOnly, flagOwner, guarded = entry.guarded),
                readOnly = readOnly,
            )
            // map-shaped fields (TLRPC.Message.params) have no handle kind of their own, so they cross as a detached json snapshot
            is Map<*, *>, is SparseArray<*> -> TlJson.valueToJson(value, policy)
                ?.let { PluginWire.encodeJson(it.toString()) }
                ?: PluginWire.encodeNull()
            else -> PluginWire.encodeError("unsupported field type ${value.javaClass}")
        }
    }

    private class Resolved(val value: Any?, val error: String?) {
        val isError: Boolean get() = error != null
    }

    private fun ok(value: Any?) = Resolved(value, null)
    private fun err(message: String) = Resolved(null, message)

    /**
     * [guarded] is the deserialize scope's rule, and it lives here rather than at the two call
     * sites because the guard is about the value: the name a payload is assigned to says nothing
     * about the fields the payload itself carries, and a vector element has no name at all.
     */
    private fun resolveSetValue(
        decoded: PluginWire.Value,
        genericType: Type,
        rawType: Class<*>,
        path: String,
        allowPrimitiveClear: Boolean = false,
        guarded: Boolean = false,
    ): Resolved =
        when (decoded) {
            is PluginWire.Value.Null -> {
                if (!rawType.isPrimitive) {
                    ok(null)
                } else if (allowPrimitiveClear) {
                    // gated primitive field: null means "absent", which for a primitive java slot is indistinguishable from zero
                    ok(zeroValueOf(rawType))
                } else {
                    err("cannot clear primitive field at '$path'")
                }
            }
            is PluginWire.Value.Bytes -> {
                if (rawType == ByteArray::class.java) {
                    ok(Base64.decode(decoded.base64, Base64.NO_WRAP))
                } else {
                    err("type mismatch assigning bytes at '$path': expected $rawType")
                }
            }
            is PluginWire.Value.Handle -> {
                val source = table[decoded.id] ?: return err(PluginWire.encodeExpired())
                val instance = source.target
                if (guarded) {
                    // it carries the addressing fields of wherever it was parsed, and splicing it in is how those reach a slot they do not name
                    err(PluginWire.encodePluginError("forbidden", SPLICE_MESSAGE))
                } else if (source.readOnly) {
                    err(PluginWire.encodePluginError("forbidden", READ_ONLY_MESSAGE))
                } else if (!rawType.isInstance(instance)) {
                    err("type mismatch assigning handle at '$path': expected $rawType, got ${instance.javaClass}")
                } else {
                    ok(instance)
                }
            }
            is PluginWire.Value.Json -> try {
                val parsed = JSONTokener(decoded.json).nextValue()
                val protected = if (guarded) TlJson.findProtectedField(parsed) else null
                if (protected != null) {
                    err(PluginWire.encodePluginError("forbidden", DeserializeGuards.protectedFieldReason(protected)))
                } else {
                    ok(TlJson.jsonToValue(genericType, parsed, path))
                }
            } catch (e: Exception) {
                err(e.message ?: "construct failed at '$path'")
            }
            else -> err("unsupported set payload at '$path'")
        }

    private fun zeroValueOf(rawType: Class<*>): Any = when (rawType) {
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Double.TYPE -> 0.0
        java.lang.Float.TYPE -> 0f
        java.lang.Boolean.TYPE -> false
        else -> error("no zero value for $rawType")
    }

    private fun elementTypeOf(type: Type): Type? {
        if (type !is ParameterizedType) return null
        if (type.rawType != ArrayList::class.java) return null
        return type.actualTypeArguments[0]
    }

    private fun rawClassOf(type: Type): Class<*> = when (type) {
        is Class<*> -> type
        is ParameterizedType -> rawClassOf(type.rawType)
        else -> Any::class.java
    }

    companion object {
        private const val PLUGIN_SCOPE = 0L

        // must stay byte-identical to READ_ONLY_MESSAGE in src/native/src/tl/proxy.rs:
        // the same refusal is raised on whichever side sees the write first
        const val READ_ONLY_MESSAGE = "this TL view is read-only; take a copy with toJSON() to edit it"

        const val SPLICE_MESSAGE = "a live TL object cannot be assigned inside interceptDeserialize: it carries the " +
            "addressing fields of wherever it was parsed - build the replacement as a plain object instead"

        // one dispatch's chain spans several plugins' tables and every one of them must release
        // the same scope id, so the counter can't live per-instance
        private var nextScopeId = 1L

        fun newScope(): Long = nextScopeId++

        fun of(engine: QuickJs): TlHandles =
            engine.listener?.tl as? TlHandles ?: throw IllegalStateException("no handle table")

        private val byPlugin = HashMap<Plugin, TlHandles>()

        // `plugin.engine` is *not* this signal: it is cleared only after `engine.close()` and the
        // table only after the abandon loops, so between the two a chain restarted by one of those
        // abandons would read a leaving plugin as live and mint into an engine already unloading
        private val detaching = Collections.newSetFromMap(IdentityHashMap<Plugin, Boolean>())

        /** the plugin's own table, which every materialization for it mints into */
        fun attach(plugin: Plugin, policy: TlFilter.Policy): TlHandles =
            TlHandles(policy).also { byPlugin[plugin] = it }

        fun of(plugin: Plugin): TlHandles? = byPlugin[plugin]

        /** [of], but null once the plugin is on its way out - see [detaching] */
        fun attached(plugin: Plugin): TlHandles? = if (plugin in detaching) null else byPlugin[plugin]

        fun beginDetach(plugin: Plugin) {
            detaching.add(plugin)
        }

        /**
         * last of the whole teardown, and that is the rule: the abandons above it reject inside this
         * plugin too, and a continuation touching its own request view must not find every field
         * expired.
         */
        fun endDetach(plugin: Plugin) {
            byPlugin.remove(plugin)?.releaseAll()
            detaching.remove(plugin)
        }
    }
}
