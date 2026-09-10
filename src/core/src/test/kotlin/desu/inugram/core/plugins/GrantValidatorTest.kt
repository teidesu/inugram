package desu.inugram.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrantValidatorTest {
    @Test
    fun rpcGrantsAcceptKnownNonTakeoverMethods() {
        assertEquals(emptyList<String>(), GrantValidator.validateGrants(listOf("interceptRpc(users.getUsers)")))
        assertEquals(emptyList<String>(), GrantValidator.validateGrants(listOf("invokeRpc(users.getUsers)")))
    }

    @Test
    fun rpcGrantsRejectUnknownMethodName() {
        val problems = GrantValidator.validateGrants(listOf("interceptRpc(not.a.method)"))
        assertEquals(listOf("unknown rpc method 'not.a.method' in @grant interceptRpc"), problems)
    }

    @Test
    fun rpcGrantsRejectTakeoverMethods() {
        val problems = GrantValidator.validateGrants(listOf("invokeRpc(auth.exportLoginToken)"))
        assertEquals(listOf("'auth.exportLoginToken' is a takeover method and cannot be granted"), problems)
    }

    @Test
    fun onUpdateAcceptsScopedUpdateNamesAndTheSyntheticOnes() {
        assertEquals(emptyList<String>(), GrantValidator.validateGrants(listOf("onUpdate(updateNewMessage)")))
        assertEquals(emptyList<String>(), GrantValidator.validateGrants(listOf("onUpdate(new_message,edit_message,delete_message)")))
    }

    @Test
    fun onUpdateRejectsUnknownUpdateType() {
        val problems = GrantValidator.validateGrants(listOf("onUpdate(not_a_real_update)"))
        assertEquals(listOf("unknown update type 'not_a_real_update' in @grant onUpdate"), problems)
    }

    @Test
    fun updateTypesThatAreNeverDeliveredCannotBeGranted() {
        val expected = listOf("'updateServiceNotification' is never delivered to plugins and cannot be granted")
        assertEquals(expected, GrantValidator.validateGrants(listOf("onUpdate(updateServiceNotification)")))
        assertEquals(expected, GrantValidator.validateGrants(listOf("interceptUpdate(updateServiceNotification)")))
    }

    @Test
    fun aMalformedGrantRefusesTheInstall() {
        // it used to widen to the unscoped form, so a missing paren silently bought every domain
        assertEquals(
            listOf("malformed grant 'fetch(evil.com'"),
            GrantValidator.validateGrants(listOf("fetch(evil.com")),
        )
        // an unclosed paren also swallows every token after it on the line (splitTopLevelCommas
        // never returns to depth 0), so refusing the install is the only way the loss is visible
        assertEquals(
            listOf("malformed grant 'fetch(a.com, kv'"),
            GrantValidator.validateGrants(listOf("fetch(a.com, kv")),
        )
    }

    @Test
    fun aWrittenButEmptyScopeListRefusesTheInstall() {
        assertEquals(listOf("malformed grant 'invokeRpc()'"), GrantValidator.validateGrants(listOf("invokeRpc()")))
        assertEquals(listOf("malformed grant 'account.read( )'"), GrantValidator.validateGrants(listOf("account.read( )")))
    }

    @Test
    fun theBypassGrantReopensEverySurfaceTheFilterCloses() {
        assertEquals(
            emptyList<String>(),
            GrantValidator.validateGrants(listOf("unsafe.disableApiFiltering", "onUpdate(updateServiceNotification)")),
        )
        assertEquals(
            emptyList<String>(),
            GrantValidator.validateGrants(listOf("unsafe.disableApiFiltering", "interceptUpdate(updateServiceNotification)")),
        )
        assertEquals(
            emptyList<String>(),
            GrantValidator.validateGrants(listOf("unsafe.disableApiFiltering", "invokeRpc(auth.exportLoginToken)")),
        )
    }

    @Test
    fun interceptUpdateAcceptsRealUpdateNamesButNotSyntheticOnes() {
        assertEquals(emptyList<String>(), GrantValidator.validateGrants(listOf("interceptUpdate(updateNewMessage)")))
        val problems = GrantValidator.validateGrants(listOf("interceptUpdate(new_message)"))
        assertEquals(listOf("unknown update type 'new_message' in @grant interceptUpdate"), problems)
    }

    @Test
    fun accountReadAcceptsOnlyItsClosedVocabulary() {
        assertEquals(emptyList<String>(), GrantValidator.validateGrants(listOf("account.read(self,peers,messages,dialogs,history,draft)")))
        assertEquals(
            listOf("unknown account.read scope 'bogus'"),
            GrantValidator.validateGrants(listOf("account.read(bogus)")),
        )
    }

    @Test
    fun accountWriteAcceptsOnlyItsClosedVocabulary() {
        assertEquals(
            emptyList<String>(),
            GrantValidator.validateGrants(listOf("account.write(send,edit,delete,forward,react,read,typing,draft)")),
        )
        assertEquals(
            listOf("unknown account.write scope 'bogus'"),
            GrantValidator.validateGrants(listOf("account.write(bogus)")),
        )
    }

    @Test
    fun fetchAcceptsDomainsAndNothingElse() {
        assertEquals(emptyList<String>(), GrantValidator.validateGrants(listOf("fetch(example.com,api.example.com)")))
        assertEquals(emptyList<String>(), GrantValidator.validateGrants(listOf("fetch(EXAMPLE.com,xn--80ak6aa92e.com)")))
    }

    /**
     * a scope shaped like anything but a host can never match one, so it narrows the grant to
     * nothing - the same silent failure the rest of this validator exists to turn into a refusal
     */
    @Test
    fun fetchRefusesAScopeThatIsNotAHostName() {
        for (scope in listOf("https://example.com", "example.com/path", "example.com:443", "*.example.com", "-a.com")) {
            assertEquals(
                scope,
                listOf("'" + scope + "' is not a domain in @grant fetch"),
                GrantValidator.validateGrants(listOf("fetch(" + scope + ")")),
            )
        }
    }

    @Test
    fun fsAcceptsSizeShapedScopesOnly() {
        assertEquals(emptyList<String>(), GrantValidator.validateGrants(listOf("fs(200mb)")))
        assertEquals(emptyList<String>(), GrantValidator.validateGrants(listOf("fs(1KB,2Gb)")))
        assertEquals(
            listOf("invalid fs scope 'unlimited' (expected e.g. '200mb')"),
            GrantValidator.validateGrants(listOf("fs(unlimited)")),
        )
    }

    @Test
    fun noScopeGrantsRejectAnyScope() {
        for (name in listOf("kv", "clipboard.read", "openUrl", "unsafe.fs", "unsafe.disableApiFiltering")) {
            assertEquals(listOf("grant '$name' takes no scopes"), GrantValidator.validateGrants(listOf("$name(oops)")))
        }
    }

    @Test
    fun noScopeGrantsAreValidWithoutScopes() {
        assertEquals(emptyList<String>(), GrantValidator.validateGrants(listOf("kv", "openUrl", "unsafe.jvm")))
    }

    /** the grant reaches every class there is, so a scope list on it described nothing */
    @Test
    fun jvmTakesNoScopes() {
        assertEquals(emptyList<String>(), GrantValidator.validateGrants(listOf("unsafe.jvm")))
        assertEquals(
            listOf("grant 'unsafe.jvm' takes no scopes"),
            GrantValidator.validateGrants(listOf("unsafe.jvm(java.util.*)")),
        )
        assertEquals(
            listOf("grant 'unsafe.jvm' takes no scopes"),
            GrantValidator.validateGrants(listOf("unsafe.jvm(*)")),
        )
    }

    @Test
    fun unknownGrantNamesAreIgnoredScopesAndAll() {
        assertEquals(emptyList<String>(), GrantValidator.validateGrants(listOf("someFutureGrant(whatever,garbage)")))
    }

    @Test
    fun validFullManifestShapedGrantListPasses() {
        val problems = GrantValidator.validateGrants(
            listOf(
                "kv",
                "fetch(api.example.com)",
                "fs(50mb)",
                "account.read(self,peers)",
                "account.write(send)",
                "interceptRpc(messages.sendMessage)",
                "onUpdate(new_message)",
                "clipboard.write",
            ),
        )
        assertTrue(problems.isEmpty())
    }
}
