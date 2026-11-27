package desu.inugram.core.sticker

import java.util.concurrent.atomic.AtomicInteger

/** UI-owned cache state; workers only read the revision to skip obsolete calculations. */
class StickerFitState {
    private val revision = AtomicInteger()
    private var key: List<Any?>? = null
    var support: List<StickerFit.Point>? = null
        private set
    var busy = false
        private set
    var failed = false
        private set
    val ready get() = !busy && (failed || support?.isNotEmpty() == true)

    fun invalidate() {
        revision.incrementAndGet()
        key = null
        support = null
        busy = false
        failed = false
    }

    fun begin(nextKey: List<Any?>): Int? {
        if (!needsAnalysis(nextKey)) return null
        key = nextKey
        support = null
        busy = true
        failed = false
        return revision.incrementAndGet()
    }

    /** Layout-only notifications must keep completed geometry and its visible controls intact. */
    fun needsAnalysis(nextKey: List<Any?>) = key != nextKey

    fun isCurrent(token: Int) = token == revision.get()

    fun complete(token: Int, result: Result<List<StickerFit.Point>>): Boolean {
        if (!isCurrent(token)) return false
        busy = false
        result.onSuccess { support = it }.onFailure { failed = true }
        return true
    }
}
