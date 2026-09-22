package desu.inugram.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import desu.inugram.sources.forkRoot
import java.io.File
import org.junit.Test

class PluginPermissionsTest {
    @Test
    fun parsesUnscopedGrant() {
        assertEquals(Grant("openUrl", emptyList()), PluginPermissions.parseGrant("openUrl"))
    }

    @Test
    fun parsesScopedGrant() {
        assertEquals(
            Grant("interceptRpc", listOf("users.getUsers", "channels.getChannels")),
            PluginPermissions.parseGrant("interceptRpc(users.getUsers,channels.getChannels)"),
        )
    }

    @Test
    fun parseGrantTrimsWhitespaceInsideParens() {
        assertEquals(
            Grant("fetch", listOf("google.com", "bing.com")),
            PluginPermissions.parseGrant("fetch( google.com , bing.com )"),
        )
    }

    @Test
    fun parseGrantRefusesAnUnclosedScopeListRatherThanWideningIt() {
        // the widening this replaces was fail-OPEN: `fetch(evil.com` used to parse as unscoped
        // `fetch`, i.e. every domain
        assertNull(PluginPermissions.parseGrant("fetch(evil.com"))
        assertTrue(PluginPermissions.isMalformed("fetch(evil.com"))
        assertNull(PluginPermissions.parseGrant("invokeRpc(messages.sendMessage)x"))
        assertTrue(PluginPermissions.isMalformed("invokeRpc(messages.sendMessage)x"))
    }

    @Test
    fun aMalformedTokenGrantsNothingAtTheGate() {
        val permissions = PluginPermissions.parse(listOf("fetch(evil.com"))
        assertFalse(permissions.has("fetch"))
        assertFalse(permissions.allows("fetch", "evil.com", ScopeMatch.DOMAIN))
    }

    @Test
    fun wellFormedTokensAreNotMalformed() {
        assertFalse(PluginPermissions.isMalformed("openUrl"))
        assertFalse(PluginPermissions.isMalformed("fetch(a.com,b.com)"))
        assertFalse(PluginPermissions.isMalformed("   "))
    }

    @Test
    fun parseGrantBlankIsNull() {
        assertNull(PluginPermissions.parseGrant("   "))
    }

    @Test
    fun writtenButEmptyScopeListIsRefusedRatherThanWidened() {
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
    fun hasReflectsAnyGrant() {
        val p = PluginPermissions.parse(listOf("openUrl", "fetch(google.com)"))
        assertTrue(p.has("openUrl"))
        assertTrue(p.has("fetch"))
        assertFalse(p.has("clipboard.read"))
        assertEquals(setOf("openUrl", "fetch"), p.grantedApis)
    }

    @Test
    fun unscopedGrantAllowsAnyTarget() {
        val p = PluginPermissions.parse(listOf("interceptRpc"))
        assertTrue(p.allows("interceptRpc", "users.getUsers", ScopeMatch.EXACT))
        assertTrue(p.allows("interceptRpc", "anything.at.all", ScopeMatch.EXACT))
    }

    @Test
    fun scopedGrantAllowsOnlyListed() {
        val p = PluginPermissions.parse(listOf("interceptRpc(users.getUsers,channels.getChannels)"))
        assertTrue(p.allows("interceptRpc", "users.getUsers", ScopeMatch.EXACT))
        assertTrue(p.allows("interceptRpc", "channels.getChannels", ScopeMatch.EXACT))
        assertFalse(p.allows("interceptRpc", "messages.getHistory", ScopeMatch.EXACT))
    }

    @Test
    fun ungrantedApiIsNeverAllowed() {
        val p = PluginPermissions.parse(listOf("openUrl"))
        assertFalse(p.allows("interceptRpc", "users.getUsers", ScopeMatch.EXACT))
    }

    @Test
    fun unscopedUnionWinsOverScoped() {
        val p = PluginPermissions.parse(listOf("fetch(google.com)", "fetch"))
        assertTrue(p.allows("fetch", "example.com", ScopeMatch.DOMAIN))
    }

    @Test
    fun domainMatchesSubdomainsButNotLookalikes() {
        val p = PluginPermissions.parse(listOf("fetch(google.com)"))
        assertTrue(p.allows("fetch", "google.com", ScopeMatch.DOMAIN))
        assertTrue(p.allows("fetch", "api.google.com", ScopeMatch.DOMAIN))
        assertFalse(p.allows("fetch", "evilgoogle.com", ScopeMatch.DOMAIN))
        assertFalse(p.allows("fetch", "google.com.evil.com", ScopeMatch.DOMAIN))
        // dns is case-insensitive and the caller lowercases, so a scope written in any case matches
        assertTrue(PluginPermissions.parse(listOf("fetch(Google.COM)")).allows("fetch", "api.google.com", ScopeMatch.DOMAIN))
    }

    @Test
    fun namespaceWildcardMatchesPrefix() {
        val p = PluginPermissions.parse(listOf("jvm.cls(java.util.*,java.lang.Object)"))
        assertTrue(p.allows("jvm.cls", "java.util.List", ScopeMatch.NAMESPACE))
        assertTrue(p.allows("jvm.cls", "java.util.concurrent.Executor", ScopeMatch.NAMESPACE))
        assertTrue(p.allows("jvm.cls", "java.lang.Object", ScopeMatch.NAMESPACE))
        assertFalse(p.allows("jvm.cls", "java.lang.String", ScopeMatch.NAMESPACE))
        assertFalse(p.allows("jvm.cls", "java.utility.Foo", ScopeMatch.NAMESPACE))
    }

    @Test
    fun starWildcardMatchesEverything() {
        val p = PluginPermissions.parse(listOf("jvm.cls(*)"))
        assertTrue(p.allows("jvm.cls", "any.Class", ScopeMatch.NAMESPACE))
    }

    @Test
    fun theEngineIsHandedOnePairPerScopeAndAnEmptyScopeForAnUnscopedGrant() {
        val permissions = PluginPermissions.parse(listOf("openUrl", "fetch( a.com , b.com )", "fetch(evil.com", "x()"))
        assertEquals(listOf("openUrl", "", "fetch", "a.com", "fetch", "b.com"), permissions.toPairs())
    }

    /** the same table rust's `grants_tests` reads, so the two matchers cannot drift apart silently */
    @Test
    fun scopeMatchingAgreesWithTheEnginesTable() {
        val rows = File(forkRoot(), "src/test/grants/scope-matches.tsv").readLines()
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue(rows.size > 10)
        for (row in rows) {
            val (scope, target, mode, granted) = row.split('\t')
            val match = ScopeMatch.valueOf(mode.uppercase())
            assertEquals(row, granted == "true", PluginPermissions.parse(listOf("x($scope)")).allows("x", target, match))
        }
    }
}
