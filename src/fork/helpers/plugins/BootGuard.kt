package desu.inugram.helpers.plugins

import androidx.core.content.edit
import desu.inugram.InuConfig

/**
 * Detects a plugin that hung or crashed during startup. Commit a flag before running each
 * plugin's code and clear it when the call returns; the next process checks any remaining flag.
 *
 * Guard each plugin separately. A process-wide guard would misread normal Android headless
 * startup and termination (push, widget, `BOOT_COMPLETED`) as a crash. A guard covering the whole
 * startup pass would also misread termination after [BootCohort.EARLY_BUDGET_MILLIS][
 * desu.inugram.core.plugins.BootCohort.EARLY_BUDGET_MILLIS], when the app stops waiting but plugins
 * may still be evaluating.
 */
class BootGuard {
    enum class Reason { FORCED, CRASHED }

    @Volatile
    var reason: Reason? = null
        private set

    private var decided = false

    val safeMode: Boolean get() = reason != null

    private fun read(key: String): Boolean = InuConfig.prefs.getBoolean(key, false)

    /** Use `commit`, not `apply`: the flag must reach disk before plugin code can crash the process. */
    private fun write(key: String, value: Boolean) {
        InuConfig.prefs.edit(commit = true) { putBoolean(key, value) }
    }

    /**
     * Returns true if plugins may run. Once selected, safe mode lasts for the whole process,
     * including the later startup pass.
     *
     * Reads and clears the startup and safe-mode flags. Only [guardPlugin] arms the guard.
     */
    @Synchronized
    fun startPass(): Boolean {
        if (decided) return reason == null
        decided = true
        val forced = read(FORCED_KEY)
        val crashed = read(GUARD_KEY)
        if (forced || crashed) {
            reason = if (forced) Reason.FORCED else Reason.CRASHED
            write(FORCED_KEY, false)
            write(GUARD_KEY, false)
            return false
        }
        return true
    }

    /** runs one plugin's own code with the flag committed across it, and across nothing else */
    fun guardPlugin(body: () -> Unit) {
        write(GUARD_KEY, true)
        try {
            body()
        } finally {
            write(GUARD_KEY, false)
        }
    }

    /** the user asked for safe mode; honoured (and cleared) by the next process's first pass */
    fun armForcedSafeMode() {
        write(FORCED_KEY, true)
    }

    companion object {
        const val GUARD_KEY = "plugins_boot_guard"
        const val FORCED_KEY = "plugins_force_safe"
    }
}
