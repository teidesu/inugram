package desu.inugram.helpers

import java.io.File
import java.io.RandomAccessFile

object SfntParser {
    data class RawFace(
        val ttcIndex: Int,
        val weight: Int,
        val italic: Boolean,
        val variable: Boolean,
        val wghtMin: Int,
        val wghtMax: Int,
        val family: String?,
    )

    fun parse(file: File): List<RawFace> {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                when (raf.readInt()) {
                    0x74746366 -> parseTtc(raf) // 'ttcf'
                    0x00010000, 0x4F54544F, 0x74727565 -> listOfNotNull(parseFace(raf, 0, 0))
                    else -> emptyList()
                }
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    private fun parseTtc(raf: RandomAccessFile): List<RawFace> {
        raf.readInt() // ttc version
        val n = raf.readInt()
        if (n <= 0 || n > 256) return emptyList()
        val offsets = List(n) { raf.readInt() }
        return offsets.mapIndexedNotNull { i, off ->
            try { parseFace(raf, off, i) } catch (e: Throwable) { null }
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
        if (os2Off >= 0) {
            raf.seek(os2Off.toLong() + 4) // skip version(2), xAvgCharWidth(2)
            weight = raf.readShort().toInt() and 0xffff
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
        return RawFace(ttcIndex, weight, italic, variable, wMin, wMax, family)
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
