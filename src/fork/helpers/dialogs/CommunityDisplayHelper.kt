package desu.inugram.helpers.dialogs

import androidx.recyclerview.widget.RecyclerView
import desu.inugram.InuConfig
import desu.inugram.InuConfig.CommunityDisplayModeItem
import org.telegram.messenger.MessagesController
import org.telegram.ui.Cells.DialogCell
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.DialogsActivity
import org.telegram.ui.community.CommunitySheet

object CommunityDisplayHelper {
    @JvmStatic
    fun isRegular(): Boolean =
        InuConfig.COMMUNITY_DISPLAY_MODE.value == CommunityDisplayModeItem.REGULAR

    @JvmStatic
    fun isInvisible(): Boolean =
        InuConfig.COMMUNITY_DISPLAY_MODE.value == CommunityDisplayModeItem.INVISIBLE

    @JvmStatic
    fun handleAvatarClick(cell: DialogCell, currentAccount: Int, dialogId: Long): Boolean {
        if (InuConfig.COMMUNITY_DISPLAY_MODE.value != CommunityDisplayModeItem.LONG_TAP) return false
        if (cell.insideCommunityList || getLinkedCommunityId(currentAccount, dialogId) == 0L) return false
        if (wouldOpenStory(currentAccount, dialogId)) return false
        val list = cell.parent as? RecyclerListView ?: return false
        val position = list.getChildAdapterPosition(cell)
        if (position == RecyclerView.NO_POSITION) return false
        list.clickItem(cell, position)
        return true
    }

    @JvmStatic
    fun handleAvatarLongPress(cell: DialogCell, parentFragment: DialogsActivity?, currentAccount: Int): Boolean {
        if (InuConfig.COMMUNITY_DISPLAY_MODE.value != CommunityDisplayModeItem.LONG_TAP) return false
        if (parentFragment == null || cell.insideCommunityList) return false
        val communityId = getLinkedCommunityId(currentAccount, cell.dialogId)
        if (communityId == 0L) return false
        parentFragment.showDialog(CommunitySheet(parentFragment, communityId))
        return true
    }

    private fun getLinkedCommunityId(currentAccount: Int, dialogId: Long): Long {
        val controller = MessagesController.getInstance(currentAccount)
        return if (dialogId > 0) {
            controller.getUser(dialogId)?.linked_community_id ?: 0L
        } else {
            controller.getChat(-dialogId)?.linked_community_id ?: 0L
        }
    }

    private fun wouldOpenStory(currentAccount: Int, dialogId: Long): Boolean {
        val controller = MessagesController.getInstance(currentAccount)
        if (controller.storiesController.hasStories(dialogId)) return true
        return if (dialogId > 0) {
            val user = controller.getUser(dialogId)
            user != null && !user.stories_unavailable && user.stories_max_id != null && user.stories_max_id.max_id > 0
        } else {
            val chat = controller.getChat(-dialogId)
            chat != null && !chat.stories_unavailable && chat.stories_max_id != null && chat.stories_max_id.max_id > 0
        }
    }
}
