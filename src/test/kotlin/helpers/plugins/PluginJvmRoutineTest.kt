package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.platform.PluginJvm
import desu.inugram.jvmfixture.JvmFixture
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

class PluginJvmRoutineTest {
    @Before fun setUp() = resetBridge()

    private fun createRoutine(plugin: Plugin, definition: String, vararg args: String): Runnable {
        val wire = plugin.js.listener!!.jvm(PluginJvm.OP_ROUTINE, 0, definition, arrayOf(*args))
        assertTrue(wire.startsWith("GO"), wire)
        return PluginJvm.bridgeFor(plugin.js)!!.decode("G" + wire.substring(2)) as Runnable
    }

    @Test fun routine_runs_java_on_the_calling_thread() {
        val plugin = startPlugin("routine", "unsafe.jvm")
        val fixture = JvmFixture()
        val bridge = PluginJvm.bridgeFor(plugin.js)!!
        val target = "G" + bridge.encode(fixture).substring(2)
        val threadClass = "G" + bridge.encode(Thread::class.java).substring(2)
        val task = createRoutine(plugin, """{"nodes":[["call",[0,1],"currentThread",[]],["set",[0,0],"payload",[1,0]]],"roots":[1]}""", target, threadClass)
        val thread = Thread(task)
        thread.start()
        thread.join(5000)
        assertTrue(!thread.isAlive)
        assertEquals(thread, fixture.payload)
    }

    @Test fun branches_are_lazy_and_results_are_recomputed_per_run() {
        val plugin = startPlugin("routine", "unsafe.jvm")
        val fixture = JvmFixture()
        val target = "G" + PluginJvm.bridgeFor(plugin.js)!!.encode(fixture).substring(2)
        val task = createRoutine(plugin, """{"nodes":[["get",[0,0],"count"],["math","+",[1,0],[0,1]],["set",[0,0],"count",[1,1]],["call",[0,0],"boom",[]],["when",[0,2],[2],[3]]],"roots":[4,2]}""", target, "I2", "B1")
        task.run()
        assertEquals(5, fixture.count)
        task.run()
        assertEquals(7, fixture.count)
        PluginJvm.detach(plugin.js)
        task.run()
        assertEquals(7, fixture.count)
    }

    /** an operand the bridge refuses to carry stops the routine where it stands, writes included */
    @Test fun a_refused_result_stops_the_routine_before_writes() {
        val plugin = startPlugin("routine", "unsafe.jvm")
        val fixture = JvmFixture().apply { label = "x".repeat(PluginJvm.VALUE_LIMIT_BYTES + 1) }
        val target = "G" + PluginJvm.bridgeFor(plugin.js)!!.encode(fixture).substring(2)
        val task = createRoutine(plugin, """{"nodes":[["get",[0,0],"label"],["set",[0,0],"count",[0,1]]],"roots":[0,1]}""", target, "I99")
        task.run()
        assertEquals(3, fixture.count)
    }

    @Test fun attempt_runs_fallback_after_a_java_exception() {
        val plugin = startPlugin("routine", "unsafe.jvm")
        val fixture = JvmFixture()
        val target = "G" + PluginJvm.bridgeFor(plugin.js)!!.encode(fixture).substring(2)
        val task = createRoutine(plugin, """{"nodes":[["call",[0,0],"boom",[]],["set",[0,0],"count",[0,1]],["attempt",[0],[1]]],"roots":[2]}""", target, "I99")
        task.run()
        assertEquals(99, fixture.count)
    }

    @Test fun malformed_graphs_are_refused_before_any_operation_runs() {
        val plugin = startPlugin("routine", "unsafe.jvm")
        for (definition in listOf(
            """{"nodes":[["math","+",[1,0],[0,0]]],"roots":[0]}""",
            """{"nodes":[],"roots":[0]}""",
            """{"nodes":[["math","unknown",[0,0],[0,0]]],"roots":[0]}""",
        )) {
            val wire = plugin.js.listener!!.jvm(PluginJvm.OP_ROUTINE, 0, definition, arrayOf("I1"))
            assertTrue(wire.startsWith("Pinvalid-argument"), wire)
        }
    }

