package desu.inugram.helpers.icons

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import kotlin.math.abs
import kotlin.math.min

abstract class IconPackDrawable(private val width: Int, private val height: Int) : Drawable() {
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var colorFilter: ColorFilter? = null
    private var tint: ColorStateList? = null
    private var tintMode = PorterDuff.Mode.SRC_IN
    private var tintFilter: PorterDuffColorFilter? = null

    protected val activeColorFilter get() = colorFilter ?: tintFilter

    protected abstract fun copy(): IconPackDrawable

    override fun getIntrinsicWidth() = width
    override fun getIntrinsicHeight() = height

    override fun setAlpha(alpha: Int) {
        if (paint.alpha == alpha) return
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun getAlpha() = paint.alpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        this.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getColorFilter() = colorFilter

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        if (updateTintFilter()) invalidateSelf()
    }

    override fun setTintMode(tintMode: PorterDuff.Mode?) {
        this.tintMode = tintMode ?: PorterDuff.Mode.SRC_IN
        if (updateTintFilter()) invalidateSelf()
    }

    override fun isStateful() = tint?.isStateful == true

    override fun onStateChange(state: IntArray) = updateTintFilter()

    private fun updateTintFilter(): Boolean {
        val tint = tint
        if (tint == null) {
            val changed = tintFilter != null
            tintFilter = null
            return changed
        }
        tintFilter = PorterDuffColorFilter(tint.getColorForState(state, tint.defaultColor), tintMode)
        return true
    }

    @Deprecated("required override")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun getConstantState(): ConstantState = State(this)

    // BitmapDrawable keeps paint alpha, color filter and tint in its ConstantState; stock clones themed drawables relying on that
    private class State(private val source: IconPackDrawable) : ConstantState() {
        override fun newDrawable() = source.copy().also {
            it.paint.alpha = source.paint.alpha
            it.colorFilter = source.colorFilter
            it.tint = source.tint
            it.tintMode = source.tintMode
            it.updateTintFilter()
        }
        override fun getChangingConfigurations() = 0
    }
}

class BitmapIconDrawable(private val content: Content, width: Int, height: Int) : IconPackDrawable(width, height) {
    sealed interface Content {
        class Raster(val bitmap: Bitmap) : Content

        // shared by every drawable of this icon, so a list of identical icons rasterizes once
        class Svg(private val svg: SVG) : Content {
            private var cache: Bitmap? = null

            @Synchronized
            fun render(width: Int, height: Int): Bitmap {
                cache?.let { if (it.width == width && it.height == height) return it }
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                svg.renderToCanvas(Canvas(bitmap), RenderOptions().viewPort(0f, 0f, width.toFloat(), height.toFloat()))
                cache = bitmap
                return bitmap
            }
        }
    }

    private val matrix = Matrix()
    private val matrixValues = FloatArray(9)

    override fun copy() = BitmapIconDrawable(content, intrinsicWidth, intrinsicHeight)

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val bitmap = when (content) {
            is Content.Raster -> content.bitmap
            is Content.Svg -> renderSvg(canvas, content) ?: return
        }
        paint.colorFilter = activeColorFilter
        canvas.drawBitmap(bitmap, null, bounds, paint)
    }

    private fun renderSvg(canvas: Canvas, svg: Content.Svg): Bitmap? {
        // same cache sizing as VectorDrawable: rasterize at on-screen size, ignore scale under skew
        @Suppress("DEPRECATION")
        canvas.getMatrix(matrix)
        matrix.getValues(matrixValues)
        val skewed = matrixValues[Matrix.MSKEW_X] != 0f || matrixValues[Matrix.MSKEW_Y] != 0f
        val scaleX = if (skewed) 1f else abs(matrixValues[Matrix.MSCALE_X])
        val scaleY = if (skewed) 1f else abs(matrixValues[Matrix.MSCALE_Y])
        val w = min(MAX_CACHED_BITMAP_SIZE, (bounds.width() * scaleX).toInt())
        val h = min(MAX_CACHED_BITMAP_SIZE, (bounds.height() * scaleY).toInt())
        if (w <= 0 || h <= 0) return null
        return svg.render(w, h)
    }

    private companion object {
        const val MAX_CACHED_BITMAP_SIZE = 2048
    }
}
