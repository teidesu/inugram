package desu.inugram.core.text

object CombiningMarkGuard {
    const val MAX_MARKS_PER_CLUSTER = 512

    private const val ZWNJ = '\u200C'

    @JvmStatic
    @JvmOverloads
    fun prepare(text: String, maxMarksPerCluster: Int = MAX_MARKS_PER_CLUSTER): String {
        var out: CharArray? = null
        var run = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val width = Character.charCount(cp)
            val neutralise = when {
                cp == 0x2066 || cp == 0x2067 -> {
                    run = 0
                    true
                }

                isMark(cp) -> ++run > maxMarksPerCluster
                else -> {
                    run = 0
                    false
                }
            }
            if (neutralise) {
                if (out == null) out = text.toCharArray()
                for (k in 0 until width) out[i + k] = ZWNJ
            }
            i += width
        }
        return if (out == null) text else String(out)
    }

    private fun isMark(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
        else -> false
    }
}
