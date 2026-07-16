package desu.inugram.helpers.font

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontStyle
import android.graphics.fonts.SystemFonts
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import desu.inugram.helpers.font.FontLibrary.buildEditorRoster
import desu.inugram.helpers.font.SfntParser.Script
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.Utilities
import org.telegram.ui.Components.Paint.PaintTypeface
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Owns the editor **font roster** and the storage behind it: imported font families, discovered device
 * system fonts, the ordered roster + hidden flags, and the media-editor [PaintTypeface] list. The app's
 * font *selection* and rendering (resolve / install / preview) live in [FontHelper], which queries this
 * library for per-token font data.
 *
 * Storage layout under `filesDir/inu_fonts/`:
 * ```
 *   index.json        { "roster": [token,…], "hidden": [token,…] }   ← editor roster order + hidden flags
 *   <id>/pack.json    { "family": "…", "faces": [ {file,ttcIndex,weight,italic,variable,wghtMin,wghtMax}, … ] }
 *   <id>/f*.bin       the raw font files
 * ```
 * Roster entries are typed [FontId]s — built-ins, `font:<id>` imported families, or `sys:<name>` device
 * system fonts — serialized to/from token strings only at the storage boundary.
 */
object FontLibrary {
    private const val TAG = "InuFonts"
    private const val DIR = "inu_fonts"
    private const val INDEX = "index.json"
    private const val MANIFEST = "pack.json"
    private const val STAGING = ".staging"

    data class Face(
        val file: String,
        val ttcIndex: Int,
        val weight: Int,
        val italic: Boolean,
        val variable: Boolean,
        val wghtMin: Int,
        val wghtMax: Int,
    )

    /** One imported font family (a directory with a manifest + face files). */
    class Family(
        val id: String,
        val dir: File,
        val name: String?,
        val faces: List<Face>,
    ) {
        private val cache = HashMap<String, Typeface>()
        private var cachedScripts: Set<Script>? = null // faces are immutable, so the OS/2 scan is one-shot

        // imported faces store a name relative to [dir]; system faces store an absolute path.
        private fun faceFile(f: Face): File = File(f.file).let { if (it.isAbsolute) it else File(dir, f.file) }

        @Synchronized
        fun resolve(targetWeight: Int, targetItalic: Boolean): Typeface? {
            if (faces.isEmpty()) return null
            val cacheKey = "$targetWeight/$targetItalic"
            cache[cacheKey]?.let { return it }

            val pick = pickBestFace(targetWeight, targetItalic) ?: return null
            val file = faceFile(pick)
            if (!file.isFile) {
                FileLog.d("$TAG: resolve: face file missing: ${file.absolutePath} (family=$id)")
                return null
            }

            val base: Typeface = try {
                val builder = Typeface.Builder(file).setTtcIndex(pick.ttcIndex)
                if (pick.variable && pick.wghtMin != pick.wghtMax) {
                    val w = targetWeight.coerceIn(pick.wghtMin, pick.wghtMax)
                    builder.setFontVariationSettings("'wght' $w")
                }
                builder.build()
            } catch (e: Throwable) {
                FileLog.e("$TAG: resolve: Typeface.Builder threw for ${file.name} (family=$id)", e)
                return null
            } ?: run {
                FileLog.d("$TAG: resolve: Typeface.Builder rejected ${file.name} (family=$id, ttcIndex=${pick.ttcIndex})")
                return null
            }

            // synthesize italic / weight tweaks when the picked face doesn't match exactly
            val needSynth = (targetItalic && !pick.italic) ||
                (!pick.variable && pick.weight != targetWeight)
            // Telegram's "bold" is Medium (500); Typeface.create(weight) only emboldens at a large delta,
            // so a font whose heaviest face is lighter than the request would render bold ≈ regular. The
            // style API fake-bolds reliably — use it when an emphasis weight (≥500) can't be satisfied.
            val fakeBold = !pick.variable && targetWeight >= 500 && pick.weight < targetWeight
            val finalTf = if (needSynth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    if (fakeBold) Typeface.create(base, if (targetItalic) Typeface.BOLD_ITALIC else Typeface.BOLD)
                    else Typeface.create(base, targetWeight, targetItalic)
                } catch (_: Throwable) {
                    base
                }
            } else base

            cache[cacheKey] = finalTf
            return finalTf
        }

        private fun pickBestFace(targetWeight: Int, targetItalic: Boolean): Face? {
            val sameItalic = faces.filter { it.italic == targetItalic }
            val pool = sameItalic.ifEmpty { faces }
            if (pool.isEmpty()) return null

            pool.firstOrNull { it.variable && targetWeight in it.wghtMin..it.wghtMax }
                ?.let { return it }

            // css font-matching algorithm by weight
            val sorted = pool.sortedBy { it.weight }
            return when {
                targetWeight in 400..500 ->
                    sorted.firstOrNull { it.weight in targetWeight..500 }
                        ?: sorted.lastOrNull { it.weight < targetWeight }
                        ?: sorted.firstOrNull { it.weight > 500 }

                targetWeight < 400 ->
                    sorted.lastOrNull { it.weight <= targetWeight }
                        ?: sorted.firstOrNull { it.weight > targetWeight }

                else ->
                    sorted.firstOrNull { it.weight >= targetWeight }
                        ?: sorted.lastOrNull { it.weight < targetWeight }
            }
        }

