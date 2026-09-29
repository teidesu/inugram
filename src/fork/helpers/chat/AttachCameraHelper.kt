package desu.inugram.helpers.chat

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import desu.inugram.InuConfig
import desu.inugram.InuConfig.AttachCameraModeItem.Companion.FAB
import desu.inugram.InuConfig.AttachCameraModeItem.Companion.INSTANT
import desu.inugram.InuConfig.AttachCameraModeItem.Companion.STATIC
import desu.inugram.InuConfig.AttachCameraModeItem.Companion.TAB
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.ChatAttachAlert
import org.telegram.ui.Components.ChatAttachAlertPhotoLayout
import java.lang.ref.WeakReference

object AttachCameraHelper {
    private var pendingOpen: WeakReference<ChatAttachAlertPhotoLayout>? = null

    @JvmStatic
    fun isInstant(): Boolean = InuConfig.ATTACH_CAMERA_MODE.value == INSTANT

    fun isFab(): Boolean = InuConfig.ATTACH_CAMERA_MODE.value == FAB

    fun isTab(): Boolean = InuConfig.ATTACH_CAMERA_MODE.value == TAB

    /** whether the camera lives outside the gallery grid, i.e. as a fab or a bottom tab */
    @JvmStatic
    fun hasCameraOutsideGrid(alert: ChatAttachAlert): Boolean {
        val mode = InuConfig.ATTACH_CAMERA_MODE.value
        if (mode == INSTANT || mode == STATIC) return false
        val chatActivity = alert.baseFragment as? ChatActivity ?: return false
        return chatActivity.chatActivityEnterView != null
    }

    @JvmStatic
    fun hasCameraTab(alert: ChatAttachAlert): Boolean =
        isTab() && alert.photoLayout?.inu_cameraOutsideGrid == true

    @JvmStatic
    fun addFab(alert: ChatAttachAlert, container: FrameLayout, resourcesProvider: Theme.ResourcesProvider?) {
        if (!isFab() || alert.photoLayout?.inu_cameraOutsideGrid != true) return

        val fab = ImageView(container.context).apply {
            setImageResource(R.drawable.camera)
            colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = LocaleController.getString(R.string.InuAttachCamera)
            AttachFabHelper.applyFabStyle(this, resourcesProvider)
            setOnClickListener { openCamera(alert) }
            setOnLongClickListener { openSystemCamera(alert); true }
        }

        AttachFabHelper.install(alert, container, fab, AttachFabHelper.createLayoutParams())
    }

    /** opens the camera, switching to the gallery layout first if some other layout is shown */
    @JvmStatic
    fun openCamera(alert: ChatAttachAlert) {
        val layout = alert.photoLayout ?: return
        if (alert.currentAttachLayout !== layout) {
            pendingOpen = WeakReference(layout)
            alert.showLayout(layout)
            return
        }
        openCameraNow(layout)
    }

    /** the camera tab can be tapped from any layout, so opening it may have to wait for the switch */
    @JvmStatic
    fun onLayoutShown(layout: ChatAttachAlertPhotoLayout) {
        if (pendingOpen?.get() !== layout) return
        pendingOpen = null
        openCameraNow(layout)
    }

    @JvmStatic
    fun onLongClickTab(alert: ChatAttachAlert, view: View): Boolean {
        if (view.tag != ChatAttachAlert.inu_TAG_CAMERA) return false
        openSystemCamera(alert)
        return true
    }

    /** same as long tapping the camera cell in the gallery grid */
    @JvmStatic
    fun openSystemCamera(alert: ChatAttachAlert) {
        alert.delegate?.didPressedButton(0, false, true, 0, 0, 0L, alert.isCaptionAbove, false, 0L)
    }

    @JvmStatic
    fun openCameraNow(layout: ChatAttachAlertPhotoLayout) {
        if (layout.noCameraPermissions) {
            layout.checkCamera(true)
            return
        }
        layout.openCameraByClick()
    }
}
