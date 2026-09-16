package desu.inugram.core.plugins

/**
 * Everything generated about stock's TL classes, parsed once: flag gates, js-number longs, wire names
 * by constructor id, and the name overrides [TlNames] derives the rest from. `pnpm run generate-tl`
 * writes `tl_tables.txt` from the worktree; it is gitignored, so `pnpm run setup` has to have run.
 */
object TlTables {
    private const val RESOURCE = "/tl_tables.txt"

    internal class Table(
        val flagWords: List<String>,
        val namespaces: Set<String>,
        /** `<container>.<simpleName>` -> wire name, for every class whose java name doesn't read as it */
        val nameOverrides: Map<String, String>,
        val idsByName: Map<String, Set<Int>>,
        /**
         * every id back to every name that claims it. A legacy variant shares its id with the live
         * constructor it replaced, and which of the two a caller would have seen first is hash
         * order, so the answer is the whole set: a question asked of one name must be asked of all.
         */
        val namesById: Map<Int, Set<String>>,
        val methodNames: Set<String>,
        val updateNames: Set<String>,
        val gatesById: Map<Int, Map<String, TlFlags.Gate>>,
        val int53ById: Map<Int, Set<String>>,
    )

    internal val table: Table by lazy { parse() }

    fun prewarm() {
        table
    }

    /** every constructor id, legacy variants included, that reads as a kept class's wire name */
    fun idsOf(name: String): Set<Int>? = table.idsByName[name]

    /** every name a constructor id is declared under, empty when no layer this build knows declares it */
    fun namesOf(id: Int): Set<String> = table.namesById[id].orEmpty()

    val methodNames: Set<String> get() = table.methodNames
    val updateNames: Set<String> get() = table.updateNames
    val allNames: Set<String> get() = table.idsByName.keys

    internal fun constructorIdOf(cls: Class<*>): Int? = try {
        val field = cls.getDeclaredField("constructor")
        if (java.lang.reflect.Modifier.isStatic(field.modifiers) && field.type == Integer.TYPE) {
            field.getInt(null)
        } else {
            null
        }
    } catch (e: NoSuchFieldException) {
        null
    }

    private fun parse(): Table {
        val text = TlTables::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("$RESOURCE is missing from the classpath; run `pnpm run generate-tl`")

        var flagWords = emptyList<String>()
        var namespaces = emptySet<String>()
        val nameOverrides = HashMap<String, String>()
        val idsByName = HashMap<String, MutableSet<Int>>()
        val namesById = HashMap<Int, Set<String>>()
        val methodNames = HashSet<String>()
        val updateNames = HashSet<String>()
        val gatesById = HashMap<Int, Map<String, TlFlags.Gate>>()
        val int53ById = HashMap<Int, Set<String>>()
        for (line in text.lineSequence()) {
            if (line.isEmpty() || line.startsWith('#')) continue
            val parts = line.split(' ')
            when (parts[0]) {
                "words" -> flagWords = parts.drop(1)
                "ns" -> namespaces = parts.drop(1).toHashSet()
                "name" -> nameOverrides[parts[1]] = parts[2]
                else -> {
                    val id = parts[0].toLong(16).toInt()
                    val names = LinkedHashSet<String>()
                    val gates = HashMap<String, TlFlags.Gate>()
                    val int53 = HashSet<String>()
                    for (i in 1 until parts.size) {
                        val token = parts[i]
                        val value = token.substring(2)
                        when (token[0]) {
                            'c', 'm', 'u' -> {
                                names.add(value)
                                idsByName.getOrPut(value) { HashSet() }.add(id)
                                if (token[0] == 'm') methodNames.add(value)
                                if (token[0] == 'u') updateNames.add(value)
                            }
                            'f' -> {
                                val eq = value.indexOf('=')
                                val bit = value.substring(eq + 1)
                                val second = bit.startsWith('+')
                                gates[value.substring(0, eq)] =
                                    TlFlags.Gate(if (second) 1 else 0, (if (second) bit.substring(1) else bit).toInt())
                            }
                            'i' -> int53.add(value)
                            else -> throw IllegalStateException("$RESOURCE: unknown token '$token' on constructor ${parts[0]}")
                        }
                    }
                    if (names.isNotEmpty()) namesById[id] = names
                    if (gates.isNotEmpty()) gatesById[id] = gates
                    if (int53.isNotEmpty()) int53ById[id] = int53
                }
            }
        }
        return Table(flagWords, namespaces, nameOverrides, idsByName, namesById, methodNames, updateNames, gatesById, int53ById)
    }
}
