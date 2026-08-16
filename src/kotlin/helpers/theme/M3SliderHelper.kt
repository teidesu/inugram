package desu.inugram.helpers.theme

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import androidx.core.graphics.ColorUtils
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedFloat
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.Easings
import org.telegram.ui.Components.SeekBarView
import org.telegram.ui.Components.SlideChooseView
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Material 3 slider rendering (thick track, floating bar handle with a gap, stop indicator),
 * drawn in place of the stock thin-line + circle-thumb sliders when
 * [desu.inugram.InuConfig.MATERIAL3_SLIDERS] is on.
 *
 * Geometry follows the M3 spec (track 16dp r8, inside corners 2dp, handle 4x44 -> clamped,
 * 6dp handle gap, 4dp stop indicator/ticks).
 *
 * Three consumers:
 * - [drawSeekBar] takes over [SeekBarView.onDraw] (settings sliders, music player, wallpaper dim…);
 *   falls back to stock for the two-sided mode and chapter-timestamp tracks it doesn't model.
 * - [drawSlideChoose] takes over [SlideChooseView.onDraw] (discrete labeled choosers); the label
 *   row is reproduced verbatim from stock so drawable/text layouts don't shift.
 * - [drawPlain] renders a plain 0..1 slider for fork views built on the low-level
 *   [org.telegram.ui.Components.SeekBar] (see [desu.inugram.ui.settings.SliderCell]).
 *
 * Touch handling, accessibility and progress state stay fully stock — this is draw-only.
 */
object M3SliderHelper {
    private const val TRACK_HEIGHT = 16f
    private const val OUTER_RADIUS = 8f

    // corner facing the handle gap
    private const val INNER_RADIUS = 2f
    private const val GAP = 6f
    private const val HANDLE_WIDTH = 4f
    private const val HANDLE_WIDTH_PRESSED = 2f

    // spec is 44dp; clamped to the host view, which is 38dp tall in most settings rows
    private const val HANDLE_HEIGHT = 28f
    private const val TICK_RADIUS = 2f
    private const val DIM_ALPHA = 0.5f

    // tick density limits; denser stops get subsampled (see tickCount)
    private const val MIN_TICK_SPACING = 16f
    private const val MAX_TICK_COUNT = 7

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private val radii = FloatArray(8)

    private class State(view: View) {
        val handleWidth = AnimatedFloat(view, 0, 120, CubicBezierInterpolator.DEFAULT)
        var lastUpdateTime = 0L
    }

    private val states = WeakHashMap<View, State>()

    private fun enabled() = InuConfig.MATERIAL3_SLIDERS.value

    private fun stateFor(view: View) = states.getOrPut(view) { State(view) }

    /** Current animated handle width in px; narrows while dragging like the M3 pressed state. */
    private fun handleWidth(view: View, dragging: Boolean): Float =
        stateFor(view).handleWidth.set(AndroidUtilities.dpf2(if (dragging) HANDLE_WIDTH_PRESSED else HANDLE_WIDTH))

