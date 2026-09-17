package desu.inugram.helpers.plugins.ui

import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import desu.inugram.helpers.plugins.tl.TlJson
import org.json.JSONArray
import org.telegram.messenger.CodeHighlighting
import org.telegram.messenger.Emoji
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.TextStyleSpan
import org.telegram.ui.Components.URLSpanMono

/**
 * `InputText` as a surface draws it: the plain text plus the entities beside it, turned into the
 * spans stock already knows how to render (rust: `arguments::read_input_text`).
 *
 * An entity the app cannot build is dropped rather than refused - a surface asked for text, and a
 * malformed entity must not cost it the text. [fontMetrics] is the target view's, and is what custom
 * emoji need to size themselves; without it they stay the text they replaced.
 */
object PluginText {

    @JvmStatic
    @JvmOverloads
    fun formatted(text: String, entitiesJson: String?, fontMetrics: Paint.FontMetricsInt? = null): CharSequence {
        val array = entitiesJson?.takeIf { it.isNotEmpty() }?.let {
            try {
                JSONArray(it)
            } catch (e: Exception) {
                null
            }
        }
        return formatted(text, array, fontMetrics)
    }

    @JvmStatic
    @JvmOverloads
    fun formatted(text: String, entities: JSONArray?, fontMetrics: Paint.FontMetricsInt? = null): CharSequence {
        val parsed = ArrayList<TLRPC.MessageEntity>()
        if (entities != null) {
            for (index in 0 until entities.length()) {
                val one = entities.optJSONObject(index) ?: continue
                (runCatching { TlJson.fromJson(one) }.getOrNull() as? TLRPC.MessageEntity)?.let(parsed::add)
            }
        }
        val out = SpannableStringBuilder(text)
        var result: CharSequence = out
        if (parsed.isNotEmpty()) {
            MessageObject.addEntitiesToText(out, parsed, false, false, false, false)
            inheritCodeColor(out)
            if (fontMetrics != null) result = MessageObject.replaceAnimatedEmoji(out, parsed, fontMetrics)
        }
        return Emoji.replaceEmoji(result, fontMetrics, false)
    }

    /**
     * Stock draws a code entity through a span that hard-sets the chat bubble's own text colour and
     * the chat font size, since in a message that is the only place it can appear: unreadable on a
     * bulletin's dark background, and mis-sized anywhere else. A plain style span keeps the monospace
     * face and inherits whatever colour and size the view draws with.
     */
    private fun inheritCodeColor(text: SpannableStringBuilder) {
        for (span in text.getSpans(0, text.length, URLSpanMono::class.java)) {
            replaceWithMono(text, span)
        }
        for (span in text.getSpans(0, text.length, CodeHighlighting.Span::class.java)) {
            replaceWithMono(text, span)
        }
    }

    private fun replaceWithMono(text: SpannableStringBuilder, span: Any) {
        val start = text.getSpanStart(span)
        val end = text.getSpanEnd(span)
        text.removeSpan(span)
        if (start < 0 || end <= start) return
        val run = TextStyleSpan.TextStyleRun().also { it.flags = TextStyleSpan.FLAG_STYLE_MONO }
        text.setSpan(TextStyleSpan(run), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
