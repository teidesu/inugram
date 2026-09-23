package desu.inugram.helpers.plugins.telegram

import android.webkit.MimeTypeMap
import desu.inugram.core.plugins.OwnerRegistry
import desu.inugram.helpers.plugins.SessionResource
import desu.inugram.helpers.plugins.UiObservation
import desu.inugram.core.plugins.PluginRefusal
import desu.inugram.helpers.media.MediaSendHelper
import desu.inugram.helpers.plugins.io.PluginTransfers
import desu.inugram.helpers.plugins.ui.PluginAnimationDecoder
import desu.inugram.helpers.plugins.tl.TlHandles
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.telegram.PluginWrites.Call
import desu.inugram.core.plugins.PluginWire.refuse
import desu.inugram.helpers.plugins.tl.TlJson
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC

/**
 * Stock's loader handles transfers, dc migration, cdn redirects, file-reference refreshes and the path
 * database; downloads join existing transfers. Upload inputs are staged as files because stock takes
 * paths and only rust can read blob content.
 */
object PluginMedia : SessionResource {
    private class Transfer(
        val call: Call,
        val fileName: String,
        upload: Boolean,
        val onDone: (Any?, String?) -> Unit,
    ) {
        val progressEvent =
            if (upload) NotificationCenter.fileUploadProgressChanged else NotificationCenter.fileLoadProgressChanged
        val doneEvent = if (upload) NotificationCenter.fileUploaded else NotificationCenter.fileLoaded
        val failedEvent = if (upload) NotificationCenter.fileUploadFailed else NotificationCenter.fileLoadFailed

        val observation = UiObservation(
            intArrayOf(progressEvent, doneEvent, failedEvent),
            { listOf(NotificationCenter.getInstance(call.accountId)) },
        ) { id, _, args ->
            if (args.isEmpty() || args[0] != fileName) return@UiObservation
            when (id) {
                progressEvent -> report(this, longAt(args, 1), longAt(args, 2))
                doneEvent -> {
                    release(this)
                    onDone(args.getOrNull(1), null)
                }
                failedEvent -> {
                    release(this)
                    onDone(null, "the transfer failed")
                }
            }
        }
    }

    private val live = OwnerRegistry<PluginSession, Transfer>()

    fun messageFile(session: PluginSession, accountId: Int, value: String): String {
        return EngineDispatch.produceWire("getMessageFile") {
            val message = resolveMessageHandle(session.tl, value)
            val media = mediaFile(message) ?: return PluginWire.encodeNull()
            val loader = FileLoader.getInstance(accountId)
            val path = findLocalFile(loader, message, media)
                ?: loader.getPathToMessage(message)
                ?: return PluginWire.encodeNull()
            val json = JSONObject()
            json.put("path", path.absolutePath)
            json.put("exists", path.exists() && path.length() > 0)
            PluginWire.encodeJson(json.toString())
        }
    }

    internal fun download(call: Call, toFile: Boolean): String? {
        val message = resolveMessageHandle(call.session.tl, call.values.firstOrNull() ?: refuse("invalid-argument", "no message"))
        val media = mediaFile(message) ?: refuse("invalid-argument", "this message has no media to download")
        val loader = FileLoader.getInstance(call.accountId)
        val already = findLocalFile(loader, message, media)
        if (already != null) {
            // settling inside the upcall re-enters the same engine and aborts the process
            call.answer { downloadWire(already, message, toFile) }
            return null
        }
        val transfer = Transfer(call, media.fileName, upload = false) { arrived, error ->
            call.answer {
                val file = arrived as? File
                when {
                    error != null -> PluginWire.encodePluginError("internal", "downloadMedia: $error")
                    file == null -> PluginWire.encodePluginError("not-found", "downloadMedia: the file did not arrive")
                    else -> downloadWire(file, message, toFile)
                }
            }
        }
        observe(transfer)
        AndroidUtilities.runOnUIThread {
            when (media) {
                is Downloadable.Doc -> loader.loadFile(media.document, message, FileLoader.PRIORITY_NORMAL, 0)
                is Downloadable.Image ->
                    loader.loadFile(media.location, message, "jpg", FileLoader.PRIORITY_NORMAL, 0)
            }
        }
        return null
    }

    /** a document stock streamed (an autoplayed gif) is kept in its cache folder under the same name */
    private fun findLocalFile(loader: FileLoader, message: TLRPC.Message, media: Downloadable): File? =
        sequenceOf(
            { loader.getPathToMessage(message) },
            { (media as? Downloadable.Doc)?.let { loader.getPathToAttach(it.document, true) } },
        ).mapNotNull { it() }.firstOrNull { it.exists() && it.length() > 0 }

