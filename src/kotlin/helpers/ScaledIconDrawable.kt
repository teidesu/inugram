package desu.inugram.helpers

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

class ScaledIconDrawable(private val inner: Drawable, maxPx: Int) : Drawable() {
    private val w: Int
    private val h: Int

    init {
        val srcW = inner.intrinsicWidth.takeIf { it > 0 } ?: maxPx
        val srcH = inner.intrinsicHeight.takeIf { it > 0 } ?: maxPx
        val s = minOf(maxPx.toFloat() / srcW, maxPx.toFloat() / srcH)
        w = (srcW * s).toInt().coerceAtLeast(1)
        h = (srcH * s).toInt().coerceAtLeast(1)
    }

    override fun getIntrinsicWidth() = w
    override fun getIntrinsicHeight() = h
    override fun draw(canvas: Canvas) {
        inner.bounds = bounds
        inner.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {
        inner.alpha = alpha
    }

    override fun setColorFilter(cf: ColorFilter?) {
        inner.colorFilter = cf
    }

    @Deprecated("required override")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