    @JvmStatic
    fun drawSeekBar(view: SeekBarView, canvas: Canvas): Boolean {
        if (!enabled()) return false
        if (view.isTwoSided) return false

        val timestamps = view.timestamps
        if (timestamps != null && timestamps.isNotEmpty()) return false

        val width = view.measuredWidth
        val span = width - view.selectorWidth
        if (span <= 0) return true

        // mirror stock's step snapping (SeekBarView.onDraw top)
        var thumbX = view.thumbX.toFloat()
        val delegate = view.delegate
        var stepsCount = 0
        if (view.separatorsCount > 1) {
            stepsCount = view.separatorsCount
            val step = span / (stepsCount - 1f)
            thumbX = view.animatedThumbX.set((thumbX / step).roundToInt() * step)
        } else if (delegate != null && delegate.needVisuallyDivideSteps() && delegate.stepsCount > 1) {
            stepsCount = delegate.stepsCount
            val step = span / (stepsCount - 1f)
            thumbX = ((thumbX / step).roundToInt() * step)
        }

        var cx = thumbX + view.selectorWidth / 2f

        // stock cross-fades two thumb circles on animated setProgress(); a bar handle slides instead
        var invalidate = false
        if (view.transitionProgress < 1f) {
            val fromCx = view.transitionThumbX + view.selectorWidth / 2f
            cx = AndroidUtilities.lerp(fromCx, cx, Easings.easeOutQuad.getInterpolation(view.transitionProgress))

            val state = stateFor(view)
            val now = SystemClock.elapsedRealtime()
            var dt = now - state.lastUpdateTime
            state.lastUpdateTime = now

            if (dt > 18) dt = 16
            view.transitionProgress = min(1f, view.transitionProgress + dt / 225f)
            invalidate = true
        }

        val activeColor = view.outerPaint1.color
        val inactiveColor = view.getThemedColor(Theme.key_player_progressBackground)
        val cy = view.measuredHeight / 2f
        val left = view.selectorWidth / 2f
        val right = width - view.selectorWidth / 2f
        val hw = handleWidth(view, view.isDragging) / 2f
        val gap = AndroidUtilities.dpf2(GAP)
        val outerR = AndroidUtilities.dpf2(OUTER_RADIUS)
        val innerR = AndroidUtilities.dpf2(INNER_RADIUS)

        // stock renders the sub-minProgress region at half alpha
        val dimUntil = if (view.minProgress >= 0f) left + view.minProgress * (right - left) else -Float.MAX_VALUE
        val activeRight = cx - hw - gap
        val inactiveLeft = cx + hw + gap
        drawSpan(canvas, left, activeRight, cy, outerR, innerR, activeColor, dimUntil, Float.MAX_VALUE)
        drawSpan(canvas, inactiveLeft, right, cy, innerR, outerR, inactiveColor, -Float.MAX_VALUE, Float.MAX_VALUE)

        if (view.bufferedProgress > 0f) {
            val bufferedRight = left + view.bufferedProgress * (right - left)
            if (bufferedRight > inactiveLeft + 1f) {
                trackPaint.color = view.getThemedColor(Theme.key_player_progressCachedBackground)
                val endR = if (bufferedRight >= right - 1f) outerR else innerR
                drawRounded(canvas, inactiveLeft, min(bufferedRight, right), cy, innerR, endR, trackPaint)
            }
        }

        val dots = tickCount(stepsCount - 1, right - left)
        if (dots > 0) {
            drawTicks(canvas, left, right, cy, cx, hw, dots, view.getThemedColor(Theme.key_windowBackgroundWhite), activeColor, dimUntil)
        } else {
            drawStopIndicator(canvas, right, cy, inactiveLeft, activeColor)
        }

        drawHandle(canvas, cx, cy, hw * 2f, view.measuredHeight.toFloat(), activeColor)

        if (invalidate) view.postInvalidateOnAnimation()

        return true
    }

