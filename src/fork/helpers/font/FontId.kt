package desu.inugram.helpers.font


/**
 * Identity of a single font in the roster / a font stack. Serialized to/from a **token** string for
 * persistence (the `index.json` roster + the [FontConfig.FontMode] JSON). Token format:
 * - built-in editor font → the bare `PaintTypeface` key (e.g. `roboto`)
 * - imported family      → `font:<id>`
 * - device system font   → `sys:<name>`
 */
sealed interface FontId {
    data class Builtin(val key: String) : FontId
    data class System(val name: String) : FontId
    data class Family(val id: String) : FontId

    fun token(): String = when (this) {
        is Builtin -> key
        is System -> SYSTEM_PREFIX + name
        is Family -> FAMILY_PREFIX + id
    }

    companion object {
        private const val FAMILY_PREFIX = "font:"
        private const val SYSTEM_PREFIX = "sys:"

        /**
         * Parses a roster / stack token. A bare string is a built-in key — imported families always carry
         * the `font:` prefix here (their ids are base36 timestamps, never bare in this form).
         */
        fun parse(token: String): FontId = when {
            token.startsWith(FAMILY_PREFIX) -> Family(token.substring(FAMILY_PREFIX.length))
            token.startsWith(SYSTEM_PREFIX) -> System(token.substring(SYSTEM_PREFIX.length))
            else -> Builtin(token)
        }

        // parse, but also supports legacy format where builtins werent prefixed
        fun parseWithLegacyCompat(id: String): FontId = when {
            id.startsWith(FAMILY_PREFIX) -> Family(id.substring(FAMILY_PREFIX.length))
            id.startsWith(SYSTEM_PREFIX) -> System(id.substring(SYSTEM_PREFIX.length))
            id.isEmpty() -> Family("")
            FontLibrary.isBuiltinKey(id) -> Builtin(id)
            else -> Family(id)
        }
    }
}
