package desu.inugram.helpers.plugins

import androidx.core.content.edit
import desu.inugram.InuConfig
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Safe mode, against the storage it actually persists in.
 *
 * The subject *is* the persistence: a guard whose write does not survive the process that armed it
 * is a guard that never fires, so a stand-in store would be supplying the one truth these assert.
 * A crashed process is modelled the way it presents to the next start - the arming write landed and
 * the clearing write never did - which is the state on disk and not an approximation of it.
 *
 * These share the app's own prefs file, so [clear] runs on both sides of every case: a guard flag
 * left behind puts the *device* into safe mode for the next suite and the next launch.
 */
class BootGuardTest {
    @Before
    fun setUp() {
        resetBridge()
        clear()
    }

    @After
    fun tearDown() = clear()

    private fun clear() = InuConfig.prefs.edit(commit = true) {
        remove(BootGuard.GUARD_KEY)
        remove(BootGuard.FORCED_KEY)
    }

    /** what the next process reads; a fresh instance is a fresh start over the same disk */
    private fun restart() = BootGuard()

    private fun armed(): Boolean = InuConfig.prefs.getBoolean(BootGuard.GUARD_KEY, false)

    /** a process that died with plugin code on the stack: the arming write landed, the clearing one did not */
    private fun crash() = InuConfig.prefs.edit(commit = true) { putBoolean(BootGuard.GUARD_KEY, true) }

    @Test
    fun a_plugin_that_came_back_leaves_nothing_behind_for_the_next_start() {
        val first = restart()
        assertTrue(first.startPass())
        first.guardPlugin {}

        val second = restart()
        assertTrue(second.startPass(), "the previous process ran its plugins and survived")
        assertNull(second.reason)
    }

    @Test
    fun a_process_that_never_came_back_is_what_safe_mode_is_for() {
        crash()

        val next = restart()
        assertFalse(next.startPass())
        assertEquals(BootGuard.Reason.CRASHED, next.reason)
    }

    /**
     * the pass is unbounded - the app stops *waiting* at `BootCohort.EARLY_BUDGET_MILLIS` while the
     * plugins behind that keep evaluating - so a flag held across all of it turns android reaping a
     * process it considers idle into a crash nobody had.
     */
    @Test
    fun a_pass_commits_nothing_between_one_plugin_and_the_next() {
        val guard = restart()
        assertTrue(guard.startPass())
        assertFalse(armed(), "a pass that armed before it had anything to blame")
        guard.guardPlugin {}
        assertFalse(armed())

        assertTrue(restart().startPass(), "a process reaped between two plugins did not crash")
    }

    @Test
    fun the_flag_is_on_disk_while_a_plugin_runs_and_off_it_again_after() {
        val guard = restart()
        assertTrue(guard.startPass())
        var duringWrite = false
        guard.guardPlugin { duringWrite = InuConfig.prefs.getBoolean(BootGuard.GUARD_KEY, false) }
        assertTrue(duringWrite, "a plugin ran with nothing committed to catch it")
        assertFalse(armed())
    }

    /** a plugin throwing is not a crash: the app is still here to clear the flag */
    @Test
    fun a_plugin_that_threw_still_disarms_the_guard() {
        val guard = restart()
        assertTrue(guard.startPass())
        runCatching { guard.guardPlugin { error("boom") } }
        assertFalse(armed())
        assertTrue(restart().startPass())
    }

    @Test
    fun safe_mode_lasts_one_start() {
        crash()
        assertFalse(restart().startPass())

        val third = restart()
        assertTrue(third.startPass(), "skipping the plugins is what proved the app can start")
        assertNull(third.reason)
    }

    @Test
    fun every_pass_of_a_process_safe_mode_was_decided_for_is_skipped() {
        crash()

        val next = restart()
        assertFalse(next.startPass())
        assertFalse(next.startPass(), "the late cohort must not run in a process the boot cohort was skipped in")
    }

    @Test
    fun a_second_pass_runs_plugins_under_a_guard_of_their_own() {
        val guard = restart()
        assertTrue(guard.startPass())
        guard.guardPlugin {}
        assertTrue(guard.startPass())
        var duringWrite = false
        guard.guardPlugin { duringWrite = InuConfig.prefs.getBoolean(BootGuard.GUARD_KEY, false) }
        assertTrue(duringWrite)
        assertFalse(armed())
    }

    @Test
    fun the_user_asking_for_safe_mode_is_honoured_once_and_named() {
        restart().armForcedSafeMode()

        val next = restart()
        assertFalse(next.startPass())
        assertEquals(BootGuard.Reason.FORCED, next.reason)

        assertTrue(restart().startPass())
    }

    @Test
    fun a_forced_safe_mode_outranks_the_crash_guard_in_what_it_says() {
        crash()
        restart().armForcedSafeMode()

        val next = restart()
        assertFalse(next.startPass())
        assertEquals(BootGuard.Reason.FORCED, next.reason)
        assertTrue(restart().startPass(), "both flags have to be cleared, or the next start is safe too")
    }
}
