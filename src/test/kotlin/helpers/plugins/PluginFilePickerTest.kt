package desu.inugram.helpers.plugins

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.io.PluginBlobs
import desu.inugram.helpers.plugins.ui.PluginFilePicker
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONArray
import org.junit.After
import org.junit.Before
import org.junit.Test

/** a `file:` uri has no provider to report a size, which is exactly the case the running count exists for */
class PluginFilePickerTest {
    private val sources = ArrayList<File>()
    private lateinit var plugin: Plugin

    @Before
    fun setUp() {
        resetBridge()
        plugin = startPlugin("picker")
    }

    @After
    fun tearDown() {
        sources.forEach(File::delete)
        PluginBlobs.wipe(plugin.id)
    }

    private fun sparseSource(bytes: Long): Uri {
        val file = File.createTempFile("pick", ".bin", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        sources.add(file)
        RandomAccessFile(file, "rw").use { it.setLength(bytes) }
        return Uri.fromFile(file)
    }

    private fun pickedDir(): File = File(PluginBlobs.dirFor(plugin.id), "picked")

    @Test
    fun a_file_within_the_bound_is_copied_whole() {
        val picked = PluginFilePicker.copyIn(plugin.session!!, listOf(sparseSource(4096)), multiple = false)

        val entry = JSONArray((PluginWire.decode(picked.wire) as PluginWire.Value.Json).json).getJSONObject(0)
        assertEquals(4096L, File(entry.getString("path")).length())
        assertEquals(picked.copies.map { it.absolutePath }, listOf(entry.getString("path")))
    }

    @Test
    fun a_file_past_the_bound_stops_the_copy_and_leaves_no_file_of_any_pick_behind() {
        val within = sparseSource(4096)
        val past = sparseSource(PluginFilePicker.MAX_PICK_BYTES + 1)

        val picked = PluginFilePicker.copyIn(plugin.session!!, listOf(within, past), multiple = true)

        assertPluginError("quota-exceeded", picked.wire)
        assertTrue(picked.copies.isEmpty())
        assertEquals(emptyList(), pickedDir().listFiles().orEmpty().toList())
    }
}
