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
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import androidx.annotation.RequiresApi
import org.telegram.messenger.AndroidUtilities.dpf2
import org.telegram.ui.ActionBar.ActionBarLayout
import org.telegram.ui.ActionBar.Theme
import kotlin.math.abs

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
object Material3PredictiveBack {

    private const val LAZY_START = 0.015f
    private const val MAX_SCALE = 0.9f
    private const val EDGE_MARGIN_DP = 8f
    private const val MAX_DY_DP = 24f
    private const val ENTER_OFFSET_DP = 56f
    private const val ENTER_GESTURE_PROGRESS = 0.5f // gesture covers this much of the entering slide; rest plays on commit
    private const val ENTER_MIN_SCALE = 0.92f
    private const val SCRIM_ALPHA_BYTE = 77 // ~0.3 * 255
    private const val COMMIT_DURATION = 300L
    private const val CANCEL_DURATION = 200L

    private val GESTURE_INTERP: Interpolator = PathInterpolator(0.1f, 0.1f, 0f, 1f)
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
        private var startTouchY = 0f
        private var deviceCornerPx = 0
        private var edgeMarginPx = 0f
        private var enterOffsetPx = 0f
        private var maxDyPx = 0f
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
            runningAnim?.cancel()
            runningAnim = null
            attached = false
            invoked = false
            startTouchY = backEvent.touchY
            deviceCornerPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                activity.window.decorView.rootWindowInsets
                    ?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
            } else 0
            edgeMarginPx = dpf2(EDGE_MARGIN_DP)
            enterOffsetPx = dpf2(ENTER_OFFSET_DP)
            maxDyPx = dpf2(MAX_DY_DP)
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

            // Translating cvb's children leaves the parent in place; paint it with the theme
            // background so the gap matches the entering fragment, and overlay a black scrim.
            val cvb = layout.containerViewBack ?: return
            savedCvbBackground = cvb.background
            savedCvbForeground = cvb.foreground
            cvb.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
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
            val maxDx = ((w - scale * w) / 2f - edgeMarginPx).coerceAtLeast(0f)
            val tx = maxDx * p

            val deltaY = touchY - startTouchY
            val dyCap = minOf(maxDyPx, ((h - scale * h) / 2f - edgeMarginPx).coerceAtLeast(0f))
            val ySign = if (deltaY >= 0f) 1f else -1f
            val yNorm = (abs(deltaY) / (h / 2f)).coerceIn(0f, 1f)
            val ty = ySign * dyCap * GESTURE_INTERP.getInterpolation(yNorm) * p

            cv.pivotX = w / 2f
            cv.pivotY = h / 2f
            cv.scaleX = scale
            cv.scaleY = scale
            cv.translationX = tx
            cv.translationY = ty
            cv.invalidateOutline()

            val enterTx = -enterOffsetPx * (1f - ENTER_GESTURE_PROGRESS * p)
            val enterScale = ENTER_MIN_SCALE + (1f - ENTER_MIN_SCALE) * ENTER_GESTURE_PROGRESS * p
            cvb.eachChild {
                it.translationX = enterTx
                it.translationY = ty
                it.scaleX = enterScale
                it.scaleY = enterScale
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
            val cv = layout.containerView ?: run { finalizeStock(cancel); return }
            val cvb = layout.containerViewBack ?: run { finalizeStock(cancel); return }

            val cvTargetScale = if (cancel) 1f else MAX_SCALE
            val cvTargetTx = if (cancel) 0f else cv.translationX + enterOffsetPx
            val cvTargetAlpha = if (cancel) 1f else 0f
            val childTargetTx = if (cancel) -enterOffsetPx else 0f
            val childTargetScale = if (cancel) ENTER_MIN_SCALE else 1f

            val first = cvb.getChildAt(0)
            val animators = mutableListOf<Animator>(
                ObjectAnimator.ofFloat(cv, View.SCALE_X, cvTargetScale).apply {
                    addUpdateListener { cv.invalidateOutline() }
                },
                ObjectAnimator.ofFloat(cv, View.SCALE_Y, cvTargetScale),
                ObjectAnimator.ofFloat(cv, View.TRANSLATION_X, cvTargetTx),
                ObjectAnimator.ofFloat(cv, View.TRANSLATION_Y, 0f),
                childFloatAnim(cvb, first?.translationX ?: 0f, childTargetTx) { c, v -> c.translationX = v },
                childFloatAnim(cvb, first?.translationY ?: 0f, 0f) { c, v -> c.translationY = v },
                childFloatAnim(cvb, first?.scaleX ?: 1f, childTargetScale) { c, v -> c.scaleX = v; c.scaleY = v },
            )
            if (!cancel) {
                animators += ObjectAnimator.ofFloat(cv, View.ALPHA, cvTargetAlpha)
                animators += ValueAnimator.ofInt(scrim.alpha, 0).apply {
                    addUpdateListener { scrim.alpha = it.animatedValue as Int }
                }
            }

            runningAnim = AnimatorSet().apply {
                playTogether(animators)
                duration = if (cancel) CANCEL_DURATION else COMMIT_DURATION
                interpolator = if (cancel) EMPHASIZED_DECELERATE else EMPHASIZED
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
