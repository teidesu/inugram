package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.platform.PluginHookContext
import desu.inugram.helpers.plugins.platform.PluginJvm
import desu.inugram.helpers.plugins.platform.PluginXposed
import desu.inugram.jvmfixture.JvmFixture
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.function.Consumer

class PluginXposedConsumerTest {
    @Before fun setUp() = resetBridge()

    private fun install(plugin: Plugin, method: java.lang.reflect.Method, before: String = "N", after: String = "N") {
        val bridge = PluginJvm.bridgeFor(plugin.js)!!
        val id = bridge.encode(method).substring(2).toLong()
        val wire = plugin.js.listener!!.xposed(PluginXposed.OP_HOOK, id, "", arrayOf(before, after))
        val site = (PluginWire.decode(wire) as PluginWire.Value.Str).value.toLong()
        assertEquals("N", plugin.js.listener!!.xposed(PluginXposed.OP_NATIVE_ADD, site, "1", arrayOf(before, after)))
    }

    private fun createConsumer(plugin: Plugin, graph: String, vararg args: String): String {
        val wire = plugin.js.listener!!.jvm(PluginJvm.OP_XPOSED_ROUTINE, 0, graph, arrayOf(*args))
        assertTrue(wire.startsWith("GO"), wire)
        val input = "G" + wire.substring(2)
        assertTrue(PluginJvm.bridgeFor(plugin.js)!!.decode(input) is Consumer<*>)
        return input
    }

    @Test fun consumers_edit_arguments_and_replace_the_original_result_without_js() {
        val plugin = startPlugin("consumer", "unsafe.jvm", "unsafe.xposed")
        val before = createConsumer(plugin, """{"nodes":[["hookArgument",[0,0]],["math","+",[1,0],[0,1]],["hookSetArgument",[0,0],[1,1]]],"roots":[2]}""", "I0", "I9")
        val after = createConsumer(plugin, """{"nodes":[["hookResult"],["math","+",[1,0],[0,0]],["hookSetResult",[1,1]]],"roots":[2]}""", "I1")
        val method = JvmFixture::class.java.getDeclaredMethod("sum", Int::class.java, Int::class.java)
        install(plugin, method, before, after)
        assertEquals(13, method.invoke(null, 1, 2))
        assertTrue(plugin.js.xposedBefores.isEmpty())
        assertTrue(plugin.js.xposedAfters.isEmpty())
        PluginXposed.detach(plugin.js)
        assertEquals(3, method.invoke(null, 1, 2))
    }

    @Test fun a_before_consumer_can_return_null_and_an_after_consumer_can_recover_an_exception() {
        val plugin = startPlugin("nullable-consumer", "unsafe.jvm", "unsafe.xposed")
        val fixture = JvmFixture()
        val nullResult = createConsumer(plugin, """{"nodes":[["hookSetResult",[0,0]]],"roots":[0]}""", "N")
        val echo = JvmFixture::class.java.getDeclaredMethod("echo", String::class.java)
        install(plugin, echo, before = nullResult)
        assertEquals(null, echo.invoke(fixture, "text"))
        val recover = createConsumer(plugin, """{"nodes":[["hookThrowable"],["hookSetResult",[0,0]],["when",[1,0],[1],[]]],"roots":[2]}""", "Srecovered")
        val boom = JvmFixture::class.java.getDeclaredMethod("boom")
        install(plugin, boom, after = recover)
        assertEquals("recovered", boom.invoke(fixture))
    }

    @Test fun argument_reads_enforce_runtime_class_scopes() {
        val plugin = startPlugin("scoped-consumer", "unsafe.jvm(desu.inugram.jvmfixture.*)", "unsafe.xposed")
        val before = createConsumer(plugin, """{"nodes":[["hookArgument",[0,0]],["hookSetResult",[0,1]]],"roots":[0,1]}""", "I0", "Sblocked")
        val method = JvmFixture::class.java.getDeclaredMethod("boxed", Any::class.java)
        install(plugin, method, before = before)
        assertEquals("java.util.ArrayList", method.invoke(JvmFixture(), ArrayList<String>()))
    }

    @Test fun consumers_can_set_and_clear_throwables() {
        val plugin = startPlugin("throwing-consumer", "unsafe.jvm", "unsafe.xposed")
        val exception = IllegalStateException("blocked")
        val wire = "G" + PluginJvm.bridgeFor(plugin.js)!!.encode(exception).substring(2)
        val before = createConsumer(plugin, """{"nodes":[["hookSetThrowable",[0,0]]],"roots":[0]}""", wire)
        val after = createConsumer(plugin, """{"nodes":[["hookThrowable"],["hookSetResult",[0,0]],["when",[1,0],[1],[]]],"roots":[2]}""", "I7")
        val method = JvmFixture::class.java.getDeclaredMethod("sum", Int::class.java, Int::class.java)
        install(plugin, method, before, after)
        assertEquals(7, method.invoke(null, 1, 2))
    }

    @Test fun context_validates_types_and_expires_after_the_invocation() {
        val method = JvmFixture::class.java.getDeclaredMethod("sum", Int::class.java, Int::class.java)
        val context = PluginHookContext(method, null, mutableListOf(1, 2))
        assertFailsWith<IllegalArgumentException> { context.setReturnValue(null) }
        assertFailsWith<IllegalArgumentException> { context.setArgument(2, 1) }
        context.setThrowable(IllegalStateException("blocked"))
        assertTrue(context.getThrowable() is IllegalStateException)
        context.setReturnValue(7L)
        assertEquals(7, context.getReturnValue())
        assertEquals(null, context.getThrowable())
        context.close()
        assertFailsWith<IllegalStateException> { context.getReturnValue() }
    }
}
