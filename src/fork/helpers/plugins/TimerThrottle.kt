package desu.inugram.helpers.plugins

import android.os.SystemClock

// a tick is followed by (100/this - 1) times its own cost
private const val TIMER_DUTY_PERCENT = 10

// bounds chains of ~free ticks, which no duty cycle can: browsers' nested-setTimeout clamp
private const val MIN_WAKE_GAP_MS = 4L

private const val NO_WAKE = -1L

/**
 * The native deadline limits each entry, not frequency: a zero-delay timer can reschedule forever, so
 * each tick delays the next by its cost. Ticks are delayed, never dropped; combines with the
 * background floor via `max`.
 */
class TimerThrottle(private val session: PluginSession) {
    private val wake = Runnable { tick() }

    private var wantedAt = NO_WAKE

    private var readyAt = 0L

    fun schedule(delayMs: Long) {
        if (delayMs < 0) {
            wantedAt = NO_WAKE
            EngineDispatch.scheduler.cancel(wake)
            return
        }
        wantedAt = SystemClock.uptimeMillis() + delayMs
        post()
    }

    private fun post() {
        val at = maxOf(wantedAt, readyAt)
        EngineDispatch.scheduler.cancel(wake)
        EngineDispatch.scheduler.postRunnable(wake, maxOf(0L, at - SystemClock.uptimeMillis()))
    }

    private fun tick() {
        // after a reload the new engine has its own wheel
        if (!session.isCurrent()) return
        wantedAt = NO_WAKE
        val startedAt = System.nanoTime()
        try {
            session.engine.runTimers()
        } finally {
            readyAt = SystemClock.uptimeMillis() + cooldownMs(System.nanoTime() - startedAt)
            // re-armed from inside itself, before its cost was known
            if (wantedAt != NO_WAKE) post()
        }
    }

    private fun cooldownMs(busyNanos: Long): Long {
        val cooldown = busyNanos / TIMER_DUTY_PERCENT * (100 - TIMER_DUTY_PERCENT)
        return maxOf(MIN_WAKE_GAP_MS, (cooldown + 999_999L) / 1_000_000L)
    }
}
