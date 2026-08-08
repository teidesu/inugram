package desu.inugram.helpers.plugins.api

import desu.inugram.core.plugins.FsQuota
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.io.PluginBlobs
import desu.inugram.helpers.plugins.io.PluginFs
import desu.inugram.helpers.plugins.platform.PluginJvm
import desu.inugram.helpers.plugins.platform.PluginXposed
import org.telegram.ui.LaunchActivity

/**
 * What an engine is wired with, and the order it happens in.
 *
 * Here rather than in [PluginApi] because none of it is an api: it is the bring-up `PluginManager`
 * runs once per engine, and the ordering constraints among the pieces are the whole content of the
 * file. Split off the api for the same reason the api is not the bridge - a file everything imports
 * is not the file everything belongs in.
 */
object EngineBindings {
    /** the app screen is here rather than in `PluginJvm`, which reaches no `Activity` of its own */
    fun jvmListenerFor(plugin: Plugin, engine: QuickJs) = PluginJvm.listenerFor(plugin, engine, AppScreen)

    /**
     * Everything the engine's own bindings need in place, in the one order that works: the read
     * surface installs from inside `installApi`, taking the peer helpers `inu.utils` leaves behind
     * and the `Account` handles it hangs its getters on, and `inu.xposed` mints every handle its
     * entry points take out of `inu.jvm`'s table.
     */
    fun install(plugin: Plugin, engine: QuickJs) {
        PluginJvm.install(engine)
        PluginXposed.install(engine)
        engine.installApi(PluginBlobs.dirFor(plugin.id))
        // after installApi, which creates the blob table `fs.write` reads a `Blob` through. A plugin that declared no `fs` gets no bindings and no directory
        val quota = FsQuota.forGrants(plugin.manifest.grants)
        if (quota != null) {
            engine.installFs(
                PluginFs.dirFor(plugin.id),
                quota,
                PluginFs.isUnscoped(plugin.permissions),
                PluginFs.androidDirs(),
            )
        }
        // a plugin loaded while the app is hidden would otherwise tick unthrottled until the next transition; no callback can hear this, its own code not having run yet
        if (!PluginApi.isForeground) engine.appVisibilityChanged(false)
    }

    /** read live rather than off a snapshot, which would be a strong reference to a screen the user has already left. Here rather than in `PluginJvm`, which reaches no `Activity` of its own */
    private object AppScreen : PluginJvm.AppScreen {
        override fun currentFragment(): Any? = LaunchActivity.getSafeLastFragment()

        override fun currentActivity(): Any? = LaunchActivity.instance?.takeIf { !it.isFinishing }
    }
}
