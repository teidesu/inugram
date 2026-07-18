package desu.inugram.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginPermissionsTest {
    @Test
    fun parsesUnscopedGrant() {
        assertEquals(Grant("inu.kv", emptyList()), PluginPermissions.parseGrant("inu.kv"))
    }

    @Test
    fun parsesScopedGrant() {
        assertEquals(
            Grant("inu.interceptRpc", listOf("users.getUsers", "channels.getChannels")),
            PluginPermissions.parseGrant("inu.interceptRpc(users.getUsers,channels.getChannels)"),
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
    fun parseGrantBlankIsNull() {
        assertNull(PluginPermissions.parseGrant("   "))
    }

    @Test
    fun emptyParensIsUnscoped() {
        assertEquals(Grant("fetch", emptyList()), PluginPermissions.parseGrant("fetch()"))
    }

    @Test
    fun hasReflectsAnyGrant() {
        val p = PluginPermissions.parse(listOf("inu.kv", "fetch(google.com)"))
        assertTrue(p.has("inu.kv"))
        assertTrue(p.has("fetch"))
        assertFalse(p.has("inu.clipboard.read"))
        assertEquals(setOf("inu.kv", "fetch"), p.grantedApis)
    }

    @Test
    fun unscopedGrantAllowsAnyTarget() {
        val p = PluginPermissions.parse(listOf("inu.interceptRpc"))
        assertTrue(p.allows("inu.interceptRpc", "users.getUsers", ScopeMatch.EXACT))
        assertTrue(p.allows("inu.interceptRpc", "anything.at.all", ScopeMatch.EXACT))
    }

    @Test
    fun scopedGrantAllowsOnlyListed() {
        val p = PluginPermissions.parse(listOf("inu.interceptRpc(users.getUsers,channels.getChannels)"))
        assertTrue(p.allows("inu.interceptRpc", "users.getUsers", ScopeMatch.EXACT))
        assertTrue(p.allows("inu.interceptRpc", "channels.getChannels", ScopeMatch.EXACT))
        assertFalse(p.allows("inu.interceptRpc", "messages.getHistory", ScopeMatch.EXACT))
    }

    @Test
    fun ungrantedApiIsNeverAllowed() {
        val p = PluginPermissions.parse(listOf("inu.kv"))
        assertFalse(p.allows("inu.interceptRpc", "users.getUsers", ScopeMatch.EXACT))
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
    }

    @Test
    fun namespaceWildcardMatchesPrefix() {
        val p = PluginPermissions.parse(listOf("inu.jvm.cls(java.util.*,java.lang.Object)"))
        assertTrue(p.allows("inu.jvm.cls", "java.util.List", ScopeMatch.NAMESPACE))
        assertTrue(p.allows("inu.jvm.cls", "java.util.concurrent.Executor", ScopeMatch.NAMESPACE))
        assertTrue(p.allows("inu.jvm.cls", "java.lang.Object", ScopeMatch.NAMESPACE))
        assertFalse(p.allows("inu.jvm.cls", "java.lang.String", ScopeMatch.NAMESPACE))
        assertFalse(p.allows("inu.jvm.cls", "java.utility.Foo", ScopeMatch.NAMESPACE))
    }

    @Test
    fun starWildcardMatchesEverything() {
        val p = PluginPermissions.parse(listOf("inu.jvm.cls(*)"))
        assertTrue(p.allows("inu.jvm.cls", "any.Class", ScopeMatch.NAMESPACE))
    }
}
