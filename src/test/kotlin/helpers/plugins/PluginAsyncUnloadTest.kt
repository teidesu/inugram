package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.platform.PluginJvm
import desu.inugram.jvmfixture.JvmFixture
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

class PluginAsyncUnloadTest {
    @Before fun setUp() = resetBridge()

    @Test fun cleanup_runnable_resolves_unload_after_the_original_entry_returns() {
        val plugin = startPlugin("async cleanup", "unsafe.jvm")
        val engine = QuickJs()
        plugin.engine = engine
        attachBridge(plugin, engine, object : CoreListener {
            override fun onConsole(level: Int, message: String) = Unit
            override fun onTimerSchedule(delayMs: Long) = Unit
        })
        try {
            assertEquals("ready", engine.evaluate("""
                const fixture = inu.jvm.cls('desu.inugram.jvmfixture.JvmFixture');
                fixture.setStaticField('tag', 'before');
                inu.onUnload(() => new Promise(resolve => {
                    fixture.setStaticField('task', inu.jvm.runnable(() => {
                        fixture.setStaticField('tag', 'cleaned');
                        resolve();
                    }));
                }));
                'ready';
            """.trimIndent()))
            engine.stopCallbacks()
            engine.notifyUnload()
            assertFalse(engine.pollUnload())
            val cleanup = JvmFixture.task!!
            val caller = Thread(cleanup, "cleanup-caller")
            caller.start()
            caller.join(5000)
            assertFalse(caller.isAlive)
            assertEquals("cleaned", JvmFixture.tag)
            assertTrue(engine.pollUnload())
            JvmFixture.tag = "after"
            cleanup.run()
            assertEquals("after", JvmFixture.tag)
        } finally {
            engine.stopCallbacks()
            PluginJvm.detach(engine)
            engine.close()
            JvmFixture.task = null
            plugin.engine = null
        }
    }
}
