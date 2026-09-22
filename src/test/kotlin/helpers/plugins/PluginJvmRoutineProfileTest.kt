package desu.inugram.helpers.plugins

import android.util.Log
import desu.inugram.helpers.plugins.platform.PluginJvmRoutine
import desu.inugram.jvmfixture.JvmFixture
import org.junit.Before
import org.junit.Test

/**
 * Not a test of behaviour: what each instruction costs on its own. A body of one copy and a body of
 * sixty-five are the same run except for sixty-four more of the instruction under test, so the
 * difference divided by sixty-four is that instruction and nothing else, and the one-copy time
 * minus one of those is what a run costs before it has done anything.
 */
class PluginJvmRoutineProfileTest {
    private lateinit var host: ConstantRoutineHost

    @Before fun setUp() {
        resetBridge()
        host = ConstantRoutineHost(startPlugin("routine-profile").session!!)
    }

    private fun routineOf(program: String, captures: List<Any?>) =
        PluginJvmRoutine(program, captures, host, false)

    private fun measure(iterations: Int, run: () -> Unit): Double {
        repeat(iterations) { run() }
        val samples = DoubleArray(7) {
            val started = System.nanoTime()
            repeat(iterations) { run() }
            (System.nanoTime() - started).toDouble() / iterations
        }
        return samples.sorted()[3]
    }

    /** `body(at)` is the instruction under test, written for the register it lands on */
    private class Shape(
        val name: String,
        val prelude: List<String> = emptyList(),
        val slots: Int = 0,
        val captures: List<Any?> = listOf(7),
        val body: (Int) -> String,
    )

    private fun programOf(shape: Shape, copies: Int): String {
        val code = shape.prelude.toMutableList()
        repeat(copies) { code.add(shape.body(code.size)) }
        val layout = shape.captures.joinToString(",") { "-1" }
        return """{"v":1,"slots":${shape.slots},"tries":[],"layout":[$layout],"code":[${code.joinToString(",")}]}"""
    }

    private val shapes = listOf(
        Shape("capture") { """["capture",0]""" },
        Shape("this") { """["this"]""" },
        Shape("arg") { """["arg",[0]]""" },
        Shape("getSlot", slots = 1) { """["getSlot",0]""" },
        Shape("setSlot", prelude = listOf("""["capture",0]"""), slots = 1) { """["setSlot",0,0]""" },
        Shape("jump") { at -> """["jump",${at + 1}]""" },
        Shape("jumpIfFalsy", prelude = listOf("""["capture",0]""")) { at -> """["jumpIfFalsy",0,${at + 1}]""" },
        Shape("not", prelude = listOf("""["capture",0]""")) { """["not",0]""" },
        Shape("eq int", prelude = listOf("""["capture",0]""")) { """["eq",0,[7]]""" },
        Shape("eq object", prelude = listOf("""["capture",0]"""), captures = listOf(JvmFixture())) { """["eq",0,0]""" },
        Shape("lt int", prelude = listOf("""["capture",0]""")) { """["lt",0,[9]]""" },
        Shape("add small", prelude = listOf("""["capture",0]""")) { """["add",0,[1]]""" },
        Shape("add big", prelude = listOf("""["capture",0]"""), captures = listOf(1_000_000)) { """["add",0,[1]]""" },
        Shape("add double", prelude = listOf("""["capture",0]"""), captures = listOf(1.5)) { """["add",0,[1]]""" },
        Shape("add string", prelude = listOf("""["capture",0]"""), captures = listOf("x")) { """["add",0,["y"]]""" },
        Shape("mul", prelude = listOf("""["capture",0]""")) { """["mul",0,[3]]""" },
        Shape("bitAnd", prelude = listOf("""["capture",0]""")) { """["bitAnd",0,[3]]""" },
        Shape("array", prelude = listOf("""["capture",0]""")) { """["array",[0,0]]""" },
        Shape("call", prelude = listOf("""["capture",0]"""), captures = listOf(JvmFixture())) { """["call",0,["f"],[]]""" },
        Shape("call 2 args", prelude = listOf("""["capture",0]"""), captures = listOf(JvmFixture())) { """["call",0,["f"],[0,0]]""" },
    )

    @Test fun bench_routine_instruction_cost() {
        val self = Any()
        val args = arrayOf<Any?>(3)
        val report = StringBuilder()
        for (shape in shapes) {
            val one = routineOf(programOf(shape, 1), shape.captures)
            val many = routineOf(programOf(shape, 65), shape.captures)
            val oneTime = measure(20000) { one.execute(null, self, args) }
            val manyTime = measure(20000) { many.execute(null, self, args) }
            val each = (manyTime - oneTime) / 64
            report.append(" | ${shape.name}=%.1fns".format(each))
        }
        Log.i("InuBench", "routine per instruction$report")
    }

    /** what a turn of a `for of` costs, which is an `advance` and a `loop` plus the body */
    @Test fun bench_routine_loop_turn() {
        val self = Any()
        val args = arrayOf<Any?>(3)
        fun over(items: Int): Double {
            val list = (1..items).toList()
            val routine = PluginJvmRoutine(
                """{"v":1,"slots":1,"tries":[],"layout":[-1],"code":[["capture",0],["setSlot",0,[0]],
                   ["iterate",0],["advance",2,8],["getSlot",0],["add",4,3],["setSlot",0,5],["loop",3],
                   ["getSlot",0],["return",8]]}""",
                listOf(list),
                host,
                false,
            )
            return measure(12000) { routine.execute(null, self, args) }
        }
        val short = over(1)
        val long = over(65)
        Log.i("InuBench", "routine loop: turn=%.1fns (1 item %.0fns, 65 items %.0fns)".format((long - short) / 64, short, long))

        fun bare(items: Int): Double {
            val list = (1..items).toList()
            val routine = PluginJvmRoutine(
                """{"v":1,"slots":0,"tries":[],"layout":[-1],"code":[["capture",0],["iterate",0],
                   ["advance",1,4],["loop",2],["return",[0]]]}""",
                listOf(list),
                host,
                false,
            )
            return measure(12000) { routine.execute(null, self, args) }
        }
        Log.i("InuBench", "routine bare loop: turn=%.1fns".format((bare(65) - bare(1)) / 64))
    }

    @Test fun bench_routine_load_cost() {
        val shape = shapes.first { it.name == "add small" }
        val one = programOf(shape, 1)
        val many = programOf(shape, 65)
        val oneTime = measure(2000) { routineOf(one, shape.captures) }
        val manyTime = measure(2000) { routineOf(many, shape.captures) }
        val empty = measure(2000) { routineOf("""{"v":1,"slots":0,"tries":[],"code":[]}""", emptyList()) }
        val parseOne = measure(2000) { org.json.JSONObject(one) }
        val parseMany = measure(2000) { org.json.JSONObject(many) }
        Log.i(
            "InuBench",
            ("routine load: empty=%.1fus 2 instructions=%.1fus per instruction=%.2fus"
                + " | of which json parse: %.2fus per instruction").format(
                empty / 1000, oneTime / 1000, (manyTime - oneTime) / 64 / 1000, (parseMany - parseOne) / 64 / 1000,
            ),
        )
    }
}
