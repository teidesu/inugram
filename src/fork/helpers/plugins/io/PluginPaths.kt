package desu.inugram.helpers.plugins.io

import desu.inugram.core.plugins.PluginInstalls
import java.io.File
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.Utilities

internal object PluginPaths {
    private const val TRASH = "inu_plugins_trash"

    fun scopedDir(installId: String, resolveDir: (String) -> File?): String {
        require(PluginInstalls.isValidId(installId)) { "malformed install id" }
        return ensure(resolveDir(installId))
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

    fun wipe(installId: String, resolveDir: (String) -> File?) {
        if (!PluginInstalls.isValidId(installId)) return
        resolveDir(installId)?.deleteRecursively()
    }

    /** call before any plugin starts: the move keeps a store from being deleted under an engine that just created it */
    fun sweepOrphans(root: String, live: Set<String>, parseDeclaredId: (String) -> String?) {
        val entries = File(ApplicationLoader.applicationContext.filesDir, root).listFiles() ?: return
        val trash = File(ApplicationLoader.applicationContext.filesDir, TRASH)
        for (entry in entries) {
            val id = parseDeclaredId(entry.name)?.takeIf(PluginInstalls::isValidId) ?: continue
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

    /** off-thread: the caller is `ApplicationLoader.onCreate`, and after a mid-write death this is an unbounded delete */
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
