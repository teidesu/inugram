package desu.inugram.helpers.dialogs

import android.os.Build
import android.view.animation.DecelerateInterpolator
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import androidx.annotation.RequiresApi
import desu.inugram.InuConfig
import desu.inugram.ui.drawer.DrawerSwipeController
import org.telegram.ui.LaunchActivity

/**
 * Turns a left-edge system back gesture into a drawer open, covering the part of
 * the edge that [DrawerSwipeController]'s gesture exclusion band can't reach —
 * the platform caps exclusion at 200dp per edge. The right edge keeps plain back.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
object DrawerBackGesture {

    private val INTERPOLATOR = DecelerateInterpolator()

    @JvmStatic
    fun wrap(activity: LaunchActivity, inner: OnBackAnimationCallback): OnBackAnimationCallback =
        if (InuConfig.NAVIGATION_DRAWER.value) Callback(activity, inner) else inner

    private class Callback(
        private val activity: LaunchActivity,
        private val inner: OnBackAnimationCallback,
    ) : OnBackAnimationCallback {

        private var latched = false

        private fun getController(): DrawerSwipeController? =
            activity.drawerLayoutContainer?.inu_drawer

        override fun onBackStarted(backEvent: BackEvent) {
            latched = InuConfig.DRAWER_BACK_GESTURE.value &&
                backEvent.swipeEdge == BackEvent.EDGE_LEFT &&
                getController()?.canOpenFromBackGesture == true
            if (!latched) inner.onBackStarted(backEvent)
        }

        override fun onBackProgressed(backEvent: BackEvent) {
            if (!latched) {
                inner.onBackProgressed(backEvent)
                return
            }
            val controller = getController() ?: return
            val progress = INTERPOLATOR.getInterpolation(backEvent.progress.coerceIn(0f, 1f))
            controller.dragDrawerTo(progress * controller.drawerWidth)
        }

        override fun onBackInvoked() {
            if (!latched) {
                inner.onBackInvoked()
                return
            }
            latched = false
            getController()?.openDrawer(false)
        }

        override fun onBackCancelled() {
            if (!latched) {
                inner.onBackCancelled()
                return
            }
            latched = false
            getController()?.closeDrawer(false)
        }
    }
}
