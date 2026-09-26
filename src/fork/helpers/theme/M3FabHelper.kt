package desu.inugram.helpers.theme

import android.graphics.drawable.Drawable
import android.view.ViewOutlineProvider
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.utils.ViewOutlineProviderImpl
import org.telegram.ui.ActionBar.Theme

object M3FabHelper {
    // MD3 standard FAB is 16dp on 56dp; scaled proportionally to TG's 48dp FABs.
    private const val RADIUS_DP = 14f

    @JvmStatic
    fun outlineProvider(): ViewOutlineProvider =
        if (InuConfig.MATERIAL3_FABS.value) {
            ViewOutlineProviderImpl.boundsWithPaddingRoundRect(0, AndroidUtilities.dpf2(RADIUS_DP))
        } else {
            ViewOutlineProviderImpl.BOUNDS_OVAL
        }

    @JvmStatic
    fun makeSelectorBackground(sizeDp: Int, accent: Int, pressed: Int): Drawable =
        if (InuConfig.MATERIAL3_FABS.value) {
            Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(RADIUS_DP), accent, pressed)
        } else {
            Theme.createSimpleSelectorCircleDrawable(AndroidUtilities.dp(sizeDp.toFloat()), accent, pressed)
        }
}
