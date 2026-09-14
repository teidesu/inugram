package desu.inugram.core.plugins

data class ActionRegistration<T : Any>(
    val owner: T,
    val kind: Int,
    val id: String,
    val token: Int,
    val placements: Int,
    val text: String?,
    val icon: String?,
    val dynamicFields: Int,
)

/** Rows belong to an engine. A reload can reuse a token. */
class ActionRegistry<T : Any>(private val perKindLimit: Int = DEFAULT_PER_KIND_LIMIT) {
    private data class Registration(
        val token: Int,
        val placements: Int,
        val text: String?,
        val icon: String?,
        val dynamicFields: Int,
    )

    private val rows = HashMap<T, HashMap<Int, LinkedHashMap<String, Registration>>>()

    fun register(
        owner: T,
        kind: Int,
        token: Int,
        id: String,
        placements: Int = DEFAULT_PLACEMENT,
        text: String? = null,
        icon: String? = null,
        dynamicFields: Int = ALL_FIELDS_DYNAMIC,
    ): String? {
        val perKind = rows.getOrPut(owner) { HashMap() }
        val tokens = perKind.getOrPut(kind) { LinkedHashMap() }
        for (bit in 0 until Int.SIZE_BITS) {
            val placement = 1 shl bit
            if (placements and placement == 0) continue
            val position = tokens.count { (key, value) -> key != id && value.placements and placement != 0 } + 1
            if (position > perKindLimit) {
                return "'$id' would be row $position; at most $perKindLimit are drawn per menu"
            }
        }
        tokens[id] = Registration(token, placements, text, icon, dynamicFields)
        return null
    }

    fun unregister(owner: T, kind: Int, token: Int) {
        val perKind = rows[owner] ?: return
        val tokens = perKind[kind] ?: return
        tokens.values.removeAll { it.token == token }
        if (tokens.isEmpty()) perKind.remove(kind)
        if (perKind.isEmpty()) rows.remove(owner)
    }

    fun forget(owner: T) {
        rows.remove(owner)
    }

    fun count(owner: T, kind: Int, placements: Int = ALL_PLACEMENTS): Int =
        rows[owner]?.get(kind)?.values?.count { it.placements and placements != 0 } ?: 0

    fun registrationsInOrder(
        kind: Int,
        order: List<T>,
        placements: Int = ALL_PLACEMENTS,
    ): List<ActionRegistration<T>> = buildList {
        for (owner in order) {
            for ((id, value) in rows[owner]?.get(kind).orEmpty()) {
                if (value.placements and placements == 0) continue
                add(ActionRegistration(owner, kind, id, value.token, value.placements, value.text, value.icon, value.dynamicFields))
            }
        }
    }

    fun size(kind: Int, order: List<T>, placements: Int = ALL_PLACEMENTS): Int =
        order.sumOf { count(it, kind, placements) }

    companion object {
        const val DEFAULT_PER_KIND_LIMIT = 8
        private const val DEFAULT_PLACEMENT = 1
        private const val ALL_PLACEMENTS = -1
        private const val ALL_FIELDS_DYNAMIC = -1
    }
}
