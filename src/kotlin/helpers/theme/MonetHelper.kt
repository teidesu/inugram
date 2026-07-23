package desu.inugram.helpers.theme

import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.PatternMatcher
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import desu.inugram.InuConfig
import google_material.Blend
import google_material.Hct
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLog
import org.telegram.messenger.NotificationCenter
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.LaunchActivity
import java.io.File
import kotlin.math.max


@RequiresApi(api = Build.VERSION_CODES.S)
object MonetHelper {
    private const val DARK_NAME_SOFTEN_RATIO = 0.22f
    private val MODIFIER_REGEX = Regex("^([^(]+)\\(([^)]+)\\)?$")
    private val ids: HashMap<String?, Int?> = object : HashMap<String?, Int?>() {
        init {
            put("a1_0", android.R.color.system_accent1_0)
            put("a1_10", android.R.color.system_accent1_10)
            put("a1_50", android.R.color.system_accent1_50)
            put("a1_100", android.R.color.system_accent1_100)
            put("a1_200", android.R.color.system_accent1_200)
            put("a1_300", android.R.color.system_accent1_300)
            put("a1_400", android.R.color.system_accent1_400)
            put("a1_500", android.R.color.system_accent1_500)
            put("a1_600", android.R.color.system_accent1_600)
            put("a1_700", android.R.color.system_accent1_700)
            put("a1_800", android.R.color.system_accent1_800)
            put("a1_900", android.R.color.system_accent1_900)
            put("a1_1000", android.R.color.system_accent1_1000)
            put("a2_0", android.R.color.system_accent2_0)
            put("a2_10", android.R.color.system_accent2_10)
            put("a2_50", android.R.color.system_accent2_50)
            put("a2_100", android.R.color.system_accent2_100)
            put("a2_200", android.R.color.system_accent2_200)
            put("a2_300", android.R.color.system_accent2_300)
            put("a2_400", android.R.color.system_accent2_400)
            put("a2_500", android.R.color.system_accent2_500)
            put("a2_600", android.R.color.system_accent2_600)
            put("a2_700", android.R.color.system_accent2_700)
            put("a2_800", android.R.color.system_accent2_800)
            put("a2_900", android.R.color.system_accent2_900)
            put("a2_1000", android.R.color.system_accent2_1000)
            put("a3_0", android.R.color.system_accent3_0)
            put("a3_10", android.R.color.system_accent3_10)
            put("a3_50", android.R.color.system_accent3_50)
            put("a3_100", android.R.color.system_accent3_100)
            put("a3_200", android.R.color.system_accent3_200)
            put("a3_300", android.R.color.system_accent3_300)
            put("a3_400", android.R.color.system_accent3_400)
            put("a3_500", android.R.color.system_accent3_500)
            put("a3_600", android.R.color.system_accent3_600)
            put("a3_700", android.R.color.system_accent3_700)
            put("a3_800", android.R.color.system_accent3_800)
            put("a3_900", android.R.color.system_accent3_900)
            put("a3_1000", android.R.color.system_accent3_1000)
            put("n1_0", android.R.color.system_neutral1_0)
            put("n1_10", android.R.color.system_neutral1_10)
            put("n1_50", android.R.color.system_neutral1_50)
            put("n1_100", android.R.color.system_neutral1_100)
            put("n1_200", android.R.color.system_neutral1_200)
            put("n1_300", android.R.color.system_neutral1_300)
            put("n1_400", android.R.color.system_neutral1_400)
            put("n1_500", android.R.color.system_neutral1_500)
            put("n1_600", android.R.color.system_neutral1_600)
            put("n1_700", android.R.color.system_neutral1_700)
            put("n1_800", android.R.color.system_neutral1_800)
            put("n1_900", android.R.color.system_neutral1_900)
            put("n1_1000", android.R.color.system_neutral1_1000)
            put("n2_0", android.R.color.system_neutral2_0)
            put("n2_10", android.R.color.system_neutral2_10)
            put("n2_50", android.R.color.system_neutral2_50)
            put("n2_100", android.R.color.system_neutral2_100)
            put("n2_200", android.R.color.system_neutral2_200)
            put("n2_300", android.R.color.system_neutral2_300)
            put("n2_400", android.R.color.system_neutral2_400)
            put("n2_500", android.R.color.system_neutral2_500)
            put("n2_600", android.R.color.system_neutral2_600)
            put("n2_700", android.R.color.system_neutral2_700)
            put("n2_800", android.R.color.system_neutral2_800)
            put("n2_900", android.R.color.system_neutral2_900)
            put("n2_1000", android.R.color.system_neutral2_1000)
        }
    }

