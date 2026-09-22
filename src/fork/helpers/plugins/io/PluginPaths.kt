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
