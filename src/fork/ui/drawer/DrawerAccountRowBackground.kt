package desu.inugram.ui.drawer

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import desu.inugram.helpers.dialogs.DrawerHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme

/** Inset ripple over a fill that keeps a dragged row opaque, tinted for the current account. */
class DrawerAccountRowBackground : LayerDrawable(
    arrayOf(GradientDrawable(), Theme.createRadSelectorDrawable(0, RADIUS_DP.toInt(), RADIUS_DP.toInt()))
) {
    init {
        val v = AndroidUtilities.dp(INSET_V_DP)
        setLayerInset(0, 0, v, 0, v)
        setLayerInset(1, 0, v, 0, v)
        (getDrawable(0) as GradientDrawable).cornerRadius = AndroidUtilities.dpf2(RADIUS_DP)
        updateColors(false)
    }

    fun updateColors(current: Boolean) {
        (getDrawable(0) as GradientDrawable).setColor(
            if (current) DrawerHelper.getTonalColor() else Theme.getColor(Theme.key_chats_menuBackground)
        )
        Theme.setSelectorDrawableColor(getDrawable(1), Theme.getColor(Theme.key_listSelector), true)
    }

    private companion object {
        const val INSET_V_DP = 2f
        const val RADIUS_DP = 14f
    }
}
