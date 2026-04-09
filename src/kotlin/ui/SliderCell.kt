package desu.inugram.ui

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
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
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
    private val format: (Float) -> String,
    private val onChanged: (Float) -> Unit,
) : LinearLayout(context) {

    var value: Float = snap(initialValue)
        private set

    private val seekBarView = SeekBarWrapper(
        context,
        snapProgress = step?.let { s -> { p -> snapProgress(p, s) } },
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
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        addView(seekBarView, LayoutHelper.createLinear(0, 38, 1f, 6, 0, 0, 0))
        addView(
            valueLabel,
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8f, 0f, 4f, 0f)
        )
        addView(resetButton, LayoutHelper.createLinear(40, 40, 0f, 0f, 4f, 0f))
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
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(48f), MeasureSpec.EXACTLY),
        )
    }

    // lower-level SeekBar is used instead of SeekBarView so we avoid the step-change haptic.
    private class SeekBarWrapper(
        context: Context,
        private val snapProgress: ((Float) -> Float)? = null,
    ) : View(context) {
        var onProgressChanged: ((Float) -> Unit)? = null
        private val seekBar = SeekBar(this)

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
            seekBar.setProgress(progress)
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            seekBar.setSize(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            seekBar.draw(canvas)
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
                    val thumbWidth = AndroidUtilities.dp(24f)
                    val denom = (width - thumbWidth).coerceAtLeast(1).toFloat()
                    val raw = ((event.x - thumbWidth / 2f) / denom).coerceIn(0f, 1f)
                    val snapped = snapProgress.invoke(raw)
                    seekBar.setProgress(snapped)
                    onProgressChanged?.invoke(snapped)
                    invalidate()
                    return true
                }
                else -> return false
            }
        }
    }
}
