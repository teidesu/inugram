package desu.inugram.core.plugins

/**
 * install-time validation of a manifest's `@grant` scopes against the closed vocabularies
 * [GrantCatalog] gives each grant. Unknown grant *names* are ignored on purpose (open vocabulary);
 * this only rejects a scope a *known* grant would never accept.
 */
object GrantValidator {
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
            val entry = GrantCatalog.entryOf(grant.name) ?: continue
            if (entry.scopes == ScopeKind.NONE) {
                if (grant.scopes.isNotEmpty()) problems.add("grant '${grant.name}' takes no scopes")
                continue
            }
            for (scope in grant.scopes) {
                validateScope(entry, scope, bypassesFilter)?.let { problems.add(it) }
            }
        }
        return problems
    }

    private fun validateScope(entry: GrantCatalog.Entry, scope: String, bypassesFilter: Boolean): String? = when (entry.scopes) {
        ScopeKind.NONE -> null
        ScopeKind.LIST ->
            if (scope in entry.values) null else "unknown ${entry.name} scope '$scope'"
        ScopeKind.DOMAIN ->
            if (GrantCatalog.isDomain(scope)) null else "'$scope' is not a domain in @grant ${entry.name}"
        ScopeKind.FS_SIZE ->
            if (GrantCatalog.fsSizeMatch(scope) != null) null else "invalid ${entry.name} scope '$scope' (expected e.g. '200mb')"
        ScopeKind.RPC_METHOD -> when {
            scope !in TlTables.methodNames -> "unknown rpc method '$scope' in @grant ${entry.name}"
            entry.refusesTakeover && GrantCatalog.isTakeoverMethod(scope) && !bypassesFilter ->
                "'$scope' is a takeover method and cannot be granted"
            else -> null
        }
        ScopeKind.UPDATE_TYPE -> when {
            scope in GrantCatalog.UNDELIVERABLE_UPDATES && !bypassesFilter ->
                "'$scope' is never delivered to plugins and cannot be granted"
            scope in TlTables.updateNames || scope in entry.extraValues -> null
            else -> "unknown update type '$scope' in @grant ${entry.name}"
        }
    }
}
