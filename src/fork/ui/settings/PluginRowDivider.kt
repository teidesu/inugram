package desu.inugram.ui.settings

import android.graphics.Canvas
import android.view.View
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.ui.ActionBar.Theme

/** the hairline a plugin list row draws under itself, starting where its text column does */
internal fun View.drawRowDivider(canvas: Canvas, insetDp: Float) {
    val inset = AndroidUtilities.dp(insetDp).toFloat()
    val rtl = LocaleController.isRTL
    val y = (height - 1).toFloat()
    canvas.drawLine(if (rtl) 0f else inset, y, if (rtl) width - inset else width.toFloat(), y, Theme.dividerPaint)
}
