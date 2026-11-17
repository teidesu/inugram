package desu.inugram.helpers.theme

import android.os.Build
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import androidx.annotation.RequiresApi
import desu.inugram.InuConfig
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.LaunchActivity
import org.telegram.ui.ViewPagerActivity

interface PredictiveBackTarget {
    fun inu_canPredictiveBack(): Boolean

    fun inu_setPredictiveBackProgress(progress: Float)

    /** Called on cancel, and after the back action ran; animates to rest if still shown. */
    fun inu_settlePredictiveBack()
}

object PredictiveBack {

    @JvmStatic
    fun isEnabled(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            InuConfig.PREDICTIVE_BACK_MODE.value == InuConfig.PredictiveBackModeItem.MATERIAL3

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun createCallback(target: PredictiveBackTarget, invoke: Runnable): OnBackInvokedCallback =
        if (isEnabled()) TargetCallback(target, invoke) else OnBackInvokedCallback { invoke.run() }

    /** Sheets attached to a fragment share the activity window, so its callback must route to them. */
    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun wrapAttachedSheets(activity: LaunchActivity, inner: OnBackAnimationCallback): OnBackAnimationCallback =
        AttachedSheetCallback(activity, inner)

    private fun findAttachedSheet(activity: LaunchActivity): BaseFragment.AttachedSheet? {
        val layout = activity.actionBarLayout ?: return null
        val sheetFragment = layout.getSheetFragment(false)
        if (sheetFragment != null && sheetFragment.hasShownSheet()) return sheetFragment.lastSheet
        val last = layout.lastFragment ?: return null
        return last.lastSheet ?: (last as? ViewPagerActivity)?.currentVisibleFragment?.lastSheet
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private class AttachedSheetCallback(
        private val activity: LaunchActivity,
        private val inner: OnBackAnimationCallback,
    ) : OnBackAnimationCallback {

        private var latched: TargetCallback? = null

        override fun onBackStarted(backEvent: BackEvent) {
            val target = findAttachedSheet(activity) as? PredictiveBackTarget
            latched = if (target != null && activity.onBackPressed(false) && target.inu_canPredictiveBack()) {
                TargetCallback(target) { activity.onBackPressed() }
            } else null
            (latched ?: inner).onBackStarted(backEvent)
        }

        override fun onBackProgressed(backEvent: BackEvent) {
            (latched ?: inner).onBackProgressed(backEvent)
        }

        override fun onBackInvoked() {
            val callback = latched ?: inner
            latched = null
            callback.onBackInvoked()
        }

        override fun onBackCancelled() {
            val callback = latched ?: inner
            latched = null
            callback.onBackCancelled()
        }
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal class TargetCallback(
    private val target: PredictiveBackTarget,
    private val invoke: Runnable,
) : OnBackAnimationCallback {

    private var active = false
    var rawProgress = 0f
        private set

    override fun onBackStarted(backEvent: BackEvent) {
        rawProgress = 0f
        active = target.inu_canPredictiveBack()
    }

    override fun onBackProgressed(backEvent: BackEvent) {
        if (!active) return
        rawProgress = backEvent.progress.coerceIn(0f, 1f)
        target.inu_setPredictiveBackProgress(Material3BackMotion.GESTURE.getInterpolation(rawProgress))
    }

    override fun onBackInvoked() {
        val wasActive = active
        active = false
        invoke.run()
        if (wasActive) target.inu_settlePredictiveBack()
    }

    override fun onBackCancelled() {
        if (!active) return
        active = false
        target.inu_settlePredictiveBack()
    }
}
