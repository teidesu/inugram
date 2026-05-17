package desu.inugram.helpers

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object FontHelper {
    private const val MANIFEST = "pack.json"

    private data class Face(
        val file: String,
        val ttcIndex: Int,
        val weight: Int,
        val italic: Boolean,
        val variable: Boolean,
        val wghtMin: Int,
        val wghtMax: Int,
    )

    private var packDir: File? = null
    private var faces: List<Face> = emptyList()
    private val cache = HashMap<String, Typeface>()

    var familyName: String? = null
        private set

    fun init(context: Context) {
        packDir = File(context.filesDir, "inu_fonts").apply { mkdirs() }
        loadManifest()
    }

    val hasPack: Boolean get() = faces.isNotEmpty()

    private fun loadManifest() {
        faces = emptyList()
        familyName = null
        val dir = packDir ?: return
        val mf = File(dir, MANIFEST)
        if (!mf.isFile) return
        try {
            val root = JSONObject(mf.readText())
            familyName = root.optString("family", "").takeIf { it.isNotEmpty() }
            val arr = root.getJSONArray("faces")
            faces = (0 until arr.length()).map { i ->
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
        } catch (e: Throwable) {
            faces = emptyList()
            familyName = null
        }
    }

    private fun saveManifest(list: List<Face>, family: String?) {
        val dir = packDir ?: return
        val arr = JSONArray()
        for (f in list) {
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
        File(dir, MANIFEST).writeText(root.toString())
    }

    fun clear() {
        val dir = packDir ?: return
        dir.listFiles()?.forEach { it.delete() }
        faces = emptyList()
        familyName = null
        cache.clear()
    }

    /** Replaces the current pack with the given URIs. Returns number of face entries discovered. */
    fun importFromUris(context: Context, uris: List<Uri>): Int {
        val dir = packDir ?: return 0
        clear()
        val out = mutableListOf<Face>()
        val familyVotes = HashMap<String, Int>()
        val cr = context.contentResolver
        for ((i, uri) in uris.withIndex()) {
            val name = "f${i}_${System.currentTimeMillis()}.bin"
            val dst = File(dir, name)
            try {
                cr.openInputStream(uri)?.use { ins ->
                    FileOutputStream(dst).use { os -> ins.copyTo(os) }
                } ?: continue
            } catch (e: Throwable) {
                continue
            }
            val raws = SfntParser.parse(dst)
            if (raws.isEmpty()) {
                dst.delete()
                continue
            }
            for (r in raws) {
                out.add(Face(name, r.ttcIndex, r.weight, r.italic, r.variable, r.wghtMin, r.wghtMax))
                r.family?.let { familyVotes.merge(it, 1, Int::plus) }
            }
        }
        faces = out
        familyName = familyVotes.entries.maxByOrNull { it.value }?.key
        if (out.isNotEmpty()) saveManifest(out, familyName) else clear()
        cache.clear()
        return out.size
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun resolve(targetWeight: Int, targetItalic: Boolean): Typeface? {
        if (faces.isEmpty()) return null
        val cacheKey = "$targetWeight/$targetItalic"
        cache[cacheKey]?.let { return it }

        val pick = pickBestFace(targetWeight, targetItalic) ?: return null
        val dir = packDir ?: return null
        val file = File(dir, pick.file)
        if (!file.isFile) return null

        val base: Typeface = try {
            val builder = Typeface.Builder(file).setTtcIndex(pick.ttcIndex)
            if (pick.variable && pick.wghtMin != pick.wghtMax) {
                val w = targetWeight.coerceIn(pick.wghtMin, pick.wghtMax)
                builder.setFontVariationSettings("'wght' $w")
            }
            builder.build()
        } catch (e: Throwable) {
            return null
        } ?: return null

        // synthesize italic / weight tweaks when picked face doesn't match exactly
        val needSynth = (targetItalic && !pick.italic) ||
            (!pick.variable && pick.weight != targetWeight)
        val finalTf = if (needSynth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { Typeface.create(base, targetWeight, targetItalic) } catch (e: Throwable) { base }
        } else base

        cache[cacheKey] = finalTf
        return finalTf
    }

    /**
     * Best-effort: swap [Typeface.DEFAULT] / [Typeface.DEFAULT_BOLD] and the entries in
     * `Typeface.sSystemFontMap` for `sans-serif*` so that UI widgets which don't go through
     * [android.graphics.Typeface.create] from an asset path (TextView default, chat_msgTextPaint…)
     * also pick up the custom font.
     *
     * Reflection. Wrapped in try/catch — failures are non-fatal.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun installAsDefault() {
        if (faces.isEmpty()) return
        val regular = resolve(400, false) ?: return
        val bold = resolve(700, false) ?: regular
        try {
            val cls = Typeface::class.java
            replaceStatic(cls, "DEFAULT", regular)
            replaceStatic(cls, "DEFAULT_BOLD", bold)
            replaceStatic(cls, "SANS_SERIF", regular)
            try {
                @Suppress("UNCHECKED_CAST")
                val map = cls.getDeclaredField("sSystemFontMap").apply { isAccessible = true }
                    .get(null) as? MutableMap<String, Typeface> ?: return
                for (k in arrayOf("sans-serif", "sans-serif-light", "sans-serif-thin",
                    "sans-serif-condensed", "sans-serif-condensed-light")) {
                    map[k] = regular
                }
                for (k in arrayOf("sans-serif-medium", "sans-serif-black")) {
                    map[k] = bold
                }
            } catch (_: Throwable) {
                // hidden API blocked on some firmware; DEFAULT swap alone still covers most cases
            }
        } catch (_: Throwable) {
        }
    }

    private fun replaceStatic(cls: Class<*>, name: String, value: Typeface) {
        try {
            val f = cls.getDeclaredField(name)
            f.isAccessible = true
            f.set(null, value)
        } catch (_: Throwable) {
        }
    }

    private fun pickBestFace(targetWeight: Int, targetItalic: Boolean): Face? {
        val sameItalic = faces.filter { it.italic == targetItalic }
        val pool = if (sameItalic.isNotEmpty()) sameItalic else faces
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
}