        /**
         * (style label, typeface) per face — for the disabled face list shown in the tap menu.
         * Collapsed by label so a variable family doesn't render dozens of identical "Variable" rows.
         */
        @Synchronized
        fun faceInfos(): List<Pair<String, Typeface?>> {
            val seen = HashSet<String>()
            return faces.filter { seen.add(faceLabel(it)) }.map { f ->
                val tf = try {
                    val b = Typeface.Builder(faceFile(f)).setTtcIndex(f.ttcIndex)
                    if (f.variable && f.wghtMin != f.wghtMax) {
                        b.setFontVariationSettings("'wght' ${f.weight.coerceIn(f.wghtMin, f.wghtMax)}")
                    }
                    b.build()
                } catch (e: Throwable) {
                    FileLog.e("$TAG: faceInfos: Typeface.Builder failed for ${f.file} (family=$id)", e)
                    null
                }
                faceLabel(f) to tf
            }
        }

        private fun faceLabel(f: Face): String {
            if (f.variable && f.wghtMin != f.wghtMax) return if (f.italic) "Variable Italic" else "Variable"
            val w = when {
                f.weight <= 150 -> "Thin"
                f.weight <= 250 -> "ExtraLight"
                f.weight <= 350 -> "Light"
                f.weight <= 450 -> "Regular"
                f.weight <= 550 -> "Medium"
                f.weight <= 650 -> "SemiBold"
                f.weight <= 750 -> "Bold"
                f.weight <= 850 -> "ExtraBold"
                else -> "Black"
            }
            return when {
                f.italic && w == "Regular" -> "Italic"
                f.italic -> "$w Italic"
                else -> w
            }
        }

        /** Best-matching face as a [Font] (style pinned to the request) for use in a font stack. */
        @RequiresApi(Build.VERSION_CODES.Q)
        @Synchronized
        fun fontFor(targetWeight: Int, targetItalic: Boolean): Font? {
            val pick = pickBestFace(targetWeight, targetItalic) ?: return null
            val file = faceFile(pick)
            if (!file.isFile) {
                FileLog.d("$TAG: fontFor: face file missing: ${file.absolutePath} (family=$id)")
                return null
            }
            return try {
                val b = Font.Builder(file).setTtcIndex(pick.ttcIndex)
                if (pick.variable && pick.wghtMin != pick.wghtMax) {
                    b.setFontVariationSettings("'wght' ${targetWeight.coerceIn(pick.wghtMin, pick.wghtMax)}")
                }
                b.setWeight(targetWeight.coerceIn(1, 1000))
                b.setSlant(if (targetItalic) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT)
                b.build()
            } catch (e: Throwable) {
                FileLog.e("$TAG: fontFor: Font.Builder failed for ${file.name} (family=$id)", e)
                null
            }
        }

        /** True if no face reaches [weight] (so emphasis at that weight must be synthesized). */
        @Synchronized
        fun lacksWeight(weight: Int, italic: Boolean): Boolean {
            val pick = pickBestFace(weight, italic) ?: return false
            if (pick.variable && weight in pick.wghtMin..pick.wghtMax) return false
            return pick.weight < weight
        }

        /** Which styles the family's faces actually provide — drives the "synthesized style" warnings. */
        @Synchronized
        fun styleCoverage(): StyleCoverage {
            var regular = false
            var bold = false
            var upright = false
            var italic = false
            for (f in faces) {
                if (f.italic) italic = true else upright = true
                val lo = if (f.variable) f.wghtMin else f.weight
                val hi = if (f.variable) f.wghtMax else f.weight
                if (lo < 600) regular = true
                if (hi >= 600) bold = true
            }
            return StyleCoverage(regular, bold, upright, italic)
        }

