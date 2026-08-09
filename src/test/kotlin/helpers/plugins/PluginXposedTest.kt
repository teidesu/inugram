package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.platform.PluginJvm
import desu.inugram.helpers.plugins.platform.PluginXposed
import desu.inugram.jvmfixture.JvmFixture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

class PluginXposedTest {
    private val scope = "unsafe.jvm(desu.inugram.jvmfixture.*)"

    @Before
    fun setUp() = resetBridge()

    @Test
    fun aHookRunsBeforeOriginalAndAfterOnTheCallingThreadThenUnhooks() {
        val plugin = startPlugin("xposed", scope, "unsafe.xposed(desu.inugram.jvmfixture.*)")
        val engine = plugin.js
        val order = ArrayList<String>()
        engine.onXposedBefore = {
            order.add("before")
            arrayOf("P1", PluginWire.encodeInt(4), PluginWire.encodeInt(6))
        }
        engine.onXposedAfter = {
            order.add("after:${intOf(it.resultWire)}")
            PluginWire.encodeInt(17)
        }

        val cls = jvmHandleId(plugin.jvm(PluginJvm.OP_CLASS, name = JvmFixture::class.java.name))
        val method = jvmHandleId(plugin.jvm(PluginJvm.OP_METHOD, cls, "sum(II)I"))
        val site = stringOf(plugin.xposed(PluginXposed.OP_HOOK, method)).toLong()

        assertTrue(engine.xposedInstalled)
        val sum = JvmFixture::class.java.getDeclaredMethod("sum", Int::class.java, Int::class.java)
        assertEquals(17, invokeOffQueue { sum.invoke(null, 1, 2) as Int })
        assertEquals(listOf("before", "after:10"), order)
        assertEquals(1, engine.xposedBefores.size)
        assertEquals(1, engine.xposedAfters.size)
        drain()
        assertEquals(listOf(engine.xposedBefores.single().dispatchId), engine.xposedReleases)

        plugin.xposed(PluginXposed.OP_UNHOOK, site)
        assertEquals(3, sum.invoke(null, 1, 2))
        assertEquals(1, engine.xposedBefores.size)

        PluginXposed.detach(engine)
    }

    private fun Plugin.jvm(op: Int, target: Long = 0, name: String = "", vararg args: String): String =
        js.listener!!.jvm(op, target, name, arrayOf(*args))

    private fun Plugin.xposed(op: Int, target: Long, name: String = "", vararg args: String): String =
        js.listener!!.xposed(op, target, name, arrayOf(*args))

    private fun intOf(wire: String): Long = (PluginWire.decode(wire) as PluginWire.Value.IntNum).value

    private fun jvmHandleId(wire: String): Long {
        assertTrue(wire.length > 2 && wire[0] == 'G', "not a jvm handle: $wire")
        return wire.substring(2).toLong()
    }

    private fun invokeOffQueue(call: () -> Int): Int {
        val result = AtomicInteger()
        val done = CountDownLatch(1)
        Thread {
            result.set(call())
            done.countDown()
        }.start()
        repeat(100) {
            drain()
            if (done.await(10, TimeUnit.MILLISECONDS)) return result.get()
        }
        assertTrue(false, "hooked call did not return")
        return 0
    }
}
