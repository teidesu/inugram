package desu.inugram.helpers

import android.content.Context
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ContentPreviewViewer
import java.io.File

object StickerSendHelper {
    const val MENU_OPTION_SEND_WITHOUT_PACK = 521

    // stock ContentPreviewViewer action codes for "send" and "send without sound"
    private const val ACTION_SEND = 0
    private const val ACTION_SEND_SILENT = 6

    @JvmStatic
    fun addPreviewMenuItems(
        delegate: ContentPreviewViewer.ContentPreviewViewerDelegate?,
        document: TLRPC.Document?,
        items: MutableList<CharSequence>,
        icons: MutableList<Int>,
        actions: MutableList<Int>,
    ) {
        if (delegate == null || document == null) return
        if (!MessageObject.isStickerHasSet(document) || MessageObject.isMaskDocument(document)) return
        if (DialogObject.isEncryptedDialog(delegate.dialogId)) return
        val anchor = actions.indexOf(ACTION_SEND_SILENT).takeIf { it >= 0 } ?: actions.indexOf(ACTION_SEND)
        if (anchor < 0) return
        items.add(anchor + 1, LocaleController.getString(R.string.InuSendWithoutPack))
        icons.add(anchor + 1, R.drawable.msg_send)
        actions.add(anchor + 1, MENU_OPTION_SEND_WITHOUT_PACK)
    }

    @JvmStatic
    fun processPreviewMenuOption(
        delegate: ContentPreviewViewer.ContentPreviewViewerDelegate?,
        accountId: Int,
        document: TLRPC.Document?,
        query: String?,
        parentObject: Any?,
        action: Int,
    ): Boolean {
        if (action != MENU_OPTION_SEND_WITHOUT_PACK) return false
        if (delegate == null || document == null) return true
        StickerDownloadHelper.withDownloadedFile(accountId, document, parentObject) { file ->
            Utilities.globalQueue.postRunnable {
                val local = ensureLocalFile(document, file) ?: return@postRunnable
                AndroidUtilities.runOnUIThread {
                    send(delegate, accountId, document, local, query, parentObject)
                }
            }
        }
        return true
    }

    @JvmStatic
    fun isPacklessSticker(accountId: Int, documentId: Long): Boolean =
        prefs.contains(getDocumentKey(accountId, documentId))

    @JvmStatic
    fun onStickerSent(accountId: Int, localDocument: TLRPC.Document, sentDocument: TLRPC.Document, dialogId: Long, messageId: Int) {
        val file = localDocument.localPath?.let(::File) ?: return
        if (file.parentFile != localDir) return
        val originalId = file.nameWithoutExtension.toLongOrNull() ?: return
        localDocument.localPath = null
        prefs.edit()
            .putString(getOriginalKey(accountId, originalId), "${sentDocument.id}:$dialogId:$messageId")
            .putLong(getDocumentKey(accountId, sentDocument.id), originalId)
            .apply()
        // the server adds every sent sticker to recents on its own
        val req = TLRPC.TL_messages_saveRecentSticker()
        req.id = TLRPC.TL_inputDocument().apply {
            id = sentDocument.id
            access_hash = sentDocument.access_hash
            file_reference = sentDocument.file_reference ?: ByteArray(0)
        }
        req.unsave = true
        ConnectionsManager.getInstance(accountId).sendRequest(req, null)
    }

    private val prefs by lazy {
        ApplicationLoader.applicationContext.getSharedPreferences("inugram_packless_stickers", Context.MODE_PRIVATE)
    }

    private fun getOriginalKey(accountId: Int, originalId: Long) =
        "${UserConfig.getInstance(accountId).clientUserId}_o$originalId"

    private fun getDocumentKey(accountId: Int, documentId: Long) =
        "${UserConfig.getInstance(accountId).clientUserId}_d$documentId"

    // kept outside the cache dir: stock moves cache-dir attach paths away on send, which reloads the image
    private val localDir by lazy { File(ApplicationLoader.getFilesDirFixed(), "inu_packless") }

    private fun ensureLocalFile(document: TLRPC.Document, source: File): File? {
        localDir.mkdirs()
        val file = File(localDir, "${document.id}.${source.extension}")
        if (file.exists()) return file
        val temp = File(localDir, "${document.id}.${Utilities.random.nextLong()}.tmp")
        if (!AndroidUtilities.copyFileSafe(source, temp)) return null
        return if (temp.renameTo(file) || file.exists()) file else null
    }

    private fun send(
        delegate: ContentPreviewViewer.ContentPreviewViewerDelegate,
        accountId: Int,
        document: TLRPC.Document,
        local: File,
        query: String?,
        parentObject: Any?,
    ) {
        val record = prefs.getString(getOriginalKey(accountId, document.id), null)?.split(":")
        val sentDocumentId = record?.getOrNull(0)?.toLongOrNull()
        val dialogId = record?.getOrNull(1)?.toLongOrNull()
        val messageId = record?.getOrNull(2)?.toIntOrNull()
        if (sentDocumentId == null || dialogId == null || messageId == null) {
            delegate.sendSticker(createPacklessCopy(accountId, document, local), query, parentObject, true, 0, 0)
            return
        }
        fetchSentMessage(accountId, dialogId, messageId) { message ->
            val sent = message?.media?.document
            if (sent == null || sent.id != sentDocumentId) {
                delegate.sendSticker(createPacklessCopy(accountId, document, local), query, parentObject, true, 0, 0)
            } else {
                sent.localPath = local.absolutePath
                delegate.sendSticker(sent, query, MessageObject(accountId, message, false, false), true, 0, 0)
            }
        }
    }

    private fun fetchSentMessage(accountId: Int, dialogId: Long, messageId: Int, callback: (TLRPC.Message?) -> Unit) {
        val controller = MessagesController.getInstance(accountId)
        val chat = if (DialogObject.isChatDialog(dialogId)) controller.getChat(-dialogId) else null
        val req: TLObject = if (ChatObject.isChannel(chat)) {
            TLRPC.TL_channels_getMessages().apply {
                channel = controller.getInputChannel(-dialogId)
                id.add(messageId)
            }
        } else {
            TLRPC.TL_messages_getMessages().apply { id.add(messageId) }
        }
        ConnectionsManager.getInstance(accountId).sendRequest(req) { response, _ ->
            val message = (response as? TLRPC.messages_Messages)?.messages?.firstOrNull { it.id == messageId }
            AndroidUtilities.runOnUIThread { callback(message) }
        }
    }

    // a document the server doesn't know yet gets uploaded as a new sticker with no set attached
    private fun createPacklessCopy(accountId: Int, document: TLRPC.Document, file: File): TLRPC.TL_document {
        val copy = TLRPC.TL_document()
        copy.mime_type = document.mime_type
        copy.date = ConnectionsManager.getInstance(accountId).currentTime
        copy.size = file.length()
        copy.file_reference = ByteArray(0)
        copy.localPath = file.absolutePath
        copy.thumbs.addAll(document.thumbs.filterIsInstance<TLRPC.TL_photoStrippedSize>())
        for (attribute in document.attributes) {
            copy.attributes.add(
                if (attribute is TLRPC.TL_documentAttributeSticker) {
                    TLRPC.TL_documentAttributeSticker().apply {
                        alt = attribute.alt
                        stickerset = TLRPC.TL_inputStickerSetEmpty()
                    }
                } else {
                    attribute
                }
            )
        }
        return copy
    }
}
