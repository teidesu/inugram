package desu.inugram.helpers

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import androidx.annotation.RequiresApi
import org.telegram.messenger.AndroidUtilities.dpf2
import org.telegram.ui.ActionBar.ActionBarLayout
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ProfileActivity
import org.telegram.ui.ViewPagerActivity
import kotlin.math.abs

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
object Material3PredictiveBack {

    private const val LAZY_START = 0.015f
    private const val MAX_SCALE = 0.9f
    private const val EDGE_MARGIN_DP = 16f
    private const val ENTER_OFFSET_DP = 96f // entering screen starts this far off the left edge; closing slides this far off on commit
    private const val SCRIM_ALPHA_BYTE = 77 // ~0.3 * 255 (AOSP uses 0.2 light / 0.8 dark; fixed 0.3 reads better in-app)
    private const val CLOSING_ALPHA_FADE = 0.2f // leaving screen is fully faded by this much of commit progress (AOSP)
    private const val SCRIM_FADE = 0.5f // scrim lifts by this much of commit progress (AOSP fades it over the full duration, which lingers past the motion)
    private const val COMMIT_DURATION = 450L
    private const val CANCEL_DURATION = 200L

    private val GESTURE_INTERP: Interpolator = PathInterpolator(0.1f, 0.1f, 0f, 1f)
    private val VERTICAL_INTERP: Interpolator = DecelerateInterpolator()
    private val EMPHASIZED_DECELERATE: Interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    private val EMPHASIZED: Interpolator = PathInterpolator(
        Path().apply {
            moveTo(0f, 0f)
            cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
            cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
        }
    )

    private inline fun ViewGroup.eachChild(action: (View) -> Unit) {
        for (i in 0 until childCount) action(getChildAt(i))
    }

    // The entering fragment's own background to fill the M3 gap. ViewPagerActivity (e.g.
    // MainTabsActivity) sets hasOwnBackground but draws nothing itself — the visible color comes
    // from the current tab's inner fragment, so descend into it.
    private fun enteringBackground(fragment: BaseFragment?): Drawable? {
        var f = fragment
        while (f is ViewPagerActivity) f = f.currentVisibleFragment
        // ProfileActivity keeps fragmentView transparent and paints via its children (gray listView),
        // so use the gray window background directly.
        if (f is ProfileActivity) return ColorDrawable(Theme.getColor(Theme.key_windowBackgroundGray))
        val bg = f?.fragmentView?.background
        // A transparent fill can't fill the gap — it would show the black window behind. Reject it so
        // the caller falls back to a solid color.
        if (bg is ColorDrawable && Color.alpha(bg.color) == 0) return null
        return bg
    }

    @JvmStatic
    fun createCallback(
        activity: Activity,
        layout: ActionBarLayout,
        plainBack: Runnable,
    ): OnBackAnimationCallback = Callback(activity, layout, plainBack)

