package desu.inugram.helpers.dialogs

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.TextPaint
import android.util.Pair
import androidx.core.content.edit
import androidx.core.graphics.withSave
import desu.inugram.InuConfig
import desu.inugram.helpers.security.ParanoiaHelper
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatObject
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.Bulletin
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.FilterTabsView
import org.telegram.ui.Components.FiltersListBottomSheet
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet
import org.telegram.ui.FilterCreateActivity
import org.telegram.ui.LaunchActivity
import org.telegram.ui.ProfileActivity
import org.telegram.ui.Stories.recorder.HintView2
import java.lang.ref.WeakReference
import kotlin.math.ceil

object FolderHelper {
    @JvmStatic
    fun canShowTabsAtBottom(): Boolean =
        !InuConfig.NON_ISLAND_FOLDERS_BAR.value || MainTabsHelper.isMaterial || MainTabsHelper.isHidden

    @JvmStatic
    fun shouldShowTabsAtBottom(): Boolean = InuConfig.FOLDER_TABS_AT_BOTTOM.value && canShowTabsAtBottom()

    private val folderIcons = mapOf(
        "\uD83D\uDC31" to R.drawable.filter_cat,
        "\uD83D\uDCD5" to R.drawable.filter_book,
        "\uD83D\uDCB0" to R.drawable.filter_money,
        "\uD83C\uDFAE" to R.drawable.filter_game,
        "\uD83D\uDCA1" to R.drawable.filter_light,
        "\uD83D\uDC4C" to R.drawable.filter_like,
        "\uD83C\uDFB5" to R.drawable.filter_note,
        "\uD83C\uDFA8" to R.drawable.filter_palette,
        "\u2708" to R.drawable.filter_travel,
        "\u26BD" to R.drawable.filter_sport,
        "\u2B50" to R.drawable.filter_favorite,
        "\uD83C\uDF93" to R.drawable.filter_study,
        "\uD83D\uDEEB" to R.drawable.filter_airplane,
        "\uD83D\uDC64" to R.drawable.filter_private,
        "\uD83D\uDC65" to R.drawable.filter_group,
        "\uD83D\uDCAC" to R.drawable.filter_all,
        "\u2705" to R.drawable.filter_unread,
        "\uD83E\uDD16" to R.drawable.filter_bots,
        "\uD83D\uDC51" to R.drawable.filter_crown,
        "\uD83C\uDF39" to R.drawable.filter_flower,
        "\uD83C\uDFE0" to R.drawable.filter_home,
        "\u2764" to R.drawable.filter_love,
        "\uD83C\uDFAD" to R.drawable.filter_mask,
        "\uD83C\uDF78" to R.drawable.filter_party,
        "\uD83D\uDCC8" to R.drawable.filter_trade,
        "\uD83D\uDCBC" to R.drawable.filter_work,
        "\uD83D\uDD14" to R.drawable.filter_unmuted,
        "\uD83D\uDCE2" to R.drawable.filter_channels,
        "\uD83D\uDCC1" to R.drawable.filter_custom,
        "\uD83D\uDCCB" to R.drawable.filter_setup,
    )

    const val ICON_GAP = 4

    @JvmStatic
    fun capturePublicJoinOrigin(account: Int, chatId: Long, fragment: BaseFragment?): BaseFragment? {
        if (!InuConfig.SUGGEST_FOLDER_AFTER_JOIN.value || ParanoiaHelper.shouldHideFolders()) return null
        val controller = MessagesController.getInstance(account)
        if (!ChatObject.isPublic(controller.getChat(chatId)) || controller.dialogFilters.none { !it.isDefault }) return null
        val origin = fragment ?: LaunchActivity.getLastFragment()?.takeIf {
            when (it) {
                is ChatActivity -> it.dialogId == -chatId
                is ProfileActivity -> it.dialogId == -chatId
                else -> false
            }
        }
        return origin?.takeIf { isJoinOriginAvailable(it, account) }
    }

