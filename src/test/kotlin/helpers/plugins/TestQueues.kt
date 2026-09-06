package desu.inugram.helpers.plugins

import org.telegram.messenger.DispatchQueue
import org.telegram.messenger.Utilities

/**
 * The app's two dispatch queues, made deterministic for the length of a test.
 *
 * [Utilities.globalQueue]/[Utilities.stageQueue] are `volatile` and not `final`, so a test swaps its
 * own in and puts the app's back in `@After`. Both share one ordering - (due time, then post order),
 * which is what a `Handler` gives a single queue and the only cross-queue property the bridge may
 * depend on - and nothing runs until [drain].
 *
 * Time is the device's real [android.os.SystemClock] plus an offset [advanceBy] moves, so a test can
 * reach a timeout without waiting for it. The offset is **this queue's alone**: `PluginRpc` reads
 * `SystemClock.uptimeMillis()` directly for the budget it suspends across a passthrough, and that
 * arithmetic sees real elapsed time here rather than the jump. So advancing decides *what fires*,
 * never how much budget a resumed chain is left with.
 */
object TestQueues {
    private class Task(val runnable: Runnable, val due: Long, val seq: Long, val queue: String)

    private val pending = ArrayList<Task>()
    private var nextSeq = 1L
    private var offset = 0L
    private var saved: List<DispatchQueue>? = null
    private var testThread: Thread? = null

    private class Recording(private val label: String) : DispatchQueue(label, false) {
        override fun postRunnable(runnable: Runnable): Boolean = postRunnable(runnable, 0)

        override fun postRunnable(runnable: Runnable, delay: Long): Boolean {
            post(runnable, delay, label)
            return true
        }

        override fun cancelRunnable(runnable: Runnable) = cancel(runnable)

        override fun cancelRunnables(runnables: Array<Runnable>) = runnables.forEach { cancel(it) }
    }

    @Synchronized
    private fun now(): Long = android.os.SystemClock.uptimeMillis() + offset

    /**
     * The app's own networking is **live in this process**: the real tgnet library is loaded, and
     * its `onUpdate` callback posts `MessagesController.updateTimerProc` here from stock's own
     * thread. Drained like anything else, that sends `help.getPromoData` and friends into the
     * recording [org.telegram.tgnet.ConnectionsManager] partway through whichever test is running,
     * where `lastSent()` then answers the app's request instead of the plugin's.
     *
     * So work stock posts from stock's own threads is dropped. Both halves of that are needed: the
     * bridge itself can post here from threads a test does not run on, and a test drives the ui thread
     * deliberately through `runOnMainSync`, so neither of those may be dropped.
     */
    @Synchronized
    private fun post(runnable: Runnable, delay: Long, queue: String) {
        val current = Thread.currentThread()
        val driven = current === testThread || current === android.os.Looper.getMainLooper().thread
        if (!driven && !runnable.javaClass.name.startsWith("desu.inugram.")) return
        pending.add(Task(runnable, now() + delay, nextSeq++, queue))
    }

    @Synchronized
    private fun earliest(): Long? = pending.minOfOrNull { it.due }

    @Synchronized
    private fun advanceTo(target: Long) {
        offset += target - now()
    }

    /**
     * steps to each due time in turn rather than jumping the whole way, so a timer armed by a
     * runnable that itself came due fires in the order the app would have seen it.
     */
    fun advanceBy(millis: Long) {
        val target = synchronized(this) { now() + millis }
        while (true) {
            drain()
            val next = earliest()
            if (next == null || next > target) {
                advanceTo(target)
                drain()
                return
            }
            advanceTo(maxOf(next, synchronized(this) { now() }))
        }
    }

    @Synchronized
    private fun cancel(runnable: Runnable) {
        pending.removeAll { it.runnable === runnable }
    }

    @Synchronized
    private fun takeDue(): Task? {
        val now = now()
        val next = pending.filter { it.due <= now }.minWithOrNull(compareBy({ it.due }, { it.seq })) ?: return null
        pending.remove(next)
        return next
    }

    fun install() {
        if (saved == null) {
            saved = listOf(Utilities.globalQueue, Utilities.stageQueue, Utilities.cacheClearQueue)
        }
        synchronized(this) {
            pending.clear()
            nextSeq = 1L
            offset = 0L
            testThread = Thread.currentThread()
        }
        Utilities.globalQueue = Recording("globalQueue")
        Utilities.stageQueue = Recording("stageQueue")
        // `PluginBlobs.scheduleSweep` posts here rather than to globalQueue: the sweep is an
        // unbounded recursive delete. Left real, it would race the assertion off another thread.
        Utilities.cacheClearQueue = Recording("cacheClearQueue")
    }

    fun restore() {
        saved?.let { (global, stage, cacheClear) ->
            Utilities.globalQueue = global
            Utilities.stageQueue = stage
            Utilities.cacheClearQueue = cacheClear
        }
        saved = null
        synchronized(this) { pending.clear() }
    }

    /** runs everything already due, including what those runnables post, without waiting */
    fun drain(): Int {
        var ran = 0
        while (true) {
            val next = takeDue() ?: return ran
            next.runnable.run()
            ran++
        }
    }

    @Synchronized
    fun pendingQueues(): List<String> = pending.map { it.queue }
}
