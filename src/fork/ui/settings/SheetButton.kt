package desu.inugram.ui.settings

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.CubicBezierInterpolator

fun createSheetButton(
    context: Context,
    text: CharSequence,
    background: Drawable,
    textColor: Int,
    bold: Boolean,
    onClick: () -> Unit,
): TextView = TextView(context).apply {
    this.text = text
    isAllCaps = false
    isSingleLine = true
    ellipsize = TextUtils.TruncateAt.END
    gravity = Gravity.CENTER
    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
    setTextColor(textColor)
    if (bold) typeface = AndroidUtilities.bold()
    this.background = background
    setOnClickListener { onClick() }
}

/**
 * The strip is opaque while it covers content and fades out once there is nothing left under
 * it - the cancel button has no fill of its own, so over a scrolling list it would otherwise be
 * a label floating on top of rows. Without [fadeBackground] only the shadow fades.
 */
class SheetButtonsView(context: Context, private val fadeBackground: Boolean = true) : FrameLayout(context) {
    private val paint = Paint()
    // inside the view rather than above it: the sheet clips its children, so the band the
    // shadow falls on has to be part of what this one measures
    private val shadowHeight = dp(3f)
    private val shadowPaint = Paint().apply {
        shader = LinearGradient(
            0f, 0f, 0f, shadowHeight.toFloat(),
            0x00000000, 0x12000000, Shader.TileMode.CLAMP,
        )
    }
    private var progress = 1f
    private var covering = true
    private var animator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        setPadding(0, dp(12f) + shadowHeight, 0, 0)
    }

    fun setCovering(value: Boolean) {
        if (covering == value) return
        covering = value
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, if (value) 1f else 0f).apply {
            duration = 180
            interpolator = CubicBezierInterpolator.DEFAULT
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val alpha = (255 * progress).toInt()
        shadowPaint.alpha = alpha
        canvas.drawRect(0f, 0f, width.toFloat(), shadowHeight.toFloat(), shadowPaint)
        paint.color = Theme.getColor(Theme.key_windowBackgroundWhite)
        if (fadeBackground) paint.alpha = alpha
        canvas.drawRect(0f, shadowHeight.toFloat(), width.toFloat(), height.toFloat(), paint)
    }
}
