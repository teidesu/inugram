package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.api.PluginKv
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.After
import org.junit.Before
import org.junit.Test

/** `inu.kv` on a real engine: the store and its quota are rust's (`kv_tests.rs`), where it lives is this side's */
class PluginKvTest {
    private val install = freshInstallId()
    private val other = freshInstallId()
    private val engines = ArrayList<Plugin>()

    @Before
    fun setUp() = resetBridge()

    @After
    fun tearDown() {
        engines.forEach(::closeEngine)
        PluginKv.wipe(install)
        PluginKv.wipe(other)
    }

    private fun startKv(installId: String): Plugin =
        startEngine("kv", "kv", kvPath = PluginKv.pathFor(installId)).also { engines.add(it) }

    private fun Plugin.js(code: String): String = engine!!.evaluate(code) ?: "null"

    @Test
    fun a_store_outlives_its_engine_and_is_its_install_s_alone() {
        val first = startKv(install)
        first.js("inu.kv.set('k', 'mine')")
        closeEngine(first)

        assertEquals("null", startKv(other).js("inu.kv.get('k')"))
        assertEquals("mine", startKv(install).js("inu.kv.get('k')"))
    }

    @Test
    fun a_wiped_install_starts_empty() {
        val first = startKv(install)
        first.js("inu.kv.set('k', 'v')")
        closeEngine(first)
        PluginKv.wipe(install)

        assertEquals("null", startKv(install).js("inu.kv.get('k')"))
    }

    @Test
    fun an_id_that_is_not_one_never_becomes_a_path() {
        val mixed = "0123456789abcdef0123456789abcdef"
        for (id in listOf("../../etc", "", "not-hex-at-all-not-hex-at-all-xx", mixed.uppercase(), install + "0")) {
            assertFailsWith<IllegalArgumentException>(id) { PluginKv.pathFor(id) }
        }
    }
}
