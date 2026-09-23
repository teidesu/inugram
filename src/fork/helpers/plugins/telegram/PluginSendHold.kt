package desu.inugram.helpers.plugins.telegram

import desu.inugram.helpers.plugins.PluginManager
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.ui.ChatActivity
import org.telegram.ui.LaunchActivity

/**
 * `interceptSendMessage` runs at `ConnectionsManager.sendRequest`, after the composer would draw the
 * bubble, so the draw is held until passthrough, drop, or [GRACE_MILLIS]. Media uploads usually outlast
 * the grace, so this mostly benefits text sends.
 */
object PluginSendHold {
    private const val GRACE_MILLIS = 100L

    private class Held(val account: Int, val peer: Long, val ids: Set<Int>, val show: Runnable) {
        var decided = false
        var draw = true
        var timer: Runnable? = null
    }

    /** ui thread only, where stock calls [draw] from */
    private val held = ArrayList<Held>()

    /** stock's draw as at the call site: interface update plus the dialog reload a non-scheduled send asks for */
    @JvmStatic
    fun draw(account: Int, peer: Long, messages: ArrayList<MessageObject>, mode: Int, scheduleDate: Int) {
        if (!PluginManager.anyRunning) return drawNow(account, peer, messages, mode, scheduleDate)
        val show = Runnable { drawNow(account, peer, messages, mode, scheduleDate) }
        // a send growing into media is already on screen and wants the change animation
        if (PluginSendMorph.redrawInstead(account, peer, messages, scheduleDate)) return
        val holding = PluginRpc.maySendBeIntercepted(messages.firstOrNull()?.messageOwner?.message)
        // a send behind a parked one waits too, or they would arrive out of order
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

    internal fun release(account: Int, messages: List<MessageObject>) = settle(account, messages, draw = true)

    /** a drop leaves the message to be deleted, so the parked draw is discarded */
    internal fun settle(account: Int, messages: List<MessageObject>, draw: Boolean) {
        if (messages.isEmpty()) return
        // all accounts mint local ids from the same sequence
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
     * the composer keeps the typed text for 200ms so the bubble's enter animation grows out of it, and
     * `ChatActivity` cuts that short via [ChatActivityEnterView.startMessageTransition] when the bubble arrives.
     * A dropped send has no bubble.
     */
    private fun finishSendTransition(account: Int, peer: Long) {
        val chat = LaunchActivity.getSafeLastFragment() as? ChatActivity ?: return
        if (chat.currentAccount != account || chat.dialogId != peer) return
        chat.chatActivityEnterView?.startMessageTransition()
    }
}
