package desu.inugram.helpers.plugins.io

import java.io.File
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLoader

/**
 * All file operations run in rust, where Blob bytes live. Keyed by install id, so data survives renames and
 * no plugin can pick another's directory by name.
 */
object PluginFs {
    private const val SCOPED_ROOT = "inu_plugins"
    private const val STORE = "inugram_plugins"

    private fun dir(installId: String): File = PluginPaths.scopedFile(installId, SCOPED_ROOT, "scoped_$installId")

    fun storeDir(): File =
        File(ApplicationLoader.applicationContext.filesDir, STORE).apply { mkdirs() }

    fun dirFor(installId: String): String = PluginPaths.ensure(dir(installId))

    fun androidDirs(): String {
        val plugins = storeDir()
        val media = intArrayOf(
            FileLoader.MEDIA_DIR_FILES,
            FileLoader.MEDIA_DIR_IMAGE,
            FileLoader.MEDIA_DIR_VIDEO,
            FileLoader.MEDIA_DIR_AUDIO,
            FileLoader.MEDIA_DIR_DOCUMENT,
        ).map { runCatching { FileLoader.checkDirectory(it)?.absolutePath }.getOrNull().orEmpty() }
        return (listOf(plugins.absolutePath, ApplicationLoader.applicationContext.cacheDir.absolutePath) + media)
            .joinToString("\n")
    }

    /** uninstall only: the one plugin-owned tree meant to outlive the engine */
    fun wipe(installId: String) = PluginPaths.wipe(installId, ::dir)

    fun sweepOrphans(live: Set<String>) = PluginPaths.sweepOrphans(SCOPED_ROOT, live) { it.removePrefix("scoped_") }
}
