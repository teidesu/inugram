package desu.inugram.helpers.plugins.platform

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

internal class PluginJvmRoutine(
    definition: String,
    values: List<Any?>,
    private val isLive: () -> Boolean,
    private val hookMode: Boolean,
    private val validate: (Any?) -> Any?,
    private val invoke: (String, Any, String, List<Any?>) -> Any?,
) : Runnable {
    private class Operand(val reference: Boolean, val index: Int)
    private class Operation(
        val kind: String,
        val name: String,
        val operands: List<Operand>,
        val yes: List<Int> = emptyList(),
        val no: List<Int> = emptyList(),
    ) {
        /** the same vocabulary as [kind], resolved once: a hooked call would otherwise compare strings per operation */
        val code = CODES[kind] ?: CODE_MEMBER
    }

    @Volatile private var captured: List<Any?>? = values
    private val operations: List<Operation>
    private val roots: List<Int>

    /** what a run has to set up for, decided once: a predicate over arguments needs none of it */
    private val usesAttempt: Boolean
    private val usesJava: Boolean

    init {
        require(definition.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "routine: definition exceeds 1 MB" }
        val graph = JSONObject(definition)
        val nodes = graph.getJSONArray("nodes")
        require(nodes.length() <= 256) { "routine: at most 256 operations" }
        fun operandOf(value: JSONArray, index: Int): Operand {
            require(value.length() == 2) { "routine: malformed operand" }
            val reference = readIndex(value, 0, 2) == 1
            return Operand(reference, readIndex(value, 1, if (reference) index else values.size))
        }
        operations = List(nodes.length()) { index ->
            val node = nodes.getJSONArray(index)
            val kind = node.getString(0)
            fun operand(at: Int): Operand = operandOf(node.getJSONArray(at), index)
            when (kind) {
                "methodThis", "methodArgument", "methodSetResult" -> {
                    val count = if (kind == "methodThis") 0 else 1
                    require(node.length() == count + 1) { "routine: malformed method operation" }
                    Operation(kind, "", List(count) { operand(it + 1) })
                }
                "hookThis", "hookMethod", "hookResult", "hookThrowable", "hookArgument", "hookSetArgument", "hookSetResult", "hookSetThrowable" -> {
                    require(hookMode) { "routine: hook operations require inu.xposed.routine" }
                    val count = when (kind) {
                        "hookSetArgument" -> 2
                        "hookArgument", "hookSetResult", "hookSetThrowable" -> 1
                        else -> 0
                    }
                    require(node.length() == count + 1) { "routine: malformed hook operation" }
                    Operation(kind, "", List(count) { operand(it + 1) })
                }
                "getLocal", "setLocal" -> {
                    require(node.length() == if (kind == "getLocal") 2 else 3) { "routine: malformed variable access" }
                    val name = node.getString(1)
                    require(name.isNotEmpty()) { "routine: empty variable name" }
                    Operation(kind, name, if (kind == "setLocal") listOf(operand(2)) else emptyList())
                }
                "get", "set", "call" -> {
                    require(node.length() == if (kind == "get") 3 else 4) { "routine: malformed operation" }
                    val name = node.getString(2)
                    require(name.isNotEmpty()) { "routine: empty member name" }
                    val args = when (kind) {
                        "get" -> emptyList()
                        "set" -> listOf(operand(3))
                        else -> {
                            val arguments = node.getJSONArray(3)
                            require(arguments.length() <= 256) { "routine: too many arguments" }
                            List(arguments.length()) { arg -> operandOf(arguments.getJSONArray(arg), index) }
                        }
                    }
                    Operation(kind, name, listOf(operand(1)) + args)
                }
                "when" -> {
                    require(node.length() == 4) { "routine: malformed condition" }
                    Operation(kind, "", listOf(operand(1)), readIndices(node.getJSONArray(2), index), readIndices(node.getJSONArray(3), index))
                }
                "attempt" -> {
                    require(node.length() == 3) { "routine: malformed attempt" }
                    Operation(kind, "", emptyList(), readIndices(node.getJSONArray(1), index), readIndices(node.getJSONArray(2), index))
                }
                "compare" -> {
                    require(node.length() == 4) { "routine: malformed comparison" }
                    val op = node.getString(1)
                    require(op in setOf("==", "!=", "<", "<=", ">", ">=")) { "routine: unknown comparison operator" }
                    Operation(kind, op, listOf(operand(2), operand(3)))
                }
                "and", "or", "not" -> {
                    require(node.length() == if (kind == "not") 2 else 3) { "routine: malformed boolean operation" }
                    Operation(kind, "", if (kind == "not") listOf(operand(1)) else listOf(operand(1), operand(2)))
                }
                "math" -> {
                    require(node.length() == 4) { "routine: malformed math operation" }
                    val op = node.getString(1)
                    require(op in setOf("+", "-", "*", "/", "%")) { "routine: unknown math operator" }
                    Operation(kind, op, listOf(operand(2), operand(3)))
                }
                else -> error("routine: unknown operation '$kind'")
            }
        }
        roots = readIndices(graph.getJSONArray("roots"), operations.size)
        usesAttempt = operations.any { it.code == CODE_ATTEMPT }
        usesJava = operations.any { it.code == CODE_MEMBER }
    }

    fun close() {
        captured = null
    }

    override fun run() { execute(null) }

    internal fun execute(context: PluginHookContext?, methodSelf: Any? = null, methodArgs: Array<Any?>? = null): Any? {
        val values = captured ?: return null
        if (!isLive()) return null
        var methodResult: Any? = null
        val results = arrayOfNulls<Any?>(operations.size)
        var locals: HashMap<String, Any?>? = null
        val evaluated = BooleanArray(operations.size)
        // only an `attempt` can evaluate an operation that already failed; without one the first failure unwinds the run
        val failures = if (usesAttempt) arrayOfNulls<Exception>(operations.size) else null
        // the graph is acyclic and each operation runs at most once, so only a java call can outlast the budget
        val deadline = if (usesJava) System.nanoTime() + 250_000_000L else 0L
        fun evaluate(index: Int): Any? {
            if (!isLive() || captured == null) error("routine: cancelled")
            failures?.get(index)?.let { throw it }
            if (evaluated[index]) return results[index]
            val op = operations[index]
            fun readOperand(operand: Operand): Any? = if (operand.reference) evaluate(operand.index) else values[operand.index]
            val result = try {
                when (op.code) {
                    CODE_METHOD_THIS -> {
                        check(methodArgs != null) { "routine: no method invocation" }
                        validate(methodSelf)
                    }
                    CODE_METHOD_ARGUMENT -> {
                        val args = checkNotNull(methodArgs) { "routine: no method invocation" }
                        val index = readOperand(op.operands[0])
                        require(index is Number && index.toDouble().isFinite() && index.toDouble() == index.toInt().toDouble() && index.toInt() in args.indices) { "routine: invalid method argument index" }
                        validate(args[index.toInt()])
                    }
                    CODE_METHOD_SET_RESULT -> {
                        check(methodArgs != null) { "routine: no method invocation" }
                        methodResult = readOperand(op.operands[0])
                        null
                    }
                    CODE_HOOK_THIS -> validate(requireNotNull(context).getThisObject())
                    CODE_HOOK_METHOD -> validate(requireNotNull(context).getMethod())
                    CODE_HOOK_RESULT -> validate(requireNotNull(context).getReturnValue())
                    CODE_HOOK_THROWABLE -> validate(requireNotNull(context).getThrowable())
                    CODE_HOOK_ARGUMENT, CODE_HOOK_SET_ARGUMENT -> {
                        val index = readOperand(op.operands[0])
                        require(index is Number && index.toDouble().isFinite() && index.toDouble() == index.toInt().toDouble()) { "xposed: expected an integer argument index" }
                        if (op.code == CODE_HOOK_ARGUMENT) validate(requireNotNull(context).getArgument(index.toInt()))
                        else { requireNotNull(context).setArgument(index.toInt(), readOperand(op.operands[1])); null }
                    }
                    CODE_HOOK_SET_RESULT -> { requireNotNull(context).setReturnValue(readOperand(op.operands[0])); null }
                    CODE_HOOK_SET_THROWABLE -> {
                        val throwable = readOperand(op.operands[0])
                        require(throwable is Throwable) { "xposed: expected a Throwable" }
                        requireNotNull(context).setThrowable(throwable)
                        null
                    }
                    CODE_GET_LOCAL -> {
                        val held = locals
                        check(held != null && held.containsKey(op.name)) { "routine: variable '${op.name}' is not initialized" }
                        held[op.name]
                    }
                    CODE_SET_LOCAL -> readOperand(op.operands[0]).also {
                        (locals ?: HashMap<String, Any?>().also { fresh -> locals = fresh })[op.name] = it
                    }
                    CODE_ATTEMPT -> {
                        try {
                            for (child in op.yes) evaluate(child)
                        } catch (e: Exception) {
                            for (child in op.no) evaluate(child)
                        }
                        null
                    }
                    CODE_WHEN -> {
                        for (child in if (getTruthiness(readOperand(op.operands[0]))) op.yes else op.no) evaluate(child)
                        null
                    }
                    CODE_COMPARE -> compareValues(op.name, readOperand(op.operands[0]), readOperand(op.operands[1]))
                    CODE_AND -> getTruthiness(readOperand(op.operands[0])) && getTruthiness(readOperand(op.operands[1]))
                    CODE_OR -> getTruthiness(readOperand(op.operands[0])) || getTruthiness(readOperand(op.operands[1]))
                    CODE_NOT -> !getTruthiness(readOperand(op.operands[0]))
                    CODE_MATH -> calculate(op.name, readOperand(op.operands[0]), readOperand(op.operands[1]))
                    else -> {
                        check(System.nanoTime() < deadline) { "routine: execution budget exceeded" }
                        val target = readOperand(op.operands[0]) ?: error("routine: null receiver")
                        val arguments = ArrayList<Any?>(op.operands.size - 1)
                        for (at in 1 until op.operands.size) arguments.add(readOperand(op.operands[at]))
                        invoke(op.kind, target, op.name, arguments)
                    }
                }
            } catch (e: Exception) {
                failures?.set(index, e)
                throw e
            }
            evaluated[index] = true
            results[index] = result
            return result
        }
        try {
            for (root in roots) evaluate(root)
        } catch (e: Exception) {
            if (methodArgs != null) throw e
            Log.d("InuPluginRoutine", "routine failed", e)
        }
        return methodResult
    }

    private fun getTruthiness(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Number -> value.toDouble() != 0.0 && !value.toDouble().isNaN()
        is String -> value.isNotEmpty()
        else -> true
    }

    private fun isPrimitiveNumber(value: Any?): Boolean =
        value is Byte || value is Short || value is Int || value is Long || value is Float || value is Double

    private fun compareValues(op: String, left: Any?, right: Any?): Boolean {
        val order: Int? = when {
            left is Number && right is Number && isPrimitiveNumber(left) && isPrimitiveNumber(right) -> {
                val a = left.toDouble()
                val b = right.toDouble()
                if (a.isNaN() || b.isNaN()) return op == "!="
                if (!a.isFinite() || !b.isFinite()) {
                    a.compareTo(b)
                } else {
                    val leftIntegral = left !is Float && left !is Double
                    val rightIntegral = right !is Float && right !is Double
                    when {
                        leftIntegral && rightIntegral -> left.toLong().compareTo(right.toLong())
                        !leftIntegral && !rightIntegral -> a.compareTo(b)
                        else -> {
                            val exactLeft = if (leftIntegral) java.math.BigDecimal.valueOf(left.toLong()) else java.math.BigDecimal(a)
                            val exactRight = if (rightIntegral) java.math.BigDecimal.valueOf(right.toLong()) else java.math.BigDecimal(b)
                            exactLeft.compareTo(exactRight)
                        }
                    }
                }
            }
            left is String && right is String -> left.compareTo(right)
            else -> null
        }
        if (op == "==" || op == "!=") {
            val equal = if (order != null) order == 0 else if (left is Boolean && right is Boolean) left == right else left === right
            return if (op == "==") equal else !equal
        }
        require(order != null) { "routine: ordering requires two numbers or two strings" }
        return when (op) {
            "<" -> order < 0
            "<=" -> order <= 0
            ">" -> order > 0
            else -> order >= 0
        }
    }

    private fun calculate(op: String, left: Any?, right: Any?): Number {
        require(left is Number && right is Number) { "routine: math operands must be numbers" }
        if (left is Float || left is Double || right is Float || right is Double) {
            val a = left.toDouble()
            val b = right.toDouble()
            require(a.isFinite() && b.isFinite()) { "routine: non-finite operand" }
            require(op != "/" && op != "%" || b != 0.0) { "routine: division by zero" }
            val result = when (op) {
                "+" -> a + b
                "-" -> a - b
                "*" -> a * b
                "/" -> a / b
                else -> a % b
            }
            require(result.isFinite()) { "routine: non-finite result" }
            return result
        }
        val a = left.toLong()
        val b = right.toLong()
        return when (op) {
            "+" -> Math.addExact(a, b)
            "-" -> Math.subtractExact(a, b)
            "*" -> Math.multiplyExact(a, b)
            "/" -> {
                require(b != 0L) { "routine: division by zero" }
                require(a != Long.MIN_VALUE || b != -1L) { "routine: integer overflow" }
                a / b
            }
            else -> {
                require(b != 0L) { "routine: division by zero" }
                a % b
            }
        }
    }

    private companion object {
        const val CODE_MEMBER = 0
        const val CODE_METHOD_THIS = 1
        const val CODE_METHOD_ARGUMENT = 2
        const val CODE_METHOD_SET_RESULT = 3
        const val CODE_HOOK_THIS = 4
        const val CODE_HOOK_METHOD = 5
        const val CODE_HOOK_RESULT = 6
        const val CODE_HOOK_THROWABLE = 7
        const val CODE_HOOK_ARGUMENT = 8
        const val CODE_HOOK_SET_ARGUMENT = 9
        const val CODE_HOOK_SET_RESULT = 10
        const val CODE_HOOK_SET_THROWABLE = 11
        const val CODE_GET_LOCAL = 12
        const val CODE_SET_LOCAL = 13
        const val CODE_ATTEMPT = 14
        const val CODE_WHEN = 15
        const val CODE_COMPARE = 16
        const val CODE_AND = 17
        const val CODE_OR = 18
        const val CODE_NOT = 19
        const val CODE_MATH = 20

        /** `get`, `set` and `call` keep [Operation.kind] instead: it is what reaches the bridge */
        val CODES = mapOf(
            "methodThis" to CODE_METHOD_THIS,
            "methodArgument" to CODE_METHOD_ARGUMENT,
            "methodSetResult" to CODE_METHOD_SET_RESULT,
            "hookThis" to CODE_HOOK_THIS,
            "hookMethod" to CODE_HOOK_METHOD,
            "hookResult" to CODE_HOOK_RESULT,
            "hookThrowable" to CODE_HOOK_THROWABLE,
            "hookArgument" to CODE_HOOK_ARGUMENT,
            "hookSetArgument" to CODE_HOOK_SET_ARGUMENT,
            "hookSetResult" to CODE_HOOK_SET_RESULT,
            "hookSetThrowable" to CODE_HOOK_SET_THROWABLE,
            "getLocal" to CODE_GET_LOCAL,
            "setLocal" to CODE_SET_LOCAL,
            "attempt" to CODE_ATTEMPT,
            "when" to CODE_WHEN,
            "compare" to CODE_COMPARE,
            "and" to CODE_AND,
            "or" to CODE_OR,
            "not" to CODE_NOT,
            "math" to CODE_MATH,
        )
    }

    private fun readIndices(array: JSONArray, limit: Int): List<Int> {
        require(array.length() <= 256) { "routine: too many roots" }
        return List(array.length()) { readIndex(array, it, limit) }
    }

    private fun readIndex(array: JSONArray, index: Int, limit: Int): Int {
        val value = array.get(index)
        require(value is Int && value >= 0 && value < limit) { "routine: invalid reference" }
        return value
    }
}
