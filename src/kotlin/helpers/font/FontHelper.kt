package desu.inugram.helpers.font

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.TextPaint
import android.widget.TextView
import androidx.annotation.RequiresApi
import desu.inugram.InuConfig
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.Paint.PaintTypeface
import java.io.File
import java.io.FileOutputStream
import java.util.Hashtable

/**
 * Manages user-imported font families and the app's font selection.
 *
 * Storage layout under `filesDir/inu_fonts/`:
 * ```
 *   index.json        { "roster": [token,…] }   ← editor roster order (built-in keys + font:<id>)
 *   <id>/pack.json    { "family": "…", "faces": [ {file,ttcIndex,weight,italic,variable,wghtMin,wghtMax}, … ] }
 *   <id>/f*.bin       the raw font files
 * ```
 *
 * The **app UI font** is selected via [InuConfig.FONT_MODE] (bundled / system / custom) +
 * [InuConfig.ACTIVE_FONT_ID]; [resolve] returns the active family so the whole UI-font pipeline
 * ([installAsDefault], [onGetTypeface], [applyDefaultFont], [onThemePaintsCreated]) keeps working.
 *
 * The **media editor** roster is the ordered [roster] token list, consumed by `PaintTypeface`.
 */
object FontHelper {
    private const val DIR = "inu_fonts"
    private const val INDEX = "index.json"
    private const val MANIFEST = "pack.json"
    private const val STAGING = ".staging"
    private const val FAMILY_TOKEN_PREFIX = "font:"

    /** Roster token for an imported family id. */
    fun familyToken(id: String): String = FAMILY_TOKEN_PREFIX + id

    /** The family id if [token] refers to an imported family, null for built-in editor fonts. */
    fun familyId(token: String): String? =
        if (token.startsWith(FAMILY_TOKEN_PREFIX)) token.substring(FAMILY_TOKEN_PREFIX.length) else null

    private data class Face(
        val file: String,
        val ttcIndex: Int,
        val weight: Int,
        val italic: Boolean,
        val variable: Boolean,
        val wghtMin: Int,
        val wghtMax: Int,
    )

