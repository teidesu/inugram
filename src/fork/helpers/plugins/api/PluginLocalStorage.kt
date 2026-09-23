package desu.inugram.helpers.plugins.api

import desu.inugram.core.plugins.PluginInstalls
import desu.inugram.helpers.plugins.io.PluginPaths
import java.io.File

/**
 * Locates each install's `localStorage` store. Rust `local_storage.rs` owns its contents and quota.
 * Uses `filesDir` to survive restarts and install IDs to preserve data across renames
 * without letting another plugin claim it by name.
 */
object PluginLocalStorage {
    private const val ROOT = "inu_local_storage"

    private fun file(installId: String): File = PluginPaths.scopedFile(installId, ROOT)

    fun pathFor(installId: String): String = file(installId).apply { parentFile!!.mkdirs() }.absolutePath

    /** rust's `local_storage::staged_path` and `quarantine_path`, which belong to the store beside them */
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
