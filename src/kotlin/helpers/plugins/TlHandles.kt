package desu.inugram.helpers.plugins

import android.util.Base64
import desu.inugram.core.plugins.TlNames
import desu.inugram.core.plugins.TlWire
import org.json.JSONArray
import org.json.JSONTokener
import org.telegram.tgnet.TLObject
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * Handle table backing the live-proxy `inu.interceptRpc`/`inu.invokeRpc` bridge (rust side:
 * src/rust/inu_native/src/tl_proxy.rs). A handle is a `Long -> (TLObject | ArrayList<Any?>)`
 * mapping; JS never sees the real object, only a lazy `Proxy` wrapping the handle id, whose
 * get/set/has/deleteProperty traps round-trip through [QuickJs.TlListener] (implemented here)
 * to reflect on the *real* instance - no eager snapshot of the object graph.
 *
 * Touched ONLY on [org.telegram.messenger.Utilities.globalQueue] - the same thread every engine
 * runs on, and JNI upcalls from JS traps execute synchronously on that thread - so the table
 * needs no locking, exactly like [PluginRpc]'s own bookkeeping.
 *
 * Deliberately does NOT cache minted child handles per (parent, field): a cache would need to
 * hold an rquickjs `Persistent` (a GC root) to keep the cached child proxy reachable across
 * repeated accesses - a leak-prone complication for a lookup that is already O(1) per access.
 * Minting a fresh (no graph traversal) handle on every field access is cheap and side-steps it.
 *
 * Handle lifetime: every handle belongs to an intercept-dispatch [scopeId] (one per top-level
 * [PluginRpc.maybeIntercept] dispatch) and is hard-invalidated in bulk via [releaseScope] once
 * that dispatch settles - regardless of whether JS still references it (proxies "stashed across
 * await" throw on next access, per spec). The rust-side GC finalizer (`HandleBox`'s `Drop` ->
 * [tlRelease]) additionally frees a handle early if its proxy becomes unreachable before the
 * dispatch settles, and is a harmless no-op after [releaseScope] already dropped the entry.
 * invokeRpc responses don't go through this table at all - they resolve as detached `J` snapshots
 * (see [PluginRpc]'s `encodeInvokeResult`).
 */
object TlHandles : QuickJs.TlListener {
    private class HandleEntry(
        val target: Any,
        val elementType: Type?,
        val scopeId: Long,
    )

    private var nextHandle = 1L
    private val table = HashMap<Long, HandleEntry>()
    private var nextScopeId = 1L

    // -- lifecycle --

    fun newScope(): Long = nextScopeId++

    /** mints a handle for a TLObject or ArrayList<Any?> owned by an intercept-dispatch scope */
    fun mintForScope(target: Any, scopeId: Long): Long = mint(target, elementType = null, scopeId = scopeId)

    private fun mint(target: Any, elementType: Type?, scopeId: Long): Long {
        val handle = nextHandle++
        table[handle] = HandleEntry(target, elementType, scopeId)
        return handle
    }

    /** bulk hard-invalidate every handle minted under [scopeId] - called once a dispatch settles */
    fun releaseScope(scopeId: Long) {
        if (table.isEmpty()) return
        table.entries.removeAll { it.value.scopeId == scopeId }
    }

    fun resolveTlObject(handle: Long): TLObject? = table[handle]?.target as? TLObject

    // -- QuickJs.TlListener --

    override fun tlGet(handle: Long, key: String): String {
        val entry = table[handle] ?: return TlWire.encodeExpired()
        return when (val target = entry.target) {
            is TLObject -> getObjectField(entry, target, key)
            is ArrayList<*> -> getVectorProp(entry, target, key)
            else -> TlWire.encodeError("internal: unsupported handle target ${target.javaClass}")
        }
    }

    override fun tlSet(handle: Long, key: String, wire: String): String? {
        val entry = table[handle] ?: return TlWire.HANDLE_EXPIRED_MESSAGE
        @Suppress("UNCHECKED_CAST")
        return when (val target = entry.target) {
            is TLObject -> setObjectField(target, key, wire)
            is ArrayList<*> -> setVectorProp(entry, target as ArrayList<Any?>, key, wire)
            else -> "internal: unsupported handle target ${target.javaClass}"
        }
    }

    override fun tlHas(handle: Long, key: String): Int {
        val entry = table[handle] ?: return -1
        val present = when (val target = entry.target) {
            is TLObject -> key == "_" || TlJson.publicFields(target.javaClass).containsKey(key)
            is ArrayList<*> -> key == "length" || (key.toIntOrNull()?.let { it in 0 until target.size } ?: false)
            else -> false
        }
        return if (present) 1 else 0
    }

    override fun tlOwnKeys(handle: Long): String? {
        val target = table[handle]?.target as? TLObject ?: return null
        return (sequenceOf("_") + TlJson.publicFields(target.javaClass).keys).joinToString(",")
    }

    override fun tlCopy(handle: Long): String? {
        val entry = table[handle] ?: return null
        return when (val target = entry.target) {
            is TLObject -> TlJson.toJson(target).toString()
            is ArrayList<*> -> {
                val arr = JSONArray()
                for (item in target) if (item != null) arr.put(TlJson.valueToJson(item))
                arr.toString()
            }
            else -> null
        }
    }

    override fun tlRelease(handle: Long) {
        table.remove(handle)
    }

    // -- object field get/set --

    private fun getObjectField(entry: HandleEntry, target: TLObject, key: String): String {
        if (key == "_") return TlWire.encodeString(TlNames.classNameToTlName(target.javaClass.simpleName))
        val field = TlJson.publicFields(target.javaClass)[key]
            ?: return TlWire.encodeError("no such field '$key' on '${TlNames.classNameToTlName(target.javaClass.simpleName)}'")
        val value = try {
            field.get(target)
        } catch (e: Exception) {
            return TlWire.encodeError(e.message ?: "reflection get failed")
        }
        return encodeFieldValue(entry, value, field.genericType)
    }

    private fun setObjectField(target: TLObject, key: String, wire: String): String? {
        if (key == "_") return "cannot assign to '_'"
        val field = TlJson.publicFields(target.javaClass)[key]
            ?: return "no such field '$key' on '${TlNames.classNameToTlName(target.javaClass.simpleName)}'"
        val resolved = resolveSetValue(TlWire.decode(wire), field.genericType, field.type, key)
        if (resolved.isError) return resolved.error
        return try {
            field.set(target, resolved.value)
            null
        } catch (e: Exception) {
            e.message ?: "reflection set failed"
        }
    }

    // -- vector get/set (length, indexed, push-via-index==size) --

    private fun getVectorProp(entry: HandleEntry, target: ArrayList<*>, key: String): String {
        if (key == "length") return TlWire.encodeInt(target.size.toLong())
        val index = key.toIntOrNull() ?: return TlWire.encodeError("no such property '$key' on a TL vector")
        if (index < 0 || index >= target.size) return TlWire.encodeError("vector index out of range: $index")
        return encodeFieldValue(entry, target[index], entry.elementType ?: Any::class.java)
    }

    private fun setVectorProp(entry: HandleEntry, target: ArrayList<Any?>, key: String, wire: String): String? {
        if (key == "length") {
            // JS `vec.length = n` always crosses as a `J`-tagged JSON number (see js_value_to_wire -
            // outbound scalar sets are JSON-wrapped so Kotlin can coerce against the target field's
            // declared type elsewhere), never a raw `I` tag - so `Value.IntNum` alone is unreachable.
            val decoded = TlWire.decode(wire)
            val newLength = when (decoded) {
                is TlWire.Value.IntNum -> decoded.value.toInt()
                is TlWire.Value.Json -> (JSONTokener(decoded.json).nextValue() as? Number)?.toInt()
                else -> null
            } ?: return "vector length must be an integer"
            if (newLength < 0 || newLength > target.size) return "vector length can only shrink (${target.size} -> $newLength not allowed)"
            while (target.size > newLength) target.removeAt(target.size - 1)
            return null
        }
        val index = key.toIntOrNull() ?: return "no such property '$key' on a TL vector"
        if (index < 0 || index > target.size) return "vector index out of range: $index"
        val elementType = entry.elementType ?: return "vector element type is unknown"
        val resolved = resolveSetValue(TlWire.decode(wire), elementType, rawClassOf(elementType), "[$index]")
        if (resolved.isError) return resolved.error
        if (index == target.size) target.add(resolved.value) else target[index] = resolved.value
        return null
    }

    // -- shared value codecs --

    private fun encodeFieldValue(entry: HandleEntry, value: Any?, declaredType: Type): String {
        if (value == null) return TlWire.encodeNull()
        return when (value) {
            is Long -> TlWire.encodeString(value.toString())
            is Int -> TlWire.encodeInt(value.toLong())
            is Short -> TlWire.encodeInt(value.toLong())
            is Byte -> TlWire.encodeInt(value.toLong())
            is Double -> TlWire.encodeDouble(value)
            is Float -> TlWire.encodeDouble(value.toDouble())
            is Boolean -> TlWire.encodeBool(value)
            is String -> TlWire.encodeString(value)
            is ByteArray -> TlWire.encodeBytes(Base64.encodeToString(value, Base64.NO_WRAP))
            is TLObject -> TlWire.encodeHandle(vector = false, id = mint(value, null, entry.scopeId))
            is ArrayList<*> -> {
                val elementType = elementTypeOf(declaredType)
                TlWire.encodeHandle(vector = true, id = mint(value, elementType, entry.scopeId))
            }
            else -> TlWire.encodeError("unsupported field type ${value.javaClass}")
        }
    }

    private class Resolved(val value: Any?, val error: String?) {
        val isError: Boolean get() = error != null
    }

    private fun ok(value: Any?) = Resolved(value, null)
    private fun err(message: String) = Resolved(null, message)

    private fun resolveSetValue(decoded: TlWire.Value, genericType: Type, rawType: Class<*>, path: String): Resolved =
        when (decoded) {
            is TlWire.Value.Null -> {
                if (rawType.isPrimitive) err("cannot clear primitive field at '$path'") else ok(null)
            }
            is TlWire.Value.Bytes -> {
                if (rawType == ByteArray::class.java) {
                    ok(Base64.decode(decoded.base64, Base64.NO_WRAP))
                } else {
                    err("type mismatch assigning bytes at '$path': expected $rawType")
                }
            }
            is TlWire.Value.Handle -> {
                val instance = table[decoded.id]?.target ?: return err(TlWire.HANDLE_EXPIRED_MESSAGE)
                if (!rawType.isInstance(instance)) {
                    err("type mismatch assigning handle at '$path': expected $rawType, got ${instance.javaClass}")
                } else {
                    ok(instance)
                }
            }
            is TlWire.Value.Json -> try {
                val parsed = JSONTokener(decoded.json).nextValue()
                ok(TlJson.jsonToValue(genericType, parsed, path))
            } catch (e: Exception) {
                err(e.message ?: "construct failed at '$path'")
            }
            else -> err("unsupported set payload at '$path'")
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
}