    // M3 semantic tokens (added in API 34, UPSIDE_DOWN_CAKE).
    // Resource IDs resolve at runtime only on API 34+; older versions fall back to the
    // closest palette tone / hardcoded M3 default. Each token name is the Android resource
    // name verbatim (e.g. `surface_bright_light` -> `android.R.color.system_surface_bright_light`).
    private data class SemanticToken(val resourceId: Int, val fallback: String)

    private val semantic: HashMap<String, SemanticToken> = hashMapOf(
        // primary
        "monet_primary_light" to SemanticToken(android.R.color.system_primary_light, "a1_600"),
        "monet_primary_dark" to SemanticToken(android.R.color.system_primary_dark, "a1_200"),
        "monet_on_primary_light" to SemanticToken(android.R.color.system_on_primary_light, "a1_0"),
        "monet_on_primary_dark" to SemanticToken(android.R.color.system_on_primary_dark, "a1_800"),
        "monet_primary_container_light" to SemanticToken(android.R.color.system_primary_container_light, "a1_100"),
        "monet_primary_container_dark" to SemanticToken(android.R.color.system_primary_container_dark, "a1_700"),
        "monet_on_primary_container_light" to SemanticToken(android.R.color.system_on_primary_container_light, "a1_900"),
        "monet_on_primary_container_dark" to SemanticToken(android.R.color.system_on_primary_container_dark, "a1_100"),
//        "monet_inverse_primary_light" to SemanticToken(android.R.color.system_inverse_primary_light, "a1_200"),
//        "monet_inverse_primary_dark" to SemanticToken(android.R.color.system_inverse_primary_dark, "a1_700"),

        // secondary
        "monet_secondary_light" to SemanticToken(android.R.color.system_secondary_light, "a2_600"),
        "monet_secondary_dark" to SemanticToken(android.R.color.system_secondary_dark, "a2_200"),
        "monet_on_secondary_light" to SemanticToken(android.R.color.system_on_secondary_light, "a2_0"),
        "monet_on_secondary_dark" to SemanticToken(android.R.color.system_on_secondary_dark, "a2_800"),
        "monet_secondary_container_light" to SemanticToken(android.R.color.system_secondary_container_light, "a2_100"),
        "monet_secondary_container_dark" to SemanticToken(android.R.color.system_secondary_container_dark, "a2_700"),
        "monet_on_secondary_container_light" to SemanticToken(android.R.color.system_on_secondary_container_light, "a2_900"),
        "monet_on_secondary_container_dark" to SemanticToken(android.R.color.system_on_secondary_container_dark, "a2_100"),

        // tertiary
        "monet_tertiary_light" to SemanticToken(android.R.color.system_tertiary_light, "a3_600"),
        "monet_tertiary_dark" to SemanticToken(android.R.color.system_tertiary_dark, "a3_200"),
        "monet_on_tertiary_light" to SemanticToken(android.R.color.system_on_tertiary_light, "a3_0"),
        "monet_on_tertiary_dark" to SemanticToken(android.R.color.system_on_tertiary_dark, "a3_800"),
        "monet_tertiary_container_light" to SemanticToken(android.R.color.system_tertiary_container_light, "a3_100"),
        "monet_tertiary_container_dark" to SemanticToken(android.R.color.system_tertiary_container_dark, "a3_700"),
        "monet_on_tertiary_container_light" to SemanticToken(android.R.color.system_on_tertiary_container_light, "a3_900"),
        "monet_on_tertiary_container_dark" to SemanticToken(android.R.color.system_on_tertiary_container_dark, "a3_100"),

        // error (M3 default error palette; not derived from Monet)
        "monet_error_light" to SemanticToken(android.R.color.system_error_light, "#B3261E"),
        "monet_error_dark" to SemanticToken(android.R.color.system_error_dark, "#F2B8B5"),
        "monet_on_error_light" to SemanticToken(android.R.color.system_on_error_light, "#FFFFFF"),
        "monet_on_error_dark" to SemanticToken(android.R.color.system_on_error_dark, "#601410"),
        "monet_error_container_light" to SemanticToken(android.R.color.system_error_container_light, "#F9DEDC"),
        "monet_error_container_dark" to SemanticToken(android.R.color.system_error_container_dark, "#8C1D18"),
        "monet_on_error_container_light" to SemanticToken(android.R.color.system_on_error_container_light, "#410E0B"),
        "monet_on_error_container_dark" to SemanticToken(android.R.color.system_on_error_container_dark, "#F9DEDC"),

        // background & surface base
        "monet_background_light" to SemanticToken(android.R.color.system_background_light, "n1_10"),
        "monet_background_dark" to SemanticToken(android.R.color.system_background_dark, "n1_900"),
        "monet_on_background_light" to SemanticToken(android.R.color.system_on_background_light, "n1_900"),
        "monet_on_background_dark" to SemanticToken(android.R.color.system_on_background_dark, "n1_100"),
        "monet_surface_light" to SemanticToken(android.R.color.system_surface_light, "n1_10"),
        "monet_surface_dark" to SemanticToken(android.R.color.system_surface_dark, "n1_900"),
        "monet_on_surface_light" to SemanticToken(android.R.color.system_on_surface_light, "n1_900"),
        "monet_on_surface_dark" to SemanticToken(android.R.color.system_on_surface_dark, "n1_100"),
        "monet_surface_variant_light" to SemanticToken(android.R.color.system_surface_variant_light, "n2_100"),
        "monet_surface_variant_dark" to SemanticToken(android.R.color.system_surface_variant_dark, "n2_700"),
        "monet_on_surface_variant_light" to SemanticToken(android.R.color.system_on_surface_variant_light, "n2_700"),
        "monet_on_surface_variant_dark" to SemanticToken(android.R.color.system_on_surface_variant_dark, "n2_200"),

        // surface tonal variants
        "monet_surface_bright_light" to SemanticToken(android.R.color.system_surface_bright_light, "n1_10"),
        "monet_surface_bright_dark" to SemanticToken(android.R.color.system_surface_bright_dark, "n1_800"),
        "monet_surface_dim_light" to SemanticToken(android.R.color.system_surface_dim_light, "n1_100"),
        "monet_surface_dim_dark" to SemanticToken(android.R.color.system_surface_dim_dark, "n1_900"),

        // surface containers
        "monet_surface_container_lowest_light" to SemanticToken(android.R.color.system_surface_container_lowest_light, "n1_0"),
        "monet_surface_container_lowest_dark" to SemanticToken(android.R.color.system_surface_container_lowest_dark, "n1_1000"),
        "monet_surface_container_low_light" to SemanticToken(android.R.color.system_surface_container_low_light, "n1_10"),
        "monet_surface_container_low_dark" to SemanticToken(android.R.color.system_surface_container_low_dark, "n1_900"),
        "monet_surface_container_light" to SemanticToken(android.R.color.system_surface_container_light, "n1_50"),
        "monet_surface_container_dark" to SemanticToken(android.R.color.system_surface_container_dark, "n1_900"),
        "monet_surface_container_high_light" to SemanticToken(android.R.color.system_surface_container_high_light, "n1_100"),
        "monet_surface_container_high_dark" to SemanticToken(android.R.color.system_surface_container_high_dark, "n1_800"),
        "monet_surface_container_highest_light" to SemanticToken(android.R.color.system_surface_container_highest_light, "n1_200"),
        "monet_surface_container_highest_dark" to SemanticToken(android.R.color.system_surface_container_highest_dark, "n1_800"),

        // outline
        "monet_outline_light" to SemanticToken(android.R.color.system_outline_light, "n2_500"),
        "monet_outline_dark" to SemanticToken(android.R.color.system_outline_dark, "n2_400"),
        "monet_outline_variant_light" to SemanticToken(android.R.color.system_outline_variant_light, "n2_200"),
        "monet_outline_variant_dark" to SemanticToken(android.R.color.system_outline_variant_dark, "n2_700"),

        // inverse
//        "monet_inverse_surface_light" to SemanticToken(android.R.color.system_inverse_surface_light, "n1_800"),
//        "monet_inverse_surface_dark" to SemanticToken(android.R.color.system_inverse_surface_dark, "n1_100"),
//        "monet_inverse_on_surface_light" to SemanticToken(android.R.color.system_inverse_on_surface_light, "n1_50"),
//        "monet_inverse_on_surface_dark" to SemanticToken(android.R.color.system_inverse_on_surface_dark, "n1_800"),
    )

