package desu.inugram.core.plugins

enum class ScopeMatch {
    EXACT,

    DOMAIN,

    NAMESPACE,
}

data class Grant(val name: String, val scopes: List<String>)

class PluginPermissions private constructor(private val grants: List<Grant>) {
    private val byName: Map<String, List<Grant>> = grants.groupBy { it.name }

    val grantedApis: Set<String> = byName.keys

    private val unscopedApis: Set<String> =
        byName.filterValues { list -> list.any { it.scopes.isEmpty() } }.keys

    fun has(name: String): Boolean = grantedApis.contains(name)

    /** what the engine is handed: `name, scope` pairs, an unscoped grant's scope being empty */
    fun toPairs(): List<String> = grants.flatMap { grant ->
        if (grant.scopes.isEmpty()) listOf(grant.name, "") else grant.scopes.flatMap { listOf(grant.name, it) }
    }

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

        /** A malformed scope must not become an unscoped grant. */
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
