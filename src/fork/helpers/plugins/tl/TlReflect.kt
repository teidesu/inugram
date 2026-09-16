package desu.inugram.helpers.plugins.tl

import desu.inugram.core.plugins.TlFlags
import desu.inugram.core.plugins.TlInt53
import desu.inugram.core.plugins.TlNames
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
 * What a TL class is made of: which classes exist, which of their fields are wire data, and the
 * flag words gating them.
 *
 * Every TL path in the host reads a field through [publicFields] - the live views in [TlHandles],
 * the snapshots in [TlJson], the draft check in
 * [TlFilter] - so this is the hot one, and the caches are the reason: reflecting a class costs a
 * `declaredFields` walk per level and the answer never changes for the life of the process.
 *
 * The flag half writes rather than reads, and belongs with it for the same reason: [TlFlags] says
 * which bit gates a field, this is what finds the word holding it on an actual object.
 *
 * Caveats (mirrored in src/plugins/common.d.ts):
 * - `flags`/`flags2` are never exposed and never accepted: [TlFlags] owns them. a field whose bit is
 *   clear is omitted from reads entirely, and assigning a field recomputes its bit from the value
 *   (`null`/`0`/`""`/empty vector clear it).
 * - stock annotates TL classes with non-wire `//custom` fields (`Message.dialog_id`, `attachPath`,
 *   `voiceTranscription`, ...); reflection can't tell them apart from wire fields, so they ride
 *   along.
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

    /**
     * everything a read decides from (class, name), settled once: `Field.getGenericType()` reparses
     * the signature on every call, and the gate is three lookups. [wordField] is the flags word
     * holding [gate]'s bit, `null` when the field is not gated.
     */
    class FieldInfo(
        val field: Field,
        val gate: TlFlags.Gate?,
        val wordField: Field?,
        val isFlagWord: Boolean,
        val hiddenInTakeover: Boolean,
        val sealedInTakeover: Boolean,
        val redactedInTakeover: Boolean,
        /** a draft rides on a `Dialog`, a `ForumTopic`, a `savedDialog` and `updateDraftMessage` as well as on `getDraft`, so this keys on the field's declared type */
        val isDraft: Boolean,
        /** a long, or a vector of them, that crosses as a js number ([TlInt53]) */
        val isInt53: Boolean,
    ) {
        val genericType: java.lang.reflect.Type = field.genericType
        val type: Class<*> = field.type

        val quotedName: String = JSONObject.quote(field.name)

        val isScalar: Boolean = type == String::class.java || type.isPrimitive

        /** which [Field] getter answers without boxing; [KIND_OTHER] has to go through `get` */
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
            val gate = TlFlags.gateOf(cls, name)
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

    fun fieldInfo(cls: Class<*>, name: String): FieldInfo? = fieldInfos(cls)[name]

    /**
     * whether every field this class has is a scalar - `peerUser`, `inputPeerChat`,
     * `documentAttributeVideo` and the like. Such an object is completely described by its scalars,
     * so a projection of it is the whole object and a plugin never has to cross for one of its
     * fields. Anything with a child of its own is not: projecting it would carry a handle whose own
     * fields still cross, and the object graph has no bottom.
     */
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

    /**
     * every table a TL read consults is built on first use, and the first read a plugin makes is
     * what pays for it (measured on a Pixel 9: 85ms, most of it parsing the TL flag table out of the
     * apk). [PluginManager] calls this off the boot path so no read does.
     */
    fun prewarm() {
        TlTables.prewarm()
        classesByTlName
    }

    fun classOf(tlName: String): Class<out TLObject>? = classesByTlName[tlName]

    /** whether an optional field is present at all; a word this does not know about gates nothing */
    fun isBitSet(obj: TLObject, cls: Class<*>, gate: TlFlags.Gate): Boolean {
        val name = TlFlags.wordName(gate.word) ?: return true
        val field = publicFields(cls)[name] ?: return true
        return (field.getInt(obj) and (1 shl gate.bit)) != 0
    }

    /** for writes onto a live object whose other fields must be left exactly as the app had them */
    fun syncFlagBit(obj: TLObject, fieldName: String) {
        val cls = obj.javaClass
        val gate = TlFlags.gateOf(cls, fieldName) ?: return
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
        for (word in TlFlags.wordsOf(cls)) {
            val name = TlFlags.wordName(word) ?: continue
            val target = fields[name] ?: continue
            target.setInt(obj, TlFlags.computeWord(cls, word) { field ->
                TlFlags.isPresent(fields[field]?.get(obj))
            })
        }
    }

    /** a hand-built request nests objects carrying flag words of their own, so a top-level-only sync still drops them */
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
