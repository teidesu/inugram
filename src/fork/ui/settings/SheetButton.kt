package desu.inugram.ui.settings

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities

fun createSheetButton(
    context: Context,
    text: CharSequence,
    background: Drawable,
    textColor: Int,
    bold: Boolean,
    onClick: () -> Unit,
): TextView = TextView(context).apply {
    this.text = text
    isAllCaps = false
    isSingleLine = true
    ellipsize = TextUtils.TruncateAt.END
    gravity = Gravity.CENTER
    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
    setTextColor(textColor)
    if (bold) typeface = AndroidUtilities.bold()
    this.background = background
    setOnClickListener { onClick() }
}
