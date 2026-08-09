package desu.inugram.helpers.plugins.io

import desu.inugram.core.plugins.PluginInstalls
import java.io.File
import java.util.UUID
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.Utilities

/**
 * Where a `Blob` too big to keep in memory spills to (rust: `blob.rs`). The engine owns the bytes;
 * the host owns only where the files live and when the tree dies.
 *
 * Deliberately the app's *internal* cache dir rather than [org.telegram.messenger.AndroidUtilities.getCacheDir],
 * which resolves to external storage and may be a removable card the user can eject mid-write.
 *
 * A spilled blob never outlives the engine that made it, so a directory from any other process is
 * orphaned by definition: everything is filed under a per-process [session] and [scheduleSweep]
 * takes the rest, which is also what cleans up after a process death mid-write.
 */
object PluginBlobs {
    private const val ROOT = "inu_plugin_blobs"

    private val session = UUID.randomUUID().toString()

    private fun root(): File = File(ApplicationLoader.applicationContext.cacheDir, ROOT)

    /**
     * drops every previous process's spills; call once from [PluginManager.init].
     *
     * Off the caller's thread because that caller is `ApplicationLoader.onCreate`, and after a
     * process death mid-download this is an unbounded recursive delete.
     */
    fun scheduleSweep() {
        Utilities.cacheClearQueue.postRunnable {
            val children = root().listFiles() ?: return@postRunnable
            for (child in children) {
                if (child.name == session) continue
                child.deleteRecursively()
            }
        }
    }

    /**
     * this plugin's spill directory, or "" when it cannot be made - which leaves the engine unable
     * to spill (blobs stay in memory against the native budget) rather than unable to run.
     */
    fun dirFor(installId: String): String {
        // the id names a path here, so it is re-checked here and not only where it was reconciled
        require(PluginInstalls.isValidId(installId)) { "malformed install id" }
        val dir = File(root(), "$session/$installId")
        if (!dir.isDirectory && !dir.mkdirs()) return ""
        return dir.absolutePath
    }

    /**
     * permanently deletes a plugin's spills. call on globalQueue *after* its engine is closed:
     * rust holds an open descriptor per spill until the context is dropped.
     */
    fun wipe(installId: String) {
        if (!PluginInstalls.isValidId(installId)) return
        File(root(), "$session/$installId").deleteRecursively()
    }
}
