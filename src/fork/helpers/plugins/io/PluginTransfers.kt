package desu.inugram.helpers.plugins.io

import desu.inugram.core.plugins.PluginInstalls
import java.io.File
import java.util.UUID
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLoader

/**
 * In stock's media cache so the composer can take files by rename, which needs the same volume; stock
 * only moves sent files to download locations when they start in this cache. Leftovers from older
 * processes can be deleted: accepted files have moved.
 */
object PluginTransfers {
    private const val ROOT = "inu_plugin_transfers"
    private val STAGED_NAME = Regex("transfer-\\d+\\.bin")

    private val session = UUID.randomUUID().toString()

    /** stock's media dirs are set only once its image loader exists; reading earlier throws */
    private fun root(): File? {
        ImageLoader.getInstance()
        return FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE)?.let { File(it, ROOT) }
    }

    fun scheduleSweep() = PluginPaths.sweepStaleSessions(session, ::root)

    fun dirFor(installId: String): String = PluginPaths.scopedDir(installId, ::getInstallDir)

    fun isStaged(installId: String, file: File): Boolean {
        if (!PluginInstalls.isValidId(installId) || !STAGED_NAME.matches(file.name)) return false
        val parent = file.absoluteFile.parentFile?.canonicalFile ?: return false
        return listOf(dirFor(installId), PluginBlobs.dirFor(installId))
            .any { it.isNotEmpty() && File(it).canonicalFile == parent }
    }

    /** on the plugin queue after the engine is closed */
    fun wipe(installId: String) = PluginPaths.wipe(installId, ::getInstallDir)

    private fun getInstallDir(installId: String): File? = root()?.let { File(it, "$session/$installId") }
}
