package desu.inugram.helpers.plugins.tl

import desu.inugram.core.plugins.TlTables

/** flags come from stock serialization, not the schema: stock can be on an older layer */
object TlFlags {
    fun isFlagWord(cls: Class<*>, name: String): Boolean {
        if (name !in TlTables.table.flagWords) return false
        return getGates(cls) != null
    }

    fun findGate(cls: Class<*>, name: String): TlTables.Gate? = getGates(cls)?.get(name)

    fun wordName(word: Int): String? = TlTables.table.flagWords.getOrNull(word)

    fun getFlagWords(cls: Class<*>): Set<Int> =
        getGates(cls)?.values?.mapTo(HashSet()) { it.word } ?: emptySet()

    fun isBitPresent(cls: Class<*>, gate: TlTables.Gate, isPresent: (String) -> Boolean): Boolean {
        for ((name, other) in getGates(cls) ?: return false) {
            if (other == gate && isPresent(name)) return true
        }
        return false
    }

    fun computeWord(cls: Class<*>, word: Int, isPresent: (String) -> Boolean): Int {
        var value = 0
        for ((name, gate) in getGates(cls) ?: return 0) {
            if (gate.word == word && isPresent(name)) value = value or (1 shl gate.bit)
        }
        return value
    }

    private fun getGates(cls: Class<*>): Map<String, TlTables.Gate>? {
        val id = TlTables.readConstructorId(cls) ?: return null
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
