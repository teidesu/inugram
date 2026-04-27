package desu.inugram

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DocumentObject
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.messenger.SvgHelper
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.LayoutHelper

class UpdateAppAlertDialog(
    context: Context,
    private val appUpdate: TLRPC.TL_help_appUpdate,
    private val accountNum: Int,
) : BottomSheet(context, false) {

    private val shadowDrawable: Drawable = context.resources.getDrawable(R.drawable.sheet_shadow_round).mutate().apply {
        colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_dialogBackground), PorterDuff.Mode.MULTIPLY)
    }

    private val container: FrameLayout
    private val scrollView: NestedScrollView
    private val linearLayout: LinearLayout
    private val shadow: View
    private val location = IntArray(2)
    private var scrollOffsetY = 0
    private var shadowAnimation: AnimatorSet? = null

    init {
        setCanceledOnTouchOutside(false)
        setApplyTopPadding(false)
        setApplyBottomPadding(false)

        container = object : FrameLayout(context) {
            override fun setTranslationY(translationY: Float) {
                super.setTranslationY(translationY)
                updateLayout()
            }

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                if (ev.action == MotionEvent.ACTION_DOWN && scrollOffsetY != 0 && ev.y < scrollOffsetY) {
                    dismiss()
                    return true
                }
                return super.onInterceptTouchEvent(ev)
            }

            override fun onTouchEvent(e: MotionEvent): Boolean {
                return !isDismissed && super.onTouchEvent(e)
            }

            override fun onDraw(canvas: Canvas) {
                val top = (scrollOffsetY - backgroundPaddingTop - translationY).toInt()
                shadowDrawable.setBounds(0, top, measuredWidth, measuredHeight)
                shadowDrawable.draw(canvas)
            }
        }
        container.setWillNotDraw(false)
        containerView = container

        scrollView = object : NestedScrollView(context) {
            private var ignoreLayout = false

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val height = MeasureSpec.getSize(heightMeasureSpec)
                measureChildWithMargins(linearLayout, widthMeasureSpec, 0, heightMeasureSpec, 0)
                val contentHeight = linearLayout.measuredHeight
                var padding = (height / 5 * 2)
                val visiblePart = height - padding
                if (contentHeight - visiblePart < AndroidUtilities.dp(90f)
                    || contentHeight < height / 2 + AndroidUtilities.dp(90f)
                ) {
                    padding = height - contentHeight
                }
                if (padding < 0) padding = 0
                if (paddingTop != padding) {
                    ignoreLayout = true
                    setPadding(0, padding, 0, 0)
                    ignoreLayout = false
                }
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
            }

            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                super.onLayout(changed, l, t, r, b)
                updateLayout()
            }

            override fun requestLayout() {
                if (ignoreLayout) return
                super.requestLayout()
            }

            override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
                super.onScrollChanged(l, t, oldl, oldt)
                updateLayout()
            }
        }.apply {
            isFillViewport = true
            setWillNotDraw(false)
            clipToPadding = false
            isVerticalScrollBarEnabled = false
        }
        container.addView(
            scrollView,
            LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat(),
                Gravity.LEFT or Gravity.TOP, 0f, 0f, 0f, 130f,
            ),
        )

        linearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(
            linearLayout,
            LayoutHelper.createScroll(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT or Gravity.TOP,
            ),
        )

        appUpdate.sticker?.let { sticker ->
            val imageView = BackupImageView(context)
            val svgThumb: SvgHelper.SvgDrawable? =
                DocumentObject.getSvgThumb(sticker.thumbs, Theme.key_windowBackgroundGray, 1.0f)
            val thumb: TLRPC.PhotoSize? = FileLoader.getClosestPhotoSizeWithSize(sticker.thumbs, 90)
            val imageLocation = ImageLocation.getForDocument(thumb, sticker)
            if (svgThumb != null) {
                imageView.setImage(ImageLocation.getForDocument(sticker), "250_250", svgThumb, 0, "update")
            } else {
                imageView.setImage(ImageLocation.getForDocument(sticker), "250_250", imageLocation, null, 0, "update")
            }
            linearLayout.addView(
                imageView,
                LayoutHelper.createLinear(
                    160, 160, Gravity.CENTER_HORIZONTAL or Gravity.TOP, 17, 8, 17, 0,
                ),
            )
        }

        val titleView = TextView(context).apply {
            typeface = AndroidUtilities.bold()
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            text = LocaleController.getString(R.string.AppUpdate)
        }
        linearLayout.addView(
            titleView,
            LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL, 23, 16, 23, 0,
            ),
        )

        val messageView = TextView(getContext()).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            movementMethod = AndroidUtilities.LinkMovementMethodMy()
            setLinkTextColor(Theme.getColor(Theme.key_dialogTextLink))
            text = LocaleController.formatString(
                R.string.AppUpdateVersionAndSize,
                appUpdate.version,
                AndroidUtilities.formatFileSize(appUpdate.document.size)
            )
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
        }
        linearLayout.addView(
            messageView,
            LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL, 23, 0, 23, 5,
            ),
        )

        val changelogView = TextView(getContext()).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            movementMethod = AndroidUtilities.LinkMovementMethodMy()
            setLinkTextColor(Theme.getColor(Theme.key_dialogTextLink))
            if (TextUtils.isEmpty(appUpdate.text)) {
                text = AndroidUtilities.replaceTags(LocaleController.getString(R.string.AppUpdateChangelogEmpty))
            } else {
                val builder = SpannableStringBuilder(appUpdate.text)
                MessageObject.addEntitiesToText(builder, appUpdate.entities, false, false, false, false)
                text = builder
            }
            gravity = Gravity.LEFT or Gravity.TOP
        }
        linearLayout.addView(
            changelogView,
            LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT or Gravity.TOP, 23, 15, 23, 0,
            ),
        )

        val shadowParams = FrameLayout.LayoutParams(
            LayoutHelper.MATCH_PARENT,
            AndroidUtilities.getShadowHeight(),
            Gravity.BOTTOM or Gravity.LEFT,
        ).apply { bottomMargin = AndroidUtilities.dp(130f) }
        shadow = View(context).apply {
            setBackgroundColor(Theme.getColor(Theme.key_dialogShadowLine))
            alpha = 0f
            tag = 1
        }
        container.addView(shadow, shadowParams)

        val doneButton = BottomSheetCell(context, false).apply {
            setText(
                LocaleController.formatString("AppUpdateDownloadNow", R.string.AppUpdateDownloadNow),
                false,
            )
            backgroundView.setOnClickListener {
                FileLoader.getInstance(accountNum).loadFile(
                    appUpdate.document, "update", FileLoader.PRIORITY_NORMAL, 1,
                )
                dismiss()
            }
        }
        container.addView(
            doneButton,
            LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, 50f,
                Gravity.LEFT or Gravity.BOTTOM, 0f, 0f, 0f, 50f,
            ),
        )

        val scheduleButton = BottomSheetCell(context, true).apply {
            setText(LocaleController.getString(R.string.AppUpdateRemindMeLater), false)
            backgroundView.setOnClickListener { dismiss() }
        }
        container.addView(
            scheduleButton,
            LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, 50f,
                Gravity.LEFT or Gravity.BOTTOM, 0f, 0f, 0f, 0f,
            ),
        )
    }

    private fun runShadowAnimation(show: Boolean) {
        val shouldRun = (show && shadow.tag != null) || (!show && shadow.tag == null)
        if (!shouldRun) return
        shadow.tag = if (show) null else 1
        if (show) shadow.visibility = View.VISIBLE
        shadowAnimation?.cancel()
        val anim = AnimatorSet()
        anim.playTogether(ObjectAnimator.ofFloat(shadow, View.ALPHA, if (show) 1f else 0f))
        anim.duration = 150
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (shadowAnimation === animation) {
                    if (!show) shadow.visibility = View.INVISIBLE
                    shadowAnimation = null
                }
            }

            override fun onAnimationCancel(animation: Animator) {
                if (shadowAnimation === animation) shadowAnimation = null
            }
        })
        shadowAnimation = anim
        anim.start()
    }

    private fun updateLayout() {
        val child = linearLayout.getChildAt(0) ?: return
        child.getLocationInWindow(location)
        val top = location[1] - AndroidUtilities.dp(24f)
        val newOffset = top.coerceAtLeast(0)
        runShadowAnimation(
            location[1] + linearLayout.measuredHeight
                > container.measuredHeight - AndroidUtilities.dp(113f) + containerView.translationY
        )
        if (scrollOffsetY != newOffset) {
            scrollOffsetY = newOffset
            scrollView.invalidate()
        }
    }

    override fun canDismissWithSwipe(): Boolean = false

    private inner class BottomSheetCell(context: Context, withoutBackground: Boolean) : FrameLayout(context) {
        val backgroundView: View = View(context)
        private val textViews = arrayOfNulls<TextView>(2)
        private val hasBackground: Boolean = !withoutBackground
        private var animationInProgress = false

        init {
            setBackground(null)
            if (hasBackground) {
                backgroundView.background =
                    Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 4f)
            }
            addView(
                backgroundView,
                LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat(),
                    0, 16f, if (withoutBackground) 0f else 16f, 16f, 16f,
                ),
            )
            for (a in 0 until 2) {
                val tv = TextView(context).apply {
                    setLines(1)
                    isSingleLine = true
                    gravity = Gravity.CENTER
                    ellipsize = TextUtils.TruncateAt.END
                    if (hasBackground) {
                        setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText))
                        typeface = AndroidUtilities.bold()
                    } else {
                        setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton))
                    }
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                    setPadding(0, 0, 0, if (hasBackground) 0 else AndroidUtilities.dp(13f))
                }
                addView(
                    tv,
                    LayoutHelper.createFrame(
                        LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER,
                    ),
                )
                if (a == 1) tv.alpha = 0f
                textViews[a] = tv
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(
                    AndroidUtilities.dp(if (hasBackground) 80f else 50f),
                    MeasureSpec.EXACTLY,
                ),
            )
        }

        fun setText(text: CharSequence, animated: Boolean) {
            if (!animated) {
                textViews[0]?.text = text
                return
            }
            textViews[1]?.text = text
            animationInProgress = true
            val animator = AnimatorSet().apply {
                duration = 180
                interpolator = CubicBezierInterpolator.EASE_OUT
                playTogether(
                    ObjectAnimator.ofFloat(textViews[0], View.ALPHA, 1f, 0f),
                    ObjectAnimator.ofFloat(textViews[0], View.TRANSLATION_Y, 0f, -AndroidUtilities.dp(10f).toFloat()),
                    ObjectAnimator.ofFloat(textViews[1], View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(textViews[1], View.TRANSLATION_Y, AndroidUtilities.dp(10f).toFloat(), 0f),
                )
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        animationInProgress = false
                        val tmp = textViews[0]
                        textViews[0] = textViews[1]
                        textViews[1] = tmp
                    }
                })
            }
            animator.start()
        }
    }
}
