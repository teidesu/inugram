package desu.inugram.helpers.plugins

import android.text.Spanned
import desu.inugram.helpers.plugins.ui.PluginText
import desu.inugram.helpers.plugins.ui.formatted
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.LocaleController
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.FormattedDateSpan
import org.telegram.ui.Components.QuoteSpan
import org.telegram.ui.Components.TextStyleSpan
import org.telegram.ui.Components.URLSpanNoUnderline
import org.telegram.ui.Components.URLSpanReplacement

/**
 * What a surface draws for each entity a plugin can put beside its text. The markup languages only
 * reach a handful of these - the rest arrive as TL objects a plugin built itself, or read off a
 * message - so the entities here are written out by hand rather than parsed from `md`/`html`.
 *
 * The two that carry their own presentation are the ones worth pinning: a blockquote becomes a
 * quote rather than a style run, and a formatted date *replaces the text it covers*, which stock
 * does in a second pass after the spans are placed.
 */
class PluginTextTest {

    @Before
    fun setUp() = resetBridge()

    private fun entity(name: String, offset: Int, length: Int, vararg extra: Pair<String, Any>) =
        JSONObject().apply {
            put("_", name)
            put("offset", offset)
            put("length", length)
            for ((key, value) in extra) put(key, value)
        }

    private fun render(text: String, vararg entities: JSONObject): Spanned =
        PluginText.formatted(text, JSONArray().also { array -> entities.forEach(array::put) }) as Spanned

    private inline fun <reified T> spansOf(text: Spanned): Array<T> =
        text.getSpans(0, text.length, T::class.java)

    private fun styleFlagsOf(text: Spanned): Int =
        spansOf<TextStyleSpan>(text).fold(0) { flags, span -> flags or span.textStyleRun.flags }

    @Test
    fun style_entities_become_style_runs() {
        val cases = listOf(
            "messageEntityBold" to TextStyleSpan.FLAG_STYLE_BOLD,
            "messageEntityItalic" to TextStyleSpan.FLAG_STYLE_ITALIC,
            "messageEntityUnderline" to TextStyleSpan.FLAG_STYLE_UNDERLINE,
            "messageEntityStrike" to TextStyleSpan.FLAG_STYLE_STRIKE,
            "messageEntitySpoiler" to TextStyleSpan.FLAG_STYLE_SPOILER,
        )
        for ((name, flag) in cases) {
            val rendered = render("hello world", entity(name, 0, 5))
            assertEquals("hello world", rendered.toString(), name)
            assertTrue(styleFlagsOf(rendered) and flag != 0, "$name did not set its style flag")
        }
    }

    /**
     * both spell "monospace", and both are rewritten away from stock's chat-bubble span by
     * [PluginText], which is why they are asserted as a plain style run rather than a `URLSpanMono`
     */
    @Test
    fun code_and_pre_become_monospace_runs() {
        for (one in listOf(entity("messageEntityCode", 0, 4), entity("messageEntityPre", 0, 4, "language" to "kotlin"))) {
            val rendered = render("code here", one)
            assertEquals("code here", rendered.toString())
            assertTrue(
                styleFlagsOf(rendered) and TextStyleSpan.FLAG_STYLE_MONO != 0,
                "${one.getString("_")} did not render as monospace",
            )
        }
    }

    @Test
    fun a_text_url_becomes_a_link_carrying_its_url() {
        val rendered = render("click here", entity("messageEntityTextUrl", 0, 5, "url" to "https://example.org/x"))
        val links = spansOf<URLSpanReplacement>(rendered)
        assertEquals(1, links.size)
        assertEquals("https://example.org/x", links[0].url)
        assertEquals(0, rendered.getSpanStart(links[0]))
        assertEquals(5, rendered.getSpanEnd(links[0]))
    }

    /** a quote is a block, not a run: stock puts it on through [QuoteSpan.putQuote] */
    @Test
    fun a_blockquote_becomes_a_quote() {
        val rendered = render("quoted line", entity("messageEntityBlockquote", 0, 11))
        assertTrue(spansOf<QuoteSpan.QuoteStyleSpan>(rendered).isNotEmpty(), "no quote span")
    }

