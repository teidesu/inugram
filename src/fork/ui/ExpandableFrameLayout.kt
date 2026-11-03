package desu.inugram.ui

import android.animation.ValueAnimator
import android.content.Context
import android.widget.FrameLayout
import org.telegram.ui.Components.CubicBezierInterpolator
import kotlin.math.roundToInt

class ExpandableFrameLayout(context: Context) : FrameLayout(context) {
    var isExpanded = true
        private set
    private var progress = 1f
    private var animator: ValueAnimator? = null

    fun setExpanded(expanded: Boolean, animated: Boolean) {
        if (expanded == isExpanded) return
        isExpanded = expanded
        animator?.cancel()
        val target = if (expanded) 1f else 0f
        if (!animated) {
            setProgress(target)
            return
        }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 280
            interpolator = CubicBezierInterpolator.EASE_OUT_QUINT
            addUpdateListener { setProgress(it.animatedValue as Float) }
            start()
        }
    }

    private fun setProgress(value: Float) {
        progress = value
        alpha = value
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, (measuredHeight * progress).roundToInt())
    }
}
