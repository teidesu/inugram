package desu.inugram.helpers.plugins.api

import desu.inugram.core.plugins.FsQuota
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.PluginBridge
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.io.PluginBlobs
import desu.inugram.helpers.plugins.io.PluginTransfers
import desu.inugram.helpers.plugins.io.PluginFs
import desu.inugram.helpers.plugins.platform.PluginJvm
import desu.inugram.helpers.plugins.platform.PluginXposed
import desu.inugram.helpers.plugins.ui.PluginAppVisibility
import org.telegram.ui.LaunchActivity

/**
 * What an engine is wired with, and the order it happens in.
 *
 * Separate from the bridge because none of it is an api: it is the bring-up `PluginManager`
 * runs once per engine, and the ordering constraints among the pieces are the whole content of the
 * file. Split off the api for the same reason the api is not the bridge - a file everything imports
 * is not the file everything belongs in.
 */
object EngineBindings {
    /** the app screen is here rather than in `PluginJvm`, which reaches no `Activity` of its own */
    fun jvmListenerFor(session: PluginSession) = PluginJvm.listenerFor(session, AppScreen)

    /**
     * Everything the engine's own bindings need in place, in the one order that works: the read
     * surface installs from inside `installApi`, taking the peer helpers `inu.utils` leaves behind
     * and the `Account` handles it hangs its getters on, and `inu.xposed` mints every handle its
     * entry points take out of `inu.jvm`'s table.
     */
    fun start(session: PluginSession, bridge: PluginBridge) {
        val quota = FsQuota.forGrants(session.manifest.grants)
        session.engine.start(
            bridge,
            QuickJs.Config(
                spillDir = PluginBlobs.dirFor(session.plugin.id),
                transferDir = PluginTransfers.dirFor(session.plugin.id),
                fsDir = quota?.let { PluginFs.dirFor(session.plugin.id) } ?: "",
                fsQuotaBytes = quota ?: 0,
                fsUnscoped = PluginFs.isUnscoped(session.permissions),
                installFs = quota != null,
                androidDirs = PluginFs.androidDirs(),
                kvPath = PluginKv.pathFor(session.plugin.id),
                installJvm = bridge.jvm != null,
                installXposed = bridge.xposed != null,
                grants = session.permissions,
            ),
        )
        // a plugin loaded while the app is hidden would otherwise tick unthrottled until the next transition; no callback can hear this, its own code not having run yet
        if (!PluginAppVisibility.isForeground) session.engine.appVisibilityChanged(PluginAppVisibility.MODE_BACKGROUND)
    }

    /** read live rather than off a snapshot, which would be a strong reference to a screen the user has already left. Here rather than in `PluginJvm`, which reaches no `Activity` of its own */
    private object AppScreen : PluginJvm.AppScreen {
        override fun currentFragment(): Any? = LaunchActivity.getSafeLastFragment()

        override fun currentActivity(): Any? = LaunchActivity.instance?.takeIf { !it.isFinishing }
    }
}
