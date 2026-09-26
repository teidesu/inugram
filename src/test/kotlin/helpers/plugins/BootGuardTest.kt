package desu.inugram.helpers.plugins

import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class BootGuardTest {
    private lateinit var dir: File
    private var now = 1_000L

    @Before
    fun setUp() {
        resetBridge()
        dir = Files.createTempDirectory("boot-guard").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun restart() = BootGuard(dir) { now }

    private fun armed(): Boolean = File(dir, BootGuard.STARTING).exists()

    private fun crash() = File(dir, BootGuard.STARTING).apply { parentFile!!.mkdirs() }.writeText("")

    private fun crashAfterStart(after: Long) {
        val guard = restart()
        assertTrue(guard.startPass())
        guard.guardPlugin {}
        now += after
        guard.recordCrash()
    }

    @Test
    fun a_process_that_never_came_back_skips_every_pass_of_one_start() {
        crash()

        val next = restart()
        assertFalse(next.startPass())
        assertEquals(BootGuard.Reason.CRASHED, next.reason)
        assertFalse(next.startPass(), "the late cohort must not run in a process the boot cohort was skipped in")

        val third = restart()
        assertTrue(third.startPass(), "skipping the plugins is what proved the app can start")
        assertNull(third.reason)
    }

    /** a pass is unbounded, so a flag held across it would turn android reaping an idle process into a crash */
    @Test
    fun the_flag_is_on_disk_only_while_a_plugin_runs_in_every_pass() {
        val guard = restart()
        repeat(2) {
            assertTrue(guard.startPass())
            assertFalse(armed())
            var duringWrite = false
            guard.guardPlugin { duringWrite = armed() }
            assertTrue(duringWrite, "a plugin ran with nothing committed to catch it")
            assertFalse(armed())
        }

        val next = restart()
        assertTrue(next.startPass())
        assertNull(next.reason)
    }

    @Test
    fun a_plugin_that_threw_still_disarms_the_guard() {
        val guard = restart()
        assertTrue(guard.startPass())
        runCatching { guard.guardPlugin { error("boom") } }
        assertFalse(armed())
        assertTrue(restart().startPass())
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
    fun two_processes_crashing_soon_after_their_plugins_started_are_a_loop() {
        crashAfterStart(1_000)
        assertTrue(restart().startPass(), "one crash is not a loop")
        crashAfterStart(1_000)

        val next = restart()
        assertFalse(next.startPass())
        assertEquals(BootGuard.Reason.CRASH_LOOP, next.reason)
        assertTrue(restart().startPass(), "the count is spent by the safe start it caused")
    }

    @Test
    fun a_crash_long_after_the_plugins_started_is_not_counted() {
        crashAfterStart(1_000)
        crashAfterStart(BootGuard.CRASH_WINDOW_MILLIS)
        assertTrue(restart().startPass())
    }

    @Test
    fun a_crash_before_any_plugin_ran_is_not_counted() {
        repeat(BootGuard.CRASH_LIMIT) {
            val guard = restart()
            assertTrue(guard.startPass())
            guard.recordCrash()
        }
        assertTrue(restart().startPass())
    }

    @Test
    fun a_process_that_survived_the_window_resets_the_count() {
        crashAfterStart(1_000)
        val survivor = restart()
        assertTrue(survivor.startPass())
        survivor.guardPlugin {}
        survivor.survivedWindow()
        crashAfterStart(1_000)
        assertTrue(restart().startPass(), "the crashes were not consecutive")
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
