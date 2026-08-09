package desu.inugram.helpers.plugins

import androidx.core.content.edit
import desu.inugram.InuConfig

/**
 * whether plugins may run in this process.
 *
 * A persisted flag rather than anything the process can observe about itself: a plugin that wedges
 * or aborts the app leaves nothing behind to read, so the *next* process is what notices - the flag
 * is committed before that plugin's own code runs and dropped again when it came back.
 *
 * Scoped to **one plugin, not the process and not the pass**. Not the process, because android
 * starts this one without a ui all the time (a push, a widget, `BOOT_COMPLETED`) and kills it again,
 * so a guard waiting for an activity would be left armed by every one of those. Not the pass, because
 * the app stops *waiting* for it at [BootCohort.EARLY_BUDGET_MILLIS][
 * desu.inugram.core.plugins.BootCohort.EARLY_BUDGET_MILLIS] while every plugin behind that keeps
 * evaluating, and a flag committed across all of it turns the reap of a process android considers
 * idle into a crash nobody had.
 */
class BootGuard {
    enum class Reason { FORCED, CRASHED }

    @Volatile
    var reason: Reason? = null
        private set

    private var decided = false

    val safeMode: Boolean get() = reason != null

    private fun read(key: String): Boolean = InuConfig.prefs.getBoolean(key, false)

    /**
     * `commit` and never `apply`: the whole mechanism is that this write survives a process that
     * does not come back, and `apply` reaches disk after it returns.
     */
    private fun write(key: String, value: Boolean) {
        InuConfig.prefs.edit(commit = true) { putBoolean(key, value) }
    }

    /**
     * true == this pass may run plugins. Once safe mode is decided it holds for the whole process:
     * a later pass (the one that loads what the boot cohort left) must not run either.
     *
     * Commits nothing on its own. Deciding is reading two flags and clearing them if either is set;
     * what arms the guard is [guardPlugin], around the only thing it can attribute anything to.
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
