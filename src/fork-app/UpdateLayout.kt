package desu.inugram

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import desu.inugram.ui.UpdateRowView
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.SharedConfig
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.IUpdateLayout
import org.telegram.ui.UpdateLayoutWrapper

class UpdateLayout(
    private val activity: Activity,
    private val sideMenuContainer: ViewGroup?,
) : IUpdateLayout(activity, sideMenuContainer) {

    private var row: UpdateRowView? = null

    override fun updateFileProgress(args: Array<out Any?>?) {
        row?.applyProgress(args)
    }

    override fun createUpdateUI(currentAccount: Int) {
        val container = sideMenuContainer ?: return
        if (row != null) return
        val view = UpdateRowView(activity)
        view.visibility = View.GONE
        view.translationY = dp(44f).toFloat()
        // UpdateLayoutWrapper propagates paddingBottom only to children present at the time of
        // its setPadding call. Seed the row from the container's current padding so its
        // visible-content area (the top 44dp) stays correctly sized regardless of when the
        // wrapper's padding was last set.
        if (container is UpdateLayoutWrapper) {
            view.setPadding(
                container.paddingLeft,
                container.paddingTop,
                container.paddingRight,
                container.paddingBottom,
            )
        }
        container.addView(
            view,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44, Gravity.LEFT or Gravity.BOTTOM),
        )
        row = view
    }

    override fun updateAppUpdateViews(currentAccount: Int, animated: Boolean) {
        if (sideMenuContainer == null) return
        if (SharedConfig.isAppUpdateAvailable()) {
            createUpdateUI(currentAccount)
            val view = row ?: return
            view.refresh(animated)
            if (view.tag != null) return
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.tag = 1
            if (animated) {
                view.animate().translationY(0f)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT).setListener(null)
                    .setDuration(180).start()
            } else {
                view.translationY = 0f
            }
        } else {
            val view = row ?: return
            if (view.tag == null) return
            view.tag = null
            view.animate().cancel()
            if (animated) {
                view.animate().translationY(dp(44f).toFloat())
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT)
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (view.tag == null) view.visibility = View.GONE
                        }
                    }).setDuration(180).start()
            } else {
                view.translationY = dp(44f).toFloat()
                view.visibility = View.GONE
            }
        }
    }
}
