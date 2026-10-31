package desu.inugram.helpers.chat

import androidx.core.content.edit
import desu.inugram.InuConfig
// #if PLUGINS
import desu.inugram.helpers.plugins.telegram.PluginMessageFilters
// #endif
import org.telegram.messenger.MessageObject
import java.util.concurrent.ConcurrentHashMap
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ChatActivity

object BlockedMessagesHelper {
    private val extraHiddenCache = HashMap<Int, Set<Long>>()
    private val revealed = ConcurrentHashMap.newKeySet<Pair<Int, Long>>()

    private fun getExtraHiddenKey(account: Int) = "blocked_messages_extra:$account"

    fun getExtraHidden(account: Int): Set<Long> {
        synchronized(extraHiddenCache) {
            extraHiddenCache[account]?.let { return it }
            val stored = InuConfig.prefs.getStringSet(getExtraHiddenKey(account), null)
            val set = stored?.mapNotNullTo(HashSet(), String::toLongOrNull) ?: emptySet()
            extraHiddenCache[account] = set
            return set
        }
    }

    fun setExtraHidden(account: Int, ids: Collection<Long>) {
        synchronized(extraHiddenCache) {
            extraHiddenCache[account] = HashSet(ids)
        }
        InuConfig.prefs.edit { putStringSet(getExtraHiddenKey(account), ids.map(Long::toString).toHashSet()) }
        notifyChanged(account)
    }

