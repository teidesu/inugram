package desu.inugram.helpers.plugins.tl

import desu.inugram.core.plugins.TlTables
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import org.json.JSONObject
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_account
import org.telegram.tgnet.tl.TL_aicompose
import org.telegram.tgnet.tl.TL_bots
import org.telegram.tgnet.tl.TL_chatlists
import org.telegram.tgnet.tl.TL_communities
import org.telegram.tgnet.tl.TL_ephemeral
import org.telegram.tgnet.tl.TL_forum
import org.telegram.tgnet.tl.TL_fragment
import org.telegram.tgnet.tl.TL_iv
import org.telegram.tgnet.tl.TL_keyboard
import org.telegram.tgnet.tl.TL_payments
import org.telegram.tgnet.tl.TL_phone
import org.telegram.tgnet.tl.TL_stars
import org.telegram.tgnet.tl.TL_stats
import org.telegram.tgnet.tl.TL_stories
import org.telegram.tgnet.tl.TL_update
import org.telegram.tgnet.tl.legacy.TL_legacy_message

/**
 * Rules mirrored in `sdk/types/common.d.ts`: `flags`/`flags2` are neither exposed nor accepted, cleared
 * optional fields are omitted, and assignments recompute the bit (`null`, `0`, `""`, `[]` clear it).
 * Stock's `//custom` fields (`Message.dialog_id`, `attachPath`, ...) are exposed too: reflection cannot
 * tell them from wire fields.
 */
object TlReflect {
    // fields inherited from TLObject that are runtime bookkeeping, not TL wire data
    private val EXCLUDED_FIELD_NAMES = setOf("networkType", "disableFree")

