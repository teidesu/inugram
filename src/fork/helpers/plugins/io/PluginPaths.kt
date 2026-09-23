package desu.inugram.helpers.plugins.io

import desu.inugram.core.plugins.PluginInstalls
import java.io.File
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.Utilities

/**
 * Builds per-install paths, revalidating IDs each time. Returns "" if a directory cannot
 * be created instead of failing the plugin. Each owner chooses and initializes its root.
 */
internal object PluginPaths {
    private const val TRASH = "inu_plugins_trash"

    fun scopedDir(installId: String, dirOf: (String) -> File?): String {
        require(PluginInstalls.isValidId(installId)) { "malformed install id" }
        return ensure(dirOf(installId))
    }

    fun scopedFile(installId: String, root: String, name: String = installId): File {
        require(PluginInstalls.isValidId(installId)) { "malformed install id" }
        return File(ApplicationLoader.applicationContext.filesDir, "$root/$name")
    }

    fun ensure(dir: File?): String {
        if (dir == null) return ""
        if (!dir.isDirectory && !dir.mkdirs()) return ""
        return dir.absolutePath
    }

    fun wipe(installId: String, dirOf: (String) -> File?) {
        if (!PluginInstalls.isValidId(installId)) return
        dirOf(installId)?.deleteRecursively()
    }

    /**
     * Moves every entry of [root] that [idOf] attributes to an install outside [live] into the
     * trash, then empties the trash off the caller's thread. Call before any plugin starts: the
     * move is what keeps a store from being deleted under an engine that just created it.
     */
    fun sweepOrphans(root: String, live: Set<String>, idOf: (String) -> String?) {
        val entries = File(ApplicationLoader.applicationContext.filesDir, root).listFiles() ?: return
        val trash = File(ApplicationLoader.applicationContext.filesDir, TRASH)
        for (entry in entries) {
            val id = idOf(entry.name)?.takeIf(PluginInstalls::isValidId) ?: continue
            if (id in live) continue
            if (!trash.isDirectory && !trash.mkdirs()) return
            entry.renameTo(File(trash, PluginInstalls.mintId()))
        }
    }

    fun emptyTrash() {
        Utilities.cacheClearQueue.postRunnable {
            File(ApplicationLoader.applicationContext.filesDir, TRASH).listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    /**
     * drops every directory a previous process left behind, off the caller's thread: that caller is
     * `ApplicationLoader.onCreate`, and after a process death mid-write this is an unbounded
     * recursive delete.
     */
    fun sweepStaleSessions(session: String, root: () -> File?) {
        Utilities.cacheClearQueue.postRunnable {
            val children = root()?.listFiles() ?: return@postRunnable
            for (child in children) {
                if (child.name == session) continue
                child.deleteRecursively()
            }
        }
    }
}
