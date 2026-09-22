package desu.inugram.helpers.plugins.api

import desu.inugram.core.plugins.PluginInstalls
import desu.inugram.helpers.plugins.io.PluginPaths
import java.io.File

/**
 * Locates each install's `inu.kv` store. Rust `kv.rs` owns its contents and quota.
 * Uses `filesDir` to survive restarts and install IDs to preserve data across renames
 * without letting another plugin claim it by name.
 */
object PluginKv {
    private const val ROOT = "inu_kv"

    private fun file(installId: String): File = PluginPaths.scopedFile(installId, ROOT)

    fun pathFor(installId: String): String = file(installId).apply { parentFile!!.mkdirs() }.absolutePath

    /** after the engine is closed; `.tmp` is rust's `kv::staged_path` */
    fun wipe(installId: String) {
        if (!PluginInstalls.isValidId(installId)) return
        val store = file(installId)
        store.delete()
        File("${store.path}.tmp").delete()
    }
}
