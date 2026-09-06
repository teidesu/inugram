package desu.inugram.helpers.plugins

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

class EngineDispatchTest {
    @Before fun setUp() = resetBridge()

    @Test fun host_dispatch_preserves_owner_thread_and_drops_stale_work() {
        val plugin = startPlugin("host-dispatch")
        val engine = plugin.engine!!
        val onHost = EngineDispatch.createHostDispatcher { EngineDispatch.isLive(plugin, engine) }
        val owner = Thread.currentThread()
        val seen = ArrayList<Thread>()
        onHost { seen.add(Thread.currentThread()) }
        assertEquals(listOf(owner), seen)
        val caller = Thread { onHost { seen.add(Thread.currentThread()) } }
        caller.start()
        caller.join(5000)
        assertTrue(!caller.isAlive)
        assertEquals(listOf(owner), seen)
        drain()
        assertEquals(listOf(owner, owner), seen)
        val stale = Thread { onHost { seen.add(Thread.currentThread()) } }
        stale.start()
        stale.join(5000)
        assertTrue(!stale.isAlive)
        plugin.engine = RecordingQuickJs()
        drain()
        assertEquals(listOf(owner, owner), seen)
    }
}