    @JvmStatic
    fun onPublicChatJoined(account: Int, chatId: Long, origin: BaseFragment?) {
        if (origin == null || !InuConfig.SUGGEST_FOLDER_AFTER_JOIN.value) return
        val reference = WeakReference(origin)
        val deadline = SystemClock.uptimeMillis() + 5000
        object : Runnable {
            override fun run() {
                val fragment = reference.get() ?: return
                if (!isJoinOriginAvailable(fragment, account) || SystemClock.uptimeMillis() >= deadline) return
                if (fragment.parentLayout?.isTransitionAnimationInProgress == true) {
                    AndroidUtilities.runOnUIThread(this, 250)
                    return
                }
                if (!isJoinOriginActive(fragment, account)) return
                val controller = fragment.messagesController
                val chat = controller.getChat(chatId) ?: return
                if (!ChatObject.isPublic(chat)) return
                if (fragment.bulletinLayoutContainer == null) return
                if (ChatObject.isNotInChat(chat) ||
                    Bulletin.getVisibleBulletin() != null || fragment.visibleDialog?.isShowing == true
                ) {
                    AndroidUtilities.runOnUIThread(this, 250)
                    return
                }
                val accountInstance = AccountInstance.getInstance(account)
                val dialog = joinedDialog(fragment, chatId)
                if (controller.dialogFilters.none { !it.isDefault && !it.includesDialog(accountInstance, -chatId, dialog) }) return
                val text = if (ChatObject.isChannelAndNotMegaGroup(chat)) {
                    R.string.InuAddChannelToFolderSuggestion
                } else {
                    R.string.InuAddGroupToFolderSuggestion
                }
                BulletinFactory.of(fragment).createSimpleBulletin(
                    R.raw.folder_in, getString(text), getString(R.string.FilterChoose), 3000,
                ) {
                    reference.get()?.let { showJoinFolderPicker(it, account, chatId) }
                }.show()
            }
        }.run()
    }

    private fun isJoinOriginAvailable(fragment: BaseFragment, account: Int): Boolean {
        return InuConfig.SUGGEST_FOLDER_AFTER_JOIN.value && !ParanoiaHelper.shouldHideFolders() &&
            (fragment is ChatActivity || fragment is ProfileActivity) &&
            fragment.currentAccount == account && UserConfig.selectedAccount == account &&
            !fragment.isFinished
    }

    private fun isJoinOriginActive(fragment: BaseFragment, account: Int): Boolean {
        return isJoinOriginAvailable(fragment, account) && !fragment.isPaused && fragment.isLastFragment &&
            !fragment.isInPreviewMode && fragment.fragmentView?.isAttachedToWindow == true &&
            fragment.articleViewer == null
    }

    @JvmStatic
    fun canCreateFolder(fragment: BaseFragment): Boolean {
        val controller = fragment.messagesController
        val limit = if (fragment.userConfig.isPremium) controller.dialogFiltersLimitPremium else controller.dialogFiltersLimitDefault
        return controller.dialogFilters.count { !it.isDefault } < limit
    }

    private fun joinedDialog(fragment: BaseFragment, chatId: Long): TLRPC.Dialog {
        return fragment.messagesController.dialogs_dict[-chatId] ?: TLRPC.TL_dialog().apply {
            id = -chatId
            peer = fragment.messagesController.getPeer(id)
            flags = 1
        }
    }

