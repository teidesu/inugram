package desu.inugram.helpers.plugins

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
        val site = decodeString(plugin.xposed(PluginXposed.OP_HOOK, plugin.jvmHandle(method), "", before, after)).toLong()
        assertEquals("N", plugin.xposed(PluginXposed.OP_NATIVE_ADD, site, "1", before, after))
    }

    private fun createConsumer(plugin: Plugin, graph: String, vararg args: String): String {
        val wire = plugin.jvm(PluginJvm.OP_XPOSED_ROUTINE, 0, graph, *args)
        assertTrue(wire.startsWith("GO"), wire)
        val input = "G" + wire.substring(2)
        assertTrue(PluginJvm.bridgeFor(plugin.js)!!.decode(input) is Consumer<*>)
        return input
    }

    @Test fun a_before_consumer_can_return_null_and_an_after_consumer_can_recover_an_exception() {
        val plugin = startPlugin("nullable-consumer", "unsafe.jvm", "unsafe.xposed")
        val fixture = JvmFixture()
        val nullResult = createConsumer(plugin, """{"v":1,"slots":0,"tries":[],"code":[["capture",0],["setResult",0]]}""", "N")
        val echo = JvmFixture::class.java.getDeclaredMethod("echo", String::class.java)
        install(plugin, echo, before = nullResult)
        assertEquals(null, echo.invoke(fixture, "text"))
        val recover = createConsumer(plugin, """{"v":1,"slots":0,"tries":[],"code":[["capture",0],["throwable"],["jumpIfFalsy",1,3],["setResult",0]]}""", "Srecovered")
        val boom = JvmFixture::class.java.getDeclaredMethod("boom")
        install(plugin, boom, after = recover)
        assertEquals("recovered", boom.invoke(fixture))
    }

    @Test fun argument_reads_the_bridge_refuses_stop_the_consumer() {
        val plugin = startPlugin("consumer", "unsafe.jvm", "unsafe.xposed")
        val before = createConsumer(plugin, """{"v":1,"slots":0,"tries":[],"code":[["capture",0],["capture",1],["arg",0],["setResult",1]]}""", "I0", "Sblocked")
        val method = JvmFixture::class.java.getDeclaredMethod("boxed", Any::class.java)
        install(plugin, method, before = before)
        val oversized = "x".repeat(PluginJvm.VALUE_LIMIT_BYTES + 1)
        assertEquals("java.lang.String", method.invoke(JvmFixture(), oversized))
    }

    @Test fun consumers_can_set_and_clear_throwables() {
        val plugin = startPlugin("throwing-consumer", "unsafe.jvm", "unsafe.xposed")
        val exception = IllegalStateException("blocked")
        val wire = plugin.jvmWire(exception)
        val before = createConsumer(plugin, """{"v":1,"slots":0,"tries":[],"code":[["capture",0],["setThrowable",0]]}""", wire)
        val after = createConsumer(plugin, """{"v":1,"slots":0,"tries":[],"code":[["capture",0],["throwable"],["jumpIfFalsy",1,3],["setResult",0]]}""", "I7")
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
