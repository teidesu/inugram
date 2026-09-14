package desu.inugram.core.plugins

/** Use flags from stock serialization, not the schema. Stock can use an older TL layer. */
object TlFlags {
    data class Gate(val word: Int, val bit: Int)

    private const val RESOURCE = "/tl_flags.txt"

    private class Table(val words: List<String>, val byConstructor: Map<Int, Map<String, Gate>>)

    private val table: Table by lazy { parse() }

    fun prewarm() {
        table
    }

    private fun parse(): Table {
        val text = TlFlags::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
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

    fun isFlagWord(cls: Class<*>, name: String): Boolean {
        if (name !in table.words) return false
        return gatesOf(cls) != null
    }

    fun gateOf(cls: Class<*>, name: String): Gate? = gatesOf(cls)?.get(name)

    fun wordName(word: Int): String? = table.words.getOrNull(word)

    fun wordsOf(cls: Class<*>): Set<Int> =
        gatesOf(cls)?.values?.mapTo(HashSet()) { it.word } ?: emptySet()

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