    private fun showJoinFolderPicker(fragment: BaseFragment, account: Int, chatId: Long) {
        if (!isJoinOriginActive(fragment, account)) return
        val chat = fragment.messagesController.getChat(chatId) ?: return
        if (!ChatObject.isPublic(chat) || ChatObject.isNotInChat(chat)) return
        val selectedDialogs = arrayListOf(-chatId)
        val sheet = FiltersListBottomSheet.inu_forFragment(fragment, selectedDialogs, joinedDialog(fragment, chatId))
        sheet.setDelegate { selectedFilter, _ ->
            if (!isJoinOriginActive(fragment, account)) return@setDelegate
            if (selectedFilter == null) {
                if (canCreateFolder(fragment)) {
                    val editor = FilterCreateActivity(null, selectedDialogs)
                    editor.setCurrentAccount(account)
                    fragment.presentFragment(editor)
                } else {
                    showFolderLimit(fragment, LimitReachedBottomSheet.TYPE_FOLDERS)
                }
                return@setDelegate
            }
            val filter = fragment.messagesController.dialogFilters.find { it.id == selectedFilter.id } ?: return@setDelegate
            val checked = filter.includesDialog(AccountInstance.getInstance(account), -chatId, joinedDialog(fragment, chatId))
            val alwaysShow = ArrayList(filter.alwaysShow)
            val neverShow = ArrayList(filter.neverShow)
            val targetDialogs = if (checked) neverShow else alwaysShow
            val sourceDialogs = if (checked) alwaysShow else neverShow
            sourceDialogs.remove(-chatId)
            if (!targetDialogs.contains(-chatId)) targetDialogs.add(-chatId)
            val controller = fragment.messagesController
            val limit = if (fragment.userConfig.isPremium) controller.dialogFiltersChatsLimitPremium else controller.dialogFiltersChatsLimitDefault
            if (targetDialogs.size > limit) {
                showFolderLimit(fragment, LimitReachedBottomSheet.TYPE_CHATS_IN_FOLDER)
                return@setDelegate
            }
            if (checked) filter.pinnedDialogs.delete(-chatId)
            FilterCreateActivity.saveFilterToServer(
                filter, filter.flags, filter.name, filter.entities, filter.title_noanimate, filter.color,
                alwaysShow, neverShow, filter.pinnedDialogs, false, false, true, true, false, fragment, null,
            )
        }
        fragment.showDialog(sheet)
    }

    // shown on top instead of via showDialog, which would dismiss the open folder picker
    private fun showFolderLimit(fragment: BaseFragment, type: Int) {
        LimitReachedBottomSheet(fragment, fragment.parentActivity, type, fragment.currentAccount, fragment.resourceProvider).show()
    }

    private fun getIconSize(): Int {
        return if (isIconsOnly()) 28 else 24
    }

    @JvmStatic
    fun saveMeta(storage: MessagesStorage, filters: List<MessagesController.DialogFilter>) {
        val db = storage.database ?: return
        db.executeFast("DELETE FROM inu_folder_meta").stepThis().dispose()
        val state = db.executeFast("REPLACE INTO inu_folder_meta VALUES(?, ?)")
        for (filter in filters) {
            state.requery()
            state.bindInteger(1, filter.id)
            state.bindString(2, filter.inu_emoticon ?: "")
            state.step()
        }
        state.dispose()
    }

    @JvmStatic
    fun loadMeta(storage: MessagesStorage, account: Int, filters: List<MessagesController.DialogFilter>) {
        val db = storage.database ?: return
        val map = HashMap<Int, String>()
        val cursor = db.queryFinalized("SELECT filter_id, emoticon FROM inu_folder_meta")
        while (cursor.next()) {
            map[cursor.intValue(0)] = cursor.stringValue(1)
        }
        cursor.dispose()
        var hasMissing = false
        for (filter in filters) {
            val cached = map[filter.id]
            if (cached == null) hasMissing = true
            filter.inu_emoticon = if (cached.isNullOrEmpty()) null else cached
        }
        if (hasMissing) {
            val userConfig = UserConfig.getInstance(account)
            userConfig.filtersLoaded = false
            userConfig.preferences.edit { putBoolean("filtersLoaded", false) }
        }
    }

