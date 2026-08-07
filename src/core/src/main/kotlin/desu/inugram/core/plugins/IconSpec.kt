package desu.inugram.core.plugins

/**
 * The host half of the icon-spec rules `ui/icons.rs` states (`is_resource_name`/`check_svg`).
 *
 * Rust decides which error a plugin sees and refuses a bad spec where it is minted and again
 * wherever one is read, but a spec reaches the host inside a JSON document the engine serialized,
 * so the host must not treat "rust validated this" as a property of the bytes it received. Both
 * rules exist because of what the host does with the value: `Resources.getIdentifier` also accepts
 * a qualified `package:type/name`, and an svg is handed to the platform's own xml reader, where a
 * document type declaration is the one construct that names external files or expands to more of
 * itself.
 */
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
