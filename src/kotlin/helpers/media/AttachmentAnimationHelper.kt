package desu.inugram.helpers.media

import android.animation.Animator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.View
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MediaController
import org.telegram.messenger.Utilities
import org.telegram.ui.Cells.PhotoAttachPhotoCell
import org.telegram.ui.Components.ChatAttachAlert
import org.telegram.ui.PhotoViewer

object AttachmentAnimationHelper {
    private class AttachmentBackdropState(val alert: ChatAttachAlert, val width: Int, val height: Int) {
        var renderer: AttachmentBackdrop? = null
        var bitmap: Bitmap? = null
        var viewer: PhotoViewer? = null
        var active = false
        val origin = IntArray(2)
        val windowOrigin = IntArray(2)
        val bitmapPaint = Paint()
        val shadePaint = Paint().apply { color = Color.BLACK; alpha = 0 }
        var detachListener: View.OnAttachStateChangeListener? = null
    }

    /**
     * Draws two gradient strips directly. Stock uses a full-view saveLayerAlpha and DST_IN masks,
     * even with blur off; avoiding that temporary layer and masking work reduces rendering spikes
     * during opening. The shaders, paints and matrix are reused across frames.
     */
    private class AttachmentShadows {
        private val matrix = Matrix()
        private val topGradient = LinearGradient(0f, 0f, 0f, 1f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        private val bottomGradient = LinearGradient(0f, 0f, 0f, 1f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        private val topPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = topGradient
        }
        private val bottomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = bottomGradient
        }

        fun draw(
            canvas: Canvas,
            width: Int,
            height: Int,
            top: Int,
            bottom: Int,
            alpha: Int,
            topControls: View,
            bottomControls: View,
        ) {
            // Match stock's second-half backdrop fade.
            val opacity = ((alpha - 127) / 127f).coerceIn(0f, 1f)
            if (opacity == 0f) return

            val topY = topControls.translationY
            matrix.setScale(1f, top.toFloat())
            matrix.postTranslate(0f, topY)
            topGradient.setLocalMatrix(matrix)
            topPaint.alpha = (0xd0 * opacity * topControls.alpha).toInt()
            canvas.drawRect(0f, topY, width.toFloat(), topY + top, topPaint)

            val bottomY = height - bottom + bottomControls.translationY
            matrix.setScale(1f, bottom.toFloat())
            matrix.postTranslate(0f, bottomY)
            bottomGradient.setLocalMatrix(matrix)
            bottomPaint.alpha = (0xbb * opacity * bottomControls.alpha).toInt()
            canvas.drawRect(0f, bottomY, width.toFloat(), bottomY + bottom, bottomPaint)
        }
    }

    private var attachmentBackdrop: AttachmentBackdropState? = null
    private val attachmentShadows = AttachmentShadows()