    @JvmStatic
    fun getDefaultsFromFlags(filterFlags: Int): Pair<String, String> {
        val allChats = MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS
        var flags = filterFlags and allChats

        if (flags and allChats == allChats) {
            if (filterFlags and MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ != 0) {
                return Pair.create(getString(R.string.FilterNameUnread), "\u2705")
            }
            if (filterFlags and MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_MUTED != 0) {
                return Pair.create(getString(R.string.FilterNameNonMuted), "\uD83D\uDD14")
            }
        } else if (flags and MessagesController.DIALOG_FILTER_FLAG_CONTACTS != 0) {
            flags = flags and MessagesController.DIALOG_FILTER_FLAG_CONTACTS.inv()
            if (flags == 0 || flags == MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS) {
                return Pair.create(getString(R.string.FilterContacts), "\uD83D\uDC64")
            }
        } else if (flags and MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS != 0) {
            flags = flags and MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS.inv()
            if (flags == 0) return Pair.create(getString(R.string.FilterNonContacts), "\uD83D\uDC64")
        } else if (flags and MessagesController.DIALOG_FILTER_FLAG_GROUPS != 0) {
            flags = flags and MessagesController.DIALOG_FILTER_FLAG_GROUPS.inv()
            if (flags == 0) return Pair.create(getString(R.string.FilterGroups), "\uD83D\uDC65")
        } else if (flags and MessagesController.DIALOG_FILTER_FLAG_BOTS != 0) {
            flags = flags and MessagesController.DIALOG_FILTER_FLAG_BOTS.inv()
            if (flags == 0) return Pair.create(getString(R.string.FilterBots), "\uD83E\uDD16")
        } else if (flags and MessagesController.DIALOG_FILTER_FLAG_CHANNELS != 0) {
            flags = flags and MessagesController.DIALOG_FILTER_FLAG_CHANNELS.inv()
            if (flags == 0) return Pair.create(getString(R.string.FilterChannels), "\uD83D\uDCE2")
        }

        return Pair.create("", "")
    }

    /** resolve (name, emoticon) for a non-default filter, with flag-based fallbacks */
    @JvmStatic
    fun getTabInfo(filter: MessagesController.DialogFilter): Pair<String, String> {
        val defaults = getDefaultsFromFlags(filter.flags)
        val name = filter.name?.takeIf { it.isNotEmpty() } ?: defaults.first
        val emoticon = filter.inu_emoticon?.takeIf { it.isNotEmpty() } ?: defaults.second
        return Pair.create(name, emoticon)
    }

    @JvmStatic
    fun getTabIcon(emoticon: String?): Int {
        if (emoticon != null) {
            val stripped = emoticon.replace("\uFE0F", "")
            return folderIcons[stripped] ?: R.drawable.filter_custom
        }
        return R.drawable.filter_custom
    }

    /** extra dp to add to titleWidth measurement when icon is shown alongside title */
    @JvmStatic
    fun getContentWidth(title: CharSequence?, textPaint: TextPaint): Int {
        val mode = InuConfig.FOLDERS_DISPLAY_MODE.value;
        if (mode == InuConfig.FoldersDisplayModeItem.ICONS_ONLY) return AndroidUtilities.dp(getIconSize().toFloat())

        var w = ceil(HintView2.measureCorrectly(title, textPaint)).toInt();
        if (mode == InuConfig.FoldersDisplayModeItem.TITLES_AND_ICONS) w += AndroidUtilities.dp((getIconSize() + ICON_GAP).toFloat());

        return w
    }

    @JvmStatic
    fun needIcons(): Boolean {
        return InuConfig.FOLDERS_DISPLAY_MODE.value != InuConfig.FoldersDisplayModeItem.TITLES
    }

    @JvmStatic
    fun isIconsOnly(): Boolean {
        return InuConfig.FOLDERS_DISPLAY_MODE.value == InuConfig.FoldersDisplayModeItem.ICONS_ONLY
    }

    @JvmStatic
    fun isTitleOnly(): Boolean {
        return InuConfig.FOLDERS_DISPLAY_MODE.value == InuConfig.FoldersDisplayModeItem.TITLES
    }

    /** text x offset to make room for icon in titles+icons mode */
    @JvmStatic
    fun getTextXOffset(): Float {
        if (InuConfig.FOLDERS_DISPLAY_MODE.value != InuConfig.FoldersDisplayModeItem.TITLES_AND_ICONS) return 0f
        return AndroidUtilities.dp((getIconSize() + ICON_GAP).toFloat()).toFloat()
    }

