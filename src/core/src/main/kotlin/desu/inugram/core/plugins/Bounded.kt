package desu.inugram.core.plugins

import java.util.IdentityHashMap

/**
 * A bounded, thread-safe identity set. Stock can retry parked pts/seq batches using the same
 * TLRPC objects, so entries only need to survive that retry window. TLRPC classes do not
 * override `hashCode`.
 *
 * Synchronize access because stock's `processUpdateArray` can query the set from threads
 * other than the writer's.
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
 * A bounded map that evicts its oldest entry and supports taking entries out.
 * Uses `LinkedHashMap` eviction instead of a separate deque, avoiding linear removal scans
 * on globalQueue for settled requests.
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
