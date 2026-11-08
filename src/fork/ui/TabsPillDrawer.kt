package desu.inugram.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.AndroidUtilities.lerp
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedFloat
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ScrollSlidingTextTabStrip
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory

class TabsPillDrawer(
    private val strip: ScrollSlidingTextTabStrip,
    private val background: ClassicBarDrawable,
) {
    private var docked = false
    private val dockedT = AnimatedFloat(strip, 320L, CubicBezierInterpolator.EASE_OUT_QUINT)
    private val bgRect = RectF()
    private val bgBounds = Rect()
    private val bgPath = Path()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setDocked(docked: Boolean) {
        if (this.docked != docked) {
            this.docked = docked
            strip.invalidate()
        }
    }

    fun getDockedProgress(): Float = dockedT.get()

    fun drawBackgroundAndClip(canvas: Canvas, color: Int) {
        val t = dockedT.set(docked)
        val w = strip.measuredWidth.toFloat()
        val h = strip.measuredHeight.toFloat()
        val margin = dp(MARGIN_DP).toFloat()
        val container = strip.tabsContainer
        val first = container.getChildAt(0)
        val last = container.getChildAt(container.childCount - 1)
        val span = if (first != null && last != null) (last.right - first.left).toFloat() else w
        var left = lerp(margin, 0f, t)
        var right = lerp(w - margin, w, t)
        if (first != null && span + 2 * margin <= w) {
            val centered = (w - span) / 2f
            container.translationX = lerp(centered - container.left - first.left + strip.scrollX, 0f, t)
            left = lerp(centered, 0f, t)
            right = lerp(centered + span, w, t)
        } else {
            container.translationX = 0f
        }
        bgRect.set(
            left,
            strip.paddingTop.toFloat(),
            right,
            h - strip.paddingBottom + t * dp(4f),
        )
        val r = lerp(bgRect.height() / 2f, 0f, t)
        canvas.save()
        canvas.translate(strip.scrollX.toFloat(), 0f)
        bgPath.rewind()
        bgPath.addRoundRect(bgRect, r, r, Path.Direction.CW)
        bgPaint.color = color
        canvas.drawPath(bgPath, bgPaint)
        canvas.clipPath(bgPath)
        if (t > 0f) {
            bgRect.roundOut(bgBounds)
            background.bounds = bgBounds
            background.alpha = (0xFF * t).toInt()
            background.draw(canvas)
        }
        canvas.translate(-strip.scrollX.toFloat(), 0f)
    }

    companion object {
        private const val MARGIN_DP = 12f

        @JvmStatic
        fun attach(strip: ScrollSlidingTextTabStrip, factory: BlurredBackgroundDrawableViewFactory, resourcesProvider: Theme.ResourcesProvider?) {
            strip.layoutParams = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 50, Gravity.LEFT or Gravity.TOP)
            strip.setPadding(0, dp(7f), 0, dp(7f))
            strip.clipToPadding = false
            strip.tabsContainer.setPadding(dp(12f), 0, dp(12f), 0)
            strip.setUseMinimalWidth(true)
            strip.inu_pill = TabsPillDrawer(strip, ClassicBarDrawable(factory, strip, resourcesProvider))
        }
    }
}
