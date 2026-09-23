package desu.inugram.core.plugins

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * What a manifest's `fs` grants say about how much disk a plugin gets. String arithmetic over the
 * grant list and nothing else - the directory the number caps is `PluginFs`'s and is tested on a
 * device, and the enforcement is rust's.
 */
class FsQuotaTest {
    @Test
    fun a_plain_fs_grant_gets_the_default_cap() {
        assertEquals(FsQuota.DEFAULT_BYTES, FsQuota.forGrants(listOf("openUrl", "fs")))
    }

    @Test
    fun a_size_scope_is_the_cap_in_bytes() {
        assertEquals(200L * 1024 * 1024, FsQuota.forGrants(listOf("fs(200mb)")))
        assertEquals(512L * 1024, FsQuota.forGrants(listOf("fs(512kb)")))
        assertEquals(2L * 1024 * 1024 * 1024, FsQuota.forGrants(listOf("fs(2GB)")))
    }

    /** adding a grant line must never be able to take storage away from a plugin */
    @Test
    fun a_plugin_that_asked_twice_gets_the_larger_number() {
        assertEquals(200L * 1024 * 1024, FsQuota.forGrants(listOf("fs(10mb)", "fs(200mb)")))
        assertEquals(200L * 1024 * 1024, FsQuota.forGrants(listOf("fs(200mb)", "fs(10mb)")))
    }

    @Test
    fun unsafe_fs_is_uncapped_and_replaces_the_safe_grant_rather_than_adding_to_it() {
        assertEquals(FsQuota.UNCAPPED, FsQuota.forGrants(listOf("unsafe.fs")))
        assertEquals(FsQuota.UNCAPPED, FsQuota.forGrants(listOf("fs(10mb)", "unsafe.fs")))
    }

    /**
     * null rather than 0: rust reads a 0 as "the host had no number to give" and substitutes the
     * default cap, so the two sentinels would silently disagree and every install would get a
     * scoped directory made for an api it may never call. `PluginManager` skips `installFs`
     * outright on null.
     */
    @Test
    fun a_plugin_that_asked_for_nothing_carries_no_cap_of_its_own() {
        assertNull(FsQuota.forGrants(listOf("openUrl", "fetch")))
        assertNull(FsQuota.forGrants(emptyList()))
    }

    @Test
    fun a_size_the_validator_would_have_refused_is_not_a_number() {
        assertNull(FsQuota.parseSize("200"))
        assertNull(FsQuota.parseSize("mb"))
        assertNull(FsQuota.parseSize("200tb"))
        assertNull(FsQuota.parseSize("2 mb"))
        assertEquals(200L * 1024 * 1024, FsQuota.parseSize(" 200MB "))
    }

    /** a manifest asking for exabytes is a typo, and must not become an overflowed cap in rust */
    @Test
    fun an_absurd_size_is_clamped_rather_than_wrapped() {
        val huge = FsQuota.parseSize("99999999999gb")!!
        assertTrue(huge > 0, "a cap that overflowed would read as negative")
        assertTrue(huge < Long.MAX_VALUE)
    }
}
