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
    fun `a plain fs grant gets the default cap`() {
        assertEquals(FsQuota.DEFAULT_BYTES, FsQuota.forGrants(listOf("kv", "fs")))
    }

    @Test
    fun `a size scope is the cap in bytes`() {
        assertEquals(200L * 1024 * 1024, FsQuota.forGrants(listOf("fs(200mb)")))
        assertEquals(512L * 1024, FsQuota.forGrants(listOf("fs(512kb)")))
        assertEquals(2L * 1024 * 1024 * 1024, FsQuota.forGrants(listOf("fs(2GB)")))
    }

    /** adding a grant line must never be able to take storage away from a plugin */
    @Test
    fun `a plugin that asked twice gets the larger number`() {
        assertEquals(200L * 1024 * 1024, FsQuota.forGrants(listOf("fs(10mb)", "fs(200mb)")))
        assertEquals(200L * 1024 * 1024, FsQuota.forGrants(listOf("fs(200mb)", "fs(10mb)")))
    }

    @Test
    fun `unsafe fs is uncapped and replaces the safe grant rather than adding to it`() {
        assertEquals(FsQuota.UNCAPPED, FsQuota.forGrants(listOf("unsafe.fs")))
        assertEquals(FsQuota.UNCAPPED, FsQuota.forGrants(listOf("fs(10mb)", "unsafe.fs")))
    }

    /**
     * null rather than 0: rust reads a 0 as "the host had no number to give" and substitutes the
     * default cap, so the two sentinels would silently disagree and every install would get a
     * scoped directory made for an api it may never call. `PluginApi.install` skips `installFs`
     * outright on null.
     */
    @Test
    fun `a plugin that asked for nothing carries no cap of its own`() {
        assertNull(FsQuota.forGrants(listOf("kv", "fetch")))
        assertNull(FsQuota.forGrants(emptyList()))
    }

    @Test
    fun `a size the validator would have refused is not a number`() {
        assertNull(FsQuota.parseSize("200"))
        assertNull(FsQuota.parseSize("mb"))
        assertNull(FsQuota.parseSize("200tb"))
        assertNull(FsQuota.parseSize("2 mb"))
        assertEquals(200L * 1024 * 1024, FsQuota.parseSize(" 200MB "))
    }

    /** a manifest asking for exabytes is a typo, and must not become an overflowed cap in rust */
    @Test
    fun `an absurd size is clamped rather than wrapped`() {
        val huge = FsQuota.parseSize("99999999999gb")!!
        assertTrue(huge > 0, "a cap that overflowed would read as negative")
        assertTrue(huge < Long.MAX_VALUE)
    }

    /** the shape `GrantValidator` refuses the install over is the shape the cap is read with */
    @Test
    fun `the validator refuses exactly the scopes that are not a size`() {
        assertEquals(
            listOf("invalid fs scope '200' (expected e.g. '200mb')"),
            GrantValidator.validateGrants(listOf("fs(200)")),
        )
        assertEquals(emptyList(), GrantValidator.validateGrants(listOf("fs(200mb)")))
    }
}
