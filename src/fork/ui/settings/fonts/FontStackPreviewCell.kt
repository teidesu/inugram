package desu.inugram.ui.settings.fonts

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import android.widget.TextView
import desu.inugram.helpers.font.FontHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.TypefaceSpan

/**
 * Lightweight live preview for the font stack: a single text view whose sample carries bold / italic /
 * underline / strike / mono spans, each rendered directly in the draft stack's typeface. No real
 * [org.telegram.ui.Cells.ChatMessageCell] / global typeface swap — spans are styled in place.
 */
@SuppressLint("ViewConstructor")
class FontStackPreviewCell(context: Context, private val fragment: BaseFragment) : TextView(context) {
    private var primary: String? = null
    private var fallbacks: List<String> = emptyList()
    private var monoToken: String = ""

    init {
        textSize = 16f
        setLineSpacing(AndroidUtilities.dp(3f).toFloat(), 1f)
        setPadding(AndroidUtilities.dp(20f), AndroidUtilities.dp(14f), AndroidUtilities.dp(20f), AndroidUtilities.dp(14f))
        includeFontPadding = false // CJK fonts have tall metrics; keep it compact
    }

    fun setStack(primary: String?, fallbacks: List<String>, monoToken: String) {
        this.primary = primary
        this.fallbacks = fallbacks
        this.monoToken = monoToken
        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh() // pick up a theme change while scrolled away
    }

    private fun refresh() {
        val tfs = resolveTypefaces()
        typeface = tfs.regular // base for unspanned text; narrower spans override the styled runs
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, fragment.resourceProvider))
        text = buildText(tfs)
    }

    private fun resolveTypefaces(): FontHelper.PreviewTypefaces {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return FontHelper.buildPreviewTypefaces(primary, fallbacks, monoToken)
        }
        val d = FontHelper.stockDefault
        return FontHelper.PreviewTypefaces(d, d, d, FontHelper.stockMonospace)
    }

    private fun buildText(tfs: FontHelper.PreviewTypefaces): CharSequence {
        // Latin + Cyrillic + CJK across the styled lines; bold / italic / underline / strike + a mono block
        val l1 = "The quick brown fox. Съешь ещё этих булочек. 日本語 中文 한국어. Inline mono: rm -rf\n"
        val l2 = "Mixed weights: bold, italic, underline, strike. 漢字 かな 가나 0123\n"
        val code = "function greet(name: string) {\n  console.log(`Привет, \${name}`)\n}"
        val s = l1 + l2 + code
        return SpannableStringBuilder(s).apply {
            span(this, s, "quick", TypefaceSpan(tfs.bold))
            span(this, s, "ещё", TypefaceSpan(tfs.italic))
            span(this, s, "fox", UnderlineSpan())
            span(this, s, "rm -rf", TypefaceSpan(tfs.mono))
            span(this, s, "bold", TypefaceSpan(tfs.bold))
            span(this, s, "italic", TypefaceSpan(tfs.italic))
            span(this, s, "underline", UnderlineSpan())
            span(this, s, "strike", StrikethroughSpan())
            setSpan(TypefaceSpan(tfs.mono), l1.length + l2.length, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun span(b: SpannableStringBuilder, text: String, word: String, span: Any) {
        val i = text.indexOf(word)
        if (i < 0) return
        b.setSpan(span, i, i + word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
