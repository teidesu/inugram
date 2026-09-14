package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.FsQuota
import desu.inugram.core.plugins.PluginPermissions
import desu.inugram.helpers.plugins.io.PluginBlobs
import desu.inugram.helpers.plugins.io.PluginFs
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.ApplicationLoader

/**
 * The host's whole share of `inu.fs`: which directory an install id names, how big the manifest
 * asked for it to be, and when it is allowed to go away. The path resolution and the containment
 * check live in rust (`fs.rs`), where they are tested against a real filesystem.
 */
class PluginFsTest {
    @Before
    fun setUp() = resetBridge()

    private val validId = "0123456789abcdef0123456789abcdef"

    @Test
    fun an_install_id_names_a_directory_under_the_app_s_durable_storage() {
        val path = PluginFs.dirFor(validId)

        assertTrue(File(path).isDirectory)
        assertTrue(path.endsWith("inu_plugins/scoped_$validId"), path)
        val files = ApplicationLoader.applicationContext.filesDir.absolutePath
        assertTrue(path.startsWith(files), "fs storage is durable, so it is not in the cache area: $path")
    }

    /**
     * the promise `fs.d.ts` makes is that content "survives restarts and sticks around until the
     * plugin deletes it", which the cache area does not - android evicts it and stock's own
     * "clear cache" wipes it
     */
    @Test
    fun the_fs_directory_is_not_inside_the_blob_spill_area() {
        val fs = PluginFs.dirFor(validId)
        val spill = PluginBlobs.dirFor(validId)

        assertFalse(fs.startsWith(spill), "$fs is under $spill")
        assertFalse(spill.startsWith(fs), "$spill is under $fs")
    }

    /**
     * `android.d.ts` calls it "where plugins are installed", and that is the directory
     * `PluginManager` loads `.js` files out of - not the parent of every install's private `fs`
     * root, which holds no source at all and enumerates every other plugin by install id.
     */
    @Test
    fun getPluginsDir_names_the_store_the_manager_installs_into_not_the_fs_roots() {
        val dirs = PluginFs.androidDirs().split("\n")
        val store = PluginFs.storeDir()

        assertEquals(store.absolutePath, dirs[0])
        assertTrue(store.isDirectory)
        val scopedRoot = File(PluginFs.dirFor(validId)).parentFile!!
        assertFalse(dirs[0] == scopedRoot.absolutePath, "the fs roots are not the plugin store")
    }

    @Test
    fun an_id_that_names_a_path_is_refused_rather_than_resolved() {
        for (bad in listOf("../../etc", "..", "abc", "0123456789ABCDEF0123456789abcdef/x", "")) {
            assertFailsWith<IllegalArgumentException>("'$bad' must not reach the filesystem") {
                PluginFs.dirFor(bad)
            }
        }
    }

    @Test
    fun wipe_only_ever_deletes_the_directory_of_a_well_formed_id() {
        val path = PluginFs.dirFor(validId)
        File(path, "kept.txt").writeText("data")
        val root = File(path).parentFile!!

        PluginFs.wipe("../..")
        assertTrue(File(path).isDirectory, "a malformed id must not delete anything")

        PluginFs.wipe(validId)
        assertFalse(File(path).exists())
        assertTrue(root.isDirectory, "and it takes only its own directory")
    }

    /** the mode half of `unsafe.fs`; that it is also uncapped is [FsQuota.forGrants]'s, in `FsQuotaTest` */
    @Test
    fun unsafe_fs_is_unscoped_and_replaces_the_safe_grant_rather_than_adding_to_it() {
        assertTrue(PluginFs.isUnscoped(PluginPermissions.parse(listOf("fs(10mb)", "unsafe.fs"))))
        assertFalse(PluginFs.isUnscoped(PluginPermissions.parse(listOf("fs(10mb)"))))
    }
}
