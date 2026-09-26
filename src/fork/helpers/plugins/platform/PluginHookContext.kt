package desu.inugram.helpers.plugins.platform

import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.lang.reflect.Method

class PluginHookContext internal constructor(
    private val method: Member,
    private val receiver: Any?,
    internal val arguments: MutableList<Any?>,
) {
    private val thread = Thread.currentThread()
    private var active = true
    internal var answered = false
    internal var outcome: Result<Any?> = Result.success(null)

    private fun checkActive() {
        check(active && Thread.currentThread() === thread) { "xposed: context is outside its invocation" }
    }

    fun getThisObject(): Any? {
        checkActive()
        return receiver
    }
    fun getMethod(): Member {
        checkActive()
        return method
    }
    fun getArgument(index: Int): Any? {
        checkActive()
        require(index in arguments.indices) { "xposed: argument index out of range" }
        return arguments[index]
    }
    fun setArgument(index: Int, value: Any?) {
        checkActive()
        require(index in arguments.indices) { "xposed: argument index out of range" }
        val type = (method as Executable).parameterTypes[index]
        val converted = PluginJvm.convert(value, type)
            ?: throw IllegalArgumentException("xposed: invalid argument for ${type.name}")
        arguments[index] = converted.value
    }
    fun getReturnValue(): Any? {
        checkActive()
        return outcome.getOrNull()
    }
    fun getThrowable(): Throwable? {
        checkActive()
        return outcome.exceptionOrNull()
    }
    fun setReturnValue(value: Any?) {
        checkActive()
        val type = (method as? Method)?.returnType ?: Void.TYPE
        outcome = Result.success(convertReturnValue(method, value))
        answered = true
    }
    fun setThrowable(value: Throwable) {
        checkActive()
        outcome = Result.failure(value)
        answered = true
    }
    internal fun close() { active = false }
}

internal class PluginXposedRoutine(private val program: PluginJvmRoutine) : java.util.function.Consumer<PluginHookContext> {
    override fun accept(context: PluginHookContext) { program.execute(context) }
}

internal fun convertReturnValue(member: Member, value: Any?): Any? {
    val type = (member as? Method)?.returnType ?: return null
    if (type == Void.TYPE) return null
    return (PluginJvm.convert(value, type) ?: throw IllegalArgumentException("xposed: invalid result for ${type.name}")).value
}