    private fun downloadWire(file: File, message: TLRPC.Message, toFile: Boolean): String {
        val json = JSONObject()
        json.put("path", file.absolutePath)
        if (!toFile) {
            json.put("size", file.length())
            json.put("mime", getMessageMime(message, file.name))
            json.put("name", downloadName(message, file))
            json.put("mtime", file.lastModified())
        }
        return PluginWire.encodeJson(json.toString())
    }

    private fun getMessageDocument(message: TLRPC.Message): TLRPC.Document? =
        (message.media as? TLRPC.TL_messageMediaDocument)?.document

    private fun downloadName(message: TLRPC.Message, file: File): String =
        FileLoader.getDocumentFileName(getMessageDocument(message)).takeIf { it.isNotEmpty() } ?: file.name

    private fun getMessageMime(message: TLRPC.Message, fileName: String): String =
        getMessageDocument(message)?.mime_type?.takeIf { it.isNotEmpty() } ?: guessMimeFromName(fileName)

    internal fun uploadFile(call: Call): String? {
        val source = stagedFile(call, call.values.firstOrNull() ?: refuse("invalid-argument", "no file"))
        upload(call, source) { input ->
            call.answer {
                if (input == null) PluginWire.encodePluginError("internal", "uploadFile: the upload failed")
                else PluginWire.encodeJson(TlJson.toJson(input, call.session.tl.policy).toString())
            }
        }
        return null
    }

    /**
     * not stock's `FileLoader.uploadFile(path, callback)`: it matches paths with `==`, and a second upload of
     * a path already in `uploadOperationPaths` starts no operation, so its callback never fires
     */
    private fun upload(call: Call, source: Source, done: (TLRPC.InputFile?) -> Unit) {
        val requested = call.json.optString("fileName")
        val transfer = Transfer(call, source.path.absolutePath, upload = true) { uploaded, _ ->
            val input = uploaded as? TLRPC.InputFile
            val name = getFileName(source, requested)
            done(if (input == null) null else named(input, name))
        }
        observe(transfer)
        AndroidUtilities.runOnUIThread {
            FileLoader.getInstance(call.accountId)
                .uploadFile(source.path.absolutePath, false, false, ConnectionsManager.FileTypeFile)
        }
    }

    /** only reports progress; the message coming back settles the promise */
    internal fun watchUpload(call: Call, path: String) {
        observe(Transfer(call, path, upload = true) { _, _ -> })
    }

    private fun named(input: TLRPC.InputFile, name: String): TLRPC.InputFile {
        val copy = if (input is TLRPC.TL_inputFileBig) TLRPC.TL_inputFileBig() else TLRPC.TL_inputFile()
        copy.id = input.id
        copy.parts = input.parts
        copy.md5_checksum = input.md5_checksum
        copy.name = name.ifEmpty { input.name }
        return copy
    }

    internal fun sendMedia(call: Call, album: Boolean): String? {
        // a secret-chat send must be refused before anything uploads
        val peer = call.peer() as TLRPC.InputPeer
        val items = if (album) call.json.optJSONArray("items") ?: refuse("invalid-argument", "no items")
        else null
        val count = items?.length() ?: 1
        if (call.values.size != count) refuse("invalid-argument", "sendMedia: item and file counts disagree")
        if (!album && call.optedIn("optimistic") && PluginOptimisticSend.canSend(call)) {
            val wire = call.values.first()
            if (wire.startsWith(FILE_TAG)) {
                val source = stagedFile(call, wire)
                val name = getFileName(source, call.json.optString("fileName"))
                val mime = source.mime.ifEmpty { guessMimeFromName(name) }
                val asDocument = call.flag("asDocument")
                val described = describeLocalDocument(source.path, mime, asDocument)
                // the fallback uploads the staged file, which lasts until the write answers
                return PluginOptimisticSend.sendMedia(call, source.path, name, mime, asDocument, described) {
                    PluginWrites.answerRefusals(call) { sendByRequest(call, peer, items, count, described.attributes) }
                }
            }
        }
        sendByRequest(call, peer, items, count)
        return null
    }

    private fun sendByRequest(
        call: Call,
        peer: TLRPC.InputPeer,
        items: org.json.JSONArray?,
        count: Int,
        described: List<TLRPC.DocumentAttribute>? = null,
    ) {
        val medias = arrayOfNulls<TLRPC.InputMedia>(count)
        var remaining = count
        for (index in 0 until count) {
            val describe = items?.optJSONObject(index) ?: call.json
            resolveMedia(call, call.values[index], describe, described) { media ->
                // an uploaded item answers inline, one still uploading answers from the ui thread
                EngineDispatch.scheduler.postRunnable {
                    medias[index] = media
                    remaining--
                    if (remaining > 0) return@postRunnable
                    if (medias.any { it == null }) {
                        call.answer {
                            PluginWire.encodePluginError("internal", "sendMedia: an upload failed")
                        }
                    } else {
                        try {
                            sendResolved(call, peer, medias.filterNotNull(), items)
                        } catch (e: PluginRefusal) {
                            call.answer { e.wire }
                        }
                    }
                }
            }
        }
    }

