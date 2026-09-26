package desu.inugram.helpers.theme

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.view.View
import android.widget.FrameLayout
import desu.inugram.helpers.dialogs.MainTabsHelper
import me.vkryl.android.AnimatorUtils
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.AndroidUtilities.dpf2
import org.telegram.messenger.AndroidUtilities.lerp
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl
import org.telegram.ui.MainTabsLayout

object M3MainTabsHelper {
    const val BAR_HEIGHT: Int = 64
    const val COMPACT_BAR_HEIGHT: Int = 48
    const val VERTICAL_PADDING = 6f
    const val INDICATOR_WIDTH = 56f
    const val INDICATOR_HEIGHT = 32f
    const val ICON_SIZE = 24f
    const val ICON_LABEL_SPACE = 4f
    const val LABEL_HEIGHT = 16f
    const val LABEL_TOP_MARGIN = VERTICAL_PADDING + INDICATOR_HEIGHT + ICON_LABEL_SPACE
    const val BADGE_SIZE = 16f
    const val BADGE_HORIZONTAL_OFFSET = 12f
    const val BADGE_VERTICAL_OFFSET = 14f
    const val BADGE_CENTER_X_OFFSET = ICON_SIZE / 2f - (BADGE_HORIZONTAL_OFFSET - BADGE_SIZE / 2f)
    const val SCRIM_RADIUS = 16
    const val DIVIDER_HEIGHT_PX = 1

    private const val INDICATOR_ALPHA = 0.2f
    private const val INDICATOR_APPEAR_SCALE_X = 0.92f
    private const val SCRIM_INSET = 4f
    private const val RIPPLE_ALPHA = 0.1f

    @JvmStatic
    val isEnabled: Boolean
        get() = MainTabsHelper.isMaterial

    @JvmStatic
    val barHeight: Int
        get() = if (MainTabsHelper.isCompact) COMPACT_BAR_HEIGHT else BAR_HEIGHT

    private val indicatorCenterY: Float
        get() = if (MainTabsHelper.isCompact) barHeight / 2f else VERTICAL_PADDING + INDICATOR_HEIGHT / 2f

    @JvmStatic
    val iconTopMargin: Float
        get() = indicatorCenterY - ICON_SIZE / 2f

    @JvmStatic
    val badgeCenterY: Float
        get() = iconTopMargin + (BADGE_VERTICAL_OFFSET - BADGE_SIZE / 2f)

    private val indicatorRect = RectF()

    @JvmStatic
    fun drawIndicator(canvas: Canvas, paint: Paint, viewWidth: Float, color: Int, selectedFactor: Float) {
        val alpha = AnimatorUtils.DECELERATE_INTERPOLATOR.getInterpolation(selectedFactor)
        val width = dpf2(INDICATOR_WIDTH)
        val height = dpf2(INDICATOR_HEIGHT)
        val cx = viewWidth / 2f
        val cy = dpf2(indicatorCenterY)
        indicatorRect.set(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f)
        paint.color = Theme.multAlpha(color, INDICATOR_ALPHA * alpha)
        canvas.save()
        canvas.scale(lerp(INDICATOR_APPEAR_SCALE_X, 1f, selectedFactor), 1f, cx, cy)
        canvas.drawRoundRect(indicatorRect, height / 2f, height / 2f, paint)
        canvas.restore()
    }

    @JvmStatic
    fun applyTabsLayout(tabsView: MainTabsLayout) {
        if (!isEnabled) return
        tabsView.inu_materialTabs = true
        tabsView.setMaxWidth(0)
        tabsView.setPadding(0, 0, 0, 0)
    }

    private class IndicatorMaskDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            val width = dpf2(INDICATOR_WIDTH)
            val height = dpf2(INDICATOR_HEIGHT)
            val cx = bounds.centerX().toFloat()
            val cy = bounds.top + dpf2(indicatorCenterY)
            rect.set(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f)
            canvas.drawRoundRect(rect, height / 2f, height / 2f, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    @JvmStatic
    fun applyTabRipple(tab: View, color: Int) {
        if (!isEnabled) return
        tab.foreground = RippleDrawable(rippleColor(color), null, IndicatorMaskDrawable())
    }

    @JvmStatic
    fun updateTabRipple(tab: View, color: Int) {
        (tab.foreground as? RippleDrawable)?.setColor(rippleColor(color))
    }

    private fun rippleColor(color: Int): ColorStateList =
        ColorStateList.valueOf(Theme.multAlpha(color, RIPPLE_ALPHA))

    private class TopDividerDrawable : Drawable() {
        override fun draw(canvas: Canvas) {
            if (BlurredBackgroundProviderImpl.checkBlurEnabled(null)) return
            val top = bounds.top.toFloat()
            canvas.drawRect(bounds.left.toFloat(), top, bounds.right.toFloat(), top + DIVIDER_HEIGHT_PX, Theme.dividerPaint)
        }

        override fun setAlpha(alpha: Int) {}

        override fun setColorFilter(colorFilter: ColorFilter?) {}

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    @JvmStatic
    fun applyTabsWrapper(wrapper: FrameLayout) {
        if (!isEnabled) return
        wrapper.background = TopDividerDrawable()
    }

    @JvmStatic
    fun applyTabsBackground(background: BlurredBackgroundDrawable) {
        if (!isEnabled) return
        background.setRadius(0f)
        background.setPadding(0)
    }

    @JvmStatic
    fun applyTabsInsets(wrapper: FrameLayout, tabsView: MainTabsLayout, left: Int, right: Int, bottom: Int): Boolean {
        if (!isEnabled) return false
        wrapper.setPadding(left, DIVIDER_HEIGHT_PX, right, 0)
        tabsView.setPadding(0, 0, 0, bottom)
        val lp = tabsView.layoutParams
        val height = dp(barHeight.toFloat()) + bottom
        if (lp.height != height) {
            lp.height = height
            tabsView.layoutParams = lp
        }
        return true
    }

    @JvmStatic
    fun sizeScrimBackground(background: ShapeDrawable, anchor: View) {
        if (!isEnabled) return
        if (MainTabsHelper.isCompact) {
            background.intrinsicWidth = dp(INDICATOR_WIDTH)
            background.intrinsicHeight = dp(INDICATOR_HEIGHT)
            return
        }
        val inset = dp(SCRIM_INSET) * 2
        background.intrinsicWidth = anchor.width - inset
        background.intrinsicHeight = anchor.height - inset
    }
}
