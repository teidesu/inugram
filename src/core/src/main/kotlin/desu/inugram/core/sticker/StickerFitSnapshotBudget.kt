package desu.inugram.core.sticker

import kotlin.math.max

/** Bounds native-resolution snapshots before allocating masks or temporary ARGB rasters. */
class StickerFitSnapshotBudget(
    private val maximumBytes: Long,
    private val maximumPixels: Long = 8L * 1024 * 1024,
) {
    companion object {
        const val COPY_PIXELS = 16 * 1024
    }

    private var retainedPixels = 0L

    init {
        require(maximumBytes >= 0 && maximumPixels in 0..Int.MAX_VALUE.toLong())
    }

    fun reserve(width: Int, height: Int, render: Boolean): Int {
        require(width > 0 && height > 0)
        val pixels = width.toLong() * height
        check(pixels <= maximumPixels - retainedPixels) { "Sticker snapshot exceeds its pixel budget" }
        val retained = retainedPixels + pixels
        val rasterBytes = if (render) pixels * 4 else 0L
        // Every retained mask is one byte per pixel. Speck analysis needs at most
        // one more byte per pixel, and the UI copy uses one reusable IntArray.
        val peakBytes = max(retained + rasterBytes, retained * 2) + COPY_PIXELS * 4L
        check(peakBytes <= maximumBytes) {
            "Sticker snapshot exceeds its memory budget"
        }
        retainedPixels = retained
        return pixels.toInt()
    }
}
