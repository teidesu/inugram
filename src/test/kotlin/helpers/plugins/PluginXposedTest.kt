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
import org.junit.After
import org.junit.Before
import org.junit.Test

class PluginXposedTest {
    private val jvm = "unsafe.jvm"

    @Before
    fun setUp() = resetBridge()

    @After
    fun tearDown() = resetBridge()

    @Test
    fun the_bundled_xposed_oracle_passes() {
        val (_, lines) = startOracle("xposed-test.js")
        assertOracleExact(lines, "xposed test done", 7)
    }

    @Test
    fun a_hook_runs_before_original_and_after_on_the_calling_thread_then_unhooks() {
        val plugin = startPlugin("xposed", jvm, "unsafe.xposed")
        val engine = plugin.js
        val order = ArrayList<String>()
        engine.onXposedBefore = {
            order.add("before")
            arrayOf("P1", PluginWire.encodeInt(4), PluginWire.encodeInt(6))
        }
        engine.onXposedAfter = {
            order.add("after:${it.result}")
            PluginWire.encodeInt(17)
        }

        val method = plugin.jvmHandle(JvmFixture::class.java.getDeclaredMethod("sum", Int::class.java, Int::class.java))
        val site = plugin.hookWithBefore(method)

        assertTrue(engine.xposedInstalled)
        val sum = JvmFixture::class.java.getDeclaredMethod("sum", Int::class.java, Int::class.java)
        assertEquals(17, invokeOffQueue { sum.invoke(null, 1, 2) as Int })
        assertEquals(listOf("before", "after:10"), order)
        assertEquals(1, engine.xposedBefores.size)
        assertEquals(1, engine.xposedAfters.size)
        drain()
        assertTrue(engine.xposedReleases.isEmpty(), "an after phase that ran took its pending state with it")

        plugin.xposed(PluginXposed.OP_UNHOOK, site)
        assertEquals(3, sum.invoke(null, 1, 2))
        assertEquals(1, engine.xposedBefores.size)
    }

    @Test
    fun a_refused_before_phase_runs_the_original_and_owes_no_release() {
        val plugin = startPlugin("xposed", listOf(jvm, "unsafe.xposed")) {
            it.xposedBudgetMillis = 1
        }
        val engine = plugin.js
        engine.onXposedBefore = { null }

        val sum = JvmFixture::class.java.getDeclaredMethod("sum", Int::class.java, Int::class.java)
        plugin.hookWithBefore(plugin.jvmHandle(sum))

        assertEquals(3, invokeOffQueue { sum.invoke(null, 1, 2) as Int })
        drain()
        assertEquals(1, engine.xposedBefores.size)
        assertTrue(engine.xposedReleases.isEmpty())
    }

    @Test
    fun a_hooked_call_mints_nothing_whatever_the_phases_answer() {
        val plugin = startPlugin("xposed mints", jvm, "unsafe.xposed")
        val engine = plugin.js
        val target = JvmFixture::class.java.getDeclaredMethod("getPayload")
        val site = plugin.hookWithBefore(plugin.jvmHandle(target))
        val fixture = JvmFixture()
        fixture.payload = JvmFixture()
        val settled = engine.liveHandles
        fun assertCallMintsNothing(what: String) {
            assertEquals(1, invokeOffQueue { if (target.invoke(fixture) === fixture.payload) 1 else 0 })
            drain()
            assertEquals(settled, engine.liveHandles, what)
        }
        engine.onXposedBefore = { null }
        assertCallMintsNothing("refused before")
        engine.onXposedBefore = { arrayOf("P1") }
        for (answer in listOf(null, QuickJs.NOT_DISPATCHED)) {
            engine.onXposedAfter = { answer }
            assertCallMintsNothing("after answering $answer")
        }
        plugin.xposed(PluginXposed.OP_JS_BEFORES, site, "0")
        for (answer in listOf(null, QuickJs.NOT_DISPATCHED)) {
            engine.onXposedAfterOnly = { answer }
            assertCallMintsNothing("after-only answering $answer")
        }
    }

