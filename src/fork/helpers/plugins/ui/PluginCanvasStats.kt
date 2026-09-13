package desu.inugram.helpers.plugins.ui

import android.util.Log
import java.util.TreeMap

/**
 * Debug instrumentation for the animation pipeline: nanosecond accumulators per stage, summed
 * across threads, dumped to logcat under [TAG] when a pipeline object is done. Not for release.
 */
internal class PluginCanvasStats(private val what: String) {
    private class Stage {
        var nanos = 0L
        var count = 0
        var maxNanos = 0L
    }

    private val stages = TreeMap<String, Stage>()

    inline fun <T> time(stage: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            add(stage, System.nanoTime() - start)
        }
    }

    fun add(stage: String, nanos: Long) {
        synchronized(stages) {
            val entry = stages.getOrPut(stage) { Stage() }
            entry.nanos += nanos
            entry.count++
            if (nanos > entry.maxNanos) entry.maxNanos = nanos
        }
    }

    fun dump() {
        val lines = synchronized(stages) {
            stages.entries.map { (name, stage) ->
                "%s total=%.1fms n=%d avg=%.2fms max=%.2fms".format(
                    name,
                    stage.nanos / 1e6,
                    stage.count,
                    stage.nanos / 1e6 / stage.count.coerceAtLeast(1),
                    stage.maxNanos / 1e6,
                )
            }
        }
        Log.d(TAG, "$what:")
        for (line in lines) Log.d(TAG, "  $line")
    }

    companion object {
        const val TAG = "InuCanvasStats"
    }
}
