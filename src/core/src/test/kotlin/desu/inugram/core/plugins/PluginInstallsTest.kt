package desu.inugram.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PluginInstallsTest {
    private fun seeded() = Random(1337)

    private val a = "a".repeat(32)
    private val b = "b".repeat(32)
    private val c = "c".repeat(32)

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
    fun fileNameRoundTripsThroughIdOfFile() {
        assertEquals(a, PluginInstalls.idOfFile(PluginInstalls.fileName(a)))
        assertEquals("$a.js", PluginInstall(a, true).file)
    }

    @Test
    fun idOfFileIgnoresEverythingElse() {
        assertNull(PluginInstalls.idOfFile("plugin.js"))
        assertNull(PluginInstalls.idOfFile(a))
        assertNull(PluginInstalls.idOfFile("$a.js.tmp"))
        assertNull(PluginInstalls.idOfFile("${a.uppercase()}.js"))
    }

    @Test
    fun reconcileKeepsPersistedOrderAndFlags() {
        val persisted = listOf(
            PluginInstall(b, false, "x.second", dev = true),
            PluginInstall(a, true, "x.first"),
        )
        assertEquals(persisted, PluginInstalls.reconcile(persisted, listOf("$a.js", "$b.js")))
    }

    @Test
    fun reconcileAppendsUnrecordedFilesDisabledInIdOrder() {
        val known = PluginInstall(b, true)
        val out = PluginInstalls.reconcile(listOf(known), listOf("$c.js", "$b.js", "$a.js"))
        assertEquals(listOf(known, PluginInstall(a, false), PluginInstall(c, false)), out)
    }

    @Test
    fun reconcileKeepsEveryIdWhenThePersistedStateIsLost() {
        val out = PluginInstalls.reconcile(emptyList(), listOf("$a.js", "$b.js"))
        assertEquals(listOf(a, b), out.map { it.id })
    }

    @Test
    fun reconcileDropsRecordsWhoseFileIsGone() {
        assertEquals(emptyList<PluginInstall>(), PluginInstalls.reconcile(listOf(PluginInstall(a, true)), emptyList()))
    }

    @Test
    fun reconcileIgnoresFilesNotNamedAfterAnInstall() {
        assertEquals(emptyList<PluginInstall>(), PluginInstalls.reconcile(emptyList(), listOf("plugin.js", "$a.js.tmp")))
    }

    @Test
    fun reconcileKeepsOnlyTheFirstRecordPerId() {
        val first = PluginInstall(a, true)
        val out = PluginInstalls.reconcile(listOf(first, PluginInstall(a, false)), listOf("$a.js"))
        assertEquals(listOf(first), out)
    }
}
