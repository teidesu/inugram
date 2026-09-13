package desu.inugram.helpers.plugins.io

import desu.inugram.core.plugins.PluginInstalls
import java.io.File
import java.util.UUID
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLoader
import org.telegram.messenger.Utilities

/**
 * Where rust stages what a plugin hands a send or an upload (rust: `writes.rs`): a subtree of
 * stock's media cache rather than the [PluginBlobs] tree, so the app takes a staged file for its
 * composer with a rename instead of a copy. A rename cannot cross volumes, and stock only moves a
 * sent file to where a download of it lands when it was sent from that cache. A transfer is handed
 * over the moment it is written, so the removable volume that keeps spills off it costs nothing here.
 *
 * Filed per process like the blob tree: a transfer the app took has already left it, and anything
 * still here belongs to an engine that is gone.
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
    fun scheduleSweep() {
        Utilities.cacheClearQueue.postRunnable {
            val children = root()?.listFiles() ?: return@postRunnable
            for (child in children) {
                if (child.name == session) continue
                child.deleteRecursively()
            }
        }
    }

    /** this plugin's staging directory, or "" when stock has no media cache to put it in, which stages beside the spills */
    fun dirFor(installId: String): String {
        require(PluginInstalls.isValidId(installId)) { "malformed install id" }
        val dir = File(root() ?: return "", "$session/$installId")
        if (!dir.isDirectory && !dir.mkdirs()) return ""
        return dir.absolutePath
    }

    /** whether [file] is a transfer rust staged for this plugin, which nothing but the write it came in on reads again */
    fun isStaged(installId: String, file: File): Boolean {
        if (!PluginInstalls.isValidId(installId) || !STAGED_NAME.matches(file.name)) return false
        val parent = file.absoluteFile.parentFile?.canonicalFile ?: return false
        return listOf(dirFor(installId), PluginBlobs.dirFor(installId))
            .any { it.isNotEmpty() && File(it).canonicalFile == parent }
    }

    /** permanently deletes a plugin's staged transfers; call on the plugin queue after its engine is closed */
    fun wipe(installId: String) {
        if (!PluginInstalls.isValidId(installId)) return
        File(root() ?: return, "$session/$installId").deleteRecursively()
    }
}
