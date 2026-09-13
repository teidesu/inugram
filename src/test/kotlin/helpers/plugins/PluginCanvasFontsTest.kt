package desu.inugram.helpers.plugins

import android.util.Log
import desu.inugram.helpers.font.FontLibrary
import desu.inugram.helpers.plugins.ui.PluginCanvas
import java.io.File
import org.json.JSONArray
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `inu.canvas.listFonts` against the app's real font roster, which is the only place it exists: the
 * names it answers are what a `ctx.font` resolves against, so a list that disagreed with the roster
 * would be a list of names that draw as the default face.
 */
class PluginCanvasFontsTest {
    private val plugins = ArrayList<Plugin>()

    @Before
    fun setUp() = resetBridge()

    @After
    fun tearDown() {
        plugins.forEach(::closeCanvasEngine)
        plugins.clear()
    }

    /** a name of its own per engine: the install id is the name's, and wiping one live store would take another's */
    private fun engineFor(name: String = "canvas-fonts"): Plugin =
        canvasEngine(name) { Log.d(TAG, it) }.also {
            PluginCanvas.wipe(it.id)
            plugins.add(it)
        }

    @Test
    fun the_list_is_the_app_s_own_roster_and_says_where_each_family_came_from() {
        val plugin = engineFor()
        // the roster is read on both sides of the call: the app loads the device's families in the
        // background at startup, so the only claim that holds is that the list is one of the two
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

    /** what the plugin loaded for itself is a family it can name too, and nobody else can */
    @Test
    fun a_font_the_plugin_loaded_is_listed_as_its_own() {
        val plugin = engineFor()
        // bytes rather than a path: the suite installs no `inu.fs`, and a font is a font either way
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

    /** any real font will do, and the smallest keeps the array literal it is handed over as small */
    private fun smallestSystemFont(): ByteArray {
        val fonts = File("/system/fonts").listFiles()?.filter { it.isFile && it.name.endsWith(".ttf") }.orEmpty()
        val font = assertNotNull(fonts.minByOrNull { it.length() }, "this device has no system fonts to load")
        return font.readBytes()
    }

    private companion object {
        const val TAG = "InuCanvasFonts"
    }
}
