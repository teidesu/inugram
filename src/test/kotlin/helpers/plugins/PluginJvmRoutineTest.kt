package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.platform.PluginJvm
import desu.inugram.helpers.plugins.platform.PluginJvmRoutine
import desu.inugram.core.plugins.PluginRefusal
import desu.inugram.jvmfixture.JvmFixture
import desu.inugram.jvmfixture.JvmSuperBase
import desu.inugram.jvmfixture.JvmSuperChild
import desu.inugram.jvmfixture.JvmSuperConcrete
import desu.inugram.jvmfixture.JvmSuperGrandchild
import org.json.JSONArray
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

class PluginJvmRoutineTest {
    @Before fun setUp() = resetBridge()

    @Test fun compiler_output_preserves_control_flow_and_input_failures() {
        val plugin = startPlugin("compiled-routines", "unsafe.jvm")
        val host = PluginJvm.bridgeFor(plugin.js) as PluginJvm.Session
        val cases = JSONArray(testAsset("routines.json").toString(Charsets.UTF_8))
        val oversized = "x".repeat(PluginJvm.VALUE_LIMIT_BYTES + 1)
        for (at in 0 until cases.length()) {
            val case = cases.getJSONObject(at)
            val name = case.getString("name")
            val trace = StringBuilder()
            val args = arrayOf<Any?>(trace, if (case.optBoolean("oversizedArgument")) oversized else 3)
            val receiver = if (case.optBoolean("oversizedReceiver")) oversized else Any()
            val routine = PluginJvmRoutine(case.getJSONObject("program").toString(), emptyList(), host, false)
            try {
                val result = routine.execute(null, receiver, args)
                assertTrue(result is Number, "$name: $result")
                assertEquals(case.getLong("expected"), result.toLong(), name)
                assertEquals(case.getString("trace"), trace.toString(), name)
            } finally {
                routine.close()
            }
        }
    }

    private fun program(code: String, slots: Int = 0, tries: String = "[]", layout: String? = null): String {
        val shape = if (layout == null) "" else ""","layout":$layout"""
        return """{"v":1,"slots":$slots,"tries":$tries,"code":[$code]$shape}"""
    }

    private fun buildRoutine(plugin: Plugin, definition: String, vararg args: String): String =
        plugin.jvm(PluginJvm.OP_ROUTINE, 0, definition, *args)

    private fun createRoutine(plugin: Plugin, definition: String, vararg args: String): Runnable {
        val wire = buildRoutine(plugin, definition, *args)
        assertTrue(wire.startsWith("GO"), wire)
        return PluginJvm.bridgeFor(plugin.js)!!.decode("G" + wire.substring(2)) as Runnable
    }

    @Test fun routine_runs_java_on_the_calling_thread() {
        val plugin = startPlugin("routine", "unsafe.jvm")
        val fixture = JvmFixture()
        val task = createRoutine(
            plugin,
            program("""["capture",0],["capture",1],["call",1,["currentThread"],[]],["set",0,["payload"],2]"""),
            plugin.jvmWire(fixture),
            plugin.jvmWire(Thread::class.java),
        )
        assertEquals(runOnCaller(task), fixture.payload)
    }

    @Test fun a_skipped_branch_runs_nothing_and_a_run_starts_from_scratch() {
        val plugin = startPlugin("routine", "unsafe.jvm")
        val fixture = JvmFixture()
        val task = createRoutine(
            plugin,
            program(
                """["capture",0],["jumpIfFalsy",[false],4],["call",0,["boom"],[]],["jump",4],
                   ["get",0,["count"]],["add",4,[2]],["set",0,["count"],5]""",
            ),
            plugin.jvmWire(fixture),
        )
        task.run()
        assertEquals(5, fixture.count)
        task.run()
        assertEquals(7, fixture.count)
        PluginJvm.detach(plugin.session!!)
        task.run()
        assertEquals(7, fixture.count)
    }

    @Test fun a_refused_result_stops_the_routine_before_writes() {
        val plugin = startPlugin("routine", "unsafe.jvm")
        val fixture = JvmFixture().apply { label = "x".repeat(PluginJvm.VALUE_LIMIT_BYTES + 1) }
        createRoutine(
            plugin,
            program("""["capture",0],["get",0,["label"]],["set",0,["count"],[99]]"""),
            plugin.jvmWire(fixture),
        ).run()
        assertEquals(3, fixture.count)
    }

    @Test fun catch_takes_over_after_a_java_exception_or_error_and_binds_what_was_thrown() {
        val plugin = startPlugin("routine", "unsafe.jvm")
        for ((method, thrown) in listOf("boom" to IllegalStateException::class, "detonate" to AssertionError::class)) {
            val fixture = JvmFixture()
            createRoutine(
                plugin,
                program(
                    """["capture",0],["call",0,["$method"],[]],["jump",5],["catch"],["set",0,["payload"],3],
                       ["set",0,["count"],[99]]""",
                    tries = "[[1,3,3]]",
                ),
                plugin.jvmWire(fixture),
            ).run()
            assertEquals(99, fixture.count, method)
            assertTrue(thrown.isInstance(fixture.payload), "$method: ${fixture.payload}")
        }
    }

