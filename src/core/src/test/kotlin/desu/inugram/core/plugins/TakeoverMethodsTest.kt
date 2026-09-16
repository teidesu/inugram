package desu.inugram.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TakeoverMethodsTest {
    @Test
    fun everyAccountMethodStillExistsInTheSchema() {
        // regression: a rename in TLRPC.java must not silently empty this blocklist
        for (name in TakeoverMethods.ACCOUNT_METHODS) {
            assertTrue("$name missing from TlTables.methodNames", name in TlTables.methodNames)
        }
    }

    @Test
    fun blocklistMatchesTheDocumentedSetExactly() {
        // read out of the contract rather than restated here: the other tests only iterate whatever
        // the set holds, so both dropping an entry from the code and adding one to the doc alone
        // would widen the api and still pass
        assertEquals(documentedAccountMethods(), TakeoverMethods.ACCOUNT_METHODS)
    }

    @Test
    fun everyAccountMethodIsActuallyRefusedByTheGate() {
        // holding the right set is not the same as the gate reading it: `isBlocked` is what
        // invokeRpc/interceptRpc ask, and an unscoped grant satisfies every scope check, so this
        // call is the only thing between `@grant invokeRpc` and account.deleteAccount
        for (name in TakeoverMethods.ACCOUNT_METHODS) {
            assertTrue("$name is on the blocklist but isBlocked() lets it through", TakeoverMethods.isBlocked(name))
        }
    }

    @Test
    fun authExportLoginTokenIsBlockedAndKnown() {
        assertTrue(TakeoverMethods.isBlocked("auth.exportLoginToken"))
        assertTrue("auth.exportLoginToken" in TlTables.methodNames)
    }

    @Test
    fun usersGetUsersIsNotBlocked() {
        assertFalse(TakeoverMethods.isBlocked("users.getUsers"))
    }

    @Test
    fun everyAuthMethodIsBlockedByPrefix() {
        assertTrue(TakeoverMethods.isBlocked("auth.signIn"))
        assertTrue(TakeoverMethods.isBlocked("auth.anything"))
    }

    /**
     * the `account.`\{…\} run in `common.d.ts`'s "takeover rpc methods are refused" bullet, which is
     * the normative statement of this set.
     */
    private fun documentedAccountMethods(): Set<String> {
        val contract = File(forkRoot(), "src/plugins/common.d.ts").readText()
        val open = contract.indexOf(LIST_OPEN)
        assertTrue("the takeover bullet no longer opens with '$LIST_OPEN'", open >= 0)
        val close = contract.indexOf(LIST_CLOSE, open)
        assertTrue("the takeover bullet is not closed with '$LIST_CLOSE'", close >= 0)
        val names = contract.substring(open + LIST_OPEN.length, close)
            .split('`')
            .filterIndexed { i, _ -> i % 2 == 1 }
            .map { "account.$it" }
        assertEquals("the contract lists a name twice", names.size, names.toSet().size)
        return names.toSet()
    }

    private fun forkRoot(): File {
        var dir: File? = File(javaClass.protectionDomain.codeSource.location.toURI()).canonicalFile
        while (dir != null && !File(dir, "src/plugins/common.d.ts").isFile) dir = dir.parentFile
        return dir ?: error("could not locate the repo root")
    }

    private companion object {
        const val LIST_OPEN = "plus `account.`\\{"
        const val LIST_CLOSE = "\\}"
    }
}
