package desu.inugram.core.plugins

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class OwnerRegistryTest {
    @Test
    fun removing_an_owners_last_item_forgets_the_owner() {
        val registry = OwnerRegistry<String, Int>()
        registry.add("a", 1)
        registry.add("a", 2)
        assertEquals(2, registry.remove("a") { it == 2 })
        assertNull(registry.remove("a") { it == 2 })
        assertEquals(1, registry.remove("a") { it == 1 })
        assertEquals(0, registry.count("a"))
        assertEquals(emptyList(), registry.take("a"))
    }

    @Test
    fun take_hands_over_everything_once_and_leaves_other_owners_alone() {
        val registry = OwnerRegistry<String, Int>()
        registry.add("a", 1)
        registry.add("a", 1)
        registry.add("b", 3)
        assertEquals(listOf(1, 1), registry.take("a"))
        assertEquals(emptyList(), registry.take("a"))
        assertEquals(1, registry.count("b"))
    }

    @Test
    fun take_where_takes_every_picked_owner() {
        val registry = OwnerRegistry<String, Int>()
        registry.add("install-1/a", 1)
        registry.add("install-1/b", 2)
        registry.add("install-2/a", 3)
        assertEquals(setOf(1, 2), registry.takeWhere { it.startsWith("install-1/") }.toSet())
        assertEquals(listOf(3), registry.take("install-2/a"))
    }

    @Test
    fun concurrent_adds_are_all_kept() {
        val registry = OwnerRegistry<String, Int>()
        val threads = (0 until 8).map { t -> Thread { repeat(1000) { registry.add("a", t) } } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals(8000, registry.count("a"))
    }
}
