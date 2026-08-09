package desu.inugram.helpers.dialogs

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.view.HapticFeedbackConstants
import android.view.View
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.DialogObject
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.PullForegroundDrawable
import org.telegram.ui.DialogsActivity

object PullActionHelper {
    private fun mode(): Int = InuConfig.PULL_DOWN_ACTION.value

    @JvmStatic
    fun hideArchiveRow(): Boolean = when (mode()) {
        InuConfig.PullDownActionItem.DISABLED,
        InuConfig.PullDownActionItem.SAVED_MESSAGES,
        InuConfig.PullDownActionItem.SEARCH -> true
        else -> false
    }

    @JvmStatic
    fun openArchiveOnPull(): Boolean = mode() == InuConfig.PullDownActionItem.OPEN_ARCHIVE

    @JvmStatic
    fun isStandalonePull(): Boolean = when (mode()) {
        InuConfig.PullDownActionItem.SAVED_MESSAGES,
        InuConfig.PullDownActionItem.SEARCH -> true
        else -> false
    }

    @JvmStatic
    fun filterArchiveRow(dialogs: ArrayList<TLRPC.Dialog>): ArrayList<TLRPC.Dialog> {
        var hasFolder = false
        for (i in dialogs.indices) {
            if (dialogs[i] is TLRPC.TL_dialogFolder) {
                hasFolder = true
                break
            }
        }
        if (!hasFolder) return dialogs
        val filtered = ArrayList<TLRPC.Dialog>(dialogs.size - 1)
        for (dialog in dialogs) {
            if (dialog !is TLRPC.TL_dialogFolder) filtered.add(dialog)
        }
        return filtered
    }

    @JvmStatic
    fun shouldShowArchiveEntry(currentAccount: Int): Boolean =
        hideArchiveRow() &&
            MessagesController.getInstance(currentAccount).dialogs_dict.get(DialogObject.makeFolderDialogId(1)) != null

    @JvmStatic
    fun createDrawable(listView: DialogsActivity.DialogsRecyclerView): PullForegroundDrawable =
        PullActionDrawable(listView, mode())

    @JvmStatic
    fun onScrolled(drawable: PullForegroundDrawable?, measuredDy: Int, usedDy: Int, dy: Int, isDragging: Boolean) {
        (drawable as? PullActionDrawable)?.onScrolled(measuredDy, usedDy, dy, isDragging)
    }

    @JvmStatic
    fun onRelease(activity: DialogsActivity, drawable: PullForegroundDrawable?) {
        (drawable as? PullActionDrawable)?.onRelease(activity)
    }