    /** draw the folder icon on the tab. call before drawing text. */
    @JvmStatic
    fun drawTabIcon(
        canvas: Canvas,
        icon: Drawable?,
        colorFilter: ColorFilter?,
        textX: Float,
        viewWidth: Int,
        viewHeight: Int
    ) {
        if (icon == null || !needIcons()) return

        val iconPx = AndroidUtilities.dp(getIconSize().toFloat())
        icon.colorFilter = colorFilter
        icon.setBounds(0, 0, iconPx, iconPx)
        canvas.withSave {
            translate(textX, (viewHeight - iconPx) / 2f)
            icon.draw(this)
        }
    }

    /** adjusted tab padding between tabs */
    @JvmStatic
    fun getTabPadding(): Float {
        if (isIconsOnly()) return 16f
        return FilterTabsView.TAB_PADDING_WIDTH
    }

    /** skip adding default "All Chats" tab when toggle is on AND user has other filters */
    @JvmStatic
    fun shouldSkipDefaultTab(totalFilters: Int): Boolean {
        return InuConfig.HIDE_ALL_CHATS_TAB.value && totalFilters > 1
    }

    /** if selectedType lands on the (now hidden) default filter, return first non-default index */
    @JvmStatic
    fun snapOffDefault(filters: List<MessagesController.DialogFilter>, selectedType: Int): Int {
        if (!shouldSkipDefaultTab(filters.size)) return selectedType
        if (selectedType !in filters.indices || !filters[selectedType].isDefault) return selectedType
        return filters.indexOfFirst { !it.isDefault }.takeIf { it >= 0 } ?: selectedType
    }

    /** after a rebuild that skipped the default tab, refresh selectedTabId + currentPosition */
    @JvmStatic
    fun refreshSelectedTab(filterTabsView: FilterTabsView, selectedType: Int, filtersSize: Int) {
        if (!InuConfig.HIDE_ALL_CHATS_TAB.value) return
        if (selectedType < 0 || selectedType >= filtersSize) return
        filterTabsView.selectTabWithId(selectedType, 1f)
    }

    @JvmStatic
    fun isMuteFilteringActive(): Boolean {
        val mode = InuConfig.FOLDERS_UNREAD_COUNTER_MODE.value
        return mode == InuConfig.FoldersUnreadCounterModeItem.EXCLUDE_MUTED ||
            mode == InuConfig.FoldersUnreadCounterModeItem.EXCLUDE_MUTED_NON_DMS
    }

    @JvmStatic
    @JvmOverloads
    fun shouldExcludeFromCounter(currentAccount: Int, dialogId: Long, user: TLRPC.User? = null): Boolean {
        if (ParanoiaHelper.isHidden(currentAccount, dialogId)) return true
        val mode = InuConfig.FOLDERS_UNREAD_COUNTER_MODE.value
        if (mode == InuConfig.FoldersUnreadCounterModeItem.EXCLUDE_MUTED_NON_DMS && dialogId > 0) {
            // human DM → never excluded; bot → excluded if muted; user info missing → defer to a later call
            if (user == null || !user.bot) return false
        }
        if (mode != InuConfig.FoldersUnreadCounterModeItem.EXCLUDE_MUTED &&
            mode != InuConfig.FoldersUnreadCounterModeItem.EXCLUDE_MUTED_NON_DMS
        ) return false
        return MessagesController.getInstance(currentAccount).isDialogMuted(dialogId, 0)
    }

    /** adjusted tab internal padding (indicator overshoot) */
    @JvmStatic
    fun getTabInternalPadding(): Float {
        if (isIconsOnly()) return FilterTabsView.TAB_INTERNAL_PADDING / 2f
        if (InuConfig.FOLDERS_DISPLAY_MODE.value == InuConfig.FoldersDisplayModeItem.TITLES_AND_ICONS) return 8f
        return FilterTabsView.TAB_INTERNAL_PADDING
    }
}
