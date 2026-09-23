package desu.inugram.helpers.plugins.io

import java.io.File
import java.util.UUID
import org.telegram.messenger.ApplicationLoader

object PluginBlobs {
    private const val ROOT = "inu_plugin_blobs"

    private val session = UUID.randomUUID().toString()

    private fun root(): File = File(ApplicationLoader.applicationContext.cacheDir, ROOT)

    fun scheduleSweep() = PluginPaths.sweepStaleSessions(session, ::root)
    fun dirFor(installId: String): String = PluginPaths.scopedDir(installId, ::getInstallDir)
    fun wipe(installId: String) = PluginPaths.wipe(installId, ::getInstallDir)

    private fun getInstallDir(installId: String): File = File(root(), "$session/$installId")
}
