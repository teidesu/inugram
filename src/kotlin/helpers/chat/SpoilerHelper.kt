package desu.inugram.helpers.chat

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
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object SpoilerHelper {
    private class State {
        var baseColor: Int = 0
        var prevLeft: Float = Float.NaN
        var prevRight: Float = Float.NaN
        var nextLeft: Float = Float.NaN
        var nextRight: Float = Float.NaN
    }

    // UI-thread only; weak so released effects don't pin entries.
    private val states = WeakHashMap<SpoilerEffect, State>()
    private fun stateOf(e: SpoilerEffect) = states.getOrPut(e) { State() }

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
        // During reveal, stock blends lastColor toward the opaque text color and fades
        // mAlpha to 0 — both would visibly change the overlay. We pin a constant color
        // (captured pre-reveal) and constant alpha, letting the ripple-path PorterDuff.CLEAR
        // be the only visible change.
        val state = stateOf(effect)
        if (effect.rippleProgress < 0) state.baseColor = lastColor
        val alphaScale = if (isOutgoingBubble(parent)) 0.45f else 0.25f
        solidPaint.color = state.baseColor
        solidPaint.alpha = (0xFF * alphaScale).toInt().coerceIn(0, 0xFF)

        val r = dp(4f).toFloat()
        val tlR = if (state.prevLeft <= bounds.left) 0f else r
        val trR = if (state.prevRight >= bounds.right) 0f else r
        val blR = if (state.nextLeft <= bounds.left) 0f else r
        val brR = if (state.nextRight >= bounds.right) 0f else r

        tempPath.rewind()
        tempRect.set(bounds)
        tempPath.addRoundRect(tempRect, floatArrayOf(tlR, tlR, trR, trR, brR, brR, blR, blR), Path.Direction.CW)
        canvas.drawPath(tempPath, solidPaint)

        // Concave-step fillets where a neighbor extends past our edge.
        if (state.prevLeft < bounds.left)
            drawFillet(canvas, bounds.left.toFloat(), bounds.top.toFloat(), dx = -1, dy = +1, r = r)
        if (state.prevRight > bounds.right)
            drawFillet(canvas, bounds.right.toFloat(), bounds.top.toFloat(), dx = +1, dy = +1, r = r)
        if (state.nextLeft < bounds.left)
            drawFillet(canvas, bounds.left.toFloat(), bounds.bottom.toFloat(), dx = -1, dy = -1, r = r)
        if (state.nextRight > bounds.right)
            drawFillet(canvas, bounds.right.toFloat(), bounds.bottom.toFloat(), dx = +1, dy = -1, r = r)

        return true
    }

    @JvmStatic
    fun drawMediaSpoilerIfOverridden(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, alpha: Float, isSelfDestruct: Boolean, isNotLoaded: Boolean): Boolean {
        if (!InuConfig.SIMPLE_MEDIA_SPOILERS.value) return false
        val clampedAlpha = alpha.coerceIn(0f, 1f)

        solidPaint.color = Color.BLACK
        solidPaint.alpha = (110 * clampedAlpha).toInt().coerceIn(0, 255)
        canvas.drawRect(left, top, right, bottom, solidPaint)

        // Self-destruct media (view-once / timed) carries its own stock indicator, and
        // not-yet-downloaded media draws a centered download/loading button — in both cases
        // the "MEDIA SPOILER" pill would collide, so keep just the overlay.
        if (isSelfDestruct || isNotLoaded) return true

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
            labelPaint.alpha = (230 * clampedAlpha).toInt().coerceIn(0, 255)
            canvas.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f, labelPaint)
        }
        return true
    }

    private fun isOutgoingBubble(view: View?): Boolean {
        return generateSequence(view) { it.parent as? View }
            .filterIsInstance<ChatMessageCell>()
            .firstOrNull()?.messageObject?.isOutOwner == true
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
        // getSelectionPath emits an extra trailing-whitespace rect per line
        // sometimes this overlaps the main rect (visible duplicate overdraw);
        // without it the two rects are adjacent and create a visible seam in SIMPLE
        // mode. Merge any two rects on the same y-span that touch or overlap on x —
        // drop the narrower one and extend the wider one to cover the union.
        for (i in spoilers.indices) {
            val a = spoilers[i]
            if (!a.inu_isTextSpoiler || a.bounds.isEmpty) continue
            val ab = a.bounds
            for (j in spoilers.indices) {
                if (i == j) continue
                val b = spoilers[j]
                if (!b.inu_isTextSpoiler || b.bounds.isEmpty) continue
                val bb = b.bounds
                if (ab.top != bb.top || ab.bottom != bb.bottom) continue
                if (ab.right < bb.left || ab.left > bb.right) continue
                if (ab.width() >= bb.width()) continue
                b.setBounds(min(ab.left, bb.left), bb.top, max(ab.right, bb.right), bb.bottom)
                a.setBounds(0, 0, 0, 0)
                break
            }
        }
        // Snap sub-radius edge misalignments between vertically-adjacent line spoilers.
        // Otherwise one line rounds its corner while its neighbor draws a concave fillet
        // for the same offset — the curves don't mate and leave a visible sliver.
        // Shrink to the inner edge to avoid covering text just outside the span.
        val snap = dp(4f).toFloat()
        for (i in spoilers.indices) {
            val a = spoilers[i]
            if (!a.inu_isTextSpoiler || a.bounds.isEmpty) continue
            val ab = a.bounds
            for (j in spoilers.indices) {
                if (i == j) continue
                val b = spoilers[j]
                if (!b.inu_isTextSpoiler || b.bounds.isEmpty) continue
                val bb = b.bounds
                if (abs(ab.bottom - bb.top) > 1 && abs(bb.bottom - ab.top) > 1) continue
                if (ab.left >= bb.right || ab.right <= bb.left) continue
                val dl = abs(ab.left - bb.left).toFloat()
                if (dl in 0.001f..snap) {
                    val nl = max(ab.left, bb.left)
                    a.setBounds(nl, ab.top, ab.right, ab.bottom)
                    b.setBounds(nl, bb.top, bb.right, bb.bottom)
                }
                val dr = abs(ab.right - bb.right).toFloat()
                if (dr in 0.001f..snap) {
                    val nr = min(ab.right, bb.right)
                    a.setBounds(ab.left, ab.top, nr, ab.bottom)
                    b.setBounds(bb.left, bb.top, nr, bb.bottom)
                }
            }
        }
        for (s in spoilers) {
            if (!s.inu_isTextSpoiler) continue
            stateOf(s).apply {
                prevLeft = Float.NaN; prevRight = Float.NaN
                nextLeft = Float.NaN; nextRight = Float.NaN
            }
        }
        for (i in spoilers.indices) {
            val a = spoilers[i]
            if (!a.inu_isTextSpoiler) continue
            val ast = stateOf(a)
            val ab = a.bounds
            for (j in spoilers.indices) {
                if (i == j) continue
                val b = spoilers[j]
                if (!b.inu_isTextSpoiler) continue
                val bb = b.bounds
                if (ab.left >= bb.right || ab.right <= bb.left) continue
                if (abs(ab.bottom - bb.top) <= 1) {
                    ast.nextLeft = if (ast.nextLeft.isNaN()) bb.left.toFloat() else min(ast.nextLeft, bb.left.toFloat())
                    ast.nextRight = if (ast.nextRight.isNaN()) bb.right.toFloat() else max(ast.nextRight, bb.right.toFloat())
                }
                if (abs(bb.bottom - ab.top) <= 1) {
                    ast.prevLeft = if (ast.prevLeft.isNaN()) bb.left.toFloat() else min(ast.prevLeft, bb.left.toFloat())
                    ast.prevRight = if (ast.prevRight.isNaN()) bb.right.toFloat() else max(ast.prevRight, bb.right.toFloat())
                }
            }
        }
    }
}