    /** no markup produces these - they arrive already detected on a message the plugin read */
    @Test
    fun detected_entities_have_no_markup_and_still_render() {
        val phone = render("call +15551234567 now", entity("messageEntityPhone", 5, 12))
        val phoneLinks = spansOf<URLSpanNoUnderline>(phone)
        assertEquals(1, phoneLinks.size, "phone")
        assertEquals("tel:+15551234567", phoneLinks[0].url)

        val card = render("card 4111111111111111 ok", entity("messageEntityBankCard", 5, 16))
        val cardLinks = spansOf<URLSpanNoUnderline>(card)
        assertEquals(1, cardLinks.size, "bank card")
        assertTrue(cardLinks[0].url.startsWith("card:"), "bank card url was ${cardLinks[0].url}")

        // url/email/mention/hashtag carry no payload of their own: the text under them is the link
        for (name in listOf("messageEntityUrl", "messageEntityEmail", "messageEntityMention", "messageEntityHashtag")) {
            val rendered = render("see example here", entity(name, 4, 7))
            assertEquals("see example here", rendered.toString(), name)
        }
    }

    /**
     * the regression this file was written for: the span alone leaves the placeholder text on
     * screen, because stock substitutes the date in a pass of its own after the spans are placed
     */
    @Test
    fun a_formatted_date_replaces_the_text_it_covers() {
        val date = 1_647_531_900
        val one = entity("messageEntityFormattedDate", 8, 5, "date" to date, "short_time" to true)
        val rendered = render("meet at 22:45 today", one)

        val spans = spansOf<FormattedDateSpan>(rendered)
        assertEquals(1, spans.size, "no formatted-date span")
        assertEquals("22:45", spans[0].originalText)

        val expected = LocaleController.formatEntityFormattedDate(spans[0].entity)
        // the formatter is the thing under test here too, so an empty answer must not pass quietly
        assertTrue(expected.isNotEmpty(), "the formatter produced no text to substitute")
        assertEquals("meet at $expected today", rendered.toString())
        assertNotEquals("meet at 22:45 today", rendered.toString(), "the date was never substituted")
    }

    /**
     * `<tg-time unix=... format=...></tg-time>` covers no text at all: the app is being asked to
     * write the date, not to reformat something. Stock spans nothing of zero length, so the mark
     * [PluginText] leaves is what turns its substitution into an insertion.
     */
    @Test
    fun an_empty_formatted_date_writes_the_date_in() {
        val one = entity("messageEntityFormattedDate", 3, 0, "date" to 1_647_531_900, "short_time" to true)
        val rendered = render("at  ok", one)
        val spans = spansOf<FormattedDateSpan>(rendered)
        assertEquals(1, spans.size, "no formatted-date span for an empty entity")

        val expected = LocaleController.formatEntityFormattedDate(spans[0].entity)
        assertTrue(expected.isNotEmpty(), "the formatter produced no text to insert")
        assertEquals("at $expected ok", rendered.toString())
    }

    /** the text around an insertion keeps its own styling, and moves along with it */
    @Test
    fun an_empty_formatted_date_moves_what_follows_it() {
        val rendered = render(
            "at  ok",
            entity("messageEntityFormattedDate", 3, 0, "date" to 1_647_531_900, "short_time" to true),
            entity("messageEntityBold", 4, 2),
        )
        assertTrue(rendered.toString().startsWith("at "), "leading text moved")
        assertTrue(rendered.toString().endsWith(" ok"), "trailing text was overwritten")
        assertTrue(styleFlagsOf(rendered) and TextStyleSpan.FLAG_STYLE_BOLD != 0, "bold lost")
        val bold = spansOf<TextStyleSpan>(rendered).first { it.textStyleRun.flags and TextStyleSpan.FLAG_STYLE_BOLD != 0 }
        assertEquals("ok", rendered.substring(rendered.getSpanStart(bold), rendered.getSpanEnd(bold)))
    }

