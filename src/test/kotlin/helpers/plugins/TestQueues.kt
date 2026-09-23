package desu.inugram.helpers.plugins

import org.telegram.messenger.DispatchQueue
import org.telegram.messenger.Utilities

/**
 * Both queues share one (due time, post order) ordering, which is what a `Handler` gives and the
 * only cross-queue property the bridge may depend on. EngineDispatch reads the same clock.
 */
object TestQueues {
    private class Task(val runnable: Runnable, val due: Long, val seq: Long)

    private val pending = ArrayList<Task>()
    private var nextSeq = 1L
    private var offset = 0L
    private var testThread: Thread? = null

    private class Recording(label: String) : DispatchQueue(label, false) {
        override fun postRunnable(runnable: Runnable): Boolean = postRunnable(runnable, 0)

        override fun postRunnable(runnable: Runnable, delay: Long): Boolean {
            post(runnable, delay)
            return true
        }

        override fun cancelRunnable(runnable: Runnable) = cancel(runnable)

        override fun cancelRunnables(runnables: Array<Runnable>) = runnables.forEach { cancel(it) }
    }

    @Synchronized
    private fun now(): Long = android.os.SystemClock.uptimeMillis() + offset

    /**
     * stock tgnet's `onUpdate` posts `updateTimerProc` here from its own threads, which would send
     * `help.getPromoData` and friends mid-test, so stock-thread posts are dropped. Bridge threads
     * and the ui thread still post.
     */
    @Synchronized
    private fun post(runnable: Runnable, delay: Long) {
        val current = Thread.currentThread()
        val driven = current === testThread || current === android.os.Looper.getMainLooper().thread
        if (!driven && !runnable.javaClass.name.startsWith("desu.inugram.")) return
        pending.add(Task(runnable, now() + delay, nextSeq++))
    }

    @Synchronized
    private fun earliest(): Long? = pending.minOfOrNull { it.due }

    @Synchronized
    private fun advanceTo(target: Long) {
        offset += target - now()
    }

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
        EngineDispatch.scheduler = object : desu.inugram.core.plugins.DispatchScheduler {
            override fun nowMillis(): Long = now()
            override fun postRunnable(task: Runnable, delayMillis: Long) {
                TestQueues.post(task, delayMillis)
            }
            override fun cancel(task: Runnable) = TestQueues.cancel(task)
        }
        synchronized(this) {
            pending.clear()
            nextSeq = 1L
            offset = 0L
            testThread = Thread.currentThread()
        }
        Utilities.globalQueue = Recording("globalQueue")
        Utilities.stageQueue = Recording("stageQueue")
        Utilities.cacheClearQueue = Recording("cacheClearQueue")
    }

    fun drain(): Int {
        var ran = 0
        while (true) {
            val next = takeDue() ?: return ran
            next.runnable.run()
            ran++
        }
    }
}
