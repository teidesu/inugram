package desu.inugram.helpers.font

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.os.Build
import android.text.TextPaint
import android.widget.TextView
import androidx.annotation.RequiresApi
import desu.inugram.helpers.font.FontConfig.FontMode
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.ui.ActionBar.Theme
import java.lang.reflect.Field
import java.util.Hashtable

object FontHelper {
    private const val TAG = "InuFonts"

    // "real" default/mono fonts captured before they are replaced with reflection
    val stockDefault: Typeface = Typeface.DEFAULT
    val stockMonospace: Typeface = Typeface.MONOSPACE

    private val typefaceDefaultField = Typeface::class.java.getDeclaredField("DEFAULT")
    private val typefaceDefaultBoldField = Typeface::class.java.getDeclaredField("DEFAULT_BOLD")
    private val typefaceMonospaceField = Typeface::class.java.getDeclaredField("MONOSPACE")
    private val typefaceSansSerifField = Typeface::class.java.getDeclaredField("SANS_SERIF")

    fun init(context: Context) {
        FontLibrary.loadStorage(context)
        validateActiveAppFont()

        try {
            typefaceDefaultField.isAccessible = true
            typefaceDefaultBoldField.isAccessible = true
            typefaceMonospaceField.isAccessible = true
            typefaceSansSerifField.isAccessible = true
        } catch (_: Exception) {
        }
    }

    /**
     * Reverts the app font to the bundled default when the active custom selection no longer resolves.
     * Only imported families can be the app font; built-in / system fonts are editor-roster-only.
     */
    fun validateActiveAppFont() {
        val m = FontConfig.FONT.value as? FontMode.Custom ?: return
        val id = m.fontId as? FontId.Family ?: run {
            FileLog.d("$TAG: validateActiveAppFont: non-family app font ${m.fontId.token()}, resetting to bundled")
            return resetAppFont()
        }
        // a Family with an empty id is the legacy "first family" marker → valid as long as any family exists
        if (if (id.id.isEmpty()) !FontLibrary.hasAnyFamily() else !FontLibrary.containsFamily(id.id)) {
            FileLog.d("$TAG: validateActiveAppFont: family ${id.id} not loaded, resetting to bundled")
            resetAppFont()
        }
    }

    fun resetAppFont() {
        FontConfig.FONT.value = FontMode.Bundled
    }

    fun isActiveCustomFont(id: FontId): Boolean {
        val m = FontConfig.FONT.value as? FontMode.Custom ?: return false
        return maybeResolveLegacyEmpty(m.fontId) == id
    }

    /** resolve the legacy font id marker (empty string) to the first available family */
    fun maybeResolveLegacyEmpty(fontId: FontId): FontId? {
        if (fontId is FontId.Family && fontId.id.isEmpty()) {
            return FontLibrary.firstFamilyId()?.let { FontId.Family(it) }
        }
        return fontId
    }

    /** Ordered fallbacks of the active custom stack (empty for bundled / system). */
    fun getActiveFallbackIds(): List<FontId> =
        (FontConfig.FONT.value as? FontMode.Custom)?.fallbacks ?: emptyList()

    // ---- app UI font resolution ------------------------------------------------------------

