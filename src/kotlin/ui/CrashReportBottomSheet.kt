package desu.inugram.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import desu.inugram.helpers.CrashReporter
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.StickerImageView
import org.telegram.ui.LaunchActivity

class CrashReportBottomSheet(context: Context) : BottomSheet(context, false) {

    init {
        setApplyBottomPadding(false)
        setApplyTopPadding(false)
        fixNavigationBar(getThemedColor(Theme.key_windowBackgroundWhite))

        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val sticker = StickerImageView(context, UserConfig.selectedAccount).apply {
            setStickerNum(0)
            imageReceiver.setAutoRepeat(1)
        }
        container.addView(sticker, LayoutHelper.createLinear(110, 110, Gravity.CENTER_HORIZONTAL, 0, 26, 0, 0))

        val title = TextView(context).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            typeface = AndroidUtilities.bold()
            text = LocaleController.getString(R.string.InuCrashTitle)
        }
        container.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 21, 20, 21, 0))

        val description = TextView(context).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
            setLineSpacing(lineSpacingExtra, lineSpacingMultiplier * 1.1f)
            text = LocaleController.getString(R.string.InuCrashDesc)
        }
        container.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 28, 7, 28, 16))

        val discardBtn = makeButton(
            context, R.string.InuCrashDiscard,
            background = Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(21f), 0, Theme.getColor(Theme.key_listSelector),
            ),
            textColor = Theme.getColor(Theme.key_featuredStickers_addButton),
            bold = false,
        ) {
            CrashReporter.deleteCrashLog()
            dismiss()
        }
        val shareBtn = makeButton(
            context, R.string.InuCrashShare,
            background = Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 21f),
            textColor = Theme.getColor(Theme.key_featuredStickers_buttonText),
            bold = true,
        ) {
            val activity = (context as? LaunchActivity) ?: return@makeButton
            dismiss()
            CrashReporter.shareCrashLog(activity)
        }
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(discardBtn, LinearLayout.LayoutParams(0, AndroidUtilities.dp(42f), 1f).apply {
                marginEnd = AndroidUtilities.dp(8f)
            })
            addView(shareBtn, LinearLayout.LayoutParams(0, AndroidUtilities.dp(42f), 1f))
        }
        container.addView(buttonRow, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 16, 16,
        ))

        setCustomView(NestedScrollView(context).apply { addView(container) })
    }

    private fun makeButton(
        context: Context,
        textRes: Int,
        background: Drawable,
        textColor: Int,
        bold: Boolean,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = LocaleController.getString(textRes)
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
}