    private fun sendResolved(
        call: Call,
        peer: TLRPC.InputPeer,
        medias: List<TLRPC.InputMedia>,
        items: org.json.JSONArray?,
    ) {
        if (items == null) {
            val request = TLRPC.TL_messages_sendMedia()
            request.peer = peer
            request.media = medias.first()
            request.message = call.json.optString("text")
            request.entities = PluginWrites.readEntities(call.json)
            request.random_id = Utilities.random.nextLong()
            request.silent = call.flag("silent")
            request.schedule_date = call.int("scheduleDate")
            request.reply_to = PluginWrites.replyTo(call)
            request.send_as = PluginWrites.sendAs(call)
            PluginWrites.send(call, request) { response ->
                PluginWrites.messageWire(call, response, request.random_id, request.message)
            }
            return
        }
        val request = TLRPC.TL_messages_sendMultiMedia()
        request.peer = peer
        request.silent = call.flag("silent")
        request.schedule_date = call.int("scheduleDate")
        request.reply_to = PluginWrites.replyTo(call)
        request.send_as = PluginWrites.sendAs(call)
        for ((index, media) in medias.withIndex()) {
            val describe = items.optJSONObject(index) ?: JSONObject()
            request.multi_media.add(
                TLRPC.TL_inputSingleMedia().apply {
                    this.media = media
                    random_id = Utilities.random.nextLong()
                    message = describe.optString("text")
                    entities = PluginWrites.readEntities(describe)
                },
            )
        }
        PluginWrites.send(call, request) { response -> PluginWrites.messagesWire(call, response) }
    }

    private fun resolveMedia(
        call: Call,
        wire: String,
        describe: JSONObject,
        described: List<TLRPC.DocumentAttribute>?,
        done: (TLRPC.InputMedia?) -> Unit,
    ) {
        val asDocument = describe.optBoolean("asDocument", false)
        if (!wire.startsWith(FILE_TAG)) {
            when (val given = call.session.tl.objectFromWire(wire)) {
                is TLRPC.InputMedia -> return done(given)
                is TLRPC.InputFile -> return done(uploadedMedia(given, "", guessMimeFromName(""), asDocument, emptyList()))
                else -> refuse("invalid-argument", "sendMedia: '${given.javaClass.simpleName}' is not a file")
            }
        }
        val source = stagedFile(call, wire)
        val name = getFileName(source, describe.optString("fileName"))
        val mime = source.mime.ifEmpty { guessMimeFromName(name) }
        // not in the callback, which runs on the ui thread
        val attributes = described ?: describeLocalDocument(source.path, mime, asDocument, withThumb = false).attributes
        upload(call, source) { input ->
            if (input == null) done(null)
            else {
                input.name = name
                done(uploadedMedia(input, name, mime, asDocument, attributes))
            }
        }
    }

    private fun uploadedMedia(
        input: TLRPC.InputFile,
        name: String,
        mime: String,
        asDocument: Boolean,
        described: List<TLRPC.DocumentAttribute>,
    ): TLRPC.InputMedia {
        if (PluginOptimisticSend.asPhoto(mime, asDocument)) {
            return TLRPC.TL_inputMediaUploadedPhoto().apply { file = input }
        }
        return TLRPC.TL_inputMediaUploadedDocument().apply {
            file = input
            mime_type = mime
            force_file = asDocument
            if (name.isNotEmpty()) {
                attributes.add(TLRPC.TL_documentAttributeFilename().apply { file_name = name })
            }
            attributes.addAll(described)
        }
    }

    internal class Upload(val file: File, val owned: Boolean) {
        fun discard() {
            if (owned) file.delete()
        }
    }