    @Test fun comparisons_preserve_large_integers_and_do_not_coerce_types() {
        val plugin = startPlugin("comparisons", "unsafe.jvm")
        val fixture = JvmFixture()
        val target = "G" + PluginJvm.bridgeFor(plugin.js)!!.encode(fixture).substring(2)
        for ((op, left, right, expected) in listOf(
            listOf("==", "I9007199254740993", "I9007199254740992", false),
            listOf(">", "I9007199254740993", "D9007199254740992", true),
            listOf("<", "I9223372036854775807", "D9223372036854775808", true),
            listOf("==", "D-0.0", "I0", true),
            listOf("==", "I1", "S1", false),
            listOf("!=", "B1", "I1", true),
            listOf("==", "N", "N", true),
            listOf("<=", "Sa", "Sb", true),
            listOf(">=", "Sb", "Sb", true),
        )) {
            fixture.flag = !(expected as Boolean)
            createRoutine(plugin, """{"nodes":[["compare","$op",[0,1],[0,2]],["set",[0,0],"flag",[1,0]]],"roots":[1]}""", target, left as String, right as String).run()
            assertEquals(expected, fixture.flag, "$left $op $right")
        }
        createRoutine(plugin, """{"nodes":[["compare","==",[0,0],[0,0]],["set",[0,0],"flag",[1,0]]],"roots":[1]}""", target).run()
        assertTrue(fixture.flag)
    }

    @Test fun boolean_operations_short_circuit_and_return_booleans() {
        val plugin = startPlugin("booleans", "unsafe.jvm")
        val fixture = JvmFixture()
        val target = "G" + PluginJvm.bridgeFor(plugin.js)!!.encode(fixture).substring(2)
        for ((op, left, expected) in listOf(Triple("and", "I0", false), Triple("or", "Syes", true))) {
            fixture.flag = !expected
            createRoutine(plugin, """{"nodes":[["call",[0,0],"boom",[]],["$op",[0,1],[1,0]],["set",[0,0],"flag",[1,1]]],"roots":[2]}""", target, left).run()
            assertEquals(expected, fixture.flag)
        }
        createRoutine(plugin, """{"nodes":[["not",[0,1]],["set",[0,0],"flag",[1,0]]],"roots":[1]}""", target, "N").run()
        assertTrue(fixture.flag)
        createRoutine(plugin, """{"nodes":[["compare","<",[0,1],[0,2]],["set",[0,0],"count",[0,3]]],"roots":[0,1]}""", target, "S1", "I1", "I99").run()
        assertEquals(3, fixture.count)
    }

    @Test fun locals_reset_each_run_and_distinct_reads_observe_assignments() {
        val plugin = startPlugin("locals", "unsafe.jvm")
        val fixture = JvmFixture()
        val target = "G" + PluginJvm.bridgeFor(plugin.js)!!.encode(fixture).substring(2)
        val task = createRoutine(plugin, """{"nodes":[["setLocal","x",[0,1]],["getLocal","x"],["math","+",[1,1],[0,2]],["setLocal","x",[1,2]],["getLocal","x"],["math","*",[1,4],[0,2]],["setLocal","x",[1,5]],["getLocal","x"],["set",[0,0],"count",[1,7]],["set",[0,0],"payload",[1,1]]],"roots":[0,3,6,8,9]}""", target, "I1", "I2")
        repeat(2) {
            task.run()
            assertEquals(6, fixture.count)
            assertEquals(1, fixture.payload)
        }
    }

    @Test fun skipped_set_does_not_initialize_a_local_and_null_is_a_valid_value() {
        val plugin = startPlugin("locals", "unsafe.jvm")
        val fixture = JvmFixture()
        val target = "G" + PluginJvm.bridgeFor(plugin.js)!!.encode(fixture).substring(2)
        createRoutine(plugin, """{"nodes":[["setLocal","x",[0,1]],["when",[0,2],[0],[]],["getLocal","x"],["set",[0,0],"count",[0,1]]],"roots":[1,2,3]}""", target, "I99", "B0").run()
        assertEquals(3, fixture.count)
        createRoutine(plugin, """{"nodes":[["setLocal","x",[0,1]],["getLocal","x"],["set",[0,0],"payload",[1,1]],["set",[0,0],"count",[0,2]]],"roots":[0,2,3]}""", target, "N", "I99").run()
        assertEquals(null, fixture.payload)
        assertEquals(99, fixture.count)
    }

    @Test fun empty_local_names_are_refused() {
        val plugin = startPlugin("bad-locals", "unsafe.jvm")
        val wire = plugin.js.listener!!.jvm(PluginJvm.OP_ROUTINE, 0, """{"nodes":[["getLocal",""]],"roots":[0]}""", emptyArray())
        assertTrue(wire.startsWith("Pinvalid-argument"), wire)
    }

    @Test fun arithmetic_failure_stops_later_writes() {
        val plugin = startPlugin("routine", "unsafe.jvm")
        val fixture = JvmFixture()
        val target = "G" + PluginJvm.bridgeFor(plugin.js)!!.encode(fixture).substring(2)
        for (operands in listOf(arrayOf("I1", "I0"), arrayOf("I-9223372036854775808", "I-1"))) {
            val task = createRoutine(plugin, """{"nodes":[["math","/",[0,1],[0,2]],["set",[0,0],"count",[0,3]]],"roots":[0,1]}""", target, *operands, "I99")
            task.run()
            assertEquals(3, fixture.count)
        }
    }
}
