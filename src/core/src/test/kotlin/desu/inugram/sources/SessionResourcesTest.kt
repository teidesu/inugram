package desu.inugram.sources

import java.io.File
import kotlin.test.assertTrue
import org.junit.Test

class SessionResourcesTest {
    /** a resource left out of the teardown keeps a stopped plugin's state alive, and nothing turns red */
    @Test
    fun everySessionResourceIsDetachedByTheTeardown() {
        val declared = File(forkRoot(), "src/fork/helpers/plugins").walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { Regex("""object (\w+) : SessionResource\b""").findAll(it.readText()).map { m -> m.groupValues[1] } }
            .toList()
        assertTrue(declared.size >= 11, "found only $declared")
        val manager = forkSource("PluginManager.kt").readText()
        val lists = manager.substring(manager.indexOf("private val CHAIN_OWNERS"), manager.indexOf("private fun teardown"))
        for (name in declared) assertTrue(Regex("\\b$name\\b").containsMatchIn(lists), "$name is never detached")
    }
}
