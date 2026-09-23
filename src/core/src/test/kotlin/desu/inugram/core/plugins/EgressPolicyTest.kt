package desu.inugram.core.plugins

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/** That `PluginFetch` asks per hop is the transport's own property and is a device test. */
class EgressPolicyTest {
    private fun grants(vararg tokens: String) = PluginPermissions.parse(tokens.toList())

    private fun readErrorCode(wire: String?): String? {
        val decoded = PluginWire.decode(wire ?: return null)
        return (decoded as PluginWire.Value.PluginErr).code
    }

    @Test
    fun the_host_is_the_one_the_request_connects_to_never_the_one_it_reads_as() {
        assertEquals("example.com", EgressPolicy.parseHost("https://example.com/x"))
        assertEquals("example.com", EgressPolicy.parseHost("https://EXAMPLE.com.:8443/x?q=1"))
        assertEquals("::1", EgressPolicy.parseHost("http://[::1]:8080/x"))
        assertNull(EgressPolicy.parseHost("https://allowed.com@127.0.0.1/x"), "userinfo hides the real host")
        assertNull(EgressPolicy.parseHost("file:///etc/hosts"))
        assertNull(EgressPolicy.parseHost("content://media/external/1"))
        assertNull(EgressPolicy.parseHost("ftp://example.com/x"))
        assertNull(EgressPolicy.parseHost("not a url"))
    }

    @Test
    fun a_host_the_grant_does_not_cover_is_refused() {
        assertEquals("not-granted", readErrorCode(EgressPolicy.screenHop(grants("fetch(example.com)"), "https://evil.com/x")))
    }

    @Test
    fun a_granted_host_passes() {
        assertNull(EgressPolicy.screenHop(grants("fetch(example.com)"), "https://api.example.com/x"))
    }
}
