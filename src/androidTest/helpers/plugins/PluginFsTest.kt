package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.io.PluginBlobs
import desu.inugram.helpers.plugins.io.PluginFs
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        val scopedRoot = File(PluginFs.dirFor(validId)).parentFile
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
        val root = File(path).parentFile

        PluginFs.wipe("../..")
        assertTrue(File(path).isDirectory, "a malformed id must not delete anything")

        PluginFs.wipe(validId)
        assertFalse(File(path).exists())
        assertTrue(root.isDirectory, "and it takes only its own directory")
    }

    @Test
    fun a_plain_fs_grant_gets_the_default_cap() {
        assertEquals(PluginFs.DEFAULT_QUOTA_BYTES, PluginFs.quotaFor(listOf("kv", "fs")))
    }

    /**
     * every other test here spells the constant, so the number a device actually gets is otherwise
     * pinned by nothing: multiplied by a thousand the whole suite stays green and a plugin holding
     * `@grant fs` gets the disk.
     */
    @Test
    fun the_default_cap_is_the_size_the_contract_states() {
        val contract = fsContract()
        val mb = statedNumber(contract, "so it is **capped at {} MB**")
        assertEquals(mb * 1024 * 1024, PluginFs.DEFAULT_QUOTA_BYTES)
        // `quota()` restates it, and a plugin reads that line rather than the prose above it
        assertEquals(mb, statedNumber(contract, "the cap, in bytes: {} MB"))
    }

    @Test
    fun a_size_scope_is_the_cap_in_bytes() {
        assertEquals(200L * 1024 * 1024, PluginFs.quotaFor(listOf("fs(200mb)")))
        assertEquals(512L * 1024, PluginFs.quotaFor(listOf("fs(512kb)")))
        assertEquals(2L * 1024 * 1024 * 1024, PluginFs.quotaFor(listOf("fs(2GB)")))
    }

    /** adding a grant line must never be able to take storage away from a plugin */
    @Test
    fun a_plugin_that_asked_twice_gets_the_larger_number() {
        assertEquals(200L * 1024 * 1024, PluginFs.quotaFor(listOf("fs(10mb)", "fs(200mb)")))
        assertEquals(200L * 1024 * 1024, PluginFs.quotaFor(listOf("fs(200mb)", "fs(10mb)")))
    }

    @Test
    fun unsafe_fs_is_uncapped_and_unscoped_and_replaces_the_safe_grant_rather_than_adding_to_it() {
        assertEquals(PluginFs.UNCAPPED, PluginFs.quotaFor(listOf("unsafe.fs")))
        assertEquals(PluginFs.UNCAPPED, PluginFs.quotaFor(listOf("fs(10mb)", "unsafe.fs")))

        val permissions = desu.inugram.core.plugins.PluginPermissions.parse(listOf("fs(10mb)", "unsafe.fs"))
        assertTrue(PluginFs.isUnscoped(permissions))
        assertFalse(PluginFs.isUnscoped(desu.inugram.core.plugins.PluginPermissions.parse(listOf("fs(10mb)"))))
    }

    /**
     * null rather than 0: rust reads a 0 as "the host had no number to give" and substitutes the
     * default cap, so the two sentinels would silently disagree and every install would get a
     * scoped directory made for an api it may never call. `PluginApi.attach` skips `installFs`
     * outright on null.
     */
    @Test
    fun a_plugin_that_asked_for_nothing_carries_no_cap_of_its_own() {
        assertNull(PluginFs.quotaFor(listOf("kv", "fetch")))
        assertNull(PluginFs.quotaFor(emptyList()))
    }

    @Test
    fun a_size_the_validator_would_have_refused_is_not_a_number() {
        assertNull(PluginFs.parseSize("200"))
        assertNull(PluginFs.parseSize("mb"))
        assertNull(PluginFs.parseSize("200tb"))
        assertNull(PluginFs.parseSize("2 mb"))
        assertEquals(200L * 1024 * 1024, PluginFs.parseSize(" 200MB "))
    }

    /** a manifest asking for exabytes is a typo, and must not become an overflowed cap in rust */
    @Test
    fun an_absurd_size_is_clamped_rather_than_wrapped() {
        val huge = PluginFs.parseSize("99999999999gb")!!
        assertTrue(huge > 0, "a cap that overflowed would read as negative")
        assertTrue(huge < Long.MAX_VALUE)
    }
}
