package desu.inugram.helpers.plugins.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import desu.inugram.helpers.plugins.SerialExecutor
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.Executor

/**
 * The mp4 writer behind `inu.canvas.createEncoder`: a frame at a time out of whatever the plugin
 * drew, through the device's own h264 encoder. The result is silent, which is what telegram's
 * animations are.
 *
 * A frame is taken in two halves. [snapshot] runs on the engine's thread, where the source bitmap
 * is owned, and copies its memory into a buffer the encoder lends out; [addFrame] then runs on
 * [queue] with that buffer and gives it back. `MediaCodec` may not be used from two threads at
 * once and a plugin is free to add a frame without awaiting the one before it, so the engine's own
 * ordering is not enough.
 */
internal class PluginVideoEncoder private constructor(
    private val output: File,
    val width: Int,
    val height: Int,
    private val codec: MediaCodec,
    private val muxer: MediaMuxer,
    shared: Executor,
) {
    val queue: Executor = SerialExecutor(shared)

    private val info = MediaCodec.BufferInfo()
    private val spare = ArrayDeque<ByteBuffer>()
    private var track = -1
    private var muxing = false
    private var presentationUs = 0L
    private var failure: Throwable? = null

    @Volatile
    private var finished = false

    @Volatile
    private var closed = false

    /** engine thread only: the one bitmap a source of another size is drawn into */
    private var scaled: Bitmap? = null

    /**
     * The source's pixels as they are now, so it may go on being drawn on: a copy of the bitmap's
     * memory, which is a memcpy rather than the unpremultiplying walk `getPixels` does. As many
     * buffers circulate as the host keeps frames in flight; one more is the plugin's to fill.
     */
    fun snapshot(source: Bitmap): ByteBuffer {
        val buffer = synchronized(spare) { spare.poll() }
            ?: ByteBuffer.allocateDirect(width * height * 4)
        val fitted = fit(source)
        buffer.clear()
        fitted.copyPixelsToBuffer(buffer)
        return buffer
    }

    fun addFrame(pixels: ByteBuffer, durationMs: Double) {
        try {
            check(!finished && !closed) { "this encoder is closed" }
            failure?.let { throw it }
            var attempts = INPUT_ATTEMPTS
            while (true) {
                drain(false)
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val length = write(index, pixels)
                    codec.queueInputBuffer(index, 0, length, presentationUs, 0)
                    break
                }
                if (--attempts <= 0) throw IllegalStateException("the encoder never asked for a frame")
            }
            presentationUs += (durationMs * 1000.0).toLong().coerceAtLeast(1L)
        } catch (e: Throwable) {
            if (failure == null) failure = e
            throw e
        } finally {
            synchronized(spare) { if (spare.size < SPARE_BUFFERS) spare.add(pixels) }
        }
    }

    fun finish(): File {
        check(!finished && !closed) { "this encoder is closed" }
        failure?.let { throw it }
        var attempts = INPUT_ATTEMPTS
        while (true) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
            drain(false)
            if (--attempts <= 0) throw IllegalStateException("the encoder never asked for a frame")
        }
        drain(true)
        if (!muxing) throw IllegalStateException("the encoder wrote no video")
        info.set(0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        muxer.writeSampleData(track, ByteBuffer.allocate(0), info)
        codec.stop()
        codec.release()
        muxer.stop()
        muxer.release()
        finished = true
        return output
    }

    /**
     * What [finish] does when the encoding is abandoned instead: nothing is kept, the file included.
     * Called from whichever thread the plugin's handle went away on, so the teardown itself is put
     * on [queue] behind whatever frame is still in flight - which sees `closed` and gives up.
     */
    fun close() {
        if (closed) return
        closed = true
        scaled?.recycle()
        scaled = null
        if (runCatching { queue.execute { discard() } }.isFailure) discard()
    }

    /**
     * Whether there is anything to abandon is decided here rather than in [close], which runs on the
     * thread the handle went away on: a [finish] that already handed the file over may be settling
     * on [queue] at that very moment, and deleting its result then would answer with a blob over a
     * file that is gone.
     */
    private fun discard() {
        if (finished) return
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { if (muxing) muxer.stop() }
        runCatching { muxer.release() }
        output.delete()
    }

    /** a source that is not the encoder's size is drawn into it whole, centred, on black */
    private fun fit(source: Bitmap): Bitmap {
        if (source.width == width && source.height == height) return source
        val into = scaled ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { scaled = it }
        val scale = minOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val w = source.width * scale
        val h = source.height * scale
        val canvas = Canvas(into)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(
            source,
            null,
            RectF((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        return into
    }

    /** fills the encoder's buffer and answers how many of its bytes the frame reached */
    private fun write(index: Int, pixels: ByteBuffer): Int {
        val image = codec.getInputImage(index)
        val written = if (image != null) {
            val planes = image.planes
            convert(
                pixels,
                plane(planes[0].buffer, planes[0].rowStride, planes[0].pixelStride),
                plane(planes[1].buffer, planes[1].rowStride, planes[1].pixelStride),
                plane(planes[2].buffer, planes[2].rowStride, planes[2].pixelStride),
            )
        } else {
            val buffer = codec.getInputBuffer(index) ?: throw IllegalStateException("the encoder gave no buffer to fill")
            val luma = width * height
            val chroma = luma / 4
            when (codec.inputFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT)) {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar,
                -> convert(
                    pixels,
                    Plane(buffer, 0, width, 1),
                    Plane(buffer, luma, width / 2, 1),
                    Plane(buffer, luma + chroma, width / 2, 1),
                )
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar,
                -> convert(
                    pixels,
                    Plane(buffer, 0, width, 1),
                    Plane(buffer, luma, width, 2),
                    Plane(buffer, luma + 1, width, 2),
                )
                else -> throw IllegalStateException("this device's encoder wants a frame laid out in a way we cannot write")
            }
        }
        if (written < 0) throw IllegalStateException("the encoder's buffer could not take a frame this size")
        return written
    }

    private class Plane(val buffer: ByteBuffer, val offset: Int, val rowStride: Int, val pixelStride: Int)

    /** an `Image` plane is a slice of the input buffer; an additional position is relative to that slice */
    private fun plane(buffer: ByteBuffer, rowStride: Int, pixelStride: Int) =
        Plane(buffer, buffer.position(), rowStride, pixelStride)

    /**
     * BT.601 limited range, which is what an h264 encoder takes unless it is told otherwise, done
     * in native code: see `jni/pixels.rs`. Answers the extent the three planes reach, which is the
     * length the frame was written at - not `w * h * 3 / 2`, since a plane the device gave us may
     * be padded to a stride of its own - or `-1`.
     */
    private fun convert(pixels: ByteBuffer, y: Plane, u: Plane, v: Plane): Int = NativePixels.rgbaToYuv420(
        pixels, width, height,
        y.buffer, y.offset, y.rowStride, y.pixelStride,
        u.buffer, u.offset, u.rowStride, u.pixelStride,
        v.buffer, v.offset, v.rowStride, v.pixelStride,
    )

    private fun drain(untilEnd: Boolean) {
        val deadline = System.currentTimeMillis() + DRAIN_DEADLINE_MILLIS
        while (true) {
            val status = codec.dequeueOutputBuffer(info, if (untilEnd) TIMEOUT_US else 0)
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEnd) return
                if (System.currentTimeMillis() > deadline) throw IllegalStateException("the encoder never finished")
                continue
            }
            if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                startMuxing()
                continue
            }
            if (status < 0) continue
            val buffer = codec.getOutputBuffer(status) ?: throw IllegalStateException("the encoder gave no buffer back")
            val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                if (!muxing) startMuxing()
                info.flags = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
                muxer.writeSampleData(track, buffer, info)
            }
            codec.releaseOutputBuffer(status, false)
            if (end) return
        }
    }

    private fun startMuxing() {
        if (muxing) return
        track = muxer.addTrack(codec.outputFormat)
        muxer.start()
        muxing = true
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val INPUT_ATTEMPTS = 200
        /**
         * How many frames may be queued behind the encoder before `addFrame` stops resolving on
         * arrival: enough that decoding, drawing and encoding overlap, few enough that a plugin
         * awaiting each frame holds about three frames' worth of pixels.
         */
        const val FRAMES_IN_FLIGHT = 2

        /** the host's frames in flight, plus the one being taken */
        private const val SPARE_BUFFERS = FRAMES_IN_FLIGHT + 1
        private const val DRAIN_DEADLINE_MILLIS = 10_000L

        /** roughly what telegram's own converter asks for at these sizes */
        private const val BITS_PER_PIXEL_PER_SECOND = 0.14

        fun open(shared: Executor, output: File, width: Int, height: Int, fps: Int, bitrate: Long): PluginVideoEncoder {
            val rate = if (bitrate > 0) {
                bitrate
            } else {
                (width.toLong() * height * fps * BITS_PER_PIXEL_PER_SECOND).toLong().coerceIn(MIN_BITRATE, MAX_BITRATE)
            }
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, rate.toInt())
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_SECONDS)
            }
            var codec: MediaCodec? = null
            var muxer: MediaMuxer? = null
            try {
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                return PluginVideoEncoder(output, width, height, codec, muxer, shared)
            } catch (e: Throwable) {
                runCatching { codec?.release() }
                runCatching { muxer?.release() }
                output.delete()
                throw e
            }
        }

        private const val KEYFRAME_SECONDS = 1
        private const val MIN_BITRATE = 200_000L
        private const val MAX_BITRATE = 8_000_000L
    }
}
