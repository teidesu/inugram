package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.core.plugins.DispatchScheduler
import org.telegram.messenger.DispatchQueue
import org.telegram.messenger.Utilities

/**
 * Checks engine identity after host callbacks cross queues.
 *
 * Reloads create a new engine whose request, invoke, callback, and menu IDs restart at 1.
 * A stale callback could otherwise settle an unrelated promise on the replacement engine.
 *
 * [onDropped] releases resources carried by stale callbacks, such as bitmaps, fetched bodies,
 * and responses whose stock free was suppressed.
 */
internal object EngineDispatch {
    private val queue = DispatchQueue("inuPlugins")
    var scheduler: DispatchScheduler = object : DispatchScheduler {
        override fun nowMillis(): Long = android.os.SystemClock.uptimeMillis()
        override fun postRunnable(task: Runnable, delayMillis: Long) {
            queue.postRunnable(task, delayMillis)
        }
        override fun cancel(task: Runnable) = queue.cancelRunnable(task)
    }

    /** post [block] to the plugin queue, and run it only if the plugin is still on this engine */
    fun onEngine(session: PluginSession, onDropped: () -> Unit = {}, block: () -> Unit) {
        scheduler.postRunnable {
            if (session.isCurrent()) block() else onDropped()
        }
    }

    /** Host state stays on its creation queue; delayed work must still belong to the same engine. */
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

    /**
     * Settles a request in [QuickJs.settle]'s [api] table with one wire.
     * Exceptions from [produce] become `internal` errors naming [what].
     * [release] runs exactly once, after settlement or when a stale callback is dropped.
     */
    fun settle(
        session: PluginSession,
        api: Int,
        requestId: Long,
        what: String,
        release: () -> Unit = {},
        produce: () -> String,
    ) = onEngine(session, release) {
        try {
            session.engine.settle(api, requestId, wireOf(what, produce))
        } finally {
            release()
        }
    }

    fun wireOf(what: String, produce: () -> String): String = try {
        produce()
    } catch (e: Exception) {
        PluginWire.encodePluginError("internal", "$what: ${e.message ?: e.toString()}")
    }
}
