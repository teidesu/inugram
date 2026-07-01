package desu.inugram.helpers.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.ChatActivityEnterView
import org.telegram.ui.Components.ChatActivityEnterViewAnimatedIconView
import org.telegram.ui.Components.ChatAttachAlert
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import desu.inugram.helpers.theme.M3FabHelper
import desu.inugram.helpers.theme.NonIslandHelper

object VoiceRecorderHelper {
    private const val RECORD_DELAY_MS = 150L
    private const val FAB_SIZE = 48
    private const val FAB_MARGIN = 12

    // read by InstantCameraView.showCamera to pick initial camera
    @JvmField
    var nextCameraFront: Boolean = true
    private var skipIntercept = false

    @JvmStatic
    fun isMovedToAttach(): Boolean = InuConfig.CHAT_VOICE_IN_ATTACH.value

    // when enabled, attachButton/attachLayout are shifted right into the sendButton slot
    // since the frame margin stays at DEFAULT_HEIGHT but the audio/video button is gone
    @JvmStatic
    fun attachTranslationXOffset(): Float =
        if (isMovedToAttach()) AndroidUtilities.dp(48f).toFloat() else 0f

    /**
     * Called from ChatActivityEnterView.recordAudioVideoRunnable.
     * Sets nextCameraFront for options 1/2, shows a picker for option 3.
     * @return true if intercepted (caller should return early)
     */
    @JvmStatic
    fun interceptForCameraChoice(enterView: ChatActivityEnterView): Boolean {
        if (skipIntercept) {
            skipIntercept = false; return false
        }
        nextCameraFront = InuConfig.ROUND_DEFAULT_CAMERA.value != 2
        if (!enterView.isInVideoMode || InuConfig.ROUND_DEFAULT_CAMERA.value != 3) return false
        enterView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        showCameraPicker(ItemOptions.makeOptions(enterView.parentFragment, enterView)) { front ->
            nextCameraFront = front
            skipIntercept = true
            enterView.inu_startRecordingLocked(true)
        }
        return true
    }