    /** One imported font family (a directory with a manifest + face files). */
    private class Family(
        val id: String,
        val dir: File,
        val name: String?,
        val faces: List<Face>,
    ) {
        private val cache = HashMap<String, Typeface>()

        @RequiresApi(Build.VERSION_CODES.O)
        @Synchronized
        fun resolve(targetWeight: Int, targetItalic: Boolean): Typeface? {
            if (faces.isEmpty()) return null
            val cacheKey = "$targetWeight/$targetItalic"
            cache[cacheKey]?.let { return it }

            val pick = pickBestFace(targetWeight, targetItalic) ?: return null
            val file = File(dir, pick.file)
            if (!file.isFile) return null

            val base: Typeface = try {
                val builder = Typeface.Builder(file).setTtcIndex(pick.ttcIndex)
                if (pick.variable && pick.wghtMin != pick.wghtMax) {
                    val w = targetWeight.coerceIn(pick.wghtMin, pick.wghtMax)
                    builder.setFontVariationSettings("'wght' $w")
                }
                builder.build()
            } catch (_: Throwable) {
                return null
            } ?: return null

            // synthesize italic / weight tweaks when the picked face doesn't match exactly
            val needSynth = (targetItalic && !pick.italic) ||
                (!pick.variable && pick.weight != targetWeight)
            val finalTf = if (needSynth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try { Typeface.create(base, targetWeight, targetItalic) } catch (_: Throwable) { base }
            } else base

            cache[cacheKey] = finalTf
            return finalTf
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

        /** (style label, typeface) per face — for the disabled face list shown in the tap menu. */
        @RequiresApi(Build.VERSION_CODES.O)
        @Synchronized
        fun faceInfos(): List<Pair<String, Typeface?>> = faces.map { f ->
            val tf = try {
                val b = Typeface.Builder(File(dir, f.file)).setTtcIndex(f.ttcIndex)
                if (f.variable && f.wghtMin != f.wghtMax) {
                    b.setFontVariationSettings("'wght' ${f.weight.coerceIn(f.wghtMin, f.wghtMax)}")
                }
                b.build()
            } catch (_: Throwable) {
                null
            }
            faceLabel(f) to tf
        }

        private fun faceLabel(f: Face): String {
            if (f.variable && f.wghtMin != f.wghtMax) return "Variable"
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
    }

    private var rootDir: File? = null
    private val families = LinkedHashMap<String, Family>()
    private var rosterTokens: MutableList<String> = mutableListOf()
    private var hiddenTokens: MutableSet<String> = HashSet()

    // guards `families` / `rosterTokens` / `hiddenTokens`. Reads from themeQueue (PaintTypeface roster
    // build) and globalQueue (import) race with UI-thread mutations; hold this lock for any mutation
    // or any read that escapes the helper.
    private val lock = Any()

    fun init(context: Context) {
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

        val builtinKeys = builtinKeys()
        val (saved, savedHidden) = readIndex()
        val roster: MutableList<String> = saved ?: builtinKeys.toMutableList()

        // reconcile: keep saved order, drop font tokens whose dir is gone, append newly-found families,
        // then append any current built-in missing from the roster (e.g. added in an app update)
        val seenFonts = HashSet<String>()
        val cleaned = ArrayList<String>(roster.size)
        for (tok in roster) {
            val id = familyId(tok)
            if (id == null) {
                cleaned.add(tok)
            } else if (discovered.containsKey(id) && seenFonts.add(id)) {
                cleaned.add(tok)
            }
        }
        for (id in discovered.keys) if (seenFonts.add(id)) cleaned.add(familyToken(id))
        for (key in builtinKeys) if (key !in cleaned) cleaned.add(key)

        val newHidden = savedHidden.filterTo(HashSet()) { it in cleaned }
        synchronized(lock) {
            families.clear()
            rosterTokens = cleaned
            hiddenTokens = newHidden
            for (tok in cleaned) {
                familyId(tok)?.let { id -> discovered[id]?.let { families[it.id] = it } }
            }
        }
        if (saved == null || saved != cleaned || savedHidden != newHidden) saveRoster()

        // active app font may reference a removed family — revert to default so UI doesn't silently
        // fall back to "Custom font" with no typeface installed.
        if (InuConfig.FONT_MODE.value == InuConfig.FontModeItem.CUSTOM) {
            val id = InuConfig.ACTIVE_FONT_ID.value
            val builtinKey = id.isNotEmpty() && id in builtinKeys
            val familyExists = id.isNotEmpty() && synchronized(lock) { families.containsKey(id) }
            val migrated = id.isEmpty() && synchronized(lock) { families.isNotEmpty() }
            if (!builtinKey && !familyExists && !migrated) resetAppFont()
        }
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
            if (faces.isEmpty()) null else Family(sub.name, sub, name, faces)
        } catch (_: Throwable) {
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

    /** Returns (roster tokens or null if absent, hidden token set). */
    private fun readIndex(): Pair<MutableList<String>?, Set<String>> {
        val dir = rootDir ?: return null to emptySet()
        val f = File(dir, INDEX)
        if (!f.isFile) return null to emptySet()
        return try {
            val o = JSONObject(f.readText())
            val rArr = o.optJSONArray("roster")
            val roster = if (rArr != null) MutableList(rArr.length()) { rArr.getString(it) } else null
            val hArr = o.optJSONArray("hidden")
            val hidden = if (hArr != null) (0 until hArr.length()).mapTo(HashSet()) { hArr.getString(it) } else emptySet()
            roster to hidden
        } catch (_: Throwable) {
            null to emptySet()
        }
    }

    private fun saveRoster() {
        val dir = rootDir ?: return
        // snapshot under the lock so JSONArray doesn't iterate a list being mutated on another thread
        val (rosterSnapshot, hiddenSnapshot) = synchronized(lock) {
            ArrayList(rosterTokens) to ArrayList(hiddenTokens)
        }
        try {
            File(dir, INDEX).writeText(
                JSONObject()
                    .put("roster", JSONArray(rosterSnapshot))
                    .put("hidden", JSONArray(hiddenSnapshot))
                    .toString()
            )
        } catch (_: Throwable) {
        }
    }

    /** Pre-multi-family installs stored a single pack at `inu_fonts/pack.json`; fold it into a family dir. */
    private fun migrateLegacy() {
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
                    } catch (_: Throwable) {
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

    // ---- public model ----------------------------------------------------------------------

    /** (id, display name) for every visible imported family, in roster order — for the app-font picker. */
    fun familyChoices(): List<Pair<String, String>> = synchronized(lock) {
        families.values
            .filter { familyToken(it.id) !in hiddenTokens }
            .map { it.id to (it.name ?: LocaleController.getString(R.string.InuFontUnnamed)) }
    }

    /** Editor roster tokens (built-in keys + `font:<id>`) in display order, including hidden ones. */
    fun roster(): List<String> = synchronized(lock) { rosterTokens.toList() }

    /**
     * Restores the default editor order: built-ins first (in their natural order), then families in
     * import order (family ids are time-ordered). Keeps hidden flags untouched.
     */
    fun resetOrder() {
        synchronized(lock) {
            val builtins = builtinKeys()
            val familyIds = families.keys.sorted()
            rosterTokens = (builtins + familyIds.map { familyToken(it) }).toMutableList()
            val reordered = LinkedHashMap<String, Family>()
            for (id in familyIds) families[id]?.let { reordered[id] = it }
            families.clear()
            families.putAll(reordered)
        }
        saveRoster()
    }

    /** (style label, typeface) for each face of an imported family. */
    fun familyFaces(id: String): List<Pair<String, Typeface?>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        val family = synchronized(lock) { families[id] } ?: return emptyList()
        return family.faceInfos()
    }

    fun isHidden(token: String): Boolean = synchronized(lock) { token in hiddenTokens }

    fun setHidden(token: String, hidden: Boolean) {
        synchronized(lock) {
            if (hidden) hiddenTokens.add(token) else hiddenTokens.remove(token)
        }
        saveRoster()
    }

    fun setRoster(tokens: List<String>) {
        synchronized(lock) {
            rosterTokens = tokens.toMutableList()
            val reordered = LinkedHashMap<String, Family>()
            for (t in rosterTokens) familyId(t)?.let { id -> families[id]?.let { reordered[it.id] = it } }
            for ((k, v) in families) reordered.putIfAbsent(k, v)
            families.clear()
            families.putAll(reordered)
        }
        saveRoster()
    }

    private class StagedFile(val file: File, val faces: List<SfntParser.RawFace>, val family: String?)

    /**
     * Imports the given URIs, grouping faces by family name. Files whose family matches an existing
     * family (or each other) merge into one entry instead of creating duplicates. Returns the number
     * of face entries added (0 on failure).
     */
    fun importFromUris(context: Context, uris: List<Uri>): Int {
        val dir = rootDir ?: return 0
        val staging = File(dir, STAGING).apply { mkdirs() }
        val cr = context.contentResolver

        // 1. save + parse each file, tagging it with its (majority) family name
        val staged = ArrayList<StagedFile>()
        for ((i, uri) in uris.withIndex()) {
            val tmp = File(staging, "s${i}_${System.nanoTime()}.bin")
            try {
                cr.openInputStream(uri)?.use { ins -> FileOutputStream(tmp).use { os -> ins.copyTo(os) } } ?: continue
            } catch (_: Throwable) {
                continue
            }
            val raws = SfntParser.parse(tmp)
            if (raws.isEmpty()) {
                tmp.delete()
                continue
            }
            val family = raws.mapNotNull { it.family?.takeIf(String::isNotBlank) }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            staged.add(StagedFile(tmp, raws, family))
        }

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
                    } catch (_: Throwable) {
                        continue
                    }
                }
                for (r in s.faces) {
                    faces.add(Face(name, r.ttcIndex, r.weight, r.italic, r.variable, r.wghtMin, r.wghtMax))
                    addedFaces++
                }
            }
            if (faces.isEmpty()) continue
            writeManifest(targetDir, faces, familyName ?: existing?.name)
            val refreshed = readFamily(targetDir) ?: continue
            synchronized(lock) {
                families[targetId] = refreshed // replaces (refreshes cache) or adds
                if (existing == null) {
                    rosterTokens.add(familyToken(targetId))
                } else {
                    // re-importing into an existing (possibly hidden) family makes it visible again,
                    // otherwise the user's new faces are silently missing from the editor.
                    hiddenTokens.remove(familyToken(targetId))
                }
            }
        }

