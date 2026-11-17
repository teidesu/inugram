package desu.inugram.helpers.dialogs

import android.os.Build
import android.view.animation.DecelerateInterpolator
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import androidx.annotation.RequiresApi
import desu.inugram.InuConfig
import desu.inugram.helpers.theme.Material3BackMotion
import desu.inugram.ui.drawer.DrawerSwipeController
import org.telegram.ui.LaunchActivity

/**
 * Drives the drawer from the system back gesture: an open drawer plays the M3
 * side container back animation, and a left-edge swipe opens it, covering the part of the edge
 * that [DrawerSwipeController]'s gesture exclusion band can't reach — the
 * platform caps exclusion at 200dp per edge. The right edge keeps plain back.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
object DrawerBackGesture {

    private val INTERPOLATOR = DecelerateInterpolator()

    @JvmStatic
    fun wrap(activity: LaunchActivity, inner: OnBackAnimationCallback): OnBackAnimationCallback =
        if (InuConfig.NAVIGATION_DRAWER.value) Callback(activity, inner) else inner

    private enum class Mode { NONE, OPEN, CLOSE }

    private class Callback(
        private val activity: LaunchActivity,
        private val inner: OnBackAnimationCallback,
    ) : OnBackAnimationCallback {

        private var mode = Mode.NONE
        private var lastProgress = 0f

        private fun getController(): DrawerSwipeController? =
            activity.drawerLayoutContainer?.inu_drawer

        override fun onBackStarted(backEvent: BackEvent) {
            val controller = getController()
            mode = when {
                controller?.isDrawerOpened == true ->
                    if (InuConfig.PREDICTIVE_BACK_MODE.value == InuConfig.PredictiveBackModeItem.MATERIAL3) Mode.CLOSE else Mode.NONE
                InuConfig.DRAWER_BACK_GESTURE.value &&
                    backEvent.swipeEdge == BackEvent.EDGE_LEFT &&
                    controller?.canOpenFromBackGesture == true -> Mode.OPEN
                else -> Mode.NONE
            }
            lastProgress = 0f
            if (mode == Mode.NONE) inner.onBackStarted(backEvent)
        }

        override fun onBackProgressed(backEvent: BackEvent) {
            if (mode == Mode.NONE) {
                inner.onBackProgressed(backEvent)
                return
            }
            val controller = getController() ?: return
            lastProgress = backEvent.progress.coerceIn(0f, 1f)
            if (mode == Mode.OPEN) {
                controller.dragDrawerTo(INTERPOLATOR.getInterpolation(lastProgress) * controller.drawerWidth)
            } else {
controller.updateBackProgress(
                    Material3BackMotion.GESTURE.getInterpolation(lastProgress),
                    backEvent.swipeEdge == BackEvent.EDGE_LEFT,
                )
            }
        }

        override fun onBackInvoked() {
            val m = mode
            mode = Mode.NONE
            when (m) {
                Mode.NONE -> inner.onBackInvoked()
                Mode.OPEN -> getController()?.openDrawer(false)
                Mode.CLOSE -> getController()?.finishBackProgress(lastProgress)
            }
        }

        override fun onBackCancelled() {
            val m = mode
            mode = Mode.NONE
            when (m) {
                Mode.NONE -> inner.onBackCancelled()
                Mode.OPEN -> getController()?.closeDrawer(false)
                Mode.CLOSE -> getController()?.cancelBackProgress()
            }
        }
    }
}
