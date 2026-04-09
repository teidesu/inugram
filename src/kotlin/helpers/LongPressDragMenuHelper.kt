package desu.inugram.helpers

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.ActionBarPopupWindow
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.ItemOptions

object LongPressDragMenuHelper {
    @SuppressLint("ClickableViewAccessibility")
    @JvmStatic
    fun attach(source: View, factory: (View) -> ItemOptions?) {
        val state = State()
        source.setOnTouchListener { v, event -> state.onTouch(v, event) }
        source.setOnLongClickListener { v ->
            if (state.isMenuOpen) return@setOnLongClickListener true
            val options = factory(v) ?: return@setOnLongClickListener false
            options.inu_longPressDragMode = true
            options.show()
            v.parent?.requestDisallowInterceptTouchEvent(true)
            state.menuOpened(options)
            true
        }
    }

    private class State {
        private var options: ItemOptions? = null
        private var highlighted: View? = null
        private var savedBackground: Drawable? = null
        private var hadSelection = false
        private var downTime = 0L
        private var downY = 0f

        val isMenuOpen: Boolean get() = options != null

        fun menuOpened(o: ItemOptions) {
            options = o
            highlighted = null
            hadSelection = false
        }

        fun onTouch(source: View, event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                downTime = event.eventTime
                downY = event.rawY
            }
            if (options == null && event.actionMasked == MotionEvent.ACTION_MOVE) {
                val elapsed = event.eventTime - downTime
                val movedUp = downY - event.rawY
                if (elapsed >= ViewConfiguration.getLongPressTimeout() / 2 &&
                    movedUp > AndroidUtilities.dp(24f)
                ) {
                    source.performLongClick()
                }
            }
            val o = options ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val item = hitTest(o, event.rawX, event.rawY)
                    if (item != null) hadSelection = true
                    setHighlight(item)
                }

                MotionEvent.ACTION_UP -> {
                    val item = highlighted
                    setHighlight(null)
                    options = null
                    source.parent?.requestDisallowInterceptTouchEvent(false)
                    if (item != null) {
                        item.performClick()
                    } else if (hadSelection) {
                        o.dismiss()
                    } else {
                        o.actionBarPopupWindow?.let {
                            it.isFocusable = true
                            it.isTouchable = true
                            it.update()
                        }
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    setHighlight(null)
                    options = null
                    source.parent?.requestDisallowInterceptTouchEvent(false)
                    o.dismiss()
                }
            }
            return true
        }

        private fun hitTest(o: ItemOptions, rawX: Float, rawY: Float): View? {
            val loc = IntArray(2)
            for (i in 0 until o.itemsCount) {
                val child = o.getItemAt(i) ?: continue
                if (child is ActionBarPopupWindow.GapView) continue
                if (!child.isEnabled || child.visibility != View.VISIBLE) continue
                child.getLocationOnScreen(loc)
                if (rawX >= loc[0] && rawX <= loc[0] + child.width &&
                    rawY >= loc[1] && rawY <= loc[1] + child.height
                ) return child
            }
            return null
        }

        private fun setHighlight(item: View?) {
            if (highlighted === item) return
            highlighted?.let { it.background = savedBackground }
            savedBackground = null
            highlighted = item
            if (item != null) {
                savedBackground = item.background
                item.background = makeHighlightBg(options!!, item)
                item.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }

        private fun makeHighlightBg(o: ItemOptions, item: View): GradientDrawable {
            val n = o.itemsCount
            var index = -1
            for (i in 0 until n) {
                if (o.getItemAt(i) === item) {
                    index = i
                    break
                }
            }
            val rad = AndroidUtilities.dp(12f).toFloat()
            val tr = if (index == 0) rad else 0f
            val br = if (index == n - 1) rad else 0f
            return GradientDrawable().apply {
                setColor(Theme.getColor(Theme.key_listSelector))
                cornerRadii = floatArrayOf(tr, tr, tr, tr, br, br, br, br)
            }
        }
    }
}
