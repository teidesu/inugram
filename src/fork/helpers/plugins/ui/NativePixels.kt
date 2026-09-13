package desu.inugram.helpers.plugins.ui

import desu.inugram.helpers.plugins.QuickJs
import java.nio.ByteBuffer

/**
 * The encoder's colour conversion, in the engine's own library over the `yuv` crate: a frame's
 * worth of per-pixel arithmetic is the one thing the plugin engine does that jvm code is too slow
 * for. The library is the one [QuickJs] loads.
 */
internal object NativePixels {
    init {
        QuickJs.ensureLoaded()
    }

    /**
     * Writes `width x height` RGBA pixels - a bitmap's memory as `copyPixelsToBuffer` hands it
     * over - into the three planes of an encoder's input, laid out as `MediaCodec` describes
     * them, and answers the length the frame reaches in the buffer, or `-1` when a plane cannot
     * take it. Every buffer must be direct.
     */
    @Suppress("LongParameterList")
    external fun rgbaToYuv420(
        pixels: ByteBuffer,
        width: Int,
        height: Int,
        y: ByteBuffer,
        yOffset: Int,
        yRowStride: Int,
        yPixelStride: Int,
        u: ByteBuffer,
        uOffset: Int,
        uRowStride: Int,
        uPixelStride: Int,
        v: ByteBuffer,
        vOffset: Int,
        vRowStride: Int,
        vPixelStride: Int,
    ): Int
}