    @Test fun an_error_nothing_catches_is_not_swallowed() {
        val plugin = startPlugin("routine", "unsafe.jvm")
        val fixture = JvmFixture()
        val routine = createRoutine(
            plugin,
            program("""["capture",0],["call",0,["detonate"],[]]"""),
            plugin.jvmWire(fixture),
        )
        assertFailsWith<AssertionError> { routine.run() }
    }

    @Test fun malformed_programs_are_refused_before_anything_runs() {
        val plugin = startPlugin("routine", "unsafe.jvm")
        for (definition in listOf(
            """{"v":2,"code":[["this"]]}""",
            """{"v":1,"code":[["nope"]]}""",
            program("""["add",0,[1]]"""),
            program("""["not",[false]],["jump",0]"""),
            program("""["setResult",[1]]"""),
            program("""["getSlot",0]"""),
            program("""["capture",7]"""),
            program("""["not",[false]],["not",[false]]""", tries = "[[0,1,1]]"),
        )) {
            val wire = buildRoutine(plugin, definition, "I1")
            assertTrue(wire.startsWith("Pinvalid-argument"), "$definition -> $wire")
        }
    }

    @Test fun comparisons_preserve_large_integers_and_do_not_coerce_types() {
        val plugin = startPlugin("comparisons", "unsafe.jvm")
        val fixture = JvmFixture()
        val target = plugin.jvmWire(fixture)
        for ((op, left, right, expected) in listOf(
            listOf("eq", "I9007199254740993", "I9007199254740992", false),
            listOf("gt", "I9007199254740993", "D9007199254740992", true),
            listOf("lt", "I9223372036854775807", "D9223372036854775808", true),
            listOf("eq", "D-0.0", "I0", true),
            listOf("eq", "I1", "S1", false),
            listOf("ne", "B1", "I1", true),
            listOf("eq", "N", "N", true),
            listOf("le", "Sa", "Sb", true),
            listOf("ge", "Sb", "Sb", true),
        )) {
            fixture.flag = !(expected as Boolean)
            createRoutine(
                plugin,
                program("""["capture",0],["capture",1],["capture",2],["$op",1,2],["set",0,["flag"],3]"""),
                target,
                left as String,
                right as String,
            ).run()
            assertEquals(expected, fixture.flag, "$left $op $right")
        }
    }

    @Test fun conditional_jumps_skip_the_side_that_must_not_run() {
        val plugin = startPlugin("booleans", "unsafe.jvm")
        val fixture = JvmFixture()
        val target = plugin.jvmWire(fixture)
        for ((jump, operand, expected) in listOf(
            Triple("jumpIfFalsy", "I0", false),
            Triple("jumpIfTruthy", "Syes", true),
        )) {
            fixture.flag = !expected
            createRoutine(
                plugin,
                program(
                    """["capture",0],["capture",1],["$jump",1,4],["call",0,["boom"],[]],
                       ["set",0,["flag"],[$expected]]""",
                ),
                target,
                operand,
            ).run()
            assertEquals(expected, fixture.flag, jump)
        }
        createRoutine(plugin, program("""["capture",0],["not",[null]],["set",0,["flag"],1]"""), target).run()
        assertTrue(fixture.flag)
    }

    @Test fun slots_hold_values_across_instructions_and_reset_each_run() {
        val plugin = startPlugin("slots", "unsafe.jvm")
        val fixture = JvmFixture()
        val task = createRoutine(
            plugin,
            program(
                """["capture",0],["setSlot",0,[1]],["getSlot",0],["add",2,[2]],["setSlot",0,3],
                   ["getSlot",0],["mul",5,[2]],["setSlot",0,6],["getSlot",0],["set",0,["count"],8]""",
                slots = 1,
            ),
            plugin.jvmWire(fixture),
        )
        repeat(2) {
            task.run()
            assertEquals(6, fixture.count)
        }
    }

    @Test fun arithmetic_failure_stops_later_writes() {
        val plugin = startPlugin("routine", "unsafe.jvm")
        val fixture = JvmFixture()
        val target = plugin.jvmWire(fixture)
        for (operands in listOf(arrayOf("I1", "I0"), arrayOf("I-9223372036854775808", "I-1"))) {
            createRoutine(
                plugin,
                program("""["capture",0],["capture",1],["capture",2],["div",1,2],["set",0,["count"],[99]]"""),
                target,
                *operands,
            ).run()
            assertEquals(3, fixture.count)
        }
    }

