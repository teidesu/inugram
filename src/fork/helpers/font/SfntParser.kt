package desu.inugram.helpers.font

import org.telegram.messenger.FileLog
import java.io.File
import java.io.RandomAccessFile

object SfntParser {
    private const val TAG = "InuFonts"
    enum class Script { LATIN, CYRILLIC, GREEK, ARABIC, HEBREW, CJK, KANA, HANGUL, THAI }

    data class RawFace(
        val ttcIndex: Int,
        val weight: Int,
        val italic: Boolean,
        val variable: Boolean,
        val wghtMin: Int,
        val wghtMax: Int,
        val family: String?,
        val scripts: Set<Script> = emptySet(),
    )

    fun parse(file: File): List<RawFace> {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                when (val magic = raf.readInt()) {
                    0x74746366 -> parseTtc(raf) // 'ttcf'
                    0x00010000, 0x4F54544F, 0x74727565 -> listOfNotNull(parseFace(raf, 0, 0))
                    else -> {
                        FileLog.d("$TAG: parse: unknown magic 0x${Integer.toHexString(magic)} in ${file.name} (size=${file.length()})")
                        emptyList()
                    }
                }
            }
        } catch (e: Throwable) {
            FileLog.e("$TAG: parse: failed for ${file.name} (size=${file.length()})", e)
            emptyList()
        }
    }

    private fun parseTtc(raf: RandomAccessFile): List<RawFace> {
        raf.readInt() // ttc version
        val n = raf.readInt()
        if (n <= 0 || n > 256) return emptyList()
        val offsets = List(n) { raf.readInt() }
        return offsets.mapIndexedNotNull { i, off ->
            try {
                parseFace(raf, off, i)
            } catch (e: Throwable) {
                FileLog.e("$TAG: parseTtc: face $i at offset $off failed", e)
                null
            }
        }
    }

    private fun parseFace(raf: RandomAccessFile, sfntOffset: Int, ttcIndex: Int): RawFace? {
        raf.seek(sfntOffset.toLong())
        val sfntVer = raf.readInt()
        if (sfntVer != 0x00010000 && sfntVer != 0x4F54544F && sfntVer != 0x74727565) return null
        val numTables = raf.readShort().toInt() and 0xffff
        raf.skipBytes(6) // searchRange, entrySelector, rangeShift

        var os2Off = -1
        var headOff = -1
        var fvarOff = -1
        var nameOff = -1
        for (i in 0 until numTables) {
            val tag = raf.readInt()
            raf.readInt() // checksum
            val off = raf.readInt()
            raf.readInt() // length
            when (tag) {
                0x4F532F32 -> os2Off = off // 'OS/2'
                0x68656164 -> headOff = off // 'head'
                0x66766172 -> fvarOff = off // 'fvar'
                0x6E616D65 -> nameOff = off // 'name'
            }
        }

        var weight = 400
        var italic = false
        var scripts = emptySet<Script>()
        if (os2Off >= 0) {
            raf.seek(os2Off.toLong() + 4) // skip version(2), xAvgCharWidth(2)
            weight = raf.readShort().toInt() and 0xffff
            raf.seek(os2Off.toLong() + 42) // ulUnicodeRange1
            val r1 = raf.readInt()
            val r2 = raf.readInt() // ulUnicodeRange2
            scripts = unicodeRangeScripts(r1, r2)
            raf.seek(os2Off.toLong() + 62) // fsSelection
            val fsSel = raf.readShort().toInt() and 0xffff
            italic = (fsSel and 0x01) != 0
        } else if (headOff >= 0) {
            raf.seek(headOff.toLong() + 44) // macStyle
            val macStyle = raf.readShort().toInt() and 0xffff
            italic = (macStyle and 0x02) != 0
            if ((macStyle and 0x01) != 0) weight = 700
        }

        var variable = false
        var wMin = weight
        var wMax = weight
        if (fvarOff >= 0) {
            raf.seek(fvarOff.toLong())
            raf.skipBytes(4) // major+minor
            val axesArrayOffset = raf.readShort().toInt() and 0xffff
            raf.skipBytes(2) // reserved
            val axisCount = raf.readShort().toInt() and 0xffff
            val axisSize = raf.readShort().toInt() and 0xffff
            for (i in 0 until axisCount) {
                val axisStart = fvarOff.toLong() + axesArrayOffset + i.toLong() * axisSize
                raf.seek(axisStart)
                val axisTag = raf.readInt()
                val minVal = raf.readInt()
                raf.readInt() // default
                val maxVal = raf.readInt()
                if (axisTag == 0x77676874) { // 'wght'
                    variable = true
                    wMin = minVal shr 16
                    wMax = maxVal shr 16
                }
            }
        }

        val family = if (nameOff >= 0) readFamily(raf, nameOff) else null
        return RawFace(ttcIndex, weight, italic, variable, wMin, wMax, family, scripts)
    }

    // OS/2 ulUnicodeRange bit → script group. r1 = bits 0-31, r2 = bits 32-63 (CJK/kana/hangul live here).
    private fun unicodeRangeScripts(r1: Int, r2: Int): Set<Script> {
        fun b1(bit: Int) = (r1 ushr bit) and 1 != 0
        fun b2(bit: Int) = (r2 ushr (bit - 32)) and 1 != 0
        val out = HashSet<Script>()
        if (b1(0) || b1(1)) out.add(Script.LATIN)
        if (b1(7)) out.add(Script.GREEK)
        if (b1(9)) out.add(Script.CYRILLIC)
        if (b1(11)) out.add(Script.HEBREW)
        if (b1(13)) out.add(Script.ARABIC)
        if (b1(24)) out.add(Script.THAI)
        if (b2(59) || b2(48)) out.add(Script.CJK) // CJK Unified Ideographs / Symbols
        if (b2(49) || b2(50)) out.add(Script.KANA) // Hiragana / Katakana
        if (b2(52) || b2(56)) out.add(Script.HANGUL) // Hangul Jamo / Syllables
        return out
    }

    private fun readFamily(raf: RandomAccessFile, nameOff: Int): String? {
        raf.seek(nameOff.toLong())
        raf.readShort() // format
        val count = raf.readShort().toInt() and 0xffff
        val stringOffset = raf.readShort().toInt() and 0xffff

        var bestPriority = Int.MAX_VALUE
        var bestLen = 0
        var bestOff = 0
        for (i in 0 until count) {
            val platformID = raf.readShort().toInt() and 0xffff
            val encodingID = raf.readShort().toInt() and 0xffff
            raf.readShort() // languageID
            val nameID = raf.readShort().toInt() and 0xffff
            val length = raf.readShort().toInt() and 0xffff
            val offset = raf.readShort().toInt() and 0xffff
            if (nameID != 1 && nameID != 16) continue
            // prefer typographic family (16) over family (1); prefer Windows Unicode
            val p = when {
                platformID == 3 && (encodingID == 1 || encodingID == 10) -> if (nameID == 16) 0 else 2
                platformID == 0 -> if (nameID == 16) 1 else 3
                else -> 100
            }
            if (p < bestPriority) {
                bestPriority = p
                bestLen = length
                bestOff = offset
            }
        }
        if (bestPriority == Int.MAX_VALUE || bestLen <= 0) return null
        return try {
            raf.seek(nameOff.toLong() + stringOffset + bestOff)
            val bytes = ByteArray(bestLen)
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_16BE).trim().takeIf { it.isNotEmpty() }
        } catch (e: Throwable) {
            null
        }
    }
}
