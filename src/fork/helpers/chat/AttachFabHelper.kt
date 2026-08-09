package desu.inugram.helpers.chat

import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.core.view.isVisible
import desu.inugram.helpers.theme.M3FabHelper
import desu.inugram.helpers.theme.NonIslandHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.PhotoViewer
import org.telegram.ui.Components.ChatAttachAlert
import org.telegram.ui.Components.LayoutHelper
import java.lang.ref.WeakReference

object AttachFabHelper {
    const val FAB_SIZE = 48
    private const val FAB_MARGIN = 12

    private var photoTransitionAnchor: WeakReference<View>? = null
    private val fabLocation = IntArray(2)
    private val anchorLocation = IntArray(2)

    /** the grid cell the photo viewer opens from / closes back into, if any */
    @JvmStatic
    fun setPhotoTransitionAnchor(view: View?) {
        photoTransitionAnchor = if (view == null) null else WeakReference(view)
    }

    // leaving the gallery hides the fab right away, arriving shows it once the switch settles
    private fun isShowingGallery(alert: ChatAttachAlert): Boolean {
        val photoLayout = alert.photoLayout ?: return false
        val next = alert.nextAttachLayout
        return alert.currentAttachLayout === photoLayout && (next == null || next === photoLayout)
    }

    private fun collidesWithPhotoTransition(fab: View): Boolean {
        if (!PhotoViewer.hasInstance() || !PhotoViewer.getInstance().isVisible) return false
        val anchor = photoTransitionAnchor?.get() ?: return false
        if (!anchor.isAttachedToWindow || !fab.isAttachedToWindow) return false

        fab.getLocationInWindow(fabLocation)
        anchor.getLocationInWindow(anchorLocation)
        return fabLocation[0] < anchorLocation[0] + anchor.width &&
            anchorLocation[0] < fabLocation[0] + fab.width &&
            fabLocation[1] < anchorLocation[1] + anchor.height &&
            anchorLocation[1] < fabLocation[1] + fab.height
    }

    fun createLayoutParams(): FrameLayout.LayoutParams {
        val tabBarHeight = if (NonIslandHelper.chatElements()) 48 else 70
        return LayoutHelper.createFrame(
            FAB_SIZE.toFloat(), FAB_SIZE.toFloat(),
            Gravity.BOTTOM or Gravity.RIGHT,
            0f, 0f,
            (if (NonIslandHelper.chatElements()) 0 else FAB_MARGIN).toFloat(),
            (tabBarHeight + FAB_MARGIN).toFloat()
        )
    }

    fun applyFabStyle(fab: View, resourcesProvider: Theme.ResourcesProvider?) {
        val accentColor = Theme.getColor(Theme.key_chat_messagePanelSend, resourcesProvider)
        fab.background = M3FabHelper.makeSelectorBackground(
            FAB_SIZE,
            accentColor,
            Theme.blendOver(accentColor, 0x28FFFFFF),
        )
        fab.outlineProvider = M3FabHelper.outlineProvider()
        fab.elevation = AndroidUtilities.dp(4f).toFloat()
    }

    fun install(alert: ChatAttachAlert, container: FrameLayout, fab: View, params: FrameLayout.LayoutParams) {
        container.addView(fab, params)

        val buttonsWrapper = alert.buttonsRecyclerViewWrapper
        val sync = Runnable {
            // the sheet's window is shown/hidden as the photo viewer's background fades, which
            // flashes the fab in and out over the cell the viewer is animating from
            val visible = buttonsWrapper.isVisible && buttonsWrapper.alpha > 0.01f &&
                isShowingGallery(alert) &&
                alert.photoLayout?.cameraOpened != true &&
                !collidesWithPhotoTransition(fab)
            val target = if (visible) View.VISIBLE else View.GONE
            if (fab.visibility != target) fab.visibility = target
            fab.alpha = buttonsWrapper.alpha
            fab.translationY = buttonsWrapper.translationY
        }
        val preDraw = ViewTreeObserver.OnPreDrawListener { sync.run(); true }
        // VTO is window-scoped; rebind on each attach so reused alert instances stay synced
        buttonsWrapper.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.viewTreeObserver.addOnPreDrawListener(preDraw)
                sync.run()
            }

            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnPreDrawListener(preDraw)
            }
        })
        if (buttonsWrapper.isAttachedToWindow) {
            buttonsWrapper.viewTreeObserver.addOnPreDrawListener(preDraw)
        }
        sync.run()
    }
}
