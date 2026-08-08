package desu.inugram.helpers.plugins.tl

import android.util.Base64
import android.util.SparseArray
import desu.inugram.core.plugins.DeserializeGuards
import desu.inugram.core.plugins.TlFlags
import desu.inugram.core.plugins.TlNames
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.tgnet.TLObject

/**
 * JSON <-> TLObject, over the reflection [TlReflect] owns: constructing a real TLObject from a
 * plugin-authored `{_: "...", ...}` literal (reused by [TlHandles] for single-field coercion), and
 * `obj.toJSON()` snapshots. The live get/set path is [TlHandles]'s and does not come through here.
 *
 * Caveats (mirrored in src/plugins/common.d.ts):
 * - `long` fields are exposed as JSON strings to avoid losing int64 precision in JS numbers.
 * - stock annotates TL classes with non-wire `//custom` fields (`Message.dialog_id`, `attachPath`,
 *   `voiceTranscription`, ...); reflection can't tell them apart from wire fields, so they ride
 *   along in snapshots. `params`/`pollMediaAttachPaths` are the two whose types aren't TL-shaped;
 *   they map to JSON objects. Field types with no JSON mapping at all are skipped rather than
 *   throwing, so one exotic field can't sink a whole snapshot.
 */
object TlJson {
    /**
     * wraps a byte-array value in snapshot JSON, revived into a Uint8Array by rust
     * (`tl_proxy::json_parse_tl`). Collision-safe: TL field names are Java identifiers.
     */
    const val BYTES_KEY = "\$inuBytes"

    fun toJson(obj: TLObject, policy: TlFilter.Policy): JSONObject {
        val cls = obj.javaClass
        val json = JSONObject()
        json.put("_", TlNames.classNameToTlName(cls))
        for ((name, field) in TlReflect.publicFields(cls)) {
            if (TlFlags.isFlagWord(cls, name)) continue
            if (TlFilter.hidesField(policy, cls, name)) continue
            val gate = TlFlags.gateOf(cls, name)
            if (gate != null && !TlReflect.isBitSet(obj, cls, gate)) continue
            val raw = field.get(obj) ?: continue
            val value = (if (policy.takeover) TlFilter.filterFieldValue(obj, name, raw) else raw) ?: continue
            json.put(name, valueToJson(value, policy) ?: continue)
        }
        return json
    }

    internal fun valueToJson(value: Any, policy: TlFilter.Policy): Any? = when (value) {
        is Long -> value.toString()
        is Int, is Short, is Byte, is Double, is Float, is Boolean, is String -> value
        is ByteArray -> JSONObject().put(BYTES_KEY, Base64.encodeToString(value, Base64.NO_WRAP))
        is TLObject -> toJson(value, policy)
        is ArrayList<*> -> {
            val arr = JSONArray()
            for (item in value) if (item != null) valueToJson(item, policy)?.let { arr.put(it) }
            arr
        }
        is Map<*, *> -> {
            val out = JSONObject()
            for ((k, v) in value) {
                if (k !is String || v == null) continue
                valueToJson(v, policy)?.let { out.put(k, it) }
            }
            out
        }
        is SparseArray<*> -> {
            val out = JSONObject()
            for (i in 0 until value.size()) {
                val v = value.valueAt(i) ?: continue
                valueToJson(v, policy)?.let { out.put(value.keyAt(i).toString(), it) }
            }
            out
        }
        else -> null
    }

    /**
     * The first [DeserializeGuards]-protected field a construct payload assigns, at any depth, or
     * null. The guard is a rule about which slot a rewrite may land in, so it has to be applied to
     * the value and not only to the name the value is assigned to: `d.peer.user_id = x` and
     * `d.peer = { _: 'peerUser', user_id: x }` write the same slot, and only the first of them
     * names a protected field.
     */
    internal fun findProtectedField(value: Any?): String? = when (value) {
        is JSONObject -> {
            var found: String? = null
            val keys = value.keys()
            while (found == null && keys.hasNext()) {
                val key = keys.next()
                found = if (DeserializeGuards.isProtectedField(key)) key else findProtectedField(value.get(key))
            }
            found
        }
        is JSONArray -> {
            var found: String? = null
            var index = 0
            while (found == null && index < value.length()) {
                found = findProtectedField(value.get(index))
                index++
            }
            found
        }
        else -> null
    }