    private val customColors: HashMap<String?, Int?> = object : HashMap<String?, Int?>() {
        init {
            put("monetAvatarRed", -0x7ba2)
            put("monetAvatarOrange", -0x158d6)
            put("monetAvatarViolet", -0x3e5f01)
            put("monetAvatarGreen", -0x7b3ebc)
            put("monetAvatarCyan", -0xff3e22)
            put("monetAvatarBlue", -0x974901)
            put("monetAvatarPink", -0x7152)
            put("monetAvatarNameRed", -0x33afb7)
            put("monetAvatarNameOrange", -0x2988de)
            put("monetAvatarNameViolet", -0x6aa325)
            put("monetAvatarNameGreen", -0xbf56e0)
            put("monetAvatarNameCyan", -0xcf6146)
            put("monetAvatarNameBlue", -0xc9752f)
            put("monetAvatarNamePink", -0x38af75)
            put("monetAvatarNameDarkRed", -0x33afb7)
            put("monetAvatarNameDarkOrange", -0x2988de)
            put("monetAvatarNameDarkViolet", -0x6aa325)
            put("monetAvatarNameDarkGreen", -0xbf56e0)
            put("monetAvatarNameDarkCyan", -0xcf6146)
            put("monetAvatarNameDarkBlue", -0xc9752f)
            put("monetAvatarNameDarkPink", -0x38af75)
            put("monetRedCall", 0xFFEF5350.toInt())
            put("monetGreen", 0xFF4CAF50.toInt())
            put("mRed200", 0xFFEF9A9A.toInt())
            put("mRed800", 0xFFC62828.toInt())
            put("mGreen200", 0xFFA5D6A7.toInt())
            put("mGreen800", 0xFF2E7D32.toInt())
            put("monetYellow", 0xFFFFB74D.toInt())
            put("monetCodeKeyword", 0xFFE05356.toInt())
            put("monetCodeOperator", 0xFF4DBBFF.toInt())
            put("monetCodeConstant", 0xFF7F79F3.toInt())
            put("monetCodeString", 0xFF37C123.toInt())
            put("monetCodeNumber", 0xFF327FE5.toInt())
            put("monetCodeFunction", 0xFFF28C39.toInt())
        }
    }
    private var lastMonetSignature: Long? = null
    private val peerColorCache = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val avatarTextColorCache = java.util.concurrent.ConcurrentHashMap<Long, Int>()
    private const val AVATAR_TEXT_DARK_TONE = 15.0
    private const val AVATAR_TEXT_MIN_CHROMA = 40.0
    private var overlayReceiverRegistered = false
    private var themeReloadReceiverRegistered = false

