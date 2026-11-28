package desu.inugram.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.AndroidUtilities.lerp
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedFloat
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ScrollSlidingTextTabStrip
import org.telegram.ui.Components.ViewPagerFixed
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory

class TabsPillDrawer(
    private val strip: View,
    private val container: ViewGroup,
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

    fun drawBackgroundAndClip(canvas: Canvas, color: Int) {
        val t = dockedT.set(docked)
        val w = strip.measuredWidth.toFloat()
        val h = strip.measuredHeight.toFloat()
        val margin = dp(MARGIN_DP).toFloat()
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

    fun updateSelector(selector: GradientDrawable, centerX: Float, pillHalf: Float, barHalf: Float, pillColor: Int, barColor: Int) {
        val t = dockedT.get()
        val height = strip.measuredHeight
        val half = lerp(pillHalf, barHalf, t)
        val dockExtra = t * dp(4f)
        val top = lerp((strip.paddingTop + dp(4f)).toFloat(), (height - strip.paddingBottom - dp(3f)).toFloat(), t) + dockExtra
        val bottom = lerp((height - strip.paddingBottom - dp(4f)).toFloat(), (height - strip.paddingBottom).toFloat(), t) + dockExtra
        val topRad = lerp(dp(14f).toFloat(), dp(3f).toFloat(), t)
        val botRad = lerp(dp(14f).toFloat(), 0f, t)
        selector.cornerRadii = floatArrayOf(topRad, topRad, topRad, topRad, botRad, botRad, botRad, botRad)
        selector.setColor(ColorUtils.blendARGB(pillColor, barColor, t))
        selector.setBounds((centerX - half).toInt(), top.toInt(), (centerX + half).toInt(), bottom.toInt())
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
            strip.inu_pill = TabsPillDrawer(strip, strip.tabsContainer, ClassicBarDrawable(factory, strip, resourcesProvider))
        }

        @JvmStatic
        fun attach(tabs: ViewPagerFixed.TabsView, factory: BlurredBackgroundDrawableViewFactory, resourcesProvider: Theme.ResourcesProvider?) {
            tabs.layoutParams = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 50, Gravity.LEFT or Gravity.TOP)
            tabs.setPadding(0, dp(7f), 0, dp(7f))
            tabs.clipToPadding = false
            tabs.inu_pill = TabsPillDrawer(tabs, tabs.listView, ClassicBarDrawable(factory, tabs, resourcesProvider))
        }
    }
}
