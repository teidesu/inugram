package desu.inugram.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionRowsTest {
    private class Owner(val name: String) {
        override fun toString(): String = name
    }

    private fun ActionRegistry<Owner>.ids(kind: Int, order: List<Owner>, placements: Int = -1): List<Pair<Owner, String>> =
        registrationsInOrder(kind, order, placements).map { it.owner to it.id }

    private fun ActionRegistry<Owner>.findToken(owner: Owner, kind: Int, id: String): Int =
        registrationsInOrder(kind, listOf(owner)).single { it.id == id }.token

    private val chat = 1
    private val message = 2

    @Test
    fun rows_are_counted_per_owner_and_per_kind() {
        val registry = ActionRegistry<Owner>()
        val a = Owner("a")
        val b = Owner("b")
        assertNull(registry.register(a, chat, 1, "one"))
        assertNull(registry.register(a, chat, 2, "two"))
        assertNull(registry.register(a, message, 3, "three"))
        assertNull(registry.register(b, chat, 1, "one"))

        assertEquals(2, registry.count(a, chat))
        assertEquals(1, registry.count(a, message))
        assertEquals(1, registry.count(b, chat))
        assertEquals(0, registry.count(b, message))
        assertEquals(3, registry.size(chat, listOf(a, b)))
        assertEquals(1, registry.size(message, listOf(a, b)))
    }

    @Test
    fun unregister_drops_one_row_and_forget_drops_the_owner() {
        val registry = ActionRegistry<Owner>()
        val a = Owner("a")
        registry.register(a, chat, 1, "one")
        registry.register(a, chat, 2, "two")
        registry.unregister(a, chat, 1)
        assertEquals(1, registry.count(a, chat))
        registry.unregister(a, chat, 1)
        assertEquals("unregistering twice removes one row, not two", 1, registry.count(a, chat))

        registry.register(a, message, 3, "three")
        registry.forget(a)
        assertEquals(0, registry.count(a, chat))
        assertEquals(0, registry.count(a, message))
    }

    @Test
    fun the_cap_is_per_owner_per_kind_and_names_the_row_it_refused() {
        val registry = ActionRegistry<Owner>(perKindLimit = 2)
        val a = Owner("a")
        val b = Owner("b")
        assertNull(registry.register(a, chat, 1, "one"))
        assertNull(registry.register(a, chat, 2, "two"))
        val refused = registry.register(a, chat, 3, "three")
        assertNotNull(refused)
        assertTrue(refused!!, refused.contains("three"))
        assertEquals(2, registry.count(a, chat))

        assertNull(registry.register(a, message, 4, "elsewhere"))
        assertNull(registry.register(b, chat, 1, "other plugin"))
    }

    @Test
    fun placements_are_capped_and_listed_independently() {
        val registry = ActionRegistry<Owner>(perKindLimit = 2)
        val owner = Owner("a")
        val bubble = 1
        val selection = 2
        assertNull(registry.register(owner, message, 1, "bubble-1", bubble))
        assertNull(registry.register(owner, message, 2, "bubble-2", bubble))
        assertNull(registry.register(owner, message, 3, "selection-1", selection))
        assertNull(registry.register(owner, message, 4, "selection-2", selection))
        assertNotNull(registry.register(owner, message, 5, "bubble-3", bubble))
        assertNotNull(registry.register(owner, message, 6, "both", bubble or selection))

        assertEquals(2, registry.count(owner, message, bubble))
        assertEquals(2, registry.count(owner, message, selection))
        assertEquals(
            listOf(owner to "bubble-1", owner to "bubble-2"),
            registry.ids(message, listOf(owner), bubble),
        )
        assertEquals(
            listOf(owner to "selection-1", owner to "selection-2"),
            registry.ids(message, listOf(owner), selection),
        )
    }

    @Test
    fun re_registering_an_id_swaps_its_token_in_place_rather_than_adding_a_row() {
        val registry = ActionRegistry<Owner>()
        val a = Owner("a")
        assertNull(registry.register(a, chat, 1, "one"))
        assertNull(registry.register(a, chat, 2, "one"))
        assertEquals(1, registry.count(a, chat))
        assertEquals(2, registry.findToken(a, chat, "one"))

        registry.unregister(a, chat, 1)
        assertEquals("retiring the displaced token does not take the replacement with it", 1, registry.count(a, chat))
        assertEquals(2, registry.findToken(a, chat, "one"))
    }

    @Test
    fun a_keyed_re_registration_at_the_cap_is_a_replacement_and_not_a_ninth_row() {
        val registry = ActionRegistry<Owner>(perKindLimit = 2)
        val a = Owner("a")
        assertNull(registry.register(a, chat, 1, "one"))
        assertNull(registry.register(a, chat, 2, "two"))
        assertNull("updating a row is how a plugin changes it, and the cap must not forbid that", registry.register(a, chat, 3, "one"))
        assertEquals(2, registry.count(a, chat))
        assertEquals(3, registry.findToken(a, chat, "one"))
        assertNotNull("a genuinely new row is still refused", registry.register(a, chat, 4, "three"))
    }

    @Test
    fun a_disposed_row_frees_its_place_under_the_cap() {
        val registry = ActionRegistry<Owner>(perKindLimit = 1)
        val a = Owner("a")
        assertNull(registry.register(a, chat, 1, "one"))
        assertNotNull(registry.register(a, chat, 2, "two"))
        registry.unregister(a, chat, 1)
        assertNull(registry.register(a, chat, 3, "three"))
    }

    @Test
    fun rows_follow_the_given_order_not_the_registration_order() {
        val registry = ActionRegistry<Owner>()
        val a = Owner("a")
        val b = Owner("b")
        val c = Owner("c")
        registry.register(c, chat, 1, "c")
        registry.register(a, chat, 1, "a")
        registry.register(a, chat, 2, "a2")

        assertEquals(listOf(a to "a", a to "a2", c to "c"), registry.ids(chat, listOf(a, b, c)))
        assertEquals(listOf(c to "c", a to "a", a to "a2"), registry.ids(chat, listOf(c, b, a)))
    }

    @Test
    fun ids_and_tokens_can_be_resolved_in_live_owner_order() {
        val registry = ActionRegistry<Owner>()
        val a = Owner("a")
        val b = Owner("b")
        registry.register(a, chat, 4, "first")
        registry.register(a, chat, 7, "second")
        registry.register(b, chat, 2, "third")

        assertEquals("second", registry.registrationsInOrder(chat, listOf(a)).single { it.token == 7 }.id)
        assertEquals(listOf(b to "third", a to "first", a to "second"), registry.ids(chat, listOf(b, a)))
    }

    @Test
    fun registrations_carry_cached_presentation_and_dynamic_fields() {
        val registry = ActionRegistry<Owner>()
        val owner = Owner("a")
        registry.register(owner, chat, 7, "row", text = "Static", icon = "rmsg_pin", dynamicFields = 4)

        assertEquals(
            listOf(ActionRegistration(owner, chat, "row", 7, 1, "Static", "rmsg_pin", 4)),
            registry.registrationsInOrder(chat, listOf(owner)),
        )
    }

    @Test
    fun an_owner_missing_from_the_order_is_not_drawn() {
        val registry = ActionRegistry<Owner>()
        val a = Owner("a")
        val gone = Owner("gone")
        registry.register(a, chat, 1, "a")
        registry.register(gone, chat, 1, "gone")

        assertEquals(listOf(a to "a"), registry.ids(chat, listOf(a)))
        assertEquals("a menu reserves no room for an owner that is gone", 1, registry.size(chat, listOf(a)))
    }
}
