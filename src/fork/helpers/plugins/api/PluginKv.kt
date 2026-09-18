package desu.inugram.helpers.plugins.api

import desu.inugram.core.plugins.PluginInstalls
import desu.inugram.helpers.plugins.io.PluginPaths
import java.io.File

/**
 * Where `inu.kv` keeps an install's store (rust: `kv.rs`, which owns the store and its quota).
 * `filesDir` rather than the cache, since a store is meant to outlive restarts, and keyed by
 * install id so a rename keeps it and nothing can name its way into another's.
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
