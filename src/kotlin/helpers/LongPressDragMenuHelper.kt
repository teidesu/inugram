package desu.inugram.helpers

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.ItemOptions
import kotlin.math.abs

// Stock ItemOptions ships press-hold-drag-release (installHoverReleaseListener), so we no longer
// drive the gesture ourselves. We keep two things on top of stock:
//  - early open on upward swipe (stock has no equivalent; dragging up cancels the native long-press)
//  - a flat background highlight on hover instead of stock's ripple (see ItemOptions.updateHover)
object LongPressDragMenuHelper {
    @JvmStatic
    fun attach(source: View, factory: (View) -> ItemOptions?) {
        installEarlySwipe(source)
        source.setOnLongClickListener { v ->
            val o = factory(v) ?: return@setOnLongClickListener false
            // stock nulls scrimView's touch listener on release, dropping our early-swipe listener
            o.setOnDismiss { installEarlySwipe(v) }
            o.show()
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installEarlySwipe(source: View) {
        val grabSlop = ViewConfiguration.get(source.context).scaledTouchSlop
        val fireDistance = AndroidUtilities.dp(24f)
        var downX = 0f
        var downY = 0f
        var fired = false
        source.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    fired = false
                }

                MotionEvent.ACTION_MOVE -> if (!fired) {
                    val up = downY - event.rawY
                    val sideways = abs(event.rawX - downX)
                    // claim the upward drag before a parent container can steal it
                    if (up > sideways && up > grabSlop) {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (up > sideways && up > fireDistance) {
                        fired = true
                        v.performLongClick()
                    }
                }
            }
            false
        }
    }

    // only one menu is open and one item hovered at a time, so a single saved background is enough
    private var hoverItem: View? = null
    private var hoverSaved: Drawable? = null

    @JvmStatic
    fun setHover(item: View, hovered: Boolean) {
        if (!hovered) {
            if (hoverItem === item) {
                item.background = hoverSaved
                hoverSaved = null
                hoverItem = null
            }
            return
        }
        hoverItem = item
        hoverSaved = item.background
        item.background = makeHighlightBg(item)
        item.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun makeHighlightBg(item: View): GradientDrawable {
        val parent = item.parent as? ViewGroup
        val first = parent != null && parent.getChildAt(0) === item
        val last = parent != null && parent.getChildAt(parent.childCount - 1) === item
        val rad = AndroidUtilities.dp(12f).toFloat()
        val tr = if (first) rad else 0f
        val br = if (last) rad else 0f
        return GradientDrawable().apply {
            setColor(Theme.getColor(Theme.key_listSelector))
            cornerRadii = floatArrayOf(tr, tr, tr, tr, br, br, br, br)
        }
    }
}
