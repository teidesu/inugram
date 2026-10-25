package desu.inugram.helpers.media

import android.graphics.Bitmap
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import android.util.Log
import desu.inugram.InuConfig
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.ImageLoader
import org.telegram.messenger.MediaController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.SendMessagesHelper
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.voip.AnimatedFileInfo
import java.io.File
import java.io.FileInputStream

object MediaSendHelper {
    @JvmStatic
    fun updateVideoConversionSupport(path: String, params: IntArray, fileOffset: Long) {
        if (params[AnimatedFileInfo.PARAM_NUM_SUPPORTED_VIDEO_CODEC] != 0 ||
            params[AnimatedFileInfo.PARAM_NUM_WIDTH] <= 0 ||
            params[AnimatedFileInfo.PARAM_NUM_HEIGHT] <= 0
        ) return

        val extractor = MediaExtractor()
        try {
            if (fileOffset > 0) {
                FileInputStream(path).use { extractor.setDataSource(it.fd, fileOffset, Long.MAX_VALUE) }
            } else {
                extractor.setDataSource(path)
            }
            val videoIndex = MediaController.findTrack(extractor, false)
            if (videoIndex < 0) return
            val format = extractor.getTrackFormat(videoIndex)
            if (MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) != null) {
                params[AnimatedFileInfo.PARAM_NUM_SUPPORTED_VIDEO_CODEC] = 1
            }
        } catch (e: Exception) {
            Log.d("Inugram", "Unable to check video conversion support", e)
        } finally {
            extractor.release()
        }
    }

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
        // A cached file must be uploaded again for Telegram to receive its video attributes.
        document.id = 0
        document.access_hash = 0
        document.file_reference = ByteArray(0)
        document.dc_id = 0
        document.thumbs.clear()
        document.flags = document.flags and 1.inv()
        val thumb = createVideoThumb(path, isEncrypted)
        if (thumb != null) {
            document.thumbs.add(thumb)
            document.flags = document.flags or 1
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
