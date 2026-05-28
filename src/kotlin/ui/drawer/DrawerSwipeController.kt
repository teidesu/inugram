package desu.inugram.ui.drawer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.Property
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.annotation.Keep
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.DrawerLayoutContainer
import org.telegram.ui.DialogsActivity

/**
 * Old Layout side drawer mechanics for [DrawerLayoutContainer]: swipe
 * tracking, open/close animation, scrim + edge-shadow rendering. Ported from
 * 11.14.1's stock DrawerLayoutContainer so the stock patch stays a thin set of
 * delegating overrides instead of carrying the whole state machine.
 */
class DrawerSwipeController(private val host: DrawerLayoutContainer) {

    private var drawerLayout: FrameLayout? = null
    private var drawerListView: View? = null
    private var drawerPosition: Float = 0f
    private var drawerOpened: Boolean = false
    private var allowOpenDrawer: Boolean = false
    private var maybeStartTracking: Boolean = false
    private var startedTracking: Boolean = false
    private var startedTrackingX: Int = 0
    private var startedTrackingY: Int = 0
    private var startedTrackingPointerId: Int = 0
    private var velocityTracker: VelocityTracker? = null
    private var beginTrackingSent: Boolean = false
    private var currentAnimation: AnimatorSet? = null
    private var scrimOpacity: Float = 0f
    private val scrimPaint = Paint()
    private var shadowLeft: Drawable? = null

    val isDrawerOpened: Boolean get() = drawerOpened

    fun setDrawerLayout(layout: FrameLayout, listView: View, lp: FrameLayout.LayoutParams) {
        drawerLayout = layout
        drawerListView = listView
        host.addView(drawerLayout, lp)
        drawerLayout!!.visibility = View.INVISIBLE
        if (shadowLeft == null) {
            try {
                shadowLeft = host.resources.getDrawable(R.drawable.header_shadow)
            } catch (_: Exception) {
            }
        }
    }

    fun isDrawerChild(child: View?): Boolean = child != null && child == drawerLayout

    fun setAllowOpenDrawer(value: Boolean, animated: Boolean) {
        allowOpenDrawer = value
        if (!allowOpenDrawer && drawerPosition != 0f) {
            if (!animated) {
                setDrawerPosition(0f); onDrawerAnimationEnd(false)
            } else closeDrawer(true)
        }
    }

    @Keep
    fun setDrawerPosition(value: Float) {
        val layout = drawerLayout ?: return
        drawerPosition = maxOf(0f, minOf(value, layout.measuredWidth.toFloat()))
        layout.translationX = drawerPosition
        if (drawerPosition > 0 && drawerListView != null && drawerListView!!.visibility != View.VISIBLE) {
            drawerListView!!.visibility = View.VISIBLE
        }
        layout.visibility = if (drawerPosition > 0) View.VISIBLE else View.INVISIBLE
        scrimOpacity = drawerPosition / layout.measuredWidth.toFloat()
        host.invalidate()
    }

