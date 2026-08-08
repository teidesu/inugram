package desu.inugram.core.plugins

/**
 * What the host knows about `inu.register*Action` rows *without* entering an engine: which rows
 * exist is host state, and only `text`/`visible` need the engine - which is what lets a menu
 * reserve its rows synchronously and ask for the labels one queue hop ahead.
 *
 * The owner is an *engine*, not a plugin: a reload restarts tokens at 1, so anything keyed on the
 * plugin would let a row drawn before the reload dispatch into whatever holds that token after it.
 *
 * Rows are keyed by *id* rather than merely counted because re-registering an id is the documented
 * way to change a row, and the engine allocates the replacement's token before retiring the one it
 * displaces - so a registry counting tokens would refuse the replacement as one row too many.
 */
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

    /**
     * [order] is the plugin list's own order and also the liveness test: an owner missing from it
     * is one whose engine is gone, and is neither asked nor drawn. A [render] answering null
     * contributes nothing rather than aborting the menu.
     *
     * A row is whatever [render] built, never a type of this registry's own: nothing here reads
     * one, and a row that carried its owner as a type parameter put that parameter in the
     * signature of every unrelated menu the host draws.
     */
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
