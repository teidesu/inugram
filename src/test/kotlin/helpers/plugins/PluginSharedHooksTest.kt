package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.platform.PluginJvm
import desu.inugram.helpers.plugins.platform.PluginXposed
import desu.inugram.jvmfixture.JvmFixture
import java.lang.reflect.Member
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

class PluginSharedHooksTest {
    private val method = JvmFixture::class.java.getDeclaredMethod("computeHookSum", Int::class.java, Int::class.java)

    @Before fun setUp() { resetBridge(); JvmFixture.sharedHookCalls.set(0) }

    private fun createPlugin(name: String) = startPlugin(name, "unsafe.jvm", "unsafe.xposed")
    private fun getHandle(plugin: Plugin, value: Any): Long = PluginJvm.bridgeFor(plugin.js)!!.encode(value).substring(2).toLong()
    private fun invoke(plugin: Plugin, op: Int, target: Long, name: String = "", vararg args: String): String =
        plugin.js.listener!!.xposed(op, target, name, arrayOf(*args))
    private fun install(plugin: Plugin, target: Member = method): Long {
        val wire = invoke(plugin, PluginXposed.OP_HOOK, getHandle(plugin, target))
        assertTrue(wire.startsWith("S"), wire)
        val site = wire.substring(1).toLong()
        invoke(plugin, PluginXposed.OP_JS_BEFORES, site, "1")
        return site
    }

    @Test fun plugins_share_before_after_and_independent_disposal() {
        val first = createPlugin("first")
        val second = createPlugin("second")
        val observer = createPlugin("original caller")
        val order = arrayListOf<String>()
        first.js.onXposedBefore = { order.add("before first"); arrayOf("P1", "I5", "I2") }
        second.js.onXposedBefore = {
            order.add("before second")
            assertEquals(listOf<Any?>(5, 2), it.args)
            arrayOf("P1", "I5", "I7")
        }
        first.js.onXposedAfter = { order.add("after first"); assertEquals(20, it.result); "I21" }
        second.js.onXposedAfter = { order.add("after second"); assertEquals(12, it.result); "I20" }
        try {
            val firstSite = install(first)
            install(second)
            assertEquals(21, method.invoke(null, 1, 2))
            assertEquals(1, JvmFixture.sharedHookCalls.get())
            assertEquals(listOf("before first", "before second", "after second", "after first"), order)
            order.clear()
            assertEquals("I3", invoke(observer, PluginXposed.OP_CALL_ORIGINAL, getHandle(observer, method), "", "N", "I1", "I2"))
            assertTrue(order.isEmpty())
            invoke(first, PluginXposed.OP_UNHOOK, firstSite)
            assertTrue(PluginXposed.isHooked(method))
            second.js.onXposedBefore = { arrayOf("P1", "I1", "I2") }
            second.js.onXposedAfter = { "I30" }
            assertEquals(30, method.invoke(null, 1, 2))
            PluginXposed.detach(second.session!!)
            assertFalse(PluginXposed.isHooked(method))
            assertEquals(3, method.invoke(null, 1, 2))
        } finally {
            for (plugin in listOf(first, second, observer)) PluginXposed.detach(plugin.session!!)
        }
    }

    @Test fun native_and_js_plugins_share_one_original_call() {
        val first = createPlugin("js layer")
        val second = createPlugin("routine layer")
        first.js.onXposedBefore = { arrayOf("P1", "I4", "I2") }
        first.js.onXposedAfter = { assertEquals(21, it.result); "I22" }
        try {
            install(first)
            val before = second.js.listener!!.jvm(PluginJvm.OP_XPOSED_ROUTINE, 0, """{"nodes":[["hookArgument",[0,0]],["math","+",[1,0],[0,1]],["hookSetArgument",[0,0],[1,1]]],"roots":[2]}""", arrayOf("I0", "I5"))
            val after = second.js.listener!!.jvm(PluginJvm.OP_XPOSED_ROUTINE, 0, """{"nodes":[["hookResult"],["math","+",[1,0],[0,0]],["hookSetResult",[1,1]]],"roots":[2]}""", arrayOf("I10"))
            assertTrue(before.startsWith("GO"), before)
            assertTrue(after.startsWith("GO"), after)
            val phases = arrayOf("G" + before.substring(2), "G" + after.substring(2))
            val wire = invoke(second, PluginXposed.OP_HOOK, getHandle(second, method), "", *phases)
            assertTrue(wire.startsWith("S"), wire)
            invoke(second, PluginXposed.OP_NATIVE_ADD, wire.substring(1).toLong(), "native", *phases)
            assertEquals(22, method.invoke(null, 1, 2))
            assertEquals(1, JvmFixture.sharedHookCalls.get())
            assertTrue(second.js.xposedBefores.isEmpty())
            assertTrue(second.js.xposedAfters.isEmpty())
        } finally {
            PluginXposed.detach(first.session!!)
            PluginXposed.detach(second.session!!)
        }
    }

