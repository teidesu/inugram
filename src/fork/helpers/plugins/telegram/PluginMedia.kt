package desu.inugram.helpers.plugins.telegram

import desu.inugram.core.plugins.OwnerRegistry
import desu.inugram.helpers.plugins.SessionResource
import desu.inugram.core.plugins.PluginRefusal
import desu.inugram.helpers.media.MediaSendHelper
import desu.inugram.helpers.plugins.io.PluginTransfers
import desu.inugram.helpers.plugins.ui.PluginAnimationDecoder
import desu.inugram.helpers.plugins.tl.TlHandles
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.telegram.PluginWrites.Call
import desu.inugram.core.plugins.PluginWire.refuse
import desu.inugram.helpers.plugins.tl.TlFilter
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
 * The media transfers: `getMessageFile`, `downloadMedia`, `downloadMediaToFile`, `uploadFile`, and
 * the upload half of `sendMedia`/`sendMultiMedia`.
 *
 * **Stock's loader does the transferring** - it knows about datacenter migration, cdn redirects,
 * refreshing a stale file reference and the per-account file-path database - so a plugin's download
 * is the app's download, joins one already running for the same file, and lands where picking it in
 * the ui would have. What this owns is the bookkeeping.
 *
 * Progress is reported per chunk and coalesced natively (`progress.rs`), so nothing is throttled
 * here and the numbers a plugin sees are the transfer's own. Anything a send is handed has already
 * become a file: stock's uploader takes a path, and blob content is only readable from rust.
 */
object PluginMedia : SessionResource {
    /** stock names every file it moves and reports it through the same three events, carrying the name first and the payload second */
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
        val events = intArrayOf(progressEvent, doneEvent, failedEvent)