        /** Declared script coverage (OS/2 ranges) unioned over the face files. Does file I/O. */
        @Synchronized
        fun scriptCoverage(): Set<Script> {
            cachedScripts?.let { return it }
            val out = HashSet<Script>()
            val seen = HashSet<String>()
            for (f in faces) {
                val file = faceFile(f)
                if (!seen.add(file.absolutePath) || !file.isFile) continue
                SfntParser.parse(file).forEach { out.addAll(it.scripts) }
            }
            return out.also { cachedScripts = it }
        }
    }

    private var rootDir: File? = null
    private val families = LinkedHashMap<String, Family>()

    // discovered device system fonts (keyed by family name), independent of the include-system toggle.
    private val systemFamilies = LinkedHashMap<String, Family>()

    @Volatile
    private var systemLoaded = false

    @Volatile
    private var systemLoading = false
    private var rosterFonts: MutableList<FontId> = mutableListOf()
    private var hiddenFonts: MutableSet<FontId> = HashSet()

    private val lock = Any()

    /** Loads the imported-font roster from disk. System fonts bootstrap separately (see [FontHelper.init]). */
    fun loadStorage(context: Context) {
        rootDir = File(context.filesDir, DIR).apply { mkdirs() }
        migrateLegacy()
        load()
    }

    // ---- loading / persistence -------------------------------------------------------------

    private fun load() {
        val dir = rootDir ?: return

        val discovered = LinkedHashMap<String, Family>()
        dir.listFiles()?.filter { it.isDirectory && it.name != STAGING }?.sortedBy { it.name }?.forEach { sub ->
            readFamily(sub)?.let { discovered[it.id] = it }
        }

        val (saved, savedHidden) = readIndex()
        val roster: MutableList<FontId> = saved ?: builtinIds.toMutableList()

        // reconcile: keep saved order, drop font tokens whose dir is gone, append newly-found families,
        // then append any current built-in missing from the roster (e.g. added in an app update)
        val seenFonts = HashSet<String>()
        val cleaned = ArrayList<FontId>(roster.size)
        for (tok in roster) {
            if (tok is FontId.Family) {
                if (discovered.containsKey(tok.id) && seenFonts.add(tok.id)) cleaned.add(tok)
            } else cleaned.add(tok)
        }
        for (id in discovered.keys) if (seenFonts.add(id)) cleaned.add(FontId.Family(id))
        for (key in builtinIds) if (key !in cleaned) cleaned.add(key)

        val newHidden = savedHidden.filterTo(HashSet()) { it in cleaned }
        synchronized(lock) {
            families.clear()
            rosterFonts = cleaned
            hiddenFonts = newHidden
            for (tok in cleaned) {
                (tok as? FontId.Family)?.let { discovered[it.id] }?.let { families[it.id] = it }
            }
        }
        if (saved == null || saved != cleaned || savedHidden != newHidden) saveRoster()
        FileLog.d("$TAG: load: families=${discovered.size} roster=${cleaned.size} hidden=${newHidden.size}")
    }

    private fun readFamily(sub: File): Family? {
        val mf = File(sub, MANIFEST)
        if (!mf.isFile) return null
        return try {
            val root = JSONObject(mf.readText())
            val name = root.optString("family", "").takeIf { it.isNotEmpty() }
            val arr = root.getJSONArray("faces")
            val faces = List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Face(
                    file = o.getString("file"),
                    ttcIndex = o.optInt("ttcIndex", 0),
                    weight = o.optInt("weight", 400),
                    italic = o.optBoolean("italic", false),
                    variable = o.optBoolean("variable", false),
                    wghtMin = o.optInt("wghtMin", 400),
                    wghtMax = o.optInt("wghtMax", 400),
                )
            }
            if (faces.isEmpty()) {
                FileLog.d("$TAG: readFamily: manifest has no faces in ${sub.name}")
                null
            } else Family(sub.name, sub, name, faces)
        } catch (e: Throwable) {
            FileLog.e("$TAG: readFamily: failed to read manifest in ${sub.name}", e)
            null
        }
    }

    private fun writeManifest(sub: File, faces: List<Face>, family: String?) {
        val arr = JSONArray()
        for (f in faces) {
            arr.put(JSONObject().apply {
                put("file", f.file)
                put("ttcIndex", f.ttcIndex)
                put("weight", f.weight)
                put("italic", f.italic)
                put("variable", f.variable)
                put("wghtMin", f.wghtMin)
                put("wghtMax", f.wghtMax)
            })
        }
        val root = JSONObject().apply {
            if (family != null) put("family", family)
            put("faces", arr)
        }
        File(sub, MANIFEST).writeText(root.toString())
    }

    /**
     * Some Google Fonts downloads (notably Google Sans variable) carry a reasonable line box but a wildly
     * oversized safety bbox/win box. Android can let those outer boxes leak into TextView top/bottom metrics,
     * which shifts Telegram text. Clamp only the outer boxes to a padded line box; keep hhea/typo metrics.
     */
    private fun normalizeVerticalMetrics(file: File) {
        try {
            RandomAccessFile(file, "rw").use { raf ->
                when (val magic = raf.readInt()) {
                    0x74746366 -> Unit // TTC checkSumAdjustment is per-face; skip rather than risk corrupting it.

                    0x00010000, 0x4F54544F, 0x74727565 -> {
                        if (normalizeFaceVerticalMetrics(raf, 0)) {
                            FileLog.d("$TAG: normalizeVerticalMetrics: normalized ${file.name}")
                        }
                    }

                    else -> FileLog.d("$TAG: normalizeVerticalMetrics: unknown magic 0x${Integer.toHexString(magic)} in ${file.name}")
                }
            }
        } catch (e: Throwable) {
            FileLog.e("$TAG: normalizeVerticalMetrics: failed for ${file.name}", e)
        }
    }

    private fun normalizeFaceVerticalMetrics(raf: RandomAccessFile, sfntOffset: Int): Boolean {
        raf.seek(sfntOffset.toLong())
        val sfntVer = raf.readInt()
        if (sfntVer != 0x00010000 && sfntVer != 0x4F54544F && sfntVer != 0x74727565) return false
        val numTables = raf.readShort().toInt() and 0xffff
        raf.skipBytes(6)

        var os2: SfntTable? = null
        var head: SfntTable? = null
        for (i in 0 until numTables) {
            val recordOffset = raf.filePointer
            val tag = raf.readInt()
            raf.readInt()
            val off = raf.readInt()
            val length = raf.readInt()
            when (tag) {
                0x4F532F32 -> os2 = SfntTable(recordOffset, off, length) // OS/2
                0x68656164 -> head = SfntTable(recordOffset, off, length) // head
            }
        }
        val os2Table = os2 ?: return false

        raf.seek(os2Table.offset.toLong() + 68)
        val typoAsc = raf.readShort().toInt()
        val typoDesc = raf.readShort().toInt()
        val typoGap = raf.readShort().toInt()
        raf.seek(os2Table.offset.toLong() + 74)
        val winAsc = raf.readShort().toInt() and 0xffff
        val winDesc = raf.readShort().toInt() and 0xffff
        val headTable = head
        var headYMin = 0
        var headYMax = 0
        if (headTable != null) {
            raf.seek(headTable.offset.toLong() + 38)
            headYMin = raf.readShort().toInt()
            raf.skipBytes(2)
            headYMax = raf.readShort().toInt()
        }

        val typoDescAbs = -typoDesc
        val typoTotal = typoAsc + typoDescAbs + typoGap
        val winTotal = winAsc + winDesc
        if (typoAsc <= 0 || typoDescAbs <= 0 || typoTotal <= 0) return false

        val shouldNormalizeWinBox = winTotal > typoTotal * 3 / 2 || winDesc > typoDescAbs * 2
        val headDescAbs = -headYMin
        val shouldNormalizeHeadBox = headTable != null && (headDescAbs > typoDescAbs * 2 || headYMax > typoAsc * 3 / 2)
        if (!shouldNormalizeWinBox && !shouldNormalizeHeadBox) return false

        val targetDesc = (typoDescAbs + typoTotal / 16).coerceAtMost(typoDescAbs * 2)
        val targetAsc = (typoAsc + typoTotal / 10).coerceAtMost(typoAsc * 3 / 2)
        var changed = false

        if (shouldNormalizeHeadBox && headTable != null) {
            raf.seek(headTable.offset.toLong() + 38)
            raf.writeShort((-targetDesc).coerceIn(Short.MIN_VALUE.toInt(), -1))
            raf.skipBytes(2)
            raf.writeShort(targetAsc.coerceIn(1, 0xffff))
            changed = true
        }

        if (shouldNormalizeWinBox) {
            raf.seek(os2Table.offset.toLong() + 74)
            raf.writeShort(targetAsc.coerceIn(1, 0xffff))
            raf.writeShort(targetDesc.coerceIn(1, 0xffff))
            updateTableChecksum(raf, os2Table)
            changed = true
        }
        if (changed) headTable?.let { updateCheckSumAdjustment(raf, it) }
        return changed
    }

    private data class SfntTable(val recordOffset: Long, val offset: Int, val length: Int)

    private fun updateTableChecksum(raf: RandomAccessFile, table: SfntTable) {
        val checksum = tableChecksum(raf, table.offset.toLong(), table.length)
        raf.seek(table.recordOffset + 4)
        raf.writeInt(checksum)
    }

    private fun updateCheckSumAdjustment(raf: RandomAccessFile, head: SfntTable) {
        val adjustmentOffset = head.offset.toLong() + 8
        raf.seek(adjustmentOffset)
        raf.writeInt(0)
        val headChecksum = tableChecksum(raf, head.offset.toLong(), head.length)
        raf.seek(head.recordOffset + 4)
        raf.writeInt(headChecksum)

        val fileSum = tableChecksum(raf, 0, raf.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).toLong() and 0xffffffffL
        raf.seek(adjustmentOffset)
        raf.writeInt((0xB1B0AFBAL - fileSum).toInt())
    }

    private fun tableChecksum(raf: RandomAccessFile, offset: Long, length: Int): Int {
        var sum = 0L
        val paddedLength = (length + 3) and -4
        val buffer = ByteArray(4)
        var pos = 0
        while (pos < paddedLength) {
            raf.seek(offset + pos)
            val remaining = length - pos
            if (remaining >= 4) {
                raf.readFully(buffer)
            } else {
                buffer.fill(0)
                if (remaining > 0) raf.readFully(buffer, 0, remaining)
            }
            sum = (sum + ((buffer[0].toLong() and 0xffL) shl 24) +
                ((buffer[1].toLong() and 0xffL) shl 16) +
                ((buffer[2].toLong() and 0xffL) shl 8) +
                (buffer[3].toLong() and 0xffL)) and 0xffffffffL
            pos += 4
        }
        return sum.toInt()
    }

    /** Returns (roster entries or null if absent, hidden entry set). */
    private fun readIndex(): Pair<MutableList<FontId>?, Set<FontId>> {
        val dir = rootDir ?: return null to emptySet()
        val f = File(dir, INDEX)
        if (!f.isFile) return null to emptySet()
        return try {
            val o = JSONObject(f.readText())
            val rArr = o.optJSONArray("roster")
            val roster = if (rArr != null) MutableList(rArr.length()) { FontId.parse(rArr.getString(it)) } else null
            val hArr = o.optJSONArray("hidden")
            val hidden = if (hArr != null) (0 until hArr.length()).mapTo(HashSet()) { FontId.parse(hArr.getString(it)) } else emptySet()
            roster to hidden
        } catch (e: Throwable) {
            FileLog.e("$TAG: readIndex: failed", e)
            null to emptySet()
        }
    }

    private fun saveRoster() {
        val dir = rootDir ?: return
        // snapshot under the lock so JSONArray doesn't iterate a list being mutated on another thread
        val (rosterSnapshot, hiddenSnapshot) = synchronized(lock) {
            rosterFonts.map { it.token() } to hiddenFonts.map { it.token() }
        }
        try {
            File(dir, INDEX).writeText(
                JSONObject()
                    .put("roster", JSONArray(rosterSnapshot))
                    .put("hidden", JSONArray(hiddenSnapshot))
                    .toString()
            )
        } catch (e: Throwable) {
            FileLog.e("$TAG: saveRoster: failed", e)
        }
    }

    /** Pre-multi-family installs stored a single pack at `inu_fonts/pack.json`; fold it into a family dir. */
    private fun migrateLegacy() {
        // todo: remove a few versions later
        val dir = rootDir ?: return
        val legacy = File(dir, MANIFEST)
        if (!legacy.isFile) return
        val id = newId()
        val sub = File(dir, id).apply { mkdirs() }
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name != INDEX) {
                val dst = File(sub, f.name)
                if (!f.renameTo(dst)) {
                    // cross-volume / locked file — fall back to copy + delete so the pack isn't orphaned
                    try {
                        f.copyTo(dst, overwrite = true)
                        f.delete()
                    } catch (e: Throwable) {
                        FileLog.e("$TAG: migrateLegacy: failed to move ${f.name}", e)
                    }
                }
            }
        }
    }

    private fun newId(): String {
        val dir = rootDir
        var n = System.currentTimeMillis()
        var id = n.toString(36)
        while (dir != null && File(dir, id).exists()) {
            n++
            id = n.toString(36)
        }
        return id
    }


    /**
     * Editor roster entries in display order, including hidden ones. System fonts are filtered out while
     * the include-system toggle is off.
     */
    fun getCachedRoster(): List<FontId> = synchronized(lock) {
        if (FontConfig.FONT_INCLUDE_SYSTEM.value) rosterFonts.toList()
        else rosterFonts.filter { it !is FontId.System }
    }

    /**
     * Restores the default editor order: built-ins first (in their natural order), then system
     * families in allowlist order, then imported families in import order (family ids are
     * time-ordered). Keeps hidden flags untouched.
     */
    fun resetOrder() {
        synchronized(lock) {
            val familyIds = families.keys.sorted()
            rosterFonts = (builtinIds + systemFamilies.keys.map { FontId.System(it) } + familyIds.map { FontId.Family(it) })
                .toMutableList()
            val reordered = LinkedHashMap<String, Family>()
            for (id in familyIds) families[id]?.let { reordered[id] = it }
            families.clear()
            families.putAll(reordered)
        }
        saveRoster()
    }

    fun getFontFamily(fontId: FontId): Family? = synchronized(lock) {
        when (fontId) {
            is FontId.Family -> families[fontId.id]
            is FontId.System -> systemFamilies[fontId.name]
            is FontId.Builtin -> null
        }
    }

    /** Display name for any roster entry: imported family, device system font, or built-in. */
    fun getFontName(fontId: FontId): String = when (fontId) {
        is FontId.System -> fontId.name
        is FontId.Family -> synchronized(lock) { families[fontId.id]?.name } ?: fontId.id
        is FontId.Builtin -> getBuiltinName(fontId.key) ?: fontId.key
    }

    /** Preview typeface for any roster entry (null below API P). */
    fun getTypefaceFor(fontId: FontId): Typeface? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return when (fontId) {
            is FontId.Family -> getEditorTypeface(fontId.id)
            is FontId.System -> getSystemFamily(fontId.name)?.resolve(400, false)
            is FontId.Builtin -> getBuiltinTypeface(fontId.key, 400, false)
        }
    }

    fun getTypefaceFor(token: String): Typeface? {
        if (token.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return getTypefaceFor(FontId.parse(token))
    }

    /** (style label, typeface) per face for an imported or system roster entry. */
    fun getFontFaces(fontId: FontId): List<Pair<String, Typeface?>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        return getFontFamily(fontId)?.faceInfos() ?: emptyList()
    }

    fun isHidden(fontId: FontId): Boolean = synchronized(lock) { fontId in hiddenFonts }

    fun setHidden(fontId: FontId, hidden: Boolean) {
        synchronized(lock) {
            if (hidden) hiddenFonts.add(fontId) else hiddenFonts.remove(fontId)
        }
        saveRoster()
    }

    fun setRoster(fonts: List<FontId>) {
        synchronized(lock) {
            // keep system fonts hidden from the reorder list (toggle off) so they aren't dropped
            val missing = rosterFonts.filter { it !in fonts && it is FontId.System }
            rosterFonts = (fonts + missing).toMutableList()
            val reordered = LinkedHashMap<String, Family>()
            for (t in rosterFonts) (t as? FontId.Family)?.let { families[it.id] }?.let { reordered[it.id] = it }
            for ((k, v) in families) reordered.putIfAbsent(k, v)
            families.clear()
            families.putAll(reordered)
        }
        saveRoster()
    }

    private class StagedFile(val file: File, val faces: List<SfntParser.RawFace>, val family: String?)

    class ImportResult(val addedFaces: Int, val rejectedBySystem: Int)

    /**
     * Imports the given URIs, grouping faces by family name. Files whose family matches an existing
     * family (or each other) merge into one entry instead of creating duplicates. Returns the number
     * of face entries added plus the number of files Android's font engine rejected.
     */
    fun importFromUris(context: Context, uris: List<Uri>): ImportResult {
        val dir = rootDir ?: run {
            FileLog.d("$TAG: importFromUris: rootDir not initialized")
            return ImportResult(0, 0)
        }
        val staging = File(dir, STAGING).apply { mkdirs() }
        val cr = context.contentResolver

        // 1. save + parse each file, tagging it with its (majority) family name
        val staged = ArrayList<StagedFile>()
        var rejected = 0
        for ((i, uri) in uris.withIndex()) {
            val tmp = File(staging, "s${i}_${System.nanoTime()}.bin")
            val copied = try {
                val ins = cr.openInputStream(uri)
                if (ins == null) {
                    FileLog.d("$TAG: importFromUris: openInputStream returned null for $uri")
                    false
                } else {
                    ins.use { FileOutputStream(tmp).use { os -> it.copyTo(os) } }
                    true
                }
            } catch (e: Throwable) {
                FileLog.e("$TAG: importFromUris: failed to copy $uri", e)
                false
            }
            if (!copied) continue
            if (stageParsedFile(tmp, staged) == StageResult.UNSUPPORTED) rejected++
        }
        FileLog.d("$TAG: importFromUris: staged ${staged.size}/${uris.size} files, rejected=$rejected")

        return ImportResult(mergeStagedFiles(dir, staging, staged), rejected)
    }

    fun importFromFile(source: File): ImportResult {
        val dir = rootDir ?: run {
            FileLog.d("$TAG: importFromFile: rootDir not initialized")
            return ImportResult(0, 0)
        }
        val staging = File(dir, STAGING).apply { mkdirs() }
        val tmp = File(staging, "s0_${System.nanoTime()}.bin")
        val staged = ArrayList<StagedFile>()
        var rejected = 0
        try {
            source.copyTo(tmp, overwrite = true)
            if (stageParsedFile(tmp, staged) == StageResult.UNSUPPORTED) rejected++
        } catch (e: Throwable) {
            FileLog.e("$TAG: importFromFile: failed to stage ${source.absolutePath}", e)
        }
        return ImportResult(mergeStagedFiles(dir, staging, staged), rejected)
    }

    class FontInfo(val family: String?, val faces: List<SfntParser.RawFace>) {
        val faceCount get() = faces.size
        val variable get() = faces.any { it.variable }
    }

    /** True when Android's font engine accepts the file — [SfntParser] is far more lenient than Minikin. */
    fun isLoadableBySystem(file: File): Boolean {
        val loadable = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Typeface.Builder(file).build() != null
            else Typeface.createFromFile(file) !== Typeface.DEFAULT
        } catch (e: Throwable) {
            FileLog.e("$TAG: isLoadableBySystem: threw for ${file.name}", e)
            false
        }
        if (!loadable) FileLog.d("$TAG: isLoadableBySystem: system rejected ${file.name} (size=${file.length()})")
        return loadable
    }

    fun inspectFont(file: File): FontInfo? {
        val raws = SfntParser.parse(file)
        if (raws.isEmpty()) return null
        val family = raws.mapNotNull { it.family?.takeIf(String::isNotBlank) }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return FontInfo(family, raws)
    }

    enum class InstallKind { NEW, ADD, REINSTALL }

    class InstallStatus(val kind: InstallKind, val familyName: String?)

    fun getInstallStatus(info: FontInfo): InstallStatus {
        val familyName = info.family
        val existing = synchronized(lock) {
            familyName?.let { name -> families.values.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        } ?: return InstallStatus(InstallKind.NEW, familyName)
        val existingKeys = existing.faces.mapTo(HashSet()) { it.weight to it.italic }
        val allPresent = info.faces.all { (it.weight to it.italic) in existingKeys }
        return InstallStatus(if (allPresent) InstallKind.REINSTALL else InstallKind.ADD, existing.name ?: familyName)
    }

    private enum class StageResult { STAGED, UNPARSEABLE, UNSUPPORTED }

    private fun stageParsedFile(tmp: File, out: MutableList<StagedFile>): StageResult {
        val raws = SfntParser.parse(tmp)
        if (raws.isEmpty()) {
            FileLog.d("$TAG: stageParsedFile: no faces parsed from ${tmp.name} (size=${tmp.length()})")
            tmp.delete()
            return StageResult.UNPARSEABLE
        }
        normalizeVerticalMetrics(tmp)
        if (!isLoadableBySystem(tmp)) {
            tmp.delete()
            return StageResult.UNSUPPORTED
        }
        val family = raws.mapNotNull { it.family?.takeIf(String::isNotBlank) }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        out.add(StagedFile(tmp, raws, family))
        return StageResult.STAGED
    }

    private fun mergeStagedFiles(dir: File, staging: File, staged: List<StagedFile>): Int {
        // 2. bucket files by family name; files with no name each get their own bucket
        val buckets = LinkedHashMap<String, MutableList<StagedFile>>()
        for (s in staged) {
            val key = s.family?.lowercase() ?: " ${s.file.name}"
            buckets.getOrPut(key) { mutableListOf() }.add(s)
        }

        // 3. merge each bucket into a matching existing family, or create a new one
        var addedFaces = 0
        for ((_, group) in buckets) {
            val familyName = group.firstNotNullOfOrNull { it.family }
            try {
                // resolve `existing` under lock; reading the LinkedHashMap concurrently with mutations is unsafe.
                val existing = synchronized(lock) {
                    familyName?.let { name -> families.values.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                }
                val targetId = existing?.id ?: newId()
                val targetDir = existing?.dir ?: File(dir, targetId).apply { mkdirs() }
                // use in-memory faces from the loaded Family — re-reading the manifest here would
                // silently drop every existing face if the file is momentarily corrupted/unreadable.
                val faces = existing?.faces?.toMutableList() ?: mutableListOf()

                // counter ensures unique destination names even when called twice in the same nanosecond
                // or when faces.size collides with a previously-imported file name.
                var nameCounter = 0
                for (s in group) {
                    var name: String
                    var dst: File
                    do {
                        name = "f${faces.size}_${System.nanoTime()}_${nameCounter++}.bin"
                        dst = File(targetDir, name)
                    } while (dst.exists())
                    if (!s.file.renameTo(dst)) {
                        try {
                            s.file.copyTo(dst, overwrite = true)
                            s.file.delete()
                        } catch (e: Throwable) {
                            FileLog.e("$TAG: mergeStagedFiles: failed to move ${s.file.name} into $targetId", e)
                            continue
                        }
                    }
                    // replace any existing face with the same weight/italic; drop its blob if now orphaned
                    val incomingKeys = s.faces.mapTo(HashSet()) { it.weight to it.italic }
                    val replaced = faces.filter { (it.weight to it.italic) in incomingKeys }
                    if (replaced.isNotEmpty()) {
                        faces.removeAll(replaced)
                        for (rem in replaced) {
                            if (faces.none { it.file == rem.file }) {
                                File(rem.file).let { if (it.isAbsolute) it else File(targetDir, rem.file) }.delete()
                            }
                        }
                    }
                    for (r in s.faces) {
                        faces.add(Face(name, r.ttcIndex, r.weight, r.italic, r.variable, r.wghtMin, r.wghtMax))
                        addedFaces++
                    }
                }
                if (faces.isEmpty()) continue
                writeManifest(targetDir, faces, familyName ?: existing?.name)
                val refreshed = readFamily(targetDir)
                if (refreshed == null) {
                    FileLog.d("$TAG: mergeStagedFiles: manifest re-read failed for $targetId right after write")
                    continue
                }
                FileLog.d("$TAG: mergeStagedFiles: family=$familyName id=$targetId faces=${faces.size} existing=${existing != null}")
                synchronized(lock) {
                    families[targetId] = refreshed // replaces (refreshes cache) or adds
                    if (existing == null) {
                        rosterFonts.add(FontId.Family(targetId))
                    } else {
                        // re-importing into an existing (possibly hidden) family makes it visible again,
                        // otherwise the user's new faces are silently missing from the editor.
                        hiddenFonts.remove(FontId.Family(targetId))
                    }
                }
            } catch (e: Throwable) {
                FileLog.e("$TAG: mergeStagedFiles: failed for family=$familyName", e)
            }
        }

        staging.deleteRecursively()
        FileLog.d("$TAG: mergeStagedFiles: addedFaces=$addedFaces from ${staged.size} staged files")
        if (addedFaces > 0) saveRoster()
        return addedFaces
    }

    fun removeFamily(id: String) {
        val tok = FontId.Family(id)
        val fam = synchronized(lock) {
            val f = families.remove(id)
            rosterFonts.remove(tok)
            hiddenFonts.remove(tok)
            f
        }
        (fam?.dir ?: rootDir?.let { File(it, id) })?.deleteRecursively()
        // drop the removed family from the app-font fallback stack so it doesn't dangle
        (FontConfig.FONT.value as? FontConfig.FontMode.Custom)?.takeIf { tok in it.fallbacks }?.let {
            FontConfig.FONT.value = it.copy(fallbacks = it.fallbacks - tok)
        }
        saveRoster()
    }

    fun ensureSystemFontsLoaded(force: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!FontConfig.FONT_INCLUDE_SYSTEM.value && !force) return
        synchronized(lock) {
            if (systemLoaded || systemLoading) return
            systemLoading = true
        }
        if (force) {
            applySystemFonts(discoverSystemFonts(), notify = false)
            return
        }

        Utilities.globalQueue.postRunnable {
            val discovered = discoverSystemFonts()
            AndroidUtilities.runOnUIThread { applySystemFonts(discovered, notify = true) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun discoverSystemFonts(): LinkedHashMap<String, Family> {
        // adapted from stock
        val discovered = LinkedHashMap<String, Family>()
        try {
            val byFamily = LinkedHashMap<String, MutableList<Face>>()
            // some families say the same file a bunch of times
            val seenFiles = HashSet<String>()
            for (font in SystemFonts.getAvailableFonts()) {
                val file = font.file ?: continue
                if (file.name.contains("Noto")) continue
                if (!seenFiles.add(file.absolutePath)) continue
                val raws = SfntParser.parse(file)
                if (raws.isEmpty()) continue
                val familyName = raws.firstNotNullOfOrNull { it.family?.takeIf(String::isNotBlank) } ?: continue
                if (familyName !in PaintTypeface.preferable) continue
                val faces = byFamily.getOrPut(familyName) { mutableListOf() }
                for (r in raws) {
                    faces.add(Face(file.absolutePath, r.ttcIndex, r.weight, r.italic, r.variable, r.wghtMin, r.wghtMax))
                }
            }
            // preserve allowlist order so the roster is stable across devices
            for (name in PaintTypeface.preferable) {
                val faces = byFamily[name] ?: continue
                discovered[name] = Family(name, rootDir ?: File(name), name, faces)
            }
        } catch (e: Throwable) {
            FileLog.e("$TAG: discoverSystemFonts: failed", e)
        }
        return discovered
    }

    // notify=false for the blocking startup path: NotificationCenter isn't initialized during
    // ApplicationLoader.onCreate (posting NPEs), and there's no open editor to refresh yet anyway.
    private fun applySystemFonts(discovered: LinkedHashMap<String, Family>, notify: Boolean) {
        synchronized(lock) {
            systemFamilies.clear()
            systemFamilies.putAll(discovered)
            systemLoaded = true
            systemLoading = false
            mergeSystemFonts()
        }
        saveRoster()
        if (notify) invalidateEditorRoster()
    }

    /** Reconciles system fonts into the roster: prune vanished families, insert newly-found ones. */
    private fun mergeSystemFonts() { // caller holds lock
        rosterFonts.removeAll { it is FontId.System && it.name !in systemFamilies }
        hiddenFonts.removeAll { it is FontId.System && it.name !in systemFamilies }
        for (name in systemFamilies.keys) {
            val tok = FontId.System(name)
            if (tok in rosterFonts) continue
            // default grouping is stock → system → custom, so slot new system fonts before the first
            // imported family (allowlist order is preserved as each insert pushes the anchor right)
            val insertAt = rosterFonts.indexOfFirst { it is FontId.Family }
            if (insertAt < 0) rosterFonts.add(tok) else rosterFonts.add(insertAt, tok)
        }
    }

    private fun getSystemFamily(name: String): Family? = synchronized(lock) { systemFamilies[name] }

    /** Imported / system family typeface at the requested style; null for built-in or unknown fonts. */
    fun getFamilyTypeface(fontId: FontId, weight: Int, italic: Boolean): Typeface? = when (fontId) {
        is FontId.Family -> synchronized(lock) { families[fontId.id] }?.resolve(weight, italic)
        is FontId.System -> getSystemFamily(fontId.name)?.resolve(weight, italic)
        is FontId.Builtin -> null
    }

    /** Imported / system family best face as a [Font]; null for built-in or unknown fonts. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun getFont(fontId: FontId, weight: Int, italic: Boolean): Font? = when (fontId) {
        is FontId.Family -> synchronized(lock) { families[fontId.id] }?.fontFor(weight, italic)
        is FontId.System -> synchronized(lock) { systemFamilies[fontId.name] }?.fontFor(weight, italic)
        is FontId.Builtin -> null
    }

    // todo: mainly used for legacy migration, remove
    fun containsFamily(id: String): Boolean = synchronized(lock) { families.containsKey(id) }
    fun hasAnyFamily(): Boolean = synchronized(lock) { families.isNotEmpty() }
    fun firstFamilyId(): String? = synchronized(lock) { families.keys.firstOrNull() }

    private val builtinIds: List<FontId.Builtin> by lazy {
        PaintTypeface.BUILT_IN_FONTS.map { FontId.Builtin(it.key) }
    }

    // resolve from the static list, NOT PaintTypeface.find() — find() searches the live editor roster,
    // which omits hidden fonts, so a hidden built-in would fail to resolve its name/typeface.
    private fun getBuiltinFont(key: String): PaintTypeface? =
        if (key.isEmpty()) null else PaintTypeface.BUILT_IN_FONTS.firstOrNull { it.key == key }

    fun isBuiltinKey(key: String): Boolean = getBuiltinFont(key) != null

    fun getBuiltinName(key: String): String? = getBuiltinFont(key)?.name

    /** A built-in editor font selected as the app font: weight/italic synthesized from its base face. */
    fun getBuiltinTypeface(key: String, weight: Int, italic: Boolean): Typeface? {
        val base = getBuiltinFont(key)?.typeface ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(base, weight, italic) else base
    }

    internal fun getStyleCoverageFor(fontId: FontId): StyleCoverage? = getFontFamily(fontId)?.styleCoverage()

    /** Parses font files. Call off the main thread. */
    internal fun getScriptCoverageFor(fontId: FontId): Set<Script>? = getFontFamily(fontId)?.scriptCoverage()

    // ---- media editor ----------------------------------------------------------------------

    // Bumped (UI thread only) whenever the editor roster changes; in-flight PaintTypeface roster
    // builds compare against it at commit time and drop stale results.
    @Volatile
    private var rosterGeneration = 0

    /** True if no roster change happened since [generation] was handed out by [buildEditorRoster]. */
    @JvmStatic
    fun isRosterCurrent(generation: Int): Boolean = generation == rosterGeneration

    /** Drops PaintTypeface's cached list so the next get() rebuilds it; lets an open editor refresh live. */
    fun invalidateEditorRoster() {
        rosterGeneration++
        PaintTypeface.typefaces = null
        PaintTypeface.loadingTypefaces = false
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.customTypefacesLoaded)
    }

    fun getEditorTypeface(id: String): Typeface? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        // pull the Family out under lock, then resolve outside (Family.resolve has its own lock and
        // does file I/O — holding the outer lock here would serialize unrelated readers).
        val family = synchronized(lock) { families[id] } ?: return null
        return family.resolve(400, false)
    }

    private fun getSystemTypeface(name: String): Typeface? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return getSystemFamily(name)?.resolve(400, false)
    }

    // Builds the editor's PaintTypeface list from the roster. `load()` prunes `font:$id` tokens whose
    // family is gone, so by the time we read them here every imported entry resolves; Typeface
    // construction is deferred to LazyTypeface so opening the editor doesn't read every font file.
    // Returns the roster generation for the caller's [isRosterCurrent] check at commit time.
    @JvmStatic
    fun buildEditorRoster(out: ArrayList<PaintTypeface>): Int {
        // kick off system-font discovery if not done — completion invalidates and rebuilds
        ensureSystemFontsLoaded()
        // generation first: a bump between here and the snapshot makes the build look stale, which
        // only costs a redundant rebuild — capturing after could commit a genuinely stale roster
        val gen = rosterGeneration
        val includeSystem = FontConfig.FONT_INCLUDE_SYSTEM.value
        // snapshot fontIds + names atomically so a concurrent removeFamily doesn't desync them
        val (fontIds, names, systemNames) = synchronized(lock) {
            Triple(
                rosterFonts.filter { it !in hiddenFonts && (includeSystem || it !is FontId.System) },
                families.mapValues { it.value.name },
                systemFamilies.keys.toHashSet(),
            )
        }
        if (fontIds.isEmpty()) {
            out.addAll(PaintTypeface.BUILT_IN_FONTS)
            return gen
        }
        val byKey = PaintTypeface.BUILT_IN_FONTS.associateBy { it.key }
        for (fontId in fontIds) {
            when (fontId) {
                is FontId.Family -> {
                    val id = fontId.id
                    val name = names[id] ?: fontId.id
                    out.add(
                        PaintTypeface(
                            PaintTypeface.LazyTypeface { getEditorTypeface(id) },
                            "inu_font_$id",
                            name,
                        )
                    )
                }

                is FontId.System -> {
                    val sysName = fontId.name
                    if (sysName in systemNames) {
                        out.add(
                            PaintTypeface(
                                PaintTypeface.LazyTypeface { getSystemTypeface(sysName) },
                                fontId.token(),
                                sysName,
                            )
                        )
                    }
                }

                is FontId.Builtin -> byKey[fontId.key]?.let { out.add(it) }
            }
        }
        return gen
    }
}
