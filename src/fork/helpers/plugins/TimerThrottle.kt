package desu.inugram.helpers.plugins

import android.os.SystemClock
import org.telegram.messenger.Utilities

// the share of wall time one plugin's timer callbacks may take on the plugin queue: a tick is followed by (100/this - 1) times its own cost
private const val TIMER_DUTY_PERCENT = 10

// what bounds a chain of ~free ticks, which no duty cycle can: browsers' nested-setTimeout clamp, and the wheel's own setInterval floor
private const val MIN_WAKE_GAP_MS = 4L

private const val NO_WAKE = -1L

/**
 * Paces one engine's timer wakes on [EngineDispatch.scheduler]. Wakes can only be requested
 * after plugin code has run.
 *
 * The native deadline limits each entry, not callback frequency. A zero-delay timer can keep
 * rescheduling itself indefinitely, so each tick delays the next in proportion to its cost.
 *
 * The native wheel decides when it wants a wake; the host applies this queue budget.
 * Throttling delays ticks without dropping them and combines with the background floor using `max`.
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
        // after a reload the plugin runs on a new engine with its own wheel
        if (!session.isCurrent()) return
        wantedAt = NO_WAKE
        val startedAt = System.nanoTime()
        try {
            session.engine.runTimers()
        } finally {
            readyAt = SystemClock.uptimeMillis() + cooldownMs(System.nanoTime() - startedAt)
            // the tick re-armed from inside itself, before its own cost was known
            if (wantedAt != NO_WAKE) post()
        }
    }

    private fun cooldownMs(busyNanos: Long): Long {
        val cooldown = busyNanos / TIMER_DUTY_PERCENT * (100 - TIMER_DUTY_PERCENT)
        return maxOf(MIN_WAKE_GAP_MS, (cooldown + 999_999L) / 1_000_000L)
    }
}