    @JvmStatic
    fun notifyChanged(account: Int) {
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.blockedUsersDidLoad)
    }

    fun isExtraHidden(account: Int, dialogId: Long): Boolean = getExtraHidden(account).contains(dialogId)

    fun toggleExtraHidden(account: Int, dialogId: Long): Boolean {
        val set = HashSet(getExtraHidden(account))
        val added = set.add(dialogId)
        if (!added) set.remove(dialogId)
        setExtraHidden(account, set)
        return added
    }

    @JvmStatic
    fun isEnabled(): Boolean {
        return InuConfig.BLOCKED_MESSAGES_MODE.value != InuConfig.BlockedMessagesModeItem.OFF
    }

    @JvmStatic
    fun isHideMode(): Boolean {
        return isBlockedHideMode() || isPluginFiltering()
    }

    fun hasFiltered(messages: List<MessageObject>): Boolean {
        if (!isEnabled() && !isPluginFiltering()) return false
        return messages.any {
            shouldSpoil(it)
                // #if PLUGINS
                || PluginMessageFilters.getRewrite(it) != null
                // #endif
        }
    }

    fun isRevealed(account: Int, dialogId: Long): Boolean = revealed.isNotEmpty() && Pair(account, dialogId) in revealed

    fun setRevealed(account: Int, dialogId: Long, reveal: Boolean) {
        val key = Pair(account, dialogId)
        if (if (reveal) revealed.add(key) else revealed.remove(key)) notifyChanged(account)
    }

    @JvmStatic
    fun onFilteringChanged(activity: ChatActivity, adapter: ChatActivity.ChatActivityAdapter?) {
        ChatActionsHelper.updateRevealItem(activity)
        if (adapter == null) return
        var changed = adapter.inu_filtering || isHideMode()
        for (msg in activity.messages) changed = rebuildIfStale(msg) || changed
        if (changed) adapter.notifyDataSetChanged(true)
    }

    fun rebuildIfStale(msg: MessageObject): Boolean {
        val spoiled = shouldSpoil(msg)
        // #if PLUGINS
        val rewrite = PluginMessageFilters.getRewrite(msg)
        val rewriteChanged = rewrite !== msg.inu_rewrite
        // #else
        val rewriteChanged = false
        // #endif
        if (spoiled == msg.inu_spoiled && !rewriteChanged) return false
        msg.forceUpdate = true
        if (msg.translated) return true
        // #if PLUGINS
        msg.inu_rewrite = rewrite
        // #endif
        if (msg.caption != null) {
            msg.caption = null
            msg.generateCaption()
        } else if (msg.type == MessageObject.TYPE_TEXT || msg.type == MessageObject.TYPE_EMOJIS) {
            msg.applyNewText()
        }
        return true
    }

    private fun isBlockedHideMode(): Boolean {
        return InuConfig.BLOCKED_MESSAGES_MODE.value == InuConfig.BlockedMessagesModeItem.HIDE
    }

    private fun isPluginFiltering(): Boolean {
        // #if PLUGINS
        return PluginMessageFilters.isActive
        // #else
        return false
        // #endif
    }

    @JvmStatic
    fun ensureBlockedPeersLoaded(currentAccount: Int) {
        if (!isEnabled()) return
        val controller = MessagesController.getInstance(currentAccount)
        if (controller.totalBlockedCount == -1 && !controller.loadingBlockedPeers) {
            controller.getBlockedPeers(true)
        }
    }

    @JvmStatic
    fun shouldHide(messageObject: MessageObject?): Boolean {
        if (messageObject == null || isRevealed(messageObject.currentAccount, messageObject.dialogId)) return false
        // #if PLUGINS
        if (PluginMessageFilters.getVerdict(messageObject)?.hide == true) return true
        // #endif
        return isBlockedHideMode() && isBlockedMessage(messageObject)
    }

    /** a message a plugin hides is spoilered wherever it still shows, like a quote of it */
    @JvmStatic
    fun shouldSpoil(messageObject: MessageObject?): Boolean {
        if (messageObject == null || isRevealed(messageObject.currentAccount, messageObject.dialogId)) return false
        // #if PLUGINS
        if (PluginMessageFilters.getVerdict(messageObject)?.let { it.hide || it.spoiler } == true) return true
        // #endif
        return isEnabled() && isBlockedMessage(messageObject)
    }

    @JvmStatic
    fun checkBlockedEntities(
        messageObject: MessageObject?,
        original: ArrayList<TLRPC.MessageEntity>?,
    ): ArrayList<TLRPC.MessageEntity>? {
        messageObject?.inu_spoiled = shouldSpoil(messageObject)
        // #if PLUGINS
        messageObject?.inu_rewrite?.let { return addSpoiler(messageObject, original, it.text) }
        // #endif
        return addSpoiler(messageObject, original, messageObject?.messageOwner?.message)
    }

    @JvmStatic
    fun checkBlockedEntities(messageObject: MessageObject?): ArrayList<TLRPC.MessageEntity>? {
        // #if PLUGINS
        messageObject?.inu_rewrite?.let { return addSpoiler(messageObject, it.entities, it.text) }
        // #endif
        return checkBlockedOwnEntities(messageObject)
    }

    @JvmStatic
    fun checkBlockedOwnEntities(messageObject: MessageObject?): ArrayList<TLRPC.MessageEntity>? {
        return addSpoiler(messageObject, messageObject?.messageOwner?.entities, messageObject?.messageOwner?.message)
    }

    private fun addSpoiler(
        messageObject: MessageObject?,
        original: ArrayList<TLRPC.MessageEntity>?,
        text: String?,
    ): ArrayList<TLRPC.MessageEntity>? {
        if (!shouldSpoil(messageObject) || text.isNullOrEmpty()) return original

        val entities = if (original != null) ArrayList(original) else ArrayList()
        val spoiler = TLRPC.TL_messageEntitySpoiler()
        spoiler.offset = 0
        spoiler.length = text.length
        entities.add(spoiler)

        return entities
    }

    @JvmStatic
    fun refreshVisible(adapter: ChatActivity.ChatActivityAdapter): ArrayList<MessageObject> {
        val source = adapter.inu_getSourceMessages()
        adapter.inu_filtering = isHideMode()
        if (!adapter.inu_filtering) return source
        val buffer = adapter.inu_visibleMessages
        buffer.clear()
        for (i in 0 until source.size) {
            val msg = source[i]
            if (msg != null && !shouldHide(msg)) buffer.add(msg)
        }
        for (i in buffer.indices.reversed()) {
            val msg = buffer[i]
            if (msg.isDateObject) {
                var hasInDay = false
                for (j in 0 until buffer.size) {
                    val other = buffer[j]
                    if (!other.isDateObject && other.dateKeyInt == msg.dateKeyInt) {
                        hasInDay = true
                        break
                    }
                }
                if (!hasInDay) buffer.removeAt(i)
            } else if (msg.contentType == 2) {
                var hasAfter = false
                for (j in i - 1 downTo 0) {
                    val other = buffer[j]
                    if (!other.isDateObject && other.contentType != 2) {
                        hasAfter = true
                        break
                    }
                }
                if (!hasAfter) buffer.removeAt(i)
            }
        }
        return buffer
    }

    @JvmStatic
    fun filterGroup(group: MessageObject.GroupedMessages?): MessageObject.GroupedMessages? {
        if (group == null || !isHideMode()) return group
        for (i in 0 until group.messages.size) {
            if (shouldHide(group.messages[i])) return null
        }
        return group
    }

    private fun isBlockedMessage(messageObject: MessageObject?): Boolean {
        if (messageObject?.messageOwner == null || messageObject.storyItem != null) return false
        if (isBlockedPeer(messageObject.currentAccount, messageObject.fromChatId)) return true
        if (InuConfig.BLOCKED_MESSAGES_HIDE_REPLIES.value && !messageObject.isOutOwner() && isReplyToBlocked(messageObject)) return true
        val forwardedFrom = messageObject.messageOwner.fwd_from?.from_id ?: return false
        return isBlockedPeer(messageObject.currentAccount, MessageObject.getPeerId(forwardedFrom))
    }

    private fun isReplyToBlocked(messageObject: MessageObject): Boolean {
        val reply = messageObject.replyMessageObject
        if (reply != null && reply.messageOwner != null && reply.messageOwner !is TLRPC.TL_messageEmpty &&
            isBlockedPeer(messageObject.currentAccount, reply.fromChatId)
        ) {
            return true
        }
        val from = messageObject.messageOwner.reply_to?.reply_from?.from_id ?: return false
        return isBlockedPeer(messageObject.currentAccount, MessageObject.getPeerId(from))
    }

    private fun isBlockedPeer(currentAccount: Int, peerId: Long): Boolean {
        if (isExtraHidden(currentAccount, peerId)) return true
        val controller = MessagesController.getInstance(currentAccount)
        if (peerId > 0 && controller.getUser(peerId)?.bot == true) return false
        val userFull = if (peerId > 0) controller.getUserFull(peerId) else null
        return userFull?.blocked == true || controller.blockePeers.indexOfKey(peerId) >= 0
    }
}
