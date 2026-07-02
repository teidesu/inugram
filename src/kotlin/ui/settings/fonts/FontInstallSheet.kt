package desu.inugram.ui.settings.fonts

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Layout
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import desu.inugram.helpers.font.FontLibrary
import desu.inugram.helpers.font.SfntParser
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import java.io.File

class FontInstallSheet(
    context: Context,
    file: File,
    info: FontLibrary.FontInfo,
    displayName: String,
    private val onInstall: () -> Unit,
    private val onOpen: () -> Unit,
) : BottomSheet(context, false) {

    init {
        setApplyBottomPadding(false)
        setApplyTopPadding(false)
        fixNavigationBar(getThemedColor(Theme.key_windowBackgroundWhite))

        val typeface = try {
            Typeface.createFromFile(file)
        } catch (_: Throwable) {
            Typeface.DEFAULT
        }

        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val title = TextView(context).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            this.typeface = AndroidUtilities.bold()
            text = info.family?.takeIf(String::isNotBlank) ?: displayName
        }
        container.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 24, 20, 6))

        val preview = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22f)
            this.typeface = typeface
            text = LocaleController.getString(R.string.InuFontPreviewSample)
            if (Build.VERSION.SDK_INT >= 23) {
                breakStrategy = Layout.BREAK_STRATEGY_BALANCED
            }
        }
        container.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 12, 24, 12))

        val meta = TextView(context).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
            text = if (info.faceCount == 1) {
                styleName(info.faces[0])
            } else {
                LocaleController.formatPluralString("InuFontStyles", info.faceCount)
            }
        }
        container.addView(meta, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 0, 20, 16))

        val status = FontLibrary.getInstallStatus(info)
        val installText = when (status.kind) {
            FontLibrary.InstallKind.NEW -> LocaleController.getString(R.string.InuFontInstall)
            FontLibrary.InstallKind.REINSTALL -> LocaleController.getString(R.string.InuFontReinstall)
            FontLibrary.InstallKind.ADD -> LocaleController.formatString(R.string.InuFontAddToFamily, status.familyName ?: "")
        }

        val openBtn = makeButton(
            context, LocaleController.getString(R.string.InuFontOpenIn),
            background = Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(21f), 0, Theme.getColor(Theme.key_dialogButtonSelector),
            ),
            textColor = Theme.getColor(Theme.key_dialogTextBlack),
            bold = false,
        ) {
            dismiss()
            onOpen()
        }

        val installBtn = makeButton(
            context, installText,
            background = Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 21f),
            textColor = Theme.getColor(Theme.key_featuredStickers_buttonText),
            bold = true,
        ) {
            dismiss()
            onInstall()
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(openBtn, LinearLayout.LayoutParams(0, AndroidUtilities.dp(42f), 1f).apply {
                marginEnd = AndroidUtilities.dp(8f)
            })
            addView(installBtn, LinearLayout.LayoutParams(0, AndroidUtilities.dp(42f), 1f))
        }
        container.addView(buttonRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 16, 16, 16))

        setCustomView(NestedScrollView(context).apply { addView(container) })
    }

    private fun styleName(face: SfntParser.RawFace): String {
        if (face.variable) {
            val slant = LocaleController.getString(
                if (face.italic) R.string.InuFontStyleItalic else R.string.InuFontStyleRegular
            )
            return LocaleController.formatString(R.string.InuFontVariableSlant, slant)
        }
        val weight = face.weight.toString()
        return if (face.italic) LocaleController.formatString(R.string.InuFontStyleItalicSuffix, weight) else weight
    }

    private fun makeButton(
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
}
