package desu.inugram.helpers.media

import android.provider.MediaStore
import desu.inugram.InuConfig
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLoader
import org.telegram.messenger.MessageObject
import org.telegram.messenger.SendMessagesHelper
import org.telegram.tgnet.TLRPC
import java.io.File
import kotlin.math.max

object MediaSendHelper {
    @JvmStatic
    fun prepareMp4Document(
        document: TLRPC.TL_document?,
        path: String?,
        isEncrypted: Boolean,
        forceDocument: Boolean,
    ): Boolean {
        if (!forceDocument || !InuConfig.SEND_MP4_DOCUMENT_AS_VIDEO.value || document == null || path == null) {
            return false
        }
        if (!FileLoader.getFileExtension(File(path)).equals("mp4", ignoreCase = true)) {
            return false
        }
        if (MessageObject.isVideoDocument(document)) {
            return true
        }

        val attribute = if (isEncrypted) {
            TLRPC.TL_documentAttributeVideo_layer159()
        } else {
            TLRPC.TL_documentAttributeVideo().apply { supports_streaming = true }
        }
        SendMessagesHelper.fillVideoAttribute(path, attribute, null)
        if (attribute.w <= 0 || attribute.h <= 0 || attribute.duration <= 0) {
            return false
        }
        document.attributes.add(attribute)

        if (document.thumbs.isEmpty()) {
            val bitmap = SendMessagesHelper.createVideoThumbnailAtTime(path, 0)
                ?: SendMessagesHelper.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND)
            if (bitmap != null) {
                val side = if (isEncrypted) 90 else max(bitmap.width, bitmap.height)
                val thumb = ImageLoader.scaleAndSaveImage(
                    bitmap,
                    side.toFloat(),
                    side.toFloat(),
                    if (side > 90) 80 else 55,
                    isEncrypted,
                )
                bitmap.recycle()
                if (thumb != null) {
                    document.thumbs.add(thumb)
                    document.flags = document.flags or 1
                }
            }
        }
        return true
    }
}
