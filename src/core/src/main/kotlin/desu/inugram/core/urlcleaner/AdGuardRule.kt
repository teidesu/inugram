package desu.inugram.core.urlcleaner

/**
 * Parsed AdGuard-style `$removeparam` rule.
 *
 * Supported syntax (best-effort subset of AdGuard rule language):
 *   $removeparam=name
 *   $removeparam=/regex/[i]
 *   $removeparam=~name                       (inverted: keep only matching, drop rest)
 *   $removeparam=~/regex/
 *   ||host^$removeparam=...
 *   ||host.tld.*^$removeparam=...            (TLD wildcard)
 *   ||host^/path*$removeparam=...
 *   /url-regex/$removeparam=...              (full-URL regex pattern)
 *   @@...$removeparam[=...]                  (allowlist / exception)
 *   ,domain=a.com|b.com|~c.com
 *   ,denyallow=a.com|b.com                   (excluded domains; upstream's preferred
 *                                             replacement for a pile of @@ exceptions)
 *
 * Resource-type modifiers are interpreted in the context of a top-level link click:
 *   - accepted: `document`, `main_frame`, `all`, any negated type (`~image`), `third-party`,
 *               `important`
 *   - drop rule: non-document positive types (xmlhttprequest, image, font, script, …),
 *                `first-party`, `app=`, `header=`, `method=`, `replace=`, etc.
 */
internal data class AdGuardRule(
    val exception: Boolean,
    val urlMatcher: UrlMatcher,
    val paramMatcher: ParamMatcher,
    val domainInclude: List<String>,
    val domainExclude: List<String>,
)

internal sealed class UrlMatcher {
    object Any : UrlMatcher()

    /**
     * `||host^...`. [hostRegex] is non-null when [host] contains a `*` wildcard; otherwise
     * a plain suffix-match is used. [pathRegex] matches against the URL's path+query.
     */
    data class Host(val host: String, val hostRegex: Regex?, val pathRegex: Regex?) : UrlMatcher()

    /** `/regex/` — matches against the full URL. */
    data class FullRegex(val regex: Regex) : UrlMatcher()
}

internal sealed class ParamMatcher {
    object All : ParamMatcher()
    data class Literal(val name: String) : ParamMatcher()
    data class Pattern(val regex: Regex) : ParamMatcher()
    data class Inverted(val inner: ParamMatcher) : ParamMatcher()

    /**
     * AdGuard semantics: literal matches the param name; regex matches the full
     * `name=value` pair (or bare `name` for valueless params); inverted negates.
     */
    fun matches(name: String, value: String?): Boolean = when (this) {
        All -> true
        is Literal -> this.name == name
        is Pattern -> regex.containsMatchIn(if (value == null) name else "$name=$value")
        is Inverted -> !inner.matches(name, value)
    }
}

internal object AdGuardRuleParser {
    private val NON_DOCUMENT_TYPES = setOf(
        "xmlhttprequest", "script", "image", "stylesheet", "font", "media", "object",
        "subdocument", "websocket", "webrtc", "ping", "other", "popup", "object-subrequest",
        "csp", "webtransport",
    )
    private val DOCUMENT_TYPES = setOf("document", "main_frame", "main-frame", "all")
    private val NEUTRAL_MODIFIERS = setOf("important", "third-party", "match-case")
    private val DISQUALIFYING_MODIFIER_PREFIXES = listOf(
        "app=", "header=", "method=", "replace=", "csp=", "redirect=",
        "redirect-rule=", "rewrite=", "jsonprune", "removeheader=", "permissions=",
        "cookie=", "stealth=", "to=", "from=", "client=", "ctag=", "dnstype=", "dnsrewrite=",
    )
    private val DISQUALIFYING_MODIFIERS = setOf("first-party", "specifichide", "generichide", "elemhide")

    private val REGEX_META = "\\.+?()[]{}|^\$".toSet()
    private val WILDCARD_META = "\\.+?()[]{}|".toSet()

    fun parse(rawLine: String): AdGuardRule? {
        val line = rawLine.trim()
        if (line.isEmpty()) return null
        if (line.startsWith("!") || line.startsWith("[")) return null
        if (line.contains("##") || line.contains("#@#") || line.contains("#%#") || line.contains("#$#")) return null

        val exception = line.startsWith("@@")
        val body = if (exception) line.substring(2) else line

        val dollar = findModifierDollar(body) ?: return null
        val pattern = body.substring(0, dollar)
        val mods = splitModifiers(body.substring(dollar + 1))

        var paramSpec: String? = null
        var hasRemoveparam = false
        var domainSpec: String? = null
        var denyallowSpec: String? = null
        var sawDocumentType = false
        var sawNonDocumentPositiveType = false

        for (m in mods) {
            when {
                m == "removeparam" -> { hasRemoveparam = true; paramSpec = null }
                m.startsWith("removeparam=") -> {
                    hasRemoveparam = true
                    paramSpec = m.substring("removeparam=".length)
                }
                m.startsWith("domain=") -> domainSpec = m.substring("domain=".length)
                m.startsWith("denyallow=") -> denyallowSpec = m.substring("denyallow=".length)
                m in NEUTRAL_MODIFIERS -> Unit
                m in DOCUMENT_TYPES -> sawDocumentType = true
                m in NON_DOCUMENT_TYPES -> sawNonDocumentPositiveType = true
                m.startsWith("~") -> {
                    if (m.substring(1) in DISQUALIFYING_MODIFIERS) return null
                }
                m in DISQUALIFYING_MODIFIERS -> return null
                DISQUALIFYING_MODIFIER_PREFIXES.any { m.startsWith(it) } -> return null
                else -> return null
            }
        }
        if (!hasRemoveparam) return null
        if (sawNonDocumentPositiveType && !sawDocumentType) return null

        val paramMatcher = parseParamSpec(paramSpec) ?: return null
        val urlMatcher = parseUrlPattern(pattern) ?: return null
        val (incl, excl) = parseDomains(domainSpec)
        // `denyallow` scopes the request URL's domain rather than the referrer's, but for a
        // top-level link those are the same — so it collapses into plain excludes. It has no
        // `~` form; parseDomains' exclude half is always empty here.
        val denyallow = parseDomains(denyallowSpec).first
        return AdGuardRule(exception, urlMatcher, paramMatcher, incl, excl + denyallow)
    }