    /**
     * asking for no format means "keep the text as written", which stock reads off `flags == 0`.
     * With nothing written there is nothing to keep, so an empty entity with no format draws nothing
     */
    @Test
    fun an_empty_formatted_date_with_no_format_writes_nothing() {
        val rendered = render("at  ok", entity("messageEntityFormattedDate", 3, 0, "date" to 1_647_531_900))
        assertEquals("at  ok", rendered.toString())
    }

    /** with no format asked for there is nothing to substitute, and the written text stands */
    @Test
    fun a_formatted_date_without_a_format_keeps_its_own_text() {
        val one = entity("messageEntityFormattedDate", 8, 5, "date" to 1_647_531_900)
        val rendered = render("meet at 22:45 today", one)
        assertEquals("meet at 22:45 today", rendered.toString())
        assertEquals(1, spansOf<FormattedDateSpan>(rendered).size)
    }

    /** the date pass must not disturb the runs placed around and inside it */
    @Test
    fun a_formatted_date_survives_neighbouring_styles() {
        val rendered = render(
            "meet at 22:45 today",
            entity("messageEntityBold", 0, 7),
            entity("messageEntityFormattedDate", 8, 5, "date" to 1_647_531_900, "short_time" to true),
            entity("messageEntityItalic", 14, 5),
        )
        assertTrue(rendered.toString().startsWith("meet at "), "leading text moved")
        assertTrue(rendered.toString().endsWith(" today"), "trailing text moved")
        val flags = styleFlagsOf(rendered)
        assertTrue(flags and TextStyleSpan.FLAG_STYLE_BOLD != 0, "bold lost")
        assertTrue(flags and TextStyleSpan.FLAG_STYLE_ITALIC != 0, "italic lost")
        assertEquals(1, spansOf<FormattedDateSpan>(rendered).size)
    }

    /** a relative date re-reads the clock on every render, so the same entity may draw differently */
    @Test
    fun a_relative_date_formats_relatively() {
        val now = (System.currentTimeMillis() / 1000).toInt() - 90
        val one = entity("messageEntityFormattedDate", 0, 3, "date" to now, "relative" to true)
        val rendered = render("now and then", one)
        val spans = spansOf<FormattedDateSpan>(rendered)
        assertEquals(1, spans.size)
        assertTrue(spans[0].entity.relative, "the relative flag did not survive the json")
        assertNotEquals("now and then", rendered.toString(), "a relative date was not substituted")
    }

    /**
     * a surface reads text and entities as one pair. An entity that writes its own text leaves the
     * text half empty, so a key is only absent when both halves are - the guard every row, title
     * and message shares.
     */
    @Test
    fun a_key_carrying_only_entities_is_not_treated_as_absent() {
        val onlyEntities = JSONObject().apply {
            put("text", "")
            put("textEntities", JSONArray().put(entity("messageEntityFormattedDate", 0, 0, "date" to 1_647_531_900, "short_time" to true)))
        }
        val drawn = onlyEntities.formatted("text")
        assertNotNull(drawn, "a row with entities but no text was dropped")
        assertTrue(drawn.isNotEmpty(), "a row with entities but no text drew nothing")

        assertNull(JSONObject().apply { put("text", "") }.formatted("text"), "an empty key should stay absent")
        assertNull(JSONObject().formatted("text"), "a missing key should stay absent")
        assertNull(
            JSONObject().apply { put("text", ""); put("textEntities", JSONArray()) }.formatted("text"),
            "an empty entity list should stay absent",
        )
    }

    /** the row variant folds newlines to spaces, which must not move an entity off its text */
    @Test
    fun the_row_variant_keeps_entity_offsets() {
        val row = JSONObject().apply {
            put("text", "a\nbold")
            put("textEntities", JSONArray().put(entity("messageEntityBold", 2, 4)))
        }
        val drawn = row.formatted("text") { it.replace('\n', ' ') } as Spanned
        assertEquals("a bold", drawn.toString())
        assertTrue(styleFlagsOf(drawn) and TextStyleSpan.FLAG_STYLE_BOLD != 0, "bold moved off its text")
    }

