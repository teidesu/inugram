package desu.inugram.helpers.plugins

import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/** a manifest's grants as the engine receives them over jni: parsed once host-side, matched natively */
class PluginGrantsTest {
    private val plugins = ArrayList<Plugin>()

    @Before
    fun setUp() = resetBridge()

    @After
    fun tearDown() {
        plugins.forEach(::closeEngine)
        plugins.clear()
    }

    private fun Plugin.rejection(call: String): String {
        js("globalThis.outcome = null; ($call).then(() => { globalThis.outcome = 'ok' }, (e) => { globalThis.outcome = e.code + '|' + (e.grant ?? '') })")
        settle()
        return js("String(globalThis.outcome)")
    }

    @Test
    fun a_scoped_grant_crosses_trimmed_and_domain_matched_and_a_malformed_one_grants_nothing() {
        val plugin = startEngine("grants", "fetch( Example.com , other.org )", "fetch(evil.com").also { plugins.add(it) }
        // past the grant, stopped by the body: nothing reaches a socket either way
        assertEquals("invalid-argument|", plugin.rejection("fetch('https://api.example.com/x', { method: 'POST', body: { a: 1 } })"))
        assertEquals("invalid-argument|", plugin.rejection("fetch('https://other.org/x', { method: 'POST', body: { a: 1 } })"))
        assertEquals("not-granted|fetch(evil.com)", plugin.rejection("fetch('https://evil.com/x')"))
    }
}
