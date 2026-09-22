package desu.inugram.helpers.plugins

import android.util.Log
import desu.inugram.core.plugins.PluginManifest

/**
 * One logcat tag per channel, because logcat filters match tags exactly and `inu dev` keeps only
 * the channels of the plugins it pushes: `InuPlugin/<manifest id>` for everything about one
 * plugin, [HOST] for what belongs to none. A subsystem names itself in the message, not the tag.
 *
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

    /** a plugin's own `console`, which names no subsystem */
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

        /** the manifest id is readable and unique among installs; a plugin with neither `@id` nor `@author` has only its install id */
        fun of(manifest: PluginManifest, installId: String): PluginLog =
            PluginLog(PLUGIN_TAG_PREFIX + (manifest.id ?: installId))

        fun of(plugin: Plugin): PluginLog = of(plugin.manifest, plugin.id)
    }
}
