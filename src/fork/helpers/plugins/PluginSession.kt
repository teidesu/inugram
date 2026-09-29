package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginPermissions
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlHandles

class PluginSession(val plugin: Plugin, val engine: QuickJs) {
    val manifest = plugin.manifest
    val source = plugin.source
    val permissions = PluginPermissions.parse(manifest.grants)
    val tl = TlHandles(TlFilter.policyFor(permissions))
    val log = PluginLog.of(manifest, plugin.id)

    @Volatile var settingsPageId: Long? = null

    @Volatile private var accepting = true

    fun isCurrent(): Boolean = plugin.session === this

    fun canDispatch(): Boolean = accepting && isCurrent()

    fun stopDispatching() {
        accepting = false
    }
}
