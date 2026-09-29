package desu.inugram.helpers.plugins

import android.util.Log
import desu.inugram.core.plugins.PluginManifest

/**
 * logcat filters match tags exactly, and `inu dev` keeps only the pushed plugins' channels.
 * `@inugram/cli` reads these tags (`device.ts`), so keep the two in step.
 */
class PluginLog private constructor(val tag: String) {
    private val chunkBytes = 4000 - tag.toByteArray().size

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
        val priority = when (level) {
            2 -> Log.WARN
            3, QuickJs.LEVEL_FAULT -> Log.ERROR
            else -> Log.DEBUG
        }
        var start = 0
        do {
            val end = findChunkEnd(message, start)
            Log.println(priority, tag, message.substring(start, end))
            start = if (end < message.length && message[end] == '\n') end + 1 else end
        } while (start < message.length)
    }

    private fun findChunkEnd(message: String, start: Int): Int {
        var bytes = 0
        var lastNewline = -1
        var i = start
        while (i < message.length) {
            val codePoint = message.codePointAt(i)
            val size = when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > chunkBytes) return if (lastNewline > start) lastNewline else i
            if (codePoint == '\n'.code) lastNewline = i
            bytes += size
            i += Character.charCount(codePoint)
        }
        return message.length
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
