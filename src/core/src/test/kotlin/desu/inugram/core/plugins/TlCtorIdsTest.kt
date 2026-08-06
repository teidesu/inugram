package desu.inugram.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TlCtorIdsTest {
    @Test
    fun messageResolvesToASetWithLegacyVariantsIncluded() {
        val ids = TlCtorIds.idsOf("message")
        assertTrue(ids != null && ids.size > 1)
    }

    @Test
    fun updateServiceNotificationIsAKnownUpdate() {
        assertTrue("updateServiceNotification" in TlCtorIds.updateNames)
        assertEquals(1, TlCtorIds.idsOf("updateServiceNotification")?.size)
    }

    @Test
    fun usersGetUsersIsAKnownMethod() {
        assertTrue("users.getUsers" in TlCtorIds.methodNames)
    }

    @Test
    fun unknownNameResolvesToNull() {
        assertNull(TlCtorIds.idsOf("this.does.not.exist"))
    }

    @Test
    fun methodNamesAndUpdateNamesAreDisjoint() {
        assertTrue(TlCtorIds.methodNames.intersect(TlCtorIds.updateNames).isEmpty())
    }

    @Test
    fun legacyVariantIdLandsInTheModernSet() {
        // TL_message_old7#5ba66c13 extends TL_message in TLRPC.java - java inheritance only, wire name is `message`
        val legacyId = 0x5ba66c13.toInt()
        assertTrue(legacyId in TlCtorIds.idsOf("message").orEmpty())
    }
}
