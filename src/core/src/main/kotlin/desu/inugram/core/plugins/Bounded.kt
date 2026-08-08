package desu.inugram.core.plugins

import java.util.IdentityHashMap

/**
 * "have I already seen this exact object", with a ceiling.
 *
 * Identity rather than equality because no `TLRPC` class overrides `hashCode`, and bounded because
 * the question is only ever asked about an arrival still in flight: stock parks a batch whose
 * pts/seq does not line up and re-feeds it later around the *same* instances, and an entry only has
 * to outlive that window.
 *
 * Synchronized because one of these is read by stock rather than by us: `processUpdateArray` asks
 * whether it may apply an update, and it has callers on threads other than the one that wrote the
 * answer. An uncontended lock costs nothing next to a resize racing a lookup.
 */
class BoundedIdentitySet<T : Any>(private val capacity: Int) {
    private val seen = java.util.Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    private val order = ArrayDeque<T>()

    @Synchronized
    fun add(value: T): Boolean {
        if (!seen.add(value)) return false
        order.addLast(value)
        while (order.size > capacity) seen.remove(order.removeFirst())
        return true
    }

    @Synchronized
    operator fun contains(value: T): Boolean = seen.contains(value)

    @Synchronized
    fun clear() {
        seen.clear()
        order.clear()
    }

    val size: Int @Synchronized get() = seen.size
}

/**
 * a bounded map that drops its oldest entry rather than growing, and can take one back out.
 *
 * `LinkedHashMap`'s own eviction hook is the whole implementation, which is the point: a parallel
 * `ArrayDeque` needs a linear scan to remove an entry that was claimed before it aged out, and that
 * scan runs on `globalQueue` once per settled request.
 */
class BoundedLru<K : Any, V : Any>(private val capacity: Int) {
    private val entries = object : LinkedHashMap<K, V>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
            size > capacity
    }

    fun put(key: K, value: V) {
        entries[key] = value
    }

    fun remove(key: K): V? = entries.remove(key)

    operator fun get(key: K): V? = entries[key]

    fun clear() = entries.clear()

    val size: Int get() = entries.size
}
