package desu.inugram.ui.settings.fonts

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView
import desu.inugram.helpers.font.FontId
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme

/** Color-coded source chip (Stock / System / Custom) shared by the font roster, stack and picker rows. */
object FontSourceTag {
    private fun of(token: FontId): Pair<Int, Int> = when (token) {
        is FontId.System -> R.string.InuFontTagSystem to Theme.key_color_purple
        is FontId.Family -> R.string.InuFontTagCustom to Theme.key_color_orange
        is FontId.Builtin -> R.string.InuFontTagStock to Theme.key_color_blue
    }

    fun newView(context: Context): TextView = TextView(context).apply {
        typeface = AndroidUtilities.bold()
        textSize = 10f
        includeFontPadding = false
        gravity = Gravity.CENTER
        val padH = AndroidUtilities.dp(5f)
        val padV = AndroidUtilities.dp(1.33f)
        setPadding(padH, padV, padH, padV)
    }

    fun bind(view: TextView, token: FontId, alpha: Float = 1f) {
        val (labelRes, colorKey) = of(token)
        bindRaw(view, LocaleController.getString(labelRes), Theme.getColor(colorKey), alpha)
    }

    fun bindRaw(view: TextView, label: String, color: Int, alpha: Float = 1f) {
        view.text = label.uppercase()
        view.setTextColor(color)
        val bg = view.background as? GradientDrawable ?: GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = AndroidUtilities.dp(4f).toFloat()
            view.background = this
        }
        bg.setColor(Theme.multAlpha(color, if (Theme.isCurrentThemeDark()) 0.20f else 0.10f))
        view.alpha = alpha
    }
}
