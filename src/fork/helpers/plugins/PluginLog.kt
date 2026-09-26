package desu.inugram.helpers.plugins

import android.util.Log
import desu.inugram.core.plugins.PluginManifest

/**
 * logcat filters match tags exactly, and `inu dev` keeps only the pushed plugins' channels.
 * `@inugram/cli` reads these tags (`device.ts`), so keep the two in step.
 */
class PluginLog private constructor(val tag: String) {
    fun d(area: String, message: String, error: Throwable? = null) {
        Log.d(tag, "[$area] $message", error)
    }

    fun w(area: String, message: String, error: Throwable? = null) {
        Log.w(tag, "[$area] $message", error)
    }

    fun e(area: String, message: String, error: Throwable? = null) {
        Log.e(tag, "[$area] $message", error)
    }

    fun console(level: Int, message: String) {
        when (level) {
            2 -> Log.w(tag, message)
            3, QuickJs.LEVEL_FAULT -> Log.e(tag, message)
            else -> Log.d(tag, message)
        }
    }

    companion object {
        private const val PLUGIN_TAG_PREFIX = "InuPlugin/"

        val HOST = PluginLog("InuPluginHost")

        /** a plugin with neither `@id` nor `@author` has only its install id */
        fun of(manifest: PluginManifest, installId: String): PluginLog =
            PluginLog(PLUGIN_TAG_PREFIX + (manifest.id ?: installId))

        fun of(plugin: Plugin): PluginLog = of(plugin.manifest, plugin.id)
    }
}
