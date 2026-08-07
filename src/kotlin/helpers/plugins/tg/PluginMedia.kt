package desu.inugram.helpers.plugins.tg

import desu.inugram.core.plugins.ScopeMatch
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginDispatch
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.tg.PluginWrites.Call
import desu.inugram.helpers.plugins.tg.PluginWrites.refuse
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlJson
import java.io.File
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
object PluginMedia {
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
     * through it the engine - would outlive the plugin. Touched from globalQueue and the ui thread.
     */
    private val live = HashMap<Plugin, MutableSet<Transfer>>()

    fun messageFile(plugin: Plugin, engine: QuickJs, accountId: Int, value: String): String {
        if (!plugin.permissions.allows("account.read", "messages", ScopeMatch.EXACT)) {
            return PluginWire.encodeNotGranted("account.read", "messages")
        }
        return try {
            val message = messageOf(engine, value)
            if (mediaFile(message) == null) return PluginWire.encodeNull()
            val path = FileLoader.getInstance(accountId).getPathToMessage(message)
                ?: return PluginWire.encodeNull()
            val json = JSONObject()
            json.put("path", path.absolutePath)
            json.put("exists", path.exists() && path.length() > 0)
            PluginWire.encodeJson(json.toString())
        } catch (e: PluginWrites.Refused) {
            e.wire
        } catch (e: Exception) {
            PluginWire.encodePluginError("internal", "getMessageFile: ${e.message ?: e.toString()}")
        }
    }

