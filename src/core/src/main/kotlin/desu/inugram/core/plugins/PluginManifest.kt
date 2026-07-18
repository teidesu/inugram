package desu.inugram.core.plugins

/**
 * Parsed userscript-style metadata header of a plugin. v0 only consumes the descriptive/identity
 * directives; [grants], [pluginApi], [platform] are parsed already so the engine layer can gate on
 * them without re-parsing.
 */
data class PluginManifest(
    val name: String,
    val namespace: String?,
    val author: String?,
    val version: String?,
    val description: String?,
    val localizedDescriptions: Map<String, String>,
    val icon: String?,
    val grants: List<String>,
    val pluginApi: Int?,
    val platform: String?,
    /** every directive, base key lowercased, in declaration order; backs `inu.info().header` */
    val raw: Map<String, List<String>>,
) {
    /** stable identity for ordering/enable persistence — userscript namespace+name convention */
    val id: String get() = if (namespace.isNullOrBlank()) name else "$namespace/$name"

    /** localized description with language fallback (exact → primary subtag → base) */
    fun description(lang: String?): String? {
        if (lang == null) return description
        val key = lang.lowercase()
        localizedDescriptions[key]?.let { return it }
        val primary = key.substringBefore('-')
        localizedDescriptions.entries
            .firstOrNull { it.key.substringBefore('-') == primary }
            ?.let { return it.value }
        return description
    }
}

class PluginManifestException(message: String) : Exception(message)

object PluginManifestParser {
    private val START = Regex("""^==UserScript==$""")
    private val END = Regex("""^==/UserScript==$""")
    private val DIRECTIVE = Regex("""^@(\S+)(?:\s+(.*))?$""")

    /** splits on commas that are not inside `(...)`, so scoped grants like `fetch(a,b)` stay one token */
    private fun splitTopLevelCommas(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var depth = 0
        for (c in s) {
            when (c) {
                '(' -> { depth++; sb.append(c) }
                ')' -> { if (depth > 0) depth--; sb.append(c) }
                ',' -> if (depth == 0) { out.add(sb.toString()); sb.setLength(0) } else sb.append(c)
                else -> sb.append(c)
            }
        }
        out.add(sb.toString())
        return out
    }

    fun parseOrNull(source: String): PluginManifest? = try {
        parse(source)
    } catch (_: PluginManifestException) {
        null
    }

    fun parse(source: String): PluginManifest {
        val raw = LinkedHashMap<String, MutableList<String>>()
        var inBlock = false
        var sawEnd = false

        for (rawLine in source.lineSequence()) {
            val line = rawLine.trim()
            if (!line.startsWith("//")) {
                // a non-comment line ends the leading metadata region
                if (inBlock) continue
                if (raw.isNotEmpty() || sawEnd) break
                continue
            }
            val body = line.removePrefix("//").trim()
            when {
                START.matches(body) -> inBlock = true
                END.matches(body) -> { sawEnd = true; break }
                inBlock -> {
                    val m = DIRECTIVE.matchEntire(body) ?: continue
                    val token = m.groupValues[1].lowercase()
                    val value = m.groupValues[2].trim()
                    raw.getOrPut(token) { mutableListOf() }.add(value)
                }
            }
        }

        if (!inBlock) throw PluginManifestException("missing ==UserScript== metadata block")
        if (!sawEnd) throw PluginManifestException("unterminated metadata block (missing ==/UserScript==)")

        val name = raw["name"]?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: throw PluginManifestException("missing @name")

        val localizedDescriptions = raw.entries
            .filter { it.key.startsWith("description:") }
            .associate { it.key.removePrefix("description:") to it.value.first() }

        val grants = raw["grant"].orEmpty()
            .flatMap { splitTopLevelCommas(it) }
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("none", ignoreCase = true) }
            .distinct()

        return PluginManifest(
            name = name,
            namespace = raw["namespace"]?.firstOrNull()?.takeIf { it.isNotBlank() },
            author = raw["author"]?.firstOrNull()?.takeIf { it.isNotBlank() },
            version = raw["version"]?.firstOrNull()?.takeIf { it.isNotBlank() },
            description = raw["description"]?.firstOrNull()?.takeIf { it.isNotBlank() },
            localizedDescriptions = localizedDescriptions,
            icon = raw["icon"]?.firstOrNull()?.takeIf { it.isNotBlank() },
            grants = grants,
            pluginApi = raw["plugin-api"]?.firstOrNull()?.toIntOrNull(),
            platform = raw["platform"]?.firstOrNull()?.takeIf { it.isNotBlank() },
            raw = raw,
        )
    }
}
