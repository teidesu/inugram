package desu.inugram.core.plugins

/**
 * every serializable TL constructor kept in this build, keyed by its canonical wire name (matches
 * [TlNamesTable]), together with the constructor ids - the class's own plus every raw descendant
 * (`_layerNNN`, `_oldN`, ...) that only java inheritance ties back to it. [RESOURCE] is generated
 * from `TLRPC.java` by `pnpm run generate-tl-typings`.
 */
object TlCtorIds {
    private const val RESOURCE = "/tl_ctor_ids.txt"

    private class Table(
        val ids: Map<String, Set<Int>>,
        val methodNames: Set<String>,
        val updateNames: Set<String>,
    )

    private val table: Table by lazy { parse() }

    private fun parse(): Table {
        val text = TlCtorIds::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("$RESOURCE is missing from the classpath; run `pnpm run generate-tl-typings`")

        val ids = HashMap<String, Set<Int>>()
        val methodNames = HashSet<String>()
        val updateNames = HashSet<String>()
        for (line in text.lineSequence()) {
            if (line.isEmpty() || line.startsWith('#')) continue
            val parts = line.split(' ')
            val kind = parts[0]
            val name = parts[1]
            val hexIds = parts.subList(2, parts.size).mapTo(HashSet()) { it.toLong(16).toInt() }
            ids[name] = hexIds
            when (kind) {
                "m" -> methodNames.add(name)
                "u" -> updateNames.add(name)
            }
        }
        return Table(ids, methodNames, updateNames)
    }

    fun idsOf(name: String): Set<Int>? = table.ids[name]

    val methodNames: Set<String> get() = table.methodNames
    val updateNames: Set<String> get() = table.updateNames
    val allNames: Set<String> get() = table.ids.keys
}