    private class Callback(
        private val activity: Activity,
        private val layout: ActionBarLayout,
        private val plainBack: Runnable,
    ) : OnBackAnimationCallback {

        private var attached = false
        private var invoked = false
        private var finishCancel = false
        private var startTouchY = 0f
        private var swipeEdge = BackEvent.EDGE_LEFT
        private var deviceCornerPx = 0
        private var edgeMarginPx = 0f
        private var enterOffsetPx = 0f
        private var runningAnim: AnimatorSet? = null
        private var savedOutlineProvider: ViewOutlineProvider? = null
        private var savedClipToOutline = false
        private var savedCvbBackground: Drawable? = null
        private var savedCvbForeground: Drawable? = null
        private val scrim = ColorDrawable(Color.BLACK).apply { alpha = SCRIM_ALPHA_BYTE }

        private val outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val sx = view.scaleX.coerceAtLeast(0.01f)
                outline.setRoundRect(0, 0, view.width, view.height, deviceCornerPx / sx)
            }
        }

        override fun onBackStarted(backEvent: BackEvent) {
            // A new gesture can arrive before the previous finish animation (or gesture) has settled.
            // Stock's onBackStarted bails out while predictiveInput is still set, so we must finalize the
            // previous one synchronously here — relying on the animator's end-callback is racy (it may
            // run after this), which would leave cvb un-prepared and the reveal gap unpainted (black).
            runningAnim?.let {
                it.removeAllListeners()
                it.cancel()
                runningAnim = null
                finalizeStock(finishCancel)
            }
            if (attached) {
                finalizeStock(cancel = true)
            } else if (layout.predictiveInput) {
                // Previous gesture set stock prep (we call layout.onBackStarted eagerly, before
                // LAZY_START) but was preempted in the invisible phase — the system never delivered
                // its cancel/invoke, so neither runningAnim nor attached reflects it. Left as-is,
                // stock's onBackStarted below bails on the stale predictiveInput and strands
                // startedTracking → nav locks up. Roll it back as a cancel.
                undoStockPrep()
            }
            invoked = false
            startTouchY = backEvent.touchY
            swipeEdge = backEvent.swipeEdge
            deviceCornerPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                activity.window.decorView.rootWindowInsets
                    ?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
            } else 0
            edgeMarginPx = dpf2(EDGE_MARGIN_DP)
            enterOffsetPx = dpf2(ENTER_OFFSET_DP)
            // Run stock's heavy prep (attach previous fragment, relayout, onResume, HW layer) during the
            // pre-LAZY_START invisible phase so the first visible frame is just a transform.
            layout.onBackStarted(backEvent.touchX, backEvent.touchY)
        }

        override fun onBackProgressed(backEvent: BackEvent) {
            if (invoked || !layout.predictiveInput) return
            val rawP = backEvent.progress
            if (!attached) {
                if (rawP <= LAZY_START) return
                attached = true
                layout.inu_m3PredictiveActive = true
                layout.invalidate()
                attachOverlays()
            }
            val p = GESTURE_INTERP.getInterpolation(
                ((rawP - LAZY_START) / (1f - LAZY_START)).coerceIn(0f, 1f)
            )
            applyFrame(p, backEvent.touchY)
        }

        override fun onBackCancelled() {
            invoked = false
            if (!attached) { undoStockPrep(); cleanupViews(); return }
            runFinishAnim(cancel = true)
        }

        override fun onBackInvoked() {
            invoked = true
            if (!attached) {
                undoStockPrep()
                cleanupViews()
                plainBack.run()
                return
            }
            runFinishAnim(cancel = false)
        }

        // Quick swipe that never crossed LAZY_START: stock's onBackStarted (run during our invisible
        // phase) attached the previous fragment off-screen. Undo that without running stock's swipe
        // commit animator, so plainBack can play the regular cross-fragment transition instead.
        private fun undoStockPrep() {
            if (!layout.predictiveInput) return
            layout.predictiveInput = false
            layout.predictiveBackInProgress = false
            layout.onSlideAnimationEnd(true)
        }

        private fun attachOverlays() {
            val cv = layout.containerView ?: return
            savedOutlineProvider = cv.outlineProvider
            savedClipToOutline = cv.clipToOutline
            cv.outlineProvider = outlineProvider
            cv.clipToOutline = true

            // Translating cvb's children leaves the parent in place; paint it with the entering
            // fragment's own background so the gap matches it (stock only forces white when the
            // fragment has none — settings use gray, chat uses a wallpaper drawable), then overlay
            // a black scrim.
            val cvb = layout.containerViewBack ?: return
            savedCvbBackground = cvb.background
            savedCvbForeground = cvb.foreground
            val enterBg = enteringBackground(layout.backgroundFragment)
            cvb.background = enterBg?.constantState?.newDrawable()
                ?: ColorDrawable(Theme.getColor(Theme.key_windowBackgroundWhite))
            cvb.foreground = scrim
            // Promote entering children to HW layers so per-frame scale/translate is texture-only.
            // (stock already put cv on a HW layer in prepareForMoving.)
            cvb.eachChild { it.setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        }

        private fun applyFrame(p: Float, touchY: Float) {
            val cv = layout.containerView ?: return
            val cvb = layout.containerViewBack ?: return
            val w = cv.width.toFloat()
            val h = cv.height.toFloat()
            if (w <= 0f || h <= 0f) return

            val scale = 1f - (1f - MAX_SCALE) * p
            // AOSP keeps the closing window centered for a right-edge swipe and only pushes it toward
            // the right edge for a left-edge swipe; the off-edge slide happens on commit either way.
            val maxDx = ((w - scale * w) / 2f - edgeMarginPx).coerceAtLeast(0f)
            val tx = if (swipeEdge == BackEvent.EDGE_RIGHT) 0f else maxDx * p

            // Vertical follow tracks touch-Y, capped by the room the shrink frees up (0 at p=0, grows
            // as the window shrinks) so no separate progress factor is needed.
            val deltaY = touchY - startTouchY
            val dyCap = ((h - scale * h) / 2f - edgeMarginPx).coerceAtLeast(0f)
            val ySign = if (deltaY >= 0f) 1f else -1f
            val yNorm = (abs(deltaY) / (h / 2f)).coerceIn(0f, 1f)
            val ty = ySign * dyCap * VERTICAL_INTERP.getInterpolation(yNorm)

            cv.pivotX = w / 2f
            cv.pivotY = h / 2f
            cv.scaleX = scale
            cv.scaleY = scale
            cv.translationX = tx
            cv.translationY = ty
            cv.invalidateOutline()

            // Entering screen shrinks in sync with the closing one (same scale) at a fixed off-edge
            // offset, then grows to full on commit.
            cvb.eachChild {
                it.translationX = -enterOffsetPx
                it.translationY = ty
                it.scaleX = scale
                it.scaleY = scale
            }
        }

        private fun childFloatAnim(
            cvb: ViewGroup, from: Float, to: Float, apply: (View, Float) -> Unit,
        ): ValueAnimator = ValueAnimator.ofFloat(from, to).apply {
            addUpdateListener {
                val v = it.animatedValue as Float
                cvb.eachChild { c -> apply(c, v) }
            }
        }

        private fun runFinishAnim(cancel: Boolean) {
            finishCancel = cancel
            val cv = layout.containerView ?: run { finalizeStock(cancel); return }
            val cvb = layout.containerViewBack ?: run { finalizeStock(cancel); return }

            // Both paths scale back to full size: cancel settles in place, commit grows back to 1
            // while sliding off-edge + fading (M3 — the leaving screen leaves at full size, not shrunk).
            val cvTargetTx = if (cancel) 0f else cv.translationX + enterOffsetPx
            val childTargetTx = if (cancel) -enterOffsetPx else 0f

            val first = cvb.getChildAt(0)
            // Spatial motion rides the emphasized curve.
            val spatial = mutableListOf<Animator>(
                ObjectAnimator.ofFloat(cv, View.SCALE_X, 1f).apply {
                    addUpdateListener { cv.invalidateOutline() }
                },
                ObjectAnimator.ofFloat(cv, View.SCALE_Y, 1f),
                ObjectAnimator.ofFloat(cv, View.TRANSLATION_X, cvTargetTx),
                ObjectAnimator.ofFloat(cv, View.TRANSLATION_Y, 0f),
                childFloatAnim(cvb, first?.translationX ?: 0f, childTargetTx) { c, v -> c.translationX = v },
                childFloatAnim(cvb, first?.translationY ?: 0f, 0f) { c, v -> c.translationY = v },
                childFloatAnim(cvb, first?.scaleX ?: 1f, 1f) { c, v -> c.scaleX = v; c.scaleY = v },
            )
            val animators = spatial.toMutableList()
            if (!cancel) {
                // AOSP fades the leaving screen out over just the first CLOSING_ALPHA_FADE of progress,
                // and the scrim linearly — both on raw progress, not the emphasized spatial curve.
                val startScrim = scrim.alpha
                animators += ValueAnimator.ofFloat(0f, 1f).apply {
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        val f = it.animatedValue as Float
                        cv.alpha = (1f - f / CLOSING_ALPHA_FADE).coerceIn(0f, 1f)
                        scrim.alpha = (startScrim * (1f - f / SCRIM_FADE).coerceAtLeast(0f)).toInt()
                    }
                }
            }

            runningAnim = AnimatorSet().apply {
                playTogether(animators)
                duration = if (cancel) CANCEL_DURATION else COMMIT_DURATION
                if (cancel) interpolator = EMPHASIZED_DECELERATE else spatial.forEach { it.interpolator = EMPHASIZED }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        runningAnim = null
                        finalizeStock(cancel)
                    }
                })
                start()
            }
        }

        private fun finalizeStock(cancel: Boolean) {
            cleanupViews()
            layout.inu_m3PredictiveActive = false
            layout.invalidate()
            if (layout.predictiveInput) {
                layout.predictiveInput = false
                layout.predictiveBackInProgress = false
                layout.onSlideAnimationEnd(cancel)
            } else if (!cancel) {
                layout.onBackPressed()
            }
            attached = false
        }

        private fun cleanupViews() {
            layout.containerView?.let {
                it.scaleX = 1f
                it.scaleY = 1f
                it.translationX = 0f
                it.translationY = 0f
                it.alpha = 1f
                it.clipToOutline = savedClipToOutline
                it.outlineProvider = savedOutlineProvider ?: ViewOutlineProvider.BACKGROUND
            }
            layout.containerViewBack?.let { cvb ->
                cvb.eachChild {
                    it.translationX = 0f
                    it.translationY = 0f
                    it.scaleX = 1f
                    it.scaleY = 1f
                    it.setLayerType(View.LAYER_TYPE_NONE, null)
                }
                cvb.background = savedCvbBackground
                cvb.foreground = savedCvbForeground
            }
            savedCvbBackground = null
            savedCvbForeground = null
            scrim.alpha = SCRIM_ALPHA_BYTE
        }
    }
}
