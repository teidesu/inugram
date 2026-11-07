package desu.inugram.helpers.theme

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.HeaderCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Cells.TextInfoPrivacyCell
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.SectionsScrollView
import org.telegram.ui.Components.TranslateAlert3
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.SettingsActivity
import java.lang.Boolean.TRUE

object M3SectionsHelper {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.FILL }
    private val path = Path()
    private val rect = RectF()
    private val radii = FloatArray(8)
    private val splitDividerPaint = Paint()

    @JvmStatic
    fun isEnabled(): Boolean = InuConfig.M3_SECTIONS_STYLE.value

    /** Theme.dividerPaint is hidden while section children draw, so vertical split lines need their own */
    @JvmStatic
    fun getSplitDividerPaint(resourcesProvider: Theme.ResourcesProvider?): Paint {
        if (!isEnabled()) return Theme.dividerPaint
        splitDividerPaint.color = Theme.getColor(Theme.key_divider, resourcesProvider)
        return splitDividerPaint
    }

    @JvmStatic
    fun shadowHeightDp(nextItem: UItem?, stockDp: Int): Int {
        if (!isEnabled()) return stockDp
        return if (nextItem != null && isHeaderViewType(nextItem.viewType)) 10 else 16
    }

    @JvmStatic
    fun isHeaderViewType(viewType: Int): Boolean {
        return viewType == UniversalAdapter.VIEW_TYPE_HEADER
            || viewType == UniversalAdapter.VIEW_TYPE_BLACK_HEADER
            || viewType == UniversalAdapter.VIEW_TYPE_LARGE_HEADER
            || viewType == UniversalAdapter.VIEW_TYPE_ANIMATED_HEADER
    }

    @JvmStatic
    fun markMerged(view: View, withPrev: Boolean, withNext: Boolean) {
        if (!isEnabled()) return
        view.setTag(R.id.inu_merge_with_prev, if (withPrev) TRUE else null)
        view.setTag(R.id.inu_merge_with_next, if (withNext) TRUE else null)
    }

    private fun isMergedWithPrev(view: View?): Boolean {
        return view?.getTag(R.id.inu_merge_with_prev) == TRUE
    }

    private fun isMergedWithNext(view: View?): Boolean {
        return view?.getTag(R.id.inu_merge_with_next) == TRUE
    }

    @JvmStatic
    fun isDetachedHeaderCell(view: View): Boolean {
        return isEnabled() && (view is HeaderCell || view is TranslateAlert3.Header) && !isMergedWithPrev(view) && !isMergedWithNext(view)
    }

    private val outerR get() = AndroidUtilities.dp(20f).toFloat()
    private val innerR get() = AndroidUtilities.dp(4f).toFloat()
    private val gap get() = AndroidUtilities.dp(2f)

    // ripple-check (asRippleCheck) rows render as the MD3 "primary toggle" pill — full pill rounding
    // (height/2) when the row stands alone; if stacked with content, the inner side falls back to
    // `innerR` like any other section corner.
    private fun outerRForChild(child: View): Float {
        if (child is TextCheckCell && child.drawCheckRipple) return child.height / 2f
        return outerR
    }

    @JvmStatic
    fun drawSectionsBackgrounds(canvas: Canvas, listView: RecyclerListView) {
        if (!listView.hasSections()) return
        val bgColor = Theme.getColor(Theme.key_windowBackgroundWhite, listView.resourcesProvider)
        for (i in 0 until listView.childCount) {
            val child = listView.getChildAt(i) ?: continue
            if (child.visibility != View.VISIBLE || child.alpha <= 0f) continue
            if (!listView.isVisualSectionItem(child)) continue
            val (tR, bR) = computeRadii(listView, child, i)
            rect.set(
                child.left.toFloat(),
                RecyclerListView.top(child),
                child.right.toFloat(),
                RecyclerListView.bottom(child),
            )
            setRadii(tR, bR)
            path.rewind()
            path.addRoundRect(rect, radii, Path.Direction.CW)
            paint.color = multAlpha(bgColor, child.alpha)
            canvas.drawPath(path, paint)
        }
    }

    @JvmStatic
    fun clipChild(canvas: Canvas, child: View?, listView: RecyclerListView) {
        if (child == null || !listView.hasSections() || !listView.isVisualSectionItem(child)) return
        val index = listView.indexOfChild(child)
        val (tR, bR) = computeRadii(listView, child, index)
        rect.set(
            child.x,
            RecyclerListView.top(child),
            child.x + child.width,
            RecyclerListView.bottom(child),
        )
        setRadii(tR, bR)
        path.rewind()
        path.addRoundRect(rect, radii, Path.Direction.CW)
        canvas.clipPath(path)
    }

    private fun computeRadii(
        listView: RecyclerListView,
        child: View,
        childIndex: Int,
    ): Pair<Float, Float> {
        val prev = if (childIndex >= 0) visualSibling(listView, childIndex, forward = false) else null
        val next = if (childIndex >= 0) visualSibling(listView, childIndex, forward = true) else null
        return m3Radii(
            child,
            prev?.takeIf { listView.isVisualSectionItem(it) },
            next?.takeIf { listView.isVisualSectionItem(it) },
        )
    }

    private fun visualSibling(listView: RecyclerListView, fromIndex: Int, forward: Boolean): View? {
        val step = if (forward) 1 else -1
        var i = fromIndex + step
        while (i in 0 until listView.childCount) {
            val v = listView.getChildAt(i)
            if (v != null && v.visibility == View.VISIBLE && v.alpha > 0.01f) return v
            i += step
        }
        return null
    }

    // M3 corner radii for an attached section child, via the same visual-sibling walk the
    // background/clip paths use. null = not a section / not attached → caller falls back to stock.
    private fun sectionRadiiFor(listView: RecyclerListView, child: View): Pair<Float, Float>? {
        if (!listView.hasSections() || !listView.isVisualSectionItem(child)) return null
        val index = listView.indexOfChild(child)
        if (index < 0) return null
        return computeRadii(listView, child, index)
    }

    private fun m3Radii(child: View, prev: View?, next: View?): Pair<Float, Float> {
        val outer = outerRForChild(child)
        val tR = when {
            isMergedWithPrev(child) || isMergedWithNext(prev) -> 0f
            prev != null -> innerR
            else -> outer
        }
        val bR = when {
            isMergedWithNext(child) || isMergedWithPrev(next) -> 0f
            next != null -> innerR
            else -> outer
        }
        return tR to bR
    }

    @JvmStatic
    fun drawScrollSectionsBackgrounds(
        canvas: Canvas,
        content: View,
        rows: List<View>,
        resourcesProvider: Theme.ResourcesProvider?,
    ) {
        canvas.save()
        canvas.translate(content.x, content.y)
        drawRowsBackgrounds(canvas, content, rows, resourcesProvider, fullWidth = true)
        canvas.restore()
    }

    @JvmStatic
    fun clipScrollSectionChild(canvas: Canvas, content: View, rows: List<View>, child: View) {
        clipRowsChild(canvas, content, rows, child, fullWidth = true)
    }

    @JvmStatic
    fun setupOwnSections(item: View, container: ViewGroup) {
        if (!isEnabled()) return
        item.tag = RecyclerListView.TAG_NOT_SECTION
        container.setTag(R.id.inu_own_sections, TRUE)
        val inset = AndroidUtilities.dp(12f)
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is HeaderCell) styleHeaderCell(child)
            if (child is TextInfoPrivacyCell) {
                val textLp = child.textView.layoutParams as ViewGroup.MarginLayoutParams
                textLp.leftMargin += inset
                textLp.rightMargin += inset
            }
            if (!SectionsScrollView.isSectionView(child)) continue
            val lp = child.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            lp.leftMargin = inset
            lp.rightMargin = inset
        }
    }

    @JvmStatic
    fun drawOwnSectionsBackgrounds(canvas: Canvas, container: ViewGroup) {
        if (container.getTag(R.id.inu_own_sections) != TRUE) return
        drawRowsBackgrounds(canvas, container, getVisibleChildren(container), null, fullWidth = false)
    }

    @JvmStatic
    fun clipOwnSectionChild(canvas: Canvas, container: ViewGroup, child: View) {
        if (container.getTag(R.id.inu_own_sections) != TRUE) return
        clipRowsChild(canvas, container, getVisibleChildren(container), child, fullWidth = false)
    }

    private fun getVisibleChildren(container: ViewGroup): List<View> {
        return (0 until container.childCount).map { container.getChildAt(it) }.filter { it.visibility == View.VISIBLE }
    }

    private fun drawRowsBackgrounds(
        canvas: Canvas,
        content: View,
        rows: List<View>,
        resourcesProvider: Theme.ResourcesProvider?,
        fullWidth: Boolean,
    ) {
        val bgColor = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider)
        for (i in rows.indices) {
            if (!SectionsScrollView.isSectionView(rows[i])) continue
            path.rewind()
            addRowPath(path, content, rows, i, fullWidth)
            paint.color = multAlpha(bgColor, getAlphaInContent(rows[i], content) * content.alpha)
            canvas.drawPath(path, paint)
        }
    }

    private fun clipRowsChild(canvas: Canvas, content: View, rows: List<View>, child: View, fullWidth: Boolean) {
        path.rewind()
        for (i in rows.indices) {
            if (isInsideOf(rows[i], child, content)) addRowPath(path, content, rows, i, fullWidth)
        }
        if (!path.isEmpty) canvas.clipPath(path)
    }

    private fun addRowPath(target: Path, content: View, rows: List<View>, index: Int, fullWidth: Boolean) {
        val row = rows[index]
        var x = 0f
        var y = 0f
        var v: View? = row
        while (v != null && v !== content) {
            x += v.x
            y += v.y
            v = v.parent as? View
        }
        val isSection = SectionsScrollView.isSectionView(row)
        if (fullWidth && isSection) {
            rect.set(content.paddingLeft.toFloat(), y, (content.width - content.paddingRight).toFloat(), y + row.height)
        } else {
            rect.set(x, y, x + row.width, y + row.height)
        }
        val lp = row.layoutParams
        if (row.parent !== content && lp is ViewGroup.MarginLayoutParams) {
            rect.top -= lp.topMargin
            rect.bottom += lp.bottomMargin
        }
        if (!isSection) {
            target.addRect(rect, Path.Direction.CW)
            return
        }
        val alpha = getAlphaInContent(row, content)
        fun getNeighbour(i: Int): View? = rows.getOrNull(i)?.takeIf {
            SectionsScrollView.isSectionView(it) && Math.abs(getAlphaInContent(it, content) - alpha) <= 0.1f
        }
        val prev = getNeighbour(index - 1)
        val next = getNeighbour(index + 1)
        val (tR, bR) = m3Radii(row, prev, next)
        if (prev != null && tR > 0f) rect.top += gap / 2f
        if (next != null && bR > 0f) rect.bottom -= gap / 2f
        setRadii(tR, bR)
        target.addRoundRect(rect, radii, Path.Direction.CW)
    }

    private fun getAlphaInContent(view: View, content: View): Float {
        var alpha = 1f
        var v: View? = view
        while (v != null && v !== content) {
            alpha *= v.alpha
            v = v.parent as? View
        }
        return alpha
    }

    private fun isInsideOf(view: View, ancestor: View, content: View): Boolean {
        var v: View? = view
        while (v != null && v !== content) {
            if (v === ancestor) return true
            v = v.parent as? View
        }
        return false
    }

    @JvmStatic
    fun makeClipBackground(listView: RecyclerListView, child: View): Drawable? {
        val (tR, bR) = sectionRadiiFor(listView, child) ?: return null
        val bgColor = Theme.getColor(Theme.key_windowBackgroundWhite, listView.resourcesProvider)
        val cw = child.width
        val ch = child.height
        val radiiArr = floatArrayOf(tR, tR, tR, tR, bR, bR, bR, bR)
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val clipPath = Path()
            private val tmp = RectF()
            override fun draw(canvas: Canvas) {
                canvas.save()
                tmp.set(0f, 0f, cw.toFloat(), ch.toFloat())
                clipPath.rewind()
                clipPath.addRoundRect(tmp, radiiArr, Path.Direction.CW)
                canvas.clipPath(clipPath)
                paint.color = ColorUtils.setAlphaComponent(bgColor, paint.alpha)
                canvas.drawRect(tmp, paint)
                canvas.restore()
            }

            override fun setAlpha(alpha: Int) {
                paint.alpha = alpha
            }

            override fun setColorFilter(cf: ColorFilter?) {}
            override fun getOpacity(): Int = PixelFormat.TRANSPARENT
        }
    }

    @JvmStatic
    fun applyScrimClip(canvas: Canvas, child: View) {
        val listView = child.parent as? RecyclerListView ?: return
        if (!listView.hasSections() || !listView.isVisualSectionItem(child)) return
        rect.set(0f, 0f, child.width.toFloat(), child.height.toFloat())
        path.rewind()
        if (isEnabled()) {
            val (tR, bR) = sectionRadiiFor(listView, child) ?: return
            setRadii(tR, bR)
            path.addRoundRect(rect, radii, Path.Direction.CW)
        } else {
            // stock-fallback: mirror stock clipChild's adapter-position neighbour lookup
            val position = listView.getChildAdapterPosition(child)
            val prevView = if (position != RecyclerView.NO_POSITION) listView.findViewByPosition(position - 1) else null
            val nextView = if (position != RecyclerView.NO_POSITION) listView.findViewByPosition(position + 1) else null
            val prev = prevView != null && listView.isVisualSectionItem(prevView)
            val next = nextView != null && listView.isVisualSectionItem(nextView)
            if (prev && next) return
            when {
                !prev && !next -> path.addRoundRect(rect, listView.sectionRadius, listView.sectionRadius, Path.Direction.CW)
                !prev -> path.addRoundRect(rect, listView.sectionRadiusTop, Path.Direction.CW)
                else -> path.addRoundRect(rect, listView.sectionRadiusBottom, Path.Direction.CW)
            }
        }
        canvas.clipPath(path)
    }

    @JvmStatic
    fun augmentItemOffsets(outRect: Rect, listView: RecyclerView, view: View) {
        // disappearing holders in a change animation have no adapter position anymore; without the
        // layout-position fallback they'd lose the gap and jump while fading out. their layout
        // position indexes the old list, so the is-last test against the new count is skipped too
        val adapterPosition = listView.getChildAdapterPosition(view)
        val disappearing = adapterPosition == RecyclerView.NO_POSITION
        val position = if (disappearing) listView.getChildLayoutPosition(view) else adapterPosition
        val isLast = !disappearing && position == (listView.adapter?.itemCount ?: 0) - 1
        if (position >= 0 && !isLast && !isMergedWithNext(view)) {
            outRect.bottom += gap
        }
    }

    @JvmStatic
    fun applySettingCellIcon(
        iconLayout: View,
        iconView: ImageView,
        topColor: Int,
        bottomColor: Int,
        cellBackground: SettingsActivity.SettingCell.Background,
    ) {
        if (!isEnabled()) return

        fun resizeSquare(view: View, dp: Int) {
            val size = AndroidUtilities.dp(dp.toFloat())
            val lp = view.layoutParams ?: return
            if (lp.width != size) {
                lp.width = size
                lp.height = size
                view.layoutParams = lp
            }
        }

        resizeSquare(iconLayout, 36)
        resizeSquare(iconView, 22)
        val (bg, fg) = computeIconColors(topColor, bottomColor)
        iconView.setColorFilter(fg)
        cellBackground.inu_monetColor = bg
    }

    @JvmStatic
    fun applyTextCellColorfulIcon(
        iconView: ImageView,
        topColor: Int,
        bottomColor: Int,
        cellBackground: SettingsActivity.SettingCell.Background,
    ) {
        if (!isEnabled()) return
        iconView.translationX = 0f
        val (bg, fg) = computeIconColors(topColor, bottomColor)
        iconView.setColorFilter(fg)
        cellBackground.inu_monetColor = bg
    }

    private fun computeIconColors(topColor: Int, bottomColor: Int): Pair<Int, Int> {
        val flat = ColorUtils.blendARGB(topColor, bottomColor, 0.5f)
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(flat, hsl)
        hsl[1] = hsl[1].coerceIn(0.55f, 1f)
        hsl[2] = 0.82f
        val bg = ColorUtils.HSLToColor(hsl)
        hsl[2] = 0.32f
        return bg to ColorUtils.HSLToColor(hsl)
    }

    @JvmStatic
    fun styleHeaderCell(cell: HeaderCell) {
        if (!isEnabled()) return
        cell.setBackgroundColor(0)
        cell.setBottomMargin(4)
        val tv = cell.textView ?: return
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        tv.typeface = AndroidUtilities.bold()
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader))
    }

    /** SectionsScrollView pads its content by 12dp, while list headers span the full width with 20dp padding */
    @JvmStatic
    fun styleScrollHeaderCell(cell: HeaderCell) {
        if (!isEnabled() || cell.textView == null) return
        var parent = cell.parent
        while (parent != null && parent !is SectionsScrollView) parent = parent.parent
        if (parent == null) return
        styleHeaderCell(cell)
        cell.setHeight(40)
        val lp = cell.textView.layoutParams as ViewGroup.MarginLayoutParams
        lp.leftMargin = AndroidUtilities.dp(8f)
        lp.rightMargin = AndroidUtilities.dp(8f)
    }

    @JvmStatic
    fun styleTranslateHeader(header: TranslateAlert3.Header, resourcesProvider: Theme.ResourcesProvider?) {
        if (!isEnabled()) return
        header.setPadding(header.paddingLeft, AndroidUtilities.dp(17f), header.paddingRight, header.paddingBottom)
        val color = Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, resourcesProvider)
        for (tv in arrayOf(header.text1View, header.text2View, header.text3View)) {
            tv.typeface = AndroidUtilities.bold()
            tv.setTextColor(color)
        }
    }

    private fun setRadii(top: Float, bottom: Float) {
        radii[0] = top; radii[1] = top; radii[2] = top; radii[3] = top
        radii[4] = bottom; radii[5] = bottom; radii[6] = bottom; radii[7] = bottom
    }

    private fun multAlpha(color: Int, alpha: Float): Int {
        val a = (((color ushr 24) and 0xFF) * alpha).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }
}
