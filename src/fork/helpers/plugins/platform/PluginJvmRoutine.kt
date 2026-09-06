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
    )

    @Volatile private var captured: List<Any?>? = values
    private val operations: List<Operation>
    private val roots: List<Int>

    init {
        require(definition.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "routine: definition exceeds 1 MB" }
        val graph = JSONObject(definition)
        val nodes = graph.getJSONArray("nodes")
        require(nodes.length() <= 256) { "routine: at most 256 operations" }
        operations = List(nodes.length()) { index ->
            val node = nodes.getJSONArray(index)
            val kind = node.getString(0)
            fun operand(at: Int): Operand {
                val value = node.getJSONArray(at)
                require(value.length() == 2) { "routine: malformed operand" }
                val reference = readIndex(value, 0, 2) == 1
                return Operand(reference, readIndex(value, 1, if (reference) index else values.size))
            }
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
                            List(arguments.length()) { arg ->
                                val value = arguments.getJSONArray(arg)
                                require(value.length() == 2) { "routine: malformed operand" }
                                val reference = readIndex(value, 0, 2) == 1
                                Operand(reference, readIndex(value, 1, if (reference) index else values.size))
                            }
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
        val locals = HashMap<String, Any?>()
        val evaluated = BooleanArray(operations.size)
        val failures = arrayOfNulls<Exception>(operations.size)
        val deadline = System.nanoTime() + 250_000_000L
        fun evaluate(index: Int): Any? {
            if (!isLive() || captured == null) error("routine: cancelled")
            check(System.nanoTime() < deadline) { "routine: execution budget exceeded" }
            failures[index]?.let { throw it }
            if (evaluated[index]) return results[index]
            val op = operations[index]
            fun value(operand: Operand): Any? = if (operand.reference) evaluate(operand.index) else values[operand.index]
            val result = try {
                when (op.kind) {
                    "methodThis" -> {
                        check(methodArgs != null) { "routine: no method invocation" }
                        validate(methodSelf)
                    }
                    "methodArgument" -> {
                        val args = checkNotNull(methodArgs) { "routine: no method invocation" }
                        val index = value(op.operands[0])
                        require(index is Number && index.toDouble().isFinite() && index.toDouble() == index.toInt().toDouble() && index.toInt() in args.indices) { "routine: invalid method argument index" }
                        validate(args[index.toInt()])
                    }
                    "methodSetResult" -> {
                        check(methodArgs != null) { "routine: no method invocation" }
                        methodResult = value(op.operands[0])
                        null
                    }
                    "hookThis" -> validate(requireNotNull(context).getThisObject())
                    "hookMethod" -> validate(requireNotNull(context).getMethod())
                    "hookResult" -> validate(requireNotNull(context).getReturnValue())
                    "hookThrowable" -> validate(requireNotNull(context).getThrowable())
                    "hookArgument", "hookSetArgument" -> {
                        val index = value(op.operands[0])
                        require(index is Number && index.toDouble().isFinite() && index.toDouble() == index.toInt().toDouble()) { "xposed: expected an integer argument index" }
                        if (op.kind == "hookArgument") validate(requireNotNull(context).getArgument(index.toInt()))
                        else { requireNotNull(context).setArgument(index.toInt(), value(op.operands[1])); null }
                    }
                    "hookSetResult" -> { requireNotNull(context).setReturnValue(value(op.operands[0])); null }
                    "hookSetThrowable" -> {
                        val throwable = value(op.operands[0])
                        require(throwable is Throwable) { "xposed: expected a Throwable" }
                        requireNotNull(context).setThrowable(throwable)
                        null
                    }
                    "getLocal" -> {
                        check(locals.containsKey(op.name)) { "routine: variable '${op.name}' is not initialized" }
                        locals[op.name]
                    }
                    "setLocal" -> value(op.operands[0]).also { locals[op.name] = it }
                    "attempt" -> {
                        try {
                            for (child in op.yes) evaluate(child)
                        } catch (e: Exception) {
                            for (child in op.no) evaluate(child)
                        }
                        null
                    }
                    "when" -> {
                        for (child in if (getTruthiness(value(op.operands[0]))) op.yes else op.no) evaluate(child)
                        null
                    }
                    "compare" -> compareValues(op.name, value(op.operands[0]), value(op.operands[1]))
                    "and" -> getTruthiness(value(op.operands[0])) && getTruthiness(value(op.operands[1]))
                    "or" -> getTruthiness(value(op.operands[0])) || getTruthiness(value(op.operands[1]))
                    "not" -> !getTruthiness(value(op.operands[0]))
                    "math" -> calculate(op.name, value(op.operands[0]), value(op.operands[1]))
                    else -> {
                        val target = value(op.operands[0]) ?: error("routine: null receiver")
                        invoke(op.kind, target, op.name, op.operands.drop(1).map(::value))
                    }
                }
            } catch (e: Exception) {
                failures[index] = e
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
                    val exactLeft = if (left is Float || left is Double) java.math.BigDecimal(a) else java.math.BigDecimal.valueOf(left.toLong())
                    val exactRight = if (right is Float || right is Double) java.math.BigDecimal(b) else java.math.BigDecimal.valueOf(right.toLong())
                    exactLeft.compareTo(exactRight)
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
