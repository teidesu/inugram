package desu.inugram.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginPermissionsTest {
    @Test
    fun parses_unscoped_grant() {
        assertEquals(Grant("openUrl", emptyList()), PluginPermissions.parseGrant("openUrl"))
    }

    @Test
    fun parses_scoped_grant() {
        assertEquals(
            Grant("interceptRpc", listOf("users.getUsers", "channels.getChannels")),
            PluginPermissions.parseGrant("interceptRpc(users.getUsers,channels.getChannels)"),
        )
    }

    @Test
    fun parse_grant_trims_whitespace_inside_parens() {
        assertEquals(
            Grant("fetch", listOf("google.com", "bing.com")),
            PluginPermissions.parseGrant("fetch( google.com , bing.com )"),
        )
    }

    @Test
    fun parse_grant_refuses_an_unclosed_scope_list_rather_than_widening_it() {
        // the widening this replaces was fail-OPEN: `fetch(evil.com` used to parse as unscoped
        // `fetch`, i.e. every domain
        assertNull(PluginPermissions.parseGrant("fetch(evil.com"))
        assertTrue(PluginPermissions.isMalformed("fetch(evil.com"))
        assertNull(PluginPermissions.parseGrant("invokeRpc(messages.sendMessage)x"))
        assertTrue(PluginPermissions.isMalformed("invokeRpc(messages.sendMessage)x"))
    }

    @Test
    fun a_malformed_token_grants_nothing_at_the_gate() {
        val permissions = PluginPermissions.parse(listOf("fetch(evil.com"))
        assertFalse(permissions.has("fetch"))
        assertFalse(permissions.allows("fetch", "evil.com", ScopeMatch.DOMAIN))
    }

    @Test
    fun well_formed_tokens_are_not_malformed() {
        assertFalse(PluginPermissions.isMalformed("openUrl"))
        assertFalse(PluginPermissions.isMalformed("fetch(a.com,b.com)"))
        assertFalse(PluginPermissions.isMalformed("   "))
    }

    @Test
    fun parse_grant_blank_is_null() {
        assertNull(PluginPermissions.parseGrant("   "))
    }

    @Test
    fun written_but_empty_scope_list_is_refused_rather_than_widened() {
        // it used to parse as unscoped, so `invokeRpc()` read like "no methods" and meant "every
        // method", sailing past the takeover screen that rejects `invokeRpc(auth.exportLoginToken)`
        for (token in listOf("fetch()", "fetch( )", "account.read(,)", "invokeRpc()")) {
            assertNull(token, PluginPermissions.parseGrant(token))
            assertTrue(token, PluginPermissions.isMalformed(token))
        }
        val permissions = PluginPermissions.parse(listOf("account.read()"))
        assertFalse(permissions.allows("account.read", "messages", ScopeMatch.EXACT))
    }

    @Test
    fun has_reflects_any_grant() {
        val p = PluginPermissions.parse(listOf("openUrl", "fetch(google.com)"))
        assertTrue(p.has("openUrl"))
        assertTrue(p.has("fetch"))
        assertFalse(p.has("clipboard.read"))
        assertEquals(setOf("openUrl", "fetch"), p.grantedApis)
    }

    @Test
    fun unscoped_grant_allows_any_target() {
        val p = PluginPermissions.parse(listOf("interceptRpc"))
        assertTrue(p.allows("interceptRpc", "users.getUsers", ScopeMatch.EXACT))
        assertTrue(p.allows("interceptRpc", "anything.at.all", ScopeMatch.EXACT))
    }

    @Test
    fun scoped_grant_allows_only_listed() {
        val p = PluginPermissions.parse(listOf("interceptRpc(users.getUsers,channels.getChannels)"))
        assertTrue(p.allows("interceptRpc", "users.getUsers", ScopeMatch.EXACT))
        assertTrue(p.allows("interceptRpc", "channels.getChannels", ScopeMatch.EXACT))
        assertFalse(p.allows("interceptRpc", "messages.getHistory", ScopeMatch.EXACT))
    }

    @Test
    fun ungranted_api_is_never_allowed() {
        val p = PluginPermissions.parse(listOf("openUrl"))
        assertFalse(p.allows("interceptRpc", "users.getUsers", ScopeMatch.EXACT))
    }

    @Test
    fun unscoped_union_wins_over_scoped() {
        val p = PluginPermissions.parse(listOf("fetch(google.com)", "fetch"))
        assertTrue(p.allows("fetch", "example.com", ScopeMatch.DOMAIN))
    }

    @Test
    fun domain_matches_subdomains_but_not_lookalikes() {
        val p = PluginPermissions.parse(listOf("fetch(google.com)"))
        assertTrue(p.allows("fetch", "google.com", ScopeMatch.DOMAIN))
        assertTrue(p.allows("fetch", "api.google.com", ScopeMatch.DOMAIN))
        assertFalse(p.allows("fetch", "evilgoogle.com", ScopeMatch.DOMAIN))
        assertFalse(p.allows("fetch", "google.com.evil.com", ScopeMatch.DOMAIN))
        // dns is case-insensitive and the caller lowercases, so a scope written in any case matches
        assertTrue(PluginPermissions.parse(listOf("fetch(Google.COM)")).allows("fetch", "api.google.com", ScopeMatch.DOMAIN))
    }

    @Test
    fun namespace_wildcard_matches_prefix() {
        val p = PluginPermissions.parse(listOf("jvm.cls(java.util.*,java.lang.Object)"))
        assertTrue(p.allows("jvm.cls", "java.util.List", ScopeMatch.NAMESPACE))
        assertTrue(p.allows("jvm.cls", "java.util.concurrent.Executor", ScopeMatch.NAMESPACE))
        assertTrue(p.allows("jvm.cls", "java.lang.Object", ScopeMatch.NAMESPACE))
        assertFalse(p.allows("jvm.cls", "java.lang.String", ScopeMatch.NAMESPACE))
        assertFalse(p.allows("jvm.cls", "java.utility.Foo", ScopeMatch.NAMESPACE))
    }

    @Test
    fun star_wildcard_matches_everything() {
        val p = PluginPermissions.parse(listOf("jvm.cls(*)"))
        assertTrue(p.allows("jvm.cls", "any.Class", ScopeMatch.NAMESPACE))
    }

    @Test
    fun the_engine_is_handed_one_pair_per_scope_and_an_empty_scope_for_an_unscoped_grant() {
        val permissions = PluginPermissions.parse(listOf("openUrl", "fetch( a.com , b.com )", "fetch(evil.com", "x()"))
        assertEquals(listOf("openUrl", "", "fetch", "a.com", "fetch", "b.com"), permissions.toPairs())
    }
}
