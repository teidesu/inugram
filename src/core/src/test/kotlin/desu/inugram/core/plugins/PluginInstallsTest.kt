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
    fun minted_id_is_lowercase_hex_of_fixed_length() {
        val id = PluginInstalls.mintId(seeded())
        assertEquals(PluginInstalls.ID_LENGTH, id.length)
        assertTrue(id, id.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(PluginInstalls.isValidId(id))
    }

    @Test
    fun minted_ids_differ() {
        val random = seeded()
        assertNotEquals(PluginInstalls.mintId(random), PluginInstalls.mintId(random))
        assertNotEquals(PluginInstalls.mintId(), PluginInstalls.mintId())
    }

    @Test
    fun is_valid_id_rejects_anything_that_could_name_a_path() {
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
    fun file_name_round_trips_through_id_of_file() {
        assertEquals(a, PluginInstalls.readInstallIdFromFileName(PluginInstalls.fileName(a)))
        assertEquals("$a.js", PluginInstall(a, true).file)
    }

    @Test
    fun id_of_file_ignores_everything_else() {
        assertNull(PluginInstalls.readInstallIdFromFileName("plugin.js"))
        assertNull(PluginInstalls.readInstallIdFromFileName(a))
        assertNull(PluginInstalls.readInstallIdFromFileName("$a.js.tmp"))
        assertNull(PluginInstalls.readInstallIdFromFileName("${a.uppercase()}.js"))
    }

    @Test
    fun reconcile_keeps_persisted_order_and_flags() {
        val persisted = listOf(
            PluginInstall(b, false, "x.second", dev = true),
            PluginInstall(a, true, "x.first"),
        )
        assertEquals(persisted, PluginInstalls.reconcile(persisted, listOf("$a.js", "$b.js")))
    }

    @Test
    fun reconcile_appends_unrecorded_files_disabled_in_id_order() {
        val known = PluginInstall(b, true)
        val out = PluginInstalls.reconcile(listOf(known), listOf("$c.js", "$b.js", "$a.js"))
        assertEquals(listOf(known, PluginInstall(a, false), PluginInstall(c, false)), out)
    }

    @Test
    fun reconcile_keeps_every_id_when_the_persisted_state_is_lost() {
        val out = PluginInstalls.reconcile(emptyList(), listOf("$a.js", "$b.js"))
        assertEquals(listOf(a, b), out.map { it.id })
    }

    @Test
    fun reconcile_drops_records_whose_file_is_gone() {
        assertEquals(emptyList<PluginInstall>(), PluginInstalls.reconcile(listOf(PluginInstall(a, true)), emptyList()))
    }

    @Test
    fun reconcile_ignores_files_not_named_after_an_install() {
        assertEquals(emptyList<PluginInstall>(), PluginInstalls.reconcile(emptyList(), listOf("plugin.js", "$a.js.tmp")))
    }

    @Test
    fun reconcile_keeps_only_the_first_record_per_id() {
        val first = PluginInstall(a, true)
        val out = PluginInstalls.reconcile(listOf(first, PluginInstall(a, false)), listOf("$a.js"))
        assertEquals(listOf(first), out)
    }
}
