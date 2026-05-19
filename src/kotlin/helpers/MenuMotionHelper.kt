package desu.inugram.helpers

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.OvershootInterpolator
import desu.inugram.InuConfig
import org.telegram.ui.ActionBar.ActionBarMenuSubItem
import org.telegram.ui.ActionBar.ActionBarPopupWindow.ActionBarPopupWindowLayout
import org.telegram.ui.ActionBar.ActionBarPopupWindow.GapView

object MenuMotionHelper {
    private val overshoot = OvershootInterpolator()

    @JvmStatic
    val enabled: Boolean get() = InuConfig.REDUCE_MENU_MOTION.value

    @JvmStatic
    fun applyShown(child: View) {
        child.alpha = if (child.isEnabled) 1f else 0.5f
        child.translationY = 0f
        (child as? ActionBarMenuSubItem)?.onItemShown()
    }

    @JvmStatic
    fun buildEnter(content: ActionBarPopupWindowLayout, target: View, finalScaleY: Float): AnimatorSet {
        content.setBackScaleY(finalScaleY)
        content.setBackAlpha(255)
        for (a in 0 until content.itemsCount) {
            val child = content.getItemAt(a) ?: continue
            if (child is GapView) continue
            child.alpha = if (child.isEnabled) 1f else 0.5f
            child.translationY = 0f
        }
        target.pivotX = target.measuredWidth.toFloat()
        target.pivotY = if (content.shownFromBottom) target.measuredHeight.toFloat() else 0f
        target.scaleX = 0.95f
        target.scaleY = 0.95f
        target.alpha = 0f
        return AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(target, View.ALPHA, 0f, 1f).setDuration(150),
                ObjectAnimator.ofFloat(target, View.SCALE_X, 0.95f, 1f).setDuration(200).apply { interpolator = overshoot },
                ObjectAnimator.ofFloat(target, View.SCALE_Y, 0.95f, 1f).setDuration(200).apply { interpolator = overshoot },
            )
        }
    }

}
