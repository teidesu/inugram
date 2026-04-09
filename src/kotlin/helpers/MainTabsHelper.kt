package desu.inugram.helpers

import android.widget.FrameLayout
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.Bulletin
import org.telegram.ui.DialogsActivity


object MainTabsHelper {
    const val MAIN_TABS_MARGIN_COMPACT: Int = 4
    const val MAIN_TABS_HEIGHT_COMPACT: Int = 48
    const val TAB_WIDTH: Int = 80
    const val TAB_WIDTH_COMPACT: Int = 64
    const val TAB_PADDING: Int = 4

    @JvmStatic
    val isCompact: Boolean
        get() = InuConfig.BOTTOM_TABS_COMPACT_MODE.value

    @JvmStatic
    val isHidden: Boolean
        get() = InuConfig.BOTTOM_TABS_HIDE.value

    @JvmStatic
    val isContactsTabHidden: Boolean
        get() = InuConfig.BOTTOM_TABS_HIDE_CONTACTS.value

    @JvmStatic
    val mainTabsHeight: Int
        get() = if (isCompact) MAIN_TABS_HEIGHT_COMPACT else DialogsActivity.MAIN_TABS_HEIGHT

    @JvmStatic
    val mainTabsMargin: Int
        get() = if (isCompact) MAIN_TABS_MARGIN_COMPACT else DialogsActivity.MAIN_TABS_MARGIN

    @JvmStatic
    val mainTabsHeightWithMargins: Int
        get() = mainTabsHeight + mainTabsMargin * 2

    @JvmStatic
    val fragmentsCount: Int
        get() = if (isContactsTabHidden) 3 else 4

    @JvmStatic
    fun positionOffset(stockPosition: Int): Int =
        if (isContactsTabHidden && stockPosition > 1) stockPosition - 1 else stockPosition

    @JvmStatic
    val tabWidth: Int
        get() = if (isCompact) TAB_WIDTH_COMPACT else TAB_WIDTH

    @JvmStatic
    val tabsViewWidth: Int
        get() = tabWidth * fragmentsCount + (mainTabsMargin + TAB_PADDING) * 2

    @JvmStatic
    fun resolveBulletinContainer(fragment: BaseFragment?): FrameLayout? {
        if (fragment is DialogsActivity && fragment.hasMainTabs) {
            return Bulletin.BulletinWindow.make(
                fragment.getParentActivity(),
                object : Bulletin.Delegate {
                    override fun getBottomOffset(tag: Int): Int {
                        return if (isHidden) 0 else dp(mainTabsHeightWithMargins.toFloat())
                    }
                })
        }
        return null
    }
}