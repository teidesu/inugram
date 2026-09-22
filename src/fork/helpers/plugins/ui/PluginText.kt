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
import org.telegram.ui.Components.FormattedDateSpan
import org.telegram.ui.Components.TextStyleSpan
import org.telegram.ui.Components.URLSpanMono

/**
 * Converts `InputText` and its entities to stock spans (Rust: `arguments::read_input_text`).
 * Drops malformed or unsupported entities while keeping their text. Custom emoji use the
 * target view's [fontMetrics] for sizing; without metrics, they remain plain text.
 */
object PluginText {

    @JvmStatic
    @JvmOverloads
    fun formatted(text: String, entitiesJson: String?, fontMetrics: Paint.FontMetricsInt? = null): CharSequence =
        formatted(text, parseArray(entitiesJson), fontMetrics)

    @JvmStatic
    @JvmOverloads
    fun formatted(text: String, entities: JSONArray?, fontMetrics: Paint.FontMetricsInt? = null): CharSequence {
        val parsed = parseEntities(entities)
        val out = SpannableStringBuilder(text)
        var result: CharSequence = out
        if (parsed.isNotEmpty()) {
            MessageObject.addEntitiesToText(out, parsed, false, false, false, false)
            markEmptyDates(out, parsed)
            inheritCodeColor(out)
            if (fontMetrics != null) result = MessageObject.replaceAnimatedEmoji(out, parsed, fontMetrics)
            result = FormattedDateSpan.applyFormatedDateEntities(result)
        }
        return Emoji.replaceEmoji(result, fontMetrics, false)
    }

    @JvmStatic
    fun parseEntities(entitiesJson: String?): ArrayList<TLRPC.MessageEntity> = parseEntities(parseArray(entitiesJson))

    private fun parseArray(entitiesJson: String?): JSONArray? =
        entitiesJson?.takeIf { it.isNotEmpty() }?.let {
            try {
                JSONArray(it)
            } catch (e: Exception) {
                null
            }
        }

    private fun parseEntities(entities: JSONArray?): ArrayList<TLRPC.MessageEntity> {
        val parsed = ArrayList<TLRPC.MessageEntity>()
        if (entities == null) return parsed
        for (index in 0 until entities.length()) {
            val one = entities.optJSONObject(index) ?: continue
            (runCatching { TlJson.fromJson(one) }.getOrNull() as? TLRPC.MessageEntity)?.let(parsed::add)
        }
        return parsed
    }

    /**
     * `<tg-time unix=...></tg-time>` says "the app writes the date here", and so covers no text for
     * [MessageObject.addEntitiesToText] to span - it skips `length <= 0`, and the date renders as
     * nothing. Marking the spot is all that is missing: stock's own substitution pass replaces a
     * span's range with the date, and an empty range makes that replacement an insertion.
     *
     * The mark is `SPAN_INCLUSIVE_EXCLUSIVE` because a `SpannableStringBuilder` drops an empty
     * `SPAN_EXCLUSIVE_EXCLUSIVE` span outright.
     */
    private fun markEmptyDates(out: SpannableStringBuilder, parsed: List<TLRPC.MessageEntity>) {
        for (one in parsed) {
            if (one !is TLRPC.TL_messageEntityFormattedDate || one.length != 0) continue
            if (one.offset < 0 || one.offset > out.length) continue
            val run = TextStyleSpan.TextStyleRun().also { it.start = one.offset; it.end = one.offset }
            out.setSpan(FormattedDateSpan("", run, one), one.offset, one.offset, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * Stock code spans force chat text color and size, making them unsuitable for other views,
     * such as dark bulletins. Use a monospace style span that inherits the view's color and size.
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
