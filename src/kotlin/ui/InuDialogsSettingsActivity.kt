package desu.inugram.ui

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.DialogsFabHelper
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class InuDialogsSettingsActivity : InuSettingsPageActivity() {

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
            UItem.asCheck(TOGGLE_BOT_WEBVIEW_BUTTON, LocaleController.getString(R.string.InuHideBotWebView))
                .setChecked(InuConfig.HIDE_BOT_WEBVIEW_INPUT.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_CHAT_PREVIEW_EXPAND,
                R.string.InuDisableChatPreviewExpand,
                R.string.InuDisableChatPreviewExpandInfo,
                InuConfig.DISABLE_CHAT_PREVIEW_EXPAND.value
            )
        )
        items.add(UItem.asShadow(null))
        // end chat list section

        // bottom tabs section
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
            BUTTON_FOLDERS_DISPLAY_MODE -> showDialog(
                RadioDialogBuilder(context, getResourceProvider())
                    .setTitle(LocaleController.getString(R.string.InuFoldersDisplayMode))
                    .setItems(
                        arrayOf(
                            LocaleController.getString(R.string.InuFoldersDisplayModeTitles),
                            LocaleController.getString(R.string.InuFoldersDisplayModeTitlesAndIcons),
                            LocaleController.getString(R.string.InuFoldersDisplayModeIconsOnly),
                        ),
                        InuConfig.FOLDERS_DISPLAY_MODE.value - 1,
                    ) { _, which ->
                        val mode = which + 1
                        if (mode == InuConfig.FOLDERS_DISPLAY_MODE.value) return@setItems
                        InuConfig.FOLDERS_DISPLAY_MODE.value = mode
                        listView.adapter.update(true)
                        showRestartBulletin()
                    }
                    .create()
            )

            BUTTON_FOLDERS_UNREAD_COUNTER_MODE -> showDialog(
                RadioDialogBuilder(context, getResourceProvider())
                    .setTitle(LocaleController.getString(R.string.InuFoldersUnreadCounter))
                    .setItems(
                        arrayOf(
                            LocaleController.getString(R.string.InuFoldersUnreadCounterHide),
                            LocaleController.getString(R.string.InuFoldersUnreadCounterRegular),
                            LocaleController.getString(R.string.InuFoldersUnreadCounterExcludeMuted),
                            LocaleController.getString(R.string.InuFoldersUnreadCounterExcludeMutedNonDms),
                        ),
                        InuConfig.FOLDERS_UNREAD_COUNTER_MODE.value,
                    ) { _, which ->
                        if (which == InuConfig.FOLDERS_UNREAD_COUNTER_MODE.value) return@setItems
                        InuConfig.FOLDERS_UNREAD_COUNTER_MODE.value = which
                        listView.adapter.update(true)
                        showRestartBulletin()
                    }
                    .create()
            )

            TOGGLE_BOT_WEBVIEW_BUTTON -> {
                val new = InuConfig.HIDE_BOT_WEBVIEW_DIALOGS.toggle()
                (view as? TextCheckCell)?.isChecked = new
                showRestartBulletin()
            }

            TOGGLE_OLD_MENTION_INDICATOR -> {
                val new = InuConfig.OLD_MENTION_INDICATOR.toggle()
                (view as? TextCheckCell)?.isChecked = new
                showRestartBulletin()
            }

            TOGGLE_OPEN_ARCHIVE_ON_PULL -> {
                val new = InuConfig.OPEN_ARCHIVE_ON_PULL.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_CHAT_PREVIEW_EXPAND -> {
                val new = InuConfig.DISABLE_CHAT_PREVIEW_EXPAND.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_BOTTOM_TABS_HIDE -> {
                val new = InuConfig.BOTTOM_TABS_HIDE.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                listView.adapter.update(true)
                showRestartBulletin()
            }

            TOGGLE_HIDE_CONTACTS_TAB -> {
                val new = InuConfig.BOTTOM_TABS_HIDE_CONTACTS.toggle()
                (view as? TextCheckCell)?.isChecked = new
                showRestartBulletin()
            }

            TOGGLE_COMPACT_MODE -> {
                val new = InuConfig.BOTTOM_TABS_COMPACT_MODE.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                showRestartBulletin()
            }

            BUTTON_FAB_MAIN_ACTION -> showFabActionDialog(
                R.string.InuDialogsFabMainAction,
                InuConfig.DIALOGS_FAB_MAIN_ACTION,
            )

            BUTTON_FAB_SECONDARY_ACTION -> showFabActionDialog(
                R.string.InuDialogsFabSecondaryAction,
                InuConfig.DIALOGS_FAB_SECONDARY_ACTION,
            )

            TOGGLE_FAB_HIDE_ON_SCROLL -> {
                val new = InuConfig.DIALOGS_FAB_HIDE_ON_SCROLL.toggle()
                (view as? TextCheckCell)?.isChecked = new
                showRestartBulletin()
            }

            TOGGLE_FAB_OFFSET_FOR_BOTTOM_BAR -> {
                val new = InuConfig.DIALOGS_FAB_OFFSET_FOR_BOTTOM_BAR.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                showRestartBulletin()
            }

            TOGGLE_FAB_LEFT_SIDE -> {
                val new = InuConfig.DIALOGS_FAB_LEFT_SIDE.toggle()
                (view as? TextCheckCell)?.isChecked = new
                showRestartBulletin()
            }
        }
    }

    private fun showFabActionDialog(
        titleRes: Int,
        item: InuConfig.IntItem,
    ) {
        val options = DialogsFabHelper.Action.entries
        val labels = options.map { it.label() }.toTypedArray()
        val current = options.indexOfFirst { it.value == item.value }.coerceAtLeast(0)
        showDialog(
            RadioDialogBuilder(context, getResourceProvider())
                .setTitle(LocaleController.getString(titleRes))
                .setItems(labels, current) { _, which ->
                    val newValue = options[which].value
                    if (newValue == item.value) return@setItems
                    item.value = newValue
                    listView.adapter.update(true)
                    showRestartBulletin()
                }
                .create()
        )
    }

    companion object {
        private val BUTTON_FOLDERS_DISPLAY_MODE = InuUtils.generateId()
        private val BUTTON_FOLDERS_UNREAD_COUNTER_MODE = InuUtils.generateId()
        private val TOGGLE_BOT_WEBVIEW_BUTTON = InuUtils.generateId()
        private val TOGGLE_OLD_MENTION_INDICATOR = InuUtils.generateId()
        private val TOGGLE_OPEN_ARCHIVE_ON_PULL = InuUtils.generateId()
        private val TOGGLE_BOTTOM_TABS_HIDE = InuUtils.generateId()
        private val TOGGLE_HIDE_CONTACTS_TAB = InuUtils.generateId()
        private val TOGGLE_COMPACT_MODE = InuUtils.generateId()
        private val BUTTON_FAB_MAIN_ACTION = InuUtils.generateId()
        private val BUTTON_FAB_SECONDARY_ACTION = InuUtils.generateId()
        private val TOGGLE_FAB_HIDE_ON_SCROLL = InuUtils.generateId()
        private val TOGGLE_FAB_OFFSET_FOR_BOTTOM_BAR = InuUtils.generateId()
        private val TOGGLE_FAB_LEFT_SIDE = InuUtils.generateId()
        private val TOGGLE_DISABLE_CHAT_PREVIEW_EXPAND = InuUtils.generateId()
    }
}
