package desu.inugram.helpers.plugins.io

import java.io.File
import java.util.UUID
import org.telegram.messenger.ApplicationLoader

/**
 * Manages spill-file locations for blobs too large to keep in memory (Rust: `blob.rs`).
 * The engine owns the bytes; the host owns directories and cleanup.
 *
 * Uses internal cache storage because `AndroidUtilities.getCacheDir` may use a removable card
 * that can disappear mid-write. Directories are grouped by process [session]. Spilled blobs
 * cannot outlive their engine, so [scheduleSweep] deletes other processes' directories,
 * including partial writes left by crashes.
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
    fun scheduleSweep() = PluginPaths.sweepStaleSessions(session, ::root)

    /**
     * this plugin's spill directory, or "" when it cannot be made - which leaves the engine unable
     * to spill (blobs stay in memory against the native budget) rather than unable to run.
     */
    // the id names a path here, so it is re-checked here and not only where it was reconciled
    fun dirFor(installId: String): String = PluginPaths.scopedDir(installId, ::dirOf)

    /**
     * permanently deletes a plugin's spills. call on the plugin queue *after* its engine is closed:
     * rust holds an open descriptor per spill until the context is dropped.
     */
    fun wipe(installId: String) = PluginPaths.wipe(installId, ::dirOf)

    private fun dirOf(installId: String): File = File(root(), "$session/$installId")
}