        /** ui thread only: both the add and the remove run there, in that order */
        var observer: NotificationCenter.NotificationCenterDelegate? = null
    }

    /**
     * every transfer still listening, per plugin. A transfer settles from a [NotificationCenter]
     * event and there is no event for one stock never started, so without this the observer - and
     * through it the engine - would outlive the plugin. Touched from the plugin queue and the ui thread.
     */
    private val live = OwnerRegistry<PluginSession, Transfer>()


    fun messageFile(session: PluginSession, accountId: Int, value: String): String {
        return try {
            val message = messageOf(session.tl, value)
            val media = mediaFile(message) ?: return PluginWire.encodeNull()
            val loader = FileLoader.getInstance(accountId)
            val path = findLocalFile(loader, message, media)
                ?: loader.getPathToMessage(message)
                ?: return PluginWire.encodeNull()
            val json = JSONObject()
            json.put("path", path.absolutePath)
            json.put("exists", path.exists() && path.length() > 0)
            PluginWire.encodeJson(json.toString())
        } catch (e: PluginRefusal) {
            e.wire
        } catch (e: Exception) {
            PluginWire.encodePluginError("internal", "getMessageFile: ${e.message ?: e.toString()}")
        }
    }

    internal fun download(call: Call, toFile: Boolean): String? {
        val message = messageOf(call.session.tl, call.values.firstOrNull() ?: refuse("invalid-argument", "no message"))
        val media = mediaFile(message) ?: refuse("invalid-argument", "this message has no media to download")
        val loader = FileLoader.getInstance(call.accountId)
        val already = findLocalFile(loader, message, media)
        if (already != null) {
            // still through [answer], because settling inside the upcall is the same-engine re-entry that aborts the process
            PluginWrites.answer(call) { downloadWire(already, message, toFile) }
            return null
        }
        val transfer = Transfer(call, media.fileName, upload = false) { arrived, error ->
            PluginWrites.answer(call) {
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

    /**
     * The media's file if it is already on disk. A download lands where [FileLoader.getPathToMessage]
     * says, but a document stock streamed - a gif autoplayed in a chat - is kept in its cache folder
     * under the same name.
     */
    private fun findLocalFile(loader: FileLoader, message: TLRPC.Message, media: Downloadable): File? =
        sequenceOf(
            { loader.getPathToMessage(message) },
            { (media as? Downloadable.Doc)?.let { loader.getPathToAttach(it.document, true) } },
        ).mapNotNull { it() }.firstOrNull { it.exists() && it.length() > 0 }

    /** the name rides along so a download fed straight back to `sendMedia` keeps it */
    private fun downloadWire(file: File, message: TLRPC.Message, toFile: Boolean): String {
        val json = JSONObject()
        json.put("path", file.absolutePath)
        if (!toFile) {
            json.put("size", file.length())
            json.put("mime", mimeOfMessage(message, file.name))
            json.put("name", downloadName(message, file))
            json.put("mtime", file.lastModified())
        }
        return PluginWire.encodeJson(json.toString())
    }

    private fun documentOf(message: TLRPC.Message): TLRPC.Document? =
        (message.media as? TLRPC.TL_messageMediaDocument)?.document

    private fun downloadName(message: TLRPC.Message, file: File): String =
        FileLoader.getDocumentFileName(documentOf(message)).takeIf { it.isNotEmpty() } ?: file.name

    private fun mimeOfMessage(message: TLRPC.Message, fileName: String): String =
        documentOf(message)?.mime_type?.takeIf { it.isNotEmpty() } ?: mimeOfName(fileName)

    internal fun uploadFile(call: Call): String? {
        val source = stagedFile(call, call.values.firstOrNull() ?: refuse("invalid-argument", "no file"))
        upload(call, source) { input ->
            PluginWrites.answer(call) {
                if (input == null) PluginWire.encodePluginError("internal", "uploadFile: the upload failed")
                else PluginWire.encodeJson(TlJson.toJson(input, TlFilter.policyFor(call.session.permissions)).toString())
            }
        }
        return null
    }

    /**
     * Stock's own `FileLoader.uploadFile(path, callback)` is deliberately not used: it matches the
     * notification's path with `==`, and a second upload of a path already in `uploadOperationPaths`
     * never starts an operation of its own, so its callback is handed a different `String` instance
     * than the running operation reports under and never fires. Listening here settles every waiter
     * on the one operation, and puts the observer where [detach] can end it.
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

    /**
     * `onProgress` for a send the composer owns: stock uploads from the same staged path, so the
     * same transfer reports it. It only reports - what settles the promise is the message coming
     * back - so the done and failed events do nothing but stop the observer.
     */
    internal fun watchUpload(call: Call, path: String) {
        observe(Transfer(call, path, upload = true) { _, _ -> })
    }

    /** one operation reports the same `InputFile` instance to every waiter, so the name goes on a copy */
    private fun named(input: TLRPC.InputFile, name: String): TLRPC.InputFile {
        val copy = if (input is TLRPC.TL_inputFileBig) TLRPC.TL_inputFileBig() else TLRPC.TL_inputFile()
        copy.id = input.id
        copy.parts = input.parts
        copy.md5_checksum = input.md5_checksum
        copy.name = name.ifEmpty { input.name }
        return copy
    }

    internal fun sendMedia(call: Call, album: Boolean): String? {
        // the peer first: a send into a secret chat must be refused before anything is uploaded
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
                val mime = source.mime.ifEmpty { mimeOfName(name) }
                val asDocument = call.flag("asDocument")
                val described = describeLocalDocument(source.path, mime, asDocument)
                // the file is taken only once the composer is: the fallback uploads the staged one, which lasts until the write answers
                return PluginOptimisticSend.sendMedia(call, source.path, name, mime, asDocument, described) {
                    PluginWrites.answerRefusals(call) { sendByRequest(call, peer, items, count, described.attributes) }
                }
            }
        }
        sendByRequest(call, peer, items, count)
        return null
    }

    /** [described] is what an optimistic send already read off its one file, so the fallback does not read it again */
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
                // back onto the engine's queue before the counter is touched: an item already uploaded answers inline while one still going up answers from the ui thread
                EngineDispatch.scheduler.postRunnable {
                    medias[index] = media
                    remaining--
                    if (remaining > 0) return@postRunnable
                    if (medias.any { it == null }) {
                        PluginWrites.answer(call) {
                            PluginWire.encodePluginError("internal", "sendMedia: an upload failed")
                        }
                    } else {
                        try {
                            sendResolved(call, peer, medias.filterNotNull(), items)
                        } catch (e: PluginRefusal) {
                            PluginWrites.answer(call) { e.wire }
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
            request.entities = PluginWrites.entitiesOf(call.json)
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
                    entities = PluginWrites.entitiesOf(describe)
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
            when (val given = PluginWrites.tlValue(call.session.tl, wire)) {
                is TLRPC.InputMedia -> return done(given)
                is TLRPC.InputFile -> return done(uploadedMedia(given, "", mimeOfName(""), asDocument, emptyList()))
                else -> refuse("invalid-argument", "sendMedia: '${given.javaClass.simpleName}' is not a file")
            }
        }
        val source = stagedFile(call, wire)
        val name = getFileName(source, describe.optString("fileName"))
        val mime = source.mime.ifEmpty { mimeOfName(name) }
        // read here rather than in the callback: that one answers on the ui thread, and this parses a container
        val attributes = described ?: describeLocalDocument(source.path, mime, asDocument, withThumb = false).attributes
        upload(call, source) { input ->
            if (input == null) done(null)
            else {
                input.name = name
                done(uploadedMedia(input, name, mime, asDocument, attributes))
            }
        }
    }

    /** an image goes up the way the ui would send it unless `asDocument` says otherwise; webp is the exception stock makes too, a sticker being a document however it looks */
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

    /** the file the composer uploads, and whether it is the app's own to move or delete afterwards */
    internal class Upload(val file: File, val owned: Boolean) {
        fun discard() {
            if (owned) file.delete()
        }
    }

    /**
     * What the composer is handed. A local message goes on pointing at that file, its extension is
     * what tells the loader an mp4 is an animation, and stock moves a sent file out of its media
     * cache once the server has answered. So a transfer rust staged, which it deletes the moment the
     * write answers, is renamed into that cache - [PluginTransfers] keeps it on the same volume. A
     * path the plugin named is uploaded from where it is and never moved, unless stock would move
     * it or its extension disagrees with the name, which only a copy fixes.
     */
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

    /** what a local [TLRPC.TL_document] needs to say about itself before the server has one of its own */
    internal class LocalDescription(
        val attributes: List<TLRPC.DocumentAttribute>,
        val thumb: TLRPC.PhotoSize?,
    ) {
        companion object {
            val NONE = LocalDescription(emptyList(), null)
        }
    }

    /**
     * What stock puts on a video the composer was given, and what nothing puts on a plugin's upload
     * otherwise: an mp4 carrying only a filename arrives as a file rather than as something that
     * plays. A silent video is an animation, which is what a gif is on telegram - stock's own muted
     * sends say exactly this about theirs - so encoding one and sending it needs no more than this.
     *
     * The thumb is the first frame, which `MessageObject` reads once, in its constructor: nothing
     * regenerates it when the server's document replaces the local one, so a document sent without
     * one is an empty bubble until the chat is reopened.
     *
     * Nothing is claimed about a file the device cannot read: it goes as it would have anyway.
     * Reading a container is disk work: call this on the queue the write came in on, never on the
     * ui thread the composer and the upload's own answer run on.
     */
    internal fun describeLocalDocument(file: File, mime: String, asDocument: Boolean, withThumb: Boolean = true): LocalDescription {
        if (asDocument || !mime.startsWith("video/")) return LocalDescription.NONE
        val path = file.absolutePath
        val video = MediaSendHelper.describeVideo(path, isEncrypted = false)
            ?: return LocalDescription.NONE
        val attributes = if (video.hasAudio) listOf(video.attribute) else listOf(video.attribute, TLRPC.TL_documentAttributeAnimated())
        val thumb = if (withThumb) coverOf(path) else null
        return LocalDescription(attributes, thumb)
    }

    /**
     * The first frame, through the app's ffmpeg bridge rather than the platform's retriever: the
     * platform spins up a hardware decoder for the one frame, which is most of a send's latency
     * before its bubble draws, and ffmpeg decodes it straight at the cover's size. The platform
     * stays as the fallback for what ffmpeg refuses.
     */
    private fun coverOf(path: String): TLRPC.PhotoSize? {
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
            val extension = MIME_BY_EXTENSION.entries.firstOrNull { it.value == source.mime }?.key
            if (extension == null) source.path.name else "${source.path.nameWithoutExtension}.$extension"
        }
    }

    internal fun stagedFile(call: Call, wire: String): Source {
        if (!wire.startsWith(FILE_TAG)) refuse("invalid-argument", "expected a Blob, bytes or { path }")
        val json = JSONObject(wire.substring(1))
        val path = File(json.optString("path"))
        if (!path.exists() || !path.isFile) refuse("not-found", "no such file: ${path.absolutePath}")
        if (path.length() == 0L) refuse("invalid-argument", "this file is empty")
        // a path the plugin named is read here and nowhere else; the grant that let it name one was checked in `writes.rs`
        return Source(path, json.optString("name"), json.optString("mime"))
    }

    // a transfer only names its message (stock reads the media off it and refreshes its file reference from it), so a read-only handle is the normal argument here
    private fun messageOf(handles: TlHandles, wire: String): TLRPC.Message =
        PluginWrites.readValue(handles, wire) as? TLRPC.Message
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

    private val MIME_BY_EXTENSION = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "mp4" to "video/mp4", "webm" to "video/webm", "mp3" to "audio/mpeg",
        "ogg" to "audio/ogg", "opus" to "audio/ogg", "pdf" to "application/pdf", "txt" to "text/plain",
        "json" to "application/json", "zip" to "application/zip",
    )

    internal fun mimeOfName(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MIME_BY_EXTENSION[extension] ?: "application/octet-stream"
    }

    /** stock reports every transfer through [NotificationCenter] keyed by the name it gave the file. Added and removed on the ui thread, the only thread that centre may be touched from */
    private fun observe(transfer: Transfer) {
        live.add(transfer.call.session, transfer)
        AndroidUtilities.runOnUIThread {
            val centre = NotificationCenter.getInstance(transfer.call.accountId)
            val observer = NotificationCenter.NotificationCenterDelegate { id, _, args ->
                if (args.isEmpty() || args[0] != transfer.fileName) return@NotificationCenterDelegate
                when (id) {
                    transfer.progressEvent -> report(transfer, longAt(args, 1), longAt(args, 2))
                    transfer.doneEvent -> {
                        release(transfer)
                        transfer.onDone(args.getOrNull(1), null)
                    }
                    transfer.failedEvent -> {
                        release(transfer)
                        transfer.onDone(null, "the transfer failed")
                    }
                }
            }
            transfer.observer = observer
            for (id in transfer.events) centre.addObserver(observer, id)
        }
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
        stopObserving(transfer)
    }

    /**
     * the remove takes the same ui hop the add did, which is the only thing that orders it behind
     * one: [AndroidUtilities.runOnUIThread] always posts, so a transfer torn down before its own add
     * landed still removes the observer that add is about to create. Reading [Transfer.observer]
     * here would find it null and leave the observer holding the dead engine for the process's life.
     */
    private fun stopObserving(transfer: Transfer) {
        AndroidUtilities.runOnUIThread {
            val observer = transfer.observer ?: return@runOnUIThread
            transfer.observer = null
            val centre = NotificationCenter.getInstance(transfer.call.accountId)
            for (id in transfer.events) centre.removeObserver(observer, id)
        }
    }

    /** there is no event for a transfer stock declined to start, so an unloaded plugin's observers would sit on the centre for the life of the process */
    override fun detach(session: PluginSession) {
        val mine = live.take(session)
        if (mine.isEmpty()) return
        for (transfer in mine) stopObserving(transfer)
    }
}
