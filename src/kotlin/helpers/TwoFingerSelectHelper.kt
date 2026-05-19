package desu.inugram.helpers

import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessageObject
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.ChatActivity

object TwoFingerSelectHelper {
    @JvmStatic
    fun newState(activity: ChatActivity, listView: RecyclerView): State = State(activity, listView)

    class State(
        private val activity: ChatActivity,
        private val listView: RecyclerView,
    ) {
        private var active = false
        private var unselect = false
        private val toggled = HashSet<Int>()
        private val pointerX = FloatArray(2)
        private val pointerY = FloatArray(2)
        private val lastPos = IntArray(2) { -1 }
        private var anchorMin = 0
        private var anchorMax = 0
        private var scrollDirection = 0
        private val scrollRunnable = Runnable { runAutoScroll() }

        fun dispatch(ev: MotionEvent): Boolean {
            if (!active) {
                if (!InuConfig.CHAT_TWO_FINGER_SELECT.value) return false
                if (ev.actionMasked != MotionEvent.ACTION_POINTER_DOWN || ev.pointerCount != 2) return false
                if (!tryStart(ev)) return false
                val cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
                listView.onTouchEvent(cancel)
                cancel.recycle()
                snapshot(ev)
                applyRange()
                updateAutoScroll()
                return true
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_MOVE -> if (ev.pointerCount >= 2) {
                    snapshot(ev)
                    applyRange()
                    updateAutoScroll()
                }
                MotionEvent.ACTION_POINTER_UP -> if (ev.pointerCount <= 2) {
                    listView.removeCallbacks(scrollRunnable)
                    scrollDirection = 0
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> end()
            }
            return true
        }

        private fun tryStart(ev: MotionEvent): Boolean {
            anchorMin = Int.MAX_VALUE
            anchorMax = Int.MIN_VALUE
            var firstMsgId = -1
            for (i in 0 until 2) {
                val cell = cellUnder(ev.getX(i), ev.getY(i)) ?: return false
                val t = cell.messageObject?.type ?: return false
                if (t == MessageObject.TYPE_PHOTO || t == MessageObject.TYPE_VIDEO || t == MessageObject.TYPE_GIF) return false
                val pos = listView.getChildAdapterPosition(cell)
                if (pos < 0) return false
                if (pos < anchorMin) anchorMin = pos
                if (pos > anchorMax) anchorMax = pos
                lastPos[i] = pos
                if (i == 0) firstMsgId = cell.messageObject.id
            }
            val bar = activity.actionBar
            val justEntered = !bar.isActionModeShowed
            if (justEntered) {
                activity.createActionMode()
                bar.showActionMode(true, null, null, null, null, null, 0)
            }
            unselect = !justEntered && isSelected(firstMsgId)
            active = true
            toggled.clear()
            return true
        }

        private fun end() {
            listView.removeCallbacks(scrollRunnable)
            scrollDirection = 0
            active = false
            toggled.clear()
            lastPos[0] = -1
            lastPos[1] = -1
        }

        private fun snapshot(ev: MotionEvent) {
            for (i in 0 until 2) {
                pointerX[i] = ev.getX(i)
                pointerY[i] = ev.getY(i)
            }
        }

        private fun applyRange() {
            var minPos = anchorMin
            var maxPos = anchorMax
            for (i in 0 until 2) {
                val cell = cellUnder(pointerX[i], pointerY[i])
                if (cell != null) {
                    val freshPos = listView.getChildAdapterPosition(cell)
                    if (freshPos >= 0) lastPos[i] = freshPos
                }
                val pos = lastPos[i]
                if (pos < 0) continue
                if (pos < minPos) minPos = pos
                if (pos > maxPos) maxPos = pos
            }

            for (i in 0 until listView.childCount) {
                val cell = listView.getChildAt(i) as? ChatMessageCell ?: continue
                val id = cell.messageObject?.id ?: continue
                val pos = listView.getChildAdapterPosition(cell)
                if (pos < 0) continue
                val inRange = pos in minPos..maxPos
                val isToggled = id in toggled
                if (inRange && !isToggled && isSelected(id) == unselect) {
                    activity.processRowSelect(cell, false, 0f, 0f)
                    toggled.add(id)
                } else if (!inRange && isToggled) {
                    activity.processRowSelect(cell, false, 0f, 0f)
                    toggled.remove(id)
                }
            }
        }

        private fun updateAutoScroll() {
            val threshold = AndroidUtilities.dp(EDGE_DP.toFloat())
            var dir = 0
            for (i in 0 until 2) {
                val y = pointerY[i]
                if (y < threshold) dir = -1
                else if (y > listView.height - threshold) dir = 1
            }
            if (dir == scrollDirection) return
            listView.removeCallbacks(scrollRunnable)
            scrollDirection = dir
            if (dir != 0) listView.postOnAnimation(scrollRunnable)
        }

        private fun runAutoScroll() {
            if (scrollDirection == 0 || !active) return
            listView.scrollBy(0, AndroidUtilities.dp(SCROLL_SPEED_DP.toFloat()) * scrollDirection)
            applyRange()
            listView.postOnAnimation(scrollRunnable)
        }

        private fun cellUnder(x: Float, y: Float): ChatMessageCell? =
            listView.findChildViewUnder(x, y) as? ChatMessageCell

        private fun isSelected(id: Int): Boolean {
            val sel = activity.selectedMessagesIds ?: return false
            return sel[0].indexOfKey(id) >= 0 || sel[1].indexOfKey(id) >= 0
        }
    }

    private const val EDGE_DP = 72
    private const val SCROLL_SPEED_DP = 8
}
