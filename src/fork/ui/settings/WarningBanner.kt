package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
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
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.createRoundRectDrawable(
                AndroidUtilities.dp(12f),
                Theme.multAlpha(Theme.getColor(Theme.key_color_orange), 0.15f),
            )
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(14f), AndroidUtilities.dp(16f), AndroidUtilities.dp(14f))
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.msg_warning)
            setColorFilter(Theme.getColor(Theme.key_color_orange))
        }
        titleRow.addView(icon, LayoutHelper.createLinear(20, 20))
        title = TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_color_orange))
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
}
