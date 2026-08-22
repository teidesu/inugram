package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginManifest
import desu.inugram.core.plugins.PluginPermissions
import java.io.File
import org.telegram.messenger.LocaleController.formatString
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.R

/**
 * why a plugin isn't running.
 *
 * [Site.REFUSED] is the one case where the plugin's own code never ran, and the only one that
 * leaves it switched on: an app too old for its `@plugin-api` starts working on its own once the
 * app updates, so switching it off would just hide it.
 */
class PluginFailure(val at: Site, val detail: String) {
    enum class Site(val labelRes: Int?) {
        REFUSED(null),
        LOAD(R.string.InuPluginsFailedAtLoad),
        RUNTIME(R.string.InuPluginsFailedAtRuntime),
        UNLOAD(R.string.InuPluginsFailedAtUnload),
    }

    val disables: Boolean get() = at != Site.REFUSED

    fun describe(): String {
        val head = detail.lineSequence().firstOrNull()?.trim().orEmpty()
        val label = at.labelRes ?: return head
        return formatString(R.string.InuPluginsFailedFormat, getString(label), head)
    }
}

/**
 * an installed plugin + its runtime state. one [QuickJs] engine per running plugin.
 *
 * [id] is the install id ([desu.inugram.core.plugins.PluginInstalls]), not anything [manifest] says:
 * it keys the plugin's storage and survives the plugin renaming itself.
 */
class Plugin(
    val id: String,
    val file: File,
    @Volatile var source: String,
    @Volatile var manifest: PluginManifest,
) {
    private class ParsedGrants(val of: PluginManifest, val permissions: PluginPermissions)

    // one field, because both halves are read from globalQueue and from stageQueue (PluginRpc's
    // chainFor, off onUpdates): as two plain writes the identity could publish ahead of the
    // permissions, and a reload that dropped a grant would still authorize an interceptor on it
    @Volatile private var parsed: ParsedGrants? = null

    val permissions: PluginPermissions
        get() {
            val current = manifest
            parsed?.let { if (it.of === current) return it.permissions }
            return PluginPermissions.parse(current.grants).also { parsed = ParsedGrants(current, it) }
        }

    // written on the UI thread, read from globalQueue by every queued engine op
    @Volatile var enabled: Boolean = true

    /** the source on disk came from the dev server, so it passed no trust or permission sheet */
    @Volatile var dev: Boolean = false

    /** why the plugin isn't running, shown in the settings list; null when healthy */
    @Volatile var failure: PluginFailure? = null

    /**
     * non-null while the plugin is running. written only on globalQueue, but read from the ui thread
     * too - `PluginActions.render` takes the live order there before it posts.
     */
    @Volatile var engine: QuickJs? = null

    /**
     * the page id passed to `inu.registerSettings`, backing the settings button in the plugins
     * list. set on globalQueue during evaluation, read from the UI thread; cleared on stop.
     */
    @Volatile var settingsPageId: Long? = null

    val running: Boolean get() = engine != null
}
