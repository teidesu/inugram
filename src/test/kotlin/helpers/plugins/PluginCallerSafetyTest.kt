package desu.inugram.helpers.plugins

import android.graphics.Bitmap
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

    @Test fun busy_runnable_returns_without_throwing_into_the_calling_thread() {
        val plugin = startEngine("busy-callback", "unsafe.jvm")
        val engine = plugin.engine!!
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
            closeEngine(plugin)
        }
        assertNull(failure.get())
    }

    @Test fun off_thread_canvas_gc_recycles_the_host_bitmap() {
        val plugin = startEngine("canvas-gc", "unsafe.jvm", canvas = true)
        val engine = plugin.engine!!
        val canvas = (engine.listener as PluginBridge).canvas
        try {
            engine.evaluate("""
                const fixture = inu.jvm.cls('desu.inugram.jvmfixture.JvmFixture');
                let surface = inu.canvas.create(8, 8);
                fixture.setStaticField('task', inu.jvm.runnable(() => { surface = null }));
            """.trimIndent())
            val surfaces = canvas.javaClass.getDeclaredField("canvases").apply { isAccessible = true }.get(canvas) as Map<*, *>
            val surface = surfaces.values.single()!!
            val bitmap = surface.javaClass.getDeclaredField("bitmap").apply { isAccessible = true }.get(surface) as Bitmap
            runOnCaller(JvmFixture.task!!)
            assertFalse(bitmap.isRecycled)
            drain()
            assertTrue(bitmap.isRecycled)
            assertEquals(0, surfaces.size)
        } finally {
            closeEngine(plugin)
        }
    }
}
