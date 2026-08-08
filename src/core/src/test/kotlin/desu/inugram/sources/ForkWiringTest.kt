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
        val api = forkSource("PluginApi.kt").readText()
        assertTrue(api.contains("PluginJvm.install(engine)"), "nothing installs inu.jvm")
        assertTrue(
            api.contains("PluginJvm.listenerFor(plugin, engine, AppScreen)"),
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
     * a transfer's observer is registered on the app-wide centre, which holds it strongly, so a
     * plugin dropped without `PluginMedia.detach` keeps its engine alive for the life of the process
     */
    @Test
    fun `PluginManager drops a plugin's transfers wherever it drops the plugin`() {
        val lines = forkSource("PluginManager.kt").readText().lines()
        val drops = lines.withIndex().filter { it.value.trim() == "PluginRpc.detach(plugin)" }
        assertEquals(1, drops.size, "the teardown sequence is one function, so a plugin is dropped one way")
        assertEquals(
            "PluginMedia.detach(plugin)",
            lines[drops[0].index + 1].trim(),
            "a dropped plugin keeps its transfers, and through them its engine",
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
    fun `the xposed ops the bridge sends are the ones rust reads`() {
        val bridge = Regex("""const val (OP_\w+) = (\d+)""")
            .findAll(forkSource("PluginXposed.kt").readText())
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        val native = Regex("""const (OP_\w+): i32 = (\d+);""")
            .findAll(File(forkRoot(), "src/rust/inu_native/src/platform/xposed.rs").readText())
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertTrue(bridge.isNotEmpty(), "read no ops out of PluginXposed.kt")
        assertTrue(native.isNotEmpty(), "read no ops out of xposed.rs")
        assertEquals(native, bridge, "PluginXposed and xposed.rs disagree about the xposed op numbering")
    }
}
