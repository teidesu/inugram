package desu.inugram.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
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

        val errorName = CrashReporter.getCrashErrorName()
        val isOom = errorName == "OutOfMemoryError"
        val hasHeapDump = CrashReporter.hasHeapDump()

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
            text = LocaleController.getString(if (isOom) R.string.InuCrashTitleOom else R.string.InuCrashTitle)
        }
        container.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 21, 20, 21, 0))

        val description = TextView(context).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
            setLineSpacing(lineSpacingExtra, lineSpacingMultiplier * 1.1f)
            val descText = SpannableStringBuilder()
            if (errorName != null) {
                val start = descText.length
                descText.append(errorName)
                descText.setSpan(StyleSpan(Typeface.BOLD), start, descText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                descText.setSpan(
                    ForegroundColorSpan(Theme.getColor(Theme.key_dialogTextBlack)),
                    start,
                    descText.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                descText.append("\n\n")
            }
            descText.append(LocaleController.getString(if (isOom) R.string.InuCrashDescOom else R.string.InuCrashDesc))
            if (hasHeapDump) {
                descText.append("\n\n")
                descText.append(LocaleController.getString(R.string.InuCrashDescHeapDump))
            } else if (isOom) {
                descText.append("\n\n")
                descText.append(LocaleController.getString(R.string.InuCrashOomEnableLogs))
            }
            text = descText
        }
        container.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 28, 7, 28, 16))

        fun secondaryButton(textRes: Int, onClick: () -> Unit) = makeButton(
            context, textRes,
            background = Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(21f), 0, Theme.getColor(Theme.key_dialogButtonSelector),
            ),
            textColor = Theme.getColor(Theme.key_dialogTextBlack),
            bold = false,
            onClick = onClick,
        )

        fun primaryButton(textRes: Int, onClick: () -> Unit) = makeButton(
            context, textRes,
            background = Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 21f),
            textColor = Theme.getColor(Theme.key_featuredStickers_buttonText),
            bold = true,
            onClick = onClick,
        )

        val closeBtn = secondaryButton(if (isOom) R.string.Close else R.string.InuCrashDiscard) {
            CrashReporter.deleteCrashLog()
            dismiss()
        }

        val onSaveHeapDump = {
            (context as? LaunchActivity)?.let { activity ->
                dismiss()
                CrashReporter.saveHeapDump(activity)
            }
            Unit
        }

        fun saveHeapDumpButton(primary: Boolean) =
            if (primary) primaryButton(R.string.InuCrashShareHeapDump, onSaveHeapDump)
            else secondaryButton(R.string.InuCrashShareHeapDump, onSaveHeapDump)

        // OOM crash logs are near-useless, so don't prompt to share them; offer the heap dump
        // (or just a way out) instead.
        if (isOom) {
            if (hasHeapDump) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(closeBtn, LinearLayout.LayoutParams(0, AndroidUtilities.dp(42f), 1f).apply {
                        marginEnd = AndroidUtilities.dp(8f)
                    })
                    addView(saveHeapDumpButton(primary = true), LinearLayout.LayoutParams(0, AndroidUtilities.dp(42f), 1f))
                }
                container.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 16, 16))
            } else {
                container.addView(closeBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 0, 16, 0, 16, 16))
            }
        } else {
            val shareBtn = primaryButton(R.string.InuCrashShare) {
                val activity = (context as? LaunchActivity) ?: return@primaryButton
                dismiss()
                CrashReporter.shareCrashLog(activity)
            }
            val buttonRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(closeBtn, LinearLayout.LayoutParams(0, AndroidUtilities.dp(42f), 1f).apply {
                    marginEnd = AndroidUtilities.dp(8f)
                })
                addView(shareBtn, LinearLayout.LayoutParams(0, AndroidUtilities.dp(42f), 1f))
            }
            container.addView(
                buttonRow, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 16, if (hasHeapDump) 8 else 16,
                )
            )
            if (hasHeapDump) {
                container.addView(
                    saveHeapDumpButton(primary = false), LayoutHelper.createLinear(
                        LayoutHelper.MATCH_PARENT, 42, 0, 16, 0, 16, 16,
                    )
                )
            }
        }

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
