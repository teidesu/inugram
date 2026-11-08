package desu.inugram.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.graphics.ColorUtils
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl
import org.telegram.utils.glass.GlassEngine

open class ClassicBarDrawable @JvmOverloads constructor(
    factory: BlurredBackgroundDrawableViewFactory,
    view: android.view.View,
    protected val resourcesProvider: Theme.ResourcesProvider?,
    var colorKey: Int = Theme.key_windowBackgroundWhite,
    var drawTopDivider: Boolean = false,
    var drawBottomDivider: Boolean = false,
) : Drawable(), Drawable.Callback {
    private val blur: BlurredBackgroundDrawable = factory.create(view, NoColors)
    private val paint = Paint()
    private var drawableAlpha = 255

    init {
        blur.callback = this
    }

    var externalBlur = false

    val isBlurred
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            && GlassEngine.getCurrentGlassSettings().isBlurEnabled
            && BlurredBackgroundProviderImpl.checkBlurEnabled(resourcesProvider)

    protected open fun getColor(): Int = Theme.getColor(colorKey, resourcesProvider)

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty || drawableAlpha == 0) return
        val blurred = isBlurred
        if (!(blurred && externalBlur)) {
            if (blurred) {
                blur.bounds = bounds
                blur.alpha = drawableAlpha
                blur.draw(canvas)
            }
            drawTint(canvas, bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
        }
        if (drawTopDivider || drawBottomDivider) {
            val divider = Theme.dividerPaint
            val dividerAlpha = divider.alpha
            divider.alpha = dividerAlpha * drawableAlpha / 255
            val l = bounds.left.toFloat()
            val r = bounds.right.toFloat()
            if (drawTopDivider) canvas.drawRect(l, bounds.top.toFloat(), r, bounds.top + 1f, divider)
            if (drawBottomDivider) canvas.drawRect(l, bounds.bottom - 1f, r, bounds.bottom.toFloat(), divider)
            divider.alpha = dividerAlpha
        }
    }

    fun drawTint(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, alpha: Int = drawableAlpha) {
        var color = getColor()
        if (isBlurred) {
            val isDark = resourcesProvider?.isDark ?: Theme.isCurrentThemeDark()
            color = ColorUtils.setAlphaComponent(color, if (isDark) ChatActivity.ACTION_BAR_BLUR_ALPHA else LIGHT_BLUR_ALPHA)
        }
        paint.color = Theme.multAlpha(color, alpha / 255f)
        canvas.drawRect(left, top, right, bottom, paint)
    }

    override fun invalidateDrawable(who: Drawable) = invalidateSelf()
    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = scheduleSelf(what, `when`)
    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)

    override fun setAlpha(alpha: Int) {
        if (drawableAlpha == alpha) return
        drawableAlpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity(): Int = if (isBlurred || drawableAlpha < 255) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE

    private object NoColors : BlurredBackgroundColorProvider {
        override fun getShadowColor(): Int = 0
        override fun getBackgroundColor(): Int = 0
        override fun getStrokeColorTop(): Int = 0
        override fun getStrokeColorBottom(): Int = 0
    }

    private companion object {
        private const val LIGHT_BLUR_ALPHA = 216
    }
}
