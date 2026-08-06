package desu.inugram.helpers.plugins


/**
 * stands in for the real [PluginManager], which reaches `Context`, `AlarmManager`, `ShortcutManager`
 * and the app's own asset dir. [PluginRpc] only ever asks it for the plugin list, whose *order* is
 * the chain order, so the harness owns that list directly.
 *
 * Guarded by `PluginManagerSeamTest`: the real source must still expose exactly this much.
 */
object PluginManager {
    var installed: List<Plugin> = emptyList()

    fun plugins(): List<Plugin> = installed
}
