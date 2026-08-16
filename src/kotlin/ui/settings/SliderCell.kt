package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.util.TypedValue
import desu.inugram.helpers.theme.M3SliderHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedFloat
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.SeekBar

@SuppressLint("ViewConstructor")
class SliderCell(
    context: Context,
    private val min: Float,
    private val max: Float,
    private val defaultValue: Float,
    initialValue: Float = defaultValue,
    private val step: Float? = null,
    title: CharSequence? = null,
    private val format: (Float) -> String,
    private val onChanged: (Float) -> Unit,
) : LinearLayout(context) {

    var value: Float = snap(initialValue)
        private set

    // snap stop count for M3 tick dots; 0 = continuous (no step, or step not evenly dividing the range)
    private val tickSteps: Int = step?.let {
        val intervals = (max - min) / it
        val rounded = Math.round(intervals)
        if (rounded >= 1 && Math.abs(intervals - rounded) < 0.01f) rounded + 1 else 0
    } ?: 0

    private val seekBarView = SeekBarWrapper(
        context,
        snapProgress = step?.let { s -> { p -> snapProgress(p, s) } },
        tickSteps = tickSteps,
    ).apply {
        onProgressChanged = {
            setValue(min + it * (max - min), syncSlider = false)
        }
        setProgress((value - min) / (max - min))
    }

    private fun snap(v: Float): Float {
        val s = step ?: return v
        return min + Math.round((v - min) / s) * s
    }

    private fun snapProgress(p: Float, stepVal: Float): Float {
        val range = max - min
        if (range <= 0f) return p
        val snapped = Math.round(p * range / stepVal) * stepVal / range
        return snapped.coerceIn(0f, 1f)
    }

    private val valueLabel = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText))
        gravity = Gravity.CENTER
        minWidth = AndroidUtilities.dp(34f)
    }

    private val titleView = title?.let {
        TextView(context).apply {
            text = it
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader))
            typeface = AndroidUtilities.bold()
        }
    }

    private val resetButton = ImageView(context).apply {
        setImageResource(R.drawable.msg_reset)
        setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon))
        background = Theme.createSelectorDrawable(
            Theme.getColor(Theme.key_listSelector),
            Theme.RIPPLE_MASK_CIRCLE_20DP,
        )
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setOnClickListener {
            if (value != defaultValue) {
                setValue(defaultValue, syncSlider = true)
            }
        }
    }

    init {
        if (titleView != null) {
            orientation = VERTICAL
            addView(
                titleView,
                LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    20f, 12f, 16f, 4f,
                )
            )
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(seekBarView, LayoutHelper.createLinear(0, 38, 1f, 6, 0, 0, 0))
            row.addView(
                valueLabel,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8f, 0f, 4f, 0f)
            )
            row.addView(resetButton, LayoutHelper.createLinear(40, 40, 0f, 0f, 4f, 0f))
            addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48))
        } else {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(seekBarView, LayoutHelper.createLinear(0, 38, 1f, 6, 0, 0, 0))
            addView(
                valueLabel,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8f, 0f, 4f, 0f)
            )
            addView(resetButton, LayoutHelper.createLinear(40, 40, 0f, 0f, 4f, 0f))
        }
        refresh()
    }

    private fun setValue(newValue: Float, syncSlider: Boolean) {
        value = newValue
        if (syncSlider) seekBarView.setProgress((newValue - min) / (max - min))
        refresh()
        onChanged(newValue)
    }

    private fun refresh() {
        valueLabel.text = format(value)
        resetButton.alpha = if (value == defaultValue) 0.35f else 1f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val exactWidth = MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY)
        if (titleView != null) {
            super.onMeasure(exactWidth, heightMeasureSpec)
        } else {
            super.onMeasure(
                exactWidth,
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(48f), MeasureSpec.EXACTLY),
            )
        }
    }

    // lower-level SeekBar is used instead of SeekBarView so the cell owns the stepped behavior
    // itself: the snap touch path below handles snapping, the step haptic and the snap animation.
    private class SeekBarWrapper(
        context: Context,
        private val snapProgress: ((Float) -> Float)? = null,
        private val tickSteps: Int = 0,
    ) : View(context) {
        var onProgressChanged: ((Float) -> Unit)? = null
        private val seekBar = SeekBar(this)

        // the snap touch path below bypasses seekBar.onTouch, so seekBar.isDragging stays false
        private var snapDragging = false

        // last progress committed to seekBar; the drawn position glides toward it, mirroring
        // stock SeekBarView's 60ms snap animation
        private var committedProgress = 0f
        private val animatedProgress = AnimatedFloat(this, 0, 60, CubicBezierInterpolator.EASE_OUT)

        init {
            seekBar.setColors(
                Theme.getColor(Theme.key_player_progressBackground),
                Theme.getColor(Theme.key_player_progressCachedBackground),
                Theme.getColor(Theme.key_player_progress),
                Theme.getColor(Theme.key_player_progress),
                Theme.getColor(Theme.key_player_progressBackground),
            )
            seekBar.setDelegate(object : SeekBar.SeekBarDelegate {
                override fun onSeekBarDrag(progress: Float) = onDrag(progress)
                override fun onSeekBarContinuousDrag(progress: Float) = onDrag(progress)
            })
        }

        private fun onDrag(progress: Float) {
            onProgressChanged?.invoke(progress)
            invalidate()
        }

        fun setProgress(progress: Float) {
            committedProgress = progress
            seekBar.setProgress(progress)
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            seekBar.setSize(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val progress: Float
            if (snapProgress != null) {
                progress = animatedProgress.set(committedProgress)
            } else {
                // getThumbX tracks the finger mid-drag; getProgress only updates on release
                val thumbWidth = AndroidUtilities.dp(24f)
                progress = (seekBar.getThumbX() - thumbWidth / 2f) / (width - thumbWidth).coerceAtLeast(1)
            }

            if (M3SliderHelper.drawPlain(this, canvas, progress, seekBar.isDragging || snapDragging, tickSteps)) {
                return
            }

            if (snapProgress != null && progress != committedProgress) {
                // stock SeekBar has no drawn-vs-committed split; borrow its thumb for this frame
                seekBar.setProgress(progress)
                seekBar.draw(canvas)
                seekBar.setProgress(committedProgress)
            } else {
                seekBar.draw(canvas)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (snapProgress == null) {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }

                val handled = seekBar.onTouch(event.action, event.x, event.y)
                if (handled) invalidate()

                return handled
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }

                    snapDragging = event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE
                    val thumbWidth = AndroidUtilities.dp(24f)
                    val denom = (width - thumbWidth).coerceAtLeast(1).toFloat()
                    val raw = ((event.x - thumbWidth / 2f) / denom).coerceIn(0f, 1f)
                    val snapped = snapProgress.invoke(raw)
                    if (snapped != committedProgress) {
                        committedProgress = snapped
                        seekBar.setProgress(snapped)

                        // step haptic like stock's, which only ticks mid-drag, not on release
                        if (event.action != MotionEvent.ACTION_UP && event.action != MotionEvent.ACTION_CANCEL) {
                            AndroidUtilities.vibrateCursor(this)
                        }

                        onProgressChanged?.invoke(snapped)
                    }

                    invalidate()

                    return true
                }
                else -> return false
            }
        }
    }
}
