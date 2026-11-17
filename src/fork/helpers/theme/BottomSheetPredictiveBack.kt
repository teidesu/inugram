package desu.inugram.helpers.theme

import android.animation.ValueAnimator
import android.graphics.Outline
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import org.telegram.messenger.AndroidUtilities.dpf2
import org.telegram.ui.ActionBar.BottomSheet

object BottomSheetPredictiveBack {

    private const val MAX_SCALE_DISTANCE_DP = 48f
    private const val CORNER_RADIUS_DP = 12f
    private const val HIDE_DURATION_MAX = 300f
    private const val HIDE_DURATION_MIN = 150f
    private const val CANCEL_DURATION = 100L

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun register(sheet: BottomSheet): Any? {
        if (!PredictiveBack.isEnabled()) return null
        val target = SheetTarget(sheet)
        sheet.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, target.callback)
        return target
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun unregister(sheet: BottomSheet, registration: Any) {
        val target = registration as SheetTarget
        sheet.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(target.callback)
        target.reset()
    }

    // onBackPressed overrides Dialog's, so R8 keeps its name and getMethod is safe
    @JvmStatic
    fun isOnBackPressedOwnedBy(sheet: BottomSheet, owner: Class<*>): Boolean =
        sheet.javaClass.getMethod("onBackPressed").declaringClass == owner

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private class SheetTarget(private val sheet: BottomSheet) : PredictiveBackTarget {

        val callback = TargetCallback(this) { invokeBack() }
        private var settleAnim: ValueAnimator? = null
        private var progress = 0f
        private var savedOutline: Pair<ViewOutlineProvider?, Boolean>? = null

        private val cornerOutlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val inset = sheet.backgroundPaddingLeft
                val radius = dpf2(CORNER_RADIUS_DP) * progress
                outline.setRoundRect(inset, 0, view.width - inset, view.height + radius.toInt() + 1, radius)
            }
        }

        private fun invokeBack() {
            if (callback.rawProgress == 0f) {
                sheet.onBackPressed()
                return
            }
            sheet.inu_backDismissDuration = (HIDE_DURATION_MAX + (HIDE_DURATION_MIN - HIDE_DURATION_MAX) * callback.rawProgress).toLong()
            try {
                sheet.onBackPressed()
            } finally {
                sheet.inu_backDismissDuration = 0
            }
        }

        override fun inu_canPredictiveBack(): Boolean {
            settleAnim?.end()
            return sheet.inu_canPredictiveBack()
        }

        override fun inu_setPredictiveBackProgress(progress: Float) {
            this.progress = progress
            val view = sheet.getContainerView()
            val width = view?.width ?: 0
            sheet.inu_setBackScale(if (progress == 0f || width <= 0) 1f else 1f - progress * dpf2(MAX_SCALE_DISTANCE_DP) / width)
            if (view != null) updateCornerClip(view)
        }

        private fun updateCornerClip(view: View) {
            val saved = savedOutline
            if (progress == 0f) {
                if (saved != null) {
                    view.outlineProvider = saved.first
                    view.clipToOutline = saved.second
                    savedOutline = null
                }
                return
            }
            if (saved == null) {
                savedOutline = view.outlineProvider to view.clipToOutline
                view.outlineProvider = cornerOutlineProvider
                view.clipToOutline = true
            }
            view.invalidateOutline()
        }

        override fun inu_settlePredictiveBack() {
            if (sheet.isDismissed) return
            settleAnim = ValueAnimator.ofFloat(progress, 0f).apply {
                duration = CANCEL_DURATION
                interpolator = FastOutSlowInInterpolator()
                addUpdateListener { inu_setPredictiveBackProgress(it.animatedValue as Float) }
                start()
            }
        }

        fun reset() {
            settleAnim?.cancel()
            if (progress != 0f) inu_setPredictiveBackProgress(0f)
        }
    }
}
