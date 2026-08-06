package desu.inugram.helpers.plugins.tl

import android.util.Base64
import android.util.SparseArray
import desu.inugram.core.plugins.TlFlags
import desu.inugram.core.plugins.TlNames
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.ArrayList
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_account
import org.telegram.tgnet.tl.TL_aicompose
import org.telegram.tgnet.tl.TL_bots
import org.telegram.tgnet.tl.TL_chatlists
import org.telegram.tgnet.tl.TL_communities
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

/**
 * Reflection-based JSON <-> TLObject bridge, [TlHandles] owning the hot get/set path: constructing
 * a real TLObject from a plugin-authored `{_: "...", ...}` literal (reused by [TlHandles] for
 * single-field coercion), and `obj.toJSON()` snapshots.
 *
 * Caveats (mirrored in src/plugins/common.d.ts):
 * - `long` fields are exposed as JSON strings to avoid losing int64 precision in JS numbers.
 * - `flags`/`flags2` are never exposed and never accepted: [TlFlags] owns them. a field whose bit is
 *   clear is omitted from snapshots entirely, and assigning a field recomputes its bit from the
 *   value (`null`/`0`/`""`/empty vector clear it).
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

    // fields inherited from TLObject that are runtime bookkeeping, not TL wire data
    private val EXCLUDED_FIELD_NAMES = setOf("networkType", "disableFree")

    private val containerClasses: List<Class<*>> = listOf(
        TLRPC::class.java,
        TL_account::class.java,
        TL_aicompose::class.java,
        TL_bots::class.java,
        TL_chatlists::class.java,
        TL_communities::class.java,
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

    /** a `static int constructor` is what makes a TL class serializable - stock declares hundreds without the `TL_` prefix, so the name says nothing */
    private fun isWireSerializable(cls: Class<*>): Boolean = try {
        val field = cls.getDeclaredField("constructor")
        Modifier.isStatic(field.modifiers) && field.type == Integer.TYPE
    } catch (e: NoSuchFieldException) {
        false
    }

    private fun collectTlClasses(root: Class<*>, out: MutableMap<String, Class<out TLObject>>) {
        val stack = ArrayDeque<Class<*>>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val cls = stack.removeLast()
            for (nested in cls.declaredClasses) stack.add(nested)
            if (!TLObject::class.java.isAssignableFrom(cls)) continue
            if (Modifier.isAbstract(cls.modifiers)) continue
            if (!isWireSerializable(cls)) continue
            @Suppress("UNCHECKED_CAST")
            val tlClass = cls as Class<out TLObject>
            val tlName = TlNames.classNameToTlName(cls)
            val existing = out[tlName]
            if (existing == null) {
                out[tlName] = tlClass
            } else if (TlNames.isLayerVariant(existing.simpleName) && !TlNames.isLayerVariant(cls.simpleName)) {
                out[tlName] = tlClass
            }
            // else: keep the existing (non-layer) mapping
        }
    }

    /**
     * most-derived first, because stock shadows inherited fields with a different type
     * (`PageBlock.caption` is a PageCaption, `pageBlockBlockquote.caption` a RichText) and
     * `Class.getFields()` does not say which it hands back first.
     */
    internal fun publicFields(cls: Class<*>): Map<String, Field> = fieldsByClass.getOrPut(cls) {
        val map = LinkedHashMap<String, Field>()
        var current: Class<*>? = cls
        while (current != null) {
            for (field in current.declaredFields) {
                if (!Modifier.isPublic(field.modifiers)) continue
                if (Modifier.isStatic(field.modifiers)) continue
                if (field.isSynthetic) continue
                if (field.name in EXCLUDED_FIELD_NAMES) continue
                if (field.name !in map) map[field.name] = field
            }
            current = current.superclass
        }
        map
    }

    internal fun classOf(tlName: String): Class<out TLObject>? = classesByTlName[tlName]

    fun toJson(obj: TLObject, policy: TlFilter.Policy): JSONObject {
        val cls = obj.javaClass
        val json = JSONObject()
        json.put("_", TlNames.classNameToTlName(cls))
        for ((name, field) in publicFields(cls)) {
            if (TlFlags.isFlagWord(cls, name)) continue
            if (TlFilter.hidesField(policy, cls, name)) continue
            val gate = TlFlags.gateOf(cls, name)
            if (gate != null && !isBitSet(obj, cls, gate)) continue
            val raw = field.get(obj) ?: continue
            val value = (if (policy.takeover) TlFilter.filterFieldValue(obj, name, raw) else raw) ?: continue
            json.put(name, valueToJson(value, policy) ?: continue)
        }
        return json
    }

    private fun isBitSet(obj: TLObject, cls: Class<*>, gate: TlFlags.Gate): Boolean {
        val name = TlFlags.wordName(gate.word) ?: return true
        val field = publicFields(cls)[name] ?: return true
        return (field.getInt(obj) and (1 shl gate.bit)) != 0
    }

    /** for writes onto a live object whose other fields must be left exactly as the app had them */
    internal fun syncFlagBit(obj: TLObject, fieldName: String) {
        val cls = obj.javaClass
        val gate = TlFlags.gateOf(cls, fieldName) ?: return
        val fields = publicFields(cls)
        val wordField = fields[TlFlags.wordName(gate.word) ?: return] ?: return
        val present = TlFlags.isBitPresent(cls, gate) { TlFlags.isPresent(fields[it]?.get(obj)) }
        val mask = 1 shl gate.bit
        val current = wordField.getInt(obj)
        wordField.setInt(obj, if (present) current or mask else current and mask.inv())
    }

    internal fun syncFlags(obj: TLObject) {
        val cls = obj.javaClass
        val fields = publicFields(cls)
        for (word in TlFlags.wordsOf(cls)) {
            val name = TlFlags.wordName(word) ?: continue
            val target = fields[name] ?: continue
            target.setInt(obj, TlFlags.computeWord(cls, word) { field ->
                TlFlags.isPresent(fields[field]?.get(obj))
            })
        }
    }

    /** a hand-built request nests objects carrying flag words of their own, so a top-level-only sync still drops them */
    internal fun syncFlagsDeep(obj: TLObject) {
        for (field in publicFields(obj.javaClass).values) {
            when (val value = field.get(obj)) {
                is TLObject -> syncFlagsDeep(value)
                is List<*> -> for (item in value) if (item is TLObject) syncFlagsDeep(item)
            }
        }
        syncFlags(obj)
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
        syncFlags(instance)
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