    fun fromJson(json: JSONObject): TLObject {
        val tlName = json.optString("_", "")
        if (tlName.isEmpty()) throw IllegalArgumentException("TlJson.fromJson: missing '_' type name")
        val cls = TlReflect.classOf(tlName)
            ?: throw IllegalArgumentException("TlJson.fromJson: unknown TL type '$tlName'")
        val instance = try {
            cls.getDeclaredConstructor().newInstance()
        } catch (e: Exception) {
            throw IllegalArgumentException("TlJson.fromJson: cannot instantiate '$tlName': ${e.message}", e)
        }
        val fields = TlReflect.publicFields(cls)
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "_") continue
            if (TlFlags.isFlagWord(cls, key)) {
                throw IllegalArgumentException(
                    "TlJson.fromJson: '$key' on '$tlName' is managed by the bridge - set the optional fields instead",
                )
            }
            val field = fields[key]
                ?: throw IllegalArgumentException("TlJson.fromJson: unknown field '$key' on '$tlName'")
            val jsonValue = json.get(key)
            val converted = jsonToValue(field.genericType, jsonValue, "$tlName.$key")
            field.set(instance, converted)
        }
        TlReflect.syncFlags(instance)
        return instance
    }

    internal fun jsonToValue(type: Type, jsonValue: Any, path: String): Any {
        if (jsonValue == JSONObject.NULL) throw IllegalArgumentException("TlJson.fromJson: null value at '$path'")
        return when (type) {
            java.lang.Long.TYPE, java.lang.Long::class.java -> when (jsonValue) {
                is String -> jsonValue.toLong()
                is Number -> jsonValue.toLong()
                else -> throw IllegalArgumentException("TlJson.fromJson: expected long at '$path'")
            }
            Integer.TYPE, Integer::class.java ->
                (jsonValue as? Number)?.toInt()
                    ?: throw IllegalArgumentException("TlJson.fromJson: expected int at '$path'")
            // reflective Field.set on a primitive field requires the exactly-matching boxed width
            java.lang.Short.TYPE ->
                (jsonValue as? Number)?.toShort()
                    ?: throw IllegalArgumentException("TlJson.fromJson: expected int at '$path'")
            java.lang.Byte.TYPE ->
                (jsonValue as? Number)?.toByte()
                    ?: throw IllegalArgumentException("TlJson.fromJson: expected int at '$path'")
            java.lang.Double.TYPE, java.lang.Double::class.java ->
                (jsonValue as? Number)?.toDouble()
                    ?: throw IllegalArgumentException("TlJson.fromJson: expected double at '$path'")
            java.lang.Float.TYPE, java.lang.Float::class.java ->
                (jsonValue as? Number)?.toFloat()
                    ?: throw IllegalArgumentException("TlJson.fromJson: expected float at '$path'")
            java.lang.Boolean.TYPE, java.lang.Boolean::class.java ->
                jsonValue as? Boolean
                    ?: throw IllegalArgumentException("TlJson.fromJson: expected boolean at '$path'")
            String::class.java ->
                jsonValue as? String
                    ?: throw IllegalArgumentException("TlJson.fromJson: expected string at '$path'")
            ByteArray::class.java -> when {
                jsonValue is String -> Base64.decode(jsonValue, Base64.NO_WRAP)
                jsonValue is JSONObject && jsonValue.has(BYTES_KEY) -> Base64.decode(jsonValue.getString(BYTES_KEY), Base64.NO_WRAP)
                else -> throw IllegalArgumentException("TlJson.fromJson: expected bytes (base64 string or {$BYTES_KEY}) at '$path'")
            }
            is ParameterizedType -> when (type.rawType) {
                ArrayList::class.java -> {
                    val elementType = type.actualTypeArguments[0]
                    val arr = jsonValue as? JSONArray
                        ?: throw IllegalArgumentException("TlJson.fromJson: expected array at '$path'")
                    val list = ArrayList<Any>(arr.length())
                    for (i in 0 until arr.length()) {
                        list.add(jsonToValue(elementType, arr.get(i), "$path[$i]"))
                    }
                    list
                }
                HashMap::class.java -> {
                    val valueType = type.actualTypeArguments[1]
                    val obj = jsonValue as? JSONObject
                        ?: throw IllegalArgumentException("TlJson.fromJson: expected object at '$path'")
                    val map = HashMap<String, Any>()
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        map[key] = jsonToValue(valueType, obj.get(key), "$path.$key")
                    }
                    map
                }
                SparseArray::class.java -> {
                    val valueType = type.actualTypeArguments[0]
                    val obj = jsonValue as? JSONObject
                        ?: throw IllegalArgumentException("TlJson.fromJson: expected object at '$path'")
                    val out = SparseArray<Any>()
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val index = key.toIntOrNull()
                            ?: throw IllegalArgumentException("TlJson.fromJson: expected integer key at '$path.$key'")
                        out.put(index, jsonToValue(valueType, obj.get(key), "$path.$key"))
                    }
                    out
                }
                else -> throw IllegalArgumentException("TlJson.fromJson: unsupported generic type at '$path'")
            }
            is Class<*> -> {
                if (TLObject::class.java.isAssignableFrom(type)) {
                    val obj = jsonValue as? JSONObject
                        ?: throw IllegalArgumentException("TlJson.fromJson: expected object at '$path'")
                    fromJson(obj)
                } else {
                    throw IllegalArgumentException("TlJson.fromJson: unsupported field type ${type.name} at '$path'")
                }
            }
            else -> throw IllegalArgumentException("TlJson.fromJson: unsupported field type $type at '$path'")
        }
    }
}
