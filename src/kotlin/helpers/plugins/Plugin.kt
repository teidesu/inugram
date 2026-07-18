package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginManifest
import desu.inugram.core.plugins.PluginPermissions
import java.io.File

/** an installed plugin + its runtime state. one [QuickJs] engine per running plugin. */
class Plugin(
    val file: File,
    var source: String,
    var manifest: PluginManifest,
) {
    val id: String get() = manifest.id

    // recomputed (not cached): manifest can be replaced wholesale on reload()
    val permissions: PluginPermissions get() = PluginPermissions.parse(manifest.grants)

    var enabled: Boolean = true

    /** last load/run error, shown in the settings list; null when healthy */
    var error: String? = null

    /** non-null while the plugin is running */
    var engine: QuickJs? = null

    /**
     * the page id passed to `inu.registerSettings`, backing the settings button in the plugins
     * list. set on globalQueue during evaluation, read from the UI thread; cleared on stop.
     */
    @Volatile var settingsPageId: Long? = null

    val running: Boolean get() = engine != null
}
