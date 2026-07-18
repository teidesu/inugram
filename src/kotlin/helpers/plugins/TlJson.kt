package desu.inugram.helpers.plugins

import android.util.Base64
import desu.inugram.core.plugins.TlNames
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_account
import org.telegram.tgnet.tl.TL_aicompose
import org.telegram.tgnet.tl.TL_bots
import org.telegram.tgnet.tl.TL_chatlists
import org.telegram.tgnet.tl.TL_forum
import org.telegram.tgnet.tl.TL_fragment
import org.telegram.tgnet.tl.TL_iv
import org.telegram.tgnet.tl.TL_payments
import org.telegram.tgnet.tl.TL_phone
import org.telegram.tgnet.tl.TL_stars
import org.telegram.tgnet.tl.TL_stats
import org.telegram.tgnet.tl.TL_stories
import org.telegram.tgnet.tl.TL_update
import org.telegram.tgnet.tl.legacy.TL_legacy_message
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.ArrayList

/**
 * Reflection-based JSON <-> TLObject bridge. Two jobs remain after the live-proxy rework
 * ([TlHandles] owns the hot get/set path now):
 * - constructing a real TLObject from a plugin-authored plain object literal (`{_: "...", ...}`),
 *   reused by [TlHandles] for both whole-object construction and single-field coercion via
 *   [jsonToValue]/[publicFields].
 * - `inu.onUpdate` and `obj.toJSON()` snapshots, which are deliberately full eager JSON dumps
 *   (observe-only / detached-copy use cases - no live aliasing wanted there).
 *
 * Caveats (mirrored in src/plugins/common.d.ts):
 * - `long` fields are exposed as JSON strings to avoid losing int64 precision in JS numbers.
 * - `flags` bitfields are exposed raw; setting/clearing optional fields without also fixing up
 *   `flags` is the plugin's responsibility.
 */
object TlJson {
    /**
     * sentinel key wrapping a byte-array value in snapshot JSON (`{"$inuBytes": "<base64>"}`);
     * revived into a real Uint8Array by the rust side (`tl_proxy::json_parse_tl`). collision-safe:
     * TL field names are Java identifiers, which can't contain '$'.
     */
    const val BYTES_KEY = "\$inuBytes"

    // fields inherited from TLObject that are runtime bookkeeping, not TL wire data
    private val EXCLUDED_FIELD_NAMES = setOf("networkType", "disableFree")

    private val containerClasses: List<Class<*>> = listOf(
        TLRPC::class.java,
        TL_account::class.java,
        TL_aicompose::class.java,
        TL_bots::class.java,
        TL_chatlists::class.java,
        TL_forum::class.java,
        TL_fragment::class.java,
        TL_iv::class.java,
        TL_payments::class.java,
        TL_phone::class.java,
        TL_stars::class.java,
        TL_stats::class.java,
        TL_stories::class.java,
        TL_update::class.java,
        TL_legacy_message::class.java,
    )

    private val classesByTlName: Map<String, Class<out TLObject>> by lazy { buildClassIndex() }
    private val fieldsByClass = java.util.concurrent.ConcurrentHashMap<Class<*>, Map<String, Field>>()

    private fun buildClassIndex(): Map<String, Class<out TLObject>> {
        val out = HashMap<String, Class<out TLObject>>()
        for (container in containerClasses) {
            collectTlClasses(container, out)
        }
        return out
    }

    private fun collectTlClasses(root: Class<*>, out: MutableMap<String, Class<out TLObject>>) {
        val stack = ArrayDeque<Class<*>>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val cls = stack.removeLast()
            for (nested in cls.declaredClasses) stack.add(nested)
            if (!TLObject::class.java.isAssignableFrom(cls)) continue
            if (Modifier.isAbstract(cls.modifiers)) continue
            if (!cls.simpleName.startsWith("TL_")) continue
            @Suppress("UNCHECKED_CAST")
            val tlClass = cls as Class<out TLObject>
            val tlName = TlNames.classNameToTlName(cls.simpleName)
            val existing = out[tlName]
            if (existing == null) {
                out[tlName] = tlClass
            } else if (TlNames.isLayerVariant(existing.simpleName) && !TlNames.isLayerVariant(cls.simpleName)) {
                out[tlName] = tlClass
            }
            // else: keep the existing (non-layer) mapping
        }
    }

    internal fun publicFields(cls: Class<*>): Map<String, Field> = fieldsByClass.getOrPut(cls) {
        val map = LinkedHashMap<String, Field>()
        for (field in cls.fields) {
            if (Modifier.isStatic(field.modifiers)) continue
            if (field.isSynthetic) continue
            if (field.name in EXCLUDED_FIELD_NAMES) continue
            map[field.name] = field
        }
        map
    }

    // -- serialize (TLObject -> JSON) --

    fun toJson(obj: TLObject): JSONObject {
        val json = JSONObject()
        json.put("_", TlNames.classNameToTlName(obj.javaClass.simpleName))
        for ((name, field) in publicFields(obj.javaClass)) {
            val value = field.get(obj) ?: continue
            json.put(name, valueToJson(value))
        }
        return json
    }

    internal fun valueToJson(value: Any): Any = when (value) {
        is Long -> value.toString()
        is Int, is Short, is Byte, is Double, is Float, is Boolean, is String -> value
        is ByteArray -> JSONObject().put(BYTES_KEY, Base64.encodeToString(value, Base64.NO_WRAP))
        is TLObject -> toJson(value)
        is ArrayList<*> -> {
            val arr = JSONArray()
            for (item in value) if (item != null) arr.put(valueToJson(item))
            arr
        }
        else -> throw IllegalArgumentException("TlJson.toJson: unsupported field type ${value.javaClass}")
    }

    // -- deserialize (JSON -> TLObject) --

    fun fromJson(json: JSONObject): TLObject {
        val tlName = json.optString("_", "")
        if (tlName.isEmpty()) throw IllegalArgumentException("TlJson.fromJson: missing '_' type name")
        val cls = classesByTlName[tlName]
            ?: throw IllegalArgumentException("TlJson.fromJson: unknown TL type '$tlName'")
        val instance = try {
            cls.getDeclaredConstructor().newInstance()
        } catch (e: Exception) {
            throw IllegalArgumentException("TlJson.fromJson: cannot instantiate '$tlName': ${e.message}", e)
        }
        val fields = publicFields(cls)
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "_") continue
            val field = fields[key]
                ?: throw IllegalArgumentException("TlJson.fromJson: unknown field '$key' on '$tlName'")
            val jsonValue = json.get(key)
            val converted = jsonToValue(field.genericType, jsonValue, "$tlName.$key")
            field.set(instance, converted)
        }
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
            is ParameterizedType -> {
                if (type.rawType != ArrayList::class.java) {
                    throw IllegalArgumentException("TlJson.fromJson: unsupported generic type at '$path'")
                }
                val elementType = type.actualTypeArguments[0]
                val arr = jsonValue as? JSONArray
                    ?: throw IllegalArgumentException("TlJson.fromJson: expected array at '$path'")
                val list = ArrayList<Any>(arr.length())
                for (i in 0 until arr.length()) {
                    list.add(jsonToValue(elementType, arr.get(i), "$path[$i]"))
                }
                list
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
