package desu.inugram.helpers.plugins

import android.util.Log
import desu.inugram.helpers.font.FontLibrary
import java.io.File
import org.json.JSONArray
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PluginCanvasFontsTest {
    @Before
    fun setUp() = resetBridge()

    private fun engineFor(name: String = "canvas-fonts"): Plugin =
        startEngine(name, canvas = true) { Log.d(TAG, it) }

    @Test
    fun the_list_is_the_app_s_own_roster_and_says_where_each_family_came_from() {
        val plugin = engineFor()
        // stock loads device fonts in the background, so the roster may change during the call
        val before = rosterNames()
        plugin.await("inu.canvas.listFonts().then(list => { globalThis.fonts = list })")
        val after = rosterNames()
        val listed = JSONArray(plugin.js("JSON.stringify(fonts)"))
        assertTrue(listed.length() > 0, "the app bundles fonts, so the list cannot be empty")

        val names = (0 until listed.length()).map { listed.getJSONObject(it).getString("name") }
        assertTrue(names == before || names == after, "the list is not the roster the app itself shows: $names")
        assertEquals(names.distinct().size, names.size, "a name a plugin cannot tell apart is listed twice")

        for (index in 0 until listed.length()) {
            val entry = listed.getJSONObject(index)
            assertTrue(
                entry.getString("source") in setOf("builtin", "imported", "device"),
                "${entry.getString("name")} came from '${entry.getString("source")}'",
            )
            assertTrue(entry.has("hidden"), "${entry.getString("name")} does not say whether it is hidden")
        }
    }

    @Test
    fun a_font_the_plugin_loaded_is_listed_as_its_own() {
        val plugin = engineFor()
        val bytes = smallestSystemFont().joinToString(",") { (it.toInt() and 0xff).toString() }
        plugin.js("globalThis.face = new Uint8Array([$bytes])")
        plugin.await(
            """
            inu.canvas.loadFont('Test Face', face)
              .then(() => inu.canvas.listFonts())
              .then(list => { globalThis.fonts = list })
            """.trimIndent(),
        )
        val listed = JSONArray(plugin.js("JSON.stringify(fonts)"))
        val mine = (0 until listed.length())
            .map { listed.getJSONObject(it) }
            .filter { it.getString("source") == "plugin" }
        assertEquals(1, mine.size, "expected the one family this plugin loaded, found ${mine.map { it.getString("name") }}")
        assertEquals("Test Face", mine[0].getString("name"))

        val other = engineFor("canvas-fonts-other")
        other.await("inu.canvas.listFonts().then(list => { globalThis.fonts = list })")
        assertTrue(
            other.js("JSON.stringify(fonts.filter(f => f.source === 'plugin'))") == "[]",
            "another plugin was shown a family it cannot draw with",
        )
    }

    private fun rosterNames(): List<String> = FontLibrary.getCachedRoster().map { FontLibrary.getFontName(it) }

    private fun smallestSystemFont(): ByteArray {
        val fonts = File("/system/fonts").listFiles()?.filter { it.isFile && it.name.endsWith(".ttf") }.orEmpty()
        val font = assertNotNull(fonts.minByOrNull { it.length() }, "this device has no system fonts to load")
        return font.readBytes()
    }

    private companion object {
        const val TAG = "InuCanvasFonts"
    }
}
