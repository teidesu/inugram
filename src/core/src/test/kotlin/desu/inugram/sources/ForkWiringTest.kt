package desu.inugram.sources

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Fork wiring that no test target can reach, either because the file doing the wiring needs an
 * `Activity` or the JNI library, or because both halves of an agreement are constants each side
 * drives its own tests off. Both go silent rather than red when they break.
 */
class ForkWiringTest {
    /**
     * the two files that own `inu.jvm`'s lifetime are two nothing else compiles, and nothing else in
     * the tree says an engine that stopped lets go of its handles or that an uninstall takes the dex
     * with it
     */
    @Test
    fun `the inu-jvm lifetime wiring is in the files no test target compiles`() {
        val bindings = forkSource("EngineBindings.kt").readText()
        assertTrue(bindings.contains("PluginJvm.install(engine)"), "nothing installs inu.jvm")
        assertTrue(
            bindings.contains("PluginJvm.listenerFor(plugin, engine, AppScreen)"),
            "nothing gives inu.jvm the screen it reads the current fragment off",
        )

        val manager = forkSource("PluginManager.kt").readText()
        assertTrue(
            manager.contains("PluginJvm.detach(engine)"),
            "an engine that stopped, or failed to load, keeps every java object its plugin held",
        )
        assertEquals(
            2,
            Regex("""teardown\(plugin, engine""").findAll(manager).count(),
            "the one teardown sequence must run from both the load-failure path and the stop path",
        )
        assertTrue(manager.contains("PluginJvm.wipe(plugin.id)"), "uninstalling leaves the staged dex behind")
    }

    /**
     * Six objects own a directory keyed by install id, and `remove` is a hand-written list of them.
     * A seventh that forgets to join it leaks that plugin's tree for good - nothing reads it again,
     * nothing reports it, and the id is minted fresh on the next install - so the list is derived
     * from the declarations rather than kept in step with them by hand.
     */
    @Test
    fun `everything keyed by install id is wiped when the plugin is uninstalled`() {
        val owners = File(forkRoot(), "src/fork/helpers/plugins").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("fun wipe(installId: String)") }
            .map { it.nameWithoutExtension }
            .toSortedSet()
        assertTrue(owners.isNotEmpty(), "the wipe signature changed, so this lint now checks nothing")

        val remove = bodyOf(forkSource("PluginManager.kt").readText(), "fun remove(plugin: Plugin)")
        assertEquals(
            emptyList(),
            owners.filterNot { remove.contains("$it.wipe(plugin.id)") },
            "uninstalling a plugin leaves this much of it on the device forever",
        )
    }

    /**
     * a transfer's observer is registered on the app-wide centre, which holds it strongly, so a
     * plugin dropped without `PluginMedia.detach` keeps its engine alive for the life of the process
     */
    @Test
    fun `PluginManager drops a plugin's transfers wherever it drops the plugin`() {
        val lines = forkSource("PluginManager.kt").readText().lines()
        val drops = lines.withIndex().filter { it.value.trim() == "PluginRpc.detach(plugin)" }
        assertEquals(1, drops.size, "the teardown sequence is one function, so a plugin is dropped one way")
        assertEquals(
            listOf("PluginUpdates.detach(plugin)", "TlHandles.endDetach(plugin)", "PluginMedia.detach(plugin)"),
            lines.drop(drops[0].index + 1).take(3).map { it.trim() },
            "a dropped plugin keeps its transfers, and through them its engine - and the handle " +
                "table is released after both chains have abandoned, never before",
        )
        assertEquals(
            "TlHandles.beginDetach(plugin)",
            lines[drops[0].index - 1].trim(),
            "an abandon restarting a chain would read a leaving plugin as live",
        )
    }

    /**
     * `native_bindRequestToGuid` is `public static native`, so nothing can observe the call: a device
     * cannot override it and there is no other target. What a guid buys is `cancelRequestsForGuid`
     * walking the request, which is silent when it misses.
     */
    @Test
    fun `a guid the app bound before the chain armed is re-applied behind the passthrough`() {
        val source = forkSource("PluginRpc.kt").readText()
        assertEquals(
            2,
            Regex("""ConnectionsManager\.native_bindRequestToGuid\(""").findAll(source).count(),
            "one call re-applies a guid bound before sendRequestInternal ran, the other one bound " +
                "while the chain held the request; without either, cancelRequestsForGuid walks past it",
        )
    }

    /**
     * `PluginXposed` reaches lsplant through JNI, so no test target loads it and every rust test
     * drives the op numbering off rust's own constants. A renumbered op is therefore green on both
     * sides and wrong only on a device, where it lands as one hooking op doing another's work. The
     * `keep in sync` comment on each half is what this makes true.
     */
    @Test
    fun `the xposed ops the bridge sends are the ones rust reads`() =
        assertOpsAgree("PluginXposed.kt", "src/native/src/api/platform/xposed/mod.rs")

    /**
     * the same hazard one api over: `inu.ui.dialog`/`prompt`/`chooser` cross as one upcall, so the
     * op is the only thing saying which modal was asked for, and rust picks it at a JNI call site
     * no rust test reaches while the device suite drives the host half off Kotlin's own constants.
     */
    @Test
    fun `the ui modal ops the bridge sends are the ones rust reads`() =
        assertOpsAgree("PluginUi.kt", "src/native/src/api/ui/mod.rs")

    private fun assertOpsAgree(kotlinFile: String, nativePath: String) {
        val bridge = Regex("""const val (OP_\w+) = (\d+)""")
            .findAll(forkSource(kotlinFile).readText())
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        val native = Regex("""const (OP_\w+): i32 = (\d+);""")
            .findAll(File(forkRoot(), nativePath).readText())
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertTrue(bridge.isNotEmpty(), "read no ops out of $kotlinFile")
        assertTrue(native.isNotEmpty(), "read no ops out of $nativePath")
        assertEquals(native, bridge, "$kotlinFile and $nativePath disagree about the op numbering")
    }
}
