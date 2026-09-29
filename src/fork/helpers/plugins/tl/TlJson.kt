package desu.inugram.helpers.plugins.tl

import android.util.Base64
import android.util.SparseArray
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.tgnet.TLObject

/**
 * The live get/set path is [TlHandles]'s. Mirrored in sdk/types/common.d.ts: longs are JSON strings
 * except [TlReflect.FieldInfo.isInt53] ones, and either is accepted back; a number past the safe range
 * is refused. Field types with no JSON mapping are skipped so one exotic field can't sink a snapshot.
 */
object TlJson {
    /** revived into a Uint8Array by rust (`tl_proxy::json_parse_tl`). TL field names are java identifiers, so no collision */
    const val BYTES_KEY = "\$inuBytes"

    fun toJson(obj: TLObject, policy: TlFilter.Policy): JSONObject {
        val cls = obj.javaClass
        val json = JSONObject()
        json.put("_", TlNames.classNameToTlName(cls))
        for ((name, info) in TlReflect.fieldInfos(cls)) {
            if (info.isFlagWord) continue
            if (TlFilter.hidesField(policy, info)) continue
            if (!info.isPresent(obj)) continue
            val raw = info.field.get(obj) ?: continue
            val value = (if (policy.takeover) TlFilter.filterFieldValue(obj, name, raw) else raw) ?: continue
            json.put(name, valueToJson(value, policy, info.isInt53) ?: continue)
        }
        return json
    }

    internal fun valueToJson(value: Any, policy: TlFilter.Policy, int53: Boolean = false): Any? = when (value) {
        is Long -> if (int53) value else value.toString()
        is Int, is Short, is Byte, is Double, is Float, is Boolean, is String -> value
        is ByteArray -> JSONObject().put(BYTES_KEY, Base64.encodeToString(value, Base64.NO_WRAP))
        is TLObject -> toJson(value, policy)
        is ArrayList<*> -> {
            val arr = JSONArray()
            for (item in value) if (item != null) valueToJson(item, policy, int53)?.let { arr.put(it) }
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

    fun fromJson(json: JSONObject): TLObject {
        val tlName = json.optString("_", "")
        if (tlName.isEmpty()) throw IllegalArgumentException("TlJson.fromJson: missing '_' type name")
        val cls = TlReflect.findTlClass(tlName)
            ?: throw IllegalArgumentException("TlJson.fromJson: unknown TL type '$tlName'")
        val instance = try {
            cls.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
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
                is Int -> jsonValue.toLong()
                is Long -> jsonValue.takeIf { it in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER } ?: throw IllegalArgumentException("TlJson.fromJson: a long at '$path' must be a safe integer or a decimal string")
                is Double -> jsonValue.takeIf { it % 1.0 == 0.0 && Math.abs(it) <= MAX_SAFE_INTEGER }?.toLong() ?: throw IllegalArgumentException("TlJson.fromJson: a long at '$path' must be a safe integer or a decimal string")
                is Number -> throw IllegalArgumentException("TlJson.fromJson: a long at '$path' must be a safe integer or a decimal string")
                else -> throw expectedAt("long", path)
            }
            Integer.TYPE, Integer::class.java -> numberAt(jsonValue, "int", path).toInt()
            // reflective Field.set on a primitive needs the exact boxed width
            java.lang.Short.TYPE -> numberAt(jsonValue, "int", path).toShort()
            java.lang.Byte.TYPE -> numberAt(jsonValue, "int", path).toByte()
            java.lang.Double.TYPE, java.lang.Double::class.java -> numberAt(jsonValue, "double", path).toDouble()
            java.lang.Float.TYPE, java.lang.Float::class.java -> numberAt(jsonValue, "float", path).toFloat()
            java.lang.Boolean.TYPE, java.lang.Boolean::class.java ->
                jsonValue as? Boolean ?: throw expectedAt("boolean", path)
            String::class.java ->
                jsonValue as? String ?: throw expectedAt("string", path)
            ByteArray::class.java -> when {
                jsonValue is String -> Base64.decode(jsonValue, Base64.NO_WRAP)
                jsonValue is JSONObject && jsonValue.has(BYTES_KEY) -> Base64.decode(jsonValue.getString(BYTES_KEY), Base64.NO_WRAP)
                else -> throw IllegalArgumentException("TlJson.fromJson: expected bytes (base64 string or {$BYTES_KEY}) at '$path'")
            }
            is ParameterizedType -> when (type.rawType) {
                ArrayList::class.java -> {
                    val elementType = type.actualTypeArguments[0]
                    val arr = jsonValue as? JSONArray ?: throw expectedAt("array", path)
                    val list = ArrayList<Any>(arr.length())
                    for (i in 0 until arr.length()) {
                        list.add(jsonToValue(elementType, arr.get(i), "$path[$i]"))
                    }
                    list
                }
                HashMap::class.java -> {
                    val valueType = type.actualTypeArguments[1]
                    val obj = jsonValue as? JSONObject ?: throw expectedAt("object", path)
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
                    val obj = jsonValue as? JSONObject ?: throw expectedAt("object", path)
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
                    val obj = jsonValue as? JSONObject ?: throw expectedAt("object", path)
                    fromJson(obj)
                } else {
                    throw IllegalArgumentException("TlJson.fromJson: unsupported field type ${type.name} at '$path'")
                }
            }
            else -> throw IllegalArgumentException("TlJson.fromJson: unsupported field type $type at '$path'")
        }
    }

    private fun numberAt(jsonValue: Any, what: String, path: String): Number =
        jsonValue as? Number ?: throw expectedAt(what, path)

    private fun expectedAt(what: String, path: String) =
        IllegalArgumentException("TlJson.fromJson: expected $what at '$path'")

    /** past it a js number no longer names one integer */
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
}
