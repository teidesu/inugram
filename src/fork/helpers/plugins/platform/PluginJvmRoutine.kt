package desu.inugram.helpers.plugins.platform

import android.util.Log
import org.json.JSONArray
import desu.inugram.core.plugins.PluginWire.refuse
import org.json.JSONObject

/**
 * Runs the bytecode `@inugram/cli` compiles a routine body into. Instruction `i` writes register
 * `i`, operands name registers below `i`, and every jump but a loop's goes forward, so a program
 * without a loop runs each instruction at most once.
 */
internal class PluginJvmRoutine(
    definition: String,
    values: List<Any?>,
    private val host: PluginJvm.Session,
    private val hookMode: Boolean,
) : Runnable {
    /** a budget or cancellation failure, which the routine's own `try` may not catch */
    private class Abort(message: String) : RuntimeException(message)

    /**
     * The instruction vocabulary, hand-kept in step with `sdk/cli/src/routines/ops.ts`. Dispatch is
     * exhaustive over it, so an op added here without a branch to run it does not compile.
     */
    private enum class Op(
        val wire: String,
        /** how many operands the shared branch reads; an op with a branch of its own reads its own fields */
        val operands: Int = 0,
        /** refused outside `inu.xposed.routine`, where there is no call to read */
        val hookOnly: Boolean = false,
        /** refused in `inu.xposed.routine`, which is never a defineClass body */
        val methodOnly: Boolean = false,
        /** crosses the bridge, so a run checks its budget and its liveness before it */
        val java: Boolean = false,
    ) {
        THIS("this"),
        OWNER("owner", methodOnly = true),
        ARG("arg", operands = 1),
        CAPTURE("capture"),

        ARG_COUNT("argCount", hookOnly = true),
        SET_ARG("setArg", operands = 2, hookOnly = true),
        METHOD("method", hookOnly = true),
        RESULT("result", hookOnly = true),
        THROWABLE("throwable", hookOnly = true),
        SET_RESULT("setResult", operands = 1, hookOnly = true),
        SET_THROWABLE("setThrowable", operands = 1, hookOnly = true),

        GET_SLOT("getSlot"),
        SET_SLOT("setSlot"),

        JUMP("jump"),
        JUMP_IF_FALSY("jumpIfFalsy"),
        JUMP_IF_TRUTHY("jumpIfTruthy"),
        JUMP_IF_NULL("jumpIfNull"),
        JUMP_IF_NOT_NULL("jumpIfNotNull"),
        LOOP("loop"),
        RETURN("return"),
        THROW("throw", operands = 1),
        CATCH("catch"),

        GET("get", operands = 2, java = true),
        SET("set", java = true),
        CALL("call", java = true),
        CALL_SUPER("callSuper", java = true),
        NEW("new", java = true),
        ARRAY("array"),

        ITERATE("iterate", operands = 1, java = true),
        ADVANCE("advance"),

        EQ("eq", operands = 2),
        NE("ne", operands = 2),
        LT("lt", operands = 2),
        LE("le", operands = 2),
        GT("gt", operands = 2),
        GE("ge", operands = 2),
        INSTANCE_OF("instanceOf", operands = 2),

        ADD("add", operands = 2),
        SUB("sub", operands = 2),
        MUL("mul", operands = 2),
        DIV("div", operands = 2),
        REM("rem", operands = 2),
        NEG("neg", operands = 1),

        BIT_AND("bitAnd", operands = 2),
        BIT_OR("bitOr", operands = 2),
        BIT_XOR("bitXor", operands = 2),
        BIT_NOT("bitNot", operands = 1),
        SHL("shl", operands = 2),
        SHR("shr", operands = 2),
        USHR("ushr", operands = 2),

        NOT("not", operands = 1),
        ;

        companion object {
            private val BY_WIRE = entries.associateBy { it.wire }

            fun of(wire: String): Op? = BY_WIRE[wire]
        }
    }

    @Volatile private var captured: List<Any?>? = null

    private val opcodes: Array<Op>
    private val fieldA: IntArray
    private val fieldB: IntArray
    private val fieldC: IntArray
    private val argLists: Array<IntArray?>
    private val constants: Array<Any?>
    private val tries: IntArray
    private val slotCount: Int

    /** what a run has to set up for, decided once: a predicate over arguments needs no clock */
    private val usesClock: Boolean

    init {
        require(definition.toByteArray(Charsets.UTF_8).size <= VALUE_LIMIT) { "routine: definition exceeds 1 MB" }
        val graph = JSONObject(definition)
        require(graph.optInt("v", 0) == 1) { "routine: unsupported bytecode version" }

        slotCount = graph.optInt("slots", 0)
        require(slotCount in 0..MAX_SLOTS) { "routine: at most $MAX_SLOTS slots" }

        val layout = graph.optJSONArray("layout")
        val captureCount = layout?.length() ?: values.size
        require(captureCount <= MAX_CAPTURES) { "routine: at most $MAX_CAPTURES captures" }

        val code = graph.getJSONArray("code")
        val count = code.length()
        // a body that does nothing compiles to nothing, and running nothing is what it means
        require(count <= MAX_INSTRUCTIONS) { "routine: at most $MAX_INSTRUCTIONS instructions" }

        opcodes = Array(count) { at ->
            val name = code.getJSONArray(at).getString(0)
            val op = Op.of(name) ?: error("routine: unknown instruction '$name'")
            require(hookMode || !op.hookOnly) { "routine: '$name' needs inu.xposed.routine" }
            require(!hookMode || !op.methodOnly) { "routine: '$name' is not available in inu.xposed.routine" }
            op
        }
        fieldA = IntArray(count)
        fieldB = IntArray(count)
        fieldC = IntArray(count)
        argLists = arrayOfNulls(count)
        val pool = ArrayList<Any?>()
        val cursors = BooleanArray(count)
        var clock = false

        for (at in 0 until count) {
            val node = code.getJSONArray(at)
            val op = opcodes[at]
            if (op.java || op == Op.LOOP) clock = true

            fun operand(field: Int): Int = readOperand(node, field, at, pool).also {
                // an iterator is the one value a run holds that never crossed the bridge's checks
                require(it < 0 || !cursors[it]) { "routine: an iterator reaches nothing but its advance" }
            }
            fun target(field: Int): Int {
                val to = node.getInt(field)
                if (op == Op.LOOP) require(to in 0..at) { "routine: a loop target must go backwards" }
                else require(to > at && to <= count) { "routine: a jump target must go forwards" }
                return to
            }
            fun immediate(field: Int, limit: Int): Int =
                node.getInt(field).also { require(it in 0 until limit) { "routine: index out of range" } }

            when (op) {
                Op.THIS, Op.OWNER, Op.ARG_COUNT, Op.METHOD, Op.RESULT, Op.THROWABLE, Op.CATCH ->
                    require(node.length() == 1) { "routine: malformed '${op.wire}'" }
                Op.CAPTURE -> {
                    require(node.length() == 2) { "routine: malformed '${op.wire}'" }
                    fieldA[at] = immediate(1, captureCount)
                }
                Op.GET_SLOT -> {
                    require(node.length() == 2) { "routine: malformed '${op.wire}'" }
                    fieldA[at] = immediate(1, slotCount)
                }
                Op.SET_SLOT -> {
                    require(node.length() == 3) { "routine: malformed '${op.wire}'" }
                    fieldA[at] = immediate(1, slotCount)
                    fieldB[at] = operand(2)
                }
                Op.JUMP, Op.LOOP -> {
                    require(node.length() == 2) { "routine: malformed '${op.wire}'" }
                    fieldA[at] = target(1)
                }
                Op.ADVANCE -> {
                    require(node.length() == 3) { "routine: malformed '${op.wire}'" }
                    fieldA[at] = readOperand(node, 1, at, pool)
                    require(fieldA[at] >= 0 && cursors[fieldA[at]]) { "routine: advance expects an iterator" }
                    fieldB[at] = target(2)
                }
                Op.JUMP_IF_FALSY, Op.JUMP_IF_TRUTHY, Op.JUMP_IF_NULL, Op.JUMP_IF_NOT_NULL -> {
                    require(node.length() == 3) { "routine: malformed '${op.wire}'" }
                    fieldA[at] = operand(1)
                    fieldB[at] = target(2)
                }
                Op.RETURN -> {
                    require(node.length() <= 2) { "routine: malformed '${op.wire}'" }
                    fieldA[at] = if (node.length() == 1) NO_OPERAND else operand(1)
                    require(!hookMode || fieldA[at] == NO_OPERAND) {
                        "routine: a hook routine answers through setReturnValue"
                    }
                }
                Op.SET -> {
                    require(node.length() == 4) { "routine: malformed '${op.wire}'" }
                    fieldA[at] = operand(1)
                    fieldB[at] = operand(2)
                    fieldC[at] = operand(3)
                }
                Op.CALL -> {
                    require(node.length() == 4) { "routine: malformed '${op.wire}'" }
                    fieldA[at] = operand(1)
                    fieldB[at] = operand(2)
                    argLists[at] = readArguments(node, 3, at, pool, cursors)
                }
                Op.CALL_SUPER -> {
                    require(node.length() == 5) { "routine: malformed '${op.wire}'" }
                    fieldA[at] = operand(1)
                    fieldB[at] = operand(2)
                    fieldC[at] = operand(3)
                    argLists[at] = readArguments(node, 4, at, pool, cursors)
                }
                Op.NEW -> {
                    require(node.length() == 3) { "routine: malformed '${op.wire}'" }
                    fieldA[at] = operand(1)
                    argLists[at] = readArguments(node, 2, at, pool, cursors)
                }
                Op.ARRAY -> {
                    require(node.length() == 2) { "routine: malformed '${op.wire}'" }
                    argLists[at] = readArguments(node, 1, at, pool, cursors)
                }
                else -> {
                    val arity = op.operands
                    require(node.length() == arity + 1) { "routine: malformed '${op.wire}'" }
                    if (arity > 0) fieldA[at] = operand(1)
                    if (arity > 1) fieldB[at] = operand(2)
                    if (op == Op.ITERATE) cursors[at] = true
                }
            }
        }

        constants = pool.toTypedArray()
        usesClock = clock
        tries = readTries(graph.optJSONArray("tries"), count)
        captured = rebuildCaptures(layout, values)
    }

    private fun readOperand(node: JSONArray, field: Int, at: Int, pool: ArrayList<Any?>): Int {
        val value = node.get(field)
        if (value is JSONArray) {
            pool.add(literalOf(value))
            return -pool.size
        }
        require(value is Int && value >= 0 && value < at) { "routine: an operand reads a later register" }
        return value
    }

    private fun literalOf(value: JSONArray): Any? {
        if (value.length() == 2) {
            require(value.getString(0) == "L") { "routine: unknown literal tag" }
            return value.getString(1).toLongOrNull() ?: error("routine: malformed long literal")
        }
        require(value.length() == 1) { "routine: malformed literal" }
        return when (val scalar = value.get(0)) {
            JSONObject.NULL -> null
            is Int, is Long, is Double, is String, is Boolean -> scalar
            else -> error("routine: a literal must be a scalar")
        }
    }

    private fun readArguments(
        node: JSONArray,
        field: Int,
        at: Int,
        pool: ArrayList<Any?>,
        cursors: BooleanArray,
    ): IntArray {
        val list = node.getJSONArray(field)
        require(list.length() <= MAX_CALL_ARGS) { "routine: at most $MAX_CALL_ARGS arguments" }
        return IntArray(list.length()) {
            readOperand(list, it, at, pool).also { operand ->
                require(operand < 0 || !cursors[operand]) { "routine: an iterator reaches nothing but its advance" }
            }
        }
    }

    private fun readTries(regions: JSONArray?, count: Int): IntArray {
        if (regions == null) return EMPTY_INTS
        require(regions.length() <= MAX_TRIES) { "routine: at most $MAX_TRIES try regions" }
        val table = IntArray(regions.length() * 3)
        for (at in 0 until regions.length()) {
            val region = regions.getJSONArray(at)
            require(region.length() == 3) { "routine: malformed try region" }
            val start = region.getInt(0)
            val end = region.getInt(1)
            val handler = region.getInt(2)
            require(start in 0 until end && end <= count) { "routine: malformed try region" }
            require(handler in end until count) { "routine: a handler runs inside its own region" }
            require(opcodes[handler] == Op.CATCH) { "routine: a handler must be a catch" }
            table[at * 3] = start
            table[at * 3 + 1] = end
            table[at * 3 + 2] = handler
        }
        for (left in table.indices step 3) {
            for (right in 0 until left step 3) {
                val disjoint = table[left + 1] <= table[right] || table[right + 1] <= table[left]
                val nested = (table[left] >= table[right] && table[left + 1] <= table[right + 1]) ||
                    (table[right] >= table[left] && table[right + 1] <= table[left + 1])
                require(disjoint || nested) { "routine: try regions overlap without nesting" }
            }
        }
        return table
    }

    /**
     * An array capture crosses flattened into the value list, because captures cross one wire each.
     * [layout] says how to put them back: `-1` for a plain value, or the nested shape of an array.
     */
    private fun rebuildCaptures(layout: JSONArray?, values: List<Any?>): List<Any?> {
        if (layout == null) return values
        val cursor = intArrayOf(0)
        val rebuilt = List(layout.length()) { take(layout.get(it), values, cursor) }
        require(cursor[0] == values.size) { "routine: capture layout does not match the values" }
        return rebuilt
    }

    private fun take(shape: Any?, values: List<Any?>, cursor: IntArray): Any? {
        if (shape is JSONArray) return Array<Any?>(shape.length()) { take(shape.get(it), values, cursor) }
        require(shape == -1) { "routine: malformed capture layout" }
        require(cursor[0] < values.size) { "routine: capture layout does not match the values" }
        return values[cursor[0]++]
    }

    fun close() {
        captured = null
    }

    override fun run() { execute(null) }

    /**
     * Converts the filter's return value to a boolean. Runs the hook if the filter fails,
     * so an error cannot silently disable it.
     */
    fun decide(receiver: Any?, args: Array<Any?>): Boolean = try {
        getTruthiness(execute(null, receiver, args))
    } catch (e: Throwable) {
        Log.d(TAG, "routine filter failed", e)
        true
    }

    internal fun execute(
        context: PluginHookContext?,
        methodSelf: Any? = null,
        methodArgs: Array<Any?>? = null,
        owner: Class<*>? = null,
    ): Any? {
        val captures = captured ?: return null
        if (!host.live) return null
        val count = opcodes.size
        val registers = arrayOfNulls<Any?>(count)
        val slots = if (slotCount == 0) EMPTY_VALUES else arrayOfNulls<Any?>(slotCount)
        val deadline = if (usesClock) System.nanoTime() + BUDGET_NANOS else 0L
        var backEdges = 0
        var methodResult: Any? = null
        var pc = 0

        while (true) {
            try {
                while (pc < count) {
                    val op = opcodes[pc]
                    var next = pc + 1
                    if (op.java) {
                        if (!host.live || captured == null) throw Abort("routine: cancelled")
                        if (System.nanoTime() >= deadline) throw Abort("routine: execution budget exceeded")
                    }
                    val value: Any? = when (op) {
                        Op.THIS -> if (hookMode) host.checkedOperand(requireNotNull(context).getThisObject()) else host.checkedOperand(methodSelf)
                        Op.OWNER -> owner ?: throw IllegalStateException("routine: inu.jvm.superOf needs the routine to be a defineClass body")
                        Op.ARG -> {
                            val index = indexOf(read(fieldA[pc], registers))
                            if (hookMode) {
                                val hook = requireNotNull(context)
                                if (index in hook.arguments.indices) host.checkedOperand(hook.getArgument(index)) else null
                            } else {
                                val args = methodArgs
                                if (args != null && index in args.indices) host.checkedOperand(args[index]) else null
                            }
                        }
                        Op.CAPTURE -> captures[fieldA[pc]]
                        Op.ARG_COUNT -> requireNotNull(context).arguments.size
                        Op.SET_ARG -> {
                            val index = indexOf(read(fieldA[pc], registers))
                            requireNotNull(context).setArgument(index, read(fieldB[pc], registers))
                            null
                        }
                        Op.METHOD -> host.checkedOperand(requireNotNull(context).getMethod())
                        Op.RESULT -> host.checkedOperand(requireNotNull(context).getReturnValue())
                        Op.THROWABLE -> host.checkedOperand(requireNotNull(context).getThrowable())
                        Op.SET_RESULT -> {
                            requireNotNull(context).setReturnValue(read(fieldA[pc], registers))
                            null
                        }
                        Op.SET_THROWABLE -> {
                            val throwable = read(fieldA[pc], registers)
                            require(throwable is Throwable) { "routine: expected a Throwable" }
                            requireNotNull(context).setThrowable(throwable)
                            null
                        }
                        Op.GET_SLOT -> slots[fieldA[pc]]
                        Op.SET_SLOT -> read(fieldB[pc], registers).also { slots[fieldA[pc]] = it }
                        Op.JUMP -> { next = fieldA[pc]; null }
                        Op.LOOP -> {
                            if (!host.live || captured == null) throw Abort("routine: cancelled")
                            if (++backEdges and (CLOCK_EVERY - 1) == 0 && System.nanoTime() >= deadline) {
                                throw Abort("routine: execution budget exceeded")
                            }
                            next = fieldA[pc]
                            null
                        }
                        Op.JUMP_IF_FALSY -> {
                            if (!getTruthiness(read(fieldA[pc], registers))) next = fieldB[pc]
                            null
                        }
                        Op.JUMP_IF_TRUTHY -> {
                            if (getTruthiness(read(fieldA[pc], registers))) next = fieldB[pc]
                            null
                        }
                        Op.JUMP_IF_NULL -> {
                            if (read(fieldA[pc], registers) == null) next = fieldB[pc]
                            null
                        }
                        Op.JUMP_IF_NOT_NULL -> {
                            if (read(fieldA[pc], registers) != null) next = fieldB[pc]
                            null
                        }
                        Op.RETURN -> {
                            if (fieldA[pc] != NO_OPERAND) methodResult = read(fieldA[pc], registers)
                            return methodResult
                        }
                        Op.THROW -> {
                            val thrown = read(fieldA[pc], registers)
                            require(thrown is Throwable) { "routine: expected a Throwable" }
                            throw thrown
                        }
                        Op.CATCH -> null
                        Op.GET -> readMember(read(fieldA[pc], registers), read(fieldB[pc], registers))
                        Op.SET -> writeMember(
                            read(fieldA[pc], registers),
                            read(fieldB[pc], registers),
                            read(fieldC[pc], registers),
                        )
                        Op.CALL -> host.callMember(
                            targetOf(read(fieldA[pc], registers)),
                            nameOf(read(fieldB[pc], registers)),
                            readAll(argLists[pc]!!, registers),
                        )
                        Op.CALL_SUPER -> host.callSuper(
                            targetOf(read(fieldA[pc], registers)),
                            read(fieldB[pc], registers),
                            nameOf(read(fieldC[pc], registers)),
                            readAll(argLists[pc]!!, registers),
                        )
                        Op.NEW -> host.newInstanceOf(
                            targetOf(read(fieldA[pc], registers)),
                            readAll(argLists[pc]!!, registers),
                        )
                        Op.ARRAY -> {
                            val items = argLists[pc]!!
                            Array<Any?>(items.size) { read(items[it], registers) }
                        }
                        Op.ITERATE -> host.iterate(targetOf(read(fieldA[pc], registers)))
                        Op.ADVANCE -> {
                            val cursor = read(fieldA[pc], registers)
                            require(cursor is Iterator<*>) { "routine: expected an iterator" }
                            if (cursor.hasNext()) host.checkedOperand(cursor.next()) else { next = fieldB[pc]; null }
                        }
                        Op.EQ, Op.NE, Op.LT, Op.LE, Op.GT, Op.GE ->
                            compareValues(op, read(fieldA[pc], registers), read(fieldB[pc], registers))
                        Op.INSTANCE_OF -> {
                            val cls = read(fieldB[pc], registers)
                            require(cls is Class<*>) { "routine: instanceof expects a java class" }
                            cls.isInstance(read(fieldA[pc], registers))
                        }
                        Op.ADD -> add(read(fieldA[pc], registers), read(fieldB[pc], registers))
                        Op.SUB, Op.MUL, Op.DIV, Op.REM ->
                            calculate(op, read(fieldA[pc], registers), read(fieldB[pc], registers))
                        Op.NEG -> calculate(Op.SUB, 0, read(fieldA[pc], registers))
                        Op.BIT_AND, Op.BIT_OR, Op.BIT_XOR, Op.SHL, Op.SHR, Op.USHR ->
                            bitwise(op, read(fieldA[pc], registers), read(fieldB[pc], registers))
                        Op.BIT_NOT -> {
                            val operand = read(fieldA[pc], registers)
                            if (operand is Long) operand.inv() else intOf(operand).inv()
                        }
                        Op.NOT -> !getTruthiness(read(fieldA[pc], registers))
                    }
                    registers[pc] = value
                    pc = next
                }
                break
            } catch (e: Throwable) {
                val handler = if (e is Abort) -1 else handlerFor(pc)
                if (handler < 0) {
                    // a defineClass body answers its java caller; a runnable or a hook phase has
                    // nobody to answer, and what it already changed stays changed
                    if (methodArgs != null || e !is Exception) throw e
                    Log.d(TAG, "routine failed", e)
                    return methodResult
                }
                registers[handler] = e
                pc = handler + 1
            }
        }
        return methodResult
    }

    private fun read(operand: Int, registers: Array<Any?>): Any? =
        if (operand >= 0) registers[operand] else constants[-operand - 1]

    private fun readAll(operands: IntArray, registers: Array<Any?>): List<Any?> {
        if (operands.isEmpty()) return EMPTY_ARGS
        val values = ArrayList<Any?>(operands.size)
        for (operand in operands) values.add(read(operand, registers))
        return values
    }

    /** regions nest, so the innermost covering one is the narrowest: latest start, earliest end */
    private fun handlerFor(pc: Int): Int {
        var best = -1
        var bestStart = -1
        var bestEnd = Int.MAX_VALUE
        var at = 0
        while (at < tries.size) {
            val start = tries[at]
            val end = tries[at + 1]
            if (pc >= start && pc < end && (start > bestStart || (start == bestStart && end < bestEnd))) {
                bestStart = start
                bestEnd = end
                best = tries[at + 2]
            }
            at += 3
        }
        return best
    }

    private fun targetOf(value: Any?): Any = value ?: error("routine: null receiver")

    private fun nameOf(value: Any?): String =
        value as? String ?: refuse("invalid-argument", "routine: a member name must be text")

    private fun readMember(target: Any?, key: Any?): Any? {
        val receiver = targetOf(target)
        if (key is String) {
            if (key == "length" && receiver.javaClass.isArray) return host.getArrayLength(receiver)
            return host.getMember(receiver, key)
        }
        return host.getElement(receiver, indexOf(key))
    }

    private fun writeMember(target: Any?, key: Any?, value: Any?): Any? {
        val receiver = targetOf(target)
        if (key is String) host.setMember(receiver, key, value)
        else host.setElement(receiver, indexOf(key), value)
        return value
    }

    private fun indexOf(value: Any?): Int {
        if (value is Int) return value
        require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble()) {
            "routine: expected an integer index"
        }
        return value.toInt()
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

    private fun isIntegral(value: Any?): Boolean =
        value is Byte || value is Short || value is Int || value is Long

    private fun intOf(value: Any?): Int {
        require(isIntegral(value)) { "routine: bitwise operands must be integers" }
        return (value as Number).toInt()
    }

    private fun compareValues(op: Op, left: Any?, right: Any?): Boolean {
        // two ints are what a comparison almost always has, and the general path spends thirty type
        // checks to reach the same answer this does
        if (left is Int && right is Int) return ordered(op, left.compareTo(right))
        val order: Int? = when {
            left is Number && right is Number && isPrimitiveNumber(left) && isPrimitiveNumber(right) -> {
                val a = left.toDouble()
                val b = right.toDouble()
                if (a.isNaN() || b.isNaN()) return op == Op.NE
                if (!a.isFinite() || !b.isFinite()) {
                    a.compareTo(b)
                } else {
                    val leftIntegral = isIntegral(left)
                    val rightIntegral = isIntegral(right)
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
        if (op == Op.EQ || op == Op.NE) {
            val equal = if (order != null) order == 0 else if (left is Boolean && right is Boolean) left == right else left === right
            return if (op == Op.EQ) equal else !equal
        }
        require(order != null) { "routine: ordering requires two numbers or two strings" }
        return ordered(op, order)
    }

    private fun ordered(op: Op, order: Int): Boolean = when (op) {
        Op.EQ -> order == 0
        Op.NE -> order != 0
        Op.LT -> order < 0
        Op.LE -> order <= 0
        Op.GT -> order > 0
        else -> order >= 0
    }

    /** js `+`, which concatenates as soon as either side is text */
    private fun add(left: Any?, right: Any?): Any? {
        if (left is String || right is String) return textOf(left) + textOf(right)
        return calculate(Op.ADD, left, right)
    }

    private fun textOf(value: Any?): String = if (value == null) "null" else value.toString()

    private fun calculate(op: Op, left: Any?, right: Any?): Number {
        if (left is Int && right is Int) {
            val a = left.toLong()
            val b = right.toLong()
            // two ints widened to 64 bits cannot overflow, so the exact forms have nothing to catch
            return when (op) {
                Op.ADD -> a + b
                Op.SUB -> a - b
                Op.MUL -> a * b
                Op.DIV -> {
                    require(b != 0L) { "routine: division by zero" }
                    a / b
                }
                else -> {
                    require(b != 0L) { "routine: division by zero" }
                    a % b
                }
            }
        }
        require(left is Number && right is Number) { "routine: math operands must be numbers" }
        if (!isIntegral(left) || !isIntegral(right)) {
            val a = left.toDouble()
            val b = right.toDouble()
            require(a.isFinite() && b.isFinite()) { "routine: non-finite operand" }
            require(op != Op.DIV && op != Op.REM || b != 0.0) { "routine: division by zero" }
            val result = when (op) {
                Op.ADD -> a + b
                Op.SUB -> a - b
                Op.MUL -> a * b
                Op.DIV -> a / b
                else -> a % b
            }
            require(result.isFinite()) { "routine: non-finite result" }
            return result
        }
        val a = left.toLong()
        val b = right.toLong()
        return when (op) {
            Op.ADD -> Math.addExact(a, b)
            Op.SUB -> Math.subtractExact(a, b)
            Op.MUL -> Math.multiplyExact(a, b)
            Op.DIV -> {
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

    /** java promotion: a pair of ints stays 32-bit, anything wider goes to 64 */
    private fun bitwise(op: Op, left: Any?, right: Any?): Number {
        if (left is Long || right is Long) {
            require(isIntegral(left) && isIntegral(right)) { "routine: bitwise operands must be integers" }
            val a = (left as Number).toLong()
            val b = (right as Number).toLong()
            return when (op) {
                Op.BIT_AND -> a and b
                Op.BIT_OR -> a or b
                Op.BIT_XOR -> a xor b
                Op.SHL -> a shl b.toInt()
                Op.SHR -> a shr b.toInt()
                else -> a ushr b.toInt()
            }
        }
        val a = intOf(left)
        val b = intOf(right)
        return when (op) {
            Op.BIT_AND -> a and b
            Op.BIT_OR -> a or b
            Op.BIT_XOR -> a xor b
            Op.SHL -> a shl b
            Op.SHR -> a shr b
            else -> a ushr b
        }
    }

    companion object {
        private const val TAG = "InuPluginRoutine"
        private const val VALUE_LIMIT = 1024 * 1024
        private const val BUDGET_NANOS = 250_000_000L

        /** how often a back edge reads the clock; every one of them checks liveness */
        private const val CLOCK_EVERY = 64

        private const val MAX_INSTRUCTIONS = 1024
        private const val MAX_SLOTS = 256
        private const val MAX_CAPTURES = 256
        private const val MAX_TRIES = 64
        private const val MAX_CALL_ARGS = 256

        private const val NO_OPERAND = Int.MIN_VALUE

        private val EMPTY_INTS = IntArray(0)
        private val EMPTY_VALUES = arrayOfNulls<Any?>(0)
        private val EMPTY_ARGS = emptyList<Any?>()
    }
}
