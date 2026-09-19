package desu.inugram.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The blocklist itself lives in `sdk/types/grants.json` and `generate-grants` already holds it
 * against the contract, so what is left to check here is the half that generation cannot see: that
 * the *gate* reads it. `isTakeoverMethod` is what `invokeRpc`/`interceptRpc` ask, and an unscoped
 * grant satisfies every scope check, so that call is the only thing between `@grant invokeRpc` and
 * `account.deleteAccount`.
 */
class TakeoverFilterTest {
    @Test
    fun everyDocumentedMethodIsRefusedByTheGate() {
        for (name in documentedAccountMethods()) {
            assertTrue("$name is documented as refused but isTakeoverMethod() lets it through", GrantCatalog.isTakeoverMethod(name))
        }
    }

    @Test
    fun everyDocumentedMethodStillExistsInTheSchema() {
        // regression: a rename in TLRPC.java must not silently empty this blocklist
        for (name in documentedAccountMethods()) {
            assertTrue("$name missing from TlTables.methodNames", name in TlTables.methodNames)
        }
    }

    @Test
    fun authExportLoginTokenIsBlockedAndKnown() {
        assertTrue(GrantCatalog.isTakeoverMethod("auth.exportLoginToken"))
        assertTrue("auth.exportLoginToken" in TlTables.methodNames)
    }

    @Test
    fun usersGetUsersIsNotBlocked() {
        assertFalse(GrantCatalog.isTakeoverMethod("users.getUsers"))
    }

    @Test
    fun everyAuthMethodIsBlockedByPrefix() {
        assertTrue(GrantCatalog.isTakeoverMethod("auth.signIn"))
        assertTrue(GrantCatalog.isTakeoverMethod("auth.anything"))
    }

    /**
     * the `account.`\{…\} run in `common.d.ts`'s "takeover rpc methods are refused" bullet, which is
     * the normative statement of this set.
     */
    private fun documentedAccountMethods(): Set<String> {
        val contract = File(forkRoot(), "sdk/types/common.d.ts").readText()
        val open = contract.indexOf(LIST_OPEN)
        assertTrue("the takeover bullet no longer opens with '$LIST_OPEN'", open >= 0)
        val close = contract.indexOf(LIST_CLOSE, open)
        assertTrue("the takeover bullet is not closed with '$LIST_CLOSE'", close >= 0)
        val names = contract.substring(open + LIST_OPEN.length, close)
            .split('`')
            .filterIndexed { i, _ -> i % 2 == 1 }
            .map { "account.$it" }
        assertEquals("the contract lists a name twice", names.size, names.toSet().size)
        assertTrue("the takeover bullet lists nothing", names.isNotEmpty())
        return names.toSet()
    }

    private fun forkRoot(): File {
        var dir: File? = File(javaClass.protectionDomain.codeSource.location.toURI()).canonicalFile
        while (dir != null && !File(dir, "sdk/types/common.d.ts").isFile) dir = dir.parentFile
        return dir ?: error("could not locate the repo root")
    }

    private companion object {
        const val LIST_OPEN = "plus `account.`\\{"
        const val LIST_CLOSE = "\\}"
    }
}
