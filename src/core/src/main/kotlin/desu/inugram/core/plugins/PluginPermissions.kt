package desu.inugram.core.plugins

enum class ScopeMatch {
    /** literal equality - rpc method names, update types */
    EXACT,

    /** domain + subdomains — `google.com` matches `google.com` and `api.google.com` */
    DOMAIN,

    /** dotted namespace with `*` wildcard — `java.util.*` matches `java.util.List`; `*` matches all */
    NAMESPACE,
}

/** `kv` -> Grant("kv", []); `fetch(a.com,b.com)` -> Grant("fetch", ["a.com","b.com"]). empty scope list == unscoped */
data class Grant(val name: String, val scopes: List<String>)

/**
 * coarse gating (is api X granted at all) drives which native bindings get installed; fine gating
 * ([allows]) is checked per call for scoped apis.
 */
class PluginPermissions private constructor(private val grants: List<Grant>) {
    // [allows] is on the per-update and per-request paths, so the grouping is done here rather than as a `filter` per call, which is an ArrayList per call on `globalQueue`
    private val byName: Map<String, List<Grant>> = grants.groupBy { it.name }

    val grantedApis: Set<String> = byName.keys

    private val unscopedApis: Set<String> =
        byName.filterValues { list -> list.any { it.scopes.isEmpty() } }.keys

    fun has(name: String): Boolean = grantedApis.contains(name)

    fun allows(name: String, target: String, match: ScopeMatch): Boolean {
        if (unscopedApis.contains(name)) return true
        val matching = byName[name] ?: return false
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
            if (!t.endsWith(")")) return null
            if (t.substring(open + 1, t.length - 1).split(',').all { it.isBlank() }) return null
            val scopes = t.substring(open + 1, t.length - 1)
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            return Grant(name, scopes)
        }

        /**
         * both shapes used to widen to the unscoped grant, which is the opposite of what they read
         * like: `fetch(evil.com` (never closed) became every domain, and `invokeRpc()` sailed past
         * the takeover screen that rejects `invokeRpc(auth.exportLoginToken)`. A token with no
         * parens at all is the real unscoped form and stays valid.
         */
        fun isMalformed(token: String): Boolean {
            val t = token.trim()
            if (t.isEmpty()) return false
            val open = t.indexOf('(')
            if (open < 0) return false
            if (t.substring(0, open).trim().isEmpty()) return true
            if (!t.endsWith(")")) return true
            return t.substring(open + 1, t.length - 1).split(',').all { it.isBlank() }
        }

        private fun scopeMatches(scope: String, target: String, match: ScopeMatch): Boolean = when (match) {
            ScopeMatch.EXACT -> scope == target
            // case-insensitive because dns is: `fetch(Example.com)` naming a host the resolver hands back lowercased would be a scope that can never match
            ScopeMatch.DOMAIN -> target.equals(scope, ignoreCase = true) ||
                target.endsWith(".$scope", ignoreCase = true)
            ScopeMatch.NAMESPACE -> when {
                scope == "*" -> true
                scope.endsWith(".*") -> target.startsWith(scope.dropLast(1))
                else -> scope == target
            }
        }
    }
}
