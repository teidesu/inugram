package desu.inugram.core.plugins

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BootGuardTest {
    /** the flags outlive the process, so one map is every start of one install */
    private val disk = HashMap<String, Boolean>()

    /** once set, the clearing write never lands: the process died with plugin code on the stack */
    private var dying = false

    private val store = object : BootGuard.Store {
        override fun read(key: String): Boolean = disk[key] ?: false
        override fun write(key: String, value: Boolean) {
            if (dying && !value) return
            disk[key] = value
        }
    }

    private fun restart() = BootGuard(store)

    private fun runOnePlugin(guard: BootGuard, armed: MutableList<Boolean> = mutableListOf()) {
        guard.guardPlugin { armed.add(disk[BootGuard.GUARD_KEY] == true) }
    }

    @Test
    fun `a plugin that came back leaves nothing behind for the next start`() {
        val first = restart()
        assertTrue(first.startPass())
        runOnePlugin(first)

        val second = restart()
        assertTrue(second.startPass(), "the previous process ran its plugins and survived")
        assertNull(second.reason)
    }

    @Test
    fun `a process that never came back is what safe mode is for`() {
        val first = restart()
        assertTrue(first.startPass())
        dying = true
        runOnePlugin(first)
        dying = false

        val next = restart()
        assertFalse(next.startPass())
        assertEquals(BootGuard.Reason.CRASHED, next.reason)
    }

    /**
     * the pass is unbounded - the app stops *waiting* at [BootCohort.EARLY_BUDGET_MILLIS] while the
     * plugins behind that keep evaluating - so a flag held across all of it turns android reaping a
     * process it considers idle into a crash nobody had.
     */
    @Test
    fun `a pass commits nothing between one plugin and the next`() {
        val guard = restart()
        assertTrue(guard.startPass())
        assertFalse(disk[BootGuard.GUARD_KEY] == true, "a pass that armed before it had anything to blame")
        runOnePlugin(guard)
        assertFalse(disk[BootGuard.GUARD_KEY] == true)

        assertTrue(restart().startPass(), "a process reaped between two plugins did not crash")
    }

    @Test
    fun `safe mode lasts one start`() {
        val first = restart()
        first.startPass()
        dying = true
        runOnePlugin(first)
        dying = false
        assertFalse(restart().startPass())

        val third = restart()
        assertTrue(third.startPass(), "skipping the plugins is what proved the app can start")
        assertNull(third.reason)
    }

    @Test
    fun `a start with no ui arms and disarms exactly like one with a ui`() {
        val headless = restart()
        assertTrue(headless.startPass())
        val armed = mutableListOf<Boolean>()
        runOnePlugin(headless, armed)
        assertEquals(listOf(true), armed, "a plugin ran with nothing committed to catch it")
        assertFalse(disk[BootGuard.GUARD_KEY] == true)

        assertTrue(restart().startPass(), "a process android woke and killed is not a crash")
    }

    @Test
    fun `every pass of a process safe mode was decided for is skipped`() {
        val first = restart()
        first.startPass()
        dying = true
        runOnePlugin(first)
        dying = false

        val next = restart()
        assertFalse(next.startPass())
        assertFalse(next.startPass(), "the late cohort must not run in a process the boot cohort was skipped in")
    }

    @Test
    fun `a second pass runs plugins under a guard of their own`() {
        val guard = restart()
        assertTrue(guard.startPass())
        runOnePlugin(guard)
        assertTrue(guard.startPass())
        val armed = mutableListOf<Boolean>()
        runOnePlugin(guard, armed)
        assertEquals(listOf(true), armed)
        assertFalse(disk[BootGuard.GUARD_KEY] == true)
    }

    @Test
    fun `the user asking for safe mode is honoured once and named`() {
        restart().armForcedSafeMode()

        val next = restart()
        assertFalse(next.startPass())
        assertEquals(BootGuard.Reason.FORCED, next.reason)

        assertTrue(restart().startPass())
    }

    @Test
    fun `a forced safe mode outranks the crash guard in what it says`() {
        val first = restart()
        first.startPass()
        dying = true
        runOnePlugin(first)
        dying = false
        restart().armForcedSafeMode()

        val next = restart()
        assertFalse(next.startPass())
        assertEquals(BootGuard.Reason.FORCED, next.reason)
        assertTrue(restart().startPass(), "both flags have to be cleared, or the next start is safe too")
    }
}
