package desu.inugram.jvmfixture

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * something for `PluginJvmTest` to reflect over. Deliberately **not** in
 * `desu.inugram.helpers.plugins`: that package is refused by `PluginJvm` whatever the grant says, so
 * a fixture living there would make every test read as a passing test of the refusal.
 *
 * Fields are `@JvmField` because the api reflects over java's shape, and a kotlin property is a pair
 * of methods rather than the field a plugin would name.
 */
class JvmFixture {
    @JvmField var count: Int = 3

    @JvmField var label: String = "inugram"

    @JvmField var flag: Boolean = true

    @JvmField var nothing: Any? = null

    @JvmField var digest: ByteArray = byteArrayOf(1, 2, 3)

    @JvmField var big: Long = 9007199254740993L

    /** whatever a test needs handed back, so the check on a *returned* class has something to refuse */
    @JvmField var payload: Any? = null

    fun getPayload(): Any? = payload

    @JvmField val sealed: String = "cannot be assigned"

    private var secret: String = "private"

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

    /** a member crossing back as an ordinary value, which `ctx.method` in `inu.xposed` also is */
    fun ownMethod(): java.lang.reflect.Method = JvmFixture::class.java.getDeclaredMethod("echo", String::class.java)

    /** runs it *inside* the reflected call, which is the reentrancy every rule here is about */
    fun runNow(action: Runnable): String {
        action.run()
        return "ran"
    }

    companion object {
        @JvmField var tag: String = "static"

        @JvmField var task: Runnable? = null
        @JvmField var callbackEntered: CountDownLatch? = null
        @JvmField var callbackRelease: CountDownLatch? = null

        @JvmStatic fun awaitCallbackRelease() {
            callbackEntered!!.countDown()
            check(callbackRelease!!.await(5, TimeUnit.SECONDS))
        }

        @JvmStatic fun make(): JvmFixture = JvmFixture()

        @JvmStatic fun sum(a: Int, b: Int): Int = a + b

        /** a `Class` handed back from an in-scope member, so the check on one has something to refuse */
        @JvmStatic fun classOfSomethingElse(): Class<*> = ArrayList::class.java
    }
}
