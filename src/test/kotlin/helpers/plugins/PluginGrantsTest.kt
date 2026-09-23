package desu.inugram.helpers.plugins

import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class PluginGrantsTest {
    @Before
    fun setUp() = resetBridge()

    private fun Plugin.rejection(call: String): String {
        js("globalThis.outcome = null; ($call).then(() => { globalThis.outcome = 'ok' }, (e) => { globalThis.outcome = e.code + '|' + (e.grant ?? '') })")
        settle()
        return js("String(globalThis.outcome)")
    }

    @Test
    fun a_scoped_grant_crosses_trimmed_and_domain_matched_and_a_malformed_one_grants_nothing() {
        val plugin = startEngine("grants", "fetch( Example.com , other.org )", "fetch(evil.com")
        // refused by the body after passing the grant, so nothing reaches a socket
        assertEquals("invalid-argument|", plugin.rejection("fetch('https://api.example.com/x', { method: 'POST', body: { a: 1 } })"))
        assertEquals("invalid-argument|", plugin.rejection("fetch('https://other.org/x', { method: 'POST', body: { a: 1 } })"))
        assertEquals("not-granted|fetch(evil.com)", plugin.rejection("fetch('https://evil.com/x')"))
    }
}