    private const val THEME_OVERRIDE_DIR = "theme-override"
    private const val ACTION_RELOAD_THEME = "desu.inugram.RELOAD_THEME"

    private val overlayChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "android.intent.action.OVERLAY_CHANGED") {
                refreshMonetThemeIfChanged()
            }
        }
    }

    fun registerOverlayChangeReceiver(context: Context) {
        if (overlayReceiverRegistered) return
        try {
            val filter = IntentFilter("android.intent.action.OVERLAY_CHANGED")
            filter.addDataScheme("package")
            filter.addDataSchemeSpecificPart("android", PatternMatcher.PATTERN_LITERAL)
            context.registerReceiver(overlayChangeReceiver, filter)
            overlayReceiverRegistered = true
        } catch (e: Exception) {
            FileLog.e("Failed to register OVERLAY_CHANGED receiver", e)
        }
    }

    private val themeReloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_RELOAD_THEME) return
            Log.d("MonetHelper", "theme reload broadcast: applied=${reapplyActiveTheme()}")
        }
    }

    fun registerThemeReloadReceiver(context: Context) {
        if (!BuildVars.DEBUG_VERSION || themeReloadReceiverRegistered) return
        try {
            val filter = IntentFilter(ACTION_RELOAD_THEME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                context.registerReceiver(themeReloadReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(themeReloadReceiver, filter)
            }
            themeReloadReceiverRegistered = true
        } catch (e: Exception) {
            FileLog.e("Failed to register theme reload receiver", e)
        }
    }

    private fun getThemeOverrideDir(): File? =
        ApplicationLoader.applicationContext.getExternalFilesDir(THEME_OVERRIDE_DIR)

    @JvmStatic
    fun getThemeOverrideFile(assetName: String): File? {
        if (!BuildVars.DEBUG_VERSION) return null
        val file = File(getThemeOverrideDir() ?: return null, assetName)
        return if (file.isFile) file else null
    }

    // Theme keys whose values represent link *text*. When the wallpaper-derived accent palette
    // collapses to near-neutral (e.g. B&W wallpapers), these would render at the same hue as
    // body text and become indistinguishable. We substitute a chromatic fallback in that case.
    private val LINK_TEXT_KEYS = setOf(
        "chat_messageLinkIn",
        "chat_messageLinkOut",
        "windowBackgroundWhiteLinkText",
        "dialogTextLink",
    )
    private const val MAX_CHROMA_PERCENT = 400
    private const val NEUTRAL_ACCENT_CHROMA_THRESHOLD = 8.0
    private const val FALLBACK_LINK_HUE = 250.0
    private const val FALLBACK_LINK_CHROMA = 70.0

    @JvmStatic
    fun getColor(color: String?): Int = getColor(color, null)

    @JvmStatic
    fun getColor(color: String?, key: String?): Int {
        try {
            var working = color?.trim { it <= ' ' } ?: ""
            var alphaPercent = 100
            var saturationPercent = 100
            var lightnessPercent = 100
            var tone = -1
            var chromaPercent = 100

            val match = MODIFIER_REGEX.find(working)
            if (match != null) {
                working = match.groupValues[1].trim()
                for (part in match.groupValues[2].split(',')) {
                    val kv = part.split('=')
                    if (kv.size != 2) continue
                    val raw = kv[1].trim().toIntOrNull() ?: continue
                    when (kv[0].trim()) {
                        "a" -> alphaPercent = raw.coerceIn(0, 100)
                        "s" -> saturationPercent = raw.coerceIn(0, 100)
                        "l" -> lightnessPercent = raw.coerceIn(0, 100)
                        "t" -> tone = raw.coerceIn(0, 100)
                        "c" -> chromaPercent = raw.coerceIn(0, MAX_CHROMA_PERCENT)
                    }
                }
            }

            var resolvedColor = resolveColor(working)
            if (tone >= 0 || chromaPercent != 100) {
                val hct = Hct.fromInt(resolvedColor)
                resolvedColor = Hct.from(
                    hct.hue,
                    hct.chroma * chromaPercent / 100.0,
                    if (tone >= 0) tone.toDouble() else hct.tone,
                ).toInt()
            }
            if (saturationPercent != 100) {
                resolvedColor = ColorUtils.blendARGB(Color.WHITE, resolvedColor, saturationPercent / 100f)
            }
            if (lightnessPercent != 100) {
                resolvedColor = ColorUtils.blendARGB(Color.BLACK, resolvedColor, lightnessPercent / 100f)
            }
            if (alphaPercent != 100) {
                resolvedColor = ColorUtils.setAlphaComponent(resolvedColor, alphaPercent * 255 / 100)
            }
            if (key != null && key in LINK_TEXT_KEYS && working.startsWith("a") && isAccentPaletteNeutral()) {
                return makeChromaticLink(resolvedColor)
            }
            return resolvedColor
        } catch (e: Exception) {
            FileLog.e("Error loading color $color", e)
            return 0
        }
    }

    private fun isAccentPaletteNeutral(): Boolean {
        return try {
            Hct.fromInt(resolveColor("a1_500")).chroma < NEUTRAL_ACCENT_CHROMA_THRESHOLD
        } catch (_: Exception) {
            false
        }
    }

    private fun makeChromaticLink(original: Int): Int {
        val tone = Hct.fromInt(original).tone
        val argb = Hct.from(FALLBACK_LINK_HUE, FALLBACK_LINK_CHROMA, tone).toInt()
        return ColorUtils.setAlphaComponent(argb, Color.alpha(original))
    }

    private val aliases = hashMapOf(
        "mRed500" to "monetRedCall",
        "mGreen500" to "monetGreen",
    )

    private fun resolveColor(color: String): Int {
        aliases[color]?.let { return resolveColor(it) }
        if (color == "mBlack") return Color.BLACK
        if (color == "mWhite") return Color.WHITE

        val id = ids[color]
        if (id != null) {
            return ApplicationLoader.applicationContext.getColor(id)
        }

        val avatarBaseColor = customColors[color]
        if (avatarBaseColor != null) {
            val harmonizedColor = harmonize(avatarBaseColor)
            if (color.startsWith("monetAvatarNameDark")) {
                return softenColorForDarkText(harmonizedColor)
            }
            return harmonizedColor
        }

        val semanticToken = semantic[color]
        if (semanticToken != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return ApplicationLoader.applicationContext.getColor(semanticToken.resourceId)
            }
            return if (semanticToken.fallback.startsWith("#")) {
                semanticToken.fallback.toColorInt()
            } else {
                resolveColor(semanticToken.fallback)
            }
        }

        throw IllegalArgumentException("Unknown Monet color token: $color")
    }

    @JvmStatic
    fun harmonize(baseColor: Int): Int {
        return Blend.harmonize(baseColor, resolveColor("a1_600"))
    }

    @JvmStatic
    @TargetApi(value = 26)
    fun maybeHarmonizePeerColor(color: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return color
        val activeTheme = Theme.getActiveTheme() ?: return color
        if (!activeTheme.inu_isMonet()) return color
        return peerColorCache.getOrPut(color) {
            try {
                harmonize(color)
            } catch (_: Exception) {
                color
            }
        }
    }

    @JvmStatic
    fun getOutCodeColorKey(inColorKey: Int): Int = when (inColorKey) {
        Theme.key_code_keyword -> Theme.key_inu_code_out_keyword
        Theme.key_code_operator -> Theme.key_inu_code_out_operator
        Theme.key_code_constant -> Theme.key_inu_code_out_constant
        Theme.key_code_string -> Theme.key_inu_code_out_string
        Theme.key_code_number -> Theme.key_inu_code_out_number
        Theme.key_code_comment -> Theme.key_inu_code_out_comment
        Theme.key_code_function -> Theme.key_inu_code_out_function
        else -> inColorKey
    }

    @JvmStatic
    fun getAvatarTextColor(fallback: Int, background: Int, background2: Int): Int {
        if (!InuConfig.MATERIAL3_AVATARS.value || !(Theme.getActiveTheme()?.inu_isMonetNight() ?: false)) return fallback
        val cacheKey = (background.toLong() shl 32) or (background2.toLong() and 0xFFFFFFFFL)
        return avatarTextColorCache.getOrPut(cacheKey) {
            try {
                val hct = Hct.fromInt(ColorUtils.blendARGB(background, background2, 0.5f))
                Hct.from(hct.hue, max(hct.chroma, AVATAR_TEXT_MIN_CHROMA), AVATAR_TEXT_DARK_TONE).toInt()
            } catch (_: Exception) {
                fallback
            }
        }
    }

    private fun softenColorForDarkText(color: Int): Int {
        val neutralTextColor = resolveColor("n1_50")
        return ColorUtils.blendARGB(color, neutralTextColor, DARK_NAME_SOFTEN_RATIO)
    }

    private fun getMonetSignature(): Long =
        (getColor("a1_600").toLong() shl 32) or (getColor("n1_500").toLong() and 0xFFFFFFFFL)

    fun refreshMonetThemeIfChanged() {
        val activeTheme: Theme.ThemeInfo? = Theme.getActiveTheme()
        if (activeTheme == null || !activeTheme.inu_isMonet()) {
            lastMonetSignature = null
            peerColorCache.clear()
            return
        }

        val signature = getMonetSignature()

        if (lastMonetSignature == null) {
            lastMonetSignature = signature
            return
        }

        if (lastMonetSignature == signature) {
            return
        }

        peerColorCache.clear()

        // Theme.applyTheme + needSetDayNightTheme start a crossfade animator in ActionBarLayout,
        // and only its end callback clears Theme.animatingColors. Without a resumed activity there
        // are no frames to end it, so leave the signature stale and let the next onResume apply.
        if (!reapplyActiveTheme()) return

        lastMonetSignature = signature
    }

    private fun reapplyActiveTheme(): Boolean {
        val activeTheme = Theme.getActiveTheme() ?: return false
        if (!LaunchActivity.isResumed) return false

        val isNight: Boolean = Theme.isCurrentThemeNight()
        Theme.applyTheme(activeTheme, isNight)
        NotificationCenter.getGlobalInstance().postNotificationName(
            NotificationCenter.needSetDayNightTheme, activeTheme, isNight, null, -1
        )
        return true
    }

    enum class ThemeMode { DISABLED, LIGHT, DARK, AMOLED, AUTO, AUTO_AMOLED }

    fun getThemeMode(): ThemeMode {
        if (Theme.selectedAutoNightType == Theme.AUTO_NIGHT_TYPE_SYSTEM &&
            Theme.getCurrentTheme()?.inu_isMonetLight() == true
        ) {
            val night = Theme.getCurrentNightTheme()
            if (night?.inu_isMonetDark() == true) return ThemeMode.AUTO
            if (night?.inu_isMonetAmoled() == true) return ThemeMode.AUTO_AMOLED
        }
        if (Theme.selectedAutoNightType == Theme.AUTO_NIGHT_TYPE_NONE) {
            val active = Theme.getActiveTheme()
            if (active?.inu_isMonetLight() == true) return ThemeMode.LIGHT
            if (active?.inu_isMonetDark() == true) return ThemeMode.DARK
            if (active?.inu_isMonetAmoled() == true) return ThemeMode.AMOLED
        }
        return ThemeMode.DISABLED
    }

    fun setThemeMode(mode: ThemeMode) {
        val current = getThemeMode()
        if (current == mode) return
        peerColorCache.clear()

        // entering Monet from a non-Monet state: snapshot so DISABLED can restore it
        if (current == ThemeMode.DISABLED) {
            val themeConfig = themeConfigPrefs()
            InuConfig.MONET_PREV.value = listOf(
                Theme.getCurrentTheme()?.key.orEmpty(),
                Theme.getCurrentNightTheme()?.key.orEmpty(),
                Theme.selectedAutoNightType,
                themeConfig.getString("lastDayTheme", "").orEmpty(),
                themeConfig.getString("lastDarkTheme", "").orEmpty(),
            ).joinToString("|")
        }

        when (mode) {
            ThemeMode.DISABLED -> restorePrevious()
            ThemeMode.LIGHT -> applySingle("Monet Light", dark = false)
            ThemeMode.DARK -> applySingle("Monet Dark", dark = true)
            ThemeMode.AMOLED -> applySingle("Monet AMOLED", dark = true)
            ThemeMode.AUTO -> applyAuto("Monet Dark")
            ThemeMode.AUTO_AMOLED -> applyAuto("Monet AMOLED")
        }
    }

    private fun applySingle(name: String, dark: Boolean) {
        Theme.selectedAutoNightType = Theme.AUTO_NIGHT_TYPE_NONE
        Theme.saveAutoNightThemeConfig()
        rememberLastTheme(name, dark = dark)
        applyDayTheme(Theme.getTheme(name))
    }

    private fun applyAuto(nightName: String) {
        Theme.getTheme(nightName)?.let { Theme.setCurrentNightTheme(it) }
        rememberLastTheme("Monet Light", dark = false)
        rememberLastTheme(nightName, dark = true)
        applyDayTheme(Theme.getTheme("Monet Light"))
        Theme.selectedAutoNightType = Theme.AUTO_NIGHT_TYPE_SYSTEM
        Theme.saveAutoNightThemeConfig()
        Theme.checkAutoNightThemeConditions(true)
    }

    private fun restorePrevious() {
        val snapshot = InuConfig.MONET_PREV.value.split("|")
        val dayTheme = Theme.getTheme(snapshot.getOrNull(0).orEmpty()) ?: Theme.getTheme("Blue")
        Theme.getTheme(snapshot.getOrNull(1).orEmpty())?.let { Theme.setCurrentNightTheme(it) }
        Theme.selectedAutoNightType = snapshot.getOrNull(2)?.toIntOrNull() ?: Theme.AUTO_NIGHT_TYPE_NONE
        restoreLastTheme("lastDayTheme", snapshot.getOrNull(3))
        restoreLastTheme("lastDarkTheme", snapshot.getOrNull(4))
        applyDayTheme(dayTheme)
        Theme.saveAutoNightThemeConfig()
        Theme.checkAutoNightThemeConditions(true)
        InuConfig.MONET_PREV.value = ""
    }

    private fun themeConfigPrefs() =
        ApplicationLoader.applicationContext.getSharedPreferences("themeconfig", Context.MODE_PRIVATE)

    private fun rememberLastTheme(name: String, dark: Boolean) {
        themeConfigPrefs().edit {
            putString(if (dark) "lastDarkTheme" else "lastDayTheme", name)
        }
    }

    private fun restoreLastTheme(key: String, value: String?) {
        themeConfigPrefs().edit {
            if (value.isNullOrEmpty()) remove(key) else putString(key, value)
        }
    }

    private fun applyDayTheme(theme: Theme.ThemeInfo?) {
        if (theme == null) return
        NotificationCenter.getGlobalInstance().postNotificationName(
            NotificationCenter.needSetDayNightTheme, theme, false, null, -1
        )
    }
}
