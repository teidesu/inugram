package desu.inugram.jvmfixture

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `@JvmStatic` on a companion member puts the body on `Companion` and leaves a forwarder on the
 * class, so a recursive call from that body never re-enters the forwarder a hook was placed on. A
 * top-level function is a real static calling itself, which is the shape stock recursion has.
 */
fun countHookDepth(depth: Int): Int = if (depth == 0) 0 else 1 + countHookDepth(depth - 1)

/**
 * `PluginJvm` walks interfaces itself rather than calling `getMethods()`/`getFields()`, so the
 * members that live only on one - a constant, which *is* inherited, and a static, which is not -
 * need something to be declared on.
 */
interface JvmContract {
    companion object {
        const val STAMP: String = "stamped"

        @JvmStatic fun notInherited(): String = "static on the interface"
    }
}

/**
 * Never touched by anything but `PluginJvmTest`'s initialization test, so its static block has not
 * run when the plugin names it: naming must not run it, the first static use must.
 */
class JvmLazy {
    companion object {
        @JvmField var initializedBy: String = "nobody"

        @JvmStatic fun whoInitialized(): String = initializedBy

        init {
            initializedBy = "clinit"
        }
    }
}

/**
 * something for `PluginJvmTest` to reflect over. Deliberately **not** in
 * `desu.inugram.helpers.plugins`: that package is refused by `PluginJvm` whatever the grant says, so
 * a fixture living there would make every test read as a passing test of the refusal.
 *
 * Fields are `@JvmField` because the api reflects over java's shape, and a kotlin property is a pair
 * of methods rather than the field a plugin would name.
 */
class JvmFixture : JvmContract {
    @JvmField var count: Int = 3

    @JvmField var label: String = "inugram"

    @JvmField var flag: Boolean = true

    @JvmField var nothing: Any? = null

    @JvmField var digest: ByteArray = byteArrayOf(1, 2, 3)

    @JvmField var big: Long = 9007199254740993L

    /** whatever a test needs handed back, so a returned reference has something to be */
    @JvmField var payload: Any? = null

    fun getPayload(): Any? = payload

    @JvmField val sealed: String = "cannot be assigned"

    private var secret: String = "private"

    /** which constructor ran, so a test can say which overload was picked */
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

    fun ambiguous(value: Comparable<*>): String = "comparable"

    fun boxed(value: Any): String = value.javaClass.name

    fun sized(bytes: ByteArray): Int = bytes.size

    fun boom(): String = throw IllegalStateException("boom")

    /** what a java callee may throw that is not an `Exception`, which a routine must still unwind */
    fun detonate(): String = throw AssertionError("detonate")

    /** a member crossing back as an ordinary value, which `ctx.method` in `inu.xposed` also is */
    fun ownMethod(): java.lang.reflect.Method = JvmFixture::class.java.getDeclaredMethod("echo", String::class.java)

    /** the same for a constructor, which `ctx.method` on a hooked constructor is */
    fun ownConstructor(): java.lang.reflect.Constructor<*> =
        JvmFixture::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)

    /** runs it *inside* the reflected call, which is the reentrancy every rule here is about */
    fun runNow(action: Runnable): String {
        action.run()
        return "ran"
    }

    companion object {
        @JvmField var tag: String = "static"

        /** the instance a test built, handed to its plugin through a static read so a test can set fields first */
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

        @JvmField val sharedHookCalls = java.util.concurrent.atomic.AtomicInteger()

        @JvmStatic fun computeHookSum(a: Int, b: Int): Int {
            sharedHookCalls.incrementAndGet()
            return a + b
        }
    }
}
