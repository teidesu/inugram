package desu.inugram.helpers.plugins

import android.os.SystemClock
import java.io.File
import org.telegram.messenger.ApplicationLoader

/**
 * Detects plugins that keep the app from running, and runs none for one process when they do.
 *
 * Two signals, each a small file in [dir] rather than a key in the shared `inugram` prefs, whose
 * every write rewrites and syncs all of the app's settings:
 * - [STARTING] exists while one plugin's top-level code runs. A process that dies with it in
 *   place hung or crashed in that code.
 * - [CRASHES] counts consecutive processes that crashed within [CRASH_WINDOW_MILLIS] of their
 *   first plugin starting. That catches a hook or callback that takes the app down shortly after
 *   boot, where [STARTING] is already gone.
 *
 * Guard each plugin's evaluation separately. A process-wide guard would misread normal Android
 * headless startup and termination (push, widget, `BOOT_COMPLETED`) as a crash. A guard covering
 * the whole startup pass would also misread termination after [BootCohort.EARLY_BUDGET_MILLIS][
 * desu.inugram.core.plugins.BootCohort.EARLY_BUDGET_MILLIS], when the app stops waiting but plugins
 * may still be evaluating. The crash count only moves on an uncaught exception, so a reaped
 * process never counts.
 *
 * Writes are plain file writes, not synced: they only have to outlive this process, and the kernel
 * keeps a write a dead process made.
 */
class BootGuard(
    private val dir: File = File(ApplicationLoader.applicationContext.filesDir, DIR),
    private val uptimeMillis: () -> Long = SystemClock::uptimeMillis,
) {
    enum class Reason { FORCED, CRASHED, CRASH_LOOP }

    @Volatile
    var reason: Reason? = null
        private set

    private var decided = false

    /** when this process first ran plugin code; 0 until then */
    @Volatile
    private var firstStartAt = 0L

    val safeMode: Boolean get() = reason != null

    /**
     * Returns true if plugins may run. Once selected, safe mode lasts for the whole process,
     * including the later startup pass. Reads and clears every flag it acts on.
     */
    @Synchronized
    fun startPass(): Boolean {
        if (decided) return reason == null
        decided = true
        reason = when {
            File(dir, FORCED).exists() -> Reason.FORCED
            File(dir, STARTING).exists() -> Reason.CRASHED
            readCrashes() >= CRASH_LIMIT -> Reason.CRASH_LOOP
            else -> return true
        }
        for (name in arrayOf(FORCED, STARTING, CRASHES)) File(dir, name).delete()
        return false
    }

    /** runs one plugin's own code with [STARTING] on disk across it, and across nothing else */
    fun guardPlugin(body: () -> Unit) {
        if (firstStartAt == 0L) firstStartAt = uptimeMillis()
        write(STARTING, "")
        try {
            body()
        } finally {
            File(dir, STARTING).delete()
        }
    }

    /** the user asked for safe mode; honoured (and cleared) by the next process's first pass */
    fun armForcedSafeMode() {
        write(FORCED, "")
    }

    /** called on the crashing thread, before the process dies */
    fun recordCrash() {
        val startedAt = firstStartAt
        if (startedAt == 0L || uptimeMillis() - startedAt >= CRASH_WINDOW_MILLIS) return
        write(CRASHES, (readCrashes() + 1).toString())
    }

    /** this process kept its plugins running through the window, so the crashes before it were not a loop */
    fun survivedWindow() {
        File(dir, CRASHES).delete()
    }

    private fun readCrashes(): Int =
        runCatching { File(dir, CRASHES).readText().trim().toInt() }.getOrDefault(0)

    /** a guard that cannot be armed does not stop the plugin: that is the state before this class existed */
    private fun write(name: String, content: String) {
        try {
            dir.mkdirs()
            File(dir, name).writeText(content)
        } catch (e: Exception) {
            PluginLog.HOST.e("guard", "could not write $name", e)
        }
    }

    companion object {
        const val CRASH_LIMIT = 2
        const val CRASH_WINDOW_MILLIS = 30_000L

        private const val DIR = "inu_plugins_guard"
        const val STARTING = "starting"
        const val FORCED = "forced"
        const val CRASHES = "crashes"
    }
}