    private fun parseParamSpec(spec: String?): ParamMatcher? {
        if (spec == null) return ParamMatcher.All
        if (spec.startsWith("~")) {
            val inner = parseParamSpec(spec.substring(1)) ?: return null
            if (inner is ParamMatcher.All) return null
            return ParamMatcher.Inverted(inner)
        }
        if (spec.startsWith("/")) {
            val endSlash = spec.lastIndexOf('/')
            if (endSlash <= 0) return null
            val body = spec.substring(1, endSlash)
            val opts = if ('i' in spec.substring(endSlash + 1)) setOf(RegexOption.IGNORE_CASE) else emptySet()
            return runCatching { ParamMatcher.Pattern(Regex(body, opts)) }.getOrNull()
        }
        return ParamMatcher.Literal(spec)
    }

    /** AdGuard uses `$` as modifier separator; `$` inside `/.../` regex pattern doesn't count. */
    private fun findModifierDollar(s: String): Int? {
        var inRegex = false
        var lastDollar = -1
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> i++
                c == '/' -> {
                    val prev = if (i > 0) s[i - 1] else ' '
                    inRegex = if (inRegex) false else (prev == '=' || prev == '$' || i == 0)
                }
                c == '$' && !inRegex -> lastDollar = i
            }
            i++
        }
        return lastDollar.takeIf { it >= 0 }
    }

    private fun splitModifiers(s: String): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var inRegex = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> { buf.append(c).append(s[i + 1]); i += 2; continue }
                c == '/' -> {
                    val prev = buf.lastOrNull() ?: ' '
                    inRegex = if (inRegex) false else (prev == '=')
                    buf.append(c)
                }
                c == ',' && !inRegex -> { out += buf.toString(); buf.setLength(0) }
                else -> buf.append(c)
            }
            i++
        }
        if (buf.isNotEmpty()) out += buf.toString()
        return out
    }

    private fun parseUrlPattern(pattern: String): UrlMatcher? {
        if (pattern.isEmpty() || pattern == "*") return UrlMatcher.Any

        if (pattern.length >= 2 && pattern.startsWith("/") && pattern.endsWith("/")) {
            return runCatching { UrlMatcher.FullRegex(Regex(pattern.substring(1, pattern.length - 1))) }.getOrNull()
        }

        if (!pattern.startsWith("||")) return null
        val body = pattern.substring(2)
        val hostEnd = body.indexOfAny(charArrayOf('^', '/')).let { if (it < 0) body.length else it }
        val host = body.substring(0, hostEnd)
        if (host.isEmpty()) return null
        val rest = body.substring(hostEnd)

        val hostRegex = if ('*' in host) buildHostRegex(host) else null
        val pathRegex = if (rest.isEmpty()) null else wildcardToRegex(rest)
        return UrlMatcher.Host(host.lowercase(), hostRegex, pathRegex)
    }

    /** Compile a wildcard host like `amazon.*` to a regex that matches host (optionally w/ subdomain). */
    private fun buildHostRegex(host: String): Regex = buildString {
        append("^(?:.+\\.)?")
        for (c in host) when {
            c == '*' -> append("[^/]+")
            c in REGEX_META -> append('\\').append(c)
            else -> append(c.lowercaseChar())
        }
        append('$')
    }.toRegex()

    private fun wildcardToRegex(glob: String): Regex = buildString {
        for (c in glob) when {
            c == '*' -> append(".*")
            c == '^' -> append("(?:[^A-Za-z0-9._%~-]|$)")
            c in WILDCARD_META -> append('\\').append(c)
            else -> append(c)
        }
    }.toRegex()

    private fun parseDomains(spec: String?): Pair<List<String>, List<String>> {
        if (spec.isNullOrEmpty()) return emptyList<String>() to emptyList()
        val incl = mutableListOf<String>()
        val excl = mutableListOf<String>()
        for (raw in spec.split('|')) {
            val d = raw.trim()
            if (d.isEmpty()) continue
            if (d.startsWith("~")) excl += d.substring(1).lowercase()
            else incl += d.lowercase()
        }
        return incl to excl
    }
}
