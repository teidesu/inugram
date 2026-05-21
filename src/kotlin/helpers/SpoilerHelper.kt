package desu.inugram.helpers

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.Components.spoilers.SpoilerEffect
import kotlin.math.max
import kotlin.math.min

object SpoilerHelper {
    private val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tempPath = Path()
    private val tempRect = RectF()
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(12f).toFloat()
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.12f
    }

    @JvmStatic
    fun drawSolidIfOverridden(canvas: Canvas, effect: SpoilerEffect, parent: View?, lastColor: Int, mAlpha: Int): Boolean {
        val mode = InuConfig.TEXT_SPOILER_MODE.value
        if (mode == InuConfig.TextSpoilerModeItem.DEFAULT) return false
        val bounds = effect.bounds
        if (bounds.isEmpty) return true

        if (mode == InuConfig.TextSpoilerModeItem.EPSTEIN) {
            solidPaint.color = Color.BLACK
            solidPaint.alpha = mAlpha
            canvas.drawRect(bounds, solidPaint)
            return true
        }

        // SIMPLE: text color overlay. Outgoing (sent) bubbles have a saturated bg that
        // absorbs a stronger tint; incoming bubbles are near-grayscale and need a softer
        // overlay to avoid stark contrast.
        val alphaScale = if (isOutgoingBubble(parent)) 0.45f else 0.25f
        solidPaint.color = lastColor
        solidPaint.alpha = (Color.alpha(lastColor) * mAlpha / 0xFF * alphaScale).toInt().coerceIn(0, 0xFF)

        val r = dp(4f).toFloat()
        val tlR = if (effect.inu_prevLeft <= bounds.left) 0f else r
        val trR = if (effect.inu_prevRight >= bounds.right) 0f else r
        val blR = if (effect.inu_nextLeft <= bounds.left) 0f else r
        val brR = if (effect.inu_nextRight >= bounds.right) 0f else r

        tempPath.rewind()
        tempRect.set(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
        tempPath.addRoundRect(tempRect, floatArrayOf(tlR, tlR, trR, trR, brR, brR, blR, blR), Path.Direction.CW)
        canvas.drawPath(tempPath, solidPaint)

        // Concave-step fillets where a neighbor extends past our edge.
        if (effect.inu_prevLeft < bounds.left)
            drawFillet(canvas, bounds.left.toFloat(), bounds.top.toFloat(), dx = -1, dy = +1, r = r)
        if (effect.inu_prevRight > bounds.right)
            drawFillet(canvas, bounds.right.toFloat(), bounds.top.toFloat(), dx = +1, dy = +1, r = r)
        if (effect.inu_nextLeft < bounds.left)
            drawFillet(canvas, bounds.left.toFloat(), bounds.bottom.toFloat(), dx = -1, dy = -1, r = r)
        if (effect.inu_nextRight > bounds.right)
            drawFillet(canvas, bounds.right.toFloat(), bounds.bottom.toFloat(), dx = +1, dy = -1, r = r)

        return true
    }

    @JvmStatic
    fun drawMediaSpoilerIfOverridden(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, alpha: Float): Boolean {
        if (!InuConfig.SIMPLE_MEDIA_SPOILERS.value) return false
        val clampedAlpha = alpha.coerceIn(0f, 1f)

        solidPaint.color = Color.BLACK
        solidPaint.alpha = (110 * clampedAlpha).toInt().coerceIn(0, 255)
        canvas.drawRect(left, top, right, bottom, solidPaint)

        val label = LocaleController.getString(R.string.InuMediaSpoilerLabel).uppercase()
        val padH = dp(16f).toFloat()
        val padV = dp(7f).toFloat()
        val fm = labelPaint.fontMetrics
        val textW = labelPaint.measureText(label)
        val textH = fm.descent - fm.ascent
        val pillW = textW + padH * 2
        val pillH = textH + padV * 2
        val pad = dp(8f).toFloat()
        if (right - left >= pillW + pad && bottom - top >= pillH + pad) {
            val cx = (left + right) / 2f
            val cy = (top + bottom) / 2f
            solidPaint.color = Color.BLACK
            solidPaint.alpha = (95 * clampedAlpha).toInt().coerceIn(0, 255)
            tempRect.set(cx - pillW / 2f, cy - pillH / 2f, cx + pillW / 2f, cy + pillH / 2f)
            canvas.drawRoundRect(tempRect, pillH / 2f, pillH / 2f, solidPaint)
            labelPaint.alpha = (210 * clampedAlpha).toInt().coerceIn(0, 255)
            canvas.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f, labelPaint)
        }
        return true
    }

    private fun isOutgoingBubble(view: View?): Boolean {
        var v: View? = view
        while (v != null) {
            if (v is ChatMessageCell) {
                return v.messageObject?.isOutOwner == true
            }
            v = v.parent as? View
        }
        return false
    }

    // Fills the curved-triangle pocket adjacent to a concave 90° corner at (cx, cy).
    // dx, dy ∈ {-1, +1} point into the empty pocket.
    private fun drawFillet(canvas: Canvas, cx: Float, cy: Float, dx: Int, dy: Int, r: Float) {
        tempPath.rewind()
        tempPath.moveTo(cx, cy + dy * r)
        val ox = cx + dx * r
        val oy = cy + dy * r
        tempRect.set(ox - r, oy - r, ox + r, oy + r)
        tempPath.arcTo(tempRect, if (dx < 0) 0f else 180f, 90f * dx * dy)
        tempPath.lineTo(cx, cy)
        tempPath.close()
        canvas.drawPath(tempPath, solidPaint)
    }

    @JvmStatic
    fun linkNeighbors(spoilers: List<SpoilerEffect>) {
        // getSelectionPath emits an extra trailing-whitespace rect per line; the
        // line-extension then stretches the main rect over it, causing duplicate
        // overdraw with the solid styles. Zero any rect strictly contained in another
        // on the same y-span before linking — so it draws nothing and isn't a neighbor.
        for (i in spoilers.indices) {
            val a = spoilers[i]
            if (!a.inu_isTextSpoiler || a.bounds.isEmpty) continue
            val ab = a.bounds
            for (j in spoilers.indices) {
                if (i == j) continue
                val b = spoilers[j]
                if (!b.inu_isTextSpoiler) continue
                val bb = b.bounds
                if (ab.top == bb.top && ab.bottom == bb.bottom
                    && ab.left >= bb.left && ab.right <= bb.right
                    && (ab.left > bb.left || ab.right < bb.right)
                ) {
                    a.setBounds(0, 0, 0, 0)
                    break
                }
            }
        }
        for (s in spoilers) {
            if (!s.inu_isTextSpoiler) continue
            s.inu_prevLeft = Float.NaN
            s.inu_prevRight = Float.NaN
            s.inu_nextLeft = Float.NaN
            s.inu_nextRight = Float.NaN
        }
        for (i in spoilers.indices) {
            val a = spoilers[i]
            if (!a.inu_isTextSpoiler) continue
            val ab = a.bounds
            for (j in spoilers.indices) {
                if (i == j) continue
                val b = spoilers[j]
                if (!b.inu_isTextSpoiler) continue
                val bb = b.bounds
                if (ab.left >= bb.right || ab.right <= bb.left) continue
                if (Math.abs(ab.bottom - bb.top) <= 1) {
                    a.inu_nextLeft = if (a.inu_nextLeft.isNaN()) bb.left.toFloat() else min(a.inu_nextLeft, bb.left.toFloat())
                    a.inu_nextRight = if (a.inu_nextRight.isNaN()) bb.right.toFloat() else max(a.inu_nextRight, bb.right.toFloat())
                }
                if (Math.abs(bb.bottom - ab.top) <= 1) {
                    a.inu_prevLeft = if (a.inu_prevLeft.isNaN()) bb.left.toFloat() else min(a.inu_prevLeft, bb.left.toFloat())
                    a.inu_prevRight = if (a.inu_prevRight.isNaN()) bb.right.toFloat() else max(a.inu_prevRight, bb.right.toFloat())
                }
            }
        }
    }
}
