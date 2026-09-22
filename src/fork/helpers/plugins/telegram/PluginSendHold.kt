package desu.inugram.helpers.plugins.telegram

import desu.inugram.helpers.plugins.PluginManager
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.ui.ChatActivity
import org.telegram.ui.LaunchActivity

/**
 * Delays drawing outgoing messages briefly while middleware can still drop them.
 *
 * `interceptSendMessage` runs at `ConnectionsManager.sendRequest`, after the composer would
 * normally draw the bubble. Holding the draw lets quick drop verdicts suppress it entirely.
 *
 * Release the hold on passthrough, drop, or [GRACE_MILLIS], whichever comes first. The request
 * itself is not delayed. Sends that no registered middleware can handle draw immediately.
 *
 * Media uploads usually finish after the grace deadline, so their bubbles may still appear
 * before a drop. The hold mainly benefits text sends.
 */
object PluginSendHold {
    private const val GRACE_MILLIS = 100L

    private class Held(val account: Int, val peer: Long, val ids: Set<Int>, val show: Runnable) {
        var decided = false
        var draw = true
        var timer: Runnable? = null
    }

    /** ui thread only, which is where stock calls [draw] from and where a notification may be posted */
    private val held = ArrayList<Held>()

    /**
     * stock's own draw, as it stood at the call site: the interface update plus the dialog list
     * reload a non-scheduled send asks for.
     */
    @JvmStatic
    fun draw(account: Int, peer: Long, messages: ArrayList<MessageObject>, mode: Int, scheduleDate: Int) {
        if (!PluginManager.anyRunning) return drawNow(account, peer, messages, mode, scheduleDate)
        val show = Runnable { drawNow(account, peer, messages, mode, scheduleDate) }
        // a send growing into media is already on screen: what it wants is the change animation
        if (PluginSendMorph.redrawInstead(account, peer, messages, scheduleDate)) return
        val holding = PluginRpc.maySendBeIntercepted(messages.firstOrNull()?.messageOwner?.message)
        // a send behind a parked one waits too, or the two would arrive out of order
        if (!holding && held.none { it.account == account && it.peer == peer }) return show.run()

        val entry = Held(account, peer, messages.map { it.id }.toSet(), show)
        entry.decided = !holding
        held.add(entry)
        if (holding) {
            val timer = Runnable { decide(entry, draw = true) }
            entry.timer = timer
            AndroidUtilities.runOnUIThread(timer, GRACE_MILLIS)
        }
        flush()
    }

    private fun drawNow(account: Int, peer: Long, messages: ArrayList<MessageObject>, mode: Int, scheduleDate: Int) {
        MessagesController.getInstance(account).updateInterfaceWithMessages(peer, messages, mode)
        if (scheduleDate == 0) {
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload)
        }
    }

    /** the chain passed the send through, or there was never one to walk */
    internal fun release(account: Int, messages: List<MessageObject>) = settle(account, messages, draw = true)

    /**
     * whether what the composer minted is still going out. A drop leaves it to be deleted, so the
     * parked draw is thrown away rather than run.
     */
    internal fun settle(account: Int, messages: List<MessageObject>, draw: Boolean) {
        if (messages.isEmpty()) return
        // every account mints its local ids out of the same descending sequence, so an id alone names two messages
        val ids = messages.map { it.id }.toSet()
        AndroidUtilities.runOnUIThread {
            held.firstOrNull { entry -> entry.account == account && entry.ids.any(ids::contains) }?.let { decide(it, draw) }
        }
    }

    private fun decide(entry: Held, draw: Boolean) {
        if (entry.decided) return
        entry.decided = true
        entry.draw = draw
        entry.timer?.let { AndroidUtilities.cancelRunOnUIThread(it) }
        entry.timer = null
        flush()
    }

    private fun flush() {
        val blocked = HashSet<Pair<Int, Long>>()
        val ready = ArrayList<Held>()
        held.removeAll { entry ->
            val dialog = entry.account to entry.peer
            when {
                dialog in blocked -> false
                !entry.decided -> {
                    blocked.add(dialog)
                    false
                }
                else -> {
                    ready.add(entry)
                    true
                }
            }
        }
        for (entry in ready) if (entry.draw) entry.show.run() else finishSendTransition(entry.account, entry.peer)
    }

    /**
     * The composer keeps the typed text in the field for 200ms after a send, so the bubble's own
     * enter animation can grow out of it, and `ChatActivity` cuts that short by calling
     * [ChatActivityEnterView.startMessageTransition] when the bubble arrives. A send that is dropped
     * has no bubble to arrive, so the text would otherwise sit there for the whole 200ms.
     */
    private fun finishSendTransition(account: Int, peer: Long) {
        val chat = LaunchActivity.getSafeLastFragment() as? ChatActivity ?: return
        if (chat.currentAccount != account || chat.dialogId != peer) return
        chat.chatActivityEnterView?.startMessageTransition()
    }
}
