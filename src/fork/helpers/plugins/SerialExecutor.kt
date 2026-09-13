package desu.inugram.helpers.plugins

import java.util.ArrayDeque
import java.util.concurrent.Executor

/** runs what it is given one at a time, in order, on [delegate]'s threads rather than on a thread of its own */
class SerialExecutor(private val delegate: Executor) : Executor {
    private val queued = ArrayDeque<Runnable>()
    private var running = false

    override fun execute(command: Runnable) {
        synchronized(queued) {
            queued.add(command)
            if (running) return
            running = true
        }
        delegate.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val next = synchronized(queued) {
                queued.poll() ?: run {
                    running = false
                    return
                }
            }
            try {
                next.run()
            } catch (e: Throwable) {
                val more = synchronized(queued) { queued.isNotEmpty().also { if (!it) running = false } }
                if (more) delegate.execute(::drain)
                throw e
            }
        }
    }
}