    internal fun download(call: Call, toFile: Boolean): String? {
        val message = messageOf(call.engine, call.values.firstOrNull() ?: refuse("invalid-argument", "no message"))
        val media = mediaFile(message) ?: refuse("invalid-argument", "this message has no media to download")
        val loader = FileLoader.getInstance(call.accountId)
        val already = loader.getPathToMessage(message)
        if (already != null && already.exists() && already.length() > 0) {
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

    private fun downloadName(message: TLRPC.Message, file: File): String {
        val document = (message.media as? TLRPC.TL_messageMediaDocument)?.document
        val named = document?.attributes?.firstNotNullOfOrNull { (it as? TLRPC.TL_documentAttributeFilename)?.file_name }
        return named ?: file.name
    }

    private fun mimeOfMessage(message: TLRPC.Message, fileName: String): String {
        val document = (message.media as? TLRPC.TL_messageMediaDocument)?.document
        return document?.mime_type?.takeIf { it.isNotEmpty() } ?: mimeOfName(fileName)
    }

    internal fun uploadFile(call: Call): String? {
        val source = stagedFile(call, call.values.firstOrNull() ?: refuse("invalid-argument", "no file"))
        upload(call, source) { input ->
            PluginWrites.answer(call) {
                if (input == null) PluginWire.encodePluginError("internal", "uploadFile: the upload failed")
                else PluginWire.encodeJson(TlJson.toJson(input, TlFilter.policyFor(call.plugin.permissions)).toString())
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
            val name = requested.ifEmpty { source.name.ifEmpty { source.path.name } }
            done(if (input == null) null else named(input, name))
        }
        observe(transfer)
        AndroidUtilities.runOnUIThread {
            FileLoader.getInstance(call.accountId)
                .uploadFile(source.path.absolutePath, false, false, ConnectionsManager.FileTypeFile)
        }
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
        val medias = arrayOfNulls<TLRPC.InputMedia>(count)
        var remaining = count
        for (index in 0 until count) {
            val describe = items?.optJSONObject(index) ?: call.json
            resolveMedia(call, call.values[index], describe) { media ->
                // back onto the engine's queue before the counter is touched: an item already uploaded answers inline while one still going up answers from the ui thread
                Utilities.globalQueue.postRunnable {
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
                        } catch (e: PluginWrites.Refused) {
                            PluginWrites.answer(call) { e.wire }
                        }
                    }
                }
            }
        }
        return null
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

    private fun resolveMedia(call: Call, wire: String, describe: JSONObject, done: (TLRPC.InputMedia?) -> Unit) {
        if (!wire.startsWith(FILE_TAG)) {
            when (val given = PluginWrites.tlValue(call.engine, wire)) {
                is TLRPC.InputMedia -> return done(given)
                is TLRPC.InputFile -> return done(uploadedMedia(given, describe, "", ""))
                else -> refuse("invalid-argument", "sendMedia: '${given.javaClass.simpleName}' is not a file")
            }
        }
        val source = stagedFile(call, wire)
        upload(call, source) { input ->
            if (input == null) done(null)
            else {
                val name = describe.optString("fileName").ifEmpty { source.name.ifEmpty { source.path.name } }
                input.name = name
                done(uploadedMedia(input, describe, name, source.mime))
            }
        }
    }

    /** an image goes up the way the ui would send it unless `asDocument` says otherwise; webp is the exception stock makes too, a sticker being a document however it looks */
    private fun uploadedMedia(
        input: TLRPC.InputFile,
        describe: JSONObject,
        name: String,
        declared: String,
    ): TLRPC.InputMedia {
        val mime = declared.ifEmpty { mimeOfName(name) }
        val asDocument = describe.optBoolean("asDocument", false)
        if (!asDocument && mime.startsWith("image/") && mime != "image/webp") {
            return TLRPC.TL_inputMediaUploadedPhoto().apply { file = input }
        }
        return TLRPC.TL_inputMediaUploadedDocument().apply {
            file = input
            mime_type = mime
            force_file = asDocument
            if (name.isNotEmpty()) {
                attributes.add(TLRPC.TL_documentAttributeFilename().apply { file_name = name })
            }
        }
    }

    private const val FILE_TAG = "F"

    private class Source(val path: File, val name: String, val mime: String)

    private fun stagedFile(call: Call, wire: String): Source {
        if (!wire.startsWith(FILE_TAG)) refuse("invalid-argument", "expected a Blob, bytes or { path }")
        val json = JSONObject(wire.substring(1))
        val path = File(json.optString("path"))
        if (!path.exists() || !path.isFile) refuse("not-found", "no such file: ${path.absolutePath}")
        if (path.length() == 0L) refuse("invalid-argument", "this file is empty")
        // a path the plugin named is read here and nowhere else; the grant that let it name one was checked in `writes.rs`
        return Source(path, json.optString("name"), json.optString("mime"))
    }

    // a transfer only names its message (stock reads the media off it and refreshes its file reference from it), so a read-only handle is the normal argument here
    private fun messageOf(engine: QuickJs, wire: String): TLRPC.Message =
        PluginWrites.readValue(engine, wire) as? TLRPC.Message
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

    private fun mimeOfName(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MIME_BY_EXTENSION[extension] ?: "application/octet-stream"
    }

    /** stock reports every transfer through [NotificationCenter] keyed by the name it gave the file. Added and removed on the ui thread, the only thread that centre may be touched from */
    private fun observe(transfer: Transfer) {
        synchronized(live) { live.getOrPut(transfer.call.plugin) { LinkedHashSet() }.add(transfer) }
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
        val engine = transfer.call.engine
        PluginDispatch.onEngine(transfer.call.plugin, engine) {
            engine.writeProgress(transfer.call.requestId, loaded, total)
        }
    }

    private fun longAt(args: Array<Any?>, index: Int): Long = when (val value = args.getOrNull(index)) {
        is Long -> value
        is Int -> value.toLong()
        else -> 0L
    }

    private fun release(transfer: Transfer) {
        synchronized(live) {
            val mine = live[transfer.call.plugin] ?: return@synchronized
            mine.remove(transfer)
            if (mine.isEmpty()) live.remove(transfer.call.plugin)
        }
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
    internal fun detach(plugin: Plugin) {
        val mine = synchronized(live) { live.remove(plugin) } ?: return
        for (transfer in mine) stopObserving(transfer)
    }
}