    @JvmStatic
    fun addFab(alert: ChatAttachAlert, container: FrameLayout, resourcesProvider: Theme.ResourcesProvider?) {
        if (!isMovedToAttach()) return
        val activity: ChatActivity = alert.baseFragment as? ChatActivity ?: return;
        val enterView = activity.chatActivityEnterView ?: return
        val context = container.context

        val buttonsWrapper = alert.buttonsRecyclerViewWrapper

        val tabBarHeight = if (NonIslandHelper.tabBars()) 48 else 70
        val fabLayoutParams = LayoutHelper.createFrame(
            FAB_SIZE.toFloat(), FAB_SIZE.toFloat(),
            Gravity.BOTTOM or Gravity.RIGHT,
            0f, 0f,
            (if (NonIslandHelper.tabBars()) 0 else FAB_MARGIN).toFloat(),
            (tabBarHeight + FAB_MARGIN).toFloat()
        )

        val fab = object : FrameLayout(context) {
            private var isVideoMode = enterView.isInVideoMode
            private var recordRunnable: Runnable? = null
            private var runnableStarted = false

            private val iconView = ChatActivityEnterViewAnimatedIconView(context, 24).apply {
                inu_legacy = false // fab keeps the stock filled icon regardless of non-island mode
                colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                scaleType = ImageView.ScaleType.CENTER
            }

            init {
                val initialState = if (isVideoMode) ChatActivityEnterViewAnimatedIconView.State.VIDEO
                else ChatActivityEnterViewAnimatedIconView.State.VOICE
                iconView.setState(initialState, false)
                addView(
                    iconView,
                    LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat())
                )

                val accentColor = Theme.getColor(Theme.key_chat_messagePanelSend, resourcesProvider)
                background = M3FabHelper.makeSelectorBackground(
                    FAB_SIZE,
                    accentColor,
                    Theme.blendOver(accentColor, 0x28FFFFFF),
                )
                outlineProvider = M3FabHelper.outlineProvider()
                elevation = AndroidUtilities.dp(4f).toFloat()
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        runnableStarted = true
                        recordRunnable = Runnable {
                            runnableStarted = false

                            if (enterView.audioVideoButtonContainerForbidden) {
                                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                val hint =
                                    activity.inu_makeMediaBannedHint(isVideoMode, container, container.childCount)
                                hint ?: return@Runnable
                                hint.setBottomOffset(fabLayoutParams.bottomMargin + 8)
                                hint.showForView(this, true)
                                return@Runnable
                            }

                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            if (isVideoMode && InuConfig.ROUND_DEFAULT_CAMERA.value == 3) {
                                showCameraPicker(
                                    ItemOptions.makeOptions(container, resourcesProvider, this).setDrawScrim(false)
                                ) { front ->
                                    nextCameraFront = front
                                    skipIntercept = true
                                    dismissAndRecord(alert, enterView, true)
                                }
                            } else {
                                nextCameraFront = InuConfig.ROUND_DEFAULT_CAMERA.value != 2
                                dismissAndRecord(alert, enterView, isVideoMode)
                            }
                        }
                        AndroidUtilities.runOnUIThread(recordRunnable, RECORD_DELAY_MS)
                        parent?.requestDisallowInterceptTouchEvent(true)
                        isPressed = true
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isPressed = false
                        if (runnableStarted) {
                            AndroidUtilities.cancelRunOnUIThread(recordRunnable)
                            runnableStarted = false
                            recordRunnable = null
                            isVideoMode = !isVideoMode
                            enterView.inu_toggleVideoMode()
                            val newState = if (isVideoMode) ChatActivityEnterViewAnimatedIconView.State.VIDEO
                            else ChatActivityEnterViewAnimatedIconView.State.VOICE
                            iconView.setState(newState, true)
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        return true
                    }
                }
                return super.onTouchEvent(event)
            }
        }


        container.addView(fab, fabLayoutParams)

        val sync = Runnable {
            val wrapperVisible = buttonsWrapper.isVisible && buttonsWrapper.alpha > 0.01f
            val target = if (wrapperVisible) View.VISIBLE else View.GONE
            if (fab.visibility != target) fab.visibility = target
            fab.alpha = buttonsWrapper.alpha
            fab.translationY = buttonsWrapper.translationY
        }
        val preDraw = ViewTreeObserver.OnPreDrawListener { sync.run(); true }
        // VTO is window-scoped; rebind on each attach so reused alert instances stay synced
        buttonsWrapper.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.viewTreeObserver.addOnPreDrawListener(preDraw)
                sync.run()
            }

            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnPreDrawListener(preDraw)
            }
        })
        if (buttonsWrapper.isAttachedToWindow) {
            buttonsWrapper.viewTreeObserver.addOnPreDrawListener(preDraw)
        }
        sync.run()
    }

    private fun dismissAndRecord(alert: ChatAttachAlert, enterView: ChatActivityEnterView, video: Boolean) {
        alert.dismiss()
        AndroidUtilities.runOnUIThread({ enterView.inu_startRecordingLocked(video) }, 200)
    }

    private fun scaleDrawable(context: Context, resId: Int, sizeDp: Int): Drawable {
        val px = AndroidUtilities.dp(sizeDp.toFloat())
        val src = context.resources.getDrawable(resId, null)
        val bmp = createBitmap(px, px)
        val canvas = Canvas(bmp)
        src.setBounds(0, 0, px, px)
        src.draw(canvas)
        return bmp.toDrawable(context.resources)
    }

    private fun showCameraPicker(options: ItemOptions, onChoice: (front: Boolean) -> Unit) {
        options
            .forceTop(true)
            .setMinWidth(160)
            .add(
                scaleDrawable(options.context, R.drawable.menu_camera_retake, 24),
                LocaleController.getString(R.string.InuRoundCameraFront)
            ) { onChoice(true) }
            .add(
                scaleDrawable(options.context, R.drawable.msg_camera, 24),
                LocaleController.getString(R.string.InuRoundCameraRear)
            ) { onChoice(false) }
            .show()
    }
}
