package desu.inugram.jvmfixture

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** top-level: a `@JvmStatic` companion body recursing would skip the hooked forwarder */
fun countHookDepth(depth: Int): Int = if (depth == 0) 0 else 1 + countHookDepth(depth - 1)

interface JvmContract {
    companion object {
        const val STAMP: String = "stamped"

        @JvmStatic fun notInherited(): String = "static on the interface"
    }
}

/** touched only by one test, so its `<clinit>` has not run before it */
class JvmLazy {
    companion object {
        @JvmField var initializedBy: String = "nobody"

        @JvmStatic fun whoInitialized(): String = initializedBy

        init {
            initializedBy = "clinit"
        }
    }
}

/** outside `desu.inugram.helpers.plugins`, which `PluginJvm` refuses whatever the grant */
class JvmFixture : JvmContract {
    @JvmField var count: Int = 3

    @JvmField var label: String = "inugram"

    @JvmField var flag: Boolean = true

    @JvmField var nothing: Any? = null

    @JvmField var digest: ByteArray = byteArrayOf(1, 2, 3)

    @JvmField var big: Long = 9007199254740993L

    @JvmField var payload: Any? = null

    fun getPayload(): Any? = payload

    @JvmField val sealed: String = "cannot be assigned"

    private var secret: String = "private"

    @JvmField var madeBy: String = "noArg"

    constructor()

    constructor(count: Int) {
        madeBy = "int"
        this.count = count
    }

    constructor(big: Long) {
        madeBy = "long"
        this.big = big
    }

    fun readCount(): Int = count

    fun echo(text: String): String = "echo:$text"

    fun width(value: Int): String = "int"

    fun width(value: Long): String = "long"

    fun width(value: Double): String = "double"

    fun ambiguous(value: CharSequence): String = "charSequence"

    fun letter(value: Char): String = "char"

    fun letter(value: String): String = "string"

    fun boxedLetter(value: Char?): String = "character"

    fun boxedLetter(value: Any?): String = "object"

    fun ambiguous(value: Comparable<*>): String = "comparable"

    fun boxed(value: Any): String = value.javaClass.name

    fun sized(bytes: ByteArray): Int = bytes.size

    fun boom(): String = throw IllegalStateException("boom")

    fun detonate(): String = throw AssertionError("detonate")

    fun ownMethod(): java.lang.reflect.Method = JvmFixture::class.java.getDeclaredMethod("echo", String::class.java)

    fun ownConstructor(): java.lang.reflect.Constructor<*> =
        JvmFixture::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)

    fun runNow(action: Runnable): String {
        action.run()
        return "ran"
    }

    companion object {
        @JvmField var tag: String = "static"

        @JvmField var shared: Any? = null

        @JvmField var task: Runnable? = null
        @JvmField var callbackEntered: CountDownLatch? = null
        @JvmField var callbackRelease: CountDownLatch? = null

        @JvmStatic fun awaitCallbackRelease() {
            callbackEntered!!.countDown()
            check(callbackRelease!!.await(5, TimeUnit.SECONDS))
        }

        @JvmStatic fun make(): JvmFixture = JvmFixture()

        @JvmStatic fun sum(a: Int, b: Int): Int = a + b

        @JvmStatic fun runTask() = task!!.run()

        @JvmField val sharedHookCalls = java.util.concurrent.atomic.AtomicInteger()

        @JvmStatic fun computeHookSum(a: Int, b: Int): Int {
            sharedHookCalls.incrementAndGet()
            return a + b
        }
    }
}

open class JvmSuperBase {
    open fun describe(): String = "base"

    open fun describe(suffix: String): String = "base:$suffix"

    open fun scale(value: Int): Int = value * 2

    open fun explode(): String = throw IllegalStateException("base explodes")

    open fun itself(): JvmSuperBase = this

    companion object {
        @JvmStatic fun origin(): String = "static on the base"
    }
}

open class JvmSuperChild : JvmSuperBase() {
    override fun describe(): String = "child"

    override fun describe(suffix: String): String = "child:$suffix"

    override fun scale(value: Int): Int = value * 3

    override fun explode(): String = "child does not"

    override fun itself(): JvmSuperChild = this
}

class JvmSuperGrandchild : JvmSuperChild() {
    override fun describe(): String = "grandchild"
}

abstract class JvmSuperAbstract {
    abstract fun describe(): String
}

class JvmSuperConcrete : JvmSuperAbstract() {
    override fun describe(): String = "concrete"
}
