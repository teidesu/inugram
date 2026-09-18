package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import desu.inugram.core.plugins.SourceObfuscation
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Stories.recorder.ButtonWithCounterView

@SuppressLint("ViewConstructor")
class WarningBanner(
    context: Context,
    buttonText: CharSequence? = null,
    onButton: (() -> Unit)? = null,
) : FrameLayout(context) {
    private val title: TextView
    private val message: TextView

    init {
        // the same orange the caution permissions are badged with, rather than the theme's own:
        // a monet palette pulls key_color_orange towards the wallpaper and the card goes grey
        val orange = tierColors(GrantTier.CAUTION).first
        val dark = Theme.isCurrentThemeDark()
        // opaque, and mixed with black/white rather than laid over the page at low alpha: this row
        // sits on the list's grey background, and any translucent tint takes that grey with it
        val fill = ColorUtils.blendARGB(orange, if (dark) Color.BLACK else Color.WHITE, if (dark) 0.76f else 0.86f)
        val accent = ColorUtils.blendARGB(orange, if (dark) Color.WHITE else Color.BLACK, 0.25f)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.createRoundRectDrawable(AndroidUtilities.dp(12f), fill)
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(14f), AndroidUtilities.dp(16f), AndroidUtilities.dp(14f))
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.inu_tabler_alert_triangle_filled)
            setColorFilter(accent)
        }
        titleRow.addView(icon, LayoutHelper.createLinear(20, 20))
        title = TextView(context).apply {
            setTextColor(accent)
            textSize = 15f
            typeface = AndroidUtilities.bold()
        }
        titleRow.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8f, 0f, 0f, 0f))
        card.addView(titleRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        message = TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            textSize = 14f
        }
        card.addView(message, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 8f, 0f, 0f))

        if (buttonText != null) {
            val button = ButtonWithCounterView(context, true, null).apply {
                setText(buttonText, false)
                setOnClickListener { onButton?.invoke() }
            }
            card.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 0f, 12f, 0f, 0f))
        }

        addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), 0, 12f, 8f, 12f, 8f))
    }

    fun setTitle(text: CharSequence) {
        title.text = text
    }

    fun setText(text: CharSequence) {
        message.text = text
    }

    /** the one wording for a source the detector flagged, wherever a plugin's source is shown */
    fun setObfuscation(kind: SourceObfuscation) {
        val (titleRes, infoRes) = OBFUSCATION_STRINGS.getValue(kind)
        setTitle(LocaleController.getString(titleRes))
        setText(LocaleController.getString(infoRes))
    }

    private companion object {
        val OBFUSCATION_STRINGS = mapOf(
            SourceObfuscation.OBFUSCATED to (R.string.InuPluginObfuscatedTitle to R.string.InuPluginObfuscatedInfo),
            SourceObfuscation.MINIFIED to (R.string.InuPluginMinifiedTitle to R.string.InuPluginMinifiedInfo),
        )
    }
}
