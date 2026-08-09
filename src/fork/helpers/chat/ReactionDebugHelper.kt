package desu.inugram.helpers.chat

import desu.inugram.InuConfig
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Cells.ChatMessageCell

object ReactionDebugHelper {
    private var staleUnreadLoggedId = 0

    @JvmStatic
    fun isEnabled(): Boolean = BuildVars.LOGS_ENABLED && InuConfig.EXTRA_DEBUG_LOGS.value

    @JvmStatic
    fun caller(): String {
        val sb = StringBuilder()
        var frames = 0
        for (frame in Throwable().stackTrace) {
            val name = frame.className
            if (name.startsWith("desu.inugram.helpers.chat.ReactionDebugHelper")) continue
            if (!name.startsWith("org.telegram") && !name.startsWith("desu.inugram")) continue
            if (sb.isNotEmpty()) sb.append(" < ")
            sb.append(name.substringAfterLast('.')).append('.').append(frame.methodName).append(':').append(frame.lineNumber)
            if (++frames == 6) break
        }
        return sb.toString()
    }

    @JvmStatic
    fun describe(messageObject: MessageObject?): String {
        val message = messageObject?.messageOwner ?: return "null"
        return "mid=${message.id} ${describeReactions(messageObject)}"
    }

    private fun describeReactions(messageObject: MessageObject?): String {
        val message = messageObject?.messageOwner ?: return "reactions=none"
        val reactions = message.reactions ?: return "reactions=null"
        val results = reactions.results.joinToString(",") { "${describe(it.reaction)}x${it.count}${if (it.chosen) "*" else ""}" }
        val recent = reactions.recent_reactions.joinToString(",") { "${describe(it.reaction)}${if (it.unread) "!" else ""}" }
        return "results=[$results] recent=[$recent] unread=${MessageObject.hasUnreadReactions(message)}"
    }

    private fun describe(reaction: TLRPC.Reaction?): String = when (reaction) {
        is TLRPC.TL_reactionEmoji -> reaction.emoticon
        is TLRPC.TL_reactionCustomEmoji -> "custom${reaction.document_id}"
        null -> "null"
        else -> reaction.javaClass.simpleName
    }

    @JvmStatic
    fun onMessageObjectReplaced(old: MessageObject?, new: MessageObject) {
        if (!isEnabled() || old == null) return
        val oldDesc = describe(old)
        val newDesc = describe(new)
        if (oldDesc == newDesc) return
        FileLog.d("InuRx replaced object reactionsChanged=${new.reactionsChanged} old=$oldDesc new=$newDesc")
    }

    @JvmStatic
    fun onReactionsLayoutKept(cell: ChatMessageCell, messageObject: MessageObject) {
        if (!isEnabled()) return
        val shown = cell.reactionsLayoutInBubble.messageObject
        if (shown === messageObject) return
        if (describeReactions(shown) == describeReactions(messageObject)) return
        FileLog.d("InuRx cell kept stale reactions shown=${describe(shown)} actual=${describe(messageObject)}")
    }

    @JvmStatic
    fun checkStaleUnread(cell: ChatMessageCell, unreadReactionsCount: Int) {
        if (!isEnabled()) return
        val messageObject = cell.messageObject ?: return
        val unreadInData = unreadReactionsCount > 0 && MessageObject.hasUnreadReactions(messageObject.messageOwner)
        if (!unreadInData || cell.reactionsLayoutInBubble.hasUnreadReactions) {
            if (staleUnreadLoggedId == messageObject.id) staleUnreadLoggedId = 0
            return
        }
        if (staleUnreadLoggedId == messageObject.id) return
        staleUnreadLoggedId = messageObject.id
        FileLog.d("InuRx stale unread badge count=$unreadReactionsCount actual=${describe(messageObject)} shown=${describe(cell.reactionsLayoutInBubble.messageObject)}")
    }
}
