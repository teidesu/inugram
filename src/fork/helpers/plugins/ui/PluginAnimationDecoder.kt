package desu.inugram.helpers.plugins.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import desu.inugram.helpers.plugins.SerialExecutor
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.telegram.messenger.UserConfig
import org.telegram.ui.Components.AnimatedFileNative
import org.telegram.ui.Components.RLottieNative

private fun AnimatedFileNative.readInto(bitmap: Bitmap?): Int = getVideoFrame(bitmap, false, 0f, 0f, false)

/** All operations run on [queue]: decoders are not thread-safe and sequential reads depend on frame order. */
internal sealed class PluginAnimationDecoder(shared: Executor) {
    val queue: Executor = SerialExecutor(shared)

    class Frame(val bitmap: Bitmap, val timestampMs: Int)

    abstract val width: Int
    abstract val height: Int
    abstract val frameCount: Int

    /** milliseconds, `0` when the source does not say */
    abstract val duration: Int

    /** `0` when the source does not say */
    abstract val fps: Int

    abstract fun frame(index: Int): Frame

    abstract fun next(): Frame?

    protected abstract fun release()

    @Volatile
    private var closed = false

    /** called from any thread, so the decoder is released on [queue] behind a frame that may still be decoding */
    fun close() {
        if (closed) return
        closed = true
        if (runCatching { queue.execute { release() } }.isFailure) release()
    }