    @Test fun a_before_override_skips_later_plugins_and_the_original() {
        val first = createPlugin("override")
        val second = createPlugin("later")
        first.js.onXposedBefore = { arrayOf("A", "I99") }
        second.js.onXposedBefore = { error("later plugin must be skipped") }
        try {
            install(first)
            install(second)
            assertEquals(99, method.invoke(null, 1, 2))
            assertEquals(0, JvmFixture.sharedHookCalls.get())
            assertTrue(second.js.xposedBefores.isEmpty())
        } finally {
            PluginXposed.detach(first.session!!)
            PluginXposed.detach(second.session!!)
        }
    }

    @Test fun disposing_every_plugin_during_an_original_does_not_lose_the_outcome() {
        val first = createPlugin("closing first")
        val second = createPlugin("closing second")
        val target = JvmFixture::class.java.getDeclaredMethod("awaitCallbackRelease")
        first.js.onXposedBefore = { arrayOf("P1") }
        second.js.onXposedBefore = { arrayOf("P1") }
        JvmFixture.callbackEntered = CountDownLatch(1)
        JvmFixture.callbackRelease = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread { try { target.invoke(null) } catch (error: Throwable) { failure.set(error) } }
        try {
            install(first, target)
            install(second, target)
            worker.start()
            assertTrue(JvmFixture.callbackEntered!!.await(5, TimeUnit.SECONDS))
            PluginXposed.detach(first.session!!)
            assertTrue(PluginXposed.isHooked(target))
            PluginXposed.detach(second.session!!)
            assertFalse(PluginXposed.isHooked(target))
            JvmFixture.callbackRelease!!.countDown()
            worker.join(5000)
            assertFalse(worker.isAlive)
            assertEquals(null, failure.get())
            assertTrue(first.js.xposedAfters.isEmpty())
            assertTrue(second.js.xposedAfters.isEmpty())
        } finally {
            JvmFixture.callbackRelease!!.countDown()
            worker.join(5000)
            PluginXposed.detach(first.session!!)
            PluginXposed.detach(second.session!!)
        }
    }

    @Test fun unchanged_objects_keep_identity_across_plugins() {
        val first = createPlugin("object first")
        val second = createPlugin("object second")
        val target = JvmFixture::class.java.getDeclaredMethod("getPayload")
        first.js.onXposedBefore = { arrayOf("P1") }
        second.js.onXposedBefore = { arrayOf("P1") }
        val instance = JvmFixture()
        try {
            install(first, target)
            install(second, target)
            for (value in listOf(JvmFixture(), 42L, null)) {
                instance.payload = value
                assertSame(value, target.invoke(instance))
            }
        } finally {
            PluginXposed.detach(first.session!!)
            PluginXposed.detach(second.session!!)
        }
    }

    @Test fun call_original_constructor_bypasses_another_plugins_hook() {
        val first = createPlugin("constructor hook")
        val caller = createPlugin("constructor caller")
        val constructor = JvmFixture::class.java.getDeclaredConstructor()
        try {
            install(first, constructor)
            val result = invoke(caller, PluginXposed.OP_CALL_ORIGINAL, getHandle(caller, constructor), "", "N")
            assertTrue(result.startsWith("GO"), result)
            val instance = PluginJvm.bridgeFor(caller.js)!!.decode("G" + result.substring(2)) as JvmFixture
            assertEquals(3, instance.count)
            assertTrue(first.js.xposedBefores.isEmpty())
        } finally {
            PluginXposed.detach(first.session!!)
            PluginXposed.detach(caller.session!!)
        }
    }

    @Test fun last_disposal_in_before_still_calls_the_original_once() {
        val first = createPlugin("dispose first")
        val second = createPlugin("dispose second")
        try {
            val firstSite = install(first)
            val secondSite = install(second)
            first.js.onXposedBefore = {
                invoke(first, PluginXposed.OP_UNHOOK, firstSite)
                invoke(second, PluginXposed.OP_UNHOOK, secondSite)
                arrayOf("P1", "I1", "I2")
            }
            assertEquals(3, method.invoke(null, 1, 2))
            assertEquals(1, JvmFixture.sharedHookCalls.get())
            assertFalse(PluginXposed.isHooked(method))
            assertTrue(second.js.xposedBefores.isEmpty())
            assertTrue(first.js.xposedAfters.isEmpty())
        } finally {
            PluginXposed.detach(first.session!!)
            PluginXposed.detach(second.session!!)
        }
    }

