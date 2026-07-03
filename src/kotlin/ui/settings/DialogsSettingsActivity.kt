package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.dialogs.DialogsFabHelper
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class DialogsSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuMainPage)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        // folders section
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuFolders)))
        items.add(
            UItem.asButton(
                BUTTON_FOLDERS_DISPLAY_MODE,
                LocaleController.getString(R.string.InuFoldersDisplayMode),
                when (InuConfig.FOLDERS_DISPLAY_MODE.value) {
                    InuConfig.FoldersDisplayModeItem.TITLES_AND_ICONS -> LocaleController.getString(R.string.InuFoldersDisplayModeTitlesAndIcons)
                    InuConfig.FoldersDisplayModeItem.ICONS_ONLY -> LocaleController.getString(R.string.InuFoldersDisplayModeIconsOnly)
                    else -> LocaleController.getString(R.string.InuFoldersDisplayModeTitles)
                }
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_FOLDERS_UNREAD_COUNTER_MODE,
                LocaleController.getString(R.string.InuFoldersUnreadCounter),
                when (InuConfig.FOLDERS_UNREAD_COUNTER_MODE.value) {
                    InuConfig.FoldersUnreadCounterModeItem.HIDE -> LocaleController.getString(R.string.InuFoldersUnreadCounterHide)
                    InuConfig.FoldersUnreadCounterModeItem.EXCLUDE_MUTED -> LocaleController.getString(R.string.InuFoldersUnreadCounterExcludeMuted)
                    InuConfig.FoldersUnreadCounterModeItem.EXCLUDE_MUTED_NON_DMS -> LocaleController.getString(R.string.InuFoldersUnreadCounterExcludeMutedNonDms)
                    else -> LocaleController.getString(R.string.InuFoldersUnreadCounterRegular)
                }
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_ALL_CHATS_TAB,
                LocaleController.getString(R.string.InuHideAllChatsTab),
            ).setChecked(InuConfig.HIDE_ALL_CHATS_TAB.value)
        )
        items.add(UItem.asShadow(null))
        // end folders section

        // chat list section
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuChatList)))
        items.add(
            UItem.asCheck(
                TOGGLE_OLD_MENTION_INDICATOR,
                LocaleController.getString(R.string.InuOldMentionIndicator),
            ).setChecked(InuConfig.OLD_MENTION_INDICATOR.value)
        )
        items.add(
            UItem.asCheck(TOGGLE_OPEN_ARCHIVE_ON_PULL, LocaleController.getString(R.string.InuOpenArchiveOnPull))
                .setChecked(InuConfig.OPEN_ARCHIVE_ON_PULL.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_SWIPE_TO_UNARCHIVE,
                LocaleController.getString(R.string.InuDisableSwipeToUnarchive),
            ).setChecked(InuConfig.DISABLE_SWIPE_TO_UNARCHIVE.value)
        )
        items.add(
            UItem.asCheck(TOGGLE_BOT_WEBVIEW_BUTTON, LocaleController.getString(R.string.InuHideBotWebView))
                .setChecked(InuConfig.HIDE_BOT_WEBVIEW_DIALOGS.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_INTERACTIVE_CHAT_PREVIEW,
                R.string.InuDisableChatPreviewExpand,
                R.string.InuDisableChatPreviewExpandInfo,
                InuConfig.INTERACTIVE_CHAT_PREVIEW.value
            )
        )
        items.add(UItem.asShadow(null))
        // end chat list section

        // bottom tabs section
        if (!InuConfig.NAVIGATION_DRAWER.value) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.InuBottomTabs)))
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_BOTTOM_TABS_HIDE,
                    R.string.InuBottomTabsHide,
                    R.string.InuBottomTabsHideInfo,
                    InuConfig.BOTTOM_TABS_HIDE.value
                )
            )
            if (!InuConfig.BOTTOM_TABS_HIDE.value) {
                items.add(
                    UItem.asCheck(
                        TOGGLE_HIDE_CONTACTS_TAB,
                        LocaleController.getString(R.string.InuHideContactsTab),
                    ).setChecked(InuConfig.BOTTOM_TABS_HIDE_CONTACTS.value)
                )
                items.add(
                    mkTwoLineCheckItem(
                        TOGGLE_COMPACT_MODE,
                        R.string.InuCompactMode,
                        R.string.InuCompactModeInfo,
                        InuConfig.BOTTOM_TABS_COMPACT_MODE.value
                    )
                )
            }
            items.add(UItem.asShadow(null))
        }
        // end bottom tabs section

        // fab section
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuDialogsFab)))
        val mainAction = DialogsFabHelper.Action.fromValue(InuConfig.DIALOGS_FAB_MAIN_ACTION.value)
        items.add(
            UItem.asButton(
                BUTTON_FAB_MAIN_ACTION,
                LocaleController.getString(R.string.InuDialogsFabMainAction),
                mainAction.label(),
            )
        )
        if (mainAction != DialogsFabHelper.Action.NONE) {
            items.add(
                UItem.asButton(
                    BUTTON_FAB_SECONDARY_ACTION,
                    LocaleController.getString(R.string.InuDialogsFabSecondaryAction),
                    DialogsFabHelper.Action.fromValue(InuConfig.DIALOGS_FAB_SECONDARY_ACTION.value).label(),
                )
            )
        }
        items.add(
            UItem.asCheck(
                TOGGLE_FAB_HIDE_ON_SCROLL,
                LocaleController.getString(R.string.InuDialogsFabHideOnScroll),
            ).setChecked(InuConfig.DIALOGS_FAB_HIDE_ON_SCROLL.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_FAB_OFFSET_FOR_BOTTOM_BAR,
                R.string.InuDialogsFabOffsetForBottomBar,
                R.string.InuDialogsFabOffsetForBottomBarInfo,
                InuConfig.DIALOGS_FAB_OFFSET_FOR_BOTTOM_BAR.value
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_FAB_LEFT_SIDE,
                LocaleController.getString(R.string.InuDialogsFabLeftSide),
            ).setChecked(InuConfig.DIALOGS_FAB_LEFT_SIDE.value)
        )
        items.add(UItem.asShadow(null))
        // end fab section
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_FOLDERS_DISPLAY_MODE -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuFoldersDisplayModeTitles),
                    LocaleController.getString(R.string.InuFoldersDisplayModeTitlesAndIcons),
                    LocaleController.getString(R.string.InuFoldersDisplayModeIconsOnly),
                ),
                InuConfig.FOLDERS_DISPLAY_MODE.value - 1,
            ) { which ->
                InuConfig.FOLDERS_DISPLAY_MODE.value = which + 1
                softRebuild()
            }

            BUTTON_FOLDERS_UNREAD_COUNTER_MODE -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuFoldersUnreadCounterHide),
                    LocaleController.getString(R.string.InuFoldersUnreadCounterRegular),
                    LocaleController.getString(R.string.InuFoldersUnreadCounterExcludeMuted),
                    LocaleController.getString(R.string.InuFoldersUnreadCounterExcludeMutedNonDms),
                ),
                InuConfig.FOLDERS_UNREAD_COUNTER_MODE.value,
            ) { which ->
                InuConfig.FOLDERS_UNREAD_COUNTER_MODE.value = which
                // resetAllUnreadCounters dispatches updateInterfaces; DialogsActivity/MainTabsActivity listen.
                for (i in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
                    if (!UserConfig.getInstance(i).isClientActivated) continue
                    val storage = MessagesStorage.getInstance(i)
                    storage.storageQueue.postRunnable { storage.resetAllUnreadCounters(false) }
                }
            }

            TOGGLE_HIDE_ALL_CHATS_TAB -> {
                val new = InuConfig.HIDE_ALL_CHATS_TAB.toggle()
                (view as? TextCheckCell)?.isChecked = new
                postNotificationForAllAccounts(NotificationCenter.dialogFiltersUpdated)
            }

            TOGGLE_BOT_WEBVIEW_BUTTON -> {
                val new = InuConfig.HIDE_BOT_WEBVIEW_DIALOGS.toggle()
                (view as? TextCheckCell)?.isChecked = new
                softRebuild()
            }

            TOGGLE_OLD_MENTION_INDICATOR -> {
                val new = InuConfig.OLD_MENTION_INDICATOR.toggle()
                (view as? TextCheckCell)?.isChecked = new
                softRebuild()
            }

            TOGGLE_OPEN_ARCHIVE_ON_PULL -> {
                val new = InuConfig.OPEN_ARCHIVE_ON_PULL.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_SWIPE_TO_UNARCHIVE -> {
                val new = InuConfig.DISABLE_SWIPE_TO_UNARCHIVE.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_INTERACTIVE_CHAT_PREVIEW -> {
                val new = InuConfig.INTERACTIVE_CHAT_PREVIEW.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_BOTTOM_TABS_HIDE -> {
                val new = InuConfig.BOTTOM_TABS_HIDE.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                listView.adapter.update(true)
                softRebuild()
            }

            TOGGLE_HIDE_CONTACTS_TAB -> {
                val new = InuConfig.BOTTOM_TABS_HIDE_CONTACTS.toggle()
                (view as? TextCheckCell)?.isChecked = new
                showRestartBulletin()
            }

            TOGGLE_COMPACT_MODE -> {
                val new = InuConfig.BOTTOM_TABS_COMPACT_MODE.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                softRebuild()
            }

            BUTTON_FAB_MAIN_ACTION -> showFabActionDialog(view, InuConfig.DIALOGS_FAB_MAIN_ACTION)
            BUTTON_FAB_SECONDARY_ACTION -> showFabActionDialog(view, InuConfig.DIALOGS_FAB_SECONDARY_ACTION)

            TOGGLE_FAB_HIDE_ON_SCROLL -> {
                val new = InuConfig.DIALOGS_FAB_HIDE_ON_SCROLL.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_FAB_OFFSET_FOR_BOTTOM_BAR -> {
                val new = InuConfig.DIALOGS_FAB_OFFSET_FOR_BOTTOM_BAR.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                softRebuild()
            }

            TOGGLE_FAB_LEFT_SIDE -> {
                val new = InuConfig.DIALOGS_FAB_LEFT_SIDE.toggle()
                (view as? TextCheckCell)?.isChecked = new
                softRebuild()
            }
        }
    }

    private fun showFabActionDialog(anchor: View, item: InuConfig.IntItem) {
        val options = DialogsFabHelper.Action.entries
        val current = options.indexOfFirst { it.value == item.value }.coerceAtLeast(0)
        RadioItemOptions.show(
            this, anchor,
            options.map { it.label() },
            current,
        ) { which ->
            item.value = options[which].value
            softRebuild()
        }
    }

    companion object {
        private val BUTTON_FOLDERS_DISPLAY_MODE = InuUtils.generateId()
        private val BUTTON_FOLDERS_UNREAD_COUNTER_MODE = InuUtils.generateId()
        private val TOGGLE_BOT_WEBVIEW_BUTTON = InuUtils.generateId()
        private val TOGGLE_OLD_MENTION_INDICATOR = InuUtils.generateId()
        private val TOGGLE_OPEN_ARCHIVE_ON_PULL = InuUtils.generateId()
        private val TOGGLE_DISABLE_SWIPE_TO_UNARCHIVE = InuUtils.generateId()
        private val TOGGLE_BOTTOM_TABS_HIDE = InuUtils.generateId()
        private val TOGGLE_HIDE_CONTACTS_TAB = InuUtils.generateId()
        private val TOGGLE_COMPACT_MODE = InuUtils.generateId()
        private val BUTTON_FAB_MAIN_ACTION = InuUtils.generateId()
        private val BUTTON_FAB_SECONDARY_ACTION = InuUtils.generateId()
        private val TOGGLE_FAB_HIDE_ON_SCROLL = InuUtils.generateId()
        private val TOGGLE_FAB_OFFSET_FOR_BOTTOM_BAR = InuUtils.generateId()
        private val TOGGLE_FAB_LEFT_SIDE = InuUtils.generateId()
        private val TOGGLE_INTERACTIVE_CHAT_PREVIEW = InuUtils.generateId()
        private val TOGGLE_HIDE_ALL_CHATS_TAB = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "dialogs",
            titleRes = R.string.InuMainPage,
            iconRes = R.drawable.msg_viewchats,
            factory = ::DialogsSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("folders-display-mode", R.string.InuFoldersDisplayMode, BUTTON_FOLDERS_DISPLAY_MODE),
                SearchRegistry.Entry("folders-unread-counter", R.string.InuFoldersUnreadCounter, BUTTON_FOLDERS_UNREAD_COUNTER_MODE),
                SearchRegistry.Entry("hide-all-chats-tab", R.string.InuHideAllChatsTab, TOGGLE_HIDE_ALL_CHATS_TAB),
                SearchRegistry.Entry("old-mention-indicator", R.string.InuOldMentionIndicator, TOGGLE_OLD_MENTION_INDICATOR),
                SearchRegistry.Entry("open-archive-on-pull", R.string.InuOpenArchiveOnPull, TOGGLE_OPEN_ARCHIVE_ON_PULL),
                SearchRegistry.Entry("disable-swipe-to-unarchive", R.string.InuDisableSwipeToUnarchive, TOGGLE_DISABLE_SWIPE_TO_UNARCHIVE),
                SearchRegistry.Entry("hide-bot-webview-dialogs", R.string.InuHideBotWebView, TOGGLE_BOT_WEBVIEW_BUTTON),
                SearchRegistry.Entry("disable-chat-preview-expand", R.string.InuDisableChatPreviewExpand, TOGGLE_INTERACTIVE_CHAT_PREVIEW),
                SearchRegistry.Entry("bottom-tabs-hide", R.string.InuBottomTabsHide, TOGGLE_BOTTOM_TABS_HIDE),
                SearchRegistry.Entry("hide-contacts-tab", R.string.InuHideContactsTab, TOGGLE_HIDE_CONTACTS_TAB),
                SearchRegistry.Entry("compact-mode", R.string.InuCompactMode, TOGGLE_COMPACT_MODE),
                SearchRegistry.Entry("dialogs-fab-main-action", R.string.InuDialogsFabMainAction, BUTTON_FAB_MAIN_ACTION),
                SearchRegistry.Entry("dialogs-fab-secondary-action", R.string.InuDialogsFabSecondaryAction, BUTTON_FAB_SECONDARY_ACTION),
                SearchRegistry.Entry("dialogs-fab-hide-on-scroll", R.string.InuDialogsFabHideOnScroll, TOGGLE_FAB_HIDE_ON_SCROLL),
                SearchRegistry.Entry("dialogs-fab-offset-for-bottom-bar", R.string.InuDialogsFabOffsetForBottomBar, TOGGLE_FAB_OFFSET_FOR_BOTTOM_BAR),
                SearchRegistry.Entry("dialogs-fab-left-side", R.string.InuDialogsFabLeftSide, TOGGLE_FAB_LEFT_SIDE),
            ),
        )
    }
}