    internal fun takeForUpload(call: Call, source: File, name: String): Upload {
        val staged = PluginTransfers.isStaged(call.session.plugin.id, source)
        val extension = name.substringAfterLast('.', "")
        val dir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE)
            ?: refuse("internal", "there is no cache directory to send this file from")
        if (!staged && source.extension.equals(extension, ignoreCase = true) && !source.canonicalFile.startsWith(dir.canonicalFile)) {
            return Upload(source, owned = false)
        }
        val target = File(dir, "inu_plugin_send_${UUID.randomUUID()}" + if (extension.isEmpty()) "" else ".$extension")
        if (staged && source.renameTo(target)) return Upload(target, owned = true)
        try {
            source.copyTo(target, overwrite = true)
        } catch (e: Exception) {
            target.delete()
            refuse("internal", "this file could not be copied: ${e.message ?: e.toString()}")
        }
        return Upload(target, owned = true)
    }

    internal class LocalDescription(
        val attributes: List<TLRPC.DocumentAttribute>,
        val thumb: TLRPC.PhotoSize?,
    ) {
        companion object {
            val NONE = LocalDescription(emptyList(), null)
        }
    }

    internal fun describeLocalDocument(file: File, mime: String, asDocument: Boolean, withThumb: Boolean = true): LocalDescription {
        if (asDocument || !mime.startsWith("video/")) return LocalDescription.NONE
        val path = file.absolutePath
        val video = MediaSendHelper.describeVideo(path, isEncrypted = false)
            ?: return LocalDescription.NONE
        val attributes = if (video.hasAudio) listOf(video.attribute) else listOf(video.attribute, TLRPC.TL_documentAttributeAnimated())
        val thumb = if (withThumb) createVideoCover(path) else null
        return LocalDescription(attributes, thumb)
    }

    /** ffmpeg rather than the platform retriever, which spins up a hardware decoder for one frame */
    private fun createVideoCover(path: String): TLRPC.PhotoSize? {
        val frame = PluginAnimationDecoder.readFirstFrame(path, THUMB_SIDE)
            ?: MediaSendHelper.readFirstFrame(path)
        return MediaSendHelper.saveVideoThumb(frame, isEncrypted = false)
    }

    /** the side stock saves a video's cover at, outside a secret chat */
    private const val THUMB_SIDE = 320

    private const val FILE_TAG = "F"

    internal class Source(val path: File, val name: String, val mime: String)

    internal fun getFileName(source: Source, requested: String): String = requested.ifEmpty {
        source.name.ifEmpty {
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(source.mime)
            if (extension == null) source.path.name else "${source.path.nameWithoutExtension}.$extension"
        }
    }

    internal fun stagedFile(call: Call, wire: String): Source {
        if (!wire.startsWith(FILE_TAG)) refuse("invalid-argument", "expected a Blob, bytes or { path }")
        val json = JSONObject(wire.substring(1))
        val path = File(json.optString("path"))
        if (!path.exists() || !path.isFile) refuse("not-found", "no such file: ${path.absolutePath}")
        if (path.length() == 0L) refuse("invalid-argument", "this file is empty")
        return Source(path, json.optString("name"), json.optString("mime"))
    }

    // stock reads the media off the message and refreshes its file reference from it, so a read-only handle is normal
    private fun resolveMessageHandle(handles: TlHandles, wire: String): TLRPC.Message =
        handles.objectFromWire(wire, allowReadOnly = true) as? TLRPC.Message
            ?: refuse("invalid-argument", "expected a message")

    private sealed class Downloadable(val fileName: String) {
        class Doc(val document: TLRPC.Document, fileName: String) : Downloadable(fileName)
        class Image(val location: ImageLocation, fileName: String) : Downloadable(fileName)
    }

    private fun mediaFile(message: TLRPC.Message): Downloadable? {
        when (val media = message.media) {
            is TLRPC.TL_messageMediaDocument -> {
                val document = media.document ?: return null
                if (document is TLRPC.TL_documentEmpty) return null
                return Downloadable.Doc(document, FileLoader.getAttachFileName(document))
            }
            is TLRPC.TL_messageMediaPhoto -> {
                val photo = media.photo ?: return null
                val size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, Int.MAX_VALUE) ?: return null
                val location = ImageLocation.getForObject(size, photo) ?: return null
                return Downloadable.Image(location, FileLoader.getAttachFileName(size, "jpg"))
            }
            else -> return null
        }
    }

    internal fun guessMimeFromName(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    private fun observe(transfer: Transfer) {
        live.add(transfer.call.session, transfer)
        transfer.observation.start()
    }

    private fun report(transfer: Transfer, loaded: Long, total: Long) {
        val engine = transfer.call.session.engine
        EngineDispatch.onEngine(transfer.call.session) {
            engine.writeProgress(transfer.call.requestId, loaded, total)
        }
    }

    private fun longAt(args: Array<Any?>, index: Int): Long = when (val value = args.getOrNull(index)) {
        is Long -> value
        is Int -> value.toLong()
        else -> 0L
    }

    private fun release(transfer: Transfer) {
        live.remove(transfer.call.session) { it === transfer }
        transfer.observation.stop()
    }

    override fun detach(session: PluginSession) {
        val mine = live.take(session)
        if (mine.isEmpty()) return
        for (transfer in mine) transfer.observation.stop()
    }
}
