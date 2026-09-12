package desu.inugram.helpers.plugins

import android.graphics.Bitmap
import desu.inugram.helpers.plugins.platform.PluginJvm
import desu.inugram.helpers.plugins.ui.PluginCanvas
import desu.inugram.jvmfixture.JvmFixture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

class PluginCallerSafetyTest {
    @Before fun setUp() = resetBridge()

    private val core = object : CoreListener {
        override fun onConsole(level: Int, message: String) = Unit
        override fun onTimerSchedule(delayMs: Long) = Unit
    }

    @Test fun busy_runnable_returns_without_throwing_into_the_calling_thread() {
        val plugin = startPlugin("busy-callback", "unsafe.jvm")
        val engine = QuickJs()
        plugin.session = PluginSession(plugin, engine)
        attachBridge(plugin.session!!, core)
        val failure = AtomicReference<Throwable?>()
        JvmFixture.callbackEntered = CountDownLatch(1)
        JvmFixture.callbackRelease = CountDownLatch(1)
        var owner: Thread? = null
        try {
            engine.evaluate("""
                const fixture = inu.jvm.cls('desu.inugram.jvmfixture.JvmFixture');
                fixture.setStaticField('task', inu.jvm.runnable(() => fixture.callStatic('awaitCallbackRelease')));
            """.trimIndent())
            val task = JvmFixture.task!!
            owner = Thread {
                try { task.run() } catch (error: Throwable) { failure.set(error) }
            }.also { it.start() }
            assertTrue(JvmFixture.callbackEntered!!.await(5, TimeUnit.SECONDS))
            val contender = Thread {
                try { task.run() } catch (error: Throwable) { failure.set(error) }
            }
            contender.start()
            contender.join(2000)
            assertFalse(contender.isAlive)
            assertNull(failure.get())
        } finally {
            JvmFixture.callbackRelease!!.countDown()
            owner?.join(5000)
            engine.stopCallbacks()
            PluginJvm.detach(engine)
            engine.close()
            JvmFixture.task = null
            JvmFixture.callbackEntered = null
            JvmFixture.callbackRelease = null
            plugin.session = null
        }
        assertNull(failure.get())
    }

    @Test fun off_thread_canvas_gc_recycles_the_host_bitmap() {
        val plugin = startPlugin("canvas-gc", "unsafe.jvm")
        val engine = QuickJs()
        plugin.session = PluginSession(plugin, engine)
        val canvas = PluginCanvas.listenerFor(plugin.session!!)
        attachBridge(plugin.session!!, core, canvas)
        try {
            engine.evaluate("""
                const fixture = inu.jvm.cls('desu.inugram.jvmfixture.JvmFixture');
                let surface = inu.canvas.create(8, 8);
                fixture.setStaticField('task', inu.jvm.runnable(() => { surface = null }));
            """.trimIndent())
            val surfaces = canvas.javaClass.getDeclaredField("canvases").apply { isAccessible = true }.get(canvas) as Map<*, *>
            val surface = surfaces.values.single()!!
            val bitmap = surface.javaClass.getDeclaredField("bitmap").apply { isAccessible = true }.get(surface) as Bitmap
            val caller = Thread(JvmFixture.task!!)
            caller.start()
            caller.join(5000)
            assertFalse(caller.isAlive)
            assertFalse(bitmap.isRecycled)
            drain()
            assertTrue(bitmap.isRecycled)
            assertEquals(0, surfaces.size)
        } finally {
            engine.stopCallbacks()
            PluginCanvas.detach(engine)
            PluginJvm.detach(engine)
            engine.close()
            JvmFixture.task = null
            plugin.session = null
        }
    }
}
