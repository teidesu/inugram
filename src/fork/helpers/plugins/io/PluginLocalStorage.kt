package desu.inugram.helpers.plugins.io

import desu.inugram.core.plugins.PluginInstalls
import java.io.File

/** rust `local_storage.rs` owns contents and quota */
object PluginLocalStorage {
    private const val ROOT = "inu_local_storage"

    private fun file(installId: String): File = PluginPaths.scopedFile(installId, ROOT)

    fun pathFor(installId: String): String = file(installId).apply { parentFile!!.mkdirs() }.absolutePath

    /** rust's `local_storage.rs` stages writes in `.tmp` and moves an unreadable store to `.corrupt` */
    private val SIDE_FILES = listOf(".tmp", ".corrupt")

    /** after the engine is closed */
    fun wipe(installId: String) {
        if (!PluginInstalls.isValidId(installId)) return
        val store = file(installId)
        store.delete()
        for (suffix in SIDE_FILES) File("${store.path}$suffix").delete()
    }

    fun sweepOrphans(live: Set<String>) = PluginPaths.sweepOrphans(ROOT, live) { name ->
        SIDE_FILES.fold(name) { stripped, suffix -> stripped.removeSuffix(suffix) }
    }
}
