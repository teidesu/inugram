package desu.inugram.helpers.media

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ImageReceiver
import org.telegram.messenger.MediaController
import org.telegram.ui.Components.AnimatedFileDrawable
import org.telegram.ui.PhotoViewer

/**
 * Keeps initial attachment bitmaps small to reduce memory, resize and texture-upload work.
 * The viewer also loads neighboring photos; zooming or editing requests the larger bitmap.
 */
object AttachmentPreviewHelper {
    private class AttachmentPreviewState(fullSize: Int) {
        val fullFilter = "${fullSize}_${fullSize}"
        // Tracks requested quality, not completion; pending edits wait for ImageReceiver.
        val fullQualityRequested = HashMap<MediaController.PhotoEntry, Boolean>()
        var pendingEdit: PendingAttachmentEdit? = null
        var preserveZoomEntry: MediaController.PhotoEntry? = null
    }

    private class PendingAttachmentEdit(
        val entry: MediaController.PhotoEntry,
        val index: Int,
        val mode: Int,
    )

    private val attachmentPreviews = HashMap<PhotoViewer, AttachmentPreviewState>()
    private val attachmentTextureRequest = Any()

    @JvmStatic
    fun getAttachmentPreviewParentObject(viewer: PhotoViewer, entry: Any?, selectionType: Int): Any? {
        if (!InuConfig.OPTIMIZED_ATTACHMENT_MENU.value || entry !is MediaController.PhotoEntry) return null
        if (entry.isVideo || !AttachmentAnimationHelper.isAttachmentMediaPreview(viewer, entry, selectionType)) return null
        if (attachmentPreviews[viewer]?.fullQualityRequested?.get(entry) == true) return null
        return attachmentTextureRequest
    }

    @JvmStatic
    fun prepareAttachmentPreview(receiver: ImageReceiver, drawable: Drawable?) {
        if (!InuConfig.OPTIMIZED_ATTACHMENT_MENU.value || receiver.parentObject !== attachmentTextureRequest) return
        if (drawable !is BitmapDrawable || drawable is AnimatedFileDrawable) return
        // Even a bitmap cache hit can need a GPU upload. On Android 7+, queue it before binding.
        // Preparation is asynchronous and still occupies RenderThread.
        drawable.bitmap.prepareToDraw()
    }

    @JvmStatic
    fun getAttachmentPreviewSize(
        viewer: PhotoViewer,
        entry: MediaController.PhotoEntry,
        selectionType: Int,
        fullSize: Int,
    ): Int {
        if (!InuConfig.FAST_ATTACHMENT_PREVIEWS.value || entry.isVideo || !AttachmentAnimationHelper.isAttachmentMediaPreview(viewer, entry, selectionType)) return fullSize

        val state = attachmentPreviews.getOrPut(viewer) { AttachmentPreviewState(fullSize) }
        if (state.fullQualityRequested[entry] == true) return fullSize
        state.fullQualityRequested[entry] = false
        return (AndroidUtilities.getPhotoSize(false) / AndroidUtilities.density).toInt()
    }

    @JvmStatic
    fun upgradeAttachmentPreview(viewer: PhotoViewer) {
        val state = attachmentPreviews[viewer] ?: return
        val entry = viewer.imagesArrLocals.getOrNull(viewer.currentIndex) as? MediaController.PhotoEntry ?: return
        if (entry.isVideo || state.fullQualityRequested[entry] != false) return
        state.fullQualityRequested[entry] = true
        val receiver = viewer.centerImage
        val preview = receiver.imageDrawable ?: receiver.staticThumb
        // The placeholder also preserves the bitmap dimensions used by the zoom gesture.
        receiver.setCrossfadeWithOldImage(true)
        // Keep the guard through later image callbacks until the photo changes.
        state.preserveZoomEntry = entry
        try {
            receiver.setImage(entry.path, state.fullFilter, preview, null, 0)
        } finally {
            receiver.setCrossfadeWithOldImage(false)
        }
    }

    @JvmStatic
    fun deferAttachmentEdit(viewer: PhotoViewer, mode: Int, selectionType: Int): Boolean {
        if (mode == PhotoViewer.EDIT_MODE_NONE || viewer.inu_isInEditMode()) return false
        val state = attachmentPreviews[viewer] ?: return false
        val entry = viewer.imagesArrLocals.getOrNull(viewer.currentIndex) as? MediaController.PhotoEntry ?: return false
        if (!AttachmentAnimationHelper.isAttachmentMediaPreview(viewer, entry, selectionType)) {
            state.fullQualityRequested.remove(entry)
            if (state.pendingEdit?.entry === entry) state.pendingEdit = null
            if (state.preserveZoomEntry === entry) state.preserveZoomEntry = null
            return false
        }
        if (entry.isVideo || !state.fullQualityRequested.containsKey(entry)) return false
        if (viewer.centerImage.imageFilter == state.fullFilter && viewer.centerImage.hasImageLoaded()) return false

        // A memory-cache hit may call the receiver delegate inside setImage.
        state.pendingEdit = PendingAttachmentEdit(entry, viewer.currentIndex, mode)
        upgradeAttachmentPreview(viewer)
        return true
    }

    @JvmStatic
    fun onAttachmentPreviewLoaded(viewer: PhotoViewer) {
        val state = attachmentPreviews[viewer] ?: return
        val pending = state.pendingEdit ?: return
        if (viewer.centerImage.imageFilter != state.fullFilter || !viewer.centerImage.hasImageLoaded()) return
        AndroidUtilities.runOnUIThread {
            if (attachmentPreviews[viewer] !== state || state.pendingEdit !== pending || !viewer.isVisible ||
                viewer.currentIndex != pending.index || viewer.imagesArrLocals.getOrNull(pending.index) !== pending.entry
            ) return@runOnUIThread
            state.pendingEdit = null
            viewer.switchToEditMode(pending.mode)
        }
    }

    @JvmStatic
    fun shouldKeepAttachmentZoom(viewer: PhotoViewer): Boolean {
        if (!InuConfig.FAST_ATTACHMENT_PREVIEWS.value) return false
        val entry = attachmentPreviews[viewer]?.preserveZoomEntry ?: return false
        return viewer.imagesArrLocals.getOrNull(viewer.currentIndex) === entry
    }

    @JvmStatic
    fun onAttachmentPhotoChanged(viewer: PhotoViewer) {
        val state = attachmentPreviews[viewer] ?: return
        state.pendingEdit = null
        state.preserveZoomEntry = null
    }

    @JvmStatic
    fun clearAttachmentPreviews(viewer: PhotoViewer) {
        attachmentPreviews.remove(viewer)
    }
}
