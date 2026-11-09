package desu.inugram.helpers.icons

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.R
import kotlin.math.roundToInt

class BackIcon private constructor(private val back: Drawable?, private val close: Drawable) {
    private var tintColor = 0
    private var tint: PorterDuffColorFilter? = null

    fun draw(canvas: Canvas, size: Int, rotation: Float, arrowRotation: Int, color: Int, colorFilter: ColorFilter?) {
        val opaque = ColorUtils.setAlphaComponent(color, 255)
        if (tint == null || tintColor != opaque) {
            tint = PorterDuffColorFilter(opaque, PorterDuff.Mode.SRC_IN)
            tintColor = opaque
        }
        val filter = colorFilter ?: tint!!
        val alpha = Color.alpha(color) / 255f
        val save = canvas.save()
        if (arrowRotation != 0) canvas.rotate(arrowRotation.toFloat(), size / 2f, size / 2f)
        if (back == null) {
            draw(canvas, close, size, alpha, 1f, filter)
        } else {
            draw(canvas, back, size, alpha * (1f - rotation), AndroidUtilities.lerp(1f, MIN_SCALE, rotation), filter)
            draw(canvas, close, size, alpha * rotation, AndroidUtilities.lerp(MIN_SCALE, 1f, rotation), filter)
        }
        canvas.restoreToCount(save)
    }

    private fun draw(canvas: Canvas, drawable: Drawable, size: Int, alpha: Float, scale: Float, filter: ColorFilter) {
        if (alpha <= 0f) return
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: size
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: size
        val left = (size - width) / 2
        val top = (size - height) / 2
        drawable.setBounds(left, top, left + width, top + height)
        drawable.colorFilter = filter
        drawable.alpha = (alpha * 255).roundToInt()
        val save = canvas.save()
        canvas.scale(scale, scale, size / 2f, size / 2f)
        drawable.draw(canvas)
        canvas.restoreToCount(save)
    }

    companion object {
        private const val MIN_SCALE = 0.1f
        private const val BACK = "ic_ab_back"
        private const val CLOSE = "ic_ab_close"

        @JvmStatic
        fun create(alwaysClose: Boolean): BackIcon? {
            val packs = IconPackHelper.getActivePacks()
            val primary = if (alwaysClose) CLOSE else BACK
            if (packs.none { primary in it.iconNames }) return null
            val resources = ApplicationLoader.applicationContext.resources
            fun load(name: String, id: Int) = packs.firstNotNullOfOrNull { it.loadDrawable(resources, name, id) }
                ?: resources.getDrawable(id, null).mutate()
            return BackIcon(if (alwaysClose) null else load(BACK, R.drawable.ic_ab_back), load(CLOSE, R.drawable.ic_ab_close))
        }
    }
}
