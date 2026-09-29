package desu.inugram.helpers.chat

import android.graphics.SurfaceTexture
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import desu.inugram.InuConfig
import org.telegram.messenger.Utilities
import org.telegram.messenger.camera.Camera2Session
import org.telegram.messenger.camera.CameraSession
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ZoomControlView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object RoundRecorderHelper {
    // physical finger spread is capped by the small recorder container;
    // 3x finger ratio is what comfortably reaches edge-to-edge.
    private const val MAX_PINCH_RATIO = 3f

    @JvmStatic
    fun pinchDeltaT(fingerRatio: Float): Float {
        val dR = fingerRatio - 1f
        val magnitude = minOf(1f, kotlin.math.abs(dR) / (MAX_PINCH_RATIO - 1f))
        return kotlin.math.sign(dR) * magnitude * magnitude
    }

    @JvmStatic
    fun mapZoomT(t: Float, min: Float, max: Float): Float =
        if (InuConfig.ROUND_RECORDER_EXPONENTIAL_ZOOM.value && min > 0f) {
            (min * Math.pow((max / min).toDouble(), t.toDouble())).toFloat()
        } else {
            min + t * (max - min)
        }

    private fun tFromZoom(zoom: Float, min: Float, max: Float): Float {
        if (max <= min) return 0f
        val t = if (InuConfig.ROUND_RECORDER_EXPONENTIAL_ZOOM.value && min > 0f) {
            (Math.log((zoom / min).toDouble()) / Math.log((max / min).toDouble())).toFloat()
        } else {
            (zoom - min) / (max - min)
        }
        return t.coerceIn(0f, 1f)
    }

    // c2 and c1 are mutually exclusive (depends on useCamera2); pass both, only the live one matters
    @JvmStatic
    fun currentZoomT(c2: Camera2Session?, c1: CameraSession?): Float = when {
        c2 != null -> tFromZoom(c2.zoom, c2.minZoom, c2.maxZoom)
        c1 != null -> c1.currentZoom
        else -> 0f
    }

    @JvmStatic
    fun applyZoomT(slider: ZoomControlView?, c2: Camera2Session?, c1: CameraSession?, t: Float) {
        val clamped = t.coerceIn(0f, 1f)
        // Camera1 driver maps [0,1] through a log ratio table; Camera2 gets the explicit zoom factor.
        c2?.setZoom(mapZoomT(clamped, c2.minZoom, c2.maxZoom))
        c1?.setZoom(clamped)
        slider?.setZoom(clamped, false)
    }

    @JvmStatic
    fun syncSlider(slider: ZoomControlView?, c2: Camera2Session?, c1: CameraSession?) {
        slider?.setZoom(currentZoomT(c2, c1), false)
    }

    @JvmStatic
    fun attachZoomSlider(parent: FrameLayout, onZoom: Utilities.Callback<Float>): ZoomControlView? {
        if (!InuConfig.ROUND_RECORDER_ZOOM_SLIDER.value) return null
        val view = ZoomControlView(parent.context)
        view.alpha = 0f
        view.visibility = View.GONE
        view.setZoom(0f, false)
        view.setDelegate { z -> onZoom.run(z) }
        parent.addView(
            view,
            LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, 50f,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM,
                24f, 0f, 24f, 70f,
            ),
        )
        return view
    }

    @JvmStatic
    fun setSliderVisible(slider: ZoomControlView?, visible: Boolean) {
        if (slider == null) return
        slider.animate().cancel()
        if (visible) {
            slider.visibility = View.VISIBLE
            slider.animate().alpha(1f).setDuration(180).start()
        } else {
            slider.animate().alpha(0f).setDuration(180)
                .withEndAction { slider.visibility = View.GONE }
                .start()
        }
    }

    @Volatile
    private var pendingDualCleanup: CountDownLatch? = null

    @JvmStatic
    fun awaitPendingDualCleanup() {
        val latch = pendingDualCleanup ?: return
        if (latch.count == 0L) return
        try {
            latch.await(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
    }

    // cancel triggers cameraDevice.close() which can take 100-300ms per camera.
    // run sync closes off the UI thread in parallel and gate the next dual open on completion
    // to avoid HAL re-open races with not-yet-released sensors.
    @JvmStatic
    fun destroyDualAsync(sessions: Array<Camera2Session?>) {
        val toClose = sessions.copyOf()
        for (i in sessions.indices) sessions[i] = null
        val latch = CountDownLatch(1)
        pendingDualCleanup = latch
        Thread({
            val inner = CountDownLatch(toClose.size)
            for (s in toClose) {
                if (s == null) { inner.countDown(); continue }
                Thread({
                    try { s.destroy(false) } finally { inner.countDown() }
                }, "inu-camera2-close").start()
            }
            try { inner.await() } catch (_: InterruptedException) {}
            latch.countDown()
        }, "inu-camera2-cleanup").start()
    }

    // configuring both CaptureSessions concurrently races in the HAL and silently starves
    // the secondary's stream (rear-first init repro). serialize: primary first, secondary chains via whenDone.
    @JvmStatic
    fun openDualSerialized(
        sessions: Array<Camera2Session?>,
        index: Int,
        primary: Int,
        surface: SurfaceTexture,
    ) {
        val self = sessions[index] ?: return
        if (index == primary) {
            self.open(surface)
        } else {
            sessions[primary]?.whenDone {
                sessions[index]?.open(surface)
            }
        }
    }
}
