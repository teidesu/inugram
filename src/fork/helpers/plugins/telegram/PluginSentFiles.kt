package desu.inugram.helpers.plugins.telegram

import java.io.File
import org.telegram.messenger.FileLoader
import org.telegram.messenger.MessageObject
import org.telegram.messenger.NotificationCenter
import org.telegram.tgnet.TLRPC

/**
 * The files the app owns for a plugin's drawn sends ([PluginMedia.Upload.owned]), until the server
 * has each message. Stock moves a photo, video or document it sent out of its media cache to where
 * a download of it lands, but leaves a gif where it was sent from: nothing would ever claim a
 * plugin's copy, and the chat bubble and the viewer, reading only where a load of the document
 * looks, would each download the gif again. So a gif is moved the way the rest are, and the
 * message's `attachPath` goes stale the way theirs does.
 *
 * A send that failed keeps its file and its entry: stock retries from that file, and a retry that
 * gets through is still reported here. Not tied to a plugin, since a send outlives the plugin that
 * started it. Ui thread only, which is where a send is drawn and where the centre reports on it.
 */
internal object PluginSentFiles {
    private val tracked = HashMap<Long, File>()
    private val observers = HashMap<Int, NotificationCenter.NotificationCenterDelegate>()

    fun track(accountId: Int, localId: Int, file: File) {
        tracked[keyOf(accountId, localId)] = file
        if (observers.containsKey(accountId)) return
        val observer = NotificationCenter.NotificationCenterDelegate { _, _, args -> onSent(accountId, args) }
        observers[accountId] = observer
        NotificationCenter.getInstance(accountId).addObserver(observer, NotificationCenter.messageReceivedByServer)
    }

    private fun onSent(accountId: Int, args: Array<Any?>) {
        val localId = args.getOrNull(0) as? Int ?: return
        val message = args.getOrNull(2) as? TLRPC.Message ?: return
        val file = tracked.remove(keyOf(accountId, localId)) ?: return
        relocate(accountId, file, message)
        if (tracked.keys.any { accountOf(it) == accountId }) return
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

    private fun keyOf(accountId: Int, localId: Int): Long = (accountId.toLong() shl 32) or (localId.toLong() and 0xffffffffL)

    private fun accountOf(key: Long): Int = (key ushr 32).toInt()
}
