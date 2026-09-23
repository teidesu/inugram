package desu.inugram.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrantValidatorTest {
    @Test
    fun rpc_grants_accept_known_non_takeover_methods() {
        assertEquals(emptyList<String>(), GrantValidator.validateGrants(listOf("interceptRpc(users.getUsers)")))
        assertEquals(emptyList<String>(), GrantValidator.validateGrants(listOf("invokeRpc(users.getUsers)")))
    }

    @Test
    fun rpc_grants_reject_unknown_method_name() {
        val problems = GrantValidator.validateGrants(listOf("interceptRpc(not.a.method)"))
        assertEquals(listOf("unknown rpc method 'not.a.method' in @grant interceptRpc"), problems)
    }

    @Test
    fun rpc_grants_reject_takeover_methods() {
        val problems = GrantValidator.validateGrants(listOf("invokeRpc(auth.exportLoginToken)"))
        assertEquals(listOf("'auth.exportLoginToken' is a takeover method and cannot be granted"), problems)
    }

    @Test
    fun on_update_accepts_scoped_update_names_and_the_synthetic_ones() {
        assertEquals(emptyList<String>(), GrantValidator.validateGrants(listOf("onUpdate(updateNewMessage)")))
        assertEquals(emptyList<String>(), GrantValidator.validateGrants(listOf("onUpdate(new_message,edit_message,delete_message)")))
    }

    @Test
    fun on_update_rejects_unknown_update_type() {
        val problems = GrantValidator.validateGrants(listOf("onUpdate(not_a_real_update)"))
        assertEquals(listOf("unknown update type 'not_a_real_update' in @grant onUpdate"), problems)
    }

    @Test
    fun update_types_that_are_never_delivered_cannot_be_granted() {
        val expected = listOf("'updateServiceNotification' is never delivered to plugins and cannot be granted")
        assertEquals(expected, GrantValidator.validateGrants(listOf("onUpdate(updateServiceNotification)")))
        assertEquals(expected, GrantValidator.validateGrants(listOf("interceptUpdate(updateServiceNotification)")))
    }

    @Test
    fun a_malformed_grant_refuses_the_install() {
        assertEquals(
            listOf("malformed grant 'fetch(evil.com'"),
            GrantValidator.validateGrants(listOf("fetch(evil.com")),
        )
        assertEquals(
            listOf("malformed grant 'fetch(a.com, openUrl'"),
            GrantValidator.validateGrants(listOf("fetch(a.com, openUrl")),
        )
    }

    @Test
    fun a_written_but_empty_scope_list_refuses_the_install() {
        assertEquals(listOf("malformed grant 'invokeRpc()'"), GrantValidator.validateGrants(listOf("invokeRpc()")))
        assertEquals(listOf("malformed grant 'account.read( )'"), GrantValidator.validateGrants(listOf("account.read( )")))
    }

    @Test
    fun the_bypass_grant_reopens_every_surface_the_filter_closes() {
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
    fun intercept_update_accepts_real_update_names_but_not_synthetic_ones() {
        assertEquals(emptyList<String>(), GrantValidator.validateGrants(listOf("interceptUpdate(updateNewMessage)")))
        val problems = GrantValidator.validateGrants(listOf("interceptUpdate(new_message)"))
        assertEquals(listOf("unknown update type 'new_message' in @grant interceptUpdate"), problems)
    }

    @Test
    fun account_scopes_accept_only_their_closed_vocabulary() {
        assertEquals(emptyList<String>(), GrantValidator.validateGrants(listOf("account.read(self,peers,messages,dialogs,history,draft)")))
        assertEquals(
            emptyList<String>(),
            GrantValidator.validateGrants(listOf("account.write(send,edit,delete,forward,react,read,typing,draft)")),
        )
        for (grant in listOf("account.read", "account.write")) {
            assertEquals(listOf("unknown $grant scope 'bogus'"), GrantValidator.validateGrants(listOf("$grant(bogus)")))
        }
    }

    @Test
    fun fetch_accepts_domains_and_nothing_else() {
        assertEquals(emptyList<String>(), GrantValidator.validateGrants(listOf("fetch(example.com,api.example.com)")))
        assertEquals(emptyList<String>(), GrantValidator.validateGrants(listOf("fetch(EXAMPLE.com,xn--80ak6aa92e.com)")))
    }

    @Test
    fun fetch_refuses_a_scope_that_is_not_a_host_name() {
        for (scope in listOf("https://example.com", "example.com/path", "example.com:443", "*.example.com", "-a.com")) {
            assertEquals(
                scope,
                listOf("'" + scope + "' is not a domain in @grant fetch"),
                GrantValidator.validateGrants(listOf("fetch(" + scope + ")")),
            )
        }
    }

    @Test
    fun fs_accepts_size_shaped_scopes_only() {
        assertEquals(emptyList<String>(), GrantValidator.validateGrants(listOf("fs(200mb)")))
        assertEquals(emptyList<String>(), GrantValidator.validateGrants(listOf("fs(1KB,2Gb)")))
        assertEquals(
            listOf("invalid fs scope 'unlimited' (expected e.g. '200mb')"),
            GrantValidator.validateGrants(listOf("fs(unlimited)")),
        )
    }

    @Test
    fun no_scope_grants_reject_any_scope() {
        for (name in listOf("clipboard.read", "openUrl", "takeout", "unsafe.fs", "unsafe.jvm", "unsafe.invokeRaw", "unsafe.disableApiFiltering", "notifications.suppress")) {
            assertEquals(listOf("grant '$name' takes no scopes"), GrantValidator.validateGrants(listOf("$name(oops)")))
        }
    }

    @Test
    fun unknown_grant_names_are_ignored_scopes_and_all() {
        assertEquals(emptyList<String>(), GrantValidator.validateGrants(listOf("someFutureGrant(whatever,garbage)")))
    }

    @Test
    fun valid_full_manifest_shaped_grant_list_passes() {
        val problems = GrantValidator.validateGrants(
            listOf(
                "fetch(api.example.com)",
                "fs(50mb)",
                "account.read(self,peers)",
                "account.write(send)",
                "interceptRpc(messages.sendMessage)",
                "onUpdate(new_message)",
                "clipboard.write",
                "openUrl",
                "unsafe.jvm",
                "takeout",
                "unsafe.invokeRaw",
                "notifications.suppress",
            ),
        )
        assertTrue(problems.isEmpty())
    }
}
