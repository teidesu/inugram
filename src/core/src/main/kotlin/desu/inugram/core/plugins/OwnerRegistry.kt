package desu.inugram.core.plugins

/** Tracks resources by owner, usually a running plugin, so teardown can release all of its resources. Thread-safe. */
class OwnerRegistry<O : Any, T> {
    private val byOwner = HashMap<O, MutableList<T>>()

    fun add(owner: O, item: T) {
        synchronized(byOwner) { byOwner.getOrPut(owner) { ArrayList() }.add(item) }
    }

    /** the first of [owner]'s items [matches] picks, taken out */
    fun remove(owner: O, matches: (T) -> Boolean): T? = synchronized(byOwner) {
        val items = byOwner[owner] ?: return null
        val index = items.indexOfFirst(matches)
        if (index < 0) return null
        val found = items.removeAt(index)
        if (items.isEmpty()) byOwner.remove(owner)
        found
    }

    fun take(owner: O): List<T> = synchronized(byOwner) { byOwner.remove(owner) }.orEmpty()

    /** every item of every owner [picks] selects, taken out */
    fun takeWhere(picks: (O) -> Boolean): List<T> = synchronized(byOwner) {
        byOwner.keys.filter(picks).flatMap { byOwner.remove(it).orEmpty() }
    }

    fun count(owner: O): Int = synchronized(byOwner) { byOwner[owner]?.size ?: 0 }

    /** whether any owner holds an item [matches] picks */
    fun any(matches: (T) -> Boolean): Boolean = synchronized(byOwner) {
        byOwner.values.any { items -> items.any(matches) }
    }
}