    @JvmStatic
    fun prepareAttachmentBackdrop(alert: ChatAttachAlert) {
        if (!InuConfig.OPTIMIZED_ATTACHMENT_MENU.value || Build.VERSION.SDK_INT < 34) return
        if (alert.isPhotoPicker || alert.isPollAttach) return
        val activity = alert.baseFragment?.parentActivity ?: return
        val decor = activity.window.decorView
        val sheet = alert.window?.decorView ?: return
        sheet.post {
            if (!sheet.isAttachedToWindow || decor.width == 0 || decor.height == 0) return@post
            val previous = attachmentBackdrop
            if (previous?.alert === alert && previous.viewer == null &&
                previous.width == decor.width && previous.height == decor.height
            ) return@post
            if (previous != null) clearAttachmentBackdrop(previous.alert)
            val state = AttachmentBackdropState(alert, decor.width, decor.height)
            attachmentBackdrop = state
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) = clearAttachmentBackdrop(alert)
            }
            state.detachListener = listener
            sheet.addOnAttachStateChangeListener(listener)
            // Move allocation off the opening path; capture on tap to keep the snapshot current.
            Utilities.globalQueue.postRunnable {
                var renderer: AttachmentBackdrop? = null
                try {
                    renderer = AttachmentBackdrop(state.width, state.height)
                } catch (error: RuntimeException) {
                    Log.d("InuMediaBackdrop", "GPU backdrop allocation failed", error)
                } catch (error: OutOfMemoryError) {
                    Log.d("InuMediaBackdrop", "GPU backdrop allocation failed", error)
                }
                val allocated = renderer
                AndroidUtilities.runOnUIThread {
                    if (attachmentBackdrop === state) {
                        state.renderer = allocated
                    } else {
                        allocated?.close()
                    }
                }
            }
        }
    }

    @JvmStatic
    fun captureAttachmentBackdrop(
        viewer: PhotoViewer,
        alert: ChatAttachAlert,
        entry: Any?,
        selectionType: Int,
        cell: View,
    ) {
        if (!InuConfig.OPTIMIZED_ATTACHMENT_MENU.value || Build.VERSION.SDK_INT < 34) return
        if (entry !is MediaController.PhotoEntry || !isAttachmentMediaPreview(viewer, entry, selectionType)) return
        val state = attachmentBackdrop ?: return
        if (state.alert !== alert || state.viewer != null || cell !is PhotoAttachPhotoCell) return
        if (cell.photoEntry !== entry) return
        val renderer = state.renderer ?: return
        val decor = alert.baseFragment?.parentActivity?.window?.decorView ?: return
        val sheet = alert.window?.decorView ?: return
        if (decor.width != state.width || decor.height != state.height) return
        state.viewer = viewer
        decor.getLocationOnScreen(state.origin)
        val sheetOrigin = IntArray(2)
        sheet.getLocationOnScreen(sheetOrigin)
        // Hide only the source cell while recording. Clipping its rectangle out of the whole
        // sheet also removes the sheet background/overlapping toolbar and exposes the chat below.
        val visibility = cell.visibility
        cell.visibility = View.INVISIBLE
        try {
            renderer.render { canvas ->
                canvas.drawColor(Color.BLACK)
                decor.draw(canvas)
                canvas.save()
                canvas.translate((sheetOrigin[0] - state.origin[0]).toFloat(), (sheetOrigin[1] - state.origin[1]).toFloat())
                // Stock uses SRC for a separate translucent window. In a combined snapshot it erases the chat.
                val dim = alert.backDrawable
                val dimAlpha = dim.alpha
                val dimPaint = Paint().apply { color = Color.BLACK; alpha = dimAlpha }
                canvas.drawRect(dim.bounds, dimPaint)
                dim.alpha = 0
                try {
                    sheet.draw(canvas)
                } finally {
                    dim.alpha = dimAlpha
                    canvas.restore()
                }
            }
        } finally {
            cell.visibility = visibility
        }
    }

    @JvmStatic
    fun beginAttachmentBackdrop(viewer: PhotoViewer): Animator? {
        val state = attachmentBackdrop ?: return null
        state.bitmap = state.renderer?.bitmap
        if (!InuConfig.OPTIMIZED_ATTACHMENT_MENU.value || state.viewer !== viewer || state.bitmap == null) {
            clearAttachmentBackdrop(state.alert)
            return null
        }
        state.active = true
        viewer.windowView.getLocationOnScreen(state.windowOrigin)
        return ValueAnimator.ofInt(0, 255).apply {
            addUpdateListener { animation ->
                state.shadePaint.alpha = animation.animatedValue as Int
                viewer.windowView.invalidate()
            }
        }
    }

    @JvmStatic
    fun drawAttachmentBackdrop(viewer: PhotoViewer, canvas: Canvas, bounds: Rect): Boolean {
        val state = attachmentBackdrop ?: return false
        if (!InuConfig.OPTIMIZED_ATTACHMENT_MENU.value || state.viewer !== viewer || !state.active) return false
        val bitmap = state.bitmap ?: return false
        val location = state.windowOrigin
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bitmap, (state.origin[0] - location[0]).toFloat(),
            (state.origin[1] - location[1]).toFloat(), state.bitmapPaint)
        canvas.drawRect(bounds, state.shadePaint)
        return true
    }

    @JvmStatic
    fun finishAttachmentBackdrop(viewer: PhotoViewer) {
        val state = attachmentBackdrop ?: return
        if (state.viewer !== viewer) return
        clearAttachmentBackdrop(state.alert)
    }

    @JvmStatic
    fun clearAttachmentBackdrop(alert: ChatAttachAlert) {
        val state = attachmentBackdrop ?: return
        if (state.alert !== alert) return
        attachmentBackdrop = null
        state.detachListener?.let { alert.window?.decorView?.removeOnAttachStateChangeListener(it) }
        state.renderer?.close()
        // Recorded UI frames can retain the hardware bitmap. Let their references expire naturally.
        state.bitmap = null
    }

    internal fun isAttachmentMediaPreview(
        viewer: PhotoViewer,
        entry: MediaController.PhotoEntry,
        selectionType: Int,
    ): Boolean {
        if (viewer.parentAlert == null || (selectionType != 0 && selectionType != 4)) return false
        return entry.filterPath == null && entry.cropState == null &&
            !entry.isFiltered && !entry.isPainted && !entry.isCropped
    }

    @JvmStatic
    fun isLightweightAttachmentPreview(viewer: PhotoViewer, selectionType: Int): Boolean {
        if (!InuConfig.OPTIMIZED_ATTACHMENT_MENU.value) return false
        val entry = viewer.imagesArrLocals.getOrNull(viewer.currentIndex) as? MediaController.PhotoEntry ?: return false
        return isAttachmentMediaPreview(viewer, entry, selectionType)
    }

    @JvmStatic
    fun drawAttachmentShadows(
        viewer: PhotoViewer,
        selectionType: Int,
        canvas: Canvas,
        width: Int,
        height: Int,
        top: Int,
        bottom: Int,
        backgroundAlpha: Int,
        topControls: View,
        bottomControls: View,
    ): Boolean {
        if (!isLightweightAttachmentPreview(viewer, selectionType)) return false
        var alpha = backgroundAlpha
        val backdrop = attachmentBackdrop
        if (backdrop?.viewer === viewer && backdrop.active) {
            alpha = backdrop.shadePaint.alpha
        }
        attachmentShadows.draw(canvas, width, height, top, bottom, alpha, topControls, bottomControls)
        return true
    }

    @JvmStatic
    fun prepareAttachmentOpening(viewer: PhotoViewer, selectionType: Int): Boolean {
        if (!isLightweightAttachmentPreview(viewer, selectionType)) return false

        val container: View = viewer.containerView
        // Keep GPU drawing without a texture for the whole container.
        container.setLayerType(View.LAYER_TYPE_NONE, null)
        // Show controls immediately: fading their parent can require an offscreen alpha layer.
        container.alpha = 1f
        return true
    }
}
