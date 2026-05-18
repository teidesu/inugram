package desu.inugram.core.urlcleaner

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests ported from AdGuard's tsurlfilter `advanced-modifier.test.ts`
 * (RemoveParamModifier vectors). Source:
 *   https://github.com/AdguardTeam/tsurlfilter/blob/master/packages/tsurlfilter/test/modifiers/advanced-modifier.test.ts
 *
 * AdGuard's modifier API doesn't apply URL pattern matching, so where their
 * patterns are bare hosts we use `||host^` and feed URLs on the same host.
 */
class AdGuardCompatTest {

    private fun cleaner(vararg lines: String) =
        UrlCleaner.fromAdGuardFilter(lines.joinToString("\n"))

    private val comPage = "http://example.com/page"

    // --- literal param removal -------------------------------------------------

    @Test fun literalParamUnchangedWhenNoQuery() {
        val c = cleaner("\$removeparam=p1")
        assertEquals(comPage, c.clean(comPage))
    }

    @Test fun literalParamUnchangedWhenNotPresent() {
        val c = cleaner("\$removeparam=p1")
        assertEquals("$comPage?p0=0", c.clean("$comPage?p0=0"))
    }

    @Test fun literalParamRemoved() {
        val c = cleaner("\$removeparam=p1")
        assertEquals("$comPage?p0=0", c.clean("$comPage?p0=0&p1=1"))
        assertEquals("$comPage?p0=0&p2=2", c.clean("$comPage?p0=0&p1=1&p2=2"))
    }

    // --- regex param removal ---------------------------------------------------

    private val regexCleaner = cleaner("\$removeparam=/p1|p2|p3|p4_/i")

    @Test fun regexParamUnchangedWhenNoQuery() {
        assertEquals(comPage, regexCleaner.clean(comPage))
    }

    @Test fun regexParamUnchangedWhenNoMatch() {
        assertEquals("$comPage?p0=0", regexCleaner.clean("$comPage?p0=0"))
    }

    @Test fun regexRemovesValuelessParam() {
        assertEquals("$comPage?p0=0", regexCleaner.clean("$comPage?p0=0&p1"))
    }

    @Test fun regexCaseInsensitive() {
        assertEquals("$comPage?p0=0", regexCleaner.clean("$comPage?p0=0&p1=1&P2=2&P3=3"))
    }

    @Test fun regexSubstringMatchOnName() {
        // pattern alternative `p4_` matches substring within `p4_1=4`
        assertEquals("$comPage?p0=0", regexCleaner.clean("$comPage?p0=0&p4_1=4"))
    }

    @Test fun regexDoesNotMatchUnrelatedParam() {
        // `p4=4` doesn't contain `p4_`
        assertEquals("$comPage?p0=0&p4=4", regexCleaner.clean("$comPage?p0=0&p4=4"))
    }

    // --- naked rule strips everything ------------------------------------------

    @Test fun nakedRuleStripsAllParams() {
        val c = cleaner("||example.com^\$removeparam")
        assertEquals(comPage, c.clean(comPage))
        assertEquals(comPage, c.clean("$comPage?p0=0"))
        assertEquals(comPage, c.clean("$comPage?p0=0&p1=1"))
    }

    @Test fun nakedRuleStripsTrailingDelimiter() {
        // adguard behaviour: `url?` with naked rule → `url` (no `?`)
        val c = cleaner("||example.com^\$removeparam")
        assertEquals(comPage, c.clean("$comPage?"))
    }

    @Test fun nonMatchingRulePreservesTrailingDelimiter() {
        // `url?` with no params and a non-matching rule → keep the `?`
        val c = cleaner("\$removeparam=p1")
        assertEquals("$comPage?", c.clean("$comPage?"))
    }

    // --- inverted removeparam --------------------------------------------------

    @Test fun invertedLiteral() {
        val c = cleaner("\$removeparam=~p0")
        assertEquals(comPage, c.clean(comPage))
        assertEquals("$comPage?p0=0", c.clean("$comPage?p0=0"))
        assertEquals("$comPage?p0=0", c.clean("$comPage?p0=0&p1=1"))
        assertEquals(
            "$comPage?p0=0",
            c.clean("$comPage?p0=0&p1=1&p2=2&p3=3"),
        )
    }

    @Test fun invertedRegex() {
        val c = cleaner("\$removeparam=~/p0/")
        assertEquals(comPage, c.clean(comPage))
        assertEquals("$comPage?p0=0", c.clean("$comPage?p0=0"))
        assertEquals("$comPage?p0=0", c.clean("$comPage?p0=0&p1=1"))
        // substring match → `p01` contains `p0`, so it's kept
        assertEquals(
            "$comPage?p01=0",
            c.clean("$comPage?p01=0&p1=1&p2=2&p3=3"),
        )
    }

    // --- edge cases from adguard suite ----------------------------------------

    @Test fun doesNotApplyToAmpersandOnlyQuery() {
        val c = cleaner("\$removeparam=asg")
        val url = "https://example.org/path==.json?&test=1"
        assertEquals(url, c.clean(url))
    }

    @Test fun doesNotApplyToDoubleAmpersandQuery() {
        val c = cleaner("\$removeparam=asg")
        val url = "https://example.org/path==.json?t1=1&&t2=2"
        assertEquals(url, c.clean(url))
    }

    @Test fun doesNotModifyUrlWithoutMatchingParam() {
        val c = cleaner("\$removeparam=p1")
        val url = "https://l-stat.livejournal.net/js/??.comments.js?v=1619510974"
        assertEquals(url, c.clean(url))
    }

    // Deliberately omitted from adguard's suite: a `$removeparam=/comments/` test against
    // `https://l-stat.livejournal.net/js/??.comments.js?v=1619510974` where adguard expects
    // only `?v=1619510974` to be stripped. Their query-string splitter apparently anchors
    // at the *last* `?`, while ours follows RFC 3986 (first `?`). The divergence only
    // surfaces on URLs containing literal `?` after the first one, which is itself off-spec.
}
