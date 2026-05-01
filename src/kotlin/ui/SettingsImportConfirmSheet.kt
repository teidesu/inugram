package desu.inugram.ui

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.StickerImageView

class SettingsImportConfirmSheet(
    context: Context,
    private val changed: Int,
    private val onConfirm: () -> Unit,
) : BottomSheet(context, false) {

    init {
        setApplyBottomPadding(false)
        setApplyTopPadding(false)
        fixNavigationBar(getThemedColor(Theme.key_windowBackgroundWhite))

        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val sticker = StickerImageView(context, UserConfig.selectedAccount).apply {
            setStickerNum(9)
            imageReceiver.setAutoRepeat(1)
        }
        container.addView(sticker, LayoutHelper.createLinear(110, 110, Gravity.CENTER_HORIZONTAL, 0, 26, 0, 0))

        val title = TextView(context).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            typeface = AndroidUtilities.bold()
            text = LocaleController.getString(R.string.InuBackupImportSheetTitle)
        }
        container.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 21, 20, 21, 0))

        val description = TextView(context).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
            setLineSpacing(lineSpacingExtra, lineSpacingMultiplier * 1.1f)
            text = AndroidUtilities.replaceTags(
                LocaleController.formatPluralString("InuBackupImportSheetChanges", changed)
            )
        }
        container.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 28, 7, 28, 0))

        val confirmBtn = TextView(context).apply {
            gravity = Gravity.CENTER
            ellipsize = TextUtils.TruncateAt.END
            isSingleLine = true
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            typeface = AndroidUtilities.bold()
            text = LocaleController.getString(R.string.InuBackupImportSheetApply)
            setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText))
            background = Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(8f),
                Theme.getColor(Theme.key_featuredStickers_addButton),
                ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_windowBackgroundWhite), 120)
            )
            setOnClickListener {
                dismiss()
                onConfirm()
            }
        }
        container.addView(confirmBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 14, 28, 14, 6))

        val cancelBtn = TextView(context).apply {
            gravity = Gravity.CENTER
            ellipsize = TextUtils.TruncateAt.END
            isSingleLine = true
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            typeface = AndroidUtilities.bold()
            text = LocaleController.getString(R.string.InuBackupImportSheetCancel)
            setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton))
            background = Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(8f),
                Color.TRANSPARENT,
                ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_featuredStickers_addButton), 120)
            )
            setOnClickListener { dismiss() }
        }
        container.addView(cancelBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 14, 0, 14, 6))

        val scroll = NestedScrollView(context).apply { addView(container) }
        setCustomView(scroll)
    }
}
