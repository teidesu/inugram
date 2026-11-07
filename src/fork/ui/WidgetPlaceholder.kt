package desu.inugram.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedFloat
import org.telegram.ui.Components.CubicBezierInterpolator

class WidgetPlaceholder(private val invalidate: Runnable) {
    private val pressedT = AnimatedFloat(invalidate, 160, CubicBezierInterpolator.EASE_OUT_QUINT)
    private val loadingT = AnimatedFloat(invalidate, 240, CubicBezierInterpolator.EASE_OUT_QUINT)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = AndroidUtilities.bold() }
    private val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val play = Path()
    private val rect = RectF()
    private var pressed = false
    private var loading = false

    fun draw(canvas: Canvas, width: Int, height: Int, out: Boolean, resourcesProvider: Theme.ResourcesProvider?, loading: Boolean, interactive: Boolean): Boolean {
        this.loading = loading
        val textColor = Theme.getColor(if (out) Theme.key_chat_messageTextOut else Theme.key_chat_messageTextIn, resourcesProvider)
        paint.color = Theme.multAlpha(textColor, 0.06f)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val pressedValue = pressedT.set(pressed)
        val loadingValue = loadingT.set(loading)
        val vertical = height >= dp(108f)
        val radius = dp(if (vertical) 24f else 18f).toFloat()

        titlePaint.textSize = dp(15f).toFloat()
        titlePaint.color = textColor
        subtitlePaint.textSize = dp(13f).toFloat()
        subtitlePaint.color = Theme.getColor(if (out) Theme.key_chat_outTimeText else Theme.key_chat_inTimeText, resourcesProvider)
        val title = LocaleController.getString(R.string.InuWidgetPlaceholderTitle)
        val subtitle = LocaleController.getString(
            when {
                !interactive -> R.string.InuWidgetPlaceholderPreview
                loading -> R.string.InuWidgetPlaceholderLoading
                else -> R.string.InuWidgetPlaceholderTap
            }
        )

        val cx: Float
        val cy: Float
        if (vertical) {
            val total = radius * 2 + dp(12f) + dp(18f) + dp(18f)
            cx = width / 2f
            cy = (height - total) / 2f + radius
            val available = (width - dp(32f)).toFloat()
            drawCentered(canvas, title, titlePaint, available, cx, cy + radius + dp(12f) + dp(14f))
            drawCentered(canvas, subtitle, subtitlePaint, available, cx, cy + radius + dp(12f) + dp(32f))
        } else {
            val textWidth = maxOf(titlePaint.measureText(title), subtitlePaint.measureText(subtitle))
            val left = maxOf(dp(16f).toFloat(), (width - radius * 2 - dp(12f) - textWidth) / 2f)
            cx = left + radius
            cy = height / 2f
            val textLeft = left + radius * 2 + dp(12f)
            val available = width - textLeft - dp(16f)
            canvas.drawText(ellipsize(title, titlePaint, available), textLeft, cy - dp(3f), titlePaint)
            canvas.drawText(ellipsize(subtitle, subtitlePaint, available), textLeft, cy + dp(15f), subtitlePaint)
        }

        val scale = 1f - 0.08f * pressedValue
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        paint.color = Theme.getColor(if (out) Theme.key_chat_outLoader else Theme.key_chat_inLoader, resourcesProvider)
        canvas.drawCircle(cx, cy, radius, paint)

        val iconColor = Theme.getColor(if (out) Theme.key_chat_outMediaIcon else Theme.key_chat_inMediaIcon, resourcesProvider)
        if (loadingValue < 1f) {
            val size = radius * 0.42f
            play.rewind()
            play.moveTo(cx - size * 0.7f, cy - size)
            play.lineTo(cx + size, cy)
            play.lineTo(cx - size * 0.7f, cy + size)
            play.close()
            paint.color = Theme.multAlpha(iconColor, 1f - loadingValue)
            canvas.drawPath(play, paint)
        }
        if (loadingValue > 0f) {
            val inset = radius * 0.45f
            rect.set(cx - radius + inset, cy - radius + inset, cx + radius - inset, cy + radius - inset)
            spinnerPaint.strokeWidth = dp(2.5f).toFloat()
            spinnerPaint.color = Theme.multAlpha(iconColor, loadingValue)
            val rotation = SystemClock.uptimeMillis() % 1000 / 1000f * 360f
            canvas.drawArc(rect, rotation, 100f, false, spinnerPaint)
        }
        canvas.restore()
        return loading || loadingValue > 0f && loadingValue < 1f
    }

    fun onTouchEvent(event: MotionEvent, width: Int, height: Int, onTap: Runnable): Boolean {
        val inside = event.x >= 0 && event.x < width && event.y >= 0 && event.y < height
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!inside || loading) return false
                pressed = true
            }
            MotionEvent.ACTION_MOVE -> if (!inside) pressed = false
            MotionEvent.ACTION_UP -> {
                if (pressed) onTap.run()
                pressed = false
            }
            MotionEvent.ACTION_CANCEL -> pressed = false
        }
        invalidate.run()
        return true
    }

    private fun drawCentered(canvas: Canvas, text: String, paint: TextPaint, available: Float, cx: Float, baseline: Float) {
        val shown = ellipsize(text, paint, available)
        canvas.drawText(shown, cx - paint.measureText(shown) / 2f, baseline, paint)
    }

    private fun ellipsize(text: String, paint: TextPaint, available: Float): String =
        TextUtils.ellipsize(text, paint, maxOf(0f, available), TextUtils.TruncateAt.END).toString()
}
