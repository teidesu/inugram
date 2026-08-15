package desu.inugram.core.plugins

enum class SourceObfuscation { OBFUSCATED, MINIFIED }

/**
 * Heuristics for source a human cannot reasonably review. [SourceObfuscation.OBFUSCATED] matches
 * the signatures of common js obfuscators (obfuscator.io hex identifiers, Dean Edwards' packer,
 * jsfuck, jjencode/aaencode); [SourceObfuscation.MINIFIED] catches the general "everything on one
 * line" shape minifiers produce. Advisory only - a warning in the UI, never a gate.
 */
object ObfuscationDetector {
    // no word boundary: javascript-obfuscator prefixes globals per-file (`a0_0x13f8f1`)
    private val HEX_IDENTIFIERS = Regex("""_0x[0-9a-fA-F]{2,}""")
    private val HEX_LITERALS = Regex("""0x[0-9a-fA-F]+""")
    private val ASCII_ESCAPES = Regex("""\\x[0-9a-fA-F]{2}""")
    private val PACKER = Regex("""eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e""")
    private val JJENCODE = Regex("""\$=~\[]""")
    private const val AAENCODE_MARKER = "ﾟωﾟ"
    private val JSFUCK_ALPHABET = setOf('[', ']', '(', ')', '!', '+')

    // terser output for even a small real plugin is a single ~400+ char line once strings are stripped
    private const val MIN_MINIFIED_CODE_CHARS = 400
    private const val MINIFIED_MEDIAN_LINE_LENGTH = 250
    private const val MINIFIED_MAX_CODE_LINE_LENGTH = 1000

    fun detect(source: String): SourceObfuscation? {
        if (isObfuscated(source)) return SourceObfuscation.OBFUSCATED
        if (isMinified(source)) return SourceObfuscation.MINIFIED
        return null
    }

    private fun isObfuscated(source: String): Boolean {
        if (PACKER.containsMatchIn(source)) return true
        if (HEX_IDENTIFIERS.findAll(source).take(10).count() >= 10) return true
        if (source.contains("while(!![])")) return true
        if (countOccurrences(source, "!![]") >= 5) return true
        if (JJENCODE.containsMatchIn(source) || source.contains(AAENCODE_MARKER)) return true
        if (isJsfuck(source)) return true
        // javascript-obfuscator with non-hex identifier naming still addresses its string array
        // by hex index and escapes plain ASCII in the array (`a[f(0x17c)]`, `'\x20'`); either
        // alone is legitimate (lookup tables; binary strings), together they aren't
        val asciiEscapes = ASCII_ESCAPES.findAll(source).count()
        if (asciiEscapes >= 20) return true
        if (asciiEscapes >= 3 && HEX_LITERALS.findAll(source).take(15).count() >= 15) return true
        return false
    }

    private fun isJsfuck(source: String): Boolean {
        var symbols = 0
        var code = 0
        for (c in source) {
            if (c.isWhitespace()) continue
            code++
            if (c in JSFUCK_ALPHABET) symbols++
        }
        return code >= 500 && symbols.toDouble() / code > 0.9
    }

    /**
     * measures only *code*: string literal contents and comments are stripped first, so embedded
     * base64 assets or other long strings never read as minification, while a long line of actual
     * code always does - even one hidden in the middle of an otherwise readable file.
     */
    private fun isMinified(source: String): Boolean {
        val lines = stripStringsAndComments(source).lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) return false
        if (lines.sumOf { it.length } < MIN_MINIFIED_CODE_CHARS) return false
        if (lines.size <= 3) return true
        if (lines.any { it.length > MINIFIED_MAX_CODE_LINE_LENGTH }) return true
        val median = lines.map { it.length }.sorted()[lines.size / 2]
        return median > MINIFIED_MEDIAN_LINE_LENGTH
    }

    /**
     * keeps quotes and newlines, drops everything between them. Regex literals aren't understood:
     * a quote inside one opens a phantom string that swallows code until the next quote, which only
     * under-counts code - the failure direction that never flags a readable file.
     */
    private fun stripStringsAndComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            val next = if (i + 1 < n) source[i + 1] else '\u0000'
            when {
                c == '/' && next == '/' -> {
                    while (i < n && source[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    i += 2
                    while (i < n && !(source[i] == '*' && i + 1 < n && source[i + 1] == '/')) {
                        if (source[i] == '\n') out.append('\n')
                        i++
                    }
                    i = minOf(i + 2, n)
                }
                c == '"' || c == '\'' || c == '`' -> {
                    out.append(c)
                    i++
                    while (i < n && source[i] != c) {
                        if (source[i] == '\\') i++
                        else if (source[i] == '\n') out.append('\n')
                        i++
                    }
                    if (i < n) {
                        out.append(c)
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = 0
        while (true) {
            index = haystack.indexOf(needle, index)
            if (index < 0) return count
            count++
            index += needle.length
        }
    }
}