    @JvmStatic
    fun drawSlideChoose(view: SlideChooseView, canvas: Canvas): Boolean {
        if (!enabled()) return false

        val options = view.optionsStr ?: return false
        val count = options.size
        if (count == 0) return true

        val selectedAnimated = view.selectedIndexAnimatedHolder.set(view.selectedIndex.toFloat())
        val movingAnimated = view.movingAnimatedHolder.set(if (view.moving) 1f else 0f)
        val cy = view.measuredHeight / 2f + AndroidUtilities.dp(11f)
        val stride = view.lineSize + view.gapSize * 2 + view.circleSize
        fun centerX(index: Float) = view.sideSide + stride * index + view.circleSize / 2f

        val handleW = AndroidUtilities.lerp(
            AndroidUtilities.dpf2(HANDLE_WIDTH),
            AndroidUtilities.dpf2(HANDLE_WIDTH_PRESSED),
            movingAnimated,
        )
        val hw = handleW / 2f
        val cx = centerX(selectedAnimated)
        val outerR = AndroidUtilities.dpf2(OUTER_RADIUS)
        val innerR = AndroidUtilities.dpf2(INNER_RADIUS)
        val gap = AndroidUtilities.dpf2(GAP)
        val trackLeft = centerX(0f) - outerR
        val trackRight = centerX((count - 1).toFloat()) + outerR
        val activeColor = view.getThemedColor(Theme.key_switchTrackChecked)
        val inactiveColor = view.getThemedColor(Theme.key_switchTrack)

        // stock: options at/below minIndex show at half alpha; dashed region beyond dashedFrom
        // (approximate/auto values) rendered dimmed instead of dashed
        val minActive = view.minIndex != Int.MIN_VALUE && view.minIndex >= 0
        val dimUntil = if (minActive) centerX(view.minIndex.toFloat()) else -Float.MAX_VALUE
        val dimFrom = if (view.dashedFrom != -1) centerX(view.dashedFrom.toFloat()) else Float.MAX_VALUE
        drawSpan(canvas, trackLeft, cx - hw - gap, cy, outerR, innerR, activeColor, dimUntil, dimFrom)
        drawSpan(canvas, cx + hw + gap, trackRight, cy, innerR, outerR, inactiveColor, dimUntil, dimFrom)

        val tickR = AndroidUtilities.dpf2(TICK_RADIUS)
        for (a in 0 until count) {
            val x = centerX(a.toFloat())
            if (abs(x - cx) < hw + gap + tickR) continue

            var color = if (x < cx) view.getThemedColor(Theme.key_windowBackgroundWhite) else activeColor
            if ((minActive && a <= view.minIndex) || (view.dashedFrom != -1 && a >= view.dashedFrom)) {
                color = Theme.multAlpha(color, DIM_ALPHA)
            }

            tickPaint.color = color
            canvas.drawCircle(x, cy, tickR, tickPaint)
        }

        drawHandle(canvas, cx, cy, handleW, view.measuredHeight.toFloat(), activeColor)

        // label row reproduced from stock SlideChooseView.onDraw so text/drawables land identically
        for (a in 0 until count) {
            val t = (1f - abs(a - selectedAnimated)).coerceAtLeast(0f)
            val size = view.optionsSizes[a]
            val text = options[a]
            view.textPaint.color = ColorUtils.blendARGB(
                view.getThemedColor(Theme.key_windowBackgroundWhiteGrayText),
                view.getThemedColor(Theme.key_windowBackgroundWhiteBlueText),
                t,
            )

            val leftDrawables = view.leftDrawables
            if (leftDrawables != null) {
                canvas.save()

                when (a) {
                    0 -> canvas.translate(AndroidUtilities.dp(12f).toFloat(), AndroidUtilities.dp(15.5f).toFloat())
                    count - 1 -> canvas.translate(
                        (view.measuredWidth - size - AndroidUtilities.dp(22f) - AndroidUtilities.dp(10f)).toFloat(),
                        (AndroidUtilities.dp(28f) - AndroidUtilities.dp(12.5f)).toFloat(),
                    )
                    else -> canvas.translate(
                        centerX(a.toFloat()) - size / 2f - AndroidUtilities.dp(10f),
                        (AndroidUtilities.dp(28f) - AndroidUtilities.dp(12.5f)).toFloat(),
                    )
                }

                leftDrawables[a].setColorFilter(view.textPaint.color, PorterDuff.Mode.MULTIPLY)
                leftDrawables[a].draw(canvas)
                canvas.restore()
                canvas.save()
                canvas.translate(
                    leftDrawables[a].intrinsicWidth / 2f - AndroidUtilities.dp(if (a == 0) 3f else 2f),
                    0f,
                )
            }
            when (a) {
                0 -> canvas.drawText(text, AndroidUtilities.dp(22f).toFloat(), AndroidUtilities.dp(28f).toFloat(), view.textPaint)
                count - 1 -> canvas.drawText(
                    text,
                    (view.measuredWidth - size - AndroidUtilities.dp(22f)).toFloat(),
                    AndroidUtilities.dp(28f).toFloat(),
                    view.textPaint,
                )
                else -> canvas.drawText(text, centerX(a.toFloat()) - size / 2f, AndroidUtilities.dp(28f).toFloat(), view.textPaint)
            }

            if (leftDrawables != null) canvas.restore()
        }

        return true
    }

