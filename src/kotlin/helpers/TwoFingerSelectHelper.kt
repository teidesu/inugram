package desu.inugram.helpers

import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessageObject
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.ChatActivity
import org.telegram.ui.PinchToZoomHelper
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

object TwoFingerSelectHelper {
    @JvmStatic
    fun newState(activity: ChatActivity, listView: RecyclerView): State = State(activity, listView)

    class State(
        private val activity: ChatActivity,
        private val listView: RecyclerView,
    ) {
        private var active = false

        // gesture started over a photo: wait to tell a select-swipe (fingers move together) apart
        // from a pinch-to-zoom (fingers spread) before committing to either
        private var pending = false
        private var startDist = 0f
        private var startCx = 0f
        private var startCy = 0f
        private var unselect = false
        private var firstMsgId = -1
        private val toggled = HashSet<Int>()
        private val pointerX = FloatArray(2)
        private val pointerY = FloatArray(2)
        private val lastPos = IntArray(2) { -1 }
        private var anchorMin = 0
        private var anchorMax = 0
        private var scrollSpeed = 0f // signed dp/frame, ramps with edge penetration; 0 = idle
        private val scrollRunnable = Runnable { runAutoScroll() }
        private val touchSlop = ViewConfiguration.get(listView.context).scaledTouchSlop.toFloat()

        fun dispatch(ev: MotionEvent): Boolean {
            if (active) return dispatchActive(ev)
            if (pending) return dispatchPending(ev)
            if (!InuConfig.CHAT_TWO_FINGER_SELECT.value) return false
            if (ev.actionMasked != MotionEvent.ACTION_POINTER_DOWN || ev.pointerCount != 2) return false
            if (!resolveAnchors(ev)) return false
            if (pointerOnImage(ev, 0) || pointerOnImage(ev, 1)) {
                pending = true
                startDist = distance(ev)
                startCx = (ev.getX(0) + ev.getX(1)) / 2f
                startCy = (ev.getY(0) + ev.getY(1)) / 2f
                return false
            }
            startSelection(ev)
            return true
        }

        private fun dispatchActive(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_MOVE -> if (ev.pointerCount >= 2) {
                    snapshot(ev)
                    applyRange()
                    updateAutoScroll()
                }

                MotionEvent.ACTION_POINTER_UP -> if (ev.pointerCount <= 2) {
                    listView.removeCallbacks(scrollRunnable)
                    scrollSpeed = 0f
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> end()
            }
            return true
        }

        private fun dispatchPending(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (ev.pointerCount < 2) return false
                    // zoom already engaged (slow spread crossed stock's tiny threshold) — yield to it
                    if (pinchHelper()?.isInOverlayMode == true) {
                        pending = false
                        return false
                    }
                    val distDelta = abs(distance(ev) - startDist)
                    val cx = (ev.getX(0) + ev.getX(1)) / 2f
                    val cy = (ev.getY(0) + ev.getY(1)) / 2f
                    val move = hypot(cx - startCx, cy - startCy)
                    if (move > touchSlop && move >= distDelta) {
                        pending = false
                        cancelPinch(ev)
                        startSelection(ev)
                        return true
                    }
                    if (distDelta > touchSlop) pending = false // pinch wins, let stock zoom
                }

                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> pending = false
            }
            return false
        }

        private fun startSelection(ev: MotionEvent) {
            val cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            listView.onTouchEvent(cancel)
            cancel.recycle()
            val bar = activity.actionBar
            val justEntered = !bar.isActionModeShowed
            if (justEntered) {
                activity.createActionMode()
                bar.showActionMode(true, null, null, null, null, null, 0)
            }
            unselect = !justEntered && isSelected(firstMsgId)
            active = true
            toggled.clear()
            snapshot(ev)
            applyRange()
            updateAutoScroll()
        }

        // resolve the selection anchors from the two down points; both must be over message cells
        private fun resolveAnchors(ev: MotionEvent): Boolean {
            anchorMin = Int.MAX_VALUE
            anchorMax = Int.MIN_VALUE
            firstMsgId = -1
            for (i in 0 until 2) {
                val cell = cellUnder(ev.getX(i), ev.getY(i)) ?: return false
                val msg = cell.messageObject ?: return false
                val pos = listView.getChildAdapterPosition(cell)
                if (pos < 0) return false
                if (pos < anchorMin) anchorMin = pos
                if (pos > anchorMax) anchorMax = pos
                lastPos[i] = pos
                if (i == 0) firstMsgId = msg.id
            }
            return true
        }

        // reset stock's pinch tracking so the just-taken-over swipe doesn't leave it half-armed
        private fun cancelPinch(ev: MotionEvent) {
            val helper = pinchHelper() ?: return
            for (i in 0 until 2) {
                val cell = cellUnder(ev.getX(i), ev.getY(i)) ?: continue
                val img = cell.photoImage ?: continue
                val cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
                helper.checkPinchToZoom(cancel, cell, img, null, null, cell.messageObject)
                cancel.recycle()
            }
        }

        private fun end() {
            listView.removeCallbacks(scrollRunnable)
            scrollSpeed = 0f
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
            val threshold = AndroidUtilities.dp(EDGE_DP.toFloat()).toFloat()
            var speed = 0f
            for (i in 0 until 2) {
                val y = pointerY[i]
                val penetration: Float
                val dir: Int
                if (y < threshold) {
                    penetration = (threshold - y) / threshold
                    dir = -1
                } else if (y > listView.height - threshold) {
                    penetration = (y - (listView.height - threshold)) / threshold
                    dir = 1
                } else continue
                val p = penetration.coerceIn(0f, 1f)
                val s = dir * (MIN_SPEED_DP + (MAX_SPEED_DP - MIN_SPEED_DP) * p)
                if (abs(s) > abs(speed)) speed = s
            }
            val wasIdle = scrollSpeed == 0f
            scrollSpeed = speed
            if (speed == 0f) listView.removeCallbacks(scrollRunnable)
            else if (wasIdle) listView.postOnAnimation(scrollRunnable)
        }

        private fun runAutoScroll() {
            if (scrollSpeed == 0f || !active) return
            listView.scrollBy(0, (AndroidUtilities.density * scrollSpeed).roundToInt())
            applyRange()
            listView.postOnAnimation(scrollRunnable)
        }

        private fun cellUnder(x: Float, y: Float): ChatMessageCell? =
            listView.findChildViewUnder(x, y) as? ChatMessageCell

        // true only when the finger lands on the actual drawn photo (where pinch-to-zoom lives),
        // not the blank margins/caption around it
        private fun pointerOnImage(ev: MotionEvent, i: Int): Boolean {
            val x = ev.getX(i)
            val y = ev.getY(i)
            val cell = cellUnder(x, y) ?: return false
            val t = cell.messageObject?.type ?: return false
            if (t != MessageObject.TYPE_PHOTO && t != MessageObject.TYPE_VIDEO && t != MessageObject.TYPE_GIF) return false
            val img = cell.photoImage ?: return false
            if (!img.hasNotThumb()) return false
            return img.isInsideImage(x - cell.x, y - cell.y)
        }

        // the single shared instance, reachable through any cell's delegate
        private fun pinchHelper(): PinchToZoomHelper? {
            for (i in 0 until listView.childCount) {
                val cell = listView.getChildAt(i) as? ChatMessageCell ?: continue
                return cell.delegate?.pinchToZoomHelper ?: continue
            }
            return null
        }

        private fun distance(ev: MotionEvent): Float =
            hypot(ev.getX(1) - ev.getX(0), ev.getY(1) - ev.getY(0))

        private fun isSelected(id: Int): Boolean {
            val sel = activity.selectedMessagesIds ?: return false
            return sel[0].indexOfKey(id) >= 0 || sel[1].indexOfKey(id) >= 0
        }
    }

    private const val EDGE_DP = 120
    private const val MIN_SPEED_DP = 4f
    private const val MAX_SPEED_DP = 18f
}
