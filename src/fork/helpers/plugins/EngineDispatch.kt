package desu.inugram.helpers.plugins

import android.os.Handler
import android.os.Looper
import desu.inugram.core.plugins.DispatchScheduler
import desu.inugram.core.plugins.PluginRefusal
import desu.inugram.core.plugins.PluginWire
import java.util.concurrent.SynchronousQueue

/**
 * Reloads restart request, invoke, callback and menu ids at 1, so a stale callback could settle an
 * unrelated promise on the new engine. [onDropped] releases what stale callbacks carry.
 */
internal object EngineDispatch {
    /** quickjs fits its stack limit to what the thread has left on entry */
    private const val STACK_BYTES = 8L * 1024 * 1024

    private val handler: Handler by lazy { Handler(startLooper()) }

    private fun startLooper(): Looper {
        val ready = SynchronousQueue<Looper>()
        val thread = Thread(null, {
            Looper.prepare()
            ready.put(Looper.myLooper()!!)
            Looper.loop()
        }, "inuPlugins", STACK_BYTES)
        thread.start()
        return ready.take()
    }

    var scheduler: DispatchScheduler = object : DispatchScheduler {
        override fun nowMillis(): Long = android.os.SystemClock.uptimeMillis()
        override fun postRunnable(task: Runnable, delayMillis: Long) {
            if (delayMillis <= 0) handler.post(task) else handler.postDelayed(task, delayMillis)
        }
        override fun cancel(task: Runnable) = handler.removeCallbacks(task)
    }

    fun onEngine(session: PluginSession, onDropped: () -> Unit = {}, block: () -> Unit) {
        scheduler.postRunnable {
            if (session.isCurrent()) block() else onDropped()
        }
    }

    fun createHostDispatcher(isLive: () -> Boolean = { true }): (() -> Unit) -> Unit {
        val owner = Thread.currentThread()
        return { block ->
            if (Thread.currentThread() === owner) {
                if (isLive()) block()
            } else {
                scheduler.postRunnable { if (isLive()) block() }
            }
        }
    }

    /** [release] runs exactly once, after settlement or when a stale callback is dropped */
    fun settle(
        session: PluginSession,
        api: Int,
        requestId: Long,
        what: String,
        release: () -> Unit = {},
        produce: () -> String,
    ) = onEngine(session, release) {
        try {
            session.engine.settle(api, requestId, produceWire(what, produce))
        } finally {
            release()
        }
    }

    @Suppress("UNCHECKED_CAST")
    inline fun <T : String?> produceWire(what: String, produce: () -> T): T = try {
        produce()
    } catch (e: PluginRefusal) {
        e.wire as T
    } catch (e: Exception) {
        PluginWire.encodePluginError("internal", "$what: ${e.message ?: e.toString()}") as T
    }
}
