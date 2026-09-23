package desu.inugram.helpers.plugins

import desu.inugram.jvmfixture.JvmFixture
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

class PluginAsyncUnloadTest {
    @Before fun setUp() = resetBridge()

    @Test fun cleanup_runnable_resolves_unload_after_the_original_entry_returns() {
        val plugin = startEngine("async cleanup", "unsafe.jvm")
        val engine = plugin.engine!!
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
            runOnCaller(cleanup)
            assertEquals("cleaned", JvmFixture.tag)
            assertTrue(engine.pollUnload())
            JvmFixture.tag = "after"
            cleanup.run()
            assertEquals("after", JvmFixture.tag)
        } finally {
            closeEngine(plugin)
        }
    }
}
