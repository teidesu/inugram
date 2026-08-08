package desu.inugram.core.plugins

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The two egress rules, which only exist here: the engine checks the first url's grant and nothing
 * else, so a redirect that is screened once and an address that is checked as a literal are both
 * failures nothing else in this codebase can see.
 *
 * That `PluginFetch` asks per hop is the transport's own property and is a device test.
 */
class EgressPolicyTest {
    private fun grants(vararg tokens: String) = PluginPermissions.parse(tokens.toList())

    private fun v4(a: Int, b: Int, c: Int, d: Int) =
        byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    /** `1234:5678::` style, written as the 16 bytes the resolver hands over */
    private fun v6(vararg words: Int): ByteArray {
        val out = ByteArray(16)
        for (i in words.indices) {
            out[i * 2] = (words[i] shr 8).toByte()
            out[i * 2 + 1] = words[i].toByte()
        }
        return out
    }

    private fun answers(vararg addresses: ByteArray): (String) -> List<ByteArray> = { addresses.toList() }

    private val public4 = v4(93, 184, 216, 34)

    private fun codeOf(wire: String?): String? {
        val decoded = PluginWire.decode(wire ?: return null)
        return (decoded as PluginWire.Value.PluginErr).code
    }

    @Test
    fun `every private loopback and link-local v4 range is refused`() {
        for (address in listOf(
            v4(127, 0, 0, 1),
            v4(127, 255, 255, 254),
            v4(0, 0, 0, 0),
            v4(10, 0, 0, 5),
            v4(172, 16, 0, 1),
            v4(172, 31, 255, 255),
            v4(192, 168, 1, 1),
            v4(169, 254, 169, 254),
            v4(100, 64, 0, 1),
            v4(192, 0, 0, 1),
            v4(198, 18, 0, 1),
            v4(224, 0, 0, 1),
            v4(255, 255, 255, 255),
        )) {
            assertTrue(EgressPolicy.isBlockedAddress(address), address.joinToString("."))
        }
    }

    @Test
    fun `an ordinary public address is not refused`() {
        for (address in listOf(public4, v4(8, 8, 8, 8), v4(1, 1, 1, 1), v4(172, 32, 0, 1), v4(100, 63, 0, 1))) {
            assertFalse(EgressPolicy.isBlockedAddress(address), address.joinToString("."))
        }
    }

    @Test
    fun `the v6 ranges are refused too`() {
        assertTrue(EgressPolicy.isBlockedAddress(v6(0, 0, 0, 0, 0, 0, 0, 1)), "::1")
        assertTrue(EgressPolicy.isBlockedAddress(v6(0, 0, 0, 0, 0, 0, 0, 0)), "::")
        assertTrue(EgressPolicy.isBlockedAddress(v6(0xfd00, 0, 0, 0, 0, 0, 0, 1)), "unique local")
        assertTrue(EgressPolicy.isBlockedAddress(v6(0xfe80, 0, 0, 0, 0, 0, 0, 1)), "link-local")
        assertTrue(EgressPolicy.isBlockedAddress(v6(0xff02, 0, 0, 0, 0, 0, 0, 1)), "multicast")
        assertFalse(EgressPolicy.isBlockedAddress(v6(0x2606, 0x4700, 0, 0, 0, 0, 0, 1)), "a public v6 address")
    }

    /** all three shapes reach the v4 address they carry, so all three are checked as one */
    @Test
    fun `a v4 address wrapped in a v6 one is unwrapped before it is judged`() {
        val mapped = v6(0, 0, 0, 0, 0, 0xffff, 0x7f00, 0x0001)
        assertTrue(EgressPolicy.isBlockedAddress(mapped), "::ffff:127.0.0.1")
        val mappedPublic = v6(0, 0, 0, 0, 0, 0xffff, 0x5db8, 0xd822)
        assertFalse(EgressPolicy.isBlockedAddress(mappedPublic), "::ffff:93.184.216.34")

        val nat64 = v6(0x0064, 0xff9b, 0, 0, 0, 0, 0xa9fe, 0xa9fe)
        assertTrue(EgressPolicy.isBlockedAddress(nat64), "64:ff9b::169.254.169.254")

        val compatible = v6(0, 0, 0, 0, 0, 0, 0x0a00, 0x0001)
        assertTrue(EgressPolicy.isBlockedAddress(compatible), "::10.0.0.1")
    }

    @Test
    fun `an address of a shape this does not know is refused rather than allowed`() {
        assertTrue(EgressPolicy.isBlockedAddress(ByteArray(0)))
        assertTrue(EgressPolicy.isBlockedAddress(ByteArray(6)))
    }

    @Test
    fun `the host is the one the request connects to, never the one it reads as`() {
        assertEquals("example.com", EgressPolicy.hostOf("https://example.com/x"))
        assertEquals("example.com", EgressPolicy.hostOf("https://EXAMPLE.com.:8443/x?q=1"))
        assertEquals("::1", EgressPolicy.hostOf("http://[::1]:8080/x"))
        assertNull(EgressPolicy.hostOf("https://allowed.com@127.0.0.1/x"), "userinfo hides the real host")
        assertNull(EgressPolicy.hostOf("file:///etc/hosts"))
        assertNull(EgressPolicy.hostOf("content://media/external/1"))
        assertNull(EgressPolicy.hostOf("ftp://example.com/x"))
        assertNull(EgressPolicy.hostOf("not a url"))
    }

    @Test
    fun `a host the grant does not cover is refused before it is resolved`() {
        var resolved = false
        val wire = EgressPolicy.screenHop(grants("fetch(example.com)"), "https://evil.com/x") {
            resolved = true
            listOf(public4)
        }
        assertEquals("not-granted", codeOf(wire))
        assertFalse(resolved, "a refused host must not even be looked up")
    }

    @Test
    fun `a granted host that resolves into a private range is refused`() {
        val wire = EgressPolicy.screenHop(grants("fetch"), "https://localtest.me/x", answers(v4(127, 0, 0, 1)))
        assertEquals("forbidden", codeOf(wire))
    }

    /**
     * the resolver picks per connection, so a name with one private answer among its public ones is
     * not a name to connect to: taking "the first address" would make the refusal a coin flip
     */
    @Test
    fun `one private answer among several refuses the whole name`() {
        val wire = EgressPolicy.screenHop(grants("fetch"), "https://mixed.example/x", answers(public4, v4(10, 0, 0, 1)))
        assertEquals("forbidden", codeOf(wire))
    }

    @Test
    fun `a granted public host passes`() {
        assertNull(EgressPolicy.screenHop(grants("fetch(example.com)"), "https://api.example.com/x", answers(public4)))
    }

    @Test
    fun `a name that does not resolve fails rather than being sent`() {
        val wire = EgressPolicy.screenHop(grants("fetch"), "https://nx.example/x") { emptyList() }
        assertEquals("network", codeOf(wire))
        val threw = EgressPolicy.screenHop(grants("fetch"), "https://nx.example/x") { throw java.net.UnknownHostException() }
        assertEquals("network", codeOf(threw))
    }
}
