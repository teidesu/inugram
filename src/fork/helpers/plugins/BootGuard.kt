package desu.inugram.helpers.plugins

import android.os.SystemClock
import java.io.File
import org.telegram.messenger.ApplicationLoader

/**
 * Two signals, each a small file in [dir] rather than the shared `inugram` prefs, whose every write
 * rewrites and syncs all settings:
 * - [STARTING] exists while one plugin's top-level code runs; dying with it present means that code hung or crashed.
 * - [CRASHES] counts consecutive processes crashing within [CRASH_WINDOW_MILLIS] of their first plugin start.
 *
 * Guarded per plugin: a process-wide or whole-pass guard would misread normal headless startup and
 * termination (push, widget, `BOOT_COMPLETED`, or past [BootCohort.EARLY_BUDGET_MILLIS]) as a crash.
 * Only uncaught exceptions count, so a reaped process never does. Writes are unsynced: the kernel keeps a
 * dead process's writes.
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

    /** 0 until this process first runs plugin code */
    @Volatile
    private var firstStartAt = 0L

    val safeMode: Boolean get() = reason != null

    /** safe mode lasts the whole process once chosen. Reads and clears every flag it acts on */
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

    fun guardPlugin(body: () -> Unit) {
        if (firstStartAt == 0L) firstStartAt = uptimeMillis()
        write(STARTING, "")
        try {
            body()
        } finally {
            File(dir, STARTING).delete()
        }
    }

    /** honoured and cleared by the next process's first pass */
    fun armForcedSafeMode() {
        write(FORCED, "")
    }

    /** on the crashing thread, before the process dies */
    fun recordCrash() {
        val startedAt = firstStartAt
        if (startedAt == 0L || uptimeMillis() - startedAt >= CRASH_WINDOW_MILLIS) return
        write(CRASHES, (readCrashes() + 1).toString())
    }

    fun survivedWindow() {
        File(dir, CRASHES).delete()
    }

    private fun readCrashes(): Int =
        runCatching { File(dir, CRASHES).readText().trim().toInt() }.getOrDefault(0)

    /** a guard that cannot be armed does not stop the plugin */
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
