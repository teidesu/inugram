package desu.inugram.helpers.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import desu.inugram.InuConfig
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.ImageLoader
import org.telegram.messenger.MessageObject
import org.telegram.messenger.SendMessagesHelper
import org.telegram.tgnet.TLRPC
import java.io.File

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
        val attribute = createVideoAttribute(path, isEncrypted) ?: return false
        document.attributes.add(attribute)
        if (document.thumbs.isEmpty()) {
            createVideoThumb(path, isEncrypted)?.let {
                document.thumbs.add(it)
                document.flags = document.flags or 1
            }
        }
        return true
    }

    /** what stock's `prepareSendingVideo` puts on a video it was given, read off the file the way it reads it; null when the file does not read as a video */
    @JvmStatic
    fun createVideoAttribute(path: String, isEncrypted: Boolean): TLRPC.TL_documentAttributeVideo? {
        val attribute = newVideoAttribute(isEncrypted)
        SendMessagesHelper.fillVideoAttribute(path, attribute, null)
        if (attribute.w <= 0 || attribute.h <= 0 || attribute.duration <= 0) return null
        return attribute
    }

    class VideoDescription(val attribute: TLRPC.TL_documentAttributeVideo, val hasAudio: Boolean)

    /**
     * [createVideoAttribute] and whether the container carries audio, read in one pass over it:
     * stock's reader answers the former alone, and opening a container is most of what either
     * costs. A file the device cannot read as a video is null; one it can read but cannot say
     * about is taken to have audio.
     */
    @JvmStatic
    fun describeVideo(path: String, isEncrypted: Boolean): VideoDescription? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val attribute = newVideoAttribute(isEncrypted)
            attribute.w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            attribute.h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            attribute.duration = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val w = attribute.w
                attribute.w = attribute.h
                attribute.h = w
            }
            if (attribute.w <= 0 || attribute.h <= 0 || attribute.duration <= 0) return null
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null
            return VideoDescription(attribute, hasAudio)
        } catch (e: Exception) {
            FileLog.e(e)
        } finally {
            runCatching { retriever.release() }
        }
        // stock's reader has a MediaPlayer fallback for what the retriever refuses
        val attribute = createVideoAttribute(path, isEncrypted) ?: return null
        return VideoDescription(attribute, hasAudio = true)
    }

    private fun newVideoAttribute(isEncrypted: Boolean): TLRPC.TL_documentAttributeVideo =
        if (isEncrypted) {
            TLRPC.TL_documentAttributeVideo_layer159()
        } else {
            TLRPC.TL_documentAttributeVideo().apply { supports_streaming = true }
        }

    /** the cover stock saves for a video it sends, at the side and quality it saves it outside and inside a secret chat */
    @JvmStatic
    fun createVideoThumb(path: String, isEncrypted: Boolean): TLRPC.PhotoSize? =
        saveVideoThumb(readFirstFrame(path), isEncrypted)

    /** the frame stock's own sends take their cover from */
    @JvmStatic
    fun readFirstFrame(path: String): Bitmap? =
        SendMessagesHelper.createVideoThumbnailAtTime(path, 0)
            ?: SendMessagesHelper.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND)

    /** saves [frame] the way stock saves a video's cover, and takes it: the bitmap is recycled either way */
    @JvmStatic
    fun saveVideoThumb(frame: Bitmap?, isEncrypted: Boolean): TLRPC.PhotoSize? {
        val bitmap = frame ?: return null
        val side = if (isEncrypted) 90f else 320f
        return try {
            ImageLoader.scaleAndSaveImage(bitmap, side, side, if (isEncrypted) 55 else 80, isEncrypted)
        } catch (e: Exception) {
            FileLog.e(e)
            null
        } finally {
            bitmap.recycle()
        }
    }
}