    @Test fun a_for_of_loop_walks_a_java_iterable() {
        val plugin = startPlugin("loops", "unsafe.jvm")
        val fixture = JvmFixture().apply { payload = listOf(1, 2, 3) }
        createRoutine(
            plugin,
            program(
                """["capture",0],["get",0,["payload"]],["iterate",1],["setSlot",0,[0]],
                   ["advance",2,9],["getSlot",0],["add",5,4],["setSlot",0,6],["loop",4],
                   ["getSlot",0],["set",0,["count"],9]""",
                slots = 1,
            ),
            plugin.jvmWire(fixture),
        ).run()
        assertEquals(6, fixture.count)
    }

    @Test fun new_builds_an_instance_and_instanceof_answers_for_it() {
        val plugin = startPlugin("new", "unsafe.jvm")
        val fixture = JvmFixture()
        createRoutine(
            plugin,
            program(
                """["capture",0],["capture",1],["new",1,[[42]]],["get",2,["madeBy"]],["set",0,["label"],3],
                   ["instanceOf",2,1],["set",0,["flag"],5]""",
            ),
            plugin.jvmWire(fixture),
            plugin.jvmWire(JvmFixture::class.java),
        ).run()
        assertEquals("int", fixture.label)
        assertTrue(fixture.flag)
    }

    @Test fun text_concatenates_and_bitwise_follows_java_promotion() {
        val plugin = startPlugin("operators", "unsafe.jvm")
        val fixture = JvmFixture()
        createRoutine(
            plugin,
            program(
                """["capture",0],["get",0,["label"]],["add",1,["!"]],["set",0,["label"],2],
                   ["bitOr",[5],[2]],["shl",[1],[3]],["add",4,5],["set",0,["count"],6]""",
            ),
            plugin.jvmWire(fixture),
        ).run()
        assertEquals("inugram!", fixture.label)
        assertEquals(15, fixture.count)
    }

    @Test fun an_array_capture_arrives_as_one_object_array() {
        val plugin = startPlugin("captures", "unsafe.jvm")
        val fixture = JvmFixture()
        createRoutine(
            plugin,
            program(
                """["capture",0],["capture",1],["get",1,[1]],["get",1,["length"]],["add",2,3],
                   ["set",0,["count"],4]""",
                layout = "[-1,[-1,-1,-1]]",
            ),
            plugin.jvmWire(fixture),
            "I10",
            "I20",
            "I30",
        ).run()
        assertEquals(23, fixture.count)
    }

    @Test fun a_body_that_does_nothing_builds_and_runs() {
        val plugin = startPlugin("empty", "unsafe.jvm")
        createRoutine(plugin, program("")).run()
    }

    @Test fun a_loop_that_never_ends_stops_at_the_budget() {
        val plugin = startPlugin("budget", "unsafe.jvm")
        val task = createRoutine(plugin, program("""["not",[false]],["loop",1]"""))
        val started = System.nanoTime()
        task.run()
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsed in 200..5000, "a runaway loop ran for $elapsed ms")
    }

    @Test fun call_super_runs_the_superclass_member_of_the_class_it_names() {
        val plugin = startPlugin("call-super", "unsafe.jvm")
        val host = PluginJvm.bridgeFor(plugin.js) as PluginJvm.Session
        fun callSuper(cls: Class<*>, receiver: Any, name: String, vararg args: Any?): Any? {
            val arguments = args.indices.joinToString(",") { "[\"capture\",${it + 2}]" }
            val base = 2 + args.size
            val code = """["capture",0],["capture",1]${if (args.isEmpty()) "" else ",$arguments"},""" +
                """["callSuper",0,1,["$name"],[${(2 until base).joinToString(",")}]],["return",$base]"""
            val routine = PluginJvmRoutine(program(code), listOf(cls, receiver, *args), host, false)
            try {
                return routine.execute(null, null, emptyArray())
            } finally {
                routine.close()
            }
        }
        val grandchild = JvmSuperGrandchild()
        assertEquals("base", callSuper(JvmSuperChild::class.java, grandchild, "describe"))
        assertEquals("child", callSuper(JvmSuperGrandchild::class.java, grandchild, "describe"))
        assertEquals("base:x", callSuper(JvmSuperChild::class.java, grandchild, "describe", "x"))
        assertEquals(10, callSuper(JvmSuperChild::class.java, grandchild, "scale", 5))
        assertEquals("static on the base", callSuper(JvmSuperChild::class.java, grandchild, "origin"))
        val thrown = assertFailsWith<IllegalStateException> { callSuper(JvmSuperChild::class.java, grandchild, "explode") }
        assertEquals("base explodes", thrown.message)
        assertEquals("child does not", grandchild.explode())
        assertFailsWith<PluginRefusal> { callSuper(JvmSuperConcrete::class.java, JvmSuperConcrete(), "describe") }
        assertFailsWith<PluginRefusal> { callSuper(JvmSuperChild::class.java, JvmSuperBase(), "describe") }
        assertFailsWith<PluginRefusal> { callSuper(Any::class.java, grandchild, "hashCode") }
    }
}
