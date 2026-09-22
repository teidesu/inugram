package desu.inugram.helpers.plugins.io

import desu.inugram.core.plugins.PluginInstalls
import java.io.File
import java.util.UUID
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLoader

/**
 * Stores staged send/upload files from Rust `writes.rs` in stock's media cache.
 * This lets the composer take ownership by rename. Renames require the same volume, and stock
 * only moves sent files into download locations when they start in this cache.
 *
 * Unlike blob spills, transfers are handed off immediately after writing, so removable storage
 * is suitable here. Directories are per-process. Files already accepted by the app have moved;
 * files remaining from older processes can be deleted.
 */
object PluginTransfers {
    private const val ROOT = "inu_plugin_transfers"

    /** the only name rust gives a staged transfer; a plugin-named path is never one of these */
    private val STAGED_NAME = Regex("transfer-\\d+\\.bin")

    private val session = UUID.randomUUID().toString()

    /** stock's media directories are only set once its image loader exists, and reading one before that throws */
    private fun root(): File? {
        ImageLoader.getInstance()
        return FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE)?.let { File(it, ROOT) }
    }

    /** drops every previous process's transfers; call once from [PluginManager.init], next to [PluginBlobs.scheduleSweep] */
    fun scheduleSweep() = PluginPaths.sweepStaleSessions(session, ::root)

    /** this plugin's staging directory, or "" when stock has no media cache to put it in, which stages beside the spills */
    fun dirFor(installId: String): String = PluginPaths.scopedDir(installId, ::dirOf)

    /** whether [file] is a transfer rust staged for this plugin, which nothing but the write it came in on reads again */
    fun isStaged(installId: String, file: File): Boolean {
        if (!PluginInstalls.isValidId(installId) || !STAGED_NAME.matches(file.name)) return false
        val parent = file.absoluteFile.parentFile?.canonicalFile ?: return false
        return listOf(dirFor(installId), PluginBlobs.dirFor(installId))
            .any { it.isNotEmpty() && File(it).canonicalFile == parent }
    }

    /** permanently deletes a plugin's staged transfers; call on the plugin queue after its engine is closed */
    fun wipe(installId: String) = PluginPaths.wipe(installId, ::dirOf)

    private fun dirOf(installId: String): File? = root()?.let { File(it, "$session/$installId") }
}
