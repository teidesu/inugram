package desu.inugram.core.plugins

interface DispatchScheduler {
    fun nowMillis(): Long
    fun postRunnable(task: Runnable) = postRunnable(task, 0)
    fun postRunnable(task: Runnable, delayMillis: Long)
    fun cancel(task: Runnable)
}

class DispatchDeadline(
    private val scheduler: DispatchScheduler,
    budgetMillis: Long,
    private val onExpired: () -> Unit,
) {
    private enum class State { PAUSED, RUNNING, FINISHED }

    private var state = State.PAUSED
    private var remaining = budgetMillis
    private var startedAt = 0L
    private var timer: Runnable? = null

    fun resume() {
        if (state != State.PAUSED) return
        state = State.RUNNING
        startedAt = scheduler.nowMillis()
        val task = object : Runnable {
            override fun run() {
                if (timer !== this || state != State.RUNNING) return
                timer = null
                state = State.FINISHED
                onExpired()
            }
        }
        timer = task
        scheduler.postRunnable(task, remaining.coerceAtLeast(0))
    }

    fun pause() {
        if (state != State.RUNNING) return
        remaining -= scheduler.nowMillis() - startedAt
        state = State.PAUSED
        timer?.let(scheduler::cancel)
        timer = null
    }

    fun cancel() {
        state = State.FINISHED
        timer?.let(scheduler::cancel)
        timer = null
    }
}
