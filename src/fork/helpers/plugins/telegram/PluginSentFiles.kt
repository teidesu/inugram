package desu.inugram.helpers.plugins.telegram

import java.io.File
import org.telegram.messenger.FileLoader
import org.telegram.messenger.MessageObject
import org.telegram.messenger.NotificationCenter
import org.telegram.tgnet.TLRPC

/**
 * Stock moves a sent photo, video or document out of its media cache but leaves a gif where it was sent
 * from, so the bubble and viewer would download it again. Gifs are moved like the rest.
 * A failed send keeps its file and entry: stock retries from it. Not per plugin, since a send outlives
 * its plugin. ui thread only.
 */
internal object PluginSentFiles {
    private val tracked = HashMap<Long, File>()
    private val observers = HashMap<Int, NotificationCenter.NotificationCenterDelegate>()

    fun track(accountId: Int, localId: Int, file: File) {
        tracked[packFileKey(accountId, localId)] = file
        if (observers.containsKey(accountId)) return
        val observer = NotificationCenter.NotificationCenterDelegate { _, _, args -> onSent(accountId, args) }
        observers[accountId] = observer
        NotificationCenter.getInstance(accountId).addObserver(observer, NotificationCenter.messageReceivedByServer)
    }

    private fun onSent(accountId: Int, args: Array<Any?>) {
        val localId = args.getOrNull(0) as? Int ?: return
        val message = args.getOrNull(2) as? TLRPC.Message ?: return
        val file = tracked.remove(packFileKey(accountId, localId)) ?: return
        relocate(accountId, file, message)
        if (tracked.keys.any { unpackAccountId(it) == accountId }) return
        observers.remove(accountId)?.let {
            NotificationCenter.getInstance(accountId).removeObserver(it, NotificationCenter.messageReceivedByServer)
        }
    }

    private fun relocate(accountId: Int, file: File, message: TLRPC.Message) {
        val document = message.media?.document ?: return
        if (!MessageObject.isGifDocument(document) || !file.exists()) return
        val target = FileLoader.getInstance(accountId).getPathToAttach(document, false) ?: return
        if (target.exists()) file.delete() else file.renameTo(target)
    }

    private fun packFileKey(accountId: Int, localId: Int): Long = (accountId.toLong() shl 32) or (localId.toLong() and 0xffffffffL)

    private fun unpackAccountId(key: Long): Int = (key ushr 32).toInt()
}
