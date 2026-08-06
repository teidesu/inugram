package desu.inugram.core.plugins

/**
 * the flag bookkeeping plugins never see. TL gates optional fields on bits of a `flags` int, and
 * leaving that to the plugin makes two silent failures easy: a bit without its field throws inside
 * `serializeToStream`, which [org.telegram.tgnet.ConnectionsManager] swallows, so the request is
 * never sent and the promise never settles; a field without its bit is dropped on the wire with no
 * error at all.
 *
 * [RESOURCE] is generated from what each constructor's `serializeToStream` actually writes, not
 * from the published schema: where stock lags a layer, the bytes it writes are what a round-tripped
 * object has to match.
 */
object TlFlags {
    data class Gate(val word: Int, val bit: Int)

    private const val RESOURCE = "/tl_flags.txt"

    private class Table(val words: List<String>, val byConstructor: Map<Int, Map<String, Gate>>)

    private val table: Table by lazy { parse() }

    private fun parse(): Table {
        val text = TlFlags::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
        // an absent resource would silently disable flag management, which fails as a wrong wire format rather than as an error
            ?: throw IllegalStateException("$RESOURCE is missing from the classpath; run `pnpm run generate-tl-typings`")

        var words = emptyList<String>()
        val out = HashMap<Int, Map<String, Gate>>()
        for (line in text.lineSequence()) {
            if (line.isEmpty() || line.startsWith('#')) continue
            val parts = line.split(' ')
            if (parts[0] == "words") {
                words = parts.drop(1)
                continue
            }
            val fields = HashMap<String, Gate>(parts.size)
            for (i in 1 until parts.size) {
                val eq = parts[i].indexOf('=')
                val value = parts[i].substring(eq + 1)
                val second = value.startsWith('+')
                fields[parts[i].substring(0, eq)] =
                    Gate(if (second) 1 else 0, (if (second) value.substring(1) else value).toInt())
            }
            out[parts[0].toLong(16).toInt()] = fields
        }
        return Table(words, out)
    }

    val flagWords: List<String> get() = table.words

    fun isFlagWord(cls: Class<*>, name: String): Boolean {
        if (name !in table.words) return false
        return gatesOf(cls) != null
    }

    fun gateOf(cls: Class<*>, name: String): Gate? = gatesOf(cls)?.get(name)

    fun wordName(word: Int): String? = table.words.getOrNull(word)

    fun wordsOf(cls: Class<*>): Set<Int> =
        gatesOf(cls)?.values?.mapTo(HashSet()) { it.word } ?: emptySet()

    /** OR-ing over every field that shares the bit: sharing one means it stands for "any of these is set" */
    fun isBitPresent(cls: Class<*>, gate: Gate, isPresent: (String) -> Boolean): Boolean {
        for ((name, other) in gatesOf(cls) ?: return false) {
            if (other == gate && isPresent(name)) return true
        }
        return false
    }

    fun computeWord(cls: Class<*>, word: Int, isPresent: (String) -> Boolean): Int {
        var value = 0
        for ((name, gate) in gatesOf(cls) ?: return 0) {
            if (gate.word == word && isPresent(name)) value = value or (1 shl gate.bit)
        }
        return value
    }

    private fun gatesOf(cls: Class<*>): Map<String, Gate>? {
        val id = constructorIdOf(cls) ?: return null
        return table.byConstructor[id]
    }

    private fun constructorIdOf(cls: Class<*>): Int? = try {
        val field = cls.getDeclaredField("constructor")
        if (java.lang.reflect.Modifier.isStatic(field.modifiers) && field.type == Integer.TYPE) {
            field.getInt(null)
        } else {
            null
        }
    } catch (e: NoSuchFieldException) {
        null
    }

    /**
     * whether a value counts as present. absent and "zero-ish" are the same thing on the wire - an
     * omitted int reads back as 0, an omitted vector as empty - so this is what lets presence be
     * read off the value rather than tracked separately.
     */
    fun isPresent(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Int -> value != 0
        is Long -> value != 0L
        is Short -> value.toInt() != 0
        is Byte -> value.toInt() != 0
        is Double -> value != 0.0
        is Float -> value != 0f
        is String -> value.isNotEmpty()
        is ByteArray -> value.isNotEmpty()
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }
}
