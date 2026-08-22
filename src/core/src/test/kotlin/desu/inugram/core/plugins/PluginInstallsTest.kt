package desu.inugram.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PluginInstallsTest {
    private fun seeded() = Random(1337)

    @Test
    fun mintedIdIsLowercaseHexOfFixedLength() {
        val id = PluginInstalls.mintId(seeded())
        assertEquals(PluginInstalls.ID_LENGTH, id.length)
        assertTrue(id, id.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(PluginInstalls.isValidId(id))
    }

    @Test
    fun mintedIdsDiffer() {
        val random = seeded()
        assertNotEquals(PluginInstalls.mintId(random), PluginInstalls.mintId(random))
        assertNotEquals(PluginInstalls.mintId(), PluginInstalls.mintId())
    }

    @Test
    fun isValidIdRejectsAnythingThatCouldNameAPath() {
        assertFalse(PluginInstalls.isValidId(null))
        assertFalse(PluginInstalls.isValidId(""))
        assertFalse(PluginInstalls.isValidId("inugram.dev/My awesome plugin"))
        assertFalse(PluginInstalls.isValidId("../".repeat(PluginInstalls.ID_LENGTH / 3)))
        assertFalse(PluginInstalls.isValidId("0".repeat(PluginInstalls.ID_LENGTH - 1)))
        assertFalse(PluginInstalls.isValidId("0".repeat(PluginInstalls.ID_LENGTH + 1)))
        assertFalse(PluginInstalls.isValidId("A".repeat(PluginInstalls.ID_LENGTH)))
        assertFalse(PluginInstalls.isValidId("g".repeat(PluginInstalls.ID_LENGTH)))
    }

    @Test
    fun reconcileKeepsPersistedIdsOrderAndEnabledBits() {
        val persisted = listOf(
            PluginInstall("a".repeat(32), "second.js", false),
            PluginInstall("b".repeat(32), "first.js", true),
        )
        val out = PluginInstalls.reconcile(persisted, listOf("first.js", "second.js"), seeded())
        assertEquals(persisted, out)
    }

    @Test
    fun reconcileMintsForUnknownFilesAndAppendsThemEnabled() {
        val known = PluginInstall("a".repeat(32), "old.js", false)
        val out = PluginInstalls.reconcile(listOf(known), listOf("new.js", "old.js"), seeded())
        assertEquals(listOf("old.js", "new.js"), out.map { it.file })
        assertEquals(known, out[0])
        assertTrue(out[1].enabled)
        assertTrue(PluginInstalls.isValidId(out[1].id))
        assertNotEquals(known.id, out[1].id)
    }

    @Test
    fun reconcileDropsRecordsWhoseFileIsGone() {
        val out = PluginInstalls.reconcile(
            listOf(PluginInstall("a".repeat(32), "gone.js", true)),
            emptyList(),
            seeded(),
        )
        assertEquals(emptyList<PluginInstall>(), out)
    }

    @Test
    fun reconcileRemintsUnusableIdsWithoutLosingTheSlot() {
        val out = PluginInstalls.reconcile(
            listOf(PluginInstall("inugram.dev/plugin", "p.js", false)),
            listOf("p.js"),
            seeded(),
        )
        assertEquals(1, out.size)
        assertEquals("p.js", out[0].file)
        assertFalse(out[0].enabled)
        assertTrue(PluginInstalls.isValidId(out[0].id))
    }

    @Test
    fun reconcileKeepsTheIdentityOfARecordItRemints() {
        val out = PluginInstalls.reconcile(
            listOf(PluginInstall("nope", "p.js", true, "teidesu\u0000my plugin")),
            listOf("p.js"),
            seeded(),
        )
        assertEquals("teidesu\u0000my plugin", out[0].identity)
    }

    @Test
    fun reconcileKeepsTheDevBitOfARecordItRemints() {
        val out = PluginInstalls.reconcile(
            listOf(PluginInstall("nope", "p.js", true, "teidesu\u0000my plugin", dev = true)),
            listOf("p.js"),
            seeded(),
        )
        assertTrue(out[0].dev)
    }

    @Test
    fun reconcileKeepsOnlyTheFirstRecordPerFile() {
        val first = PluginInstall("a".repeat(32), "p.js", true)
        val out = PluginInstalls.reconcile(
            listOf(first, PluginInstall("b".repeat(32), "p.js", false)),
            listOf("p.js"),
            seeded(),
        )
        assertEquals(listOf(first), out)
    }

    @Test
    fun reconcileGivesTwoCopiesOfTheSamePluginSeparateIdentities() {
        val out = PluginInstalls.reconcile(emptyList(), listOf("dup.js", "dup-1.js"), seeded())
        assertNotEquals(out[0].id, out[1].id)
    }
}
