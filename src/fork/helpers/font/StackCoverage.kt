package desu.inugram.helpers.font

import desu.inugram.helpers.font.SfntParser.Script

/** Whether a font (or a whole stack) provides each style natively vs. relying on synthesis. */
data class StyleCoverage(val regular: Boolean, val bold: Boolean, val upright: Boolean, val italic: Boolean) {
    infix fun or(o: StyleCoverage) =
        StyleCoverage(regular || o.regular, bold || o.bold, upright || o.upright, italic || o.italic)
}

/**
 * Stack-editor coverage analysis for a draft primary+fallbacks stack — pure policy on top of
 * [FontHelper]'s per-token font data. Consumed only by the font-stack settings page (warnings).
 */
internal object StackCoverage {
    /** Which styles the whole stack provides; Default/System primaries are assumed full. */
    fun style(primary: String?, fallbacks: List<String>): StyleCoverage {
        if (primary == null || primary == FontConfig.SYSTEM_STACK_ID) return StyleCoverage(true, true, true, true)
        var c = StyleCoverage(false, false, false, false)
        for (tok in listOf(primary) + fallbacks) FontLibrary.getStyleCoverageFor(FontId.parse(tok))?.let { c = c or it }
        return c
    }

    /**
     * Declared script coverage of the explicit stack (primary + custom fallbacks), ignoring the OS
     * fallback. Null when undeterminable (Default/System primary). Parses font files — off-main-thread.
     */
    fun scripts(primary: String?, fallbacks: List<String>): Set<Script>? {
        if (primary == null || primary == FontConfig.SYSTEM_STACK_ID) return null
        val out = HashSet<Script>()
        for (tok in listOf(primary) + fallbacks) FontLibrary.getScriptCoverageFor(FontId.parse(tok))?.let { out.addAll(it) }
        return out
    }
}