    /** an entity the app cannot build costs the surface its entity, never its text */
    @Test
    fun a_malformed_entity_is_dropped_and_the_text_stands() {
        val rendered = render(
            "hello world",
            entity("messageEntityNotAThing", 0, 5),
            entity("messageEntityBold", 6, 5),
        )
        assertEquals("hello world", rendered.toString())
        assertTrue(styleFlagsOf(rendered) and TextStyleSpan.FLAG_STYLE_BOLD != 0, "the valid entity was dropped too")
    }

    /** an entity reaching past the end is clamped by stock rather than throwing */
    @Test
    fun an_out_of_range_entity_does_not_lose_the_text() {
        val rendered = render("short", entity("messageEntityBold", 3, 99))
        assertEquals("short", rendered.toString())
    }

    @Test
    fun text_with_no_entities_is_returned_as_written() {
        assertEquals("plain text", PluginText.formatted("plain text", null as JSONArray?).toString())
        assertEquals("plain text", PluginText.formatted("plain text", "").toString())
        assertEquals("plain text", PluginText.formatted("plain text", "not json").toString())
    }

    /**
     * a mention-name needs the username pass stock only runs for message text; a surface asks for
     * none, so the entity is dropped and the name it covered stays readable
     */
    @Test
    fun a_mention_name_keeps_its_text() {
        val rendered = render("ping alice now", entity("messageEntityMentionName", 5, 5, "user_id" to 777_000L))
        assertEquals("ping alice now", rendered.toString())
    }

    /** every kind at once, to catch an entity that throws only when it has company */
    @Test
    fun every_entity_kind_renders_together() {
        val text = "bold italic code link quote 22:45 +15551234567 #tag @user"
        val rendered = render(
            text,
            entity("messageEntityBold", 0, 4),
            entity("messageEntityItalic", 5, 6),
            entity("messageEntityCode", 12, 4),
            entity("messageEntityTextUrl", 17, 4, "url" to "https://example.org"),
            entity("messageEntityBlockquote", 22, 5),
            entity("messageEntityFormattedDate", 28, 5, "date" to 1_647_531_900, "short_time" to true),
            entity("messageEntityPhone", 34, 12),
            entity("messageEntityHashtag", 47, 4),
            entity("messageEntityMention", 52, 5),
        )
        assertTrue(rendered.isNotEmpty(), "everything was dropped")
        assertTrue(rendered.toString().startsWith("bold italic code link quote "), "the text ahead of the date moved")
        assertEquals(1, spansOf<FormattedDateSpan>(rendered).size)
        assertTrue(spansOf<QuoteSpan.QuoteStyleSpan>(rendered).isNotEmpty(), "quote lost")
        assertEquals(1, spansOf<URLSpanReplacement>(rendered).size, "link lost")
        val flags = styleFlagsOf(rendered)
        for ((name, flag) in listOf(
            "bold" to TextStyleSpan.FLAG_STYLE_BOLD,
            "italic" to TextStyleSpan.FLAG_STYLE_ITALIC,
            "mono" to TextStyleSpan.FLAG_STYLE_MONO,
        )) {
            assertTrue(flags and flag != 0, "$name lost")
        }
    }

    /** the entity json is the app's own: what [PluginText] reads back must be what a plugin wrote */
    @Test
    fun entity_json_round_trips_through_the_tl_layer() {
        val rendered = render(
            "meet at 22:45",
            entity("messageEntityFormattedDate", 8, 5, "date" to 1_647_531_900, "long_date" to true, "short_time" to true),
        )
        val entity = spansOf<FormattedDateSpan>(rendered).single().entity
        assertEquals(1_647_531_900, entity.date)
        assertTrue(entity.long_date, "long_date lost")
        assertTrue(entity.short_time, "short_time lost")
        assertNotEquals(0, entity.flags, "flags were never synced from the booleans")
    }
}
