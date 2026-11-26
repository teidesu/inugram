package desu.inugram.helpers.media

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import desu.inugram.InuConfig
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Sticker frame shape: aspect ratio and corner radius. Telegram accepts stickers whose long side
 * is 512px and short side is at most 512px. The radius is a percentage of half the short side,
 * so 25 matches the stock 1/8 rounding and 100 is a pill or circle.
 */
object StickerFrameHelper {
    @JvmField val ASPECTS = listOf("1:1", "4:3", "3:4", "16:9", "9:16", "2:1", "1:2")
    const val STOCK_RADIUS = 25

    private fun enabled() = InuConfig.STICKER_TRANSFORM_CONTROLS.value

    @JvmStatic fun aspectKey(): String = InuConfig.STICKER_FRAME_ASPECT.value.takeIf { it in ASPECTS } ?: ASPECTS[0]

    /** Width divided by height. */
    @JvmStatic fun aspect(): Float {
        if (!enabled()) return 1f
        val (w, h) = aspectKey().split(':').map { it.toFloat() }
        return w / h
    }

    @JvmStatic fun radiusPercent() = if (enabled()) InuConfig.STICKER_FRAME_RADIUS.value.coerceIn(0, 100) else STOCK_RADIUS

    /** Stock geometry keeps its own constants; only a changed shape takes the fork paths. */
    @JvmStatic fun isCustom() = enabled() && (aspectKey() != ASPECTS[0] || radiusPercent() != STOCK_RADIUS)

    @JvmStatic fun frameWidth(side: Float) = side * min(1f, aspect())
    @JvmStatic fun frameHeight(side: Float) = side * min(1f, 1f / aspect())
    @JvmStatic fun outputWidth(maxSide: Int) = frameWidth(maxSide.toFloat()).roundToInt()
    @JvmStatic fun outputHeight(maxSide: Int) = frameHeight(maxSide.toFloat()).roundToInt()
    @JvmStatic fun radius(width: Float, height: Float) = min(width, height) / 2f * radiusPercent() / 100f

    /** Shrinks a stock square frame around its center to the chosen shape and returns the radius. */
    @JvmStatic fun applyFrame(rect: RectF): Float {
        val side = rect.width()
        val w = frameWidth(side)
        val h = frameHeight(side)
        rect.set(rect.centerX() - w / 2, rect.centerY() - h / 2, rect.centerX() + w / 2, rect.centerY() + h / 2)
        return radius(w, h)
    }

    /** Unit square outline clip, stretched to the frame by the stock outline matrix. */
    @JvmStatic fun unitFramePath(path: Path) {
        val a = aspect()
        val r = radiusPercent() / 200f
        path.rewind()
        path.addRoundRect(RectF(0f, 0f, 1f, 1f), r * min(1f, 1f / a), r * min(1f, a), Path.Direction.CW)
    }

    private val previewClip = Path()

    /** Draws the animated-entity overlay of a reshaped sticker preview; stock draws the default one. */
    @JvmStatic fun drawPreviewOverlay(canvas: Canvas, overlay: View, size: Int, alpha: Float): Boolean {
        val ow = overlay.width.toFloat()
        val oh = overlay.height.toFloat()
        if (ow <= 0 || oh <= 0 || ow == oh && !isCustom()) return false
        val scale = size / max(ow, oh)
        overlay.alpha = alpha
        canvas.save()
        canvas.translate(-ow * scale / 2, -oh * scale / 2)
        canvas.scale(scale, scale)
        previewClip.rewind()
        val r = radius(ow, oh)
        previewClip.addRoundRect(RectF(0f, 0f, ow, oh), r, r, Path.Direction.CW)
        canvas.clipPath(previewClip)
        overlay.draw(canvas)
        canvas.restore()
        return true
    }
}
