package desu.inugram.core.plugins

import kotlin.test.assertEquals
import org.junit.Test

class DispatchDeadlineTest {
    private class Scheduler : DispatchScheduler {
        var now = 0L
        val tasks = LinkedHashMap<Runnable, Long>()
        override fun nowMillis(): Long = now
        override fun postRunnable(task: Runnable, delayMillis: Long) { tasks[task] = now + delayMillis }
        override fun cancel(task: Runnable) { tasks.remove(task) }

        fun advanceBy(millis: Long) {
            now += millis
            while (true) {
                val task = tasks.entries.firstOrNull { it.value <= now }?.key ?: return
                tasks.remove(task)
                task.run()
            }
        }
    }

    @Test fun suspended_time_does_not_consume_the_budget() {
        val scheduler = Scheduler()
        var expired = 0
        val deadline = DispatchDeadline(scheduler, 100) { expired++ }
        deadline.resume()
        scheduler.advanceBy(40)
        deadline.pause()
        scheduler.advanceBy(10_000)
        deadline.resume()
        scheduler.advanceBy(59)
        assertEquals(0, expired)
        scheduler.advanceBy(1)
        assertEquals(1, expired)
    }

    @Test fun cancellation_wins_over_a_dequeued_timeout_and_cannot_be_resumed() {
        val scheduler = Scheduler()
        var expired = 0
        val deadline = DispatchDeadline(scheduler, 100) { expired++ }
        deadline.resume()
        val dequeued = scheduler.tasks.keys.single()
        deadline.cancel()
        dequeued.run()
        deadline.resume()
        scheduler.advanceBy(1000)
        assertEquals(0, expired)
        assertEquals(0, scheduler.tasks.size)
    }

    @Test fun a_timeout_from_an_earlier_resume_cannot_expire_the_current_budget() {
        val scheduler = Scheduler()
        var expired = 0
        val deadline = DispatchDeadline(scheduler, 100) { expired++ }
        deadline.resume()
        val dequeued = scheduler.tasks.keys.single()
        scheduler.advanceBy(20)
        deadline.pause()
        scheduler.advanceBy(200)
        deadline.resume()
        dequeued.run()
        assertEquals(0, expired)
        scheduler.advanceBy(80)
        assertEquals(1, expired)
    }

    @Test fun expiry_is_terminal_even_if_the_callback_resumes_or_reenters() {
        val scheduler = Scheduler()
        var expired = 0
        lateinit var deadline: DispatchDeadline
        deadline = DispatchDeadline(scheduler, 100) { expired++; deadline.resume() }
        deadline.resume()
        val dequeued = scheduler.tasks.keys.single()
        scheduler.advanceBy(100)
        dequeued.run()
        scheduler.advanceBy(1000)
        assertEquals(1, expired)
    }
}
