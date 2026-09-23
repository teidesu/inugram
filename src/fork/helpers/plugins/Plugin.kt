package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginManifest
import java.io.File
import org.telegram.messenger.LocaleController.formatString
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.R

/**
 * Why a plugin is not running. [Site.REFUSED] means its code never ran and leaves it enabled.
 * For example, a plugin requiring a newer `@plugin-api` can start after the app updates.
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
    // written on the UI thread, read from the plugin queue by every queued engine op
    @Volatile var enabled: Boolean = true

    /** the source on disk came from the dev server, so it passed no trust or permission sheet; UI-thread owned */
    @Volatile var dev: Boolean = false

    /** why the plugin isn't running, shown in the settings list; null when healthy */
    @Volatile var failure: PluginFailure? = null

    /**
     * non-null while the plugin is running. written only on the plugin queue, but read from the ui thread
     * too - `PluginActions.render` takes the live order there before it posts.
     */
    @Volatile var session: PluginSession? = null

    val engine: QuickJs? get() = session?.engine

    /**
     * the page id passed to `inu.registerSettings`, backing the settings button in the plugins
     * list. set on the plugin queue during evaluation, read from the UI thread; cleared on stop.
     */
    val settingsPageId: Long? get() = session?.settingsPageId

    val running: Boolean get() = engine != null
}
