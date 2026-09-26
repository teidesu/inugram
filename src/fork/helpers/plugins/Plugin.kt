package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginManifest
import java.io.File
import org.telegram.messenger.LocaleController.formatString
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.R

class PluginFailure(val at: Site, val detail: String) {
    enum class Site(val labelRes: Int?) {
        // failed to load, but kept enabled
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

/** [id] is the install id, not the manifest's: it keys storage and survives renames */
class Plugin(
    val id: String,
    val file: File,
    @Volatile var source: String,
    @Volatile var manifest: PluginManifest,
) {
    // written on the ui thread, read from the plugin queue
    @Volatile var enabled: Boolean = true

    /** from the dev server: skipped the trust sheet. ui-thread owned */
    @Volatile var dev: Boolean = false

    @Volatile var failure: PluginFailure? = null

    /** written on the plugin queue, read on the ui thread by `PluginActions.render` */
    @Volatile var session: PluginSession? = null

    val engine: QuickJs? get() = session?.engine

    /** set on the plugin queue, read on the ui thread */
    val settingsPageId: Long? get() = session?.settingsPageId

    val running: Boolean get() = engine != null
}