    /**
     * Plain 0..1 slider for fork views wrapping the low-level `SeekBar`, which maps progress over
     * `width - 24dp` with the thumb center offset by 12dp — [sidePaddingDp] must match that.
     * [steps] is the snap stop count for tick dots (0 = continuous, stop indicator only).
     */
    @JvmStatic
    fun drawPlain(view: View, canvas: Canvas, progress: Float, dragging: Boolean, steps: Int = 0, sidePaddingDp: Float = 12f): Boolean {
        if (!enabled()) return false

        val width = view.width
        val pad = AndroidUtilities.dpf2(sidePaddingDp)
        if (width <= pad * 2) return true

        val left = pad
        val right = width - pad
        val cy = view.height / 2f
        val cx = left + (right - left) * progress.coerceIn(0f, 1f)
        val hw = handleWidth(view, dragging) / 2f
        val gap = AndroidUtilities.dpf2(GAP)
        val outerR = AndroidUtilities.dpf2(OUTER_RADIUS)
        val innerR = AndroidUtilities.dpf2(INNER_RADIUS)
        val activeColor = Theme.getColor(Theme.key_player_progress)
        val inactiveColor = Theme.getColor(Theme.key_player_progressBackground)

        val inactiveLeft = cx + hw + gap
        drawSpan(canvas, left, cx - hw - gap, cy, outerR, innerR, activeColor, -Float.MAX_VALUE, Float.MAX_VALUE)
        drawSpan(canvas, inactiveLeft, right, cy, innerR, outerR, inactiveColor, -Float.MAX_VALUE, Float.MAX_VALUE)

        val dots = tickCount(steps - 1, right - left)
        if (dots > 0) {
            drawTicks(canvas, left, right, cy, cx, hw, dots, Theme.getColor(Theme.key_windowBackgroundWhite), activeColor, -Float.MAX_VALUE)
        } else {
            drawStopIndicator(canvas, right, cy, inactiveLeft, activeColor)
        }

        drawHandle(canvas, cx, cy, hw * 2f, view.height.toFloat(), activeColor)

        return true
    }

    /**
     * Number of tick dots for a stepped slider, laid out uniformly over the cap-inset span:
     * every stop when they fit under [MAX_TICK_COUNT] / [MIN_TICK_SPACING], otherwise thinned —
     * preferring a count whose gaps land on real stops (interval count divisor), else plain
     * evenly spaced marker dots. 0 = fewer than 3 dots fit — render as a continuous track.
     */
    private fun tickCount(intervals: Int, spanPx: Float): Int {
        if (intervals < 1) return 0

        // dots are laid out over the span inset by the cap radius on both sides (see drawTicks)
        val dotSpan = spanPx - 2 * AndroidUtilities.dpf2(OUTER_RADIUS)
        val maxBySpacing = (dotSpan / AndroidUtilities.dpf2(MIN_TICK_SPACING)).toInt() + 1
        val maxDots = minOf(MAX_TICK_COUNT, maxBySpacing, intervals + 1)
        if (maxDots < 3) return 0

        for (dots in maxDots downTo 3) {
            if (intervals % (dots - 1) == 0) return dots
        }

        return maxDots
    }

