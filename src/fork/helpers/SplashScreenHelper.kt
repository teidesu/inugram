package desu.inugram.helpers

import android.app.Activity
import android.content.res.Resources
import android.os.Build
import desu.inugram.helpers.theme.MonetHelper
import desu.inugram.helpers.theme.MonetHelper.ThemeMode
import org.telegram.messenger.R
import org.telegram.ui.LauncherIconController
import org.telegram.ui.LauncherIconController.LauncherIcon

object SplashScreenHelper {
    @JvmStatic
    fun sync(activity: Activity?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || activity == null) return
        val icon = LauncherIcon.entries.firstOrNull { LauncherIconController.isEnabled(it) }
        // Each icon has the default background, followed by the five Monet modes.
        val themes = when (icon) {
            LauncherIcon.STOCK, LauncherIcon.DISGUISE -> intArrayOf(
                R.style.InuSplashStock,
                R.style.InuSplashStockMonetLight,
                R.style.InuSplashStockMonetDark,
                R.style.InuSplashStockMonetAmoled,
                R.style.InuSplashStockMonetAuto,
                R.style.InuSplashStockMonetAutoAmoled,
            )
            LauncherIcon.VINTAGE -> intArrayOf(
                R.style.InuSplashVintage,
                R.style.InuSplashVintageMonetLight,
                R.style.InuSplashVintageMonetDark,
                R.style.InuSplashVintageMonetAmoled,
                R.style.InuSplashVintageMonetAuto,
                R.style.InuSplashVintageMonetAutoAmoled,
            )
            LauncherIcon.AQUA -> intArrayOf(
                R.style.InuSplashAqua,
                R.style.InuSplashAquaMonetLight,
                R.style.InuSplashAquaMonetDark,
                R.style.InuSplashAquaMonetAmoled,
                R.style.InuSplashAquaMonetAuto,
                R.style.InuSplashAquaMonetAutoAmoled,
            )
            LauncherIcon.PREMIUM -> intArrayOf(
                R.style.InuSplashPremium,
                R.style.InuSplashPremiumMonetLight,
                R.style.InuSplashPremiumMonetDark,
                R.style.InuSplashPremiumMonetAmoled,
                R.style.InuSplashPremiumMonetAuto,
                R.style.InuSplashPremiumMonetAutoAmoled,
            )
            LauncherIcon.TURBO -> intArrayOf(
                R.style.InuSplashTurbo,
                R.style.InuSplashTurboMonetLight,
                R.style.InuSplashTurboMonetDark,
                R.style.InuSplashTurboMonetAmoled,
                R.style.InuSplashTurboMonetAuto,
                R.style.InuSplashTurboMonetAutoAmoled,
            )
            LauncherIcon.NOX -> intArrayOf(
                R.style.InuSplashNox,
                R.style.InuSplashNoxMonetLight,
                R.style.InuSplashNoxMonetDark,
                R.style.InuSplashNoxMonetAmoled,
                R.style.InuSplashNoxMonetAuto,
                R.style.InuSplashNoxMonetAutoAmoled,
            )
            else -> intArrayOf(
                Resources.ID_NULL,
                R.style.InuSplashMonetLight,
                R.style.InuSplashMonetDark,
                R.style.InuSplashMonetAmoled,
                R.style.InuSplashMonetAuto,
                R.style.InuSplashMonetAutoAmoled,
            )
        }
        val themeIndex = when (MonetHelper.getThemeMode()) {
            ThemeMode.DISABLED -> 0
            ThemeMode.LIGHT -> 1
            ThemeMode.DARK -> 2
            ThemeMode.AMOLED -> 3
            ThemeMode.AUTO -> 4
            ThemeMode.AUTO_AMOLED -> 5
        }
        // Android persists the theme by name and uses it before the app starts next time.
        activity.splashScreen.setSplashScreenTheme(themes[themeIndex])
    }
}