    @Test fun the_original_recursing_into_itself_dispatches_every_level_to_every_plugin() {
        val first = createPlugin("recursion first")
        val second = createPlugin("recursion second")
        val target = Class.forName("desu.inugram.jvmfixture.JvmFixtureKt")
            .getDeclaredMethod("countHookDepth", Int::class.java)
        first.js.onXposedBefore = { arrayOf("P1", "=") }
        second.js.onXposedBefore = { arrayOf("P1", "=") }
        try {
            install(first, target)
            install(second, target)
            assertEquals(3, target.invoke(null, 3))
            assertEquals(4, first.js.xposedBefores.size)
            assertEquals(4, second.js.xposedBefores.size)
            assertEquals(4, first.js.xposedAfters.size)
        } finally {
            PluginXposed.detach(first.session!!)
            PluginXposed.detach(second.session!!)
        }
    }

    @Test fun an_invalid_after_result_preserves_the_outcome_for_previous_plugins() {
        val first = createPlugin("valid outer")
        val second = createPlugin("invalid inner")
        first.js.onXposedBefore = { arrayOf("P1", "I1", "I2") }
        second.js.onXposedBefore = { arrayOf("P1", "I1", "I2") }
        first.js.onXposedAfter = { assertEquals(3, it.result); "I4" }
        second.js.onXposedAfter = { "Snot an int" }
        try {
            install(first)
            install(second)
            assertEquals(4, method.invoke(null, 1, 2))
            assertEquals(1, JvmFixture.sharedHookCalls.get())
        } finally {
            PluginXposed.detach(first.session!!)
            PluginXposed.detach(second.session!!)
        }
    }

    @Test fun nested_methods_called_by_the_original_still_run_their_hooks() {
        val plugin = createPlugin("nested methods")
        val outer = JvmFixture::class.java.getDeclaredMethod("runNow", Runnable::class.java)
        val seen = arrayListOf<String>()
        try {
            val outerSite = install(plugin, outer)
            install(plugin)
            plugin.js.onXposedBefore = {
                seen.add(if (it.site == outerSite) "outer" else "inner")
                arrayOf("P1", *Array(it.args.size) { "=" })
            }
            val action = Thread { assertEquals(3, method.invoke(null, 1, 2)) }
            outer.invoke(JvmFixture(), action)
            assertEquals(listOf("outer", "inner"), seen)
            assertEquals(1, JvmFixture.sharedHookCalls.get())
        } finally {
            PluginXposed.detach(plugin.session!!)
        }
    }

    @Test fun two_real_engines_can_hook_the_same_method() {
        val plugins = listOf(createPlugin("real first"), createPlugin("real second"))
        val engines = plugins.map { plugin ->
            QuickJs().also { engine ->
                plugin.session = PluginSession(plugin, engine)
                attachBridge(plugin.session!!, object : CoreListener {
                    override fun onConsole(level: Int, message: String) = Unit
                    override fun onTimerSchedule(delayMs: Long) = Unit
                })
            }
        }
        try {
            engines.forEachIndexed { index, engine ->
                assertEquals("ready", engine.evaluate("""
                    const fixture = inu.jvm.cls('desu.inugram.jvmfixture.JvmFixture');
                    inu.xposed.hookMethod(fixture.getDeclaredMethod('computeHookSum(II)I'), {
                        before(ctx) { ctx.args[0] += ${index + 1}; },
                        after(ctx) { ctx.setReturnValue(ctx.returnValue * ${index + 2}); },
                    });
                    'ready';
                """.trimIndent()))
            }
            assertEquals(36, method.invoke(null, 1, 2))
            assertEquals(1, JvmFixture.sharedHookCalls.get())
            engines[0].stopCallbacks()
            PluginXposed.detach(plugins[0].session!!)
            PluginJvm.detach(plugins[0].session!!)
            engines[0].close()
            assertTrue(PluginXposed.isHooked(method))
            assertEquals(15, method.invoke(null, 1, 2))
        } finally {
            engines.forEachIndexed { index, engine ->
                engine.stopCallbacks()
                plugins[index].session?.let {
                    PluginXposed.detach(it)
                    PluginJvm.detach(it)
                }
                engine.close()
                plugins[index].session = null
            }
        }
    }
}
