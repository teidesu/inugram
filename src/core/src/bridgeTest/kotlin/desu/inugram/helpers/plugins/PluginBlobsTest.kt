package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.io.PluginBlobs
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

/** the spill directory: an install id names a path here, so a bad one must never reach the fs. */
class PluginBlobsTest {
    @Before
    fun setUp() = resetBridge()

    private val validId = "0123456789abcdef0123456789abcdef"

    @Test
    fun `a valid install id gets a directory under the process's own session`() {
        val path = PluginBlobs.dirFor(validId)

        assertTrue(File(path).isDirectory)
        assertTrue(path.endsWith(validId))
        assertTrue(path.contains("inu_plugin_blobs"))
    }

    @Test
    fun `an id that names a path is refused rather than resolved`() {
        for (bad in listOf("../../etc", "..", "abc", "0123456789ABCDEF0123456789abcdef/x", "")) {
            assertFailsWith<IllegalArgumentException>("'$bad' must not reach the filesystem") {
                PluginBlobs.dirFor(bad)
            }
        }
    }

    @Test
    fun `wipe only ever deletes the directory of a well-formed id`() {
        val path = PluginBlobs.dirFor(validId)
        val root = File(path).parentFile.parentFile
        assertTrue(root.isDirectory)

        PluginBlobs.wipe("../..")
        assertTrue(root.isDirectory, "a malformed id must not delete anything")

        PluginBlobs.wipe(validId)
        assertFalse(File(path).exists())
    }

    @Test
    fun `sweep drops other processes' sessions and keeps this one's`() {
        val mine = File(PluginBlobs.dirFor(validId))
        val stale = File(mine.parentFile.parentFile, "some-dead-process/$validId")
        assertTrue(stale.mkdirs())

        PluginBlobs.scheduleSweep()
        // the sweep is an unbounded recursive delete moved off ApplicationLoader.onCreate, so the
        // test has to run the queue it was handed to rather than expecting it inline
        drain()

        assertTrue(mine.isDirectory)
        assertFalse(stale.exists())
        assertEquals(1, mine.parentFile.parentFile.listFiles()!!.size)
    }
}
