package desu.inugram.helpers

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.Bulletin
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.DialogsActivity
import org.telegram.ui.LaunchActivity
import org.telegram.ui.MainTabsActivity


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

    private const val MENU_ICON_SIZE_DP = 28

    @JvmStatic
    fun openChatsLongPressMenu(fragment: MainTabsActivity, button: View): ItemOptions? {
        val filters = MessagesController.getInstance(fragment.currentAccount).dialogFilters
        if (filters.size <= 1) return null

        val context = fragment.context ?: return null
        val o = ItemOptions.makeOptions(fragment, button)
        for (i in filters.indices) {
            val filter = filters[i]
            val name: String
            val emoticon: String?
            if (filter.isDefault) {
                name = getString(R.string.FilterAllChats)
                emoticon = "💬"
            } else {
                val defaults = FolderHelper.getDefaultsFromFlags(filter.flags)
                name = filter.name?.takeIf { it.isNotEmpty() } ?: defaults.first
                emoticon = filter.inu_emoticon?.takeIf { it.isNotEmpty() } ?: defaults.second
            }
            val index = i
            val icon = scaledIcon(context, FolderHelper.getTabIcon(emoticon)) ?: continue
            o.add(icon, name) {
                fragment.inu_openChatsAtFilter(index)
            }
        }

        o.addGap()
        o.add(R.drawable.msg_archive, getString(R.string.ArchivedChats)) {
            val args = Bundle()
            args.putInt("folderId", 1)
            fragment.presentFragment(DialogsActivity(args))
        }

        o.setBlur(true)
        o.translate(0f, -dp(4f).toFloat())
        val bg = Theme.createRoundRectDrawable(dp(28f), fragment.getThemedColor(Theme.key_windowBackgroundWhite))
        bg.paint.setShadowLayer(dp(6f).toFloat(), 0f, dp(1f).toFloat(), Theme.multAlpha(0xFF000000.toInt(), 0.15f))
        o.setScrimViewBackground(bg)

        return o
    }

    private fun scaledIcon(context: android.content.Context, resId: Int): Drawable? {
        val src = ContextCompat.getDrawable(context, resId) ?: return null
        return ScaledIconDrawable(src, dp(MENU_ICON_SIZE_DP.toFloat()))
    }

    private var lastProfileTapMs = 0L

    @JvmStatic
    fun onProfileTabTap(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        val isDoubleTap = now - lastProfileTapMs < 500
        lastProfileTapMs = now
        return isDoubleTap && switchToNextAccount()
    }

    @JvmStatic
    fun switchToNextAccount(): Boolean {
        val current = UserConfig.selectedAccount
        val accounts = mutableListOf<Int>()
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (UserConfig.getInstance(a).isClientActivated) accounts.add(a)
        }
        if (accounts.size < 2) return false
        AccountOrderHelper.sort(accounts)
        val idx = accounts.indexOf(current)
        val target = accounts[(idx + 1) % accounts.size]
        if (target == current) return false
        LaunchActivity.instance?.switchToAccount(target, true) ?: return false
        return true
    }

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