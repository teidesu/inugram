package desu.inugram.core.plugins

/** Validate engine data again before Android uses it. Android accepts qualified resources and SVG external entities. */
object IconSpec {
    const val MAX_RESOURCE_NAME = 128
    const val SVG_LIMIT_BYTES = 64 * 1024

    fun isResourceName(name: String): Boolean =
        name.isNotEmpty() &&
            name.length <= MAX_RESOURCE_NAME &&
            !name[0].isDigit() &&
            name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }

    fun isSvgSource(source: String): Boolean {
        if (source.length > SVG_LIMIT_BYTES) return false
        if (!source.contains("<svg")) return false
        var at = source.indexOf("<!")
        while (at >= 0) {
            if (!source.startsWith("<!--", at)) return false
            at = source.indexOf("<!", at + 2)
        }
        return true
    }
}
