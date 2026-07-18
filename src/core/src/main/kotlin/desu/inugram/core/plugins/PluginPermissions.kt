package desu.inugram.core.plugins

/** how a scoped grant's arguments are matched against a concrete target */
enum class ScopeMatch {
    /** literal equality — rpc method names, update types */
    EXACT,

    /** domain + subdomains — `google.com` matches `google.com` and `api.google.com` */
    DOMAIN,

    /** dotted namespace with `*` wildcard — `java.util.*` matches `java.util.List`; `*` matches all */
    NAMESPACE,
}

/**
 * one parsed `@grant` token: an api name with an optional scope list.
 * `inu.kv` -> Grant("inu.kv", []); `fetch(a.com,b.com)` -> Grant("fetch", ["a.com","b.com"]).
 * empty scope list == unscoped == full access to that api.
 */
data class Grant(val name: String, val scopes: List<String>)

/**
 * the resolved permission set of a plugin, built from its `@grant` directives.
 *
 * coarse gating (is api X granted at all) drives which native bindings get installed;
 * fine gating ([allows]) is checked per-call for scoped apis (rpc methods, fetch domains, jvm classes).
 */
class PluginPermissions private constructor(private val grants: List<Grant>) {
    /** distinct api names the plugin holds any grant for — drives install-time gating */
    val grantedApis: Set<String> = grants.mapTo(mutableSetOf()) { it.name }

    /** true if the plugin holds any grant (scoped or not) for [name] */
    fun has(name: String): Boolean = grantedApis.contains(name)

    /**
     * true if [name] is granted and [target] is within its scope. an unscoped grant allows any
     * target; otherwise [target] must match one of the declared scopes under [match] semantics.
     */
    fun allows(name: String, target: String, match: ScopeMatch): Boolean {
        val matching = grants.filter { it.name == name }
        if (matching.isEmpty()) return false
        if (matching.any { it.scopes.isEmpty() }) return true
        return matching.any { g -> g.scopes.any { scopeMatches(it, target, match) } }
    }

    companion object {
        fun parse(tokens: List<String>): PluginPermissions =
            PluginPermissions(tokens.mapNotNull { parseGrant(it) })

        fun parseGrant(token: String): Grant? {
            val t = token.trim()
            if (t.isEmpty()) return null
            val open = t.indexOf('(')
            if (open < 0) return Grant(t, emptyList())
            val name = t.substring(0, open).trim()
            if (name.isEmpty()) return null
            if (!t.endsWith(")")) return Grant(name, emptyList())
            val scopes = t.substring(open + 1, t.length - 1)
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            return Grant(name, scopes)
        }

        private fun scopeMatches(scope: String, target: String, match: ScopeMatch): Boolean = when (match) {
            ScopeMatch.EXACT -> scope == target
            ScopeMatch.DOMAIN -> target == scope || target.endsWith(".$scope")
            ScopeMatch.NAMESPACE -> when {
                scope == "*" -> true
                scope.endsWith(".*") -> target.startsWith(scope.dropLast(1))
                else -> scope == target
            }
        }
    }
}
