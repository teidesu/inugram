package desu.inugram.helpers.plugins

import android.util.Log
import desu.inugram.helpers.plugins.platform.PluginJvm
import desu.inugram.helpers.plugins.platform.PluginJvmRoutine
import desu.inugram.jvmfixture.JvmFixture
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Not a test of behaviour: a benchmark of what a routine costs the thread it runs on, so a change
 * to the interpreter or to what `@inugram/cli` emits is measured rather than guessed at. The bodies
 * are the compiler's own output, and the host answers every java operation at once, so the number
 * is the interpreter's and not reflection's.
 */
class PluginJvmRoutineBenchTest {
    private lateinit var host: ConstantRoutineHost

    @Before fun setUp() {
        resetBridge()
        host = ConstantRoutineHost(startPlugin("routine-bench").session!!)
    }

    private fun routineOf(program: String) = PluginJvmRoutine(program, emptyList(), host, false)

    private class Case(val name: String, val program: String, val args: Array<Any?>)

    private val cases = listOf(
        Case(
            "predicate",
            """{"v":1,"slots":0,"tries":[],"code":[["arg",[0]],["eq",0,[42]],["return",1]]}""",
            arrayOf<Any?>(42),
        ),
        Case(
            "predicate3",
            """{"v":1,"slots":1,"tries":[],"code":[["arg",[0]],["eq",0,[1]],["setSlot",0,1],
               ["jumpIfFalsy",1,7],["arg",[1]],["eq",4,[2]],["setSlot",0,5],["getSlot",0],["setSlot",0,7],
               ["jumpIfFalsy",7,13],["arg",[2]],["eq",10,[3]],["setSlot",0,11],["getSlot",0],
               ["return",13]]}""",
            arrayOf<Any?>(1, 2, 3),
        ),
        Case(
            "arith",
            """{"v":1,"slots":1,"tries":[],"code":[["arg",[0]],["arg",[1]],["add",0,1],["setSlot",0,2],
               ["getSlot",0],["mul",4,[2]],["setSlot",0,5],["getSlot",0],["sub",7,[1]],["setSlot",0,8],
               ["getSlot",0],["rem",10,[7]],["return",11]]}""",
            arrayOf<Any?>(3, 4),
        ),
        Case(
            "branchy",
            """{"v":1,"slots":0,"tries":[],"code":[["arg",[0]],["gt",0,[10]],["jumpIfFalsy",1,6],["this"],
               ["call",3,["f"],[0]],["return",4],["return",[0]]]}""",
            arrayOf<Any?>(1),
        ),
        Case(
            "javacall",
            """{"v":1,"slots":0,"tries":[],"code":[["this"],["arg",[0]],["call",0,["f"],[1]],["return",2]]}""",
            arrayOf<Any?>(1),
        ),
        Case(
            "mixed",
            """{"v":1,"slots":0,"tries":[],"code":[["this"],["arg",[0]],["call",0,["f"],[1]],["arg",[1]],
               ["call",0,["g"],[3]],["gt",2,4],["jumpIfFalsy",5,9],["call",0,["set"],[2]],["jump",10],
               ["call",0,["set"],[4]],["add",2,4],["return",10]]}""",
            arrayOf<Any?>(1, 2),
        ),
        // last, so it is timed against a warm interpreter and reads as the fixed cost of a run
        Case("empty", """{"v":1,"slots":0,"tries":[],"code":[]}""", arrayOf()),
    )

    private fun measure(rounds: Int, iterations: Int, run: () -> Unit): Double {
        repeat(iterations) { run() }
        val samples = DoubleArray(rounds) {
            val started = System.nanoTime()
            repeat(iterations) { run() }
            (System.nanoTime() - started).toDouble() / iterations
        }
        return samples.sorted()[rounds / 2]
    }

    @Test fun bench_routine_execution() {
        val self = Any()
        val report = StringBuilder()
        for (case in cases) {
            val routine = routineOf(case.program)
            val time = measure(7, 20000) { routine.execute(null, self, case.args) }
            report.append(" | ${case.name}=%.0fns".format(time))
        }
        Log.i("InuBench", "routine execute$report")
    }

    /**
     * `function (a, b, c) { if (a !== 1) { return false } return this.f(b, c) }`, the shape a hook
     * filter has, with the receiver and the later arguments read where the compiler reads them and
     * where a prologue would have. Called with an `a` the filter turns away, so what the body never
     * reaches is the whole difference.
     */
    @Test fun bench_routine_reads_what_the_body_reaches() {
        val self = Any()
        val args = arrayOf<Any?>(2, JvmFixture(), JvmFixture())
        val hoisted = routineOf(
            """{"v":1,"slots":0,"tries":[],"code":[["this"],["arg",[0]],["arg",[1]],["arg",[2]],["ne",1,[1]],
               ["jumpIfFalsy",4,7],["return",[false]],["call",0,["f"],[2,3]],["return",7]]}""",
        )
        val sunk = routineOf(
            """{"v":1,"slots":0,"tries":[],"code":[["arg",[0]],["ne",0,[1]],["jumpIfFalsy",1,4],["return",[false]],
               ["this"],["arg",[1]],["arg",[2]],["call",4,["f"],[5,6]],["return",7]]}""",
        )
        val hoistedTime = measure(7, 20000) { hoisted.execute(null, self, args) }
        val sunkTime = measure(7, 20000) { sunk.execute(null, self, args) }
        Log.i(
            "InuBench",
            "routine filter: hoisted=%.0fns sunk=%.0fns (%.2fx)".format(hoistedTime, sunkTime, hoistedTime / sunkTime),
        )
    }

    @Test fun bench_routine_load() {
        val report = StringBuilder()
        for (case in cases) {
            val time = measure(7, 2000) { routineOf(case.program) }
            report.append(" | ${case.name}=%.1fus".format(time / 1000))
        }
        Log.i("InuBench", "routine load$report")
    }

    /** what the interpreter is a share of once the bridge does the reflection a real body asks for */
    @Test fun bench_routine_through_the_bridge() {
        val plugin = startPlugin("routine-bench", "unsafe.jvm")
        val fixture = JvmFixture()
        val handle = "G" + PluginJvm.bridgeFor(plugin.js)!!.encode(fixture).substring(2)
        val wire = plugin.js.listener!!.jvm(
            PluginJvm.OP_ROUTINE,
            0,
            """{"v":1,"slots":0,"tries":[],"code":[["capture",0],["get",0,["count"]],["add",1,[1]],["set",0,["count"],2]]}""",
            arrayOf(handle),
        )
        assertTrue(wire.startsWith("GO"), wire)
        val task = PluginJvm.bridgeFor(plugin.js)!!.decode("G" + wire.substring(2)) as Runnable
        val time = measure(7, 5000) { task.run() }
        Log.i("InuBench", "routine through the bridge: read+add+write=%.0fns".format(time))
    }
}
