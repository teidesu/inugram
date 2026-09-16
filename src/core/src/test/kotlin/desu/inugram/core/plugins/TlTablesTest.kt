package desu.inugram.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TlTablesTest {
    @Test
    fun messageResolvesToASetWithLegacyVariantsIncluded() {
        val ids = TlTables.idsOf("message")
        assertTrue(ids != null && ids.size > 1)
    }

    @Test
    fun updateServiceNotificationIsAKnownUpdate() {
        assertTrue("updateServiceNotification" in TlTables.updateNames)
        assertEquals(1, TlTables.idsOf("updateServiceNotification")?.size)
    }

    @Test
    fun usersGetUsersIsAKnownMethod() {
        assertTrue("users.getUsers" in TlTables.methodNames)
    }

    @Test
    fun unknownNameResolvesToNull() {
        assertNull(TlTables.idsOf("this.does.not.exist"))
        assertEquals(emptySet<String>(), TlTables.namesOf(0x1234))
    }

    /** what `invokeRaw` reads the takeover refusal off: the bytes name a constructor and nothing else */
    @Test
    fun everyIdResolvesBackToTheNamesThatClaimIt() {
        for (name in listOf("users.getUsers", "message", "auth.exportLoginToken")) {
            for (id in TlTables.idsOf(name).orEmpty()) {
                val resolved = TlTables.namesOf(id)
                assertTrue("$name id $id resolved to $resolved", name in resolved)
                for (other in resolved) assertTrue("$other does not claim $id", id in TlTables.idsOf(other).orEmpty())
            }
        }
    }

    /**
     * `invokeRaw` can only ask about the id the payload opens with, so a shared id whose names
     * disagree about being a takeover method would let one of them through under the other's name.
     */
    @Test
    fun noSharedIdDisagreesAboutBeingATakeoverMethod() {
        for (name in TlTables.allNames) {
            for (id in TlTables.idsOf(name).orEmpty()) {
                val names = TlTables.namesOf(id)
                val blocked = names.filter { TakeoverMethods.isBlocked(it) }
                assertTrue("id $id is a takeover method under $blocked but not under $names", blocked.isEmpty() || blocked.size == names.size)
            }
        }
    }

    @Test
    fun methodNamesAndUpdateNamesAreDisjoint() {
        assertTrue(TlTables.methodNames.intersect(TlTables.updateNames).isEmpty())
    }

    @Test
    fun legacyVariantIdLandsInTheModernSet() {
        // TL_message_old7#5ba66c13 extends TL_message in TLRPC.java - java inheritance only, wire name is `message`
        val legacyId = 0x5ba66c13.toInt()
        assertTrue(legacyId in TlTables.idsOf("message").orEmpty())
    }
}
