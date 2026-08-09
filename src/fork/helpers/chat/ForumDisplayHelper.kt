package desu.inugram.helpers.chat

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.edit
import desu.inugram.InuConfig
import org.telegram.messenger.ChatObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.ActionBarPopupWindow
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.Forum.ForumUtilities
import org.telegram.ui.DialogsActivity
import org.telegram.ui.TopicsFragment

object ForumDisplayHelper {
    const val MODE_DEFAULT = 0
    const val MODE_TABS = 1
    const val MODE_LIST = 2

    private fun overrideKey(chatId: Long) = "forum_display:$chatId"

    @JvmStatic
    fun getOverride(chatId: Long): Int =
        InuConfig.prefs.getInt(overrideKey(chatId), MODE_DEFAULT)

    fun addProfileMenuItem(
        fragment: BaseFragment,
        otherItem: ActionBarMenuItem,
        currentAccount: Int,
        chatId: Long,
        resourcesProvider: Theme.ResourcesProvider?,
    ) {
        val context = otherItem.context
        val windowLayout = ActionBarPopupWindow.ActionBarPopupWindowLayout(context, 0, resourcesProvider)
        windowLayout.setFitItems(true)

        val swipeBack = otherItem.popupLayout.swipeBack
        if (swipeBack != null) {
            ActionBarMenuItem.addItem(
                windowLayout, R.drawable.msg_arrow_back,
                LocaleController.getString(R.string.Back), false, resourcesProvider,
            ).setOnClickListener { swipeBack.closeForeground() }
        }

        val labels = intArrayOf(R.string.Default, R.string.TopicsLayoutTabs, R.string.TopicsLayoutList)
        for (mode in MODE_DEFAULT..MODE_LIST) {
            val cell = ActionBarMenuItem.addItem(
                windowLayout, 0,
                LocaleController.getString(labels[mode]), true, resourcesProvider,
            )
            cell.setChecked(getOverride(chatId) == mode)
            cell.setOnClickListener {
                otherItem.toggleSubMenu()
                if (getOverride(chatId) != mode) {
                    applyOverride(fragment, currentAccount, chatId, mode)
                }
            }
        }

        val cell = otherItem.addSwipeBackItem(
            R.drawable.msg_topics, null,
            LocaleController.getString(R.string.TopicsLayout), windowLayout,
        )
        (cell.parent as? ViewGroup)?.removeView(cell)
        otherItem.popupLayout.inu_addViewToTop(cell, cell.layoutParams as LinearLayout.LayoutParams)
    }

    private fun applyOverride(fragment: BaseFragment, currentAccount: Int, chatId: Long, mode: Int) {
        InuConfig.prefs.edit {
            if (mode == MODE_DEFAULT) remove(overrideKey(chatId)) else putInt(overrideKey(chatId), mode)
        }
        val storage = MessagesStorage.getInstance(currentAccount)
        storage.storageQueue.postRunnable { storage.isForumCacheInvalidate(-chatId) }

        val chat = MessagesController.getInstance(currentAccount).getChat(chatId) ?: return
        rebuildStack(fragment, currentAccount, chatId, ChatObject.areTabsEnabled(chat))
    }

    private fun rebuildStack(fragment: BaseFragment, currentAccount: Int, chatId: Long, tabs: Boolean) {
        val layout = fragment.parentLayout ?: return
        val fragments = layout.fragmentStack
        val hasChatActivity = fragments.any { it is ChatActivity && it.arguments?.getLong("chat_id") == chatId }
        var i = 0
        while (i < fragments.size) {
            val f = fragments[i]
            if (f is ChatActivity && f.arguments?.getLong("chat_id") == chatId) {
                layout.removeFragmentFromStack(f)
                f.clearViews()
                layout.addFragmentToStack(f, i)
                if (!tabs) {
                    layout.addFragmentToStack(TopicsFragment(Bundle().apply { putLong("chat_id", chatId) }), i)
                    i++
                }
            } else if (tabs && f is TopicsFragment && f.currentChat?.id == chatId) {
                layout.removeFragmentFromStack(f)
                if (!hasChatActivity) {
                    val chatActivity = ChatActivity(Bundle().apply { putLong("chat_id", chatId) })
                    ForumUtilities.applyTopic(
                        chatActivity,
                        MessagesStorage.TopicKey.of(
                            -chatId,
                            MessagesController.getInstance(currentAccount).getForumLastTopicId(chatId),
                        ),
                    )
                    layout.addFragmentToStack(chatActivity, i)
                } else {
                    i--
                }
            } else if (tabs && f is DialogsActivity) {
                f.rightSlidingDialogContainer?.takeIf { it.hasFragment() }?.finishPreview()
            }
            i++
        }
    }
}
