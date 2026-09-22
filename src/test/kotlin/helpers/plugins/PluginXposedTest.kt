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
    private val jvm = "unsafe.jvm"

    @Before
    fun setUp() = resetBridge()

    @Test
    fun the_bundled_xposed_oracle_passes() {
        val (plugin, lines) = startOracle("xposed-test.js")
        try {
            assertOracleExact(lines, "xposed test done", 7)
        } finally {
            PluginXposed.detach(plugin.session!!)
            closeEngine(plugin)
        }
    }

    @Test
    fun aHookRunsBeforeOriginalAndAfterOnTheCallingThreadThenUnhooks() {
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

        val method = memberHandle(plugin, JvmFixture::class.java.getDeclaredMethod("sum", Int::class.java, Int::class.java))
        val site = hookWithBefore(plugin, method)

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

        PluginXposed.detach(plugin.session!!)
    }

    @Test
    fun a_refused_before_phase_runs_the_original_and_owes_no_release() {
        val plugin = startPlugin("xposed", listOf(jvm, "unsafe.xposed")) {
            it.xposedBudgetMillis = 1
        }
        val engine = plugin.js
        engine.onXposedBefore = { null }

        val sum = JvmFixture::class.java.getDeclaredMethod("sum", Int::class.java, Int::class.java)
        hookWithBefore(plugin, memberHandle(plugin, sum))

        assertEquals(3, invokeOffQueue { sum.invoke(null, 1, 2) as Int })
        drain()
        assertEquals(1, engine.xposedBefores.size)
        assertTrue(engine.xposedReleases.isEmpty())

        PluginXposed.detach(plugin.session!!)
    }

    @Test
    fun a_hooked_call_mints_nothing_whatever_the_phases_answer() {
        val plugin = startPlugin("xposed mints", jvm, "unsafe.xposed")
        val engine = plugin.js
        val target = JvmFixture::class.java.getDeclaredMethod("getPayload")
        val site = hookWithBefore(plugin, memberHandle(plugin, target))
        val fixture = JvmFixture()
        fixture.payload = JvmFixture()
        val settled = engine.liveHandles
        fun assertCallMintsNothing(what: String) {
            assertEquals(1, invokeOffQueue { if (target.invoke(fixture) === fixture.payload) 1 else 0 })
            drain()
            assertEquals(settled, engine.liveHandles, what)
        }
        try {
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
        } finally {
            PluginXposed.detach(plugin.session!!)
        }
    }

    @Test
    fun unchanged_after_preserves_original_objects_and_boxed_types() {
        val plugin = startPlugin("unchanged after", jvm, "unsafe.xposed")
        val engine = plugin.js
        engine.onXposedBefore = { arrayOf("P1") }
        engine.onXposedAfter = { null }
        val target = JvmFixture::class.java.getDeclaredMethod("getPayload")
        hookWithBefore(plugin, memberHandle(plugin, target))
        val fixture = JvmFixture()
        try {
            for (value in listOf(JvmFixture(), 42L, null)) {
                fixture.payload = value
                assertEquals(1, invokeOffQueue { if (target.invoke(fixture) === value) 1 else 0 })
            }
            drain()
            assertTrue(engine.xposedReleases.isEmpty())
            fixture.payload = 42L
            engine.onXposedAfter = { "I42" }
            assertEquals(1, invokeOffQueue { if (target.invoke(fixture) is Int) 1 else 0 })
        } finally {
            PluginXposed.detach(plugin.session!!)
        }
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
        val site = hookWithBefore(plugin, memberHandle(plugin, sum))
        assertEquals("N", plugin.xposed(PluginXposed.OP_JS_FILTER, site, "G" + jvmHandleId(routine)))
        try {
            assertEquals(3, invokeOffQueue { sum.invoke(null, 1, 2) as Int })
            assertTrue(engine.xposedBefores.isEmpty(), "a call the filter rejected never reaches the engine")

            assertEquals(44, invokeOffQueue { sum.invoke(null, 42, 2) as Int })
            assertEquals(1, engine.xposedBefores.size, "a call the filter accepted dispatches as usual")
        } finally {
            PluginXposed.detach(plugin.session!!)
        }
    }

    @Test
    fun a_site_the_engine_has_not_reported_on_yet_crosses_before_the_original() {
        val plugin = startPlugin("xposed unreported site", jvm, "unsafe.xposed")
        val engine = plugin.js
        val sum = JvmFixture::class.java.getDeclaredMethod("sum", Int::class.java, Int::class.java)
        plugin.xposed(PluginXposed.OP_HOOK, memberHandle(plugin, sum))
        try {
            assertEquals(3, invokeOffQueue { sum.invoke(null, 1, 2) as Int })
            assertEquals(1, engine.xposedBefores.size, "a hook still being registered may not have its before skipped")
            assertTrue(engine.xposedAfterOnlys.isEmpty())
        } finally {
            PluginXposed.detach(plugin.session!!)
        }
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
        val site = stringOf(plugin.xposed(PluginXposed.OP_HOOK, memberHandle(plugin, sum))).toLong()
        plugin.xposed(PluginXposed.OP_JS_BEFORES, site, "0")
        try {
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
        } finally {
            PluginXposed.detach(plugin.session!!)
        }
    }

    @Test
    fun xposedCanAllocateAndCallOriginalConstructors() {
        val plugin = startPlugin("xposed", jvm, "unsafe.xposed")
        val cls = jvmHandleId(plugin.jvm(PluginJvm.OP_CLASS, name = JvmFixture::class.java.name))

        val bridge = PluginJvm.bridgeFor(plugin.js)!!
        val allocated = jvmHandleId(plugin.xposed(PluginXposed.OP_ALLOCATE, cls))
        assertEquals(0, (bridge.decode("G$allocated") as JvmFixture).count)

        val constructor = memberHandle(plugin, JvmFixture::class.java.getDeclaredConstructor())
        val constructed = jvmHandleId(plugin.xposed(PluginXposed.OP_CALL_ORIGINAL, constructor, PluginWire.encodeNull()))
        assertEquals(3, (bridge.decode("G$constructed") as JvmFixture).count)
    }

    @Test
    fun xposedCanDisableProfileSaver() {
        val plugin = startPlugin("xposed", jvm, "unsafe.xposed")
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
        val before = plugin.jvm(PluginJvm.OP_ROUTINE, name = """{"v":1,"slots":0,"tries":[],"code":[["capture",0],["capture",1],["capture",2],["call",1,["currentThread"],[]],["set",0,["payload"],3],["set",0,["count"],2]]}""", args = arrayOf(target, thread, "I7"))
        val after = plugin.jvm(PluginJvm.OP_ROUTINE, name = """{"v":1,"slots":0,"tries":[],"code":[["capture",0],["capture",1],["set",0,["count"],1]]}""", args = arrayOf(target, "I9"))
        val increment = plugin.jvm(PluginJvm.OP_ROUTINE, name = """{"v":1,"slots":0,"tries":[],"code":[["capture",0],["capture",1],["get",0,["count"]],["add",2,1],["set",0,["count"],3]]}""", args = arrayOf(target, "I1"))
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

    @Test
    fun native_afters_run_in_reverse_registration_order() {
        val plugin = startPlugin("native-hook-order", "unsafe.jvm", "unsafe.xposed")
        val bridge = PluginJvm.bridgeFor(plugin.js)!!
        val order = ArrayList<String>()
        fun record(label: String) = "G" + jvmHandleId(bridge.encode(Thread { order.add(label) }))
        val reflected = JvmFixture::class.java.getDeclaredMethod("sum", Int::class.java, Int::class.java)
        val method = jvmHandleId(bridge.encode(reflected))
        val firstBefore = record("a")
        val firstAfter = record("A")
        val site = stringOf(plugin.xposed(PluginXposed.OP_HOOK, method, args = arrayOf(firstBefore, firstAfter))).toLong()
        plugin.xposed(PluginXposed.OP_NATIVE_ADD, site, "1", firstBefore, firstAfter)
        plugin.xposed(PluginXposed.OP_NATIVE_ADD, site, "2", record("b"), record("B"))
        assertEquals(3, invokeOffQueue { reflected.invoke(null, 1, 2) as Int })
        assertEquals(listOf("a", "b", "B", "A"), order)
        plugin.xposed(PluginXposed.OP_UNHOOK, site)
    }

    private fun Plugin.jvm(op: Int, target: Long = 0, name: String = "", vararg args: String): String =
        js.listener!!.jvm(op, target, name, arrayOf(*args))

    private fun Plugin.xposed(op: Int, target: Long, name: String = "", vararg args: String): String =
        js.listener!!.xposed(op, target, name, arrayOf(*args))

    private fun hookWithBefore(plugin: Plugin, member: Long): Long {
        val site = stringOf(plugin.xposed(PluginXposed.OP_HOOK, member)).toLong()
        plugin.xposed(PluginXposed.OP_JS_BEFORES, site, "1")
        return site
    }

    /** the handle `getDeclaredMethod` would answer with: a member, minted the way any reference is */
    private fun memberHandle(plugin: Plugin, member: java.lang.reflect.Member): Long =
        jvmHandleId(PluginJvm.bridgeFor(plugin.js)!!.encode(member))

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
