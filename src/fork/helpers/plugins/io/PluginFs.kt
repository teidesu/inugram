package desu.inugram.helpers.plugins.io

import desu.inugram.core.plugins.FsQuota
import desu.inugram.core.plugins.PluginInstalls
import desu.inugram.core.plugins.PluginPermissions
import java.io.File
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLoader

/**
 * Configures `inu.fs` with its directory, quota, and scoped mode once at installation
 * (Rust: `fs.rs`). All file operations run in Rust, where Blob bytes live, avoiding copies
 * of large writes through the app's Java heap.
 *
 * Uses `filesDir` for persistence across restarts. Storage is keyed by install ID, preserving
 * data across renames and preventing one plugin from selecting another's directory by name.
 */
object PluginFs {
    private const val SCOPED_ROOT = "inu_plugins"

    /** where [desu.inugram.helpers.plugins.PluginStore] keeps plugin sources; `inu.android.getPluginsDir` answers this */
    private const val STORE = "inugram_plugins"

    private fun dir(installId: String): File = PluginPaths.scopedFile(installId, SCOPED_ROOT, "scoped_$installId")

    /** deliberately *not* [SCOPED_ROOT], which holds one private `fs` root per install and no `.js` file at all */
    fun storeDir(): File =
        File(ApplicationLoader.applicationContext.filesDir, STORE).apply { mkdirs() }

    /**
     * this plugin's own durable directory, or "" when it cannot be made - which leaves every
     * `inu.fs` call failing rather than landing somewhere the plugin does not own.
     */
    fun dirFor(installId: String): String = PluginPaths.ensure(dir(installId))

    /**
     * what `inu.android.getPluginsDir`/`getCacheDir`/`getMediaDir` answer, newline separated in the
     * order rust reads them: the plugin store, the cache, then the five media kinds `android.d.ts`
     * names. Answered once at install rather than per call - none of them moves for the life of the
     * process, and `fs.rs` deliberately has no upcall to ask over.
     *
     * `getExternalFilesDirs`/`checkDirectory` both answer null often enough that a missing entry is
     * a normal answer, not a failure; rust turns an empty one into `not-found`.
     */
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

    /**
     * `unsafe.fs` *replaces* `fs` rather than adding to it, per `fs.d.ts`, so a plugin holding both
     * gets the unscoped mode and the safe token buys it nothing extra.
     */
    fun isUnscoped(permissions: PluginPermissions): Boolean = permissions.has("unsafe.fs")

    /**
     * permanently deletes a plugin's storage. Only on **uninstall**, never on stop: this is the one
     * plugin-owned tree that is meant to outlive the engine.
     */
    fun wipe(installId: String) = PluginPaths.wipe(installId, ::dir)
}
