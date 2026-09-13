package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.core.plugins.DispatchScheduler
import org.telegram.messenger.DispatchQueue
import org.telegram.messenger.Utilities

/**
 * The one rule every host callback into an engine obeys, in one place: a settle that crossed a
 * queue hop has to prove it is still talking to the engine it left from.
 *
 * A plugin that reloaded is running on a *different* engine whose request, invoke, callback and
 * menu ids all restart at 1, so a settle that crossed a queue hop has to prove the plugin is still
 * on the engine it left from - a missed check does not fail loudly, it resolves someone else's
 * pending promise with the wrong value.
 *
 * [onDropped] runs on the stale path, because some of these settles carry an obligation: a bitmap
 * to recycle, a response whose free stock suppressed, a fetched body's bytes.
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
     * [onEngine] for a request the host answers with one wire, into the [QuickJs.settle] table [api].
     * [produce] throwing is the host's own bad day rather than the plugin's, so it becomes an
     * `internal` error wire naming [what] rather than escaping onto the plugin queue. [release] runs
     * once whichever way it went: after the settle, or in its place when the plugin moved on.
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
