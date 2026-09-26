package desu.inugram.helpers.plugins.tl

import android.util.Base64
import android.util.SparseArray
import desu.inugram.core.plugins.PluginRefusal
import desu.inugram.core.plugins.PluginWire
import desu.inugram.core.plugins.TlTables
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.TlListener
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.telegram.tgnet.TLObject

/**
 * Plugins can forge the `inu.tl.handle` marker rust reads, so each plugin has its own table.
 * Accessed from the plugin queue and caller-thread JVM/Xposed callbacks. [scopeLock] makes
 * mint/release atomic across [table] and [handlesByScope].
 * Repeated field reads mint fresh handles: caching would need rquickjs `Persistent` roots, which
 * must not outlive the runtime under `panic = "abort"`.
 */
class TlHandles(val policy: TlFilter.Policy) : TlListener {
    private class HandleEntry(
        val target: Any,
        val elementType: Type?,
        val scopeId: Long,
        val readOnly: Boolean,
        // an invokeRpc response whose freeResources() stock skipped so this table owns it
        val owned: Boolean = false,
        // set on vectors minted for a TLObject field, so push/length= can resync the owner's flag bit
        val flagOwner: Pair<TLObject, String>? = null,
        val int53: Boolean = false,
    )

    private val onHost = EngineDispatch.createHostDispatcher()
    private val nextHandle = AtomicLong(1)
    private val table = ConcurrentHashMap<Long, HandleEntry>()

    private val scopeLock = Any()

    // guarded by [scopeLock]
    private val handlesByScope = HashMap<Long, MutableList<Long>>()

    fun mintForScope(target: Any, scopeId: Long): Long =
        mint(target, elementType = null, scopeId = scopeId, readOnly = false)

    fun mintForPlugin(target: Any, readOnly: Boolean, owned: Boolean = false): Long =
        mint(target, elementType = null, scopeId = PLUGIN_SCOPE, readOnly = readOnly, owned = owned)

    /** scalars go too because rust caches them on a plugin-lifetime view */
    fun mintWireForPlugin(target: TLObject, readOnly: Boolean, owned: Boolean = false, fields: List<String>? = null): String {
        val id = mintForPlugin(target, readOnly, owned)
        return PluginWire.encodeHandle(vector = false, id = id, readOnly = readOnly, projection = project(id, fields), classId = getClassId(target.javaClass))
    }

    /** a dispatch view caches nothing, so it gets no scalars */
    fun mintWireForScope(target: TLObject, scopeId: Long): String =
        PluginWire.encodeHandle(vector = false, id = mintForScope(target, scopeId), readOnly = false, classId = getClassId(target.javaClass))

    private fun mint(
        target: Any,
        elementType: Type?,
        scopeId: Long,
        readOnly: Boolean,
        flagOwner: Pair<TLObject, String>? = null,
        owned: Boolean = false,
        int53: Boolean = false,
    ): Long = register(HandleEntry(target, elementType, scopeId, readOnly, owned, flagOwner, int53))

    /** a read already in progress may still mint after release; that handle retains its target until [releaseAll] */
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
        // every freeResources() override early-returns while this is set
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

    /** a read-only handle is refused unless [allowReadOnly]: consuming it would alias an app-owned object */
    fun objectFromWire(wire: String, allowReadOnly: Boolean = false): TLObject = when (val decoded = PluginWire.decode(wire)) {
        is PluginWire.Value.Handle -> {
            if (!allowReadOnly && isReadOnly(decoded.id)) PluginWire.refuse("forbidden", READ_ONLY_MESSAGE)
            resolveTlObject(decoded.id) ?: PluginWire.refuse("handle-expired", PluginWire.HANDLE_EXPIRED_MESSAGE)
        }
        is PluginWire.Value.Json -> constructTlObject(JSONObject(decoded.json))
        else -> PluginWire.refuse("invalid-argument", "expected a TL object")
    }

