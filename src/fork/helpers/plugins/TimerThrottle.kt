package desu.inugram.helpers.plugins

import android.os.SystemClock
import org.telegram.messenger.Utilities

// the share of wall time one plugin's timer callbacks may take on globalQueue: a tick is followed by (100/this - 1) times its own cost
private const val TIMER_DUTY_PERCENT = 10

// what bounds a chain of ~free ticks, which no duty cycle can: browsers' nested-setTimeout clamp, and the wheel's own setInterval floor
private const val MIN_WAKE_GAP_MS = 4L

private const val NO_WAKE = -1L

/**
 * paces one engine's timer wakes on [Utilities.globalQueue]; nothing can ask for a wake before the
 * plugin's own code has run.
 *
 * Timers are the only thing a plugin can put on that queue without ever returning to the engine,
 * and the native deadline bounds one entry rather than their rate: `setTimeout(function f() {
 * setTimeout(f, 0) }, 0)` re-arms forever. So a tick is charged for what it cost and the next waits
 * out the difference.
 *
 * Here rather than in the wheel because it is not a statement about timers: the engine decides when
 * it *wants* waking, this decides when the host can afford it. A delayed wake only ever fires
 * later, never less, so it composes with the background floor by `max`.
 */
class TimerThrottle(private val plugin: Plugin, private val engine: QuickJs) {
    private val wake = Runnable { tick() }

    private var wantedAt = NO_WAKE

    private var readyAt = 0L

    fun schedule(delayMs: Long) {
        if (delayMs < 0) {
            wantedAt = NO_WAKE
            Utilities.globalQueue.cancelRunnable(wake)
            return
        }
        wantedAt = SystemClock.uptimeMillis() + delayMs
        post()
    }

    private fun post() {
        val at = maxOf(wantedAt, readyAt)
        Utilities.globalQueue.cancelRunnable(wake)
        Utilities.globalQueue.postRunnable(wake, maxOf(0L, at - SystemClock.uptimeMillis()))
    }

    private fun tick() {
        // after a reload the plugin runs on a new engine with its own wheel
        if (!EngineDispatch.isLive(plugin, engine)) return
        wantedAt = NO_WAKE
        val startedAt = System.nanoTime()
        try {
            engine.runTimers()
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