    private class PullActionDrawable(
        private val list: DialogsActivity.DialogsRecyclerView,
        pullMode: Int,
    ) : PullForegroundDrawable(
        getString(if (pullMode == InuConfig.PullDownActionItem.SEARCH) R.string.InuSwipeForSearch else R.string.InuSwipeForSavedMessages),
        getString(if (pullMode == InuConfig.PullDownActionItem.SEARCH) R.string.InuReleaseForSearch else R.string.InuReleaseForSavedMessages),
    ) {
        private val fakeCell = object : View(list.context) {
            override fun invalidate() {
                list.invalidate()
            }
        }

        private var startPullingTime = 0L
        private var canTrigger = false
        private var resetAnimator: ValueAnimator? = null
        private var resetScheduled = false

        init {
            setCell(fakeCell)
            setListView(list)
            showHidden()
            setWillDraw(true)
        }

        private fun getCellHeight(): Int = dp(if (SharedConfig.useThreeLinesLayout) 76f else 70f)

        // Stock's DialogsItemAnimator.onRemoveStarting force-hides the page drawable on any
        // row removal at the top; that state machine only makes sense for the archive cell.
        override fun doNotShow() = Unit

        override fun getViewOffset(): Float = (list.viewOffset - getCellHeight()).coerceAtLeast(0f)

        fun onScrolled(measuredDy: Int, usedDy: Int, dy: Int, isDragging: Boolean) {
            scrollDy = usedDy
            if (dy < 0 && usedDy == 0 && isDragging && resetAnimator == null && !resetScheduled) {
                val offset = list.viewOffset
                val cellHeight = getCellHeight()
                var add = -dy.toFloat()
                if (offset < cellHeight) {
                    add *= startPullParallax - endPullParallax * (offset / cellHeight)
                } else {
                    val tk = (1f - (offset - cellHeight) / getMaxOverscroll()).coerceAtLeast(0f)
                    add *= startPullOverScroll * tk
                }
                if (offset == 0f && add > 0f) {
                    startPullingTime = System.currentTimeMillis()
                    showHidden()
                }
                list.overScrollMode = View.OVER_SCROLL_NEVER
                list.setViewsOffset((offset + add).coerceAtMost(cellHeight + getMaxOverscroll().toFloat()))
                updatePullState()
            } else if (list.viewOffset > 0f || pullProgress > 0f) {
                updatePullState()
            }
        }

        private fun updatePullState() {
            val progress = (list.viewOffset / getCellHeight()).coerceAtMost(1f)
            setPullProgress(progress)
            val canShow = progress > SNAP_HEIGHT &&
                System.currentTimeMillis() - startPullingTime > minPullingTime + 20
            if (canShow != canTrigger) {
                canTrigger = canShow
                try {
                    list.performHapticFeedback(
                        HapticFeedbackConstants.KEYBOARD_TAP,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                    )
                } catch (_: Exception) {
                }
                colorize(canShow)
            }
            list.invalidate()
        }

        fun onRelease(activity: DialogsActivity) {
            if (list.viewOffset == 0f || resetAnimator != null || resetScheduled) return
            if (canTrigger) {
                canTrigger = false
                val action = when (InuConfig.PULL_DOWN_ACTION.value) {
                    InuConfig.PullDownActionItem.SEARCH -> DialogsFabHelper.Action.OPEN_SEARCH
                    else -> DialogsFabHelper.Action.SAVED_MESSAGES
                }
                DialogsFabHelper.perform(activity, action)
                // Snap back after the presented fragment covers the list, mirroring the
                // open-archive-on-pull behavior.
                resetScheduled = true
                AndroidUtilities.runOnUIThread({
                    resetScheduled = false
                    list.setViewsOffset(0f)
                    resetPullState()
                }, 300)
                return
            }
            val animator = ValueAnimator.ofFloat(list.viewOffset, 0f)
            animator.addUpdateListener { animation ->
                list.setViewsOffset(animation.animatedValue as Float)
                setPullProgress((list.viewOffset / getCellHeight()).coerceAtMost(1f))
                list.invalidate()
            }
            animator.duration = (350f - 120f * (list.viewOffset / (getCellHeight() + getMaxOverscroll()))).toLong().coerceAtLeast(100)
            animator.interpolator = CubicBezierInterpolator.EASE_OUT_QUINT
            list.setScrollEnabled(false)
            animator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    list.setScrollEnabled(true)
                    resetAnimator = null
                    resetPullState()
                }
            })
            resetAnimator = animator
            animator.start()
        }

        private fun resetPullState() {
            startPullingTime = 0L
            canTrigger = false
            colorize(false)
            resetText()
            setPullProgress(0f)
            list.invalidate()
        }

        override fun draw(canvas: Canvas, header: Boolean) {
            if (!header) return
            val offset = list.viewOffset
            if (offset <= 0f) return
            val cellHeight = getCellHeight()
            if (fakeCell.measuredWidth != list.width || fakeCell.measuredHeight != cellHeight) {
                fakeCell.measure(
                    View.MeasureSpec.makeMeasureSpec(list.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(cellHeight, View.MeasureSpec.EXACTLY),
                )
                fakeCell.layout(0, 0, list.width, cellHeight)
            }
            canvas.save()
            canvas.clipRect(0f, 0f, list.width.toFloat(), offset)
            canvas.translate(0f, offset - cellHeight)
            super.draw(canvas, false)
            canvas.restore()
        }
    }
}