    @Test
    fun unchanged_after_preserves_original_objects_and_boxed_types() {
        val plugin = startPlugin("unchanged after", jvm, "unsafe.xposed")
        val engine = plugin.js
        engine.onXposedBefore = { arrayOf("P1") }
        engine.onXposedAfter = { null }
        val target = JvmFixture::class.java.getDeclaredMethod("getPayload")
        plugin.hookWithBefore(plugin.jvmHandle(target))
        val fixture = JvmFixture()
        for (value in listOf(JvmFixture(), 42L, null)) {
            fixture.payload = value
            assertEquals(1, invokeOffQueue { if (target.invoke(fixture) === value) 1 else 0 })
        }
        drain()
        assertTrue(engine.xposedReleases.isEmpty())
        fixture.payload = 42L
        engine.onXposedAfter = { "I42" }
        assertEquals(1, invokeOffQueue { if (target.invoke(fixture) is Int) 1 else 0 })
    }

    @Test
    fun a_filter_that_rejects_a_call_keeps_it_out_of_the_engine() {
        val plugin = startPlugin("xposed filter", jvm, "unsafe.xposed")
        val engine = plugin.js
        engine.onXposedBefore = { arrayOf("P0", "=", "=") }
        val routine = plugin.jvm(
            PluginJvm.OP_ROUTINE,
            name = """{"v":1,"slots":0,"tries":[],"code":[["capture",0],["capture",1],["arg",0],["eq",2,1],["return",3]]}""",
            args = arrayOf("I0", "I42"),
        )
        val sum = JvmFixture::class.java.getDeclaredMethod("sum", Int::class.java, Int::class.java)
        val site = plugin.hookWithBefore(plugin.jvmHandle(sum))
        assertEquals("N", plugin.xposed(PluginXposed.OP_JS_FILTER, site, "G" + jvmHandleId(routine)))
        assertEquals(3, invokeOffQueue { sum.invoke(null, 1, 2) as Int })
        assertTrue(engine.xposedBefores.isEmpty(), "a call the filter rejected never reaches the engine")

        assertEquals(44, invokeOffQueue { sum.invoke(null, 42, 2) as Int })
        assertEquals(1, engine.xposedBefores.size, "a call the filter accepted dispatches as usual")
    }

    @Test
    fun a_site_the_engine_has_not_reported_on_yet_crosses_before_the_original() {
        val plugin = startPlugin("xposed unreported site", jvm, "unsafe.xposed")
        val engine = plugin.js
        val sum = JvmFixture::class.java.getDeclaredMethod("sum", Int::class.java, Int::class.java)
        plugin.xposed(PluginXposed.OP_HOOK, plugin.jvmHandle(sum))
        assertEquals(3, invokeOffQueue { sum.invoke(null, 1, 2) as Int })
        assertEquals(1, engine.xposedBefores.size, "a hook still being registered may not have its before skipped")
        assertTrue(engine.xposedAfterOnlys.isEmpty())
    }

    @Test
    fun a_site_without_befores_crosses_once_after_the_original_and_owes_no_release() {
        val plugin = startPlugin("xposed after only", jvm, "unsafe.xposed")
        val engine = plugin.js
        engine.onXposedAfterOnly = {
            assertEquals(listOf<Any?>(1, 2), it.args)
            PluginWire.encodeInt((it.result as Int) * 10L)
        }
        val sum = JvmFixture::class.java.getDeclaredMethod("sum", Int::class.java, Int::class.java)
        val site = decodeString(plugin.xposed(PluginXposed.OP_HOOK, plugin.jvmHandle(sum))).toLong()
        plugin.xposed(PluginXposed.OP_JS_BEFORES, site, "0")
        assertEquals(30, invokeOffQueue { sum.invoke(null, 1, 2) as Int })
        drain()
        assertEquals(1, engine.xposedAfterOnlys.size)
        assertEquals(site, engine.xposedAfterOnlys.single().site)
        assertTrue(engine.xposedBefores.isEmpty())
        assertTrue(engine.xposedAfters.isEmpty())
        assertTrue(engine.xposedReleases.isEmpty())

        plugin.xposed(PluginXposed.OP_JS_BEFORES, site, "1")
        engine.onXposedBefore = { arrayOf("P0", "=", "=") }
        assertEquals(3, invokeOffQueue { sum.invoke(null, 1, 2) as Int })
        assertEquals(1, engine.xposedBefores.size, "a site that gained a before crosses before the original again")
        assertEquals(1, engine.xposedAfterOnlys.size)
    }

