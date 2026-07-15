package desu.inugram.ui.drawer

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.Switch
import kotlin.math.abs

class DrawerProxyCell(context: Context) : FrameLayout(context) {

    private val imageView: BackupImageView
    private val textView: TextView
    private val checkBox: Switch

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    var onSwitchToggled: ((Boolean) -> Unit)? = null

    init {
        imageView = BackupImageView(context)
        imageView.setColorFilter(PorterDuffColorFilter(Theme.getColor(Theme.key_chats_menuItemIcon), PorterDuff.Mode.SRC_IN))

        textView = TextView(context)
        textView.setTextColor(Theme.getColor(Theme.key_chats_menuItemText))
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
        textView.typeface = AndroidUtilities.bold()
        textView.gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT

        checkBox = Switch(context)
        checkBox.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_chats_menuBackground, Theme.key_chats_menuBackground)
        checkBox.isClickable = false
        checkBox.isFocusable = false

        addView(imageView, LayoutHelper.createFrame(24, 24f, Gravity.LEFT or Gravity.TOP, 19f, 12f, 0f, 0f))
        addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat(), Gravity.LEFT or Gravity.TOP, 72f, 0f, 70f, 0f))
        addView(checkBox, LayoutHelper.createFrame(37, 24f, Gravity.RIGHT or Gravity.CENTER_VERTICAL, 0f, 0f, 22f, 0f))

        // Switch draws a larger thumb/ripple than its measured bounds — allow it to overdraw.
        setClipChildren(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        textView.setTextColor(Theme.getColor(Theme.key_chats_menuItemText))
        imageView.setColorFilter(PorterDuffColorFilter(Theme.getColor(Theme.key_chats_menuItemIcon), PorterDuff.Mode.SRC_IN))
        checkBox.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_chats_menuBackground, Theme.key_chats_menuBackground)
    }

    private var switchDownX = -1f
    private var inSwitchZone = false

    private fun isInSwitchZone(x: Float): Boolean {
        if (checkBox.visibility != VISIBLE) return false
        // hit zone: from the switch left edge (with extra padding) to the right edge of the cell
        return x >= checkBox.left - AndroidUtilities.dp(12f)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            inSwitchZone = isInSwitchZone(ev.x)
            if (inSwitchZone) {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
        }
        return inSwitchZone
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!inSwitchZone) return super.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                switchDownX = event.x
            }
            MotionEvent.ACTION_UP -> {
                if (abs(event.x - switchDownX) < touchSlop) {
                    val newState = !checkBox.isChecked
                    checkBox.setChecked(newState, true)
                    onSwitchToggled?.invoke(newState)
                }
                inSwitchZone = false
            }
            MotionEvent.ACTION_CANCEL -> {
                inSwitchZone = false
            }
            // ACTION_MOVE: consumed silently to prevent the row click from firing mid-drag
        }
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(48f), MeasureSpec.EXACTLY)
        )
    }

    fun bind(text: CharSequence, iconRes: Int) {
        textView.text = text
        imageView.setImageResource(iconRes)
    }

    fun setChecked(checked: Boolean) {
        checkBox.setChecked(checked, isAttachedToWindow)
    }

    fun setSwitchVisible(visible: Boolean) {
        checkBox.visibility = if (visible) VISIBLE else GONE
    }
}
