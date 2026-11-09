package desu.inugram.helpers.theme

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.INavigationLayout
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.ChatActivityEnterView
import org.telegram.ui.Components.ChatActivityTopPanelLayout
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.FilterTabsView
import org.telegram.ui.Components.FragmentSearchField
import org.telegram.ui.Components.MentionsContainerView
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.SectionsScrollView
import org.telegram.ui.DialogsActivity
import org.telegram.ui.SearchTabsAndFiltersLayout
import org.telegram.utils.glass.BaseFragmentSimpleGlass
import desu.inugram.ui.ClassicBarDrawable
import org.telegram.utils.glass.source.GlassSource
import org.telegram.ui.Components.DialogsActivityTopPanelLayout
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory
import org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor
import org.telegram.ui.Components.chat.ViewPositionWatcher
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl
import kotlin.math.ceil
import kotlin.math.max

object NonIslandHelper {
    @JvmStatic
    fun foldersBar(): Boolean = InuConfig.NON_ISLAND_FOLDERS_BAR.value

    @JvmStatic
    fun sharedMediaTabs(): Boolean = InuConfig.NON_ISLAND_SHARED_MEDIA_TABS.value

    @JvmStatic
    fun globalSearch(): Boolean = InuConfig.NON_ISLAND_GLOBAL_SEARCH.value

    @JvmStatic
    fun chatElements(): Boolean = InuConfig.NON_ISLAND_CHAT_ELEMENTS.value

    @JvmStatic
    fun headers(): Boolean = InuConfig.NON_ISLAND_HEADERS.value

    @JvmStatic
    fun profileHeaders(): Boolean = headers() || sharedMediaTabs()

