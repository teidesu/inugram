package desu.inugram.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.text.Spanned
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.appcompat.widget.TooltipCompat
import desu.inugram.InuConfig
import desu.inugram.helpers.FormattingPopupConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.EditTextCaption
import org.telegram.ui.Components.QuoteSpan
import org.telegram.ui.Components.ScaleStateListAnimator
import org.telegram.ui.Components.TextStyleSpan
import org.telegram.ui.Components.URLSpanReplacement
import kotlin.math.max
import kotlin.math.min

class FormattingPopup private constructor(private val edit: EditTextCaption) {

    private val ctx = edit.context
    private val popup: PopupWindow
    private val container: LinearLayout
    private val items = mutableListOf<Item>()
    private val groups = mutableListOf<MutableList<Item>>(mutableListOf())
    private val tmpLoc = IntArray(2)
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (popup.isShowing) sync()
        true
    }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) {
            dismiss()
        }
    }
    private var lastSelStart = -1
    private var lastSelEnd = -1
    private var lastEditX = Int.MIN_VALUE
    private var lastEditY = Int.MIN_VALUE
    private var lastRootW = -1
    private var dismissed = false
    private var revealedSpoiler = false

    init {
        container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val scroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            background = GradientDrawable().apply {
                cornerRadius = dpf(8f)
                setColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground))
            }
            addView(
                container,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        popup = PopupWindow(
            scroll,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false,
        ).apply {
            isOutsideTouchable = false
            isFocusable = false
            elevation = dpf(1f)
            setBackgroundDrawable(null)
        }
        addItems()
    }

    private fun addItems() {
        for (entry in InuConfig.FORMATTING_POPUP_ITEMS.value) {
            if (!entry.enabled) continue
            val item = entry.item
            when (item) {
                FormattingPopupConfig.Item.SELECT_ALL -> addAction(item.iconRes, item.labelRes) { doSelectAll() }
                FormattingPopupConfig.Item.COPY -> addAction(item.iconRes, item.labelRes) { doCopy() }
                FormattingPopupConfig.Item.CUT -> addAction(item.iconRes, item.labelRes) { doCut() }
                FormattingPopupConfig.Item.PASTE -> addAction(item.iconRes, item.labelRes) {
                    edit.onTextContextMenuItem(
                        android.R.id.paste
                    )
                }

                FormattingPopupConfig.Item.DIVIDER -> addDivider()
                FormattingPopupConfig.Item.BOLD -> addStyleItem(
                    item.iconRes,
                    item.labelRes,
                    TextStyleSpan.FLAG_STYLE_BOLD
                )

                FormattingPopupConfig.Item.ITALIC -> addStyleItem(
                    item.iconRes,
                    item.labelRes,
                    TextStyleSpan.FLAG_STYLE_ITALIC
                )

                FormattingPopupConfig.Item.UNDERLINE -> addStyleItem(
                    item.iconRes,
                    item.labelRes,
                    TextStyleSpan.FLAG_STYLE_UNDERLINE
                )

                FormattingPopupConfig.Item.STRIKE -> addStyleItem(
                    item.iconRes,
                    item.labelRes,
                    TextStyleSpan.FLAG_STYLE_STRIKE
                )

                FormattingPopupConfig.Item.MONO -> addStyleItem(
                    item.iconRes,
                    item.labelRes,
                    TextStyleSpan.FLAG_STYLE_MONO
                )

                FormattingPopupConfig.Item.SPOILER -> addItem(item.iconRes, item.labelRes, { hasSpoiler() }) {
                    if (hasSpoiler()) clearStyleFlag(TextStyleSpan.FLAG_STYLE_SPOILER)
                    else edit.makeSelectedSpoiler()
                }

                FormattingPopupConfig.Item.QUOTE -> addItem(item.iconRes, item.labelRes, { hasQuote() }) {
                    if (hasQuote()) clearQuote()
                    else edit.makeSelectedQuote()
                }

                FormattingPopupConfig.Item.LINK -> addItem(item.iconRes, item.labelRes, { hasLink() }) {
                    if (hasLink()) clearLink()
                    else edit.makeSelectedUrl()
                }

                FormattingPopupConfig.Item.CLEAR -> addAction(item.iconRes, item.labelRes) { edit.makeSelectedRegular() }
            }
        }
        applyGroupCorners()
    }

    private fun applyGroupCorners() {
        for (group in groups) {
            if (group.isEmpty()) continue
            for ((idx, item) in group.withIndex()) {
                val isFirst = idx == 0
                val isLast = idx == group.size - 1
                item.setCorners(if (isFirst) dpf(6f) else 0f, if (isLast) dpf(6f) else 0f)
                if (!isLast) {
                    (item.view.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(1)
                }
            }
        }
    }

    private fun addStyleItem(iconRes: Int, tooltipRes: Int, flag: Int) {
        addItem(iconRes, tooltipRes, { hasStyleFlag(flag) }) {
            if (hasStyleFlag(flag)) clearStyleFlag(flag)
            else applyStyleFlag(flag)
        }
    }

    private fun addItem(iconRes: Int, tooltipRes: Int, isActive: () -> Boolean, onClick: () -> Unit) {
        val tint = Theme.getColor(Theme.key_actionBarDefaultSubmenuItem)
        val icon = ImageView(ctx).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
        }
        val view = FrameLayout(ctx).apply {
            isClickable = true
            isFocusable = true
            addView(icon, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            TooltipCompat.setTooltipText(this, LocaleController.getString(tooltipRes))
        }
        val item = Item(view, icon, isActive)
        item.refresh(force = true)
        view.setOnClickListener {
            onClick()
            for (it in items) it.refresh(force = true)
            sync()
        }
        items.add(item)
        groups.last().add(item)
        container.addView(
            view,
            LinearLayout.LayoutParams(dp(38), dp(38)),
        )
    }

    private fun addAction(iconRes: Int, tooltipRes: Int, action: () -> Unit) {
        addItem(iconRes, tooltipRes, { false }) { action() }
    }

    private fun doCopy() {
        val (s, e) = currentRange() ?: return
        val sub = edit.text?.subSequence(s, e) ?: return
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(null, sub))
    }

    private fun doCut() {
        val (s, e) = currentRange() ?: return
        doCopy()
        edit.text?.delete(s, e)
    }

    private fun doSelectAll() {
        val len = edit.text?.length ?: return
        edit.setSelection(0, len)
    }

    private fun addDivider() {
        groups.add(mutableListOf())
        val view = View(ctx).apply {
            setBackgroundColor(Theme.multAlpha(Theme.getColor(Theme.key_actionBarDefaultSubmenuItem), 0.2f))
        }
        container.addView(
            view,
            LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT).also {
                it.marginStart = dp(4)
                it.marginEnd = dp(6)
                it.topMargin = dp(2)
                it.bottomMargin = dp(2)
            },
        )
    }

    private fun show() {
        edit.viewTreeObserver.addOnPreDrawListener(preDraw)
        edit.addOnAttachStateChangeListener(attachListener)
        edit.post {
            if (dismissed) return@post
            val s = edit.selectionStart
            val e = edit.selectionEnd
            if (s < 0 || e < 0 || s == e) {
                dismiss()
                return@post
            }
            val cv = popup.contentView
            cv.alpha = 0f
            cv.scaleX = 0.85f
            cv.scaleY = 0.85f
            try {
                popup.showAtLocation(edit, Gravity.NO_GRAVITY, 0, 0)
            } catch (e: Throwable) {
                return@post
            }
            sync()
            if (dismissed) return@post
            cv.pivotX = cv.measuredWidth / 2f
            cv.pivotY = cv.measuredHeight.toFloat()
            cv.animate()
                .alpha(1f)
                .scaleX(1f).scaleY(1f)
                .setDuration(180)
                .setInterpolator(OvershootInterpolator(1.6f))
                .start()
        }
    }

    private fun dismiss() {
        if (dismissed) return
        dismissed = true
        edit.viewTreeObserver.removeOnPreDrawListener(preDraw)
        edit.removeOnAttachStateChangeListener(attachListener)
        if (revealedSpoiler) {
            try {
                edit.inu_setSpoilersRevealedNoText(false)
            } catch (_: Throwable) {
            }
            revealedSpoiler = false
        }
        active = null
        val cv = popup.contentView
        cv.animate().cancel()
        cv.animate()
            .alpha(0f)
            .scaleX(0.85f).scaleY(0.85f)
            .setDuration(120)
            .withEndAction {
                try {
                    popup.dismiss()
                } catch (_: Throwable) {
                }
            }
            .start()
    }

    private fun sync() {
        if (!edit.isAttachedToWindow) {
            dismiss()
            return
        }
        val s = edit.selectionStart
        val e = edit.selectionEnd
        if (s < 0 || e < 0 || s == e) {
            dismiss()
            return
        }
        val selChanged = s != lastSelStart || e != lastSelEnd
        if (selChanged) {
            lastSelStart = s
            lastSelEnd = e
            for (it in items) it.refresh()
        }
        syncSpoilerReveal()
        edit.getLocationInWindow(tmpLoc)
        val editX = tmpLoc[0]
        val editY = tmpLoc[1]
        val rootW = edit.rootView.width
        if (selChanged || editX != lastEditX || editY != lastEditY || rootW != lastRootW) {
            lastEditX = editX
            lastEditY = editY
            lastRootW = rootW
            reposition(editX, editY, rootW)
        }
    }

    private fun syncSpoilerReveal() {
        val want = overlapsSpoiler()
        if (want == revealedSpoiler) return
        revealedSpoiler = want
        try {
            edit.inu_setSpoilersRevealedNoText(want)
        } catch (_: Throwable) {
        }
    }

    private fun overlapsSpoiler(): Boolean {
        val (s, e) = currentRange() ?: return false
        val text = edit.text ?: return false
        val spans = text.getSpans(s, e, TextStyleSpan::class.java)
        for (span in spans) {
            if ((span.styleFlags and TextStyleSpan.FLAG_STYLE_SPOILER) != 0) return true
        }
        return false
    }

    private fun reposition(editX: Int, editY: Int, rootW: Int) {
        val cv = popup.contentView
        val margin = dp(8)
        val maxW = rootW - margin * 2
        cv.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val popupW = min(cv.measuredWidth, maxW)
        val popupH = cv.measuredHeight
        var x = editX + edit.width / 2 - popupW / 2
        x = max(margin, min(x, rootW - popupW - margin))
        val y = editY - popupH - dp(8)
        try {
            popup.update(x, y, popupW, -1)
        } catch (_: Throwable) {
        }
    }

    private fun applyStyleFlag(flag: Int) {
        when (flag) {
            TextStyleSpan.FLAG_STYLE_BOLD -> edit.makeSelectedBold()
            TextStyleSpan.FLAG_STYLE_ITALIC -> edit.makeSelectedItalic()
            TextStyleSpan.FLAG_STYLE_UNDERLINE -> edit.makeSelectedUnderline()
            TextStyleSpan.FLAG_STYLE_STRIKE -> edit.makeSelectedStrike()
            TextStyleSpan.FLAG_STYLE_MONO -> edit.makeSelectedMono()
        }
    }

    private fun clearStyleFlag(flag: Int) {
        val (s, e) = currentRange() ?: return
        val text = edit.text ?: return
        val clearMask = if (flag == TextStyleSpan.FLAG_STYLE_SPOILER) {
            flag or TextStyleSpan.FLAG_STYLE_SPOILER_REVEALED
        } else flag
        val spans = text.getSpans(s, e, TextStyleSpan::class.java)
        for (span in spans) {
            val ss = text.getSpanStart(span)
            val se = text.getSpanEnd(span)
            val flags = span.styleFlags
            if ((flags and flag) == 0) continue
            val oStart = max(s, ss)
            val oEnd = min(e, se)
            text.removeSpan(span)
            if (ss < oStart) {
                text.setSpan(
                    TextStyleSpan(TextStyleSpan.TextStyleRun().also { it.flags = flags }),
                    ss, oStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            val newFlags = flags and clearMask.inv()
            if (newFlags != 0) {
                text.setSpan(
                    TextStyleSpan(TextStyleSpan.TextStyleRun().also { it.flags = newFlags }),
                    oStart, oEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            if (oEnd < se) {
                text.setSpan(
                    TextStyleSpan(TextStyleSpan.TextStyleRun().also { it.flags = flags }),
                    oEnd, se, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        if (flag == TextStyleSpan.FLAG_STYLE_SPOILER) {
            revealedSpoiler = false
            try {
                edit.inu_setSpoilersRevealedNoText(false)
            } catch (_: Throwable) {
            }
        } else {
            edit.invalidateSpoilers()
        }
    }

    private fun clearQuote() {
        val (s, e) = currentRange() ?: return
        val text = edit.text ?: return
        for (q in text.getSpans(s, e, QuoteSpan::class.java)) {
            text.removeSpan(q)
            text.removeSpan(q.styleSpan)
            q.collapsedSpan?.let { text.removeSpan(it) }
        }
        edit.invalidateQuotes(true)
    }

    private fun clearLink() {
        val (s, e) = currentRange() ?: return
        val text = edit.text ?: return
        for (u in text.getSpans(s, e, URLSpanReplacement::class.java)) {
            text.removeSpan(u)
        }
    }

    private fun currentRange(): Pair<Int, Int>? {
        val s = edit.selectionStart
        val e = edit.selectionEnd
        if (s < 0 || e < 0 || s == e) return null
        return min(s, e) to max(s, e)
    }

    private fun hasStyleFlag(flag: Int): Boolean {
        val (s, e) = currentRange() ?: return false
        val text = edit.text ?: return false
        val spans = text.getSpans(s, e, TextStyleSpan::class.java)
        var cursor = s
        for (span in spans.sortedBy { text.getSpanStart(it) }) {
            if ((span.styleFlags and flag) == 0) continue
            val ss = max(s, text.getSpanStart(span))
            val se = min(e, text.getSpanEnd(span))
            if (ss > cursor) return false
            cursor = max(cursor, se)
            if (cursor >= e) return true
        }
        return cursor >= e
    }

    private fun hasSpoiler(): Boolean = hasStyleFlag(TextStyleSpan.FLAG_STYLE_SPOILER)

    private fun hasQuote(): Boolean {
        val (s, e) = currentRange() ?: return false
        val text = edit.text ?: return false
        val spans = text.getSpans(s, e, QuoteSpan::class.java)
        return spans.isNotEmpty()
    }

    private fun hasLink(): Boolean {
        val (s, e) = currentRange() ?: return false
        val text = edit.text ?: return false
        val spans = text.getSpans(s, e, URLSpanReplacement::class.java)
        return spans.isNotEmpty()
    }

    private inner class Item(val view: View, val icon: ImageView, val isActive: () -> Boolean) {
        private val itemColor = Theme.getColor(Theme.key_actionBarDefaultSubmenuItem)
        private val accentColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText)
        private val activeBg = Theme.multAlpha(accentColor, 0.08f)
        private val content = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
        private var current: Boolean? = null
        private var currentTint = itemColor
        private var anim: ValueAnimator? = null
        private val argb = ArgbEvaluator()

        init {
            view.background = content
            applyTint(itemColor)
            ScaleStateListAnimator.apply(icon, 0.15f, 2f)
        }

        // tl, tr, br, bl
        fun setCorners(left: Float, right: Float) {
            content.cornerRadii = floatArrayOf(left, left, right, right, right, right, left, left)
        }

        fun refresh(force: Boolean = false) {
            val active = isActive()
            if (!force && active == current) return
            val fromBg = (content.color?.defaultColor) ?: Color.TRANSPARENT
            val toBg = if (active) activeBg else Color.TRANSPARENT
            val fromTint = currentTint
            val toTint = if (active) accentColor else itemColor
            current = active
            anim?.cancel()
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 180
                addUpdateListener {
                    val t = it.animatedValue as Float
                    content.setColor(argb.evaluate(t, fromBg, toBg) as Int)
                    applyTint(argb.evaluate(t, fromTint, toTint) as Int)
                }
                start()
            }
        }

        private fun applyTint(color: Int) {
            currentTint = color
            icon.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        private var active: FormattingPopup? = null

        @JvmStatic
        fun isEnabled(): Boolean = InuConfig.FORMATTING_POPUP.value

        @JvmStatic
        fun tryHandle(edit: EditTextBoldCursor): Boolean {
            if (!isEnabled()) return false
            if (edit !is EditTextCaption) return false
            val s = edit.selectionStart
            val e = edit.selectionEnd
            if (s < 0 || e < 0 || s == e) return false
            val existing = active
            if (existing != null && existing.edit === edit && !existing.dismissed) {
                existing.sync()
                return true
            }
            existing?.dismiss()
            val popup = FormattingPopup(edit)
            if (popup.items.isEmpty()) return false
            active = popup
            popup.show()
            return true
        }

        private fun dp(v: Int): Int = AndroidUtilities.dp(v.toFloat())
        private fun dpf(v: Float): Float = AndroidUtilities.dp(v).toFloat()
    }
}
