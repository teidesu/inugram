package desu.inugram.core.urlcleaner

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlCleanerTest {

    private fun cleaner(vararg lines: String) =
        UrlCleaner.fromAdGuardFilter(lines.joinToString("\n"))

    @Test
    fun stripsGlobalUtm() {
        val c = cleaner("\$removeparam=/^utm_/")
        assertEquals(
            "https://example.com/x?keep=1",
            c.clean("https://example.com/x?utm_source=a&keep=1&utm_medium=b"),
        )
    }

    @Test
    fun stripsLiteralGlobal() {
        val c = cleaner("\$removeparam=fbclid")
        assertEquals(
            "https://example.com/?a=1",
            c.clean("https://example.com/?fbclid=xyz&a=1"),
        )
    }

    @Test
    fun untouchedWhenNoMatch() {
        val c = cleaner("\$removeparam=fbclid")
        val url = "https://example.com/?a=1&b=2"
        assertEquals(url, c.clean(url))
    }

    @Test
    fun untouchedWhenNoQuery() {
        val c = cleaner("\$removeparam=/^utm_/")
        assertEquals("https://example.com/path", c.clean("https://example.com/path"))
    }

    @Test
    fun preservesFragment() {
        val c = cleaner("\$removeparam=utm_source")
        assertEquals(
            "https://example.com/#section",
            c.clean("https://example.com/?utm_source=a#section"),
        )
    }

    @Test
    fun preservesOrder() {
        val c = cleaner("\$removeparam=drop")
        assertEquals(
            "https://example.com/?a=1&b=2&c=3",
            c.clean("https://example.com/?a=1&drop=x&b=2&drop=y&c=3"),
        )
    }

    @Test
    fun domainScopedAppliesOnSubdomain() {
        val c = cleaner("||example.com^\$removeparam=sid")
        assertEquals(
            "https://www.example.com/?a=1",
            c.clean("https://www.example.com/?sid=x&a=1"),
        )
    }

    @Test
    fun domainScopedDoesNotApplyElsewhere() {
        val c = cleaner("||example.com^\$removeparam=sid")
        val url = "https://other.com/?sid=x&a=1"
        assertEquals(url, c.clean(url))
    }

    @Test
    fun domainModifierSyntax() {
        val c = cleaner("\$removeparam=promo,domain=vjav.com|vjav.tube")
        assertEquals(
            "https://vjav.com/?a=1",
            c.clean("https://vjav.com/?promo=x&a=1"),
        )
        val unrelated = "https://other.com/?promo=x&a=1"
        assertEquals(unrelated, c.clean(unrelated))
    }

    @Test
    fun negativeDomainModifier() {
        val c = cleaner("\$removeparam=/^__s=/,domain=~safe.com")
        assertEquals(
            "https://other.com/?a=1",
            c.clean("https://other.com/?__s=trackme&a=1"),
        )
        val safe = "https://safe.com/?__s=trackme&a=1"
        assertEquals(safe, c.clean(safe))
    }

    @Test
    fun denyallowModifier() {
        val c = cleaner("\$denyallow=video-shoper.ru|glavnoe.life,removeparam=utm_source")
        assertEquals(
            "https://open.spotify.com/album/1?si=x",
            c.clean("https://open.spotify.com/album/1?si=x&utm_source=copy-link"),
        )
        val denied = "https://glavnoe.life/news?utm_source=keep"
        assertEquals(denied, c.clean(denied))
    }

    @Test
    fun denyallowAppliesToSubdomains() {
        val c = cleaner("\$denyallow=glavnoe.life,removeparam=utm_source")
        val denied = "https://www.glavnoe.life/news?utm_source=keep"
        assertEquals(denied, c.clean(denied))
    }

    @Test
    fun denyallowCombinesWithDomainModifier() {
        val c = cleaner("\$removeparam=utm_source,domain=example.com,denyallow=safe.example.com")
        assertEquals("https://example.com/?a=1", c.clean("https://example.com/?utm_source=x&a=1"))
        val denied = "https://safe.example.com/?utm_source=keep"
        assertEquals(denied, c.clean(denied))
    }

    @Test
    fun perParamException() {
        // global utm_* removal, but allow utm_term on example.com
        val c = cleaner(
            "\$removeparam=/^utm_/",
            "@@||example.com^\$removeparam=utm_term",
        )
        assertEquals(
            "https://example.com/?utm_term=keep",
            c.clean("https://example.com/?utm_source=a&utm_term=keep"),
        )
        // other host: utm_term should still go
        assertEquals(
            "https://other.com/",
            c.clean("https://other.com/?utm_source=a&utm_term=k"),
        )
    }

    @Test
    fun fullExceptionBypassesAll() {
        val c = cleaner(
            "\$removeparam=fbclid",
            "@@||trusted.com^\$removeparam",
        )
        val url = "https://trusted.com/?fbclid=x&a=1"
        assertEquals(url, c.clean(url))
    }

    @Test
    fun regexCaseInsensitiveFlag() {
        val c = cleaner("\$removeparam=/^MC_/i")
        assertEquals(
            "https://example.com/?keep=1",
            c.clean("https://example.com/?mc_eid=a&MC_cid=b&keep=1"),
        )
    }

    @Test
    fun pathPatternConstraint() {
        val c = cleaner("||ad.example.com/clk\$removeparam=/^x_/")
        assertEquals(
            "https://ad.example.com/clk?a=1",
            c.clean("https://ad.example.com/clk?x_id=a&a=1"),
        )
        // same host, different path → rule shouldn't apply
        val other = "https://ad.example.com/other?x_id=a&a=1"
        assertEquals(other, c.clean(other))
    }

    @Test
    fun ignoresCommentsAndBlankLines() {
        val c = cleaner("", "! a comment", "[Adblock Plus]", "\$removeparam=fbclid")
        assertEquals(
            "https://example.com/?a=1",
            c.clean("https://example.com/?fbclid=x&a=1"),
        )
    }

    @Test
    fun ignoresUnsupportedModifiers() {
        // resource-type constraints can't be evaluated for plain link clicks → drop the rule
        val c = cleaner("||example.com^\$xmlhttprequest,removeparam=foo")
        val url = "https://example.com/?foo=1"
        assertEquals(url, c.clean(url))
    }

    @Test
    fun invertedRemoveparamKeepsOnlyMatching() {
        val c = cleaner("||example.com^\$removeparam=~keep")
        assertEquals(
            "https://example.com/?keep=2",
            c.clean("https://example.com/?drop=1&keep=2&also=3"),
        )
    }

    @Test
    fun invertedRemoveparamRegex() {
        val c = cleaner("||example.com^\$removeparam=~/^session/")
        assertEquals(
            "https://example.com/?session_id=abc&session_token=def",
            c.clean("https://example.com/?session_id=abc&drop=1&session_token=def&utm_source=x"),
        )
    }

    @Test
    fun tldWildcardMatchesAllAmazonDomains() {
        val c = cleaner("||amazon.*^\$removeparam=ref_")
        assertEquals(
            "https://amazon.com/dp/X",
            c.clean("https://amazon.com/dp/X?ref_=a"),
        )
        assertEquals(
            "https://amazon.co.uk/dp/X",
            c.clean("https://amazon.co.uk/dp/X?ref_=a"),
        )
        assertEquals(
            "https://www.amazon.de/dp/X",
            c.clean("https://www.amazon.de/dp/X?ref_=a"),
        )
    }

    @Test
    fun tldWildcardDoesNotMatchUnrelatedHost() {
        val c = cleaner("||amazon.*^\$removeparam=ref_")
        val url = "https://example.com/?ref_=a"
        assertEquals(url, c.clean(url))
    }

    @Test
    fun preservesUserinfoAndPort() {
        val c = cleaner("\$removeparam=t")
        assertEquals(
            "https://u:p@example.com:8080/x?a=1",
            c.clean("https://u:p@example.com:8080/x?t=tracking&a=1"),
        )
    }

    @Test
    fun valuelessParamsHandled() {
        val c = cleaner("\$removeparam=flag")
        assertEquals(
            "https://example.com/?a=1",
            c.clean("https://example.com/?flag&a=1"),
        )
    }

    @Test
    fun rejectsNonHttpGarbage() {
        val c = cleaner("\$removeparam=utm_source")
        assertEquals("not a url", c.clean("not a url"))
    }

    @Test
    fun acceptsDocumentResourceType() {
        val c = cleaner("||example.com^\$document,removeparam=track")
        assertEquals(
            "https://example.com/?a=1",
            c.clean("https://example.com/?track=x&a=1"),
        )
    }

    @Test
    fun acceptsThirdPartyModifier() {
        val c = cleaner("||example.com^\$third-party,removeparam=track")
        assertEquals(
            "https://example.com/?a=1",
            c.clean("https://example.com/?track=x&a=1"),
        )
    }

    @Test
    fun acceptsImportantModifier() {
        val c = cleaner("||example.com^\$important,removeparam=track")
        assertEquals(
            "https://example.com/?a=1",
            c.clean("https://example.com/?track=x&a=1"),
        )
    }

    @Test
    fun acceptsNegatedResourceType() {
        // ~image: "everything except images" — a top-level link click qualifies
        val c = cleaner("||example.com^\$~image,removeparam=track")
        assertEquals(
            "https://example.com/?a=1",
            c.clean("https://example.com/?track=x&a=1"),
        )
    }

    @Test
    fun rejectsAppModifier() {
        val c = cleaner("||example.com^\$app=msedgewebview2.exe,removeparam=track")
        val url = "https://example.com/?track=x&a=1"
        assertEquals(url, c.clean(url))
    }

    @Test
    fun rejectsFirstPartyModifier() {
        val c = cleaner("||example.com^\$first-party,removeparam=track")
        val url = "https://example.com/?track=x&a=1"
        assertEquals(url, c.clean(url))
    }

    @Test
    fun rejectsReplaceModifier() {
        val c = cleaner("||example.com^\$replace=/foo/bar/,removeparam=track")
        val url = "https://example.com/?track=x&a=1"
        assertEquals(url, c.clean(url))
    }

    @Test
    fun fullUrlRegexPattern() {
        val c = cleaner("/^https?:\\/\\/ads\\.example\\.com\\/clk/\$removeparam=/^x_/")
        assertEquals(
            "https://ads.example.com/clk?a=1",
            c.clean("https://ads.example.com/clk?x_id=a&a=1"),
        )
        // doesn't apply on non-matching URL
        val other = "https://example.com/clk?x_id=a"
        assertEquals(other, c.clean(other))
    }

    // The user's real-world allowlist examples: globalRules strip utm_*, but specific
    // sites need certain params preserved.
    @Test
    fun allowlistGlavnoeLifeUtmCampaign() {
        val c = cleaner(
            "\$removeparam=/^utm_/",
            "@@||glavnoe.life^\$removeparam=utm_campaign",
        )
        assertEquals(
            "https://glavnoe.life/post?utm_campaign=keep",
            c.clean("https://glavnoe.life/post?utm_source=drop&utm_campaign=keep&utm_medium=drop"),
        )
    }

    @Test
    fun allowlistLifehackerEridParam() {
        val c = cleaner(
            "\$removeparam=erid",
            "@@||lifehacker.ru^\$removeparam=erid",
        )
        // on lifehacker.ru — keep erid
        assertEquals(
            "https://lifehacker.ru/article?erid=keep",
            c.clean("https://lifehacker.ru/article?erid=keep"),
        )
        // on other sites — strip erid
        assertEquals(
            "https://example.com/",
            c.clean("https://example.com/?erid=drop"),
        )
    }

    @Test
    fun allowlistSubdomainScoped() {
        // exception applies on any subdomain of lifehacker.ru
        val c = cleaner(
            "\$removeparam=erid",
            "@@||lifehacker.ru^\$removeparam=erid",
        )
        assertEquals(
            "https://www.lifehacker.ru/?erid=keep",
            c.clean("https://www.lifehacker.ru/?erid=keep"),
        )
    }
}
