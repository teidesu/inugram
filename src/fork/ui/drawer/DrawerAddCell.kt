/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package desu.inugram.ui.drawer

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

class DrawerAddCell(context: Context) : FrameLayout(context) {

    private val textView: TextView
    val rowBackground = DrawerAccountRowBackground()

    init {
        background = rowBackground
        textView = TextView(context)
        textView.setTextColor(Theme.getColor(Theme.key_chats_menuItemText))
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        textView.typeface = AndroidUtilities.bold()
        textView.setLines(1)
        textView.maxLines = 1
        textView.isSingleLine = true
        textView.gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
        textView.compoundDrawablePadding = AndroidUtilities.dp(20f)
        addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat(), Gravity.LEFT or Gravity.TOP, 16f, 0f, 24f, 0f))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(48f), MeasureSpec.EXACTLY)
        )
    }

    // the list presses rows without forwarding the touch point, so the ripple would start from the center
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        rowBackground.setHotspot(ev.x, ev.y)
        return super.dispatchTouchEvent(ev)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rowBackground.updateColors(false)
        textView.setTextColor(Theme.getColor(Theme.key_chats_menuItemText))
        textView.text = LocaleController.getString(R.string.AddAccount)
        val drawable = resources.getDrawable(R.drawable.msg_add)
        drawable?.colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_chats_menuItemIcon), PorterDuff.Mode.MULTIPLY)
        textView.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null)
    }
}
