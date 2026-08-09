package desu.inugram

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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import desu.inugram.helpers.update.UpdateHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DocumentObject
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
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
    private val location = IntArray(2)
    private var scrollOffsetY = 0

    companion object {
        private const val BUTTON_HEIGHT = 42f
        private const val BUTTON_ROW_TOP_MARGIN = 16f
        private const val BUTTON_ROW_BOTTOM_MARGIN = 16f
        private const val BUTTON_ROW_HEIGHT = BUTTON_HEIGHT + BUTTON_ROW_TOP_MARGIN + BUTTON_ROW_BOTTOM_MARGIN
    }

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
                Gravity.LEFT or Gravity.TOP, 0f, 0f, 0f, BUTTON_ROW_HEIGHT,
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
            val docLocation = ImageLocation.getForDocument(sticker)
            val svgThumb = DocumentObject.getSvgThumb(sticker.thumbs, Theme.key_windowBackgroundGray, 1.0f)
            if (svgThumb != null) {
                imageView.setImage(docLocation, "250_250", svgThumb, 0, "update")
            } else {
                val thumb = FileLoader.getClosestPhotoSizeWithSize(sticker.thumbs, 90)
                imageView.setImage(docLocation, "250_250", ImageLocation.getForDocument(thumb, sticker), null, 0, "update")
            }
            linearLayout.addView(
                imageView,
                LayoutHelper.createLinear(160, 160, Gravity.CENTER_HORIZONTAL or Gravity.TOP, 17, 8, 17, 0),
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

        val messageView = TextView(context).apply {
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

        val changelogView = TextView(context).apply {
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

        val radius = BUTTON_HEIGHT / 2f
        val scheduleButton = makeButton(
            R.string.AppUpdateRemindMeLater,
            background = Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(radius), 0, Theme.getColor(Theme.key_dialogButtonSelector),
            ),
            textColor = Theme.getColor(Theme.key_dialogTextBlack),
            bold = false,
        ) {
            UpdateHelper.clearPending()
            dismiss()
        }
        val doneButton = makeButton(
            R.string.AppUpdateDownloadNow,
            background = Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, radius),
            textColor = Theme.getColor(Theme.key_featuredStickers_buttonText),
            bold = true,
        ) {
            FileLoader.getInstance(accountNum).loadFile(
                appUpdate.document, "update", FileLoader.PRIORITY_NORMAL, 1,
            )
            NotificationCenter.getGlobalInstance()
                .postNotificationName(NotificationCenter.appUpdateLoading)
            dismiss()
        }
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(scheduleButton, LinearLayout.LayoutParams(0, AndroidUtilities.dp(BUTTON_HEIGHT), 1f).apply {
                marginEnd = AndroidUtilities.dp(8f)
            })
            addView(doneButton, LinearLayout.LayoutParams(0, AndroidUtilities.dp(BUTTON_HEIGHT), 1f))
        }
        container.addView(
            buttonRow,
            LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(),
                Gravity.LEFT or Gravity.BOTTOM,
                16f, BUTTON_ROW_TOP_MARGIN, 16f, BUTTON_ROW_BOTTOM_MARGIN,
            ),
        )
    }

    private fun makeButton(
        textRes: Int,
        background: Drawable,
        textColor: Int,
        bold: Boolean,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = LocaleController.getString(textRes)
        isAllCaps = false
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        setTextColor(textColor)
        if (bold) typeface = AndroidUtilities.bold()
        this.background = background
        setOnClickListener { onClick() }
    }

    private fun updateLayout() {
        val child = linearLayout.getChildAt(0) ?: return
        child.getLocationInWindow(location)
        val top = location[1] - AndroidUtilities.dp(24f)
        val newOffset = top.coerceAtLeast(0)
        if (scrollOffsetY != newOffset) {
            scrollOffsetY = newOffset
            scrollView.invalidate()
        }
    }

    override fun canDismissWithSwipe(): Boolean = false

    override fun onOpenAnimationEnd() {
        super.onOpenAnimationEnd()
        UpdateHelper.revealPendingUpdate()
    }
}
