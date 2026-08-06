package desu.inugram.core.plugins

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoundedTest {
    /** the reason it is identity: no `TLRPC` class overrides `hashCode`, and two arrivals can be equal */
    @Test
    fun `two equal but distinct objects are two different arrivals`() {
        val set = BoundedIdentitySet<String>(8)
        val first = StringBuilder("a").toString()
        val second = StringBuilder("a").toString()

        assertTrue(set.add(first))
        assertFalse(set.add(first))
        assertTrue(set.add(second))
    }

    @Test
    fun `the oldest entry is what falls out, and it is answerable again`() {
        val set = BoundedIdentitySet<Any>(2)
        val a = Any()
        val b = Any()
        val c = Any()

        set.add(a)
        set.add(b)
        set.add(c)

        assertEquals(2, set.size)
        assertFalse(a in set)
        assertTrue(b in set)
        assertTrue(c in set)
        assertTrue(set.add(a), "an entry that aged out is a first sighting again")
    }

    @Test
    fun `clearing drops the window and the order with it`() {
        val set = BoundedIdentitySet<Any>(4)
        val a = Any()
        set.add(a)
        set.clear()

        assertEquals(0, set.size)
        assertTrue(set.add(a))
    }

    @Test
    fun `an lru evicts by insertion, and a rewrite does not refresh the entry`() {
        val lru = BoundedLru<Int, String>(2)
        lru.put(1, "a")
        lru.put(2, "b")
        lru.put(1, "a2")
        lru.put(3, "c")

        assertNull(lru[1], "rewriting a key must not move it to the back of the queue")
        assertEquals("b", lru[2])
        assertEquals("c", lru[3])
        assertEquals(2, lru.size)
    }

    /**
     * the whole reason it is a `LinkedHashMap` and not a set beside an `ArrayDeque`: a guid is
     * claimed once the passthrough goes out, and taking it out used to be a linear scan of the
     * whole window on `globalQueue` for every settled request
     */
    @Test
    fun `removing an entry takes it out of the eviction order too`() {
        val lru = BoundedLru<Int, String>(2)
        lru.put(1, "a")
        lru.put(2, "b")

        assertEquals("a", lru.remove(1))
        assertNull(lru.remove(1))
        assertEquals(1, lru.size)

        lru.put(3, "c")
        assertEquals("b", lru[2], "the removed entry must not still be occupying the window")
        assertEquals("c", lru[3])
    }
}
