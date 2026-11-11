package desu.inugram.helpers.font

import android.content.Context
import desu.inugram.helpers.font.FontConfig.FontMode
import org.telegram.messenger.FileLog
import java.io.File

/**
 * Ships Vazirmatn (SIL OFL) inside the APK and makes it the app font on the first launch.
 *
 * The font is imported into [FontLibrary] like any user-imported family, so it can still be
 * changed, hidden or removed from the font settings afterwards. It is applied only once and only
 * while the app is still on the bundled font: a font the user picked earlier is never replaced,
 * and switching back to the stock font later is not undone on the next launch.
 */
object VazirmatnHelper {
    private const val TAG = "InuFonts"
    private const val ASSET = "Vazirmatn.ttf"
    private const val FAMILY_NAME = "Vazirmatn"

    fun applyAsDefaultOnce(context: Context) {
        if (FontConfig.VAZIRMATN_DEFAULT_APPLIED.value) return
        FontConfig.VAZIRMATN_DEFAULT_APPLIED.value = true
        // respect an existing choice (system font or another custom font)
        if (FontConfig.FONT.value !is FontMode.Bundled) return

        try {
            val familyId = findFamilyId() ?: importBundledFont(context) ?: return
            FontConfig.FONT.value = FontMode.Custom(FontId.Family(familyId), emptyList())
            FileLog.d("$TAG: Vazirmatn set as the default app font ($familyId)")
        } catch (e: Throwable) {
            FileLog.e("$TAG: failed to apply Vazirmatn as default", e)
        }
    }

    private fun findFamilyId(): String? =
        FontLibrary.getCachedRoster()
            .filterIsInstance<FontId.Family>()
            .firstOrNull { FontLibrary.getFontFamily(it)?.name.equals(FAMILY_NAME, ignoreCase = true) }
            ?.id

    private fun importBundledFont(context: Context): String? {
        val tmp = File(context.cacheDir, "inu_$ASSET")
        try {
            context.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val result = FontLibrary.importFromFile(tmp)
            if (result.addedFaces == 0) {
                FileLog.d("$TAG: Vazirmatn import added no faces (rejected=${result.rejectedBySystem})")
                return null
            }
        } finally {
            tmp.delete()
        }
        return findFamilyId()
    }
}
