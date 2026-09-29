package desu.inugram.helpers.plugins.ui

import desu.inugram.helpers.plugins.QuickJs
import java.nio.ByteBuffer

/** per-pixel conversion is too slow in jvm code. Lives in the library [QuickJs] loads */
internal object NativePixels {
    init {
        QuickJs.ensureLoaded()
    }

    /** planes laid out as `MediaCodec` describes; answers the length reached or `-1`. Buffers must be direct */
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
