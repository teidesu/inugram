package desu.inugram.helpers

import android.graphics.Color
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import google_material.Blend
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.NotificationCenter
import org.telegram.ui.ActionBar.Theme
import kotlin.math.max
import kotlin.math.min


@RequiresApi(api = Build.VERSION_CODES.S)
object MonetHelper {
    private const val DARK_NAME_SOFTEN_RATIO = 0.22f
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
    private val customColors: HashMap<String?, Int?> = object : HashMap<String?, Int?>() {
        init {
            put("monetAvatarRed", -0x7ba2)
            put("monetAvatarOrange", -0x144a5)
            put("monetAvatarViolet", -0x496b07)
            put("monetAvatarGreen", -0x652e9c)
            put("monetAvatarCyan", -0xa4341d)
            put("monetAvatarBlue", -0xa35006)
            put("monetAvatarPink", -0x7554)
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
            put("monetRedLight", 0xB3261E)
            put("monetRedDark", 0xF2B8B5)
            put("monetRedCall", 0xEF5350)
            put("monetGreenCall", 0x4CAF50)
        }
    }
    private var lastMonetColor = 0

    @JvmStatic
    fun getColor(color: String?): Int {
        try {
            val rawColor = color?.trim { it <= ' ' } ?: ""
            var working = rawColor
            var alphaPercent: Int? = null

            val slashIdx = working.lastIndexOf('/')
            if (slashIdx > 0 && slashIdx < working.length - 1) {
                val suffix = working.substring(slashIdx + 1)
                if (isDigitsOnly(suffix)) {
                    alphaPercent = suffix.toInt()
                    working = working.substring(0, slashIdx)
                }
            }

            var baseColor = working
            var darkenPercentValue: String? = null

            val lastUnderscore = working.lastIndexOf('_')
            if (lastUnderscore > 0 && lastUnderscore < working.length - 1) {
                val suffix = working.substring(lastUnderscore + 1)
                val candidateBase = working.substring(0, lastUnderscore)
                if (isDigitsOnly(suffix) && canResolveColor(candidateBase)) {
                    baseColor = candidateBase
                    darkenPercentValue = suffix
                }
            }

            var resolvedColor = resolveColor(baseColor)
            if (darkenPercentValue != null) {
                resolvedColor = darkenByPercent(resolvedColor, darkenPercentValue.toInt())
            }
            if (alphaPercent != null) {
                val normalized = max(0, min(alphaPercent, 100))
                resolvedColor = ColorUtils.setAlphaComponent(resolvedColor, normalized * 255 / 100)
            }
            return resolvedColor
        } catch (e: Exception) {
            FileLog.e("Error loading color $color", e)
            return 0
        }
    }

    private fun canResolveColor(color: String?): Boolean {
        return ids.containsKey(color) || customColors.containsKey(color)
    }

    private fun resolveColor(color: String): Int {
        val id = ids[color]
        if (id != null) {
            return ApplicationLoader.applicationContext.getColor(id)
        }

        val avatarBaseColor = customColors[color]
        if (avatarBaseColor != null) {
            val harmonizedColor = harmonizeAvatarColor(avatarBaseColor)
            if (color.startsWith("monetAvatarNameDark")) {
                return softenColorForDarkText(harmonizedColor)
            }
            return harmonizedColor
        }

        throw IllegalArgumentException("Unknown Monet color token: $color")
    }

    @JvmStatic
    fun harmonizeAvatarColor(baseColor: Int): Int {
        return Blend.harmonize(baseColor, resolveColor("a1_600"))
    }

    private fun softenColorForDarkText(color: Int): Int {
        val neutralTextColor = resolveColor("n1_50")
        return ColorUtils.blendARGB(color, neutralTextColor, DARK_NAME_SOFTEN_RATIO)
    }

    private fun darkenByPercent(color: Int, percent: Int): Int {
        val normalizedPercent = max(1, min(percent, 100))
        if (normalizedPercent == 100) {
            return color
        }

        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[2] = max(0f, min(1f, hsl[2] * normalizedPercent / 100f))

        return ColorUtils.setAlphaComponent(ColorUtils.HSLToColor(hsl), Color.alpha(color))
    }

    private fun isDigitsOnly(value: String): Boolean {
        for (i in 0..<value.length) {
            if (!Character.isDigit(value[i])) {
                return false
            }
        }
        return !value.isEmpty()
    }

    fun refreshMonetThemeIfChanged() {
        val activeTheme: Theme.ThemeInfo? = Theme.getActiveTheme()
        if (activeTheme == null || !activeTheme.inu_isMonet()) {
            lastMonetColor = 0
            return
        }

        val currentColor = getColor("a1_600")

        if (lastMonetColor == 0) {
            lastMonetColor = currentColor
            return
        }

        if (lastMonetColor == currentColor) {
            return
        }

        val isNight: Boolean = Theme.isCurrentThemeNight()
        Theme.applyTheme(activeTheme, isNight)
        NotificationCenter.getGlobalInstance().postNotificationName(
            NotificationCenter.needSetDayNightTheme, activeTheme, isNight, null, -1
        )

        lastMonetColor = currentColor
    }
}
