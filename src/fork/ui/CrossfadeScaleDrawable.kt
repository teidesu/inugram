package desu.inugram.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import org.telegram.messenger.AndroidUtilities
import kotlin.math.max
import kotlin.math.roundToInt

class CrossfadeScaleDrawable private constructor(private val from: Drawable, private val to: Drawable) : Drawable() {
    private var progress = 0f
    private var alpha = 255

    override fun draw(canvas: Canvas) {
        val out = OUT_INTERPOLATOR.getInterpolation((progress / PHASE).coerceAtMost(1f))
        val inFraction = ((progress - (1f - PHASE)) / PHASE).coerceAtLeast(0f)
        if (out < 1f) drawLayer(canvas, from, 1f - out * out, AndroidUtilities.lerp(1f, MIN_SCALE, out))
        if (inFraction > 0f) drawLayer(canvas, to, (inFraction * 2f).coerceAtMost(1f), AndroidUtilities.lerp(MIN_SCALE, 1f, IN_INTERPOLATOR.getInterpolation(inFraction)))
    }

    private fun drawLayer(canvas: Canvas, drawable: Drawable, layerAlpha: Float, scale: Float) {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: bounds.width()
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: bounds.height()
        drawable.setBounds(cx - w / 2, cy - h / 2, cx - w / 2 + w, cy - h / 2 + h)
        drawable.alpha = (layerAlpha * alpha).roundToInt()
        val save = canvas.save()
        canvas.scale(scale, scale, bounds.exactCenterX(), bounds.exactCenterY())
        drawable.draw(canvas)
        canvas.restoreToCount(save)
    }

    override fun getIntrinsicWidth() = max(from.intrinsicWidth, to.intrinsicWidth)

    override fun getIntrinsicHeight() = max(from.intrinsicHeight, to.intrinsicHeight)

    override fun setAlpha(alpha: Int) {
        this.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        from.colorFilter = colorFilter
        to.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private const val MIN_SCALE = 0.1f
        private const val DURATION = 220L
        private const val PHASE = 0.6f
        private val OUT_INTERPOLATOR = AccelerateInterpolator()
        private val IN_INTERPOLATOR = DecelerateInterpolator()

        @JvmStatic
        fun swap(imageView: ImageView, @DrawableRes resId: Int) {
            val to = ContextCompat.getDrawable(imageView.context, resId)?.mutate() ?: return
            val current = imageView.drawable
            if (current == null) {
                imageView.setImageDrawable(to)
                return
            }
            val drawable = CrossfadeScaleDrawable(current.mutate(), to)
            imageView.setImageDrawable(drawable)
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = DURATION
                addUpdateListener {
                    drawable.progress = it.animatedValue as Float
                    drawable.invalidateSelf()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (imageView.drawable === drawable) imageView.setImageDrawable(to)
                    }
                })
                start()
            }
        }
    }
}