        staging.deleteRecursively()
        if (addedFaces > 0) saveRoster()
        return addedFaces
    }

    fun removeFamily(id: String) {
        val fam = synchronized(lock) {
            val f = families.remove(id)
            rosterTokens.remove(familyToken(id))
            hiddenTokens.remove(familyToken(id))
            f
        }
        (fam?.dir ?: rootDir?.let { File(it, id) })?.deleteRecursively()
        saveRoster()
    }

    // ---- app UI font -----------------------------------------------------------------------

    private fun activeFamily(): Family? {
        if (InuConfig.FONT_MODE.value != InuConfig.FontModeItem.CUSTOM) return null
        val id = InuConfig.ACTIVE_FONT_ID.value
        return synchronized(lock) {
            if (id.isNotEmpty()) families[id]
            // legacy: FONT_MODE==2 with no explicit id → the single migrated family
            else families.values.firstOrNull()
        }
    }

    /** True if [id] (an imported family id or a built-in editor key) is the current app font. */
    fun isActiveAppFont(id: String): Boolean {
        if (InuConfig.FONT_MODE.value != InuConfig.FontModeItem.CUSTOM) return false
        val active = InuConfig.ACTIVE_FONT_ID.value
        if (active.isNotEmpty()) return active == id
        // legacy: empty id resolves to the first family, hidden or not (same as activeFamily())
        return synchronized(lock) { families.keys.firstOrNull() } == id
    }

    /** Reverts the app font to the bundled default. */
    fun resetAppFont() {
        InuConfig.FONT_MODE.value = InuConfig.FontModeItem.BUNDLED
        InuConfig.ACTIVE_FONT_ID.value = ""
    }

    /** Display name of the active app font, whether it's a built-in editor font or an imported family. */
    fun activeFamilyName(): String? {
        if (InuConfig.FONT_MODE.value != InuConfig.FontModeItem.CUSTOM) return null
        builtinName(InuConfig.ACTIVE_FONT_ID.value)?.let { return it }
        return activeFamily()?.name
    }

    fun activeFamilyTypeface(): Typeface? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return resolve(400, false)
    }

    private fun builtinKeys(): List<String> = PaintTypeface.BUILT_IN_FONTS.map { it.key }

    fun isBuiltinKey(key: String): Boolean =
        key.isNotEmpty() && PaintTypeface.BUILT_IN_FONTS.any { it.key == key }

    fun builtinName(key: String): String? =
        if (isBuiltinKey(key)) PaintTypeface.find(key)?.name else null

    /** A built-in editor font selected as the app font: weight/italic synthesized from its base face. */
    @RequiresApi(Build.VERSION_CODES.O)
    fun builtinTypeface(key: String, weight: Int, italic: Boolean): Typeface? {
        if (!isBuiltinKey(key)) return null
        val base = PaintTypeface.find(key)?.typeface ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(base, weight, italic) else base
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun resolve(targetWeight: Int, targetItalic: Boolean): Typeface? {
        if (InuConfig.FONT_MODE.value != InuConfig.FontModeItem.CUSTOM) return null
        builtinTypeface(InuConfig.ACTIVE_FONT_ID.value, targetWeight, targetItalic)?.let { return it }
        return activeFamily()?.resolve(targetWeight, targetItalic)
    }

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

    fun editorName(id: String): String =
        synchronized(lock) { families[id]?.name } ?: LocaleController.getString(R.string.InuFontUnnamed)

    fun editorTypeface(id: String): Typeface? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        // pull the Family out under lock, then resolve outside (Family.resolve has its own lock and
        // does file I/O — holding the outer lock here would serialize unrelated readers).
        val family = synchronized(lock) { families[id] } ?: return null
        return family.resolve(400, false)
    }

    // Builds the editor's PaintTypeface list from the roster. `load()` prunes `font:$id` tokens whose
    // family is gone, so by the time we read them here every imported entry resolves; Typeface
    // construction is deferred to LazyTypeface so opening the editor doesn't read every font file.
    // Returns the roster generation for the caller's [isRosterCurrent] check at commit time.
    @JvmStatic
    fun buildEditorRoster(out: ArrayList<PaintTypeface>): Int {
        // generation first: a bump between here and the snapshot makes the build look stale, which
        // only costs a redundant rebuild — capturing after could commit a genuinely stale roster
        val gen = rosterGeneration
        // snapshot tokens + names atomically so a concurrent removeFamily doesn't desync them
        val (tokens, names) = synchronized(lock) {
            rosterTokens.filter { it !in hiddenTokens } to families.mapValues { it.value.name }
        }
        if (tokens.isEmpty()) {
            out.addAll(PaintTypeface.BUILT_IN_FONTS)
            return gen
        }
        val byKey = PaintTypeface.BUILT_IN_FONTS.associateBy { it.key }
        for (token in tokens) {
            val id = familyId(token)
            if (id != null) {
                val name = names[id] ?: LocaleController.getString(R.string.InuFontUnnamed)
                out.add(PaintTypeface(
                    PaintTypeface.LazyTypeface { editorTypeface(id) },
                    "inu_font_$id",
                    name,
                ))
            } else {
                byKey[token]?.let { out.add(it) }
            }
        }
        return gen
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

    @JvmStatic
    fun applyDefaultFont(paint: TextPaint?) {
        if (paint == null || paint.typeface != null) return
        if (InuConfig.FONT_MODE.value != InuConfig.FontModeItem.CUSTOM) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        resolve(400, false)?.let { paint.typeface = it }
    }

    @JvmStatic
    fun applyDefaultFont(view: TextView?) {
        if (view == null) return
        if (InuConfig.FONT_MODE.value != InuConfig.FontModeItem.CUSTOM) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        resolve(400, false)?.let { view.typeface = it }
    }

    /**
     * Stock creates many `chat_*Paint`/`dialogs_*Paint`/`profile_*Paint` `TextPaint`s in
     * [org.telegram.ui.ActionBar.Theme] without an explicit typeface. Sweep them after creation
     * so message bubble text, dialog cell previews, profile bio, etc. pick up the custom font.
     */
    @JvmStatic
    fun onThemePaintsCreated() {
        if (InuConfig.FONT_MODE.value != InuConfig.FontModeItem.CUSTOM) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val tf = resolve(400, false) ?: return
        try {
            for (field in Theme::class.java.declaredFields) {
                val name = field.name
                if (!name.endsWith("Paint")) continue
                if (!(name.startsWith("chat_") || name.startsWith("dialogs_") || name.startsWith("profile_"))) continue
                val value = field.get(null) ?: continue
                when (value) {
                    is TextPaint -> if (value.typeface == null) value.typeface = tf
                    is Array<*> -> for (item in value) {
                        if (item is TextPaint && item.typeface == null) item.typeface = tf
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun onGetTypeface(cache: Hashtable<String, Typeface>, assetPath: String): Typeface? {
        val mode = InuConfig.FONT_MODE.value
        if (mode == InuConfig.FontModeItem.BUNDLED) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val key = "inu:m$mode:${InuConfig.ACTIVE_FONT_ID.value}:$assetPath"
        cache[key]?.let { return it }
        val (weight, italic) = when (assetPath) {
            AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM -> 500 to false
            AndroidUtilities.TYPEFACE_ROBOTO_EXTRA_BOLD -> 800 to false
            AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM_ITALIC -> 500 to true
            "fonts/ritalic.ttf" -> 400 to true
            "fonts/rcondensedbold.ttf" -> 700 to false
            else -> return null
        }
        val tf = when (mode) {
            InuConfig.FontModeItem.SYSTEM -> Typeface.create(null as Typeface?, weight, italic)
            InuConfig.FontModeItem.CUSTOM -> resolve(weight, italic)
            else -> null
        } ?: return null
        cache[key] = tf
        return tf
    }
}
