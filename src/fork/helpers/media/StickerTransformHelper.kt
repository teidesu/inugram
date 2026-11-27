package desu.inugram.helpers.media

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.InuConfig
import desu.inugram.core.sticker.StickerFit
import desu.inugram.core.sticker.StickerFitSnapshotBudget
import desu.inugram.core.sticker.StickerFitState
import desu.inugram.core.sticker.StickerRotation
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.SeekBarView
import org.telegram.ui.PhotoViewer
import org.telegram.ui.Stories.DarkThemeResourceProvider
import java.util.concurrent.Executors
import kotlin.math.*

/** All geometry is in stock logical coordinates; viewport only changes the display. */
object StickerTransformHelper {
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "inu-sticker-fit").apply { isDaemon = true } }
    private fun dp(value: Int) = AndroidUtilities.dp(value.toFloat())
    private fun string(id: Int) = LocaleController.getString(id)
    private fun main(viewer: PhotoViewer) = InuConfig.STICKER_TRANSFORM_CONTROLS.value &&
        viewer.isVisibleOrAnimating && viewer.sendPhotoType == PhotoViewer.SELECT_TYPE_STICKER && viewer.currentEditMode == PhotoViewer.EDIT_MODE_NONE
    private fun imageReady(viewer: PhotoViewer) = viewer.centerImage.bitmap?.isRecycled == false &&
        (viewer.centerImage.hasNotThumb() || viewer.centerImage.imageLocation == null && viewer.centerImage.mediaLocation == null)

    @JvmStatic fun rotationLocked(viewer: PhotoViewer) = main(viewer) && InuConfig.STICKER_ROTATION_LOCKED.value
    @JvmStatic fun sizeLocked(viewer: PhotoViewer) = main(viewer) && InuConfig.STICKER_SIZE_LOCKED.value
    @JvmStatic fun preserveLayoutTransform(viewer: PhotoViewer) = main(viewer) && viewer.inu_stickerTransforms?.visible == true

    @JvmStatic fun clear(viewer: PhotoViewer) {
        viewer.inu_stickerTransforms?.dispose()
        viewer.inu_stickerTransforms = null
    }

    private fun session(viewer: PhotoViewer): Session? {
        if (!InuConfig.STICKER_TRANSFORM_CONTROLS.value) {
            clear(viewer)
            return null
        }
        if (main(viewer) && viewer.stickerMakerView != null && viewer.cutOutBtn != null && viewer.inu_stickerTransforms == null) {
            viewer.inu_stickerTransforms = Session(viewer)
        }
        return viewer.inu_stickerTransforms
    }

    @JvmStatic fun onLayout(viewer: PhotoViewer) { session(viewer)?.stockLayout() }
    @JvmStatic fun onEditorStateChanged(viewer: PhotoViewer) { session(viewer)?.stateChanged() }
    @JvmStatic fun onContentChanged(viewer: PhotoViewer) { session(viewer)?.stateChanged(contentChanged = true) }
    @JvmStatic fun onTransitionStarted(viewer: PhotoViewer, animation: Animator) { session(viewer)?.watchTransition(animation) }
    @JvmStatic fun onFitClick(viewer: PhotoViewer) { session(viewer)?.requestFit() }

    /** Stock open and close transitions animate a copy of the image outside the preview viewport. */
    @JvmStatic fun mapOpenTarget(viewer: PhotoViewer, values: FloatArray) {
        val session = session(viewer) ?: return
        session.stateChanged(scheduleGeometry = false)
        if (session.visible) mapTransition(viewer, session.viewport, values)
    }

    /**
     * An animated close keeps the preview in place while the container fades and returns the viewport for the
     * closing image; [clear] follows once the viewer has closed. Any other close clears right away.
     */
    @JvmStatic fun beginClose(viewer: PhotoViewer, animated: Boolean): Matrix? {
        val session = viewer.inu_stickerTransforms ?: return null
        // Stock can bail out of a close started mid-transition; the next close reuses the frozen preview.
        if (animated && session.closing) return Matrix(session.viewport)
        if (!animated || session.closing || !main(viewer) || !session.visible) {
            clear(viewer)
            return null
        }
        session.beginClose()
        return Matrix(session.viewport)
    }

    /** [values] holds scaleX, scaleY, x and y of the transition image in window coordinates. */
    @JvmStatic fun mapTransition(viewer: PhotoViewer, viewport: Matrix, values: FloatArray) {
        val ox = viewer.containerView.x
        val oy = viewer.containerView.y
        val point = floatArrayOf(values[2] - ox, values[3] - oy)
        viewport.mapPoints(point)
        val ratio = viewport.mapRadius(1f)
        values[0] *= ratio
        values[1] *= ratio
        values[2] = point[0] + ox
        values[3] = point[1] + oy
    }

    @JvmStatic fun beforeDraw(viewer: PhotoViewer, canvas: Canvas) {
        if (!InuConfig.STICKER_TRANSFORM_CONTROLS.value) { clear(viewer); return }
        viewer.inu_stickerTransforms?.takeIf { it.closing || main(viewer) }?.let { session ->
            session.syncVisuals()
            if (session.visible) canvas.concat(session.viewport)
        }
    }

    @JvmStatic fun drawBackground(viewer: PhotoViewer, canvas: Canvas) {
        viewer.inu_stickerTransforms?.takeIf { (it.closing || main(viewer)) && it.visible }?.let { canvas.concat(it.frameViewport) }
    }

    /** The original event belongs to Android; transform and recycle only our copy. */
    @JvmStatic fun onTouch(viewer: PhotoViewer, event: MotionEvent): Boolean? {
        val session = viewer.inu_stickerTransforms ?: return null
        if (!main(viewer) || !session.visible) return null
        val copy = MotionEvent.obtain(event)
        return try {
            copy.transform(session.inverseViewport)
            val handled = viewer.onTouchEvent(copy)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL || event.actionMasked == MotionEvent.ACTION_POINTER_UP) session.stateChanged()
            handled
        } finally { copy.recycle() }
    }

    class Session(private val viewer: PhotoViewer) {
        var minimum = PhotoViewer.inu_STICKER_MIN_SCALE
            private set
        var maximum = PhotoViewer.inu_STICKER_MAX_SCALE
            private set
        val viewport = Matrix()
        val inverseViewport = Matrix()
        val frameViewport = Matrix()
        var visible = false
            private set
        var closing = false
            private set
        private var frozenPositions: List<Pair<Float, Float>>? = null
        private var disposed = false
        private var enabled = false
        private var preparationAllowed = false
        // The first layouts of a session can still carry the size from before a rotation.
        private var settled = false
        private var controlsShown = false
        private val geometry = StickerFitState()
        private val busy get() = geometry.busy
        private val support get() = geometry.support
        private var geometryDirty = true
        private var preparationScheduled = false
        private var analysisRunning = false
        private var contentRevision = 0
        private var preparedRevision = 0
        private var pendingFit = false
        private val geometryFailed get() = geometry.failed
        private val animations = HashMap<Animator, AnimatorListenerAdapter>()
        private val contentChanged = Runnable { stateChanged(contentChanged = true) }
        private val prepareGeometry = Runnable {
            preparationScheduled = false
            stateChanged(scheduleGeometry = false)
            if (!disposed && visible && enabled && geometryDirty && !analysisRunning && !isDragging()) {
                if (preparedRevision != contentRevision) {
                    schedulePreparation()
                    return@Runnable
                }
                geometryDirty = false
                refreshGeometry()
                publishControls()
            }
        }
        // Choreographer runs this before traversal; the posted callback runs after stock drew the image.
        private val settledFrame = Runnable {
            preparedRevision = contentRevision
            viewer.containerView.post(prepareGeometry)
        }
        private val rowHeight = max(48, ceil(36 * viewer.containerView.resources.configuration.fontScale).toInt())
        // All rows share one label column so the controls line up in every locale.
        private val labelWidth = android.text.TextPaint().let { paint ->
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, viewer.containerView.resources.displayMetrics)
            val text = listOf(R.string.InuStickerRotation, R.string.InuStickerSizeControl, R.string.InuStickerFrame).maxOf { paint.measureText(string(it)) }
            max(64, ceil(text / AndroidUtilities.density).toInt() + 16)
        }
        private val panelHeight = rowHeight * 3
        private var logicalWidth = 0f
        private var logicalHeight = 0f
        private val context = viewer.containerView.context
        private val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = roundedBackground(0xee202020.toInt())
        }
        private val fit: View = viewer.inu_fitBtn
        private val sliderColors = object : Theme.ResourcesProvider {
            override fun getColor(key: Int) = if (key == Theme.key_player_progressBackground) 0x44ffffff else Theme.getColor(key)
            // Theme.getColor consults compact-mode overrides before asking the provider.
            override fun isCompactMode() = false
        }
        private val rotation = TransformRow(true)
        private val size = TransformRow(false)
        private val frame = FrameRow()
        private val repositioned = listOf(viewer.cutOutBtn, viewer.btnLayout, viewer.outlineBtn, viewer.undoBtn)
        private var originalPositions: List<Pair<Float, Float>>? = null

        init {
            panel.addView(rotation.row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, rowHeight))
            panel.addView(size.row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, rowHeight))
            panel.addView(frame.row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, rowHeight))
            viewer.containerView.addView(panel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, panelHeight.toFloat(), Gravity.TOP, 10f, 0f, 10f, 0f))
            panel.visibility = View.GONE
            viewer.stickerMakerView.inu_onContentChanged = contentChanged
            viewer.paintingOverlay?.inu_onContentChanged = contentChanged
        }

        private fun roundedBackground(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(18).toFloat()
        }

        private inner class RotationWheel(private val title: String) : View(context) {
            private val drag = StickerRotation.Drag()
            val isDragging get() = drag.isDragging
            private var drawnAngle = Float.NaN
            private val ticks = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xff9b9b9b.toInt()
                strokeWidth = AndroidUtilities.density * 1.5f
            }
            private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xff51bdf3.toInt()
                strokeWidth = dp(3).toFloat()
                strokeCap = Paint.Cap.ROUND
            }

            init {
                isFocusable = true
                isClickable = true
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            }

            fun sync() {
                drag.sync(viewer.rotate)
                if (viewer.rotate != drawnAngle) {
                    drawnAngle = viewer.rotate
                    contentDescription = "$title ${StickerRotation.normalize(viewer.rotate).roundToInt()}°"
                    invalidate()
                }
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val density = AndroidUtilities.density
                val spacing = 12f * density
                val offset = ((viewer.rotate % 30f + 30f) % 30f) * 1.2f * density
                val centerY = height / 2f
                val save = canvas.save()
                canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
                for (index in -3..ceil(width / spacing).toInt() + 1) {
                    val x = index * spacing + offset
                    val halfHeight = (if (index % 3 == 0) 12f else 7f) * density
                    canvas.drawLine(x, centerY - halfHeight, x, centerY + halfHeight, ticks)
                }
                canvas.drawLine(width / 2f, centerY - dp(14), width / 2f, centerY + dp(14), needle)
                canvas.restoreToCount(save)
            }

            private fun position(event: MotionEvent, index: Int) = event.getX(index).toDouble() / AndroidUtilities.density

            private fun applyAngle(angle: Float) {
                viewer.rotate = angle
                viewer.updateMinMax(viewer.scale)
                viewer.invalidateBlur()
                viewer.containerView.invalidate()
                sync()
            }

            fun finishDrag() {
                drag.sync(viewer.rotate)
                val angle = drag.end() ?: return
                isPressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                applyAngle(angle)
                viewer.containerView.post { stateChanged() }
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (!isEnabled || !enabled || busy) {
                    val handled = isDragging
                    finishDrag()
                    return handled
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        drag.start(event.getPointerId(0), position(event, 0), viewer.rotate)
                        isPressed = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val index = event.findPointerIndex(drag.pointerId)
                        if (index < 0) finishDrag()
                        else drag.move(drag.pointerId, position(event, index))?.let { applyAngle(it) }
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        if (event.getPointerId(event.actionIndex) == drag.pointerId) {
                            val next = if (event.actionIndex == 0) 1 else 0
                            if (next < event.pointerCount) drag.replacePointer(event.getPointerId(next), position(event, next))
                            else finishDrag()
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        drag.move(event.getPointerId(event.actionIndex), position(event, event.actionIndex))?.let { applyAngle(it) }
                        finishDrag()
                        performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> finishDrag()
                }
                return true
            }

            override fun performClick(): Boolean {
                super.performClick()
                return true
            }

            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                finishDrag()
            }

            override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
                if (isEnabled && enabled && !busy) {
                    val delta = when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP -> 1f
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN -> -1f
                        else -> return super.onKeyDown(keyCode, event)
                    }
                    applyAngle(StickerRotation.normalize(viewer.rotate + delta))
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }

            override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(info)
                info.className = "android.widget.SeekBar"
                if (isEnabled && enabled && !busy) {
                    info.isScrollable = true
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, string(R.string.InuStickerRotateClockwise)))
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, string(R.string.InuStickerRotateCounterclockwise)))
                }
            }

            override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
                if (isEnabled && enabled && !busy && (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                    applyAngle(StickerRotation.normalize(viewer.rotate + if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) 1f else -1f))
                    announceForAccessibility(contentDescription)
                    return true
                }
                return super.performAccessibilityAction(action, arguments)
            }
        }

        private fun rowLabel(title: String) = TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.WHITE)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
            isSingleLine = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        private inner class FrameRow {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            private val label = rowLabel(string(R.string.InuStickerFrame))
            private val slider = SeekBarView(context, false, sliderColors).apply {
                setColors(0x44ffffff, 0xff51bdf3.toInt())
                setReportChanges(true)
                setDelegate(object : SeekBarView.SeekBarViewDelegate {
                    override fun onSeekBarDrag(stop: Boolean, progress: Float) {
                        if (!enabled || busy) return
                        InuConfig.STICKER_FRAME_RADIUS.value = (progress * 100).roundToInt()
                        frameChanged()
                    }
                    override fun getContentDescription(): CharSequence = "${string(R.string.InuStickerCornerRadius)} ${StickerFrameHelper.radiusPercent()}%"
                    override fun getStepsCount() = 100
                })
            }
            private val reset = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER
                setImageResource(R.drawable.msg_reset)
                setColorFilter(Color.WHITE)
                background = Theme.createSelectorDrawable(0x22ffffff, Theme.RIPPLE_MASK_CIRCLE_20DP)
                contentDescription = string(R.string.InuStickerResetFrame)
                setOnClickListener {
                    InuConfig.STICKER_FRAME_ASPECT.value = StickerFrameHelper.ASPECTS[0]
                    InuConfig.STICKER_FRAME_RADIUS.value = StickerFrameHelper.STOCK_RADIUS
                    frameChanged()
                }
            }
            private val aspect = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = AndroidUtilities.bold()
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                isSingleLine = true
                background = Theme.createSelectorDrawable(0x22ffffff, Theme.RIPPLE_MASK_CIRCLE_20DP)
                setOnClickListener { showAspects(this) }
            }

            init {
                row.addView(label, LayoutHelper.createLinear(labelWidth, LayoutHelper.MATCH_PARENT))
                row.addView(slider, LayoutHelper.createLinear(0, 48, 1f))
                row.addView(reset, LayoutHelper.createLinear(48, 48))
                row.addView(aspect, LayoutHelper.createLinear(48, 48))
            }

            private fun showAspects(anchor: View) {
                val current = StickerFrameHelper.aspectKey()
                val options = ItemOptions.makeOptions(viewer.containerView, DarkThemeResourceProvider(), anchor)
                for (key in StickerFrameHelper.ASPECTS) {
                    options.addChecked(key == current, key) {
                        InuConfig.STICKER_FRAME_ASPECT.value = key
                        frameChanged()
                    }
                }
                options.show()
            }

            fun sync() {
                if (!slider.isDragging) slider.setProgress(StickerFrameHelper.radiusPercent() / 100f)
                aspect.text = StickerFrameHelper.aspectKey()
                aspect.contentDescription = "${string(R.string.InuStickerAspectRatio)} ${aspect.text}"
                slider.isEnabled = enabled && !busy
                reset.isEnabled = enabled && !busy
                aspect.isEnabled = enabled && !busy
                row.alpha = if (enabled && !busy) 1f else .45f
            }
        }

        private fun frameChanged() {
            viewer.stickerMakerView.requestLayout()
            viewer.stickerMakerBackgroundView.invalidate()
            viewer.containerView.invalidate()
            frame.sync()
        }

        private inner class TransformRow(private val isRotation: Boolean) {
            private val title = string(if (isRotation) R.string.InuStickerRotation else R.string.InuStickerSizeControl)
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            private val label = rowLabel(title)
            private val reset = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER
                setImageResource(R.drawable.msg_reset)
                setColorFilter(Color.WHITE)
                background = Theme.createSelectorDrawable(0x22ffffff, Theme.RIPPLE_MASK_CIRCLE_20DP)
                contentDescription = string(if (isRotation) R.string.InuStickerResetRotation else R.string.InuStickerResetSize)
                setOnClickListener {
                    if (isRotation) viewer.rotate = 0f else viewer.scale = 1f
                    viewer.updateMinMax(viewer.scale)
                    viewer.invalidateBlur()
                    viewer.containerView.invalidate()
                    sync()
                }
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = "android.widget.Button"
                    }
                }
            }
            private val lock = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER
                background = Theme.createSelectorDrawable(0x22ffffff, Theme.RIPPLE_MASK_CIRCLE_20DP)
                setOnClickListener {
                    if (isRotation) InuConfig.STICKER_ROTATION_LOCKED.toggle() else InuConfig.STICKER_SIZE_LOCKED.toggle()
                    sync()
                }
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = "android.widget.ToggleButton"
                        info.isCheckable = true
                        info.isChecked = if (isRotation) InuConfig.STICKER_ROTATION_LOCKED.value else InuConfig.STICKER_SIZE_LOCKED.value
                    }
                }
            }
            private val wheel = if (isRotation) RotationWheel(title) else null
            // SeekBarView and the Material 3 slider both read the inactive track from the theme, not setColors.
            private val slider = if (isRotation) null else SeekBarView(context, false, sliderColors).apply {
                setColors(0x44ffffff, 0xff51bdf3.toInt())
                setReportChanges(true)
                setDelegate(object : SeekBarView.SeekBarViewDelegate {
                    override fun onSeekBarDrag(stop: Boolean, progress: Float) {
                        if (!enabled || busy) return
                        viewer.scale = exp(ln(minimum.toDouble()) + progress * ln(maximum / minimum.toDouble())).toFloat()
                        viewer.updateMinMax(viewer.scale)
                        viewer.invalidateBlur()
                        viewer.containerView.invalidate()
                        sync()
                        if (stop) viewer.containerView.post { stateChanged() }
                    }
                    override fun getContentDescription(): CharSequence = "$title ${(viewer.scale * 100).roundToInt()}%"
                    override fun getStepsCount() = 200
                })
            }
            private val control: View = wheel ?: slider!!
            val isDragging get() = wheel?.isDragging ?: slider!!.isDragging

            init {
                row.addView(label, LayoutHelper.createLinear(labelWidth, LayoutHelper.MATCH_PARENT))
                row.addView(control, LayoutHelper.createLinear(0, 48, 1f))
                row.addView(reset, LayoutHelper.createLinear(48, 48))
                row.addView(lock, LayoutHelper.createLinear(48, 48))
            }

            fun finishDrag() { wheel?.finishDrag() }

            fun sync() {
                wheel?.sync()
                if (slider != null && !slider.isDragging) {
                    val progress = (ln(viewer.scale.coerceAtLeast(minimum) / minimum.toDouble()) / ln(maximum / minimum.toDouble())).toFloat()
                    slider.setProgress(progress.coerceIn(0f, 1f))
                }
                val locked = if (isRotation) InuConfig.STICKER_ROTATION_LOCKED.value else InuConfig.STICKER_SIZE_LOCKED.value
                lock.setImageResource(if (locked) R.drawable.msg_filled_lockedrecord else R.drawable.msg_filled_unlockedrecord)
                lock.setColorFilter(if (locked) 0xff51bdf3.toInt() else Color.WHITE)
                lock.isSelected = locked
                lock.contentDescription = string(if (isRotation) {
                    if (locked) R.string.InuStickerRotationLocked else R.string.InuStickerRotationUnlocked
                } else {
                    if (locked) R.string.InuStickerSizeLocked else R.string.InuStickerSizeUnlocked
                })
                if (!enabled || busy) finishDrag()
                control.isEnabled = enabled && !busy
                reset.isEnabled = enabled && !busy
                lock.isEnabled = enabled && !busy
                row.alpha = if (enabled && !busy) 1f else .45f
            }
        }

        fun stateChanged(contentChanged: Boolean = false, scheduleGeometry: Boolean = true) {
            if (disposed || closing) return
            if (contentChanged) {
                contentRevision++
                geometry.invalidate()
                geometryDirty = true
                pendingFit = false
            }
            observeTransitions()
            val show = main(viewer) && viewer.isVisible && viewer.switchingToMode == -1
            if (!show) {
                if (visible) {
                    rotation.finishDrag()
                    restorePreview()
                    geometry.invalidate()
                    contentRevision++
                    pendingFit = false
                    logicalWidth = 0f
                    logicalHeight = 0f
                }
                visible = false
                enabled = false
                preparationAllowed = false
                geometryDirty = true
                showControls(false, View.GONE)
                return
            }
            visible = true
            preparationAllowed = viewer.animationInProgress == 0 && animations.isEmpty() && viewer.imageMoveAnimation == null && viewer.changeModeAnimation == null && !viewer.doneButtonPressed &&
                !viewer.stickerMakerView.isThanosInProgress && !viewer.cutOutBtn.isLoading && !viewer.cutOutBtn.isCancelState &&
                imageReady(viewer)
            enabled = preparationAllowed && viewer.centerImage.imageWidth > 0 && viewer.centerImage.imageHeight > 0
            if (preparationAllowed) settled = true
            syncVisuals()
            publishControls()
            if (scheduleGeometry && preparationAllowed && geometryDirty && !analysisRunning && !isDragging()) schedulePreparation()
        }

        /** Display synchronization only. Readiness and background work are driven by editor events. */
        fun syncVisuals() {
            if (disposed || closing || !visible) return
            layoutPreview()
            if (!viewer.zooming && !viewer.moving) includeScale(viewer.scale)
            rotation.sync()
            size.sync()
            frame.sync()
        }

        private fun publishControls() {
            fit.isEnabled = enabled && !geometryDirty && geometry.ready
            showControls(fit.isEnabled)
        }

        private fun showControls(show: Boolean, hiddenVisibility: Int = View.INVISIBLE) {
            if (show && controlsShown) return
            controlsShown = show
            for (view in listOf(panel, fit)) {
                view.animate().setListener(null).cancel()
                if (show) {
                    view.visibility = View.VISIBLE
                    view.alpha = 0f
                    val startScale = if (view === fit) .3f else .9f
                    view.scaleX = startScale
                    view.scaleY = startScale
                    view.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(250).setInterpolator(CubicBezierInterpolator.DEFAULT).start()
                } else {
                    view.visibility = hiddenVisibility
                    view.alpha = 1f
                    view.scaleX = 1f
                    view.scaleY = 1f
                }
            }
        }

        private fun isDragging() = viewer.zooming || viewer.moving || rotation.isDragging || size.isDragging

        private fun schedulePreparation() {
            if (preparationScheduled) return
            preparationScheduled = true
            viewer.containerView.invalidate()
            viewer.containerView.postOnAnimation(settledFrame)
        }

        private fun observeTransitions() {
            for (animation in listOfNotNull(viewer.imageMoveAnimation, viewer.changeModeAnimation)) {
                watch(animation)
            }
        }

        private fun watch(animation: Animator) {
            if (animations.containsKey(animation)) return
            val listener = object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animations.remove(animation)?.let(animation::removeListener)
                    stateChanged()
                }
            }
            animations[animation] = listener
            animation.addListener(listener)
        }

        fun watchTransition(animation: Animator) {
            if (disposed || closing) return
            watch(animation)
            stateChanged()
        }

        fun stockLayout() {
            if (closing) {
                // Stock layout puts the action buttons back; the closing preview keeps them where they were.
                frozenPositions?.let { positions -> repositioned.zip(positions).forEach { (view, position) -> view.translationX = position.first; view.translationY = position.second } }
                return
            }
            if (visible) originalPositions = repositioned.mapIndexed { index, view ->
                (originalPositions?.get(index)?.first ?: 0f) to view.translationY
            }
            syncVisuals()
            // Stock action animations and unrelated layout requests do not change Fit geometry.
            if (!enabled || geometry.needsAnalysis(key(contentMatrix()))) {
                geometryDirty = true
                contentRevision++
            }
            stateChanged()
        }

        private fun includeScale(scale: Float) {
            if (scale.isFinite() && scale > 0) {
                minimum = min(minimum, scale)
                maximum = max(maximum, scale)
            }
        }

        private fun layoutPreview() {
            val width = viewer.getContainerViewWidth().toFloat()
            val height = viewer.getContainerViewHeight().toFloat()
            if (width <= dp(20) || height <= 0 || viewer.pickerView.top <= 0) return
            if (settled && logicalWidth > dp(20) && (width != logicalWidth || height != logicalHeight)) {
                val borderRatio = (width - dp(20)) / (logicalWidth - dp(20))
                val oldBasis = imageBasis(logicalWidth, logicalHeight)
                val newBasis = imageBasis(width, height)
                if (oldBasis > 0 && newBasis > 0) {
                    viewer.scale *= borderRatio * oldBasis / newBasis
                    viewer.translationX *= borderRatio
                    viewer.translationY = (viewer.translationY + viewer.currentPanTranslationY) * borderRatio - viewer.currentPanTranslationY
                    includeScale(viewer.scale)
                    viewer.updateMinMax(viewer.scale)
                }
            }
            logicalWidth = width
            logicalHeight = height
            var panelTop = viewer.pickerView.y - dp(panelHeight + 8)
            val hasOutline = viewer.outlineBtn.visibility == View.VISIBLE
            val action = if (viewer.cutOutBtn.visibility == View.VISIBLE) viewer.cutOutBtn else viewer.btnLayout
            // The cutout view fills the screen, but its visible pill wraps the animated text.
            val actionWidth = if (action === viewer.cutOutBtn) {
                viewer.cutOutBtn.text.currentWidth + action.paddingLeft + action.paddingRight
            } else action.width.toFloat()
            val actionGap = dp(12) // Match the stock Erase / Restore spacer.
            val fitWidth = fit.measuredWidth.toFloat()
            val totalWidth = actionWidth + actionGap + fitWidth
            var stackedActions = totalWidth > width - dp(20)
            var actionRows = 1 + (if (hasOutline) 1 else 0) + (if (stackedActions) 1 else 0)
            val top = max(viewer.actionBar.bottom.toFloat(), dp(64).toFloat()) + dp(48)
            var bottom = panelTop - dp(actionRows * 48) - dp(12)
            var previewWidth = width - dp(20)
            var controlLeft = 0f
            var controlWidth = width
            val compact = bottom - top < dp(120) && width > height
            if (compact) {
                controlWidth = max(dp(max(280, labelWidth + 96 + 100)).toFloat(), width * .42f).coerceAtMost(width - dp(80))
                controlLeft = width - controlWidth
                previewWidth = controlLeft - dp(20)
                bottom = viewer.pickerView.y - dp(8)
                panelTop = viewer.actionBar.bottom + dp(12).toFloat()
                stackedActions = totalWidth > controlWidth - dp(20)
                actionRows = 1 + (if (hasOutline) 1 else 0) + (if (stackedActions) 1 else 0)
            }
            val panelWidth = (controlWidth - dp(20)).toInt()
            if (panel.layoutParams.width != panelWidth) panel.layoutParams = panel.layoutParams.apply { this.width = panelWidth }
            panel.translationX = controlLeft + dp(10) - panel.left
            panel.translationY = panelTop - panel.top
            val frameSide = width - dp(20)
            val frameWidth = StickerFrameHelper.frameWidth(frameSide)
            val frameHeight = StickerFrameHelper.frameHeight(frameSide)
            val cx = if (compact) controlLeft / 2 else width / 2
            val cy = (top + bottom) / 2
            val ratio = minOf(previewWidth / frameWidth, max(dp(48).toFloat(), bottom - top) / frameHeight, 1f)
            val halfHeight = frameHeight * ratio / 2
            viewport.reset()
            viewport.setScale(ratio, ratio)
            viewport.postTranslate(cx - width / 2 * ratio, cy - height / 2 * ratio)
            viewport.invert(inverseViewport)
            val maker = viewer.stickerMakerView
            maker.pivotX = 0f; maker.pivotY = 0f
            maker.scaleX = ratio; maker.scaleY = ratio
            maker.translationX = cx - width / 2 * ratio
            // The frame view centers using its own measured height.
            maker.translationY = cy - maker.height / 2 * ratio
            frameViewport.setScale(ratio, ratio)
            frameViewport.postTranslate(maker.translationX, maker.translationY)
            if (originalPositions == null) originalPositions = repositioned.map { it.translationX to it.translationY }
            val actionsTop = if (compact) panelTop + dp(panelHeight + 8) else cy + halfHeight + dp(8)
            val actionLeft = controlLeft + (controlWidth - if (stackedActions) actionWidth else totalWidth) / 2
            action.translationX = actionLeft - (action.width - actionWidth) / 2 - action.left
            action.translationY = actionsTop + (dp(48) - action.height) / 2 - action.top
            fit.translationX = (if (stackedActions) controlLeft + (controlWidth - fitWidth) / 2 else actionLeft + actionWidth + actionGap) - fit.left
            fit.translationY = actionsTop + (if (stackedActions) dp(48) else 0) + (dp(48) - fit.height) / 2 - fit.top
            viewer.outlineBtn.translationY = actionsTop + dp((actionRows - 1) * 48) + (dp(48) - viewer.outlineBtn.height) / 2 - viewer.outlineBtn.top
            viewer.outlineBtn.translationX = controlLeft + (controlWidth - viewer.outlineBtn.width) / 2 - viewer.outlineBtn.left
            viewer.undoBtn.translationY = cy - halfHeight - dp(44) - viewer.undoBtn.top
            viewer.undoBtn.translationX = cx - viewer.undoBtn.width / 2 - viewer.undoBtn.left
        }

        private fun imageBasis(width: Float, height: Float): Float {
            var bw = viewer.centerImage.bitmapWidth.toFloat()
            var bh = viewer.centerImage.bitmapHeight.toFloat()
            if (viewer.editState.cropState != null) {
                if (viewer.cropTransform.orientation == 90 || viewer.cropTransform.orientation == 270) { val tmp = bw; bw = bh; bh = tmp }
                bw *= viewer.cropTransform.cropPw; bh *= viewer.cropTransform.cropPh
            }
            return if (bw > 0 && bh > 0) min(width / bw, height / bh) else 0f
        }

        private fun restorePreview() {
            viewport.reset(); inverseViewport.reset(); frameViewport.reset()
            viewer.stickerMakerView.apply {
                scaleX = 1f; scaleY = 1f; translationX = 0f; translationY = 0f
                pivotX = width / 2f; pivotY = height / 2f
            }
            originalPositions?.let { positions ->
                repositioned.zip(positions).forEach { (view, position) -> view.translationX = position.first; view.translationY = position.second }
            }
            originalPositions = null
        }

        fun beginClose() {
            closing = true
            frozenPositions = repositioned.map { it.translationX to it.translationY }
            pendingFit = false
            rotation.finishDrag()
            viewer.containerView.removeCallbacks(settledFrame)
            viewer.containerView.removeCallbacks(prepareGeometry)
        }

        fun dispose() {
            disposed = true
            showControls(false, View.GONE)
            rotation.finishDrag()
            geometry.invalidate()
            viewer.containerView.removeCallbacks(settledFrame)
            viewer.containerView.removeCallbacks(prepareGeometry)
            animations.forEach { (animation, listener) -> animation.removeListener(listener) }
            animations.clear()
            if (viewer.stickerMakerView.inu_onContentChanged === contentChanged) viewer.stickerMakerView.inu_onContentChanged = null
            if (viewer.paintingOverlay?.inu_onContentChanged === contentChanged) viewer.paintingOverlay.inu_onContentChanged = null
            restorePreview()
            viewer.containerView.removeView(panel)
        }

        fun requestFit() {
            stateChanged()
            if (!enabled) return
            pendingFit = true
            if (geometryFailed) {
                geometry.invalidate()
                geometryDirty = true
                publishControls()
                schedulePreparation()
            } else if (!busy && !geometryDirty) applyFit()
        }

        private fun applyFit() {
            if (!pendingFit || !enabled || !main(viewer)) return
            pendingFit = false
            val side = (viewer.getContainerViewWidth() - dp(20)).toFloat()
            val frameWidth = StickerFrameHelper.frameWidth(side)
            val frameHeight = StickerFrameHelper.frameHeight(side)
            val result = StickerFit.fit(
                support ?: return, viewer.rotate.toDouble(), frameWidth.toDouble(), frameHeight.toDouble(),
                StickerFrameHelper.radius(frameWidth, frameHeight).toDouble(),
            ) ?: return
            viewer.scale = result.scale.toFloat()
            viewer.translationX = result.translationX.toFloat()
            viewer.translationY = result.translationY.toFloat() - viewer.currentPanTranslationY
            includeScale(viewer.scale)
            viewer.updateMinMax(viewer.scale)
            viewer.invalidateBlur()
            viewer.containerView.invalidate()
            rotation.sync(); size.sync()
        }

        private fun contentMatrix(): Matrix {
            val full = Matrix()
            viewer.applyTransformToMatrix(full)
            val outer = Matrix().apply {
                preTranslate(viewer.translationX, viewer.translationY + viewer.currentPanTranslationY)
                preScale(viewer.scale, viewer.scale)
                preRotate(viewer.rotate)
            }
            val inverse = Matrix()
            check(outer.invert(inverse))
            return Matrix().apply { setConcat(inverse, full) }
        }

        private fun key(matrix: Matrix): List<Any?> {
            val bitmap = viewer.centerImage.bitmap
            val paint = viewer.paintingOverlay?.bitmap
            val values = FloatArray(9).also(matrix::getValues)
            return buildList {
                add(viewer.currentIndex); add(bitmap); add(bitmap?.generationId); add(paint); add(paint?.generationId)
                add(viewer.getContainerViewWidth()); add(viewer.getContainerViewHeight())
                add(viewer.centerImage.imageWidth); add(viewer.centerImage.imageHeight)
                add(viewer.stickerMakerView.selectedObject); add(viewer.stickerMakerView.outlineVisible); add(viewer.stickerMakerView.outlineWidth)
                add(viewer.editState.cropState); add(viewer.editState.paintPath)
                values.forEach { add((it * 1000).roundToInt()) }
                viewer.paintingOverlay?.let { overlay ->
                    for (i in 0 until overlay.childCount) {
                        val child = overlay.getChildAt(i)
                        add(child); add(child.width); add(child.height); add(child.x); add(child.y)
                        add(child.rotation); add(child.scaleX); add(child.scaleY)
                        if (child is TextView) add(child.text.toString())
                        val animated = animatedEntity(child)
                        add(animated != null)
                        if (animated != null) {
                            // Fit uses the declared bounds, so playback frames cannot change its geometry.
                            add(animated.document)
                            add(animated.entities?.map { it.document_id })
                        } else if (child is BackupImageView) {
                            val frame = child.imageReceiver.bitmap
                            add(frame); add(frame?.generationId)
                        }
                    }
                }
            }
        }

        private fun animatedEntity(child: View) = viewer.editState.mediaEntities?.firstOrNull { it.view === child }?.takeIf {
            it.document?.mime_type != "image/webp" && it.document != null || !it.entities.isNullOrEmpty()
        }

        private fun refreshGeometry() {
            val matrix = contentMatrix()
            val token = geometry.begin(key(matrix)) ?: return
            try {
                val snapshot = snapshot(matrix)
                analysisRunning = true
                worker.execute {
                    val result = if (!geometry.isCurrent(token)) null else runCatching {
                        StickerFit.support(snapshot.layers, snapshot.rectangles, snapshot.crop, ignoreAlphaSpecks = true)
                    }
                    AndroidUtilities.runOnUIThread {
                        analysisRunning = false
                        if (!disposed && result != null && geometry.complete(token, result)) {
                            result.onFailure { failed(it) }
                            stateChanged()
                            if (pendingFit) applyFit()
                            viewer.containerView.invalidate()
                        } else if (!disposed) stateChanged()
                    }
                }
            } catch (e: Exception) {
                snapshotFailed(token, e)
            } catch (e: OutOfMemoryError) {
                snapshotFailed(token, e)
            }
        }

        private fun snapshotFailed(token: Int, error: Throwable) {
            analysisRunning = false
            if (geometry.complete(token, Result.failure(error))) failed(error)
        }

        private fun failed(error: Throwable) {
            Log.d("inu-sticker-fit", "Could not snapshot sticker composition", error)
            pendingFit = false
            try {
                BulletinFactory.of(viewer.containerView, null).createErrorBulletin(string(R.string.InuStickerFitFailed)).show()
            } catch (e: OutOfMemoryError) {
                // Failure reporting must not crash an editor that is already short of memory.
                Log.d("inu-sticker-fit", "Not enough memory to show Fit failure", e)
            }
        }

        private data class Snapshot(val layers: List<StickerFit.Layer>, val rectangles: List<List<StickerFit.Point>>, val crop: StickerFit.Rect?)

        private fun snapshot(content: Matrix): Snapshot {
            val layers = ArrayList<StickerFit.Layer>()
            val rectangles = ArrayList<List<StickerFit.Point>>()
            val reader = SnapshotReader()
            val image = viewer.centerImage
            val iw = image.imageWidth; val ih = image.imageHeight
            check(iw > 0 && ih > 0)
            // Render the receiver at native resolution to retain EXIF orientation and filtering.
            val bw = image.bitmapWidth.coerceAtLeast(1); val bh = image.bitmapHeight.coerceAtLeast(1)
            val imageMatrix = Matrix(content).apply {
                preTranslate(image.imageX, image.imageY)
                preScale(iw / bw, ih / bh)
            }
            val previewAlpha = image.alpha
            val crossfadeAlpha = image.currentAlpha
            try {
                // Mask mode hides this receiver. Measure pixel alpha, not its last preview opacity.
                image.alpha = 1f
                image.setCurrentAlpha(1f)
                layers.add(reader.render(bw, bh, imageMatrix) { canvas ->
                    canvas.scale(bw / iw, bh / ih)
                    canvas.translate(-image.imageX, -image.imageY)
                    image.draw(canvas)
                })
            } finally { image.alpha = previewAlpha; image.setCurrentAlpha(crossfadeAlpha) }

            val overlay = viewer.paintingOverlay
            if (overlay != null && overlay.width > 0 && overlay.height > 0) {
                val overlayMatrix = Matrix(content).apply {
                    preTranslate(-iw / 2, -ih / 2)
                    preScale(iw / overlay.width, ih / overlay.height)
                }
                overlay.bitmap?.takeUnless { it.isRecycled }?.let { paint ->
                    layers.add(reader.copy(paint, Matrix(overlayMatrix).apply { preScale(overlay.width.toFloat() / paint.width, overlay.height.toFloat() / paint.height) }))
                }
                for (i in 0 until overlay.childCount) {
                    val child = overlay.getChildAt(i)
                    if (child.width <= 0 || child.height <= 0) continue
                    val matrix = Matrix(overlayMatrix).apply { preTranslate(child.left.toFloat(), child.top.toFloat()); preConcat(child.matrix) }
                    if (animatedEntity(child) != null || child is BackupImageView && child.imageReceiver.bitmap == null) {
                        rectangles.add(rectangle(matrix, child.width.toFloat(), child.height.toFloat()))
                    } else {
                        layers.add(reader.render(child.width, child.height, matrix, child::draw))
                    }
                }
            }

            val maker = viewer.stickerMakerView
            if (maker.outlineVisible && maker.outlineWidth > 0) {
                maker.selectedObject?.let { obj ->
                    // Stock drawOutline clips to the current frame. Use its unbounded stroke instead.
                    val paint = Paint(obj.bordersStrokePaint).apply { alpha = 255; strokeWidth = dpFloat(maker.outlineWidth) }
                    val outline = Path()
                    paint.getFillPath(obj.segmentBorderPath, outline)
                    val bounds = RectF()
                    outline.computeBounds(bounds, true)
                    if (!bounds.isEmpty) {
                        bounds.inset(-1f, -1f)
                        layers.add(reader.render(ceil(bounds.width()).toInt(), ceil(bounds.height()).toInt(), Matrix(content).apply { preTranslate(bounds.left, bounds.top) }) { canvas ->
                            canvas.translate(-bounds.left, -bounds.top)
                            canvas.drawPath(outline, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
                        })
                    }
                }
            }
            val crop = if (viewer.editState.cropState == null) null else {
                var w = image.bitmapWidth.toFloat(); var h = image.bitmapHeight.toFloat()
                if (viewer.cropTransform.orientation == 90 || viewer.cropTransform.orientation == 270) { val tmp = w; w = h; h = tmp }
                w *= viewer.cropTransform.cropPw; h *= viewer.cropTransform.cropPh
                val fit = min(viewer.getContainerViewWidth() / w, viewer.getContainerViewHeight() / h)
                StickerFit.Rect(-w * fit / 2.0, -h * fit / 2.0, w * fit / 2.0, h * fit / 2.0)
            }
            return Snapshot(layers, rectangles, crop)
        }

        private fun dpFloat(value: Float) = AndroidUtilities.dp(value).toFloat()

        private class SnapshotReader {
            private val budget = Runtime.getRuntime().let { runtime ->
                val available = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
                // Leave headroom for stock bitmaps, geometry work, and the rest of the editor.
                StickerFitSnapshotBudget(minOf(48L * 1024 * 1024, runtime.maxMemory() / 4, available / 2))
            }
            private val pixels = IntArray(StickerFitSnapshotBudget.COPY_PIXELS)

            fun render(width: Int, height: Int, matrix: Matrix, draw: (Canvas) -> Unit): StickerFit.Layer {
                budget.reserve(width, height, render = true)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                return try { draw(Canvas(bitmap)); read(bitmap, matrix) } finally { bitmap.recycle() }
            }

            fun copy(bitmap: Bitmap, matrix: Matrix): StickerFit.Layer {
                budget.reserve(bitmap.width, bitmap.height, render = false)
                return read(bitmap, matrix)
            }

            private fun read(bitmap: Bitmap, matrix: Matrix): StickerFit.Layer {
                // Copy exact native alpha in bounded chunks; never retain a stock-owned bitmap.
                val alpha = StickerFit.copyAlpha(bitmap.width, bitmap.height, pixels) { x, y, width, height ->
                    bitmap.getPixels(pixels, 0, width, x, y, width, height)
                }
                val m = FloatArray(9).also(matrix::getValues)
                return StickerFit.Layer(alpha, bitmap.width, bitmap.height, StickerFit.Affine(
                    m[0].toDouble(), m[3].toDouble(), m[1].toDouble(), m[4].toDouble(), m[2].toDouble(), m[5].toDouble(),
                ))
            }
        }

        private fun rectangle(matrix: Matrix, width: Float, height: Float): List<StickerFit.Point> {
            val points = floatArrayOf(0f, 0f, width, 0f, width, height, 0f, height)
            matrix.mapPoints(points)
            return points.asList().chunked(2).map { StickerFit.Point(it[0].toDouble(), it[1].toDouble()) }
        }
    }
}
