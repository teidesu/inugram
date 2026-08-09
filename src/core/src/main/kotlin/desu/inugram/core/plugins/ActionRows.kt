package desu.inugram.core.plugins

/** Rows belong to an engine. A reload can reuse a token. */
class ActionRegistry<T : Any>(private val perKindLimit: Int = DEFAULT_PER_KIND_LIMIT) {
    private val rows = HashMap<T, HashMap<Int, LinkedHashMap<String, Int>>>()

    fun register(owner: T, kind: Int, token: Int, id: String): String? {
        val perKind = rows.getOrPut(owner) { HashMap() }
        val tokens = perKind.getOrPut(kind) { LinkedHashMap() }
        if (id !in tokens && tokens.size >= perKindLimit) {
            return "'$id' would be row ${tokens.size + 1}; at most $perKindLimit are drawn per menu"
        }
        tokens[id] = token
        return null
    }

    fun unregister(owner: T, kind: Int, token: Int) {
        val perKind = rows[owner] ?: return
        val tokens = perKind[kind] ?: return
        tokens.values.remove(token)
        if (tokens.isEmpty()) perKind.remove(kind)
        if (perKind.isEmpty()) rows.remove(owner)
    }

    fun forget(owner: T) {
        rows.remove(owner)
    }

    fun count(owner: T, kind: Int): Int = rows[owner]?.get(kind)?.size ?: 0

    fun tokenFor(owner: T, kind: Int, id: String): Int? = rows[owner]?.get(kind)?.get(id)

    fun size(kind: Int, order: List<T>): Int = order.sumOf { count(it, kind) }

    fun <R> rowsInOrder(kind: Int, order: List<T>, render: (T) -> List<R>?): List<R> {
        val out = mutableListOf<R>()
        for (owner in order) {
            if (count(owner, kind) == 0) continue
            out.addAll(render(owner).orEmpty())
        }
        return out
    }

    companion object {
        const val DEFAULT_PER_KIND_LIMIT = 8
    }
}
