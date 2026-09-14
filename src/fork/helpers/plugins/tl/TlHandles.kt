package desu.inugram.helpers.plugins.tl

import android.util.Base64
import android.util.SparseArray
import desu.inugram.core.plugins.TlFlags
import desu.inugram.core.plugins.TlNames
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.TlListener
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.telegram.tgnet.TLObject

/**
 * Handle table backing the live-proxy TL bridge (rust: `tl/proxy.rs`): JS holds a lazy `Proxy` over
 * a handle id whose traps round-trip through [TlListener], never an eager snapshot of the
 * object graph.
 *
 * **One instance per running plugin.** An id is a bare integer and rust re-emits any JS object
 * carrying the `inu.tl.handle` marker symbol, which a plugin can forge - a shared table would let
 * one plugin read another's objects by guessing an id.
 *
 * Reached from the plugin queue and from whichever thread a JVM runnable or an Xposed phase entered
 * on, so [table] is concurrent and the mint/release pair that spans it and [handlesByScope] is
 * serialized by [scopeLock]. Repeated reads of one field mint a fresh handle each time; caching
 * here would need an rquickjs `Persistent`, and a GC root outliving the runtime aborts under
 * `panic = "abort"`.
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
        // an invokeRpc response, whose freeResources() stock skipped so this table could own it
        val owned: Boolean = false,
        // set only when target is a vector minted for a TLObject field, so a mutation through
        // this handle (push, length=) can resync the owning object's flag bit; syncFlagBit is a
        // no-op when the field isn't gated
        val flagOwner: Pair<TLObject, String>? = null,
    )

    private val onHost = EngineDispatch.createHostDispatcher()
    private val nextHandle = AtomicLong(1)
    private val table = ConcurrentHashMap<Long, HandleEntry>()

    private val scopeLock = Any()

    // which handles a scope minted, so [releaseScope] costs that scope rather than the whole table.
    // guarded by [scopeLock]
    private val handlesByScope = HashMap<Long, MutableList<Long>>()

    fun mintForScope(target: Any, scopeId: Long): Long =
        mint(target, elementType = null, scopeId = scopeId, readOnly = false)

    /** [owned] marks a target this table must [TLObject.freeResources] itself, stock's own free having been suppressed to hand it over */
    fun mintForPlugin(target: Any, readOnly: Boolean, owned: Boolean = false): Long =
        mint(target, elementType = null, scopeId = PLUGIN_SCOPE, readOnly = readOnly, owned = owned)

    /**
     * a top-level handle as a wire: its class always, so ordinal reads take the fast path, and its
     * scalars too, which rust caches on a plugin-lifetime view
     */
    fun mintWireForPlugin(target: TLObject, readOnly: Boolean, owned: Boolean = false, fields: List<String>? = null): String {
        val id = mintForPlugin(target, readOnly, owned)
        return PluginWire.encodeHandle(vector = false, id = id, readOnly = readOnly, projection = project(id, fields), classId = classIdOf(target.javaClass))
    }

    /** [mintWireForPlugin] for a dispatch view, which caches nothing and so is sent no scalars */
    fun mintWireForScope(target: TLObject, scopeId: Long): String =
        PluginWire.encodeHandle(vector = false, id = mintForScope(target, scopeId), readOnly = false, classId = classIdOf(target.javaClass))

    private fun mint(
        target: Any,
        elementType: Type?,
        scopeId: Long,
        readOnly: Boolean,
        flagOwner: Pair<TLObject, String>? = null,
        owned: Boolean = false,
    ): Long = register(HandleEntry(target, elementType, scopeId, readOnly, owned, flagOwner))

    /**
     * the table write and the scope's bookkeeping are one step under [scopeLock], so a
     * [releaseScope] can never land between them and leave behind a scoped handle its bulk
     * invalidation cannot reach. A read that *began* before the release can still mint into the
     * scope after it; that one handle keeps its target until [releaseAll].
     */
    private fun register(entry: HandleEntry): Long {
        val handle = nextHandle.getAndIncrement()
        if (entry.scopeId == PLUGIN_SCOPE) {
            table[handle] = entry
            return handle
        }
        synchronized(scopeLock) {
            table[handle] = entry
            handlesByScope.getOrPut(entry.scopeId) { ArrayList() }.add(handle)
        }
        return handle
    }

    fun releaseAll() {
        synchronized(scopeLock) { handlesByScope.clear() }
        for (entry in table.values) freeIfOwned(entry)
        table.clear()
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
        synchronized(scopeLock) {
            val handles = handlesByScope.remove(scopeId) ?: return
            for (handle in handles) table.remove(handle)
        }
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

    /**
     * The by-ordinal read rust prefers over [tlGet]: no name to hash, no wire to build, and the
     * value lands in [out] as bytes rather than as a string that both sides then parse.
     *
     * Answers [ORDINAL_FALLBACK] for anything it will not serve - an unknown handle, a class that
     * is not the one the ordinal was resolved against, a value shape with no tag, a payload larger
     * than the buffer - and rust reads that field through [tlGet] instead. Every refusal is
     * therefore a slow read, never a wrong one.
     */
    override fun readField(handle: Long, classId: Int, ordinal: Int, out: ByteBuffer): Int {
        val entry = table[handle] ?: return ORDINAL_FALLBACK
        val target = entry.target as? TLObject ?: return ORDINAL_FALLBACK
        // the id indexes its slot, so this costs an array read rather than the hash of a class.
        // The class still has to be the one the handle holds, or a forged handle would turn an
        // ordinal resolved for one class into a read of whatever field sits at that index on another
        val slot = slotById(classId) ?: return ORDINAL_FALLBACK
        if (slot.cls !== target.javaClass) return ORDINAL_FALLBACK
        val info = slot.fields.getOrNull(ordinal) ?: return ORDINAL_FALLBACK
        out.clear()
        return try {
            if (writeField(out, entry, target, info)) out.position() else ORDINAL_FALLBACK
        } catch (e: java.nio.BufferOverflowException) {
            ORDINAL_FALLBACK
        }
    }

    override fun resolveField(classId: Int, key: String): Int =
        slotById(classId)?.ordinals?.get(key) ?: ORDINAL_FALLBACK

    private fun writeField(out: ByteBuffer, entry: HandleEntry, target: TLObject, info: TlReflect.FieldInfo): Boolean {
        if (info.isFlagWord || TlFilter.hidesField(policy, info) || !info.isPresent(target)) {
            out.put(TAG_NULL)
            return true
        }
        // the unboxed getters, for the fields a plugin actually reads in bulk
        when (info.kind) {
            TlReflect.KIND_LONG -> {
                out.put(TAG_LONG).putLong(info.field.getLong(target))
                return true
            }
            TlReflect.KIND_INT -> {
                out.put(TAG_INT).putInt(info.field.getInt(target))
                return true
            }
            TlReflect.KIND_BOOL -> {
                out.put(TAG_BOOL).put(if (info.field.getBoolean(target)) 1.toByte() else 0.toByte())
                return true
            }
            TlReflect.KIND_DOUBLE -> {
                out.put(TAG_DOUBLE).putDouble(info.field.getDouble(target))
                return true
            }
        }
        val raw = try {
            info.field.get(target)
        } catch (e: Exception) {
            return false
        }
        val value = if (policy.takeover && info.redactedInTakeover) {
            TlFilter.filterFieldValue(target, info.field.name, raw)
        } else {
            raw
        }
        val readOnly = entry.readOnly || (policy.takeover && info.sealedInTakeover)
        when (value) {
            null -> out.put(TAG_NULL)
            is String -> putString(out.put(TAG_STRING), value)
            is Long -> out.put(TAG_LONG).putLong(value)
            is Int -> out.put(TAG_INT).putInt(value)
            is Short -> out.put(TAG_INT).putInt(value.toInt())
            is Byte -> out.put(TAG_INT).putInt(value.toInt())
            is Boolean -> out.put(TAG_BOOL).put(if (value) 1.toByte() else 0.toByte())
            is Double -> out.put(TAG_DOUBLE).putDouble(value)
            is Float -> out.put(TAG_DOUBLE).putDouble(value.toDouble())
            is ByteArray -> out.put(TAG_BYTES).putInt(value.size).put(value)
            is TLObject -> putHandle(
                out,
                vector = false,
                id = register(HandleEntry(value, null, entry.scopeId, readOnly)),
                readOnly = readOnly,
                classId = classIdOf(value.javaClass),
            )
            is ArrayList<*> -> putHandle(
                out,
                vector = true,
                id = mint(
                    value,
                    elementTypeOf(info.genericType),
                    entry.scopeId,
                    readOnly,
                    flagOwner = target to info.field.name,
                ),
                readOnly = readOnly,
                classId = PluginWire.NO_CLASS,
            )
            // a json snapshot and every error shape stay on the by-name path, which already
            // spells them out; neither is worth a tag of its own
            else -> return false
        }
        return true
    }

    private fun putHandle(out: ByteBuffer, vector: Boolean, id: Long, readOnly: Boolean, classId: Int) {
        val flags = (if (vector) 1 else 0) or (if (readOnly) 2 else 0)
        out.put(TAG_HANDLE).put(flags.toByte()).putLong(id).putInt(classId)
    }

    private fun putString(out: ByteBuffer, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.putInt(bytes.size).put(bytes)
    }

    fun classIdOf(cls: Class<*>): Int = slotOf(cls).id

    override fun tlSet(handle: Long, key: String, valueWire: String): String? =
        applySet(handle, key, SetSource.Wire(PluginWire.decode(valueWire)))

    override fun tlSetBytes(handle: Long, key: String, value: ByteArray): String? =
        applySet(handle, key, SetSource.Bytes(value))

    /**
     * where a write's value came from. The wire carries every shape a set can take; [Bytes] is the
     * one that would have cost a base64 round trip to put there, so it arrives beside the wire
     * rather than inside it - the mirror of `TAG_BYTES` on the read side.
     */
    private sealed class SetSource {
        class Wire(val decoded: PluginWire.Value) : SetSource()
        class Bytes(val value: ByteArray) : SetSource()
    }

    private fun applySet(handle: Long, key: String, source: SetSource): String? {
        val entry = table[handle] ?: return PluginWire.encodeExpired()
        // the rust traps already refuse a read-only view; this is the same refusal on the side that owns the mode, so a forged wire can't write either
        if (entry.readOnly) return PluginWire.encodePluginError("forbidden", READ_ONLY_MESSAGE)
        @Suppress("UNCHECKED_CAST")
        return try {
            when (val target = entry.target) {
                is TLObject -> setObjectField(entry, target, key, source)
                is ArrayList<*> -> setVectorProp(entry, target as ArrayList<Any?>, key, source)
                else -> PluginWire.encodePluginError("internal", "unsupported handle target ${target.javaClass}")
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
        val info = TlReflect.fieldInfo(target.javaClass, key) ?: return false
        if (info.isFlagWord || TlFilter.hidesField(policy, info)) return false
        return info.isPresent(target)
    }

    /**
     * what rides along with a handle minted for a plugin's own read. An object that is nothing but
     * scalars ([TlReflect.isFullyScalar]) is carried whole, because one crossing then settles it for
     * good; anything else carries its type name alone. Never a child, not even a child's type: a
     * child is its own handle, and reflecting a parent's fields to find one costs more than the
     * crossing it would save (measured on a Pixel 9: 7.1us a read against 31us to reflect a
     * dialog's 22 fields).
     *
     * [fields] is the caller naming what it will read, which overrides both rules: exactly those
     * fields are carried, whatever the object is. A name this cannot carry - an object, a vector,
     * an outsized string, no such field at all - simply writes nothing, and rust reads it the
     * ordinary way, so a projection is never the reason a field is missing.
     */
    fun project(handle: Long, fields: List<String>? = null): String {
        val entry = table[handle] ?: return EMPTY_PROJECTION
        val target = entry.target as? TLObject ?: return EMPTY_PROJECTION
        val cls = target.javaClass
        val infos = TlReflect.fieldInfos(cls)
        val picked = when {
            fields == null -> if (TlReflect.isFullyScalar(cls)) infos.values else return typeOnlyOf(cls)
            else -> fields.mapNotNull { infos[it] }.ifEmpty { return typeOnlyOf(cls) }
        }
        return StringBuilder(96).also { appendProjection(it, target, picked) }.toString()
    }

    /** what [project] settled on: a field it cannot carry writes nothing and is left to a lazy read */
    private fun appendProjection(out: StringBuilder, target: TLObject, infos: Collection<TlReflect.FieldInfo>) {
        out.append('{').append(TYPE_ENTRY).append(quotedTypeOf(target.javaClass))
        for (info in infos) {
            if (info.isFlagWord || TlFilter.hidesField(policy, info)) continue
            val value = if (info.isPresent(target)) info.field.get(target) else null
            val filtered = if (policy.takeover && info.redactedInTakeover) {
                TlFilter.filterFieldValue(target, info.field.name, value)
            } else {
                value
            }
            val start = out.length
            out.append(',').append(info.quotedName).append(':')
            val wrote = when (filtered) {
                null -> out.append("null").let { true }
                // a long is a string: a plugin reads one back as a bigint, and JSON has no such number
                is Long -> out.append('"').append(filtered).append('"').let { true }
                is Int, is Short, is Byte, is Boolean -> out.append(filtered).let { true }
                is Double -> filtered.isFinite().also { if (it) out.append(filtered) }
                is Float -> filtered.isFinite().also { if (it) out.append(filtered.toDouble()) }
                is String -> (filtered.length <= PROJECTION_STRING_LIMIT).also { if (it) out.append(JSONObject.quote(filtered)) }
                else -> false
            }
            if (!wrote) out.setLength(start)
        }
        out.append('}')
    }

    private fun quotedTypeOf(cls: Class<*>): String =
        quotedTypeNames.getOrPut(cls) { JSONObject.quote(TlNames.classNameToTlName(cls)) }

    private fun typeOnlyOf(cls: Class<*>): String = typeOnlyProjections.getOrPut(cls) { "{$TYPE_ENTRY${quotedTypeOf(cls)}}" }

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

    override fun tlRelease(handle: Long) = onHost {
        table.remove(handle)?.let { freeIfOwned(it) }
    }

    private fun getObjectField(entry: HandleEntry, target: TLObject, key: String): String {
        val cls = target.javaClass
        if (key == "_") return PluginWire.encodeString(TlNames.classNameToTlName(cls))
        val info = TlReflect.fieldInfo(cls, key)
            ?: return PluginWire.encodeError("no such field '$key' on '${TlNames.classNameToTlName(cls)}'")
        if (info.isFlagWord) return PluginWire.encodeNull()
        // a filtered-out field reads as absent, exactly like a cleared flag bit, never as an error
        if (TlFilter.hidesField(policy, info)) return PluginWire.encodeNull()
        // a field whose bit is clear isn't there, whatever the java slot happens to hold - stock
        // parks placeholders in some of them (`photo = new TL_photoEmpty()`)
        if (!info.isPresent(target)) return PluginWire.encodeNull()
        val value = try {
            info.field.get(target)
        } catch (e: Exception) {
            return PluginWire.encodeError(e.message ?: "reflection get failed")
        }
        val filtered = if (policy.takeover && info.redactedInTakeover) TlFilter.filterFieldValue(target, key, value) else value
        return encodeFieldValue(
            entry,
            filtered,
            info.genericType,
            owner = target,
            ownerField = key,
            // the peer behind it decides redaction one level down (`m.from_id.user_id = 0`), so the child is sealed even when the parent is writable
            sealed = policy.takeover && info.sealedInTakeover,
        )
    }

    private fun setObjectField(entry: HandleEntry, target: TLObject, key: String, source: SetSource): String? {
        val cls = target.javaClass
        if (key == "_") return invalidSet("cannot assign to '_'")
        if (TlFlags.isFlagWord(cls, key)) {
            return invalidSet("'$key' on '${TlNames.classNameToTlName(cls)}' is managed by the bridge - set the optional fields instead")
        }
        // the same refusal a nonexistent field gets: without this the write lands on the app's live object while every read path still reports the field absent
        if (TlFilter.hidesField(policy, cls, key)) {
            return invalidSet("no such field '$key' on '${TlNames.classNameToTlName(cls)}'")
        }
        if (policy.takeover && TlFilter.decidesRedaction(cls, key)) {
            return PluginWire.encodePluginError("forbidden", "'$key' is sealed while api filtering is on: login code redaction is keyed on it")
        }
        val field = TlReflect.publicFields(cls)[key]
            ?: return invalidSet("no such field '$key' on '${TlNames.classNameToTlName(cls)}'")
        val gated = TlFlags.gateOf(cls, key) != null
        val resolved = resolveSetValue(source, field.genericType, field.type, key, allowPrimitiveClear = gated)
        if (resolved.isError) return resolved.error
        return try {
            field.set(target, resolved.value)
            // only this field's bit: the object is live, and its untouched fields may hold placeholders a wholesale recompute would flag
            TlReflect.syncFlagBit(target, key)
            null
        } catch (e: Exception) {
            PluginWire.encodePluginError("internal", e.message ?: "reflection set failed")
        }
    }

    private fun getVectorProp(entry: HandleEntry, target: ArrayList<*>, key: String): String {
        if (key == "length") return PluginWire.encodeInt(target.size.toLong())
        val index = key.toIntOrNull() ?: return PluginWire.encodeError("no such property '$key' on a TL vector")
        if (index < 0 || index >= target.size) return PluginWire.encodeError("vector index out of range: $index")
        return encodeFieldValue(entry, target[index], entry.elementType ?: Any::class.java)
    }

    private fun setVectorProp(entry: HandleEntry, target: ArrayList<Any?>, key: String, source: SetSource): String? {
        if (key == "length") {
            // `vec.length = n` always crosses as a `J`-tagged JSON number, never a raw `I` tag, so `Value.IntNum` alone is unreachable
            val newLength = when (val decoded = (source as? SetSource.Wire)?.decoded) {
                is PluginWire.Value.IntNum -> decoded.value.toInt()
                is PluginWire.Value.Json -> (JSONTokener(decoded.json).nextValue() as? Number)?.toInt()
                else -> null
            } ?: return invalidSet("vector length must be an integer")
            if (newLength < 0 || newLength > target.size) return invalidSet("vector length can only shrink (${target.size} -> $newLength not allowed)")
            while (target.size > newLength) target.removeAt(target.size - 1)
            entry.flagOwner?.let { (obj, name) -> TlReflect.syncFlagBit(obj, name) }
            return null
        }
        val index = key.toIntOrNull() ?: return invalidSet("no such property '$key' on a TL vector")
        if (index < 0 || index > target.size) return invalidSet("vector index out of range: $index")
        val elementType = entry.elementType ?: return PluginWire.encodePluginError("internal", "vector element type is unknown")
        val resolved = resolveSetValue(source, elementType, rawClassOf(elementType), "[$index]")
        if (resolved.isError) return resolved.error
        if (index == target.size) target.add(resolved.value) else target[index] = resolved.value
        entry.flagOwner?.let { (obj, name) -> TlReflect.syncFlagBit(obj, name) }
        return null
    }

    private fun encodeFieldValue(
        entry: HandleEntry,
        value: Any?,
        declaredType: Type,
        owner: TLObject? = null,
        ownerField: String? = null,
        sealed: Boolean = false,
    ): String {
        if (value == null) return PluginWire.encodeNull()
        val readOnly = entry.readOnly || sealed
        return when (value) {
            is Long -> PluginWire.encodeLongAsString(value)
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
                id = register(HandleEntry(value, null, entry.scopeId, readOnly)),
                readOnly = readOnly,
                classId = classIdOf(value.javaClass),
            )
            is ArrayList<*> -> PluginWire.encodeHandle(
                vector = true,
                id = mint(
                    value,
                    elementTypeOf(declaredType),
                    entry.scopeId,
                    readOnly,
                    flagOwner = if (owner != null && ownerField != null) owner to ownerField else null,
                ),
                readOnly = readOnly,
            )
            // map-shaped fields (TLRPC.Message.params) have no handle kind of their own, so they cross as a detached json snapshot
            is Map<*, *>, is SparseArray<*> -> TlJson.valueToJson(value, policy)
                ?.let { PluginWire.encodeJson(it.toString()) }
                ?: PluginWire.encodeNull()
            else -> PluginWire.encodeError("unsupported field type ${value.javaClass}")
        }
    }

    /** [error] is the refusal wire a failed set answers with */
    private class Resolved(val value: Any?, val error: String?) {
        val isError: Boolean get() = error != null
    }

    private fun ok(value: Any?) = Resolved(value, null)
    private fun err(message: String) = Resolved(null, invalidSet(message))
    private fun refused(wire: String) = Resolved(null, wire)

    private fun invalidSet(message: String): String = PluginWire.encodePluginError("invalid-argument", message)

    private fun resolveSetValue(
        source: SetSource,
        genericType: Type,
        rawType: Class<*>,
        path: String,
        allowPrimitiveClear: Boolean = false,
    ): Resolved {
        val decoded = when (source) {
            is SetSource.Bytes -> return if (rawType == ByteArray::class.java) {
                ok(source.value)
            } else {
                err("type mismatch assigning bytes at '$path': expected $rawType")
            }
            is SetSource.Wire -> source.decoded
        }
        return when (decoded) {
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
                val source = table[decoded.id] ?: return refused(PluginWire.encodeExpired())
                val instance = source.target
                if (source.readOnly) {
                    refused(PluginWire.encodePluginError("forbidden", READ_ONLY_MESSAGE))
                } else if (!rawType.isInstance(instance)) {
                    err("type mismatch assigning handle at '$path': expected $rawType, got ${instance.javaClass}")
                } else {
                    ok(instance)
                }
            }
            is PluginWire.Value.Json -> try {
                val parsed = JSONTokener(decoded.json).nextValue()
                ok(TlJson.jsonToValue(genericType, parsed, path))
            } catch (e: Exception) {
                err(e.message ?: "construct failed at '$path'")
            }
            else -> err("unsupported set payload at '$path'")
        }
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

        // a projection is meant to carry what is cheap to carry: a message body or a bio would ride
        // on every handle of a page of them, cross as one string and stay in the view's cache
        // whether or not the plugin ever reads it. Anything longer is left to the lazy read.
        private const val PROJECTION_STRING_LIMIT = 256

        /**
         * A class's fields in the order [TlReflect.fieldInfos] settled them, so rust can name one
         * by index. The id indexes [slotsById], and both sides only ever agree about an id rust was
         * handed on a handle wire.
         */
        private class ClassSlot(
            val id: Int,
            val cls: Class<*>,
            val fields: Array<TlReflect.FieldInfo>,
            val ordinals: Map<String, Int>,
        )

        private val slotsByClass = java.util.concurrent.ConcurrentHashMap<Class<*>, ClassSlot>()

        // append-only, so a read by id never sees a half-built slot. Guarded by [slotLock] on write
        @Volatile
        private var slotsById: Array<ClassSlot> = emptyArray()
        private val slotLock = Any()

        private fun slotOf(cls: Class<*>): ClassSlot = slotsByClass[cls] ?: synchronized(slotLock) {
            slotsByClass.getOrPut(cls) {
                val infos = TlReflect.fieldInfos(cls)
                val ordinals = HashMap<String, Int>(infos.size)
                for ((index, name) in infos.keys.withIndex()) ordinals[name] = index
                val slot = ClassSlot(slotsById.size, cls, infos.values.toTypedArray(), ordinals)
                slotsById += slot
                slot
            }
        }

        private fun slotById(id: Int): ClassSlot? = slotsById.getOrNull(id)

        const val ORDINAL_FALLBACK = -1

        // mirrored in src/native/src/api/tl/proxy.rs
        private const val TAG_NULL = 0.toByte()
        private const val TAG_BOOL = 1.toByte()
        private const val TAG_INT = 2.toByte()
        private const val TAG_LONG = 3.toByte()
        private const val TAG_DOUBLE = 4.toByte()
        private const val TAG_STRING = 5.toByte()
        private const val TAG_BYTES = 6.toByte()
        private const val TAG_HANDLE = 7.toByte()

        private val quotedTypeNames = java.util.concurrent.ConcurrentHashMap<Class<*>, String>()
        private val typeOnlyProjections = java.util.concurrent.ConcurrentHashMap<Class<*>, String>()

        private const val EMPTY_PROJECTION = "{}"

        // the key a projection uses for itself, mirrored in src/native/src/api/tl/proxy.rs: a TL
        // field name is a java identifier, so it cannot collide with one
        private const val TYPE_ENTRY = "\"_\":"

        // must stay byte-identical to READ_ONLY_MESSAGE in src/native/src/tl/proxy.rs:
        // the same refusal is raised on whichever side sees the write first
        const val READ_ONLY_MESSAGE = "this TL view is read-only; take a copy with toJSON() to edit it"

        // one dispatch's chain spans several plugins' tables and every one of them must release
        // the same scope id, so the counter can't live per-instance
        private val nextScopeId = AtomicLong(1)

        fun newScope(): Long = nextScopeId.getAndIncrement()

    }
}