    @JvmStatic
    @JvmOverloads
    fun applyClassicHeaderBackground(
        header: View,
        list: View?,
        resourcesProvider: Theme.ResourcesProvider?,
        glass: BaseFragmentSimpleGlass,
        enabled: Boolean = headers(),
    ): Boolean {
        if (!enabled) return false
        val background = ClassicHeaderBackground(glass.frostedFactory, header, resourcesProvider, list != null)
        header.background = background
        when (list) {
            is RecyclerView -> list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    background.setOnTop(!recyclerView.canScrollVertically(-1))
                }
            })
            is SectionsScrollView -> list.onScroll { background.setOnTop(!list.canScrollVertically(-1)) }
        }
        // the parent may be dispatching attach to its children right now: adding a sibling then skips the last one
        header.post {
            val parent = header.parent as? ViewGroup ?: return@post
            val shadow = ClassicHeaderShadow(header, background)
            parent.addView(shadow, parent.indexOfChild(header) + 1, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, shadow.background.intrinsicHeight))
        }
        return true
    }

    private class ClassicHeaderBackground(
        factory: BlurredBackgroundDrawableViewFactory,
        header: View,
        resourcesProvider: Theme.ResourcesProvider?,
        private val adaptive: Boolean,
    ) : ClassicBarDrawable(factory, header, resourcesProvider) {
        private var onTop = adaptive
        private var onTopFactor = if (adaptive) 1f else 0f
        private var animator: ValueAnimator? = null

        private val factor get() = if (adaptive && AndroidUtilities.isTablet()) 1f else onTopFactor
        val shadowAlpha get() = (1f - factor) * 0.5f

        fun setOnTop(value: Boolean) {
            if (onTop == value) return
            onTop = value
            animator?.cancel()
            animator = ValueAnimator.ofFloat(onTopFactor, if (value) 1f else 0f).apply {
                addUpdateListener {
                    onTopFactor = it.animatedValue as Float
                    invalidateSelf()
                }
                duration = 320
                interpolator = CubicBezierInterpolator.EASE_OUT_QUINT
                start()
            }
        }

        override fun getColor(): Int = ColorUtils.blendARGB(
            Theme.getColor(Theme.key_actionBarDefault, resourcesProvider),
            Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider),
            factor,
        )
    }

    private class ClassicHeaderShadow(
        private val header: View,
        private val headerBackground: ClassicHeaderBackground,
    ) : View(header.context), ViewTreeObserver.OnPreDrawListener {
        init {
            setBackgroundResource(R.drawable.header_shadow)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            viewTreeObserver.addOnPreDrawListener(this)
        }

        override fun onDetachedFromWindow() {
            viewTreeObserver.removeOnPreDrawListener(this)
            super.onDetachedFromWindow()
        }

        override fun onPreDraw(): Boolean {
            visibility = header.visibility
            translationY = header.y + header.height - top
            alpha = header.alpha * headerBackground.shadowAlpha
            return true
        }
    }

    @JvmStatic
    fun chatInputRowHeight(): Int = if (chatElements()) 48 else 44

    @JvmStatic
    fun chatSendIcon(): Int = if (chatElements()) R.drawable.ic_send else R.drawable.send_plane_24

    @JvmStatic
    fun createInputButtonSelector(color: Int): Drawable =
        if (chatElements()) Theme.createSelectorDrawable(color)
        else Theme.createInsetRoundRectDrawable(color, dp(19f).toFloat(), dp(1f), dp(3f))

    @JvmStatic
    fun applySendButtonRipple(button: View, frameWidthDp: Int, color: Int) {
        if (!chatElements()) return
        val box = dp(ChatActivityEnterView.DEFAULT_HEIGHT.toFloat())
        val inset = dp(3f)
        button.background = Theme.createInsetRoundRectDrawable(
            color,
            (box - inset * 2) / 2f,
            dp(frameWidthDp.toFloat()) - box + inset, inset, inset, inset,
        )
    }

    // ChatActivity.java
    @JvmStatic
    fun needChatLightNavBar(
        inputBubbleHeight: Float,
        resourcesProvider: Theme.ResourcesProvider?,
    ): Boolean? {
        if (!chatElements()) return null
        if (inputBubbleHeight <= 0f) return null
        val color = Theme.getColor(Theme.key_chat_messagePanelBackground, resourcesProvider)
        return AndroidUtilities.computePerceivedBrightness(color) <= 0.9f
    }

    @JvmStatic
    fun needChatLightStatusBar(resourcesProvider: Theme.ResourcesProvider?): Boolean? {
        if (!chatElements()) return null
        val color = Theme.getColor(Theme.key_chat_topPanelBackground, resourcesProvider)
        return AndroidUtilities.computePerceivedBrightness(color) > 0.721f
    }

    // ChatAttachAlert.java
    const val ATTACH_TAB_SHADOW_DP = 3f

    @JvmStatic
    fun applyChatAttachTabBar(
        wrapper: FrameLayout,
        recyclerView: RecyclerListView,
        factory: BlurredBackgroundDrawableViewFactory,
        resourcesProvider: Theme.ResourcesProvider?,
    ) {
        if (!chatElements()) return
        val shadow = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Theme.multAlpha(Theme.getColor(Theme.key_dialogShadowLine, resourcesProvider), 0.5f), 0))
        wrapper.background = LayerDrawable(arrayOf(shadow, ClassicBarDrawable(factory, wrapper, resourcesProvider, Theme.key_dialogBackground))).apply {
            setLayerGravity(0, Gravity.TOP or Gravity.FILL_HORIZONTAL)
            setLayerHeight(0, dp(ATTACH_TAB_SHADOW_DP))
            setLayerInsetTop(1, dp(ATTACH_TAB_SHADOW_DP))
        }
        wrapper.clipChildren = false
        recyclerView.clipToOutline = false
        recyclerView.outlineProvider = null
        val innerPaddingTop = dp(4f)
        val innerPadding = dp(6f)
        recyclerView.setPadding(innerPadding, innerPaddingTop, innerPadding, AndroidUtilities.navigationBarHeight)
        recyclerView.clipToPadding = false
        val recyclerLp = recyclerView.layoutParams as? FrameLayout.LayoutParams ?: return
        val shadowH = dp(ATTACH_TAB_SHADOW_DP)
        recyclerLp.topMargin = shadowH
        val lp = wrapper.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.height = shadowH + dp(48f) + innerPaddingTop + AndroidUtilities.navigationBarHeight
        // container's onLayout already offsets BOTTOM children by navigationBarHeight,
        // so use negative bottomMargin to extend the wrapper into the nav bar area
        lp.bottomMargin = -AndroidUtilities.navigationBarHeight
    }

    // DialogsActivity.java
    const val FOLDERS_BAR_HEIGHT_DP = 44
    const val FOLDERS_BAR_OVERLAP_DP = 10

    // the classic folders bar tucks under the classic search bar; with the island search
    // pill there is nothing to tuck under, so the overlap must not be applied
    @JvmStatic
    fun getFoldersBarOverlap(): Int = if (globalSearch()) dp(FOLDERS_BAR_OVERLAP_DP.toFloat()) else 0

    @JvmStatic
    fun getFoldersBarVisibleHeight(): Int = dp(FOLDERS_BAR_HEIGHT_DP.toFloat()) - getFoldersBarOverlap()

    @JvmStatic
    fun applyMd3TabsStyle(indicator: GradientDrawable, listView: RecyclerListView, selectorColor: Int) {
        val rad = AndroidUtilities.dpf2(3f)
        indicator.cornerRadii = floatArrayOf(rad, rad, rad, rad, 0f, 0f, 0f, 0f)
        listView.setSelectorType(42)
        listView.setSelectorRadius(16)
        listView.setSelectorDrawableColor(selectorColor)
    }

    @JvmStatic
    fun adjustMd3TabSelectorRect(rect: Rect) {
        val cy = rect.centerY()
        rect.top = cy - dp(16f)
        rect.bottom = cy + dp(16f)
        rect.inset(dp(2f), 0)
    }

    @JvmStatic
    @JvmOverloads
    fun setMd3TabIndicatorBounds(indicator: Drawable, indicatorX: Float, indicatorWidth: Float, height: Float, hideProgress: Float, atTop: Boolean = false) {
        val centerX = indicatorX + indicatorWidth / 2f
        val width = max(dp(24f).toFloat(), indicatorWidth - dp(2f) * 2)
        val hideOffset = hideProgress * dp(3f)
        val top = if (atTop) -hideOffset else height - dp(3f) + hideOffset
        indicator.setBounds(
            (centerX - width / 2f).toInt(),
            top.toInt(),
            (centerX + width / 2f).toInt(),
            (top + dp(3f)).toInt(),
        )
    }

    @JvmStatic
    fun applyFilterTabBar(tabsView: FilterTabsView, factory: BlurredBackgroundDrawableViewFactory, resourcesProvider: Theme.ResourcesProvider?) {
        if (!foldersBar()) return
        tabsView.setBlurredBackground(null)
        tabsView.inu_classicBackground = ClassicBarDrawable(factory, tabsView, resourcesProvider, drawBottomDivider = true)
            .apply { externalBlur = true }
        tabsView.setPadding(0, 0, 0, 0)
        val lp = tabsView.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.height = dp(FOLDERS_BAR_HEIGHT_DP.toFloat())
        lp.leftMargin = 0
        lp.rightMargin = 0
    }

    @JvmStatic
    fun moveFilterTabBarToBottom(tabsView: FilterTabsView, aboveMaterialTabs: Boolean) {
        (tabsView.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.BOTTOM
        val background = tabsView.inu_classicBackground ?: return
        background.drawBottomDivider = false
        background.drawTopDivider = true
        if (aboveMaterialTabs) {
            background.colorKey = Theme.key_glass_targetMainTabs
            background.blurredColorProvider = BlurredBackgroundProviderImpl.mainTabs(background.resourcesProvider)
        }
        tabsView.inu_indicatorAtTop = true
        val rad = AndroidUtilities.dpf2(3f)
        (tabsView.selectorDrawable as GradientDrawable).cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, rad, rad, rad, rad)
    }

    // the header and the bars under it get one blur and their tints in one canvas. separate blurs (own drawables, or
    // even the same source drawn once per bar with its own clip) come out slightly different and the seams shimmer
    @JvmStatic
    fun drawTopBarsBlur(canvas: Canvas, source: GlassSource?, headerTop: Int, width: Int, searchField: FragmentSearchField?, filterTabs: FilterTabsView?, topPanel: DialogsActivityTopPanelLayout?) {
        if (source == null) return
        fun hasExternalBlur(view: View, background: ClassicBarDrawable?) = view.isVisible && background != null && background.externalBlur && background.isBlurred
        val segments = listOfNotNull(
            searchField?.takeIf { hasExternalBlur(it, it.inu_classicBackground) }?.let { BlurSegment(it.inu_classicBackground, it.y, it.y + it.height, it.alpha) },
            filterTabs?.takeIf { hasExternalBlur(it, it.inu_classicBackground) }?.let { BlurSegment(it.inu_classicBackground, it.y, it.y + it.height, it.alpha) },
            topPanel?.takeIf { hasExternalBlur(it, it.inu_classicBackground) }?.let { BlurSegment(it.inu_classicBackground, it.y, it.y + it.getAnimatedHeightWithPadding(0f), it.alpha * it.metadata.totalVisibility) },
        ).filter { it.bottom > it.top && it.alpha > 0f }.sortedBy { it.top }.toMutableList()
        val first = segments.firstOrNull() ?: return
        if (headerTop < first.top) segments.add(0, BlurSegment(first.background, headerTop.toFloat(), first.top, 1f))
        drawBlurSegments(canvas, source, width.toFloat(), segments)
    }

    // how far the stock frosted blur (40dp radius) samples: content nearer to a bar than this must be captured, or the
    // blur clamps the capture's edge row and anything entering it pops in
    @JvmStatic
    fun getFrostedBlurReach(): Int = ceil(3 * DownscaleScrollableNoiseSuppressor.convertRadiusToSigma(AndroidUtilities.dpf2(40f))).toInt()

    // the bottom bar and the screen under it are one surface, the main tabs' background included
    @JvmStatic
    fun drawBottomBarsBlur(canvas: Canvas, source: GlassSource?, width: Int, height: Int, filterTabs: FilterTabsView, belowAlpha: Float) {
        val background = filterTabs.inu_classicBackground ?: return
        val blurSource = source?.takeIf { background.isBlurred }
        val barTop = filterTabs.y
        val barBottom = barTop + filterTabs.height
        val segments = listOfNotNull(
            BlurSegment(background, barTop, barBottom, filterTabs.alpha).takeIf { blurSource != null && filterTabs.isVisible },
            BlurSegment(background, barBottom, height.toFloat(), belowAlpha),
        ).filter { it.bottom > it.top && it.alpha > 0f }
        if (segments.isEmpty()) return
        if (blurSource != null) {
            drawBlurSegments(canvas, blurSource, width.toFloat(), segments)
            return
        }
        for (segment in segments) {
            segment.background.drawTint(canvas, 0f, segment.top, width.toFloat(), segment.bottom, (segment.alpha * 255).toInt())
        }
    }

    private val listRect = RectF()

    @JvmStatic
    fun clipChatBlurCaptureToScrolledContent(canvas: Canvas, captureRect: RectF, list: RecyclerView, root: View) {
        if (!chatElements() || captureRect.centerY() < root.height / 2f) return
        val layoutManager = list.layoutManager as? LinearLayoutManager ?: return
        val itemCount = list.adapter?.itemCount ?: return
        val newestItem = layoutManager.findViewByPosition(if (layoutManager.reverseLayout) 0 else itemCount - 1) ?: return
        if (!ViewPositionWatcher.computeRectInParent(list, root, listRect)) return
        clipCaptureToListBottom(canvas, list, newestItem)
    }

    // listRect holds the list's rect in the capture's coordinates
    private fun clipCaptureToListBottom(canvas: Canvas, list: RecyclerView, edgeItem: View) {
        val restBottom = list.height - list.paddingBottom
        val scrolled = edgeItem.bottom + edgeItem.translationY - restBottom
        if (scrolled < getFrostedBlurReach()) canvas.clipRect(listRect.left, listRect.top + restBottom - max(0f, scrolled), listRect.right, listRect.bottom)
    }

    // the blur samples past a bar's edge, so at a list's end the edge item leaks into the bar with nothing under it yet.
    // near an end, only as much content past the item's resting edge is captured as has scrolled under the bar.
    // rows above the top item (hidden archive) sit under the header by design and are never captured
    @JvmStatic
    fun clipBlurCaptureToScrolledContent(canvas: Canvas, captureRect: RectF, list: RecyclerView, root: View, filterTabsAtBottom: Boolean, topIndex: Int, topRestOffset: Float) {
        val atBottom = captureRect.centerY() > root.height / 2f
        if (if (atBottom) !filterTabsAtBottom || !foldersBar() else !globalSearch() && !foldersBar()) return
        val itemCount = list.adapter?.itemCount ?: return
        val edgeItem = list.layoutManager?.findViewByPosition(if (atBottom) itemCount - 1 else topIndex) ?: return
        if (!ViewPositionWatcher.computeRectInParent(list, root, listRect)) return
        val reach = getFrostedBlurReach()
        if (atBottom) {
            clipCaptureToListBottom(canvas, list, edgeItem)
        } else {
            val edgeTop = edgeItem.top + edgeItem.translationY
            val restTop = list.paddingTop + topRestOffset
            val scrolled = restTop - edgeTop
            canvas.clipRect(
                listRect.left, listRect.top + if (topIndex > 0) edgeTop else 0f,
                listRect.right, if (scrolled < reach) listRect.top + restTop + max(0f, scrolled) else listRect.bottom,
            )
        }
    }

    private fun drawBlurSegments(canvas: Canvas, source: GlassSource, right: Float, segments: List<BlurSegment>) {
        if (segments.all { it.alpha >= 1f } && segments.zipWithNext().all { (a, b) -> b.top <= a.bottom }) {
            val top = segments.first().top
            val bottom = segments.maxOf { it.bottom }
            val count = canvas.save()
            canvas.clipRect(0f, top, right, bottom)
            source.draw(canvas, 0f, top, right, bottom)
            canvas.restoreToCount(count)
            var tinted = top
            for (segment in segments) {
                if (segment.bottom <= tinted) continue
                segment.background.drawTint(canvas, 0f, maxOf(segment.top, tinted), right, segment.bottom, 255)
                tinted = segment.bottom
            }
            return
        }
        for (segment in segments) {
            val count = if (segment.alpha < 1f) canvas.saveLayerAlpha(0f, segment.top, right, segment.bottom, (segment.alpha * 255).toInt()) else canvas.save()
            canvas.clipRect(0f, segment.top, right, segment.bottom)
            source.draw(canvas, 0f, segment.top, right, segment.bottom)
            segment.background.drawTint(canvas, 0f, segment.top, right, segment.bottom, 255)
            canvas.restoreToCount(count)
        }
    }

    private class BlurSegment(val background: ClassicBarDrawable, val top: Float, val bottom: Float, val alpha: Float)

    @JvmStatic
    fun applyGlobalSearchBar(field: FragmentSearchField, factory: BlurredBackgroundDrawableViewFactory, resourcesProvider: Theme.ResourcesProvider?) {
        if (!globalSearch()) return
        field.setupBlurredBackground(null)
        field.inu_classicBackground = ClassicBarDrawable(factory, field, resourcesProvider).apply { externalBlur = true }
        val lp = field.layoutParams as? FrameLayout.LayoutParams
        if (lp != null) {
            lp.leftMargin = 0
            lp.rightMargin = 0
        }
        updateGlobalSearchBarInsets(field)
    }

    // statusBarHeight is 0 at createView under the non-legacy inset system (only the live
    // window insets fill it in, after createView). The field's top padding pushes the input
    // below the status bar while the blur covers the area above it; the per-draw translationY
    // subtracts the same statusBarHeight. Baking a stale (0) value here leaves the bar
    // statusBarHeight too high once insets land, so re-apply on every inset change.
    @JvmStatic
    fun updateGlobalSearchBarInsets(field: FragmentSearchField) {
        if (!globalSearch()) return
        // extra padding to cover with blur the area above the search bar
        val extraTopPadding = AndroidUtilities.statusBarHeight + dp(8f)
        field.setPadding(0, extraTopPadding, 0, 0)
        val lp = field.layoutParams as? FrameLayout.LayoutParams ?: return
        val height = dp(DialogsActivity.SEARCH_FIELD_HEIGHT.toFloat()) + extraTopPadding
        if (lp.height != height) {
            lp.height = height
            field.requestLayout()
        }
    }

    @JvmStatic
    fun applyGlobalSearchTabs(layout: SearchTabsAndFiltersLayout, factory: BlurredBackgroundDrawableViewFactory, resourcesProvider: Theme.ResourcesProvider?) {
        if (!globalSearch()) return
        layout.setBlurredBackground(null)
        layout.inu_classicBackground = ClassicBarDrawable(factory, layout, resourcesProvider, drawBottomDivider = true)
        layout.setPadding(0, 0, 0, 0)
        layout.translationY = -dp(FOLDERS_BAR_OVERLAP_DP.toFloat()).toFloat()
        val lp = layout.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.height = dp(FOLDERS_BAR_HEIGHT_DP.toFloat())
        lp.leftMargin = 0
        lp.rightMargin = 0
    }

    // ChatActivity.java
    @JvmStatic
    fun applyChatTopPanelButton(view: TextView) {
        if (!chatElements()) return
        view.stateListAnimator = null // disable ScaleStateListAnimator in case there is one
        view.background = Theme.createSelectorDrawable(
            Theme.multAlpha(view.currentTextColor, 0.10f), Theme.RIPPLE_MASK_ALL
        )
    }

    @JvmStatic
    fun drawChatHeaderShadow(
        parentLayout: INavigationLayout,
        canvas: Canvas,
        topPanelLayout: ChatActivityTopPanelLayout?,
        mentionContainer: MentionsContainerView?,
        topicsTabsHeight: Float,
        actionBarBottom: Int,
    ) {
        if (!chatElements()) {
            // upstream no longer draws an action-bar header shadow in island mode
            return
        }

        val hasPanel = topPanelLayout != null && actionBarBottom > 0;
        val panelH = if (hasPanel) topPanelLayout.getAnimatedHeightWithPadding(0f).toInt() else 0;
        if (!(mentionContainer != null && mentionContainer.isVisible)) {
            // dont draw shadow if mention container is visible (todo: probably need a smoother way but im too lazy rn)
            parentLayout.drawHeaderShadow(canvas, actionBarBottom + topicsTabsHeight.toInt() + panelH);
        }
    }
}
