package desu.inugram.core.plugins

/**
 * install-time validation of a manifest's `@grant` scopes against the closed vocabularies each
 * grant defines. Unknown grant *names* are ignored on purpose (open vocabulary); this only rejects
 * a scope a *known* grant would never accept.
 */
object GrantValidator {
    private val NO_SCOPE_GRANTS = setOf(
        "kv",
        "clipboard.read",
        "clipboard.write",
        "openUrl",
        "onAppVisibilityChange",
        "interceptSendMessage",
        "unsafe.fs",
        "unsafe.xposed",
        "unsafe.notificationCenter",
        "unsafe.disableApiFiltering",
    )

    private val ACCOUNT_READ_SCOPES = setOf("self", "peers", "messages", "dialogs", "history", "draft")
    private val ACCOUNT_WRITE_SCOPES = setOf("send", "edit", "delete", "forward", "react", "read", "typing", "draft")
    private val EXTRA_UPDATE_SCOPES = setOf("new_message", "edit_message", "delete_message")

    /** never delivered without `unsafe.disableApiFiltering`, so a grant naming one narrows to nothing */
    private val UNDELIVERABLE_UPDATES = setOf("updateServiceNotification")

    /** every way of getting a domain wrong - `fetch(https://a.com)`, `fetch(a.com/path)`, `fetch(a.com:443)` - is a scope that can never match a host */
    private val DOMAIN = Regex("""[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*""")

    /** a java binary name or a namespace ending in `.*`; anything else matches no class under [ScopeMatch.NAMESPACE] */
    private val JVM_SCOPE = Regex("""\*|[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*(\.\*)?""")

    fun validateGrants(tokens: List<String>): List<String> {
        val problems = mutableListOf<String>()
        // this grant turns the takeover filter off wholesale at call time, so the surfaces it reopens stop being the typo the rejections below exist to surface
        val bypassesFilter = tokens.any { PluginPermissions.parseGrant(it)?.name == "unsafe.disableApiFiltering" }
        for (token in tokens) {
            if (PluginPermissions.isMalformed(token)) {
                problems.add("malformed grant '${token.trim()}'")
                continue
            }
            val grant = PluginPermissions.parseGrant(token) ?: continue
            when (grant.name) {
                in NO_SCOPE_GRANTS -> {
                    if (grant.scopes.isNotEmpty()) {
                        problems.add("grant '${grant.name}' takes no scopes")
                    }
                }
                "interceptRpc", "invokeRpc" -> for (scope in grant.scopes) {
                    if (scope !in TlCtorIds.methodNames) {
                        problems.add("unknown rpc method '$scope' in @grant ${grant.name}")
                    } else if (TakeoverMethods.isBlocked(scope) && !bypassesFilter) {
                        problems.add("'$scope' is a takeover method and cannot be granted")
                    }
                }
                "onUpdate" -> for (scope in grant.scopes) {
                    if (scope in UNDELIVERABLE_UPDATES && !bypassesFilter) {
                        problems.add("'$scope' is never delivered to plugins and cannot be granted")
                    } else if (scope !in TlCtorIds.updateNames && scope !in EXTRA_UPDATE_SCOPES) {
                        problems.add("unknown update type '$scope' in @grant onUpdate")
                    }
                }
                "interceptUpdate" -> for (scope in grant.scopes) {
                    if (scope in UNDELIVERABLE_UPDATES && !bypassesFilter) {
                        problems.add("'$scope' is never delivered to plugins and cannot be granted")
                    } else if (scope !in TlCtorIds.updateNames) {
                        problems.add("unknown update type '$scope' in @grant interceptUpdate")
                    }
                }
                "account.read" -> for (scope in grant.scopes) {
                    if (scope !in ACCOUNT_READ_SCOPES) {
                        problems.add("unknown account.read scope '$scope'")
                    }
                }
                "account.write" -> for (scope in grant.scopes) {
                    if (scope !in ACCOUNT_WRITE_SCOPES) {
                        problems.add("unknown account.write scope '$scope'")
                    }
                }
                "fetch" -> for (scope in grant.scopes) {
                    if (!DOMAIN.matches(scope)) {
                        problems.add("'$scope' is not a domain in @grant fetch")
                    }
                }
                "unsafe.jvm" -> for (scope in grant.scopes) {
                    if (!JVM_SCOPE.matches(scope)) {
                        problems.add("'$scope' is not a class or namespace in @grant unsafe.jvm")
                    }
                }
                "fs" -> for (scope in grant.scopes) {
                    if (FsQuota.parseSize(scope) == null) {
                        problems.add("invalid fs scope '$scope' (expected e.g. '200mb')")
                    }
                }
            }
        }
        return problems
    }
}
