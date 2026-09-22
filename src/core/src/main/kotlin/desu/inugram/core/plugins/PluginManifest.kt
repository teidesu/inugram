package desu.inugram.core.plugins

/**
 * Parsed InuPlugin-style metadata header of a plugin. v0 only consumes the descriptive directives;
 * [grants], [pluginApi], [platform] are parsed already so the engine layer can gate on them without
 * re-parsing.
 *
 * Nothing here keys storage: that is [PluginInstall.id], minted at install time. [id] is only
 * what decides whether a second file is an update of an installed plugin or a plugin of its own.
 */
data class PluginManifest(
    val name: String,
    val author: String?,
    /** `@id` as written, once it is a single printable token; null leaves [id] to derive one */
    val declaredId: String?,
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
    /**
     * Identifies updates, compared verbatim. Prefer an explicit `@id`, usually a reverse domain
     * name, to preserve identity across renames.
     *
     * Without `@id`, derive from both [author] and [name]. Changing either creates a separate
     * plugin. Both are required to avoid unrelated same-name plugins replacing each other.
     *
     * Storage uses the separate install ID and survives renames.
     */
    val id: String? by lazy {
        if (declaredId != null) return@lazy declaredId
        val authorSlug = slug(author ?: return@lazy null)
        val nameSlug = slug(name)
        if (authorSlug.isEmpty() || nameSlug.isEmpty()) null else "$authorSlug.$nameSlug"
    }

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

    companion object {
        /**
         * Lowercase words of letters and digits joined by dashes. Keep in sync with `@inugram/cli`
         * so CLI-written IDs match IDs derived for older plugins without `@id`.
         */
        fun slug(value: String): String = buildString {
            var gap = false
            for (ch in value.lowercase()) {
                if (!ch.isLetter() && !ch.isDigit()) {
                    gap = true
                    continue
                }
                if (gap && isNotEmpty()) append('-')
                gap = false
                append(ch)
            }
        }

        /** an `@id` is compared verbatim, so it may hold nothing that is invisible or ambiguous */
        fun readDeclaredId(value: String?): String? = value
            ?.takeIf { it.isNotEmpty() && it.none { ch -> ch.isWhitespace() || ch.isISOControl() } }
    }
}

class PluginManifestException(message: String) : Exception(message)

object PluginManifestParser {
    private val START = Regex("""^==InuPlugin==$""")
    private val END = Regex("""^==/InuPlugin==$""")
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

        if (!inBlock) throw PluginManifestException("missing ==InuPlugin== metadata block")
        if (!sawEnd) throw PluginManifestException("unterminated metadata block (missing ==/InuPlugin==)")

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
            author = raw["author"]?.firstOrNull()?.takeIf { it.isNotBlank() },
            declaredId = PluginManifest.readDeclaredId(raw["id"]?.firstOrNull()),
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
