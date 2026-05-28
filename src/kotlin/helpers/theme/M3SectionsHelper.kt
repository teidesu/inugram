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
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.HeaderCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.SettingsActivity
import java.lang.Boolean.TRUE

object M3SectionsHelper {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.FILL }
    private val path = Path()
    private val rect = RectF()
    private val radii = FloatArray(8)

    @JvmStatic
    fun isEnabled(): Boolean = InuConfig.M3_SECTIONS_STYLE.value

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
        val deco = listView.sectionsItemDecoration ?: return
        val isSection = deco.isSectionItem
        val bgColor = Theme.getColor(Theme.key_windowBackgroundWhite, listView.resourcesProvider)
        for (i in 0 until listView.childCount) {
            val child = listView.getChildAt(i) ?: continue
            if (child.visibility != View.VISIBLE || child.alpha <= 0f) continue
            if (!isSection.run(child)) continue
            val (tR, bR) = computeRadii(listView, child, i, isSection)
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
        if (child == null) return
        val deco = listView.sectionsItemDecoration ?: return
        val isSection = deco.isSectionItem
        if (!isSection.run(child)) return
        val index = listView.indexOfChild(child)
        val (tR, bR) = computeRadii(listView, child, index, isSection)
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
        isSection: Utilities.CallbackReturn<View, Boolean>,
    ): Pair<Float, Float> {
        val prev = if (childIndex >= 0) visualSibling(listView, childIndex, forward = false) else null
        val next = if (childIndex >= 0) visualSibling(listView, childIndex, forward = true) else null
        return m3Radii(child, prev != null && isSection.run(prev), next != null && isSection.run(next))
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
        val deco = listView.sectionsItemDecoration ?: return null
        val isSection = deco.isSectionItem
        if (!isSection.run(child)) return null
        val index = listView.indexOfChild(child)
        if (index < 0) return null
        return computeRadii(listView, child, index, isSection)
    }

    private fun m3Radii(child: View, prevIsSection: Boolean, nextIsSection: Boolean): Pair<Float, Float> {
        val outer = outerRForChild(child)
        val tR = when {
            isMergedWithPrev(child) -> 0f
            prevIsSection -> innerR
            else -> outer
        }
        val bR = when {
            isMergedWithNext(child) -> 0f
            nextIsSection -> innerR
            else -> outer
        }
        return tR to bR
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
        if (!listView.hasSections()) return
        val deco = listView.sectionsItemDecoration ?: return
        val isSection = deco.isSectionItem
        if (!isSection.run(child)) return
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
            val prev = prevView != null && isSection.run(prevView)
            val next = nextView != null && isSection.run(nextView)
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
    fun augmentItemOffsets(outRect: Rect, view: View, position: Int) {
        if (position > 0 && !isMergedWithPrev(view)) {
            outRect.top += gap
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
        val flat = ColorUtils.blendARGB(topColor, bottomColor, 0.5f)
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(flat, hsl)
        hsl[1] = hsl[1].coerceIn(0.55f, 1f)
        hsl[2] = 0.82f
        val bg = ColorUtils.HSLToColor(hsl)
        hsl[2] = 0.32f
        iconView.setColorFilter(ColorUtils.HSLToColor(hsl))
        cellBackground.inu_monetColor = bg
    }

    @JvmStatic
    fun styleHeaderCell(cell: HeaderCell) {
        cell.setBackgroundColor(0)
        cell.setBottomMargin(4)
        val tv = cell.textView ?: return
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        tv.typeface = AndroidUtilities.bold()
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader))
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
