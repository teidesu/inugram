package desu.inugram.helpers.theme

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
import desu.inugram.ui.BlurBehindHelper

object NonIslandHelper {
    @JvmStatic
    fun foldersBar(): Boolean = InuConfig.NON_ISLAND_FOLDERS_BAR.value

    @JvmStatic
    fun sharedMediaTabs(): Boolean = InuConfig.NON_ISLAND_SHARED_MEDIA_TABS.value

    @JvmStatic
    fun globalSearch(): Boolean = InuConfig.NON_ISLAND_GLOBAL_SEARCH.value

    @JvmStatic
    fun chatElements(): Boolean = InuConfig.NON_ISLAND_CHAT_ELEMENTS.value

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

    // ChatAttachAlert.java
    const val ATTACH_TAB_SHADOW_DP = 3f

    @JvmStatic
    fun applyChatAttachTabBar(
        wrapper: FrameLayout,
        recyclerView: RecyclerListView,
    ) {
        if (!chatElements()) return
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
        if (!foldersBar()) return
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
        field.setupBlurredBackground(null)
        field.inu_blurHelper = BlurBehindHelper(field, contentView, Theme.key_windowBackgroundWhite)
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