    fun openDrawer(fast: Boolean) {
        val layout = drawerLayout ?: return
        if (!allowOpenDrawer) return
        cancelCurrentAnimation()
        val animatorSet = AnimatorSet()
        animatorSet.playTogether(ObjectAnimator.ofFloat(this, DRAWER_POSITION, layout.measuredWidth.toFloat()))
        animatorSet.interpolator = DecelerateInterpolator()
        animatorSet.duration = if (fast) maxOf((200f / layout.measuredWidth * (layout.measuredWidth - drawerPosition)).toInt(), 50).toLong() else 250L
        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                onDrawerAnimationEnd(true)
            }
        })
        animatorSet.start()
        currentAnimation = animatorSet
    }

    fun closeDrawer(fast: Boolean) {
        val layout = drawerLayout ?: return
        cancelCurrentAnimation()
        val animatorSet = AnimatorSet()
        animatorSet.playTogether(ObjectAnimator.ofFloat(this, DRAWER_POSITION, 0f))
        animatorSet.interpolator = DecelerateInterpolator()
        animatorSet.duration = if (fast) maxOf((200f / layout.measuredWidth * drawerPosition).toInt(), 50).toLong() else 250L
        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                onDrawerAnimationEnd(false)
            }
        })
        animatorSet.start()
        currentAnimation = animatorSet
    }

    private fun cancelCurrentAnimation() {
        currentAnimation?.cancel()
        currentAnimation = null
    }

    private fun onDrawerAnimationEnd(opened: Boolean) {
        startedTracking = false
        currentAnimation = null
        drawerOpened = opened
        host.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    }

    /**
     * Once the drawer is open (or mid-drag) gestures must keep working to close it.
     * Forum (right-sliding container) owns the left-edge swipe to close itself —
     * yield fully there. Non-first folder tab owns horizontal swipe for tab paging,
     * but we still allow drawer from a thin edge zone — see [tabsOwnHorizontalSwipe].
     */
    private fun canTrackGesture(): Boolean {
        if (drawerOpened || drawerPosition > 0) return true
        if (host.parentActionBarLayout.fragmentStack.size != 1) return false
        val top = host.parentActionBarLayout.lastFragment
        if (top is DialogsActivity) {
            if (top.searchIsShowed) return false
            if (top.rightSlidingDialogContainer?.hasFragment() == true) return false
        }
        return true
    }

    private fun tabsOwnHorizontalSwipe(): Boolean {
        val top = host.parentActionBarLayout.lastFragment as? DialogsActivity ?: return false
        val tabs = top.filterTabsView ?: return false
        return tabs.visibility == View.VISIBLE && !tabs.isFirstTabSelected
    }

    fun onTouchEvent(ev: MotionEvent?): Boolean {
        val layout = drawerLayout
        if (layout == null || host.parentActionBarLayout.checkTransitionAnimation()) {
            // A fragment transition mid-drag aborts the gesture; drop the stale
            // tracking state so the next gesture doesn't resume from it.
            if (startedTracking || maybeStartTracking) {
                startedTracking = false
                maybeStartTracking = false
                velocityTracker?.recycle()
                velocityTracker = null
            }
            return false
        }
        if (ev != null && ev.action == MotionEvent.ACTION_DOWN && !startedTracking && maybeStartTracking) {
            // Fresh gesture, yet maybeStartTracking is still set: the previous
            // gesture's ACTION_UP never reached us — a child (the folder pager)
            // claimed it via requestDisallowInterceptTouchEvent, so our intercept
            // stopped being polled mid-gesture. Drop the stale state, else the
            // DOWN below won't re-init tracking and this swipe-to-open is eaten.
            maybeStartTracking = false
            velocityTracker?.recycle()
            velocityTracker = null
        }
        if (drawerOpened && ev != null && ev.x > drawerPosition && !startedTracking) {
            if (ev.action == MotionEvent.ACTION_UP) closeDrawer(false)
            return true
        }
        if (allowOpenDrawer && canTrackGesture()) {
            if (ev != null && (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE)
                && !startedTracking && !maybeStartTracking
            ) {
                startedTrackingX = ev.x.toInt()
                startedTrackingY = ev.y.toInt()
                startedTrackingPointerId = ev.getPointerId(0)
                maybeStartTracking = true
                cancelCurrentAnimation()
                // Seed velocity tracker from DOWN — otherwise a short fast fling
                // (DOWN → 1 MOVE → UP) computes ~0 velocity and the drawer snaps back.
                if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
                else velocityTracker!!.clear()
                velocityTracker!!.addMovement(ev)
            } else if (ev != null && ev.action == MotionEvent.ACTION_MOVE
                && ev.getPointerId(0) == startedTrackingPointerId
            ) {
                if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
                velocityTracker!!.addMovement(ev)
                val dx = ev.x - startedTrackingX
                val dy = Math.abs(ev.y - startedTrackingY)
                val inEdgeZone = startedTrackingX <= AndroidUtilities.dp(EDGE_SAFE_ZONE_DP.toFloat())
                // Edge-zone swipes skip the angle check entirely (gesture-nav swipes
                // come at any angle); mid-screen keeps stock strict ~18° so vertical
                // scrolls with rightward drift aren't hijacked.
                val openAngleOk = inEdgeZone || dx / 3f > dy
                val openSwipe = dx > 0 && openAngleOk && Math.abs(dx) >= AndroidUtilities.getPixelsInCM(0.2f, true)
                    && (!tabsOwnHorizontalSwipe() || inEdgeZone)
                val closeSwipe = drawerOpened && dx < 0 && Math.abs(dx) >= dy && Math.abs(dx) >= AndroidUtilities.getPixelsInCM(0.4f, true)
                if (maybeStartTracking && !startedTracking && (openSwipe || closeSwipe)) {
                    maybeStartTracking = false
                    startedTracking = true
                    beginTrackingSent = false
                    // Apply accumulated dx in-line — a fast fling that crosses the
                    // threshold in one MOVE otherwise wouldn't move the drawer at
                    // all before UP arrives.
                    setDrawerPosition(drawerPosition + dx)
                    startedTrackingX = ev.x.toInt()
                    host.requestDisallowInterceptTouchEvent(true)
                } else if (startedTracking) {
                    if (!beginTrackingSent) {
                        beginTrackingSent = true
                    }
                    setDrawerPosition(drawerPosition + dx)
                    startedTrackingX = ev.x.toInt()
                }
            } else if (ev == null || ev.getPointerId(0) == startedTrackingPointerId
                && (ev.action == MotionEvent.ACTION_CANCEL
                    || ev.action == MotionEvent.ACTION_UP
                    || ev.action == MotionEvent.ACTION_POINTER_UP)
            ) {
                if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
                if (ev != null) velocityTracker!!.addMovement(ev)
                velocityTracker!!.computeCurrentVelocity(1000)
                if (startedTracking || (drawerPosition != 0f && drawerPosition != layout.measuredWidth.toFloat())) {
                    val velX = velocityTracker!!.xVelocity
                    val velY = velocityTracker!!.yVelocity
                    val back = drawerPosition < layout.measuredWidth / 2f
                        && (velX < 3500 || Math.abs(velX) < Math.abs(velY))
                        || velX < 0 && Math.abs(velX) >= 3500
                    if (!back) openDrawer(!drawerOpened && Math.abs(velX) >= 3500)
                    else closeDrawer(drawerOpened && Math.abs(velX) >= 3500)
                }
                startedTracking = false
                maybeStartTracking = false
                velocityTracker?.recycle()
                velocityTracker = null
            }
        } else {
            if (ev == null || ev.getPointerId(0) == startedTrackingPointerId
                && (ev.action == MotionEvent.ACTION_CANCEL
                    || ev.action == MotionEvent.ACTION_UP
                    || ev.action == MotionEvent.ACTION_POINTER_UP)
            ) {
                startedTracking = false
                maybeStartTracking = false
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return startedTracking
    }

    fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (drawerLayout == null) {
            return host.inu_superDrawChild(canvas, child, drawingTime)
        }
        val height = host.height
        val drawingContent = child != drawerLayout
        var lastVisibleChild = 0
        var clipLeft = 0
        val clipRight = host.width

        val restoreCount = canvas.save()
        if (drawingContent) {
            val childCount = host.childCount
            for (i in 0 until childCount) {
                val v = host.getChildAt(i)
                if (v.visibility == View.VISIBLE && v != drawerLayout) {
                    lastVisibleChild = i
                }
                if (v == child || v.visibility != View.VISIBLE || v != drawerLayout || v.height < height) {
                    continue
                }
                val vright = Math.ceil(v.x.toDouble()).toInt() + v.measuredWidth
                if (vright > clipLeft) clipLeft = vright
            }
            if (clipLeft != 0) {
                canvas.clipRect(clipLeft - AndroidUtilities.dp(1f), 0, clipRight, host.height)
            }
        }
        val result = host.inu_superDrawChild(canvas, child, drawingTime)
        canvas.restoreToCount(restoreCount)

        if (scrimOpacity > 0 && drawingContent) {
            if (host.indexOfChild(child) == lastVisibleChild) {
                scrimPaint.color = (0x99 * scrimOpacity).toInt() shl 24
                canvas.drawRect(clipLeft.toFloat(), 0f, clipRight.toFloat(), host.height.toFloat(), scrimPaint)
            }
        } else if (shadowLeft != null && drawerPosition > 0) {
            val alpha = maxOf(0f, minOf(drawerPosition / AndroidUtilities.dp(20f).toFloat(), 1f))
            if (alpha != 0f) {
                shadowLeft!!.setBounds(drawerPosition.toInt(), child.top, drawerPosition.toInt() + shadowLeft!!.intrinsicWidth, child.bottom)
                shadowLeft!!.alpha = (0xff * alpha).toInt()
                shadowLeft!!.draw(canvas)
            }
        }
        return result
    }

    companion object {
        private const val EDGE_SAFE_ZONE_DP = 25

        // ObjectAnimator with a string property name uses JavaBeans naming; an
        // explicit Property bypasses that.
        @JvmField
        val DRAWER_POSITION: Property<DrawerSwipeController, Float> =
            object : Property<DrawerSwipeController, Float>(Float::class.java, "drawerPosition") {
                override fun get(o: DrawerSwipeController): Float = o.drawerPosition
                override fun set(o: DrawerSwipeController, v: Float) {
                    o.setDrawerPosition(v)
                }
            }
    }
}
