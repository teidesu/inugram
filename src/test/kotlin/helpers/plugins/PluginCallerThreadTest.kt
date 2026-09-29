package desu.inugram.helpers.plugins

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import desu.inugram.helpers.plugins.io.PluginLocalStorage
import desu.inugram.jvmfixture.JvmFixture
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

class PluginCallerThreadTest {
    @Before fun setUp() = resetBridge()

    @Test fun real_js_runnable_keeps_its_closure_and_executes_on_the_caller() {
        val logs = CopyOnWriteArrayList<String>()
        val plugin = startEngine("caller-thread", "unsafe.jvm", "unsafe.xposed") { logs.add(it) }
        val engine = plugin.engine!!
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
                runOnCaller(task, "caller-$index")
                assertEquals("$index:caller-$index", JvmFixture.tag)
            }
            engine.evaluate("fixture.callStatic('make').call('runNow', task)")
            assertEquals("4:${Thread.currentThread().name}", JvmFixture.tag)
            engine.evaluate("""
                inu.xposed.hookMethod(fixture.getDeclaredMethod('sum(II)I'), {
                    before(ctx) {
                        fixture.setStaticField('tag', thread.callStatic('currentThread').call('getName'));
                        ctx.setReturnValue(17);
                    }
                });
            """.trimIndent())
            val hookResult = AtomicReference<Any?>()
            runOnCaller({
                hookResult.set(JvmFixture::class.java.getDeclaredMethod("sum", Int::class.java, Int::class.java).invoke(null, 1, 2))
            }, "hook-caller")
            assertEquals("hook-caller", JvmFixture.tag)
            assertEquals(17, hookResult.get())
            engine.evaluate("""
                fixture.setStaticField('task', inu.jvm.runnable(() => {
                    Promise.resolve().then(() => fixture.setStaticField('tag', 'microtask'));
                }));
            """.trimIndent())
            runOnCaller(JvmFixture.task!!)
            assertEquals("hook-caller", JvmFixture.tag)
            drain()
            assertEquals("microtask", JvmFixture.tag)
            engine.stopCallbacks()
            task.run()
            assertEquals("microtask", JvmFixture.tag)
            assertTrue(logs.isEmpty(), logs.toString())
        } finally {
            closeEngine(plugin)
        }
    }

    @Test fun the_calls_a_caller_thread_may_make_answer_on_it() {
        val plugin = startPlugin("caller-ui", "unsafe.jvm")
        val engine = QuickJs()
        plugin.session = PluginSession(plugin, engine)
        val logs = CopyOnWriteArrayList<String>()
        val toasts = CopyOnWriteArrayList<String>()
        val onHost = EngineDispatch.createHostDispatcher()
        val timers = TimerThrottle(plugin.session!!)::schedule
        attachBridge(
            plugin.session!!,
            object : CoreListener {
                override fun onConsole(level: Int, message: String) { logs.add(message) }
                override fun onTimerSchedule(delayMs: Long) = onHost { timers(delayMs) }
            },
            ui = object : UiListener by DeviceMissing {
                override fun uiToast(text: String) {
                    toasts.add("$text@${Thread.currentThread().name}")
                }
            },
            localStoragePath = PluginLocalStorage.pathFor(plugin.id),
        )
        logs.clear()
        try {
            engine.evaluate("""
                const fixture = inu.jvm.cls('desu.inugram.jvmfixture.JvmFixture');
                fixture.setStaticField('task', inu.jvm.runnable(() => {
                    inu.ui.toast('shown');
                    localStorage.setItem('caller', 'written');
                    fixture.setStaticField('tag', localStorage.getItem('caller') ?? 'missing');
                }));
            """.trimIndent())
            runOnCaller(JvmFixture.task!!, "ui-caller")
            assertEquals(listOf("shown@ui-caller"), toasts.toList())
            assertEquals("written", JvmFixture.tag)

            engine.evaluate("""
                fixture.setStaticField('tag', 'not yet');
                fixture.setStaticField('task', inu.jvm.runnable(() => {
                    setTimeout(() => fixture.setStaticField('tag', 'timer'), 0);
                }));
            """.trimIndent())
            runOnCaller(JvmFixture.task!!, "timer-caller")
            assertEquals("not yet", JvmFixture.tag, "the callback must not run on the caller thread")
            awaitTag("timer")
            assertEquals("timer", JvmFixture.tag)
            assertTrue(logs.isEmpty(), logs.toString())

            engine.evaluate("""
                fixture.setStaticField('task', inu.jvm.runnable(() => { inu.ui.getCurrentScreen(); }));
            """.trimIndent())
            runOnCaller(JvmFixture.task!!, "screen-caller")
            assertTrue(logs.any { it.contains("getCurrentScreen: this API requires the plugin queue") }, logs.toString())
        } finally {
            closeEngine(plugin)
            PluginLocalStorage.wipe(plugin.id)
        }
    }

    /** rust's timer wheel runs on real time, not the queue clock */
    private fun awaitTag(expected: String) {
        val until = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < until && JvmFixture.tag != expected) {
            drain()
            Thread.sleep(5)
        }
    }
}