    private fun resolve(targetWeight: Int, targetItalic: Boolean): Typeface? {
        val m = FontConfig.FONT.value as? FontMode.Custom ?: return null
        val primary = maybeResolveLegacyEmpty(m.fontId) ?: return null
        // composite (primary + fallbacks) needs the FontFamily APIs (Q+); below that, single typeface.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            composite(primary, m.fallbacks, targetWeight, targetItalic)?.let { return it }
        }
        return singleTokenTypeface(primary, targetWeight, targetItalic)
    }

    /** Single imported-family weighted typeface; the below-Q & no-fallback path. */
    private fun singleTokenTypeface(token: FontId?, weight: Int, italic: Boolean): Typeface? {
        return FontLibrary.getFamilyTypeface(token ?: return null, weight, italic)
    }

    /** A single imported family as a [FontFamily] at the requested style, for stack composition (Q+). */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun fontFamilyForToken(token: FontId, weight: Int, italic: Boolean): FontFamily? {
        val font = FontLibrary.getFont(token, weight, italic) ?: run {
            FileLog.d("$TAG: fontFamilyForToken: no font for ${token.token()} w=$weight i=$italic")
            return null
        }
        return try {
            FontFamily.Builder(font).build()
        } catch (e: Throwable) {
            FileLog.e("$TAG: fontFamilyForToken: FontFamily.Builder failed for ${token.token()}", e)
            null
        }
    }

    /**
     * Composes [primary] + [fallbacks] into one typeface via [Typeface.CustomFallbackBuilder]; the builder's
     * implicit final fallback is whatever [Typeface.DEFAULT] points at when [build][Typeface.CustomFallbackBuilder.build]
     * runs. Returns null when the primary is a single face with no fallbacks (the cheaper single-typeface
     * path suffices) or it can't be built (caller falls back).
     *
     * [forceSystemFallback] keeps the builder even for that single-face case, so the implicit fallback is
     * present to render missing glyphs (e.g. Cyrillic in a Latin-only font). For the editor preview that
     * implicit fallback must be the *genuine* system font, not the applied app font our `installGlobal`
     * swap repointed [Typeface.DEFAULT] at — [buildPreviewTypefaces] restores the genuine default around the build.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun composite(
        primary: FontId,
        fallbacks: List<FontId>,
        weight: Int,
        italic: Boolean,
        forceSystemFallback: Boolean = false,
    ): Typeface? {
        if (!forceSystemFallback && fallbacks.isEmpty()) return null
        val primaryFamily = fontFamilyForToken(primary, weight, italic) ?: return null
        return try {
            val b = Typeface.CustomFallbackBuilder(primaryFamily)
            for (tok in fallbacks) {
                if (tok == primary) continue
                val fam = fontFamilyForToken(tok, weight, italic) ?: continue
                try {
                    b.addCustomFallback(fam)
                } catch (_: Throwable) {
                    break
                } // 64-family cap
            }
            b.setStyle(FontStyle(weight.coerceIn(1, 1000), if (italic) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT))
            val tf = b.build()

            // setStyle won't fake-bold a medium-ish request (see Family.resolve) — force it when the
            // primary has no face that heavy, else "bold" in the stack would render like regular.
            val primaryLacksWeight = weight >= 500 &&
                (FontLibrary.getFontFamily(primary)?.lacksWeight(weight, italic) ?: false)
            if (primaryLacksWeight) {
                Typeface.create(tf, if (italic) Typeface.BOLD_ITALIC else Typeface.BOLD)
            } else {
                tf
            }
        } catch (e: Throwable) {
            FileLog.e("$TAG: composite: failed for primary=${primary.token()} w=$weight i=$italic", e)
            null
        }
    }

    // ---- stack preview (draft state, not config) -------------------------------------------

    /**
     * Builds a typeface for an arbitrary draft stack: [primary] is null (bundled default → caller uses
     * [Typeface.DEFAULT]), [FontConfig.SYSTEM_STACK_ID] (device default), or a roster token. Used by the stack editor.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun getPreviewTypeface(primary: String?, fallbacks: List<String>, weight: Int, italic: Boolean): Typeface? {
        primary ?: return null
        if (primary == FontConfig.SYSTEM_STACK_ID) return Typeface.create(null as Typeface?, weight, italic)
        val primaryId = FontId.parse(primary)
        val fallbackIds = fallbacks.filter { it != FontConfig.SYSTEM_STACK_ID }.map { FontId.parse(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // forceSystemFallback: preview missing glyphs in the genuine system font, not the applied one
            composite(primaryId, fallbackIds, weight, italic, forceSystemFallback = true)?.let { return it }
        }
        return singleTokenTypeface(primaryId, weight, italic)
    }

    /** Draft-stack typefaces for the settings preview: regular / bold / italic spans + the mono override. */
    class PreviewTypefaces(
        val regular: Typeface,
        val bold: Typeface,
        val italic: Typeface,
        val mono: Typeface,
    )

    /**
     * Builds the draft stack's typefaces for the (span-rendered) settings preview. [Typeface.DEFAULT] is
     * restored to the genuine system default around the build so a composite's implicit fallback renders
     * missing glyphs in the real system font, not whatever `installGlobal` may have applied app-wide.
     * UI thread only.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun buildPreviewTypefaces(primary: String?, fallbacks: List<String>, monoToken: String): PreviewTypefaces {
        val savedDefault = Typeface.DEFAULT
        trySetStatic(typefaceDefaultField, stockDefault)
        try {
            fun tf(weight: Int, italic: Boolean): Typeface =
                (if (primary != null) getPreviewTypeface(primary, fallbacks, weight, italic) else null)
                    ?: Typeface.create(stockDefault, weight, italic)
            return PreviewTypefaces(
                tf(400, false), tf(700, false), tf(400, true),
                FontLibrary.getTypefaceFor(monoToken) ?: stockMonospace,
            )
        } finally {
            trySetStatic(typefaceDefaultField, savedDefault)
        }
    }

    private fun styleForAsset(assetPath: String): Pair<Int, Boolean>? = when (assetPath) {
        AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM -> 500 to false
        AndroidUtilities.TYPEFACE_ROBOTO_EXTRA_BOLD -> 800 to false
        AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM_ITALIC -> 500 to true
        "fonts/ritalic.ttf" -> 400 to true
        "fonts/rcondensedbold.ttf" -> 700 to false
        else -> null
    }

    /**
     * Installs the configured app + monospace fonts process-wide by swapping the global [Typeface] statics
     * ([Typeface.DEFAULT]/[Typeface.DEFAULT_BOLD]/[Typeface.SANS_SERIF]/[Typeface.MONOSPACE]) and the
     * matching `sSystemFontMap` entries, so UI widgets that don't go through [Typeface.create] from an asset
     * path (TextView default, chat_msgTextPaint, code spans…) also pick them up.
     *
     * Reflection, best-effort — failures are non-fatal (a blocked `sSystemFontMap` still leaves the static
     * swaps, which cover most cases). Run before the Theme paints are created (from [InuHooks.init]) so they
     * capture the swapped values.
     */
    @Suppress("UNCHECKED_CAST")
    @SuppressLint("DiscouragedPrivateApi")
    @RequiresApi(Build.VERSION_CODES.P)
    fun installGlobal() {
        val map = try {
            Typeface::class.java.getDeclaredField("sSystemFontMap").apply { isAccessible = true }
                .get(null) as? MutableMap<String, Typeface>
        } catch (_: Throwable) {
            null
        }

        // app UI font (sans-serif*) — only when a custom font is selected
        val mode = FontConfig.FONT.value
        val regular = (mode as? FontMode.Custom)?.let { resolve(400, false) }
        if (mode is FontMode.Custom && regular == null) {
            FileLog.d("$TAG: installGlobal: active custom font ${mode.fontId.token()} did not resolve, app font unchanged")
        }
        if (regular != null) {
            val bold = resolve(700, false) ?: regular
            trySetStatic(typefaceDefaultField, regular)
            trySetStatic(typefaceDefaultBoldField, bold)
            trySetStatic(typefaceSansSerifField, regular)
            for (k in arrayOf(
                "sans-serif", "sans-serif-light", "sans-serif-thin",
                "sans-serif-condensed", "sans-serif-condensed-light"
            )) map?.put(k, regular)
            for (k in arrayOf("sans-serif-medium", "sans-serif-black")) map?.put(k, bold)
        }
        // monospace font (inline code + blocks) — no-op when unset
        FontLibrary.getTypefaceFor(FontConfig.MONO_FONT.value)?.let { mono ->
            trySetStatic(typefaceMonospaceField, mono)
            map?.put("monospace", mono)
        }
    }

    private fun trySetStatic(field: Field, value: Any) {
        try {
            field.set(null, value)
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun applyDefaultFont(paint: TextPaint?) {
        if (paint == null || paint.typeface != null) return
        if (FontConfig.FONT.value !is FontMode.Custom) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        resolve(400, false)?.let { paint.typeface = it }
    }

    @JvmStatic
    fun applyDefaultFont(view: TextView?) {
        if (view == null) return
        if (FontConfig.FONT.value !is FontMode.Custom) return
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
        if (FontConfig.FONT.value !is FontMode.Custom) return
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        // cheap filter first: only a handful of asset paths carry a style — the rest skip the key build
        val (weight, italic) = styleForAsset(assetPath) ?: return null
        val mode = FontConfig.FONT.value
        val keyPart = when (mode) {
            FontMode.Bundled -> return null
            FontMode.System -> "sys"
            is FontMode.Custom -> "c:${mode.fontId.token()}:${mode.fallbacks.joinToString(",") { it.token() }}"
        }
        val key = "inu:$keyPart:$assetPath"
        cache[key]?.let { return it }
        val tf = when (mode) {
            FontMode.System -> Typeface.create(null as Typeface?, weight, italic)
            is FontMode.Custom -> resolve(weight, italic)
            else -> null
        } ?: return null
        cache[key] = tf
        return tf
    }
}
