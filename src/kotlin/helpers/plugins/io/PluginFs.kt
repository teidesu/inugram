package desu.inugram.helpers.plugins.io

import desu.inugram.core.plugins.FsQuota
import desu.inugram.core.plugins.PluginInstalls
import desu.inugram.core.plugins.PluginPermissions
import java.io.File
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLoader

/**
 * Where `inu.fs` lives, and how big it may get (rust: `fs.rs`).
 *
 * The host's whole share is three answers - the directory, the cap, and whether scoping is on -
 * handed over once at install. Everything after that happens in rust, because `fs.write` takes a
 * `Blob` whose bytes only that side can read: routing them through an upcall would put every
 * written megabyte on the app-wide java heap.
 *
 * Deliberately `filesDir` and not the cache area: `fs.d.ts` promises the content survives restarts.
 * Keyed by the install id, so renaming a plugin keeps its data and no plugin can name its way into
 * another's directory.
 */
object PluginFs {
    private const val SCOPED_ROOT = "inu_plugins"

    /** where [PluginManager] installs plugin sources; `inu.android.getPluginsDir` answers this */
    private const val STORE = "inugram_plugins"

    /** the cap `@grant fs` alone buys, per `fs.d.ts`. Keep in sync with rust `fs::DEFAULT_QUOTA_BYTES`. */
    const val DEFAULT_QUOTA_BYTES = FsQuota.DEFAULT_BYTES

    /** what `installFs` is passed for `unsafe.fs`; rust turns it into `quota()` answering `Infinity` */
    const val UNCAPPED = FsQuota.UNCAPPED

    private fun dir(installId: String): File {
        require(PluginInstalls.isValidId(installId)) { "malformed install id" }
        return File(ApplicationLoader.applicationContext.filesDir, "$SCOPED_ROOT/scoped_$installId")
    }

    /** deliberately *not* [SCOPED_ROOT], which holds one private `fs` root per install and no `.js` file at all */
    fun storeDir(): File =
        File(ApplicationLoader.applicationContext.filesDir, STORE).apply { mkdirs() }

    /**
     * this plugin's own durable directory, or "" when it cannot be made - which leaves every
     * `inu.fs` call failing rather than landing somewhere the plugin does not own.
     */
    fun dirFor(installId: String): String {
        val dir = dir(installId)
        if (!dir.isDirectory && !dir.mkdirs()) return ""
        return dir.absolutePath
    }

    /**
     * what `inu.android.getPluginsDir`/`getCacheDir`/`getMediaDir` answer, newline separated in the
     * order rust reads them: the plugin store, the cache, then the five media kinds `android.d.ts`
     * names. Answered once at install rather than per call - none of them moves for the life of the
     * process, and `fs.rs` deliberately has no upcall to ask over.
     *
     * `getExternalFilesDirs`/`checkDirectory` both answer null often enough that a missing entry is
     * a normal answer, not a failure; rust turns an empty one into `not-found`.
     */
    fun androidDirs(): String {
        val plugins = storeDir()
        val media = intArrayOf(
            FileLoader.MEDIA_DIR_FILES,
            FileLoader.MEDIA_DIR_IMAGE,
            FileLoader.MEDIA_DIR_VIDEO,
            FileLoader.MEDIA_DIR_AUDIO,
            FileLoader.MEDIA_DIR_DOCUMENT,
        ).map { runCatching { FileLoader.checkDirectory(it)?.absolutePath }.getOrNull().orEmpty() }
        return (listOf(plugins.absolutePath, ApplicationLoader.applicationContext.cacheDir.absolutePath) + media)
            .joinToString("\n")
    }

    /**
     * `unsafe.fs` *replaces* `fs` rather than adding to it, per `fs.d.ts`, so a plugin holding both
     * gets the unscoped mode and the safe token buys it nothing extra.
     */
    fun isUnscoped(permissions: PluginPermissions): Boolean = permissions.has("unsafe.fs")

    /**
     * the cap the manifest asked for, or null when it declared no `fs` at all. Null is what makes
     * `PluginApi.attach` skip `installFs` entirely, following the rule `PluginJvm`/`PluginXposed`
     * already follow: an api nobody granted gets no bindings, and no directory on disk either.
     */
    fun quotaFor(grants: List<String>): Long? = FsQuota.forGrants(grants)

    /** the `\d+(kb|mb|gb)` shape `GrantValidator` already refuses the install over, in bytes */
    fun parseSize(scope: String): Long? = FsQuota.parseSize(scope)

    /**
     * permanently deletes a plugin's storage. Only on **uninstall**, never on stop: this is the one
     * plugin-owned tree that is meant to outlive the engine.
     */
    fun wipe(installId: String) {
        if (!PluginInstalls.isValidId(installId)) return
        dir(installId).deleteRecursively()
    }
}