    private val containerClasses: List<Class<*>> = listOf(
        TLRPC::class.java,
        TL_account::class.java,
        TL_aicompose::class.java,
        TL_bots::class.java,
        TL_chatlists::class.java,
        TL_communities::class.java,
        TL_ephemeral::class.java,
        TL_forum::class.java,
        TL_fragment::class.java,
        TL_iv::class.java,
        TL_keyboard::class.java,
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
    private val infosByClass = java.util.concurrent.ConcurrentHashMap<Class<*>, Map<String, FieldInfo>>()
    private val fullyScalarByClass = java.util.concurrent.ConcurrentHashMap<Class<*>, Boolean>()

    /** `Field.getGenericType()` reparses the signature on every call */
    class FieldInfo(
        val field: Field,
        val gate: TlTables.Gate?,
        val wordField: Field?,
        val isFlagWord: Boolean,
        val hiddenInTakeover: Boolean,
        val sealedInTakeover: Boolean,
        val redactedInTakeover: Boolean,
        /** drafts also ride on `Dialog`, `ForumTopic`, `savedDialog` and `updateDraftMessage`, so this keys on declared type */
        val isDraft: Boolean,
        val isInt53: Boolean,
    ) {
        val genericType: java.lang.reflect.Type = field.genericType
        val type: Class<*> = field.type

        val quotedName: String = JSONObject.quote(field.name)

        val isScalar: Boolean = type == String::class.java || type.isPrimitive

        val kind: Int = when (type) {
            java.lang.Long.TYPE -> KIND_LONG
            Integer.TYPE -> KIND_INT
            java.lang.Boolean.TYPE -> KIND_BOOL
            java.lang.Double.TYPE -> KIND_DOUBLE
            else -> KIND_OTHER
        }

        fun isPresent(obj: TLObject): Boolean =
            wordField == null || gate == null || (wordField.getInt(obj) and (1 shl gate.bit)) != 0
    }

    const val KIND_OTHER = 0
    const val KIND_LONG = 1
    const val KIND_INT = 2
    const val KIND_BOOL = 3
    const val KIND_DOUBLE = 4

    fun fieldInfos(cls: Class<*>): Map<String, FieldInfo> = infosByClass.getOrPut(cls) {
        val fields = publicFields(cls)
        val out = LinkedHashMap<String, FieldInfo>(fields.size)
        for ((name, field) in fields) {
            val gate = TlFlags.findGate(cls, name)
            out[name] = FieldInfo(
                field = field,
                gate = gate,
                wordField = gate?.let { fields[TlFlags.wordName(it.word) ?: return@let null] },
                isFlagWord = TlFlags.isFlagWord(cls, name),
                hiddenInTakeover = TlFilter.hidesTakeoverField(cls, name),
                sealedInTakeover = TlFilter.decidesRedaction(cls, name),
                redactedInTakeover = TlFilter.canRedactField(cls, name),
                isDraft = field.type == TLRPC.DraftMessage::class.java,
                isInt53 = TlInt53.isInt53(cls, name) && carriesLong(field),
            )
        }
        out
    }

    private fun carriesLong(field: Field): Boolean {
        if (field.type == java.lang.Long.TYPE || field.type == java.lang.Long::class.java) return true
        val generic = field.genericType as? java.lang.reflect.ParameterizedType ?: return false
        return generic.rawType == ArrayList::class.java && generic.actualTypeArguments[0] == java.lang.Long::class.java
    }

    fun isFullyScalar(cls: Class<*>): Boolean = fullyScalarByClass.getOrPut(cls) {
        val infos = fieldInfos(cls).values.filterNot { it.isFlagWord }
        infos.isNotEmpty() && infos.all { it.isScalar }
    }

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
            if (TlTables.readConstructorId(cls) == null) continue
            @Suppress("UNCHECKED_CAST")
            val tlClass = cls as Class<out TLObject>
            val tlName = TlNames.classNameToTlName(cls)
            val existing = out[tlName]
            if (existing == null) {
                out[tlName] = tlClass
            } else if (TlNames.isLayerVariant(existing.simpleName) && !TlNames.isLayerVariant(cls.simpleName)) {
                out[tlName] = tlClass
            }
        }
    }

    /** stock shadows inherited fields with a different type (`pageBlockBlockquote.caption`), and `Class.getFields()` order is unspecified */
    fun publicFields(cls: Class<*>): Map<String, Field> = fieldsByClass.getOrPut(cls) {
        val map = LinkedHashMap<String, Field>()
        var current: Class<*>? = cls
        while (current != null) {
            for (field in current.declaredFields) {
                if (!Modifier.isPublic(field.modifiers)) continue
                if (Modifier.isStatic(field.modifiers)) continue
                if (field.isSynthetic) continue
                if (field.name in EXCLUDED_FIELD_NAMES) continue
                if (field.name !in map) map[field.name] = field.also { it.isAccessible = true }
            }
            current = current.superclass
        }
        map
    }

    /** the first TL read otherwise pays for every table (Pixel 9: 85ms, mostly parsing the flag table out of the apk) */
    fun prewarm() {
        TlTables.prewarm()
        classesByTlName
    }

    fun findTlClass(tlName: String): Class<out TLObject>? = classesByTlName[tlName]

    /** the other fields of a live object must stay exactly as the app had them */
    fun syncFlagBit(obj: TLObject, fieldName: String) {
        val cls = obj.javaClass
        val gate = TlFlags.findGate(cls, fieldName) ?: return
        val fields = publicFields(cls)
        val wordField = fields[TlFlags.wordName(gate.word) ?: return] ?: return
        val present = TlFlags.isBitPresent(cls, gate) { TlFlags.isPresent(fields[it]?.get(obj)) }
        val mask = 1 shl gate.bit
        val current = wordField.getInt(obj)
        wordField.setInt(obj, if (present) current or mask else current and mask.inv())
    }

    fun syncFlags(obj: TLObject) {
        val cls = obj.javaClass
        val fields = publicFields(cls)
        for (word in TlFlags.getFlagWords(cls)) {
            val name = TlFlags.wordName(word) ?: continue
            val target = fields[name] ?: continue
            target.setInt(obj, TlFlags.computeWord(cls, word) { field ->
                TlFlags.isPresent(fields[field]?.get(obj))
            })
        }
    }

    /** nested objects carry their own flag words */
    fun syncFlagsDeep(obj: TLObject) {
        for (field in publicFields(obj.javaClass).values) {
            when (val value = field.get(obj)) {
                is TLObject -> syncFlagsDeep(value)
                is List<*> -> for (item in value) if (item is TLObject) syncFlagsDeep(item)
            }
        }
        syncFlags(obj)
    }
}