    private fun drawTicks(
        canvas: Canvas,
        left: Float,
        right: Float,
        cy: Float,
        cx: Float,
        hw: Float,
        dots: Int,
        onActiveColor: Int,
        onInactiveColor: Int,
        dimUntilX: Float,
    ) {
        val tickR = AndroidUtilities.dpf2(TICK_RADIUS)
        val gap = AndroidUtilities.dpf2(GAP)

        // dots spread uniformly over the span inset by the cap radius: end dots sit concentric
        // with the rounded caps and end gaps match the interior spacing. Dots therefore mark the
        // range rather than exact stop pixels; the handle gap swallows the offset whenever the
        // handle lands near one.
        val endInset = AndroidUtilities.dpf2(OUTER_RADIUS)
        val l = left + endInset
        val r = right - endInset
        for (j in 0 until dots) {
            val x = l + (r - l) * j / (dots - 1)
            if (abs(x - cx) < hw + gap + tickR) continue

            var color = if (x < cx) onActiveColor else onInactiveColor
            if (x < dimUntilX) color = Theme.multAlpha(color, DIM_ALPHA)
            tickPaint.color = color
            canvas.drawCircle(x, cy, tickR, tickPaint)
        }
    }

    private fun drawStopIndicator(canvas: Canvas, right: Float, cy: Float, inactiveLeft: Float, color: Int) {
        val tickR = AndroidUtilities.dpf2(TICK_RADIUS)
        // centered on the cap's corner arc center, concentric with the rounding
        val stopX = right - AndroidUtilities.dpf2(OUTER_RADIUS)
        if (stopX > inactiveLeft + tickR) {
            tickPaint.color = color
            canvas.drawCircle(stopX, cy, tickR, tickPaint)
        }
    }

    /**
     * One track span with [radL]/[radR] end corners; the parts before [dimUntilX] / after
     * [dimFromX] are drawn at half alpha (split edges stay square so the track reads continuous).
     */
    private fun drawSpan(
        canvas: Canvas,
        l: Float,
        r: Float,
        cy: Float,
        radL: Float,
        radR: Float,
        color: Int,
        dimUntilX: Float,
        dimFromX: Float,
    ) {
        if (r - l < 1f) return
        val p1 = dimUntilX.coerceIn(l, r)
        val p2 = dimFromX.coerceIn(p1, r)
        var a = l

        for (b in floatArrayOf(p1, p2, r)) {
            if (b - a >= 0.5f) {
                val dimmed = b <= p1 + 0.01f || a >= p2 - 0.01f
                trackPaint.color = if (dimmed) Theme.multAlpha(color, DIM_ALPHA) else color
                drawRounded(canvas, a, b, cy, if (a <= l + 0.5f) radL else 0f, if (b >= r - 0.5f) radR else 0f, trackPaint)
            }
            a = b
        }
    }

    private fun drawRounded(canvas: Canvas, l: Float, r: Float, cy: Float, radL: Float, radR: Float, paint: Paint) {
        val half = AndroidUtilities.dpf2(TRACK_HEIGHT) / 2f
        rect.set(l, cy - half, r, cy + half)

        if (radL == radR) {
            canvas.drawRoundRect(rect, radL, radL, paint)
            return
        }

        radii[0] = radL; radii[1] = radL
        radii[2] = radR; radii[3] = radR
        radii[4] = radR; radii[5] = radR
        radii[6] = radL; radii[7] = radL
        path.rewind()
        path.addRoundRect(rect, radii, Path.Direction.CW)
        canvas.drawPath(path, paint)
    }

    private fun drawHandle(canvas: Canvas, cx: Float, cy: Float, width: Float, maxHeight: Float, color: Int) {
        val h = min(AndroidUtilities.dpf2(HANDLE_HEIGHT), maxHeight)
        val hw = width / 2f
        rect.set(cx - hw, cy - h / 2f, cx + hw, cy + h / 2f)
        handlePaint.color = color
        canvas.drawRoundRect(rect, hw, hw, handlePaint)
    }
}