    fun constructTlObject(json: JSONObject): TLObject {
        val tlName = json.optString("_", "")
        if (tlName.isEmpty()) PluginWire.refuse("invalid-argument", "a constructed TL object needs a '_' type name")
        if (TlTables.getConstructorIds(tlName) == null) PluginWire.refuse("unknown-constructor", "unknown TL type '$tlName'")
        return try {
            TlJson.fromJson(json)
        } catch (e: PluginRefusal) {
            throw e
        } catch (e: Exception) {
            PluginWire.refuse("invalid-argument", e.message ?: e.toString())
        }
    }

    /** consuming an app-owned object as a request or field value would alias it into a writable graph */
    fun isReadOnly(handle: Long): Boolean = table[handle]?.readOnly ?: false

    override fun tlGet(handle: Long, key: String): String {
        val entry = table[handle] ?: return PluginWire.encodeExpired()
        return when (val target = entry.target) {
            is TLObject -> getObjectField(entry, target, key)
            is ArrayList<*> -> getVectorProp(entry, target, key)
            else -> PluginWire.encodeError("internal: unsupported handle target ${target.javaClass}")
        }
    }

    /** [ORDINAL_FALLBACK] makes rust re-read through [tlGet], so a refusal is a slow read, never a wrong one */
    override fun readField(handle: Long, classId: Int, ordinal: Int, out: ByteBuffer): Int {
        val entry = table[handle] ?: return ORDINAL_FALLBACK
        val target = entry.target as? TLObject ?: return ORDINAL_FALLBACK
        // the class must be the handle's, or a forged handle turns an ordinal for one class into a read of another's field
        val slot = slotsById.getOrNull(classId) ?: return ORDINAL_FALLBACK
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
        slotsById.getOrNull(classId)?.ordinals?.get(key) ?: ORDINAL_FALLBACK

    private fun writeField(out: ByteBuffer, entry: HandleEntry, target: TLObject, info: TlReflect.FieldInfo): Boolean {
        if (!isVisibleField(target, info)) {
            out.put(TAG_NULL)
            return true
        }
        when (info.kind) {
            TlReflect.KIND_LONG -> {
                out.put(if (info.isInt53) TAG_INT53 else TAG_LONG).putLong(info.field.getLong(target))
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
        val value = redactValue(target, info, raw)
        val readOnly = entry.readOnly || (policy.takeover && info.sealedInTakeover)
        when (value) {
            null -> out.put(TAG_NULL)
            is String -> putString(out.put(TAG_STRING), value)
            is Long -> out.put(if (info.isInt53) TAG_INT53 else TAG_LONG).putLong(value)
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
                classId = getClassId(value.javaClass),
            )
            is ArrayList<*> -> putHandle(
                out,
                vector = true,
                id = mint(
                    value,
                    getElementType(info.genericType),
                    entry.scopeId,
                    readOnly,
                    flagOwner = target to info.field.name,
                    int53 = info.isInt53,
                ),
                readOnly = readOnly,
                classId = PluginWire.NO_CLASS,
            )
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

    fun getClassId(cls: Class<*>): Int = getClassSlot(cls).id

    override fun tlSet(handle: Long, key: String, valueWire: String): String? =
        applySet(handle, key, SetSource.Wire(PluginWire.decode(valueWire)))

    override fun tlSetBytes(handle: Long, key: String, value: ByteArray): String? =
        applySet(handle, key, SetSource.Bytes(value))

    /** bytes arrive beside the wire to skip a base64 round trip, the mirror of `TAG_BYTES` on the read side */
    private sealed class SetSource {
        class Wire(val decoded: PluginWire.Value) : SetSource()
        class Bytes(val value: ByteArray) : SetSource()
    }

    private fun applySet(handle: Long, key: String, source: SetSource): String? {
        val entry = table[handle] ?: return PluginWire.encodeExpired()
        // rust refuses too; this side owns the mode, so a forged wire can't write either
        if (entry.readOnly) return PluginWire.encodePluginError("forbidden", READ_ONLY_MESSAGE)
        @Suppress("UNCHECKED_CAST")
        return try {
            when (val target = entry.target) {
                is TLObject -> setObjectField(entry, target, key, source)
                is ArrayList<*> -> setVectorProp(entry, target as ArrayList<Any?>, key, source)
                else -> PluginWire.encodePluginError("internal", "unsupported handle target ${target.javaClass}")
            }
        } catch (e: Exception) {
            // rust re-emits whatever string the plugin hangs off the marker symbol, so a malformed wire is plugin input
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

    /** `in` and `Object.keys` must agree with reads */
    private fun isVisibleField(target: TLObject, key: String): Boolean {
        val info = TlReflect.fieldInfos(target.javaClass)[key] ?: return false
        return isVisibleField(target, info)
    }

    /** stock parks placeholders in unset slots (`photo = new TL_photoEmpty()`), so a clear bit means absent */
    private fun isVisibleField(target: TLObject, info: TlReflect.FieldInfo): Boolean =
        !info.isFlagWord && !TlFilter.hidesField(policy, info) && info.isPresent(target)

    /** recomputed live on every read, never cached */
    private fun redactValue(target: TLObject, info: TlReflect.FieldInfo, value: Any?): Any? =
        if (policy.takeover && info.redactedInTakeover) TlFilter.filterFieldValue(target, info.field.name, value) else value

    /**
     * Fully scalar objects ([TlReflect.isFullyScalar]) go whole, others only their type: reflecting a
     * parent's fields costs more than the saved crossing (Pixel 9: 7.1 µs per read vs 31 µs for a dialog's 22 fields).
     */
    fun project(handle: Long, fields: List<String>? = null): String {
        val entry = table[handle] ?: return EMPTY_PROJECTION
        val target = entry.target as? TLObject ?: return EMPTY_PROJECTION
        val cls = target.javaClass
        val infos = TlReflect.fieldInfos(cls)
        val picked = when {
            fields == null -> if (TlReflect.isFullyScalar(cls)) infos.values else return getTypeOnlyProjection(cls)
            else -> fields.mapNotNull { infos[it] }.ifEmpty { return getTypeOnlyProjection(cls) }
        }
        return StringBuilder(96).also { appendProjection(it, target, picked) }.toString()
    }

    private fun appendProjection(out: StringBuilder, target: TLObject, infos: Collection<TlReflect.FieldInfo>) {
        out.append('{').append(TYPE_ENTRY).append(quoteTypeName(target.javaClass))
        for (info in infos) {
            if (info.isFlagWord || TlFilter.hidesField(policy, info)) continue
            val filtered = redactValue(target, info, if (info.isPresent(target)) info.field.get(target) else null)
            val start = out.length
            out.append(',').append(info.quotedName).append(':')
            val wrote = when (filtered) {
                null -> out.append("null").let { true }
                // JSON has no number that holds a long
                is Long -> if (info.isInt53) out.append(filtered).let { true } else out.append('"').append(filtered).append('"').let { true }
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

    private fun quoteTypeName(cls: Class<*>): String =
        quotedTypeNames.getOrPut(cls) { JSONObject.quote(TlNames.classNameToTlName(cls)) }

    private fun getTypeOnlyProjection(cls: Class<*>): String = typeOnlyProjections.getOrPut(cls) { "{$TYPE_ENTRY${quoteTypeName(cls)}}" }

    override fun tlCopy(handle: Long): String? {
        val entry = table[handle] ?: return null
        return when (val target = entry.target) {
            is TLObject -> TlJson.toJson(target, policy).toString()
            is ArrayList<*> -> {
                val arr = JSONArray()
                for (item in target) if (item != null) TlJson.valueToJson(item, policy, entry.int53)?.let { arr.put(it) }
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
        val info = TlReflect.fieldInfos(cls)[key]
            ?: return PluginWire.encodeError("no such field '$key' on '${TlNames.classNameToTlName(cls)}'")
        if (!isVisibleField(target, info)) return PluginWire.encodeNull()
        val value = try {
            info.field.get(target)
        } catch (e: Exception) {
            return PluginWire.encodeError(e.message ?: "reflection get failed")
        }
        return encodeFieldValue(
            entry,
            redactValue(target, info, value),
            info.genericType,
            owner = target,
            ownerField = key,
            // the peer decides redaction one level down (`m.from_id.user_id = 0`), so the child is sealed even under a writable parent
            sealed = policy.takeover && info.sealedInTakeover,
            int53 = info.isInt53,
        )
    }

    private fun setObjectField(entry: HandleEntry, target: TLObject, key: String, source: SetSource): String? {
        val cls = target.javaClass
        if (key == "_") return PluginWire.encodePluginError("invalid-argument", "cannot assign to '_'")
        if (TlFlags.isFlagWord(cls, key)) {
            return PluginWire.encodePluginError("invalid-argument", "'$key' on '${TlNames.classNameToTlName(cls)}' is managed by the bridge - set the optional fields instead")
        }
        val info = TlReflect.fieldInfos(cls)[key]
            ?: return PluginWire.encodePluginError("invalid-argument", "no such field '$key' on '${TlNames.classNameToTlName(cls)}'")
        // otherwise the write lands on the live object while every read reports the field absent
        if (TlFilter.hidesField(policy, info)) {
            return PluginWire.encodePluginError("invalid-argument", "no such field '$key' on '${TlNames.classNameToTlName(cls)}'")
        }
        if (policy.takeover && info.sealedInTakeover) {
            return PluginWire.encodePluginError("forbidden", "'$key' is sealed while api filtering is on: login code redaction is keyed on it")
        }
        val resolved = resolveSetValue(source, info.genericType, info.type, key, allowPrimitiveClear = info.gate != null)
        if (resolved.isError) return resolved.error
        return try {
            info.field.set(target, resolved.value)
            // only this bit: untouched fields of a live object may hold placeholders a full recompute would flag
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
        return encodeFieldValue(entry, target[index], entry.elementType ?: Any::class.java, int53 = entry.int53)
    }

    private fun setVectorProp(entry: HandleEntry, target: ArrayList<Any?>, key: String, source: SetSource): String? {
        if (key == "length") {
            // `vec.length = n` always crosses as a `J` number, never `I`
            val newLength = when (val decoded = (source as? SetSource.Wire)?.decoded) {
                is PluginWire.Value.IntNum -> decoded.value.toInt()
                is PluginWire.Value.Json -> (JSONTokener(decoded.json).nextValue() as? Number)?.toInt()
                else -> null
            } ?: return PluginWire.encodePluginError("invalid-argument", "vector length must be an integer")
            if (newLength < 0 || newLength > target.size) return PluginWire.encodePluginError("invalid-argument", "vector length can only shrink (${target.size} -> $newLength not allowed)")
            while (target.size > newLength) target.removeAt(target.size - 1)
            entry.flagOwner?.let { (obj, name) -> TlReflect.syncFlagBit(obj, name) }
            return null
        }
        val index = key.toIntOrNull() ?: return PluginWire.encodePluginError("invalid-argument", "no such property '$key' on a TL vector")
        if (index < 0 || index > target.size) return PluginWire.encodePluginError("invalid-argument", "vector index out of range: $index")
        val elementType = entry.elementType ?: return PluginWire.encodePluginError("internal", "vector element type is unknown")
        val resolved = resolveSetValue(source, elementType, getRawClass(elementType), "[$index]")
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
        int53: Boolean = false,
    ): String {
        if (value == null) return PluginWire.encodeNull()
        val readOnly = entry.readOnly || sealed
        return when (value) {
            is Long -> if (int53) PluginWire.encodeInt(value) else PluginWire.encodeLongAsString(value)
            is Int -> PluginWire.encodeInt(value.toLong())
            is Short -> PluginWire.encodeInt(value.toLong())
            is Byte -> PluginWire.encodeInt(value.toLong())
            is Double -> PluginWire.encodeDouble(value)
            is Float -> PluginWire.encodeDouble(value.toDouble())
            is Boolean -> PluginWire.encodeBool(value)
            is String -> PluginWire.encodeString(value)
            is ByteArray -> PluginWire.encodeBytes(Base64.encodeToString(value, Base64.NO_WRAP))
            // rust infers a view's lifetime from its entry point, not the wire, so a child must inherit its
            // parent's scope, and its mode unless [sealed] tightens it
            is TLObject -> PluginWire.encodeHandle(
                vector = false,
                id = register(HandleEntry(value, null, entry.scopeId, readOnly)),
                readOnly = readOnly,
                classId = getClassId(value.javaClass),
            )
            is ArrayList<*> -> PluginWire.encodeHandle(
                vector = true,
                id = mint(
                    value,
                    getElementType(declaredType),
                    entry.scopeId,
                    readOnly,
                    flagOwner = if (owner != null && ownerField != null) owner to ownerField else null,
                    int53 = int53,
                ),
                readOnly = readOnly,
            )
            // map-shaped fields (TLRPC.Message.params) have no handle kind
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
    private fun err(message: String) = Resolved(null, PluginWire.encodePluginError("invalid-argument", message))
    private fun refused(wire: String) = Resolved(null, wire)


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
                    // null means absent, indistinguishable from zero for a primitive slot
                    ok(getZeroValue(rawType))
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

    private fun getZeroValue(rawType: Class<*>): Any = when (rawType) {
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Double.TYPE -> 0.0
        java.lang.Float.TYPE -> 0f
        java.lang.Boolean.TYPE -> false
        else -> error("no zero value for $rawType")
    }

    private fun getElementType(type: Type): Type? {
        if (type !is ParameterizedType) return null
        if (type.rawType != ArrayList::class.java) return null
        return type.actualTypeArguments[0]
    }

    private fun getRawClass(type: Type): Class<*> = when (type) {
        is Class<*> -> type
        is ParameterizedType -> getRawClass(type.rawType)
        else -> Any::class.java
    }

    companion object {
        private const val PLUGIN_SCOPE = 0L

        // a message body or bio would otherwise ride on every handle of a page and stay in the view's cache
        private const val PROJECTION_STRING_LIMIT = 256

        /** rust names fields by index into [TlReflect.fieldInfos] order; ids are only valid once sent on a handle wire */
        private class ClassSlot(
            val id: Int,
            val cls: Class<*>,
            val fields: Array<TlReflect.FieldInfo>,
            val ordinals: Map<String, Int>,
        )

        private val slotsByClass = java.util.concurrent.ConcurrentHashMap<Class<*>, ClassSlot>()

        // append-only, so a read by id never sees a half-built slot. writes under [slotLock]
        @Volatile
        private var slotsById: Array<ClassSlot> = emptyArray()
        private val slotLock = Any()

        private fun getClassSlot(cls: Class<*>): ClassSlot = slotsByClass[cls] ?: synchronized(slotLock) {
            slotsByClass.getOrPut(cls) {
                val infos = TlReflect.fieldInfos(cls)
                val ordinals = HashMap<String, Int>(infos.size)
                for ((index, name) in infos.keys.withIndex()) ordinals[name] = index
                val slot = ClassSlot(slotsById.size, cls, infos.values.toTypedArray(), ordinals)
                slotsById += slot
                slot
            }
        }

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
        private const val TAG_INT53 = 9.toByte()

        private val quotedTypeNames = java.util.concurrent.ConcurrentHashMap<Class<*>, String>()
        private val typeOnlyProjections = java.util.concurrent.ConcurrentHashMap<Class<*>, String>()

        private const val EMPTY_PROJECTION = "{}"

        // mirrored in src/native/src/api/tl/proxy.rs. a TL field name is a java identifier, so it cannot collide
        private const val TYPE_ENTRY = "\"_\":"

        // byte-identical to READ_ONLY_MESSAGE in src/native/src/api/tl/proxy.rs
        const val READ_ONLY_MESSAGE = "this TL view is read-only; take a copy with structuredClone() to edit it"

        // one chain spans several plugins' tables that all release the same scope id
        private val nextScopeId = AtomicLong(1)

        fun newScope(): Long = nextScopeId.getAndIncrement()
    }
}
