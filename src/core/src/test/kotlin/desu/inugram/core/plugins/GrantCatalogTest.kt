package desu.inugram.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The catalogue is generated, but the permission sheet's own table is not: android resource ids are
 * compile-time ints, so `PluginInfoActivity` keeps a hand-written `KNOWN_GRANTS` beside it. A grant
 * missing from that table renders as "unknown" instead of failing to build, which is what this
 * catches.
 */
class GrantCatalogTest {
    @Test
    fun everyCatalogueGrantHasAPermissionSheetRow() {
        assertEquals(GrantCatalog.names, knownGrantsTable())
    }

    @Test
    fun listGrantsCarryAVocabularyAndTheOthersDoNot() {
        for (name in GrantCatalog.names) {
            val entry = GrantCatalog.entryOf(name)!!
            assertEquals("$name values", entry.scopes == ScopeKind.LIST, entry.values.isNotEmpty())
            if (entry.scopes == ScopeKind.NONE) {
                assertTrue("$name takes no scopes, so it has no unscoped form", entry.tierWhenUnscoped == null)
            }
        }
    }

    @Test
    fun takeoverIsOnlyRefusedWhereARpcScopeCanNameIt() {
        for (name in GrantCatalog.names) {
            val entry = GrantCatalog.entryOf(name)!!
            if (entry.refusesTakeover) {
                assertEquals("$name refuses takeover methods but takes no rpc scopes", ScopeKind.RPC_METHOD, entry.scopes)
            }
        }
    }

    /** the keys of `PluginInfoActivity`'s `KNOWN_GRANTS`, read out of the source */
    private fun knownGrantsTable(): Set<String> {
        val source = File(forkRoot(), "src/fork/ui/settings/PluginInfoActivity.kt").readText()
        val open = source.indexOf(TABLE_OPEN)
        assertTrue("PluginInfoActivity no longer declares `$TABLE_OPEN`", open >= 0)
        val close = source.indexOf("\n)", open)
        assertTrue("the KNOWN_GRANTS table is not closed", close >= 0)
        return ROW.findAll(source.substring(open, close)).map { it.groupValues[1] }.toSet()
    }

    private fun forkRoot(): File {
        var dir: File? = File(javaClass.protectionDomain.codeSource.location.toURI()).canonicalFile
        while (dir != null && !File(dir, "sdk/types/grants.json").isFile) dir = dir.parentFile
        return dir ?: error("could not locate the repo root")
    }

    private companion object {
        const val TABLE_OPEN = "private val KNOWN_GRANTS = mapOf("
        val ROW = Regex(""""([^"]+)" to GrantPresentation\(""")
    }
}
