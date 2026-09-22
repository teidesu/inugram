package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.api.PluginLocalStorage
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.After
import org.junit.Before
import org.junit.Test

/** `localStorage` on a real engine: the store and its quota are rust's (`local_storage_tests.rs`), where it lives is this side's */
class PluginLocalStorageTest {
    private val install = freshInstallId()
    private val other = freshInstallId()
    private val engines = ArrayList<Plugin>()

    @Before
    fun setUp() = resetBridge()

    @After
    fun tearDown() {
        engines.forEach(::closeEngine)
        PluginLocalStorage.wipe(install)
        PluginLocalStorage.wipe(other)
    }

    private fun startStorage(installId: String): Plugin =
        startEngine("storage", localStoragePath = PluginLocalStorage.pathFor(installId)).also { engines.add(it) }

    private fun Plugin.js(code: String): String = engine!!.evaluate(code) ?: "null"

    @Test
    fun a_store_outlives_its_engine_and_is_its_install_s_alone() {
        val first = startStorage(install)
        first.js("localStorage.setItem('k', 'mine')")
        closeEngine(first)

        assertEquals("null", startStorage(other).js("localStorage.getItem('k')"))
        assertEquals("mine", startStorage(install).js("localStorage.getItem('k')"))
    }

    @Test
    fun a_wiped_install_starts_empty() {
        val first = startStorage(install)
        first.js("localStorage.setItem('k', 'v')")
        closeEngine(first)
        PluginLocalStorage.wipe(install)

        assertEquals("null", startStorage(install).js("localStorage.getItem('k')"))
    }

    @Test
    fun an_id_that_is_not_one_never_becomes_a_path() {
        val mixed = "0123456789abcdef0123456789abcdef"
        for (id in listOf("../../etc", "", "not-hex-at-all-not-hex-at-all-xx", mixed.uppercase(), install + "0")) {
            assertFailsWith<IllegalArgumentException>(id) { PluginLocalStorage.pathFor(id) }
        }
    }
}
