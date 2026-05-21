package desu.inugram.helpers

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Material 3 styling for [org.telegram.ui.Components.Switch], drawn in place of the stock
 * pill+thumb when [desu.inugram.InuConfig.MATERIAL3_SWITCHES] is on.
 *
 * Geometry mirrors the MDC `MaterialSwitch` spec (track 52x32 r16, thumb 16/24dp), rendered at a
 * fixed size derived from [TARGET_TRACK_W] and centered in the host frame.
 */
object M3SwitchHelper {
    // MDC spec in track-viewport units (52x32); everything is multiplied by `scale` to fit the view.
    private const val TRACK_W = 52f
    private const val TRACK_H = 32f
    // Rendered track width in dp; height follows from the MDC ratio. Kept <= the narrowest switch
    // frame (37dp) so it never clips horizontally — don't raise without widening those frames.
    private const val TARGET_TRACK_W = 36f
    private const val RADIUS = 16f
    private const val THUMB_R_OFF = 8f
    private const val THUMB_R_ON = 12f
    private const val THUMB_CX_OFF = 16f
    private const val THUMB_CX_ON = 36f
    // Peak morph shape (MDC `mtrl_switch_thumb_path_morphing`): a 32x22 lozenge, corner radius 11.
    private const val MORPH_HALF_W = 16f
    private const val MORPH_HALF_H = 11f
    private const val MORPH_RADIUS = 11f
    // Thumb icons (e.g. the locked-switch padlock) drawn slightly inside the thumb, nudged up since
    // the padlock's visual mass sits low.
    private const val ICON_SCALE = 0.7f
    private const val ICON_NUDGE_UP = 0.5f
    // Stock checkmark is sized for the stock thumb; shrink it for the smaller M3 thumb.
    private const val CHECK_SCALE = 0.8f
    private const val CHECK_NUDGE_RIGHT = 0.5f
    private const val ICON_STROKE = 2f
    private const val CROSS_STROKE = 1.5f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = AndroidUtilities.dpf2(ICON_STROKE)
    }
    private val rectF = RectF()

    // Thumb icons (padlock) are forced white; the stock track-color tint is grey, invisible on our
    // grey thumb. Shared filter so we don't reallocate one per frame.
    private val whiteFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)

    @JvmStatic
    fun draw(
        measuredWidth: Int,
        measuredHeight: Int,
        progress: Float,
        drawIconType: Int,
        iconProgress: Float,
        iconDrawable: Drawable?,
        iconVisibility: Float,
        trackColorKey: Int,
        trackCheckedColorKey: Int,
        thumbCheckedColorKey: Int,
        resourcesProvider: Theme.ResourcesProvider?,
        canvas: Canvas,
    ) {
        val offColor = Theme.getColor(trackColorKey, resourcesProvider)
        val onColor = Theme.getColor(trackCheckedColorKey, resourcesProvider)
        val thumbOnColor = Theme.getColor(thumbCheckedColorKey, resourcesProvider)

        // Fixed size, centered in the host frame. Width is constant; height follows the MDC 52:32
        // ratio. Frames shorter than `h` clip the overflow (parent clipChildren) — acceptable, the
        // overrun is ~1dp on the track caps and the thumb always fits.
        val scale = AndroidUtilities.dpf2(TARGET_TRACK_W) / TRACK_W
        val trackW = TRACK_W * scale
        val h = TRACK_H * scale
        val left = (measuredWidth - trackW) / 2f
        val top = (measuredHeight - h) / 2f

        // Unchecked outline crossfades into the checked fill via opposing alphas as progress runs.
        rectF.set(left, top, left + trackW, top + h)
        val radius = RADIUS * scale
        val fillAlpha = (Color.alpha(onColor) * progress).roundToInt()
        if (fillAlpha > 0) {
            fillPaint.color = onColor
            fillPaint.alpha = fillAlpha
            canvas.drawRoundRect(rectF, radius, radius, fillPaint)
        }
        val outlineAlpha = (Color.alpha(offColor) * (1f - progress)).roundToInt()
        if (outlineAlpha > 0) {
            val sw = AndroidUtilities.dpf2(2f)
            strokePaint.color = offColor
            strokePaint.alpha = outlineAlpha
            strokePaint.strokeWidth = sw
            rectF.inset(sw / 2f, sw / 2f)
            canvas.drawRoundRect(rectF, radius - sw / 2f, radius - sw / 2f, strokePaint)
        }

        // Mid-travel the thumb morphs into a horizontal lozenge (peaks at progress 0.5, circle at rest).
        val cx = left + scale * (THUMB_CX_OFF + (THUMB_CX_ON - THUMB_CX_OFF) * progress)
        val cy = top + h / 2f
        val rest = scale * (THUMB_R_OFF + (THUMB_R_ON - THUMB_R_OFF) * progress)
        val morph = 1f - abs(2f * progress - 1f)
        val halfW = rest + (MORPH_HALF_W * scale - rest) * morph
        val halfH = rest + (MORPH_HALF_H * scale - rest) * morph
        val cr = rest + (MORPH_RADIUS * scale - rest) * morph
        val thumbColor = ColorUtils.blendARGB(offColor, thumbOnColor, progress)
        thumbPaint.color = thumbColor
        rectF.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        canvas.drawRoundRect(rectF, cr, cr, thumbPaint)

        // Cross (off) is white, check (on) is the accent — blend white->accent by progress as the
        // X morphs into the checkmark.
        val iconColor = ColorUtils.blendARGB(Color.WHITE, onColor, progress)
        if (iconDrawable != null) {
            if (iconDrawable.colorFilter !== whiteFilter) iconDrawable.colorFilter = whiteFilter
            if (iconVisibility > 0f) {
                val needScale = iconVisibility < 1f
                if (needScale) {
                    canvas.save()
                    canvas.scale(iconVisibility, iconVisibility, cx, cy)
                }
                val ix = cx.roundToInt()
                val iy = (cy - AndroidUtilities.dpf2(ICON_NUDGE_UP)).roundToInt()
                val hw = (iconDrawable.intrinsicWidth * ICON_SCALE / 2f).roundToInt()
                val hh = (iconDrawable.intrinsicHeight * ICON_SCALE / 2f).roundToInt()
                iconDrawable.setBounds(ix - hw, iy - hh, ix + hw, iy + hh)
                iconDrawable.draw(canvas)
                if (needScale) canvas.restore()
            }
        } else if (drawIconType == 1) {
            drawCheckmark(canvas, cx.roundToInt(), cy.roundToInt(), progress, iconColor)
        } else if (drawIconType == 2) {
            drawDot(canvas, cx.roundToInt(), cy.roundToInt(), iconProgress, iconColor)
        }
    }

    // Ported from stock Switch.onDraw (drawIconType 1): an X morphing into a checkmark by `progress`.
    private fun drawCheckmark(canvas: Canvas, cx0: Int, cy0: Int, progress: Float, color: Int) {
        iconPaint.color = color
        iconPaint.alpha = 255
        // Thinner toward the X, full weight toward the check.
        iconPaint.strokeWidth = AndroidUtilities.dpf2(CROSS_STROKE + (ICON_STROKE - CROSS_STROKE) * progress)
        // Scale about (cx0,cy0), baked into coords so stroke width is controlled separately. Track the
        // thumb size — the off-state (X) thumb is smaller than the checked one. Nudge is scaled by
        // progress so it applies to the check only; the X is already centered.
        val s = CHECK_SCALE * (THUMB_R_OFF + (THUMB_R_ON - THUMB_R_OFF) * progress) / THUMB_R_ON
        val nudge = AndroidUtilities.dpf2(CHECK_NUDGE_RIGHT) * progress
        fun fx(x: Int) = cx0 + (x - cx0) * s + nudge
        fun fy(y: Int) = cy0 + (y - cy0) * s
        val tx = cx0 - (AndroidUtilities.dp(10.8f) - AndroidUtilities.dp(1.3f) * progress).toInt()
        val ty = cy0 - (AndroidUtilities.dp(8.5f) - AndroidUtilities.dp(0.5f) * progress).toInt()

        val startX2 = AndroidUtilities.dpf2(4.6f).toInt() + tx
        val startY2 = (AndroidUtilities.dpf2(9.5f) + ty).toInt()
        val endX2 = startX2 + AndroidUtilities.dp(2f)
        val endY2 = startY2 + AndroidUtilities.dp(2f)

        var startX = AndroidUtilities.dpf2(7.5f).toInt() + tx
        var startY = (AndroidUtilities.dpf2(5.4f) + ty).toInt()
        var endX = startX + AndroidUtilities.dp(7f)
        var endY = startY + AndroidUtilities.dp(7f)

        startX = (startX + (startX2 - startX) * progress).toInt()
        startY = (startY + (startY2 - startY) * progress).toInt()
        endX = (endX + (endX2 - endX) * progress).toInt()
        endY = (endY + (endY2 - endY) * progress).toInt()
        canvas.drawLine(fx(startX), fy(startY), fx(endX), fy(endY), iconPaint)

        startX = AndroidUtilities.dpf2(7.5f).toInt() + tx
        startY = AndroidUtilities.dpf2(12.5f).toInt() + ty
        endX = startX + AndroidUtilities.dp(7f)
        endY = startY - AndroidUtilities.dp(7f)
        canvas.drawLine(fx(startX), fy(startY), fx(endX), fy(endY), iconPaint)
    }

    // Ported from stock Switch.onDraw (drawIconType 2): a dot folding into an L by `iconProgress`.
    private fun drawDot(canvas: Canvas, cx: Int, cy: Int, iconProgress: Float, color: Int) {
        iconPaint.color = color
        iconPaint.alpha = (255 * (1f - iconProgress)).toInt()
        iconPaint.strokeWidth = AndroidUtilities.dpf2(ICON_STROKE)
        canvas.drawLine(cx.toFloat(), cy.toFloat(), cx.toFloat(), (cy - AndroidUtilities.dp(5f)).toFloat(), iconPaint)
        canvas.save()
        canvas.rotate(-90 * iconProgress, cx.toFloat(), cy.toFloat())
        canvas.drawLine(cx.toFloat(), cy.toFloat(), (cx + AndroidUtilities.dp(4f)).toFloat(), cy.toFloat(), iconPaint)
        canvas.restore()
    }
}
