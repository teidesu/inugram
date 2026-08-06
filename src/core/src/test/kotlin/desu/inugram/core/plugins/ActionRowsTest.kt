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

    private val chat = 1
    private val message = 2

    @Test
    fun rowsAreCountedPerOwnerAndPerKind() {
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
    fun unregisterDropsOneRowAndForgetDropsTheOwner() {
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
    fun theCapIsPerOwnerPerKindAndNamesTheRowItRefused() {
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
    fun reRegisteringAnIdSwapsItsTokenInPlaceRatherThanAddingARow() {
        val registry = ActionRegistry<Owner>()
        val a = Owner("a")
        assertNull(registry.register(a, chat, 1, "one"))
        assertNull(registry.register(a, chat, 2, "one"))
        assertEquals(1, registry.count(a, chat))
        assertEquals(2, registry.tokenFor(a, chat, "one"))

        registry.unregister(a, chat, 1)
        assertEquals("retiring the displaced token does not take the replacement with it", 1, registry.count(a, chat))
        assertEquals(2, registry.tokenFor(a, chat, "one"))
    }

    @Test
    fun aKeyedReRegistrationAtTheCapIsAReplacementAndNotANinthRow() {
        val registry = ActionRegistry<Owner>(perKindLimit = 2)
        val a = Owner("a")
        assertNull(registry.register(a, chat, 1, "one"))
        assertNull(registry.register(a, chat, 2, "two"))
        assertNull("updating a row is how a plugin changes it, and the cap must not forbid that", registry.register(a, chat, 3, "one"))
        assertEquals(2, registry.count(a, chat))
        assertEquals(3, registry.tokenFor(a, chat, "one"))
        assertNotNull("a genuinely new row is still refused", registry.register(a, chat, 4, "three"))
    }

    @Test
    fun aDisposedRowFreesItsPlaceUnderTheCap() {
        val registry = ActionRegistry<Owner>(perKindLimit = 1)
        val a = Owner("a")
        assertNull(registry.register(a, chat, 1, "one"))
        assertNotNull(registry.register(a, chat, 2, "two"))
        registry.unregister(a, chat, 1)
        assertNull(registry.register(a, chat, 3, "three"))
    }

    @Test
    fun rowsFollowTheGivenOrderNotTheRegistrationOrder() {
        val registry = ActionRegistry<Owner>()
        val a = Owner("a")
        val b = Owner("b")
        val c = Owner("c")
        registry.register(c, chat, 1, "c")
        registry.register(a, chat, 1, "a")
        registry.register(a, chat, 2, "a2")

        val render = { owner: Owner -> listOf(ActionRow(owner, 1, owner.name)) }
        assertEquals(
            listOf(ActionRow(a, 1, "a"), ActionRow(c, 1, "c")),
            registry.rowsInOrder(chat, listOf(a, b, c), render),
        )
        assertEquals(
            listOf(ActionRow(c, 1, "c"), ActionRow(a, 1, "a")),
            registry.rowsInOrder(chat, listOf(c, b, a), render),
        )
    }

    @Test
    fun anOwnerMissingFromTheOrderIsNeitherAskedNorDrawn() {
        val registry = ActionRegistry<Owner>()
        val a = Owner("a")
        val gone = Owner("gone")
        registry.register(a, chat, 1, "a")
        registry.register(gone, chat, 1, "gone")

        val asked = mutableListOf<Owner>()
        val out = registry.rowsInOrder(chat, listOf(a)) { owner ->
            asked.add(owner)
            listOf(ActionRow(owner, 1, owner.name))
        }
        assertEquals(listOf(a), asked)
        assertEquals(listOf(ActionRow(a, 1, "a")), out)
        assertEquals("a menu reserves no room for an owner that is gone", 1, registry.size(chat, listOf(a)))
    }

    @Test
    fun anOwnerWithNoRowsOfThatKindIsNotAsked() {
        val registry = ActionRegistry<Owner>()
        val a = Owner("a")
        val b = Owner("b")
        registry.register(a, chat, 1, "a")
        registry.register(b, message, 1, "b")

        val asked = mutableListOf<Owner>()
        registry.rowsInOrder(chat, listOf(a, b)) { owner ->
            asked.add(owner)
            emptyList()
        }
        assertEquals(listOf(a), asked)
    }

    @Test
    fun anEngineThatCouldNotAnswerContributesNothingAndDoesNotStopTheRest() {
        val registry = ActionRegistry<Owner>()
        val a = Owner("a")
        val b = Owner("b")
        registry.register(a, chat, 1, "a")
        registry.register(b, chat, 1, "b")

        val out = registry.rowsInOrder(chat, listOf(a, b)) { owner ->
            if (owner === a) null else listOf(ActionRow(owner, 1, "B"))
        }
        assertEquals(listOf(ActionRow(b, 1, "B")), out)
    }
}
