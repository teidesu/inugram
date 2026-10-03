package desu.inugram.helpers.media

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.HardwareBufferRenderer
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.util.Log
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.Utilities
import java.util.concurrent.Executor

/**
 * An immutable GPU snapshot of the chat and attachment sheet. Fading a black tint over it keeps
 * the backdrop fade while the viewer is opaque, allowing underlying windows to stop drawing
 * sooner and reducing rendering work during opening.
 *
 * Allocate on a worker; record and close on the UI thread.
 */
@TargetApi(34)
internal class AttachmentBackdrop(private val width: Int, private val height: Int) : AutoCloseable {
    private val buffer: HardwareBuffer
    private val renderer: HardwareBufferRenderer
    private val node = RenderNode("inu.attachmentBackdrop")
    // Opening pre-draw can run before queued UI callbacks. Publish the finished GPU result directly.
    @Volatile var bitmap: Bitmap? = null
        private set
    // A wrapped bitmap must not observe another render writing to the same buffer.
    private var renderStarted = false
    private var inFlight = false
    private var closeRequested = false

    init {
        buffer = HardwareBuffer.create(width, height, HardwareBuffer.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        try {
            renderer = HardwareBufferRenderer(buffer)
        } catch (error: RuntimeException) {
            buffer.close()
            failure(error)
            throw error
        } catch (error: OutOfMemoryError) {
            buffer.close()
            failure(error)
            throw error
        }
        node.setPosition(0, 0, width, height)
        renderer.setContentRoot(node)
    }

    fun render(record: (Canvas) -> Unit) {
        check(!renderStarted && !closeRequested)
        renderStarted = true
        try {
            val canvas = node.beginRecording(width, height)
            try {
                record(canvas)
            } finally {
                node.endRecording()
            }
        } catch (error: RuntimeException) {
            failure(error)
            close()
            return
        } catch (error: OutOfMemoryError) {
            failure(error)
            close()
            return
        }

        inFlight = true
        try {
            renderer.obtainRenderRequest().draw(Executor { Utilities.globalQueue.postRunnable(it) }) { result ->
                var renderedBitmap: Bitmap? = null
                try {
                    result.fence.use { fence ->
                        // The callback can precede GPU completion; wait on this worker before publishing.
                        val signaled = fence.awaitForever()
                        if (signaled && result.status == HardwareBufferRenderer.RenderResult.SUCCESS) {
                            renderedBitmap = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))
                        }
                    }
                    if (renderedBitmap == null) {
                        Log.d("InuMediaBackdrop", "GPU backdrop render failed: ${result.status}")
                    }
                } catch (error: RuntimeException) {
                    failure(error)
                } catch (error: OutOfMemoryError) {
                    failure(error)
                }
                bitmap = renderedBitmap
                AndroidUtilities.runOnUIThread {
                    inFlight = false
                    close()
                }
            }
        } catch (error: RuntimeException) {
            inFlight = false
            failure(error)
            close()
        } catch (error: OutOfMemoryError) {
            inFlight = false
            failure(error)
            close()
        }
    }

    override fun close() {
        closeRequested = true
        if (inFlight || renderer.isClosed) return
        renderer.close()
        node.discardDisplayList()
        // wrapHardwareBuffer retains its own reference, so closing this handle keeps the bitmap valid.
        buffer.close()
    }

    private fun failure(error: Throwable) {
        Log.d("InuMediaBackdrop", "GPU backdrop unavailable", error)
    }
}