    protected fun newFrame(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private class Lottie(
        shared: Executor,
        private val lottie: RLottieNative,
        override val width: Int,
        override val height: Int,
    ) : PluginAnimationDecoder(shared) {
        override val frameCount = lottie.frameCount.coerceAtLeast(1)
        override val fps = lottie.fps.coerceAtLeast(1)
        override val duration = (frameCount * 1000L / fps).toInt()
        private var nextIndex = 0

        override fun frame(index: Int): Frame {
            val bitmap = newFrame(width, height)
            // below zero means nothing was drawn
            if (lottie.getFrame(index, bitmap, true) < 0) {
                bitmap.recycle()
                throw IllegalArgumentException("frame $index did not render")
            }
            nextIndex = index + 1
            return Frame(bitmap, (index * 1000L / fps).toInt())
        }

        override fun next(): Frame? = if (nextIndex < frameCount) frame(nextIndex) else null

        override fun release() = lottie.recycle()
    }

    /**
     * ffmpeg scales an opaque frame into any bitmap, so it decodes at the asked size. A transparent frame is
     * only written into a bitmap of its own size, so those decode at source size and are drawn down here.
     */
    private class Video(
        shared: Executor,
        private val video: AnimatedFileNative,
        private val rotation: Int,
        wantedWidth: Int,
        wantedHeight: Int,
        opaque: Boolean,
    ) : PluginAnimationDecoder(shared) {
        private val turned = rotation == 90 || rotation == 270

        override val width = if (wantedWidth > 0) wantedWidth else if (turned) video.height else video.width
        override val height = if (wantedHeight > 0) wantedHeight else if (turned) video.width else video.height

        private val frameWidth = if (turned) height else width
        private val frameHeight = if (turned) width else height
        private val decodeWidth = if (opaque) frameWidth else video.width
        private val decodeHeight = if (opaque) frameHeight else video.height
        override val duration = video.getDuration(TimeUnit.MILLISECONDS)
        override val fps = video.fps.takeIf { it > 0 } ?: DEFAULT_FPS
        override val frameCount = (duration.toLong() * fps / 1000L).toInt().coerceAtLeast(1)

        private var nextIndex = 0

        override fun frame(index: Int): Frame {
            if (index < nextIndex) {
                video.seekToMs(0, false)
                nextIndex = 0
            }
            while (nextIndex < index) {
                if (video.readInto(null) == 0) throw NoFrame(index)
                nextIndex++
            }
            return read(index) { bitmap -> video.readInto(bitmap) }
        }

        override fun next(): Frame? = try {
            read(nextIndex) { bitmap -> video.readInto(bitmap) }
        } catch (e: NoFrame) {
            null
        }

        private inline fun read(index: Int, decode: (Bitmap) -> Int): Frame {
            val bitmap = newFrame(decodeWidth, decodeHeight)
            // ffmpeg answers zero when it decoded nothing; the frame count is estimated, so a source can end early
            if (decode(bitmap) == 0) {
                bitmap.recycle()
                throw NoFrame(index)
            }
            nextIndex = index + 1
            val sized = scaleTo(bitmap, frameWidth, frameHeight)
            val turned = turn(sized, rotation)
            return Frame(turned, video.getProgress(TimeUnit.MILLISECONDS))
        }

        override fun release() = video.recycle()
    }

    private class Still(shared: Executor, private val source: Bitmap) : PluginAnimationDecoder(shared) {
        override val width = source.width
        override val height = source.height
        override val frameCount = 1
        override val duration = 0
        override val fps = 0
        private var read = false

        override fun frame(index: Int): Frame {
            read = true
            return Frame(source.copy(Bitmap.Config.ARGB_8888, false), 0)
        }

        override fun next(): Frame? = if (read) null else frame(0)

        override fun release() = source.recycle()
    }

    private class NoFrame(index: Int) : IllegalArgumentException("the source has no frame $index")

    companion object {
        private const val DEFAULT_FPS = 30

        /** stock's sticker size */
        private const val LOTTIE_SIDE = 512

        /** sniffed from content: bytes the plugin passed were staged without a name. Lottie has no own size */
        fun open(shared: Executor, path: String, width: Int, height: Int): PluginAnimationDecoder {
            val file = File(path)
            if (!file.isFile || file.length() == 0L) {
                throw IllegalArgumentException("there is nothing to decode here")
            }
            if (isLottie(file)) {
                val side = if (width > 0) width else LOTTIE_SIDE
                val lottieHeight = if (height > 0) height else LOTTIE_SIDE
                val lottie = RLottieNative.createFromFile(path, null, side, lottieHeight, false, null, false, 0)
                if (lottie != null) return Lottie(shared, lottie, side, lottieHeight)
            } else {
                val meta = IntArray(META_FIELDS)
                val video = AnimatedFileNative.createDecoderFrom(path, meta, UserConfig.selectedAccount, 0, null, false)
                if (video != null) {
                    if (video.width > 0 && video.height > 0) {
                        val opaque = (width <= 0 && height <= 0) || isOpaque(video)
                        return Video(shared, video, video.rotation, width, height, opaque)
                    }
                    video.recycle()
                }
            }
            val still = BitmapFactory.decodeFile(path) ?: throw IllegalArgumentException("this is not an animation the device can decode")
            return Still(shared, still)
        }

        fun readFirstFrame(path: String, maxSide: Int): Bitmap? {
            val meta = IntArray(META_FIELDS)
            val video = AnimatedFileNative.createDecoderFrom(path, meta, UserConfig.selectedAccount, 0, null, false)
                ?: return null
            try {
                if (video.width <= 0 || video.height <= 0) return null
                val scale = minOf(1f, maxSide.toFloat() / maxOf(video.width, video.height))
                val width = (video.width * scale).toInt().coerceAtLeast(1)
                val height = (video.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                if (video.readInto(bitmap) == 0) {
                    bitmap.recycle()
                    return null
                }
                if (video.isLastFrameOpaque() || (width == video.width && height == video.height)) {
                    return turn(bitmap, video.rotation)
                }
                bitmap.recycle()
                video.seekToMs(0, false)
                val full = Bitmap.createBitmap(video.width, video.height, Bitmap.Config.ARGB_8888)
                if (video.readInto(full) == 0) {
                    full.recycle()
                    return null
                }
                return turn(scaleTo(full, width, height), video.rotation)
            } finally {
                video.recycle()
            }
        }

        /** native only reports opacity for a frame it was handed a bitmap for, and scales exactly the opaque formats */
        private fun isOpaque(video: AnimatedFileNative): Boolean {
            val probe = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            try {
                if (video.readInto(probe) == 0) return true
                return video.isLastFrameOpaque()
            } finally {
                probe.recycle()
                video.seekToMs(0, false)
            }
        }

        /** recycles the original when a new one was made */
        private fun scaleTo(bitmap: Bitmap, width: Int, height: Int): Bitmap {
            if (bitmap.width == width && bitmap.height == height) return bitmap
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            if (scaled !== bitmap) bitmap.recycle()
            return scaled
        }

        /** stock's `metaData`, whose fields [AnimatedFileNative] names */
        private const val META_FIELDS = 8

        private const val GZIP_FIRST = 0x1f
        private const val GZIP_SECOND = 0x8b

        /** a lottie sticker is gzipped json, and tlottie reads plain json too */
        private fun isLottie(file: File): Boolean {
            val head = ByteArray(2)
            val read = file.inputStream().use { it.read(head) }
            if (read < 2) return false
            val first = head[0].toInt() and 0xff
            return (first == GZIP_FIRST && (head[1].toInt() and 0xff) == GZIP_SECOND) || first == '{'.code
        }

        private fun turn(bitmap: Bitmap, rotation: Int): Bitmap {
            if (rotation % 360 == 0) return bitmap
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (turned !== bitmap) bitmap.recycle()
            return turned
        }
    }
}
