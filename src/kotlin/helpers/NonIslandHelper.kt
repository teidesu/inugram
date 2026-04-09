package desu.inugram.helpers

import android.graphics.Canvas
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.ActionBar.INavigationLayout
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.ChatActivityTopPanelLayout
import org.telegram.ui.Components.FilterTabsView
import org.telegram.ui.Components.FragmentSearchField
import org.telegram.ui.Components.MentionsContainerView
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.SizeNotifierFrameLayout
import org.telegram.ui.DialogsActivity
import org.telegram.ui.SearchTabsAndFiltersLayout

object NonIslandHelper {
    @JvmStatic
    fun tabBars(): Boolean = InuConfig.NON_ISLAND_TAB_BARS.value

    @JvmStatic
    fun globalSearch(): Boolean = InuConfig.NON_ISLAND_GLOBAL_SEARCH.value

    @JvmStatic
    fun chatElements(): Boolean = InuConfig.NON_ISLAND_CHAT_ELEMENTS.value

    // ChatAttachAlert.java
    const val ATTACH_TAB_SHADOW_DP = 3f

    @JvmStatic
    fun applyChatAttachTabBar(
        wrapper: FrameLayout,
        recyclerView: RecyclerListView,
    ) {
        if (!tabBars()) return
        wrapper.background = null
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
    @JvmStatic
    fun applyFilterTabBar(tabsView: FilterTabsView, contentView: SizeNotifierFrameLayout) {
        if (!tabBars()) return
        tabsView.setBlurredBackground(null)
        tabsView.background = null
        tabsView.inu_blurHelper = BlurBehindHelper(tabsView, contentView, Theme.key_windowBackgroundWhite)
        tabsView.setPadding(0, 0, 0, 0)
        val lp = tabsView.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.height = dp(36f)
        lp.leftMargin = 0
        lp.rightMargin = 0
    }

    @JvmStatic
    fun applyGlobalSearchBar(field: FragmentSearchField, contentView: SizeNotifierFrameLayout) {
        if (!globalSearch()) return
        // extra padding to cover with blur the area above the search bar
        val extraTopPadding = AndroidUtilities.statusBarHeight + dp(8f)
        field.translationY =
            (-dp(DialogsActivity.SEARCH_FIELD_HEIGHT.toFloat() + 4) - extraTopPadding).toFloat()
        field.setupBlurredBackground(null)
        field.inu_blurHelper = BlurBehindHelper(field, contentView, Theme.key_windowBackgroundWhite)
        field.setPadding(0, extraTopPadding, 0, 0)
        val lp = field.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.height += extraTopPadding
        lp.leftMargin = 0
        lp.rightMargin = 0
    }

    @JvmStatic
    fun applyGlobalSearchTabs(layout: SearchTabsAndFiltersLayout, contentView: SizeNotifierFrameLayout) {
        if (!globalSearch()) return
        layout.setBlurredBackground(null)
        layout.background = null
        layout.inu_blurHelper = BlurBehindHelper(layout, contentView, Theme.key_windowBackgroundWhite)
        layout.setPadding(0, 0, 0, 0)
        layout.translationY = -dp(4f).toFloat()
        val lp = layout.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.height = dp(36f)
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
            // stock logic
            parentLayout.drawHeaderShadow(canvas, actionBarBottom);
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
