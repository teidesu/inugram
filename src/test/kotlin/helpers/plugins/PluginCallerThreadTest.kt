package desu.inugram.helpers.plugins

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import desu.inugram.helpers.plugins.platform.PluginJvm
import desu.inugram.helpers.plugins.platform.PluginXposed
import desu.inugram.jvmfixture.JvmFixture
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

class PluginCallerThreadTest {
    @Before fun setUp() = resetBridge()

    @Test fun real_js_runnable_keeps_its_closure_and_executes_on_the_caller() {
        val plugin = startPlugin("caller-thread", "unsafe.jvm", "unsafe.xposed")
        val engine = QuickJs()
        plugin.engine = engine
        val logs = CopyOnWriteArrayList<String>()
        attachBridge(plugin, engine, object : CoreListener {
            override fun onConsole(level: Int, message: String) { logs.add(message) }
            override fun onTimerSchedule(delayMs: Long) = Unit
        })
        logs.clear()
        try {
            engine.evaluate("""
                const fixture = inu.jvm.cls('desu.inugram.jvmfixture.JvmFixture');
                const thread = inu.jvm.cls('java.lang.Thread');
                let count = 0;
                const task = inu.jvm.runnable(() => {
                    fixture.setStaticField('tag', (++count) + ':' + thread.callStatic('currentThread').call('getName'));
                });
                fixture.setStaticField('task', task);
            """.trimIndent())
            val task = JvmFixture.task!!
            for (index in 1..3) {
                val worker = Thread(task, "caller-$index")
                worker.start()
                worker.join(5000)
                assertTrue(!worker.isAlive)
                assertEquals("$index:caller-$index", JvmFixture.tag)
            }
            engine.evaluate("""
                let rejected = false;
                try { fixture.callStatic('make').call('runNow', task); }
                catch (e) { rejected = String(e).includes('re-entered'); }
                if (!rejected) throw new Error('recursive JNI entry must be rejected');
            """.trimIndent())
            engine.evaluate("""
                inu.xposed.hookMethod(fixture.getDeclaredMethod('sum(II)I'), {
                    before(ctx) {
                        fixture.setStaticField('tag', thread.callStatic('currentThread').call('getName'));
                        ctx.setReturnValue(17);
                    }
                });
            """.trimIndent())
            val hookResult = AtomicReference<Any?>()
            val hookCaller = Thread({
                hookResult.set(JvmFixture::class.java.getDeclaredMethod("sum", Int::class.java, Int::class.java).invoke(null, 1, 2))
            }, "hook-caller")
            hookCaller.start()
            hookCaller.join(5000)
            assertTrue(!hookCaller.isAlive)
            assertEquals("hook-caller", JvmFixture.tag)
            assertEquals(17, hookResult.get())
            engine.evaluate("""
                fixture.setStaticField('task', inu.jvm.runnable(() => {
                    Promise.resolve().then(() => fixture.setStaticField('tag', 'microtask'));
                }));
            """.trimIndent())
            val promiseCaller = Thread(JvmFixture.task!!)
            promiseCaller.start()
            promiseCaller.join(5000)
            assertTrue(!promiseCaller.isAlive)
            assertEquals("hook-caller", JvmFixture.tag)
            drain()
            assertEquals("microtask", JvmFixture.tag)
            engine.stopCallbacks()
            task.run()
            assertEquals("microtask", JvmFixture.tag)
            assertTrue(logs.isEmpty(), logs.toString())
        } finally {
            engine.stopCallbacks()
            PluginXposed.detach(engine)
            PluginJvm.detach(engine)
            engine.close()
            JvmFixture.task = null
            plugin.engine = null
        }
    }
}
