package desu.inugram.core.plugins

/** Use flags from stock serialization, not the schema. Stock can use an older TL layer. */
object TlFlags {
    data class Gate(val word: Int, val bit: Int)

    fun isFlagWord(cls: Class<*>, name: String): Boolean {
        if (name !in TlTables.table.flagWords) return false
        return gatesOf(cls) != null
    }

    fun gateOf(cls: Class<*>, name: String): Gate? = gatesOf(cls)?.get(name)

    fun wordName(word: Int): String? = TlTables.table.flagWords.getOrNull(word)

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
        val id = TlTables.constructorIdOf(cls) ?: return null
        return TlTables.table.gatesById[id]
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