    @Test
    fun xposed_can_allocate_and_call_original_constructors() {
        val plugin = startPlugin("xposed", jvm, "unsafe.xposed")
        val cls = jvmHandleId(plugin.jvm(PluginJvm.OP_CLASS, name = JvmFixture::class.java.name))

        val bridge = PluginJvm.bridgeFor(plugin.js)!!
        val allocated = jvmHandleId(plugin.xposed(PluginXposed.OP_ALLOCATE, cls))
        assertEquals(0, (bridge.decode("G$allocated") as JvmFixture).count)

        val constructor = plugin.jvmHandle(JvmFixture::class.java.getDeclaredConstructor())
        val constructed = jvmHandleId(plugin.xposed(PluginXposed.OP_CALL_ORIGINAL, constructor, PluginWire.encodeNull()))
        assertEquals(3, (bridge.decode("G$constructed") as JvmFixture).count)
    }

    @Test
    fun xposed_can_disable_profile_saver() {
        val plugin = startPlugin("xposed", jvm, "unsafe.xposed")
        val result = plugin.xposed(PluginXposed.OP_DISABLE_PROFILE_SAVER, 0)
        assertTrue(PluginWire.decode(result) is PluginWire.Value.Bool)
    }

    @Test
    fun routine_hooks_run_on_the_caller_without_entering_js_and_dispose_individually() {
        val plugin = startPlugin("routine-hooks", "unsafe.jvm", "unsafe.xposed")
        val fixture = JvmFixture()
        val target = plugin.jvmWire(fixture)
        val thread = plugin.jvmWire(Thread::class.java)
        val before = plugin.jvm(PluginJvm.OP_ROUTINE, name = """{"v":1,"slots":0,"tries":[],"code":[["capture",0],["capture",1],["capture",2],["call",1,["currentThread"],[]],["set",0,["payload"],3],["set",0,["count"],2]]}""", args = arrayOf(target, thread, "I7"))
        val after = plugin.jvm(PluginJvm.OP_ROUTINE, name = """{"v":1,"slots":0,"tries":[],"code":[["capture",0],["capture",1],["set",0,["count"],1]]}""", args = arrayOf(target, "I9"))
        val increment = plugin.jvm(PluginJvm.OP_ROUTINE, name = """{"v":1,"slots":0,"tries":[],"code":[["capture",0],["capture",1],["get",0,["count"]],["add",2,1],["set",0,["count"],3]]}""", args = arrayOf(target, "I1"))
        val beforeWire = "G" + jvmHandleId(before)
        val afterWire = "G" + jvmHandleId(after)
        val incrementWire = "G" + jvmHandleId(increment)
        val method = plugin.jvmHandle(JvmFixture::class.java.getDeclaredMethod("readCount"))
        val site = decodeString(plugin.xposed(PluginXposed.OP_HOOK, method, args = arrayOf(beforeWire, afterWire))).toLong()
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
        val seen = ArrayList<Thread>()
        val before = Thread {
            seen.add(Thread.currentThread())
            throw IllegalStateException("hook failure")
        }
        val after = Thread { seen.add(Thread.currentThread()) }
        val beforeWire = plugin.jvmWire(before)
        val afterWire = plugin.jvmWire(after)
        val reflected = JvmFixture::class.java.getDeclaredMethod("sum", Int::class.java, Int::class.java)
        val method = plugin.jvmHandle(reflected)
        val invalid = plugin.jvmWire(JvmFixture())
        assertTrue(plugin.xposed(PluginXposed.OP_HOOK, method, args = arrayOf(invalid, "N")).startsWith("Pinvalid-argument"))
        val site = decodeString(plugin.xposed(PluginXposed.OP_HOOK, method, args = arrayOf(beforeWire, afterWire))).toLong()
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

    @Test
    fun native_afters_run_in_reverse_registration_order() {
        val plugin = startPlugin("native-hook-order", "unsafe.jvm", "unsafe.xposed")
        val order = ArrayList<String>()
        fun record(label: String) = plugin.jvmWire(Thread { order.add(label) })
        val reflected = JvmFixture::class.java.getDeclaredMethod("sum", Int::class.java, Int::class.java)
        val method = plugin.jvmHandle(reflected)
        val firstBefore = record("a")
        val firstAfter = record("A")
        val site = decodeString(plugin.xposed(PluginXposed.OP_HOOK, method, args = arrayOf(firstBefore, firstAfter))).toLong()
        plugin.xposed(PluginXposed.OP_NATIVE_ADD, site, "1", firstBefore, firstAfter)
        plugin.xposed(PluginXposed.OP_NATIVE_ADD, site, "2", record("b"), record("B"))
        assertEquals(3, invokeOffQueue { reflected.invoke(null, 1, 2) as Int })
        assertEquals(listOf("a", "b", "B", "A"), order)
        plugin.xposed(PluginXposed.OP_UNHOOK, site)
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
