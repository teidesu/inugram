package desu.inugram.helpers.plugins

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import desu.inugram.helpers.plugins.api.PluginKv
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

    /**
     * What a caller thread may reach without a hop: each of these holds no host state, guards its
     * own, or hands it to the dispatcher that posts to globalQueue. Everything else still refuses.
     */
    @Test fun the_calls_a_caller_thread_may_make_answer_on_it() {
        val plugin = startPlugin("caller-ui", "unsafe.jvm", "kv")
        val engine = QuickJs()
        plugin.engine = engine
        val logs = CopyOnWriteArrayList<String>()
        val toasts = CopyOnWriteArrayList<String>()
        val onHost = EngineDispatch.createHostDispatcher()
        val timers = TimerThrottle(plugin, engine)::schedule
        attachBridge(
            plugin,
            engine,
            object : CoreListener {
                override fun onConsole(level: Int, message: String) { logs.add(message) }
                override fun onTimerSchedule(delayMs: Long) = onHost { timers(delayMs) }
            },
            ui = object : UiListener by DeviceMissing {
                override fun uiToast(text: String) {
                    toasts.add("$text@${Thread.currentThread().name}")
                }
            },
            storage = PluginKv.listenerFor(plugin),
        )
        logs.clear()
        try {
            // `kv` answers a value, so it is one of the hosts the gate really did refuse; `toast`
            // is void and was never asked, and rides along only to show the void path still works
            engine.evaluate("""
                const fixture = inu.jvm.cls('desu.inugram.jvmfixture.JvmFixture');
                fixture.setStaticField('task', inu.jvm.runnable(() => {
                    inu.ui.toast('shown');
                    inu.kv.set('caller', 'written');
                    fixture.setStaticField('tag', inu.kv.get('caller') ?? 'missing');
                }));
            """.trimIndent())
            runOnCaller("ui-caller")
            assertEquals(listOf("shown@ui-caller"), toasts.toList())
            assertEquals("written", JvmFixture.tag)

            // the wake is armed from the caller thread; the callback still runs on globalQueue
            engine.evaluate("""
                fixture.setStaticField('tag', 'not yet');
                fixture.setStaticField('task', inu.jvm.runnable(() => {
                    setTimeout(() => fixture.setStaticField('tag', 'timer'), 0);
                }));
            """.trimIndent())
            runOnCaller("timer-caller")
            assertEquals("not yet", JvmFixture.tag, "the callback must not run on the caller thread")
            awaitTag("timer")
            assertEquals("timer", JvmFixture.tag)
            assertTrue(logs.isEmpty(), logs.toString())

            engine.evaluate("""
                fixture.setStaticField('task', inu.jvm.runnable(() => { inu.ui.getCurrentScreen(); }));
            """.trimIndent())
            runOnCaller("screen-caller")
            assertTrue(logs.any { it.contains("getCurrentScreen: this API requires globalQueue") }, logs.toString())
        } finally {
            engine.stopCallbacks()
            PluginJvm.detach(engine)
            engine.close()
            JvmFixture.task = null
            plugin.engine = null
        }
    }

    /** the wheel runs on real time: [TestQueues.advanceBy] moves the queue's clock and not rust's */
    private fun awaitTag(expected: String) {
        val until = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < until && JvmFixture.tag != expected) {
            drain()
            Thread.sleep(5)
        }
    }

    private fun runOnCaller(name: String) {
        val worker = Thread(JvmFixture.task!!, name)
        worker.start()
        worker.join(5000)
        assertTrue(!worker.isAlive)
    }
}
