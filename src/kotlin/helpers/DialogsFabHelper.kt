package desu.inugram.helpers

import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import desu.inugram.InuConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ChatActivity
import org.telegram.ui.DialogsActivity

object DialogsFabHelper {
    enum class Action(
        val value: Int,
        private val labelRes: Int,
        val iconRes: Int,
    ) {
        NONE(0, R.string.None, 0),
        NEW_MESSAGE(1, R.string.InuDialogsFabActionNewMessage, R.drawable.filled_fab_compose_32),
        NEW_STORY(2, R.string.InuDialogsFabActionNewStory, R.drawable.outline_fab_story_24),
        OPEN_SEARCH(3, R.string.InuDialogsFabActionOpenSearch, R.drawable.outline_header_search),
        SAVED_MESSAGES(4, R.string.InuDialogsFabActionSavedMessages, R.drawable.outline_saved_24),
        ;

        fun label(): CharSequence = LocaleController.getString(labelRes)

        companion object {
            @JvmStatic
            fun fromValue(value: Int): Action =
                entries.firstOrNull { it.value == value } ?: NONE
        }
    }

    @JvmStatic
    fun mainAction(): Action =
        Action.fromValue(InuConfig.DIALOGS_FAB_MAIN_ACTION.value)

    @JvmStatic
    fun secondaryAction(): Action {
        if (mainAction() == Action.NONE) return Action.NONE
        return Action.fromValue(InuConfig.DIALOGS_FAB_SECONDARY_ACTION.value)
    }

    @JvmStatic
    fun hideOnScroll(): Boolean = InuConfig.DIALOGS_FAB_HIDE_ON_SCROLL.value

    @JvmStatic
    fun offsetForBottomBar(): Boolean = InuConfig.DIALOGS_FAB_OFFSET_FOR_BOTTOM_BAR.value

    @JvmStatic
    fun leftSide(): Boolean = InuConfig.DIALOGS_FAB_LEFT_SIDE.value

    @JvmStatic
    fun applySide(params: FrameLayout.LayoutParams) {
        if (!leftSide()) return
        val horizontal = if (LocaleController.isRTL) Gravity.RIGHT else Gravity.LEFT
        params.gravity = (params.gravity and Gravity.HORIZONTAL_GRAVITY_MASK.inv()) or horizontal
    }

    @JvmStatic
    fun hasNewMessage(): Boolean = mainAction() == Action.NEW_MESSAGE || secondaryAction() == Action.NEW_MESSAGE

    @JvmStatic
    fun perform(activity: DialogsActivity, action: Action) {
        when (action) {
            Action.NONE -> {}
            Action.NEW_MESSAGE -> activity.openWriteContacts()
            Action.NEW_STORY -> activity.openStoriesRecorder()
            Action.OPEN_SEARCH -> activity.searchItem?.performClick()
            Action.SAVED_MESSAGES -> {
                val args = Bundle()
                args.putLong("user_id", UserConfig.getInstance(activity.currentAccount).clientUserId)
                activity.presentFragment(ChatActivity(args))
            }
        }
    }
}
