package desu.inugram.ui.settings.fonts

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

/** Parts of the shared font-row scaffold the owning row binds against. */
class FontRowViews(val handle: ImageView, val trailing: ImageView, val text: TextView, val tag: TextView)

/**
 * Builds the layout shared by the font roster row and the app-font-stack row into [host]: a leading
 * drag [handle][FontRowViews.handle], a name + source-tag column, and a trailing 48×48 icon button
 * (the caller configures its image / description). Returns the views for binding.
 */
fun buildFontRow(host: FrameLayout): FontRowViews {
    val context = host.context
    host.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
    val rtl = LocaleController.isRTL
    val leadGravity = (if (rtl) Gravity.RIGHT else Gravity.LEFT) or Gravity.CENTER_VERTICAL
    val trailGravity = (if (rtl) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL
    val leadHorizontal = if (rtl) Gravity.RIGHT else Gravity.LEFT

    val handle = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER
        setImageResource(R.drawable.list_reorder)
        colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_stickers_menu), PorterDuff.Mode.MULTIPLY)
        contentDescription = LocaleController.getString(R.string.FilterReorder)
        isClickable = true
    }
    host.addView(handle, LayoutHelper.createFrame(48, 48f, leadGravity, 4f, 0f, 4f, 0f))

    val trailing = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER
        colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY)
    }
    host.addView(trailing, LayoutHelper.createFrame(48, 48f, trailGravity, 4f, 0f, 4f, 0f))

    val text = TextView(context).apply {
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        textSize = 16f
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        gravity = leadHorizontal
        includeFontPadding = false // CJK fonts have tall metrics that overflow the fixed-height row otherwise
    }
    val tag = FontSourceTag.newView(context)
    val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = leadGravity
        addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(
            tag,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = AndroidUtilities.dp(4f) },
        )
    }
    host.addView(
        column,
        LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), leadGravity, 64f, 0f, 64f, 0f)
    )
    return FontRowViews(handle, trailing, text, tag)
}
