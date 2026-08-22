package desu.inugram.ui.settings

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ScaleStateListAnimator
import org.telegram.ui.Stars.ExplainStarsSheet
import org.telegram.ui.Stories.recorder.ButtonWithCounterView

/**
 * The shape every "this is not a preference, read it first" plugin sheet takes: danger icon, what
 * is about to be allowed, what it means in three lines, the one-sentence warning, and an accept
 * button that stays dead long enough for the text above it to be read.
 *
 * The caller owns the bit that gets set - the sheet only reports that the warning was accepted.
 */
object PluginConsentSheet {
    private const val CONFIRM_DELAY_SECONDS = 10

    class Feature(val iconRes: Int, val titleRes: Int, val textRes: Int)

    fun show(
        context: Context,
        resourcesProvider: Theme.ResourcesProvider?,
        titleRes: Int,
        infoRes: Int,
        features: List<Feature>,
        warningRes: Int,
        acceptRes: Int,
        onAccept: () -> Unit,
    ) {
        val danger = Theme.getColor(Theme.key_text_RedRegular)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(20f), dp(16f), dp(8f))
            clipChildren = false
            clipToPadding = false
        }

        val icon = ImageView(context).apply {
            setImageResource(R.drawable.inu_tabler_alert_triangle_filled)
            colorFilter = PorterDuffColorFilter(danger, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val iconCircle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Theme.multAlpha(danger, if (Theme.isCurrentThemeDark()) 0.22f else 0.14f))
            }
            addView(icon, LayoutHelper.createFrame(40, 40, Gravity.CENTER))
        }
        content.addView(iconCircle, LayoutHelper.createLinear(84, 84, Gravity.CENTER_HORIZONTAL, 0f, 0f, 0f, 12f))

        val title = TextView(context).apply {
            typeface = AndroidUtilities.bold()
            textSize = 20f
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider))
            text = getString(titleRes)
            gravity = Gravity.CENTER
        }
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0f, 0f, 0f, 8f))

        val info = TextView(context).apply {
            textSize = 14f
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider))
            text = getString(infoRes)
            gravity = Gravity.CENTER
        }
        content.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 8f, 0f, 8f, 20f))

        for (feature in features) {
            val cell = ExplainStarsSheet.FeatureCell(context, ExplainStarsSheet.FeatureCell.STYLE_SHEET, resourcesProvider)
            cell.set(feature.iconRes, getString(feature.titleRes), getString(feature.textRes))
            cell.imageView.layoutParams = LayoutHelper.createLinear(24, 24, Gravity.TOP or Gravity.LEFT, 0, 6, 22, 0)
            content.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 10f, 0f, 10f, 0f))
        }

        val pill = TextView(context).apply {
            background = Theme.createRoundRectDrawable(
                dp(12f),
                Theme.multAlpha(danger, if (Theme.isCurrentThemeDark()) 0.22f else 0.14f),
            )
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            textSize = 14f
            typeface = AndroidUtilities.bold()
            setTextColor(danger)
            text = getString(warningRes)
            gravity = Gravity.CENTER
        }
        content.addView(pill, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 8f, 0f, 4f))

        val sheet = arrayOfNulls<BottomSheet>(1)
        val accept = ButtonWithCounterView(context, false, resourcesProvider).apply {
            ScaleStateListAnimator.reset(this)
            // a plain button has no fill, so the radius only rounds its own ripple
            setRoundRadius(24)
            setText(getString(acceptRes), false)
            setOnClickListener {
                if (isTimerActive) return@setOnClickListener
                sheet[0]?.dismiss()
                onAccept()
            }
            // the counter ticks down beside the label; until it runs out the button is dead, so the
            // warning above it is on screen for as long as it takes to read. `setOnClickListener`
            // is what made it clickable, so the lock goes after it - and the timer unlocks it,
            // which is the pairing stock's own `setTimer` expects
            isEnabled = false
            isClickable = false
            setTimer(CONFIRM_DELAY_SECONDS) { isEnabled = true }
        }
        content.addView(accept, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, Gravity.FILL_HORIZONTAL, 0f, 13f, 0f, 8f))
        // filled, and last: the button that stops here is the one a hurried tap should hit
        val cancel = ButtonWithCounterView(context, true, resourcesProvider).apply {
            ScaleStateListAnimator.reset(this)
            setRoundRadius(24)
            setText(getString(R.string.Cancel), false)
            setOnClickListener { sheet[0]?.dismiss() }
        }
        content.addView(cancel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, Gravity.FILL_HORIZONTAL))

        sheet[0] = BottomSheet.Builder(context, false, resourcesProvider).setCustomView(content).create().apply {
            useBackgroundTopPadding = false
            fixNavigationBar()
            show()
        }
    }
}
