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

/** malformed or unsupported entities are dropped, keeping their text. Custom emoji need [fontMetrics] */
object PluginText {
    fun formatted(text: String, entitiesJson: String?, fontMetrics: Paint.FontMetricsInt? = null): CharSequence =
        formatted(text, parseArray(entitiesJson), fontMetrics)

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
     * `<tg-time>` covers no text, and [MessageObject.addEntitiesToText] skips `length <= 0`. Stock's substitution
     * replaces a span's range with the date, so an empty marked range becomes an insertion.
     * `SPAN_INCLUSIVE_EXCLUSIVE` because `SpannableStringBuilder` drops an empty `SPAN_EXCLUSIVE_EXCLUSIVE` span.
     */
    private fun markEmptyDates(out: SpannableStringBuilder, parsed: List<TLRPC.MessageEntity>) {
        for (one in parsed) {
            if (one !is TLRPC.TL_messageEntityFormattedDate || one.length != 0) continue
            if (one.offset < 0 || one.offset > out.length) continue
            val run = TextStyleSpan.TextStyleRun().also { it.start = one.offset; it.end = one.offset }
            out.setSpan(FormattedDateSpan("", run, one), one.offset, one.offset, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
    }

    /** stock code spans force chat text color and size, wrong in e.g. dark bulletins */
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
