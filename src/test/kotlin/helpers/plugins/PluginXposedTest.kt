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

    @Test
    fun a_refused_before_phase_runs_the_original_and_owes_no_release() {
        val plugin = startPlugin("xposed", listOf(scope, "unsafe.xposed(desu.inugram.jvmfixture.*)")) {
            it.xposedBudgetMillis = 1
        }
        val engine = plugin.js
        engine.onXposedBefore = { null }

        val cls = jvmHandleId(plugin.jvm(PluginJvm.OP_CLASS, name = JvmFixture::class.java.name))
        val method = jvmHandleId(plugin.jvm(PluginJvm.OP_METHOD, cls, "sum(II)I"))
        plugin.xposed(PluginXposed.OP_HOOK, method)
        val sum = JvmFixture::class.java.getDeclaredMethod("sum", Int::class.java, Int::class.java)

        assertEquals(3, invokeOffQueue { sum.invoke(null, 1, 2) as Int })
        drain()
        assertEquals(1, engine.xposedBefores.size)
        assertTrue(engine.xposedReleases.isEmpty())

        PluginXposed.detach(engine)
    }

    @Test
    fun unchanged_after_preserves_original_objects_and_boxed_types() {
        val plugin = startPlugin("unchanged after", scope, "unsafe.xposed(desu.inugram.jvmfixture.*)")
        val engine = plugin.js
        engine.onXposedBefore = { arrayOf("P1") }
        engine.onXposedAfter = { "U" }
        val cls = jvmHandleId(plugin.jvm(PluginJvm.OP_CLASS, name = JvmFixture::class.java.name))
        val method = jvmHandleId(plugin.jvm(PluginJvm.OP_METHOD, cls, "getPayload()Ljava/lang/Object;"))
        plugin.xposed(PluginXposed.OP_HOOK, method)
        val target = JvmFixture::class.java.getDeclaredMethod("getPayload")
        val fixture = JvmFixture()
        try {
            for (value in listOf(JvmFixture(), 42L, null)) {
                fixture.payload = value
                assertEquals(1, invokeOffQueue { if (target.invoke(fixture) === value) 1 else 0 })
            }
            drain()
            assertEquals(3, engine.xposedReleases.size)
            fixture.payload = 42L
            engine.onXposedAfter = { "I42" }
            assertEquals(1, invokeOffQueue { if (target.invoke(fixture) is Int) 1 else 0 })
        } finally {
            PluginXposed.detach(engine)
        }
    }

    @Test
    fun xposedCanAllocateAndCallOriginalConstructors() {
        val plugin = startPlugin("xposed", scope, "unsafe.xposed(desu.inugram.jvmfixture.*)")
        val cls = jvmHandleId(plugin.jvm(PluginJvm.OP_CLASS, name = JvmFixture::class.java.name))

        val allocated = jvmHandleId(plugin.xposed(PluginXposed.OP_ALLOCATE, cls))
        assertEquals(0, intOf(plugin.jvm(PluginJvm.OP_GET, allocated, "count")))

        val constructor = jvmHandleId(plugin.jvm(PluginJvm.OP_METHOD, cls, "<init>()V"))
        val constructed = jvmHandleId(plugin.xposed(PluginXposed.OP_CALL_ORIGINAL, constructor, PluginWire.encodeNull()))
        assertEquals(3, intOf(plugin.jvm(PluginJvm.OP_GET, constructed, "count")))
    }

    @Test
    fun xposedCanDisableProfileSaver() {
        val plugin = startPlugin("xposed", scope, "unsafe.xposed(desu.inugram.jvmfixture.*)")
        val result = plugin.xposed(PluginXposed.OP_DISABLE_PROFILE_SAVER, 0)
        assertTrue(PluginWire.decode(result) is PluginWire.Value.Bool)
    }

    @Test
    fun routine_hooks_run_on_the_caller_without_entering_js_and_dispose_individually() {
        val plugin = startPlugin("routine-hooks", "unsafe.jvm", "unsafe.xposed")
        val fixture = JvmFixture()
        val bridge = PluginJvm.bridgeFor(plugin.js)!!
        val target = "G" + bridge.encode(fixture).substring(2)
        val thread = "G" + bridge.encode(Thread::class.java).substring(2)
        val before = plugin.jvm(PluginJvm.OP_ROUTINE, name = """{"nodes":[["call",[0,1],"currentThread",[]],["set",[0,0],"payload",[1,0]],["set",[0,0],"count",[0,2]]],"roots":[1,2]}""", args = arrayOf(target, thread, "I7"))
        val after = plugin.jvm(PluginJvm.OP_ROUTINE, name = """{"nodes":[["set",[0,0],"count",[0,1]]],"roots":[0]}""", args = arrayOf(target, "I9"))
        val increment = plugin.jvm(PluginJvm.OP_ROUTINE, name = """{"nodes":[["get",[0,0],"count"],["math","+",[1,0],[0,1]],["set",[0,0],"count",[1,1]]],"roots":[2]}""", args = arrayOf(target, "I1"))
        val beforeWire = "G" + jvmHandleId(before)
        val afterWire = "G" + jvmHandleId(after)
        val incrementWire = "G" + jvmHandleId(increment)
        val method = jvmHandleId(bridge.encode(JvmFixture::class.java.getDeclaredMethod("readCount")))
        val site = stringOf(plugin.xposed(PluginXposed.OP_HOOK, method, args = arrayOf(beforeWire, afterWire))).toLong()
        assertEquals("N", plugin.xposed(PluginXposed.OP_NATIVE_ADD, site, "1", beforeWire, afterWire))
        assertEquals("N", plugin.xposed(PluginXposed.OP_NATIVE_ADD, site, "2", incrementWire, "N"))
        assertTrue(plugin.xposed(PluginXposed.OP_HOOK, method).startsWith("Pinvalid-argument"))
        val reflected = JvmFixture::class.java.getDeclaredMethod("readCount")
        assertEquals(8, invokeOffQueue {
            val result = reflected.invoke(fixture) as Int
            assertEquals(Thread.currentThread(), fixture.payload)
            result
        })
        assertEquals(9, fixture.count)
        assertTrue(plugin.js.xposedBefores.isEmpty())
        assertTrue(plugin.js.xposedAfters.isEmpty())
        plugin.xposed(PluginXposed.OP_NATIVE_REMOVE, site, "1")
        assertEquals(10, reflected.invoke(fixture))
        plugin.xposed(PluginXposed.OP_NATIVE_REMOVE, site, "2")
        assertEquals(10, reflected.invoke(fixture))
        plugin.xposed(PluginXposed.OP_UNHOOK, site)
    }

    @Test
    fun arbitrary_runnables_run_inline_and_exceptions_do_not_replace_the_original() {
        val plugin = startPlugin("runnable-hooks", "unsafe.jvm", "unsafe.xposed")
        val bridge = PluginJvm.bridgeFor(plugin.js)!!
        val seen = ArrayList<Thread>()
        val before = Thread {
            seen.add(Thread.currentThread())
            throw IllegalStateException("hook failure")
        }
        val after = Thread { seen.add(Thread.currentThread()) }
        val beforeWire = "G" + jvmHandleId(bridge.encode(before))
        val afterWire = "G" + jvmHandleId(bridge.encode(after))
        val reflected = JvmFixture::class.java.getDeclaredMethod("sum", Int::class.java, Int::class.java)
        val method = jvmHandleId(bridge.encode(reflected))
        val invalid = "G" + jvmHandleId(bridge.encode(JvmFixture()))
        assertTrue(plugin.xposed(PluginXposed.OP_HOOK, method, args = arrayOf(invalid, "N")).startsWith("Pinvalid-argument"))
        val site = stringOf(plugin.xposed(PluginXposed.OP_HOOK, method, args = arrayOf(beforeWire, afterWire))).toLong()
        plugin.xposed(PluginXposed.OP_NATIVE_ADD, site, "1", beforeWire, afterWire)
        assertEquals(3, invokeOffQueue {
            val result = reflected.invoke(null, 1, 2) as Int
            assertEquals(listOf(Thread.currentThread(), Thread.currentThread()), seen)
            result
        })
        assertTrue(plugin.js.xposedBefores.isEmpty())
        assertTrue(plugin.js.xposedAfters.isEmpty())
        plugin.xposed(PluginXposed.OP_UNHOOK, site)
        assertEquals(3, reflected.invoke(null, 1, 2))
        assertEquals(2, seen.size)
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
