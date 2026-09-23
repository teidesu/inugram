package desu.inugram.core.urlcleaner

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlCleanerTest {

    private fun cleaner(vararg lines: String) =
        UrlCleaner.fromAdGuardFilter(lines.joinToString("\n"))

    @Test
    fun strips_global_utm() {
        val c = cleaner("\$removeparam=/^utm_/")
        assertEquals(
            "https://example.com/x?keep=1",
            c.clean("https://example.com/x?utm_source=a&keep=1&utm_medium=b"),
        )
    }

    @Test
    fun strips_literal_global() {
        val c = cleaner("\$removeparam=fbclid")
        assertEquals(
            "https://example.com/?a=1",
            c.clean("https://example.com/?fbclid=xyz&a=1"),
        )
    }

    @Test
    fun untouched_when_no_match() {
        val c = cleaner("\$removeparam=fbclid")
        val url = "https://example.com/?a=1&b=2"
        assertEquals(url, c.clean(url))
    }

    @Test
    fun untouched_when_no_query() {
        val c = cleaner("\$removeparam=/^utm_/")
        assertEquals("https://example.com/path", c.clean("https://example.com/path"))
    }

    @Test
    fun preserves_fragment() {
        val c = cleaner("\$removeparam=utm_source")
        assertEquals(
            "https://example.com/#section",
            c.clean("https://example.com/?utm_source=a#section"),
        )
    }

    @Test
    fun preserves_order() {
        val c = cleaner("\$removeparam=drop")
        assertEquals(
            "https://example.com/?a=1&b=2&c=3",
            c.clean("https://example.com/?a=1&drop=x&b=2&drop=y&c=3"),
        )
    }

    @Test
    fun domain_scoped_applies_on_subdomain() {
        val c = cleaner("||example.com^\$removeparam=sid")
        assertEquals(
            "https://www.example.com/?a=1",
            c.clean("https://www.example.com/?sid=x&a=1"),
        )
    }

    @Test
    fun domain_scoped_does_not_apply_elsewhere() {
        val c = cleaner("||example.com^\$removeparam=sid")
        val url = "https://other.com/?sid=x&a=1"
        assertEquals(url, c.clean(url))
    }

    @Test
    fun domain_modifier_syntax() {
        val c = cleaner("\$removeparam=promo,domain=vjav.com|vjav.tube")
        assertEquals(
            "https://vjav.com/?a=1",
            c.clean("https://vjav.com/?promo=x&a=1"),
        )
        val unrelated = "https://other.com/?promo=x&a=1"
        assertEquals(unrelated, c.clean(unrelated))
    }

    @Test
    fun negative_domain_modifier() {
        val c = cleaner("\$removeparam=/^__s=/,domain=~safe.com")
        assertEquals(
            "https://other.com/?a=1",
            c.clean("https://other.com/?__s=trackme&a=1"),
        )
        val safe = "https://safe.com/?__s=trackme&a=1"
        assertEquals(safe, c.clean(safe))
    }

    @Test
    fun denyallow_modifier() {
        val c = cleaner("\$denyallow=video-shoper.ru|glavnoe.life,removeparam=utm_source")
        assertEquals(
            "https://open.spotify.com/album/1?si=x",
            c.clean("https://open.spotify.com/album/1?si=x&utm_source=copy-link"),
        )
        val denied = "https://glavnoe.life/news?utm_source=keep"
        assertEquals(denied, c.clean(denied))
    }

    @Test
    fun denyallow_applies_to_subdomains() {
        val c = cleaner("\$denyallow=glavnoe.life,removeparam=utm_source")
        val denied = "https://www.glavnoe.life/news?utm_source=keep"
        assertEquals(denied, c.clean(denied))
    }

    @Test
    fun denyallow_combines_with_domain_modifier() {
        val c = cleaner("\$removeparam=utm_source,domain=example.com,denyallow=safe.example.com")
        assertEquals("https://example.com/?a=1", c.clean("https://example.com/?utm_source=x&a=1"))
        val denied = "https://safe.example.com/?utm_source=keep"
        assertEquals(denied, c.clean(denied))
    }

    @Test
    fun per_param_exception() {
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
    fun full_exception_bypasses_all() {
        val c = cleaner(
            "\$removeparam=fbclid",
            "@@||trusted.com^\$removeparam",
        )
        val url = "https://trusted.com/?fbclid=x&a=1"
        assertEquals(url, c.clean(url))
    }

    @Test
    fun regex_case_insensitive_flag() {
        val c = cleaner("\$removeparam=/^MC_/i")
        assertEquals(
            "https://example.com/?keep=1",
            c.clean("https://example.com/?mc_eid=a&MC_cid=b&keep=1"),
        )
    }

    @Test
    fun path_pattern_constraint() {
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
    fun ignores_comments_and_blank_lines() {
        val c = cleaner("", "! a comment", "[Adblock Plus]", "\$removeparam=fbclid")
        assertEquals(
            "https://example.com/?a=1",
            c.clean("https://example.com/?fbclid=x&a=1"),
        )
    }

    @Test
    fun ignores_unsupported_modifiers() {
        // resource-type constraints can't be evaluated for plain link clicks → drop the rule
        val c = cleaner("||example.com^\$xmlhttprequest,removeparam=foo")
        val url = "https://example.com/?foo=1"
        assertEquals(url, c.clean(url))
    }

    @Test
    fun inverted_removeparam_keeps_only_matching() {
        val c = cleaner("||example.com^\$removeparam=~keep")
        assertEquals(
            "https://example.com/?keep=2",
            c.clean("https://example.com/?drop=1&keep=2&also=3"),
        )
    }

    @Test
    fun inverted_removeparam_regex() {
        val c = cleaner("||example.com^\$removeparam=~/^session/")
        assertEquals(
            "https://example.com/?session_id=abc&session_token=def",
            c.clean("https://example.com/?session_id=abc&drop=1&session_token=def&utm_source=x"),
        )
    }

    @Test
    fun tld_wildcard_matches_all_amazon_domains() {
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
    fun tld_wildcard_does_not_match_unrelated_host() {
        val c = cleaner("||amazon.*^\$removeparam=ref_")
        val url = "https://example.com/?ref_=a"
        assertEquals(url, c.clean(url))
    }

    @Test
    fun preserves_userinfo_and_port() {
        val c = cleaner("\$removeparam=t")
        assertEquals(
            "https://u:p@example.com:8080/x?a=1",
            c.clean("https://u:p@example.com:8080/x?t=tracking&a=1"),
        )
    }

    @Test
    fun valueless_params_handled() {
        val c = cleaner("\$removeparam=flag")
        assertEquals(
            "https://example.com/?a=1",
            c.clean("https://example.com/?flag&a=1"),
        )
    }

    @Test
    fun rejects_non_http_garbage() {
        val c = cleaner("\$removeparam=utm_source")
        assertEquals("not a url", c.clean("not a url"))
    }

    @Test
    fun accepts_document_resource_type() {
        val c = cleaner("||example.com^\$document,removeparam=track")
        assertEquals(
            "https://example.com/?a=1",
            c.clean("https://example.com/?track=x&a=1"),
        )
    }

    @Test
    fun accepts_third_party_modifier() {
        val c = cleaner("||example.com^\$third-party,removeparam=track")
        assertEquals(
            "https://example.com/?a=1",
            c.clean("https://example.com/?track=x&a=1"),
        )
    }

    @Test
    fun accepts_important_modifier() {
        val c = cleaner("||example.com^\$important,removeparam=track")
        assertEquals(
            "https://example.com/?a=1",
            c.clean("https://example.com/?track=x&a=1"),
        )
    }

    @Test
    fun accepts_negated_resource_type() {
        // ~image: "everything except images" — a top-level link click qualifies
        val c = cleaner("||example.com^\$~image,removeparam=track")
        assertEquals(
            "https://example.com/?a=1",
            c.clean("https://example.com/?track=x&a=1"),
        )
    }

    @Test
    fun rejects_app_modifier() {
        val c = cleaner("||example.com^\$app=msedgewebview2.exe,removeparam=track")
        val url = "https://example.com/?track=x&a=1"
        assertEquals(url, c.clean(url))
    }

    @Test
    fun rejects_first_party_modifier() {
        val c = cleaner("||example.com^\$first-party,removeparam=track")
        val url = "https://example.com/?track=x&a=1"
        assertEquals(url, c.clean(url))
    }

    @Test
    fun rejects_replace_modifier() {
        val c = cleaner("||example.com^\$replace=/foo/bar/,removeparam=track")
        val url = "https://example.com/?track=x&a=1"
        assertEquals(url, c.clean(url))
    }

    @Test
    fun full_url_regex_pattern() {
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
    fun allowlist_glavnoe_life_utm_campaign() {
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
    fun allowlist_lifehacker_erid_param() {
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
    fun allowlist_subdomain_scoped() {
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
