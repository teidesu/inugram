package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.tg.PluginMedia
import desu.inugram.helpers.plugins.tg.PluginWrites
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlJson
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.FileLoader
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC

/**
 * The media transfers: what `getMessageFile` answers, how a download is observed, and that a
 * finished transfer lets go of the notification centre it registered with.
 */
class PluginMediaTest {
    private val alice = 222L
    private lateinit var scratch: File

    @Before
    fun setUp() {
        resetBridge()
        val config = UserConfig.getInstance(0)
        config.clientUserId = 100L
        config.currentUser = TLRPC.TL_user().apply { id = 100L }
        MessagesController.getInstance(0).inu_putUser(TLRPC.TL_user().apply { id = alice; access_hash = 7L })
        scratch = Files.createTempDirectory("inu-media-test").toFile()
    }

    private fun granted(vararg extra: String) =
        startPlugin("media", "account.read(messages)", "account.write(send)", *extra)

    private fun document() = TLRPC.TL_document().apply {
        id = 99L
        access_hash = 1L
        dc_id = 2
        size = 11L
        mime_type = "text/plain"
        attributes.add(TLRPC.TL_documentAttributeFilename().apply { file_name = "note.txt" })
    }

    private fun noMedia(): TLRPC.TL_message = TLRPC.TL_message().apply {
        id = 1
        message = "hi"
    }.synced()

    private fun withMedia(id: Int = 4242): TLRPC.TL_message = TLRPC.TL_message().apply {
        this.id = id
        message = ""
        // synced at both levels: a flag bit is per object, and a media whose `document`
        // bit is clear round-trips through TlJson without one
        media = TLRPC.TL_messageMediaDocument().apply { document = document() }.synced()
    }.synced()

    private fun messageWire(message: TLRPC.Message): String =
        PluginWire.encodeJson(TlJson.toJson(message, TlFilter.Policy(takeover = true, drafts = true)).toString())

    private fun write(
        plugin: Plugin,
        op: Int,
        arg: JSONObject,
        values: Array<String>,
        requestId: Long = 1L,
    ): String? = plugin.js.writesListener!!.accountWrite(0, requestId, op, arg.toString(), values)

    private fun onDisk(name: String, content: String): File =
        File(scratch, name).apply { writeText(content) }

    private fun centre() = NotificationCenter.getInstance(0)

    private fun fileNameOf(message: TLRPC.Message): String =
        FileLoader.getAttachFileName((message.media as TLRPC.TL_messageMediaDocument).document)

    @Test
    fun `getMessageFile answers where the media would land, and null when there is none`() {
        val plugin = granted()
        val message = withMedia()
        FileLoader.getInstance(0).inu_paths[message.id] = onDisk("note.txt", "hello world")

        val wire = plugin.js.writesListener!!.messageFile(0, messageWire(message))
        val json = JSONObject((PluginWire.decode(wire) as PluginWire.Value.Json).json)
        assertTrue(json.getString("path").endsWith("note.txt"), json.getString("path"))
        assertTrue(json.getBoolean("exists"))

        assertEquals("N", plugin.js.writesListener!!.messageFile(0, messageWire(noMedia())))
    }

    @Test
    fun `a message whose media has not been downloaded says where it would go`() {
        val plugin = granted()
        val message = withMedia()
        FileLoader.getInstance(0).inu_paths[message.id] = File(scratch, "missing.txt")
        val json = JSONObject(
            (PluginWire.decode(plugin.js.writesListener!!.messageFile(0, messageWire(message))) as PluginWire.Value.Json).json,
        )
        assertTrue(json.getString("path").endsWith("missing.txt"))
        assertTrue(!json.getBoolean("exists"), "a path that is not there must not read as downloaded")
    }

    @Test
    fun `getMessageFile is gated on the messages scope, on the side that owns the data`() {
        val plugin = startPlugin("media", "account.write(send)")
        assertPluginError("not-granted", plugin.js.writesListener!!.messageFile(0, messageWire(withMedia())))
    }

    @Test
    fun `media already on disk resolves without asking the loader for it`() {
        val plugin = granted()
        val message = withMedia()
        FileLoader.getInstance(0).inu_paths[message.id] = onDisk("note.txt", "hello world")

        assertNull(write(plugin, PluginWrites.OP_DOWNLOAD_MEDIA, JSONObject(), arrayOf(messageWire(message))))
        drain()

        assertTrue(FileLoader.getInstance(0).loads.isEmpty(), "a file already there was downloaded again")
        val json = JSONObject((PluginWire.decode(plugin.js.writeResults.last().resultWire) as PluginWire.Value.Json).json)
        assertEquals(11L, json.getLong("size"))
        assertEquals("note.txt", json.getString("name"))
        assertEquals("text/plain", json.getString("mime"))
    }

    @Test
    fun `a download is the app's own, reports its progress and lets go when it lands`() {
        val plugin = granted()
        val message = withMedia()
        val target = File(scratch, "note.txt")
        FileLoader.getInstance(0).inu_paths[message.id] = target

        assertNull(write(plugin, PluginWrites.OP_DOWNLOAD_MEDIA, JSONObject(), arrayOf(messageWire(message))))
        val load = FileLoader.getInstance(0).loads.single()
        assertTrue(load.what is TLRPC.Document, "a document downloads as a document")
        assertTrue(load.parent is TLRPC.Message, "the message is the parent, or a stale file reference cannot be refreshed")
        assertTrue(centre().inu_observerCount() > 0, "nothing is listening for the transfer")

        val name = fileNameOf(message)
        centre().postNotificationName(NotificationCenter.fileLoadProgressChanged, name, 4L, 11L)
        centre().postNotificationName(NotificationCenter.fileLoadProgressChanged, name, 11L, 11L)
        drain()
        assertEquals(
            listOf(4L to 11L, 11L to 11L),
            plugin.js.writeProgress.map { it.loaded to it.total },
            "every report reaches native, which is what coalesces them",
        )
        assertTrue(plugin.js.writeResults.isEmpty(), "progress settled the transfer")

        target.writeText("hello world")
        centre().postNotificationName(NotificationCenter.fileLoaded, name, target)
        drain()
        val json = JSONObject((PluginWire.decode(plugin.js.writeResults.single().resultWire) as PluginWire.Value.Json).json)
        assertEquals(target.absolutePath, json.getString("path"))
        assertEquals(0, centre().inu_observerCount(), "a finished transfer kept listening")
    }

    @Test
    fun `a report for somebody else's file is not this transfer's`() {
        val plugin = granted()
        val message = withMedia()
        FileLoader.getInstance(0).inu_paths[message.id] = File(scratch, "note.txt")
        assertNull(write(plugin, PluginWrites.OP_DOWNLOAD_MEDIA, JSONObject(), arrayOf(messageWire(message))))

        centre().postNotificationName(NotificationCenter.fileLoadProgressChanged, "someone-else.bin", 4L, 11L)
        drain()
        assertTrue(plugin.js.writeProgress.isEmpty(), "another file's progress was reported as this one's")
    }

    @Test
    fun `a failed download rejects and lets go`() {
        val plugin = granted()
        val message = withMedia()
        FileLoader.getInstance(0).inu_paths[message.id] = File(scratch, "note.txt")
        assertNull(write(plugin, PluginWrites.OP_DOWNLOAD_MEDIA, JSONObject(), arrayOf(messageWire(message))))

        centre().postNotificationName(NotificationCenter.fileLoadFailed, fileNameOf(message), 0)
        drain()
        assertPluginError("internal", plugin.js.writeResults.single().resultWire)
        assertEquals(0, centre().inu_observerCount())
    }

    @Test
    fun `a message with no media is refused before anything is asked of the loader`() {
        val plugin = granted()
        assertPluginError(
            "invalid-argument",
            write(plugin, PluginWrites.OP_DOWNLOAD_MEDIA, JSONObject(), arrayOf(messageWire(noMedia()))),
        )
        assertTrue(FileLoader.getInstance(0).loads.isEmpty())
    }

    @Test
    fun `the to-file form answers a path and nothing a Blob could be minted from`() {
        val plugin = granted()
        val message = withMedia()
        FileLoader.getInstance(0).inu_paths[message.id] = onDisk("note.txt", "hello world")
        assertNull(write(plugin, PluginWrites.OP_DOWNLOAD_MEDIA_TO_FILE, JSONObject(), arrayOf(messageWire(message))))
        drain()
        val json = JSONObject((PluginWire.decode(plugin.js.writeResults.single().resultWire) as PluginWire.Value.Json).json)
        assertTrue(json.has("path"))
        assertTrue(!json.has("size") && !json.has("mime"), "the path form must not describe a File")
    }

    private fun stagedWire(file: File, name: String, mime: String): String =
        "F" + JSONObject().put("path", file.absolutePath).put("name", name).put("mime", mime).toString()

    /** what stock's operation posts once the last part is in, for every caller waiting on the path */
    private fun uploaded(path: String, id: Long = 5L) {
        val input = TLRPC.TL_inputFile().apply { this.id = id; parts = 1 }
        centre().postNotificationName(NotificationCenter.fileUploaded, path, input, null, null, null, 5L)
    }

    @Test
    fun `an upload takes the staged path and is named as the plugin asked`() {
        val plugin = granted()
        val staged = onDisk("transfer-1.bin", "12345")

        assertNull(
            write(
                plugin,
                PluginWrites.OP_UPLOAD_FILE,
                JSONObject().put("fileName", "chosen.dat"),
                arrayOf(stagedWire(staged, "payload.bin", "application/octet-stream")),
            ),
        )
        drain()
        assertEquals(listOf(staged.absolutePath), FileLoader.getInstance(0).uploads)

        uploaded(staged.absolutePath)
        drain()
        val json = JSONObject((PluginWire.decode(plugin.js.writeResults.single().resultWire) as PluginWire.Value.Json).json)
        assertEquals("inputFile", json.getString("_"))
        assertEquals("chosen.dat", json.getString("name"))
        assertEquals(0, centre().inu_observerCount(), "a finished upload kept listening")
    }

    @Test
    fun `without a name of its own it keeps the content's`() {
        val plugin = granted()
        val staged = onDisk("transfer-1.bin", "12345")
        assertNull(
            write(plugin, PluginWrites.OP_UPLOAD_FILE, JSONObject(), arrayOf(stagedWire(staged, "payload.bin", ""))),
        )
        drain()
        uploaded(staged.absolutePath)
        drain()
        val json = JSONObject((PluginWire.decode(plugin.js.writeResults.single().resultWire) as PluginWire.Value.Json).json)
        assertEquals("payload.bin", json.getString("name"))
    }

    @Test
    fun `an upload that failed rejects rather than resolving with nothing`() {
        val plugin = granted()
        val staged = onDisk("transfer-1.bin", "12345")
        assertNull(
            write(plugin, PluginWrites.OP_UPLOAD_FILE, JSONObject(), arrayOf(stagedWire(staged, "payload.bin", ""))),
        )
        drain()
        centre().postNotificationName(NotificationCenter.fileUploadFailed, staged.absolutePath, false)
        drain()
        assertPluginError("internal", plugin.js.writeResults.single().resultWire)
        assertEquals(0, centre().inu_observerCount())
    }

    @Test
    fun `an upload reports its progress the way a download does`() {
        val plugin = granted()
        val staged = onDisk("transfer-1.bin", "12345")
        assertNull(
            write(plugin, PluginWrites.OP_UPLOAD_FILE, JSONObject(), arrayOf(stagedWire(staged, "payload.bin", ""))),
        )
        drain()
        centre().postNotificationName(
            NotificationCenter.fileUploadProgressChanged, staged.absolutePath, 2L, 5L, false,
        )
        drain()
        assertEquals(listOf(2L to 5L), plugin.js.writeProgress.map { it.loaded to it.total })
    }

    @Test
    fun `two uploads of one path both settle, because stock only ever runs one operation`() {
        val plugin = granted()
        val staged = onDisk("transfer-1.bin", "12345")
        val wire = stagedWire(staged, "payload.bin", "")
        val named = { name: String -> JSONObject().put("fileName", name) }
        assertNull(write(plugin, PluginWrites.OP_UPLOAD_FILE, named("one.dat"), arrayOf(wire), requestId = 1))
        assertNull(write(plugin, PluginWrites.OP_UPLOAD_FILE, named("two.dat"), arrayOf(wire), requestId = 2))
        drain()
        assertEquals(
            listOf(staged.absolutePath),
            FileLoader.getInstance(0).uploads,
            "stock starts one operation per path, so a second request cannot be waiting on a second",
        )

        uploaded(staged.absolutePath)
        drain()
        val names = plugin.js.writeResults.associate {
            it.requestId to JSONObject((PluginWire.decode(it.resultWire) as PluginWire.Value.Json).json).getString("name")
        }
        assertEquals(
            mapOf(1L to "one.dat", 2L to "two.dat"),
            names,
            "one operation reports one instance to every waiter, so naming it in place renames somebody else's",
        )
        assertEquals(0, centre().inu_observerCount())
    }

    @Test
    fun `a staged file that is not there is not uploaded`() {
        val plugin = granted()
        val wire = stagedWire(File(scratch, "gone.bin"), "gone.bin", "")
        assertPluginError("not-found", write(plugin, PluginWrites.OP_UPLOAD_FILE, JSONObject(), arrayOf(wire)))
        assertTrue(FileLoader.getInstance(0).uploads.isEmpty())
    }

    /**
     * the carve-out `common.d.ts` states: the request a write api sends is the plugin's and stays
     * out of the chains, while the transfer it causes is the app's - stock runs one operation per
     * file for every caller waiting on it, so there is no such thing as this plugin's `upload.
     * saveFilePart`.
     */
    @Test
    fun `the send a media api makes is the plugin's, and the transfer it causes is the app's`() {
        val watcher = startPlugin("watcher", "interceptRpc(messages.sendMedia)", "interceptRpc(upload.saveFilePart)")
        assertNull(watcher.interceptRpc("messages.sendMedia", "upload.saveFilePart"))
        val plugin = granted()
        val staged = onDisk("transfer-1.bin", "12345")

        assertNull(
            write(
                plugin,
                PluginWrites.OP_SEND_MEDIA,
                JSONObject().put("peer", "D$alice").put("text", ""),
                arrayOf(stagedWire(staged, "payload.bin", "")),
            ),
        )
        drain()
        // stock's loader is what sends the parts, from the operation it started above
        connections().sendRequest(TLRPC.TL_upload_saveFilePart(), { _, _ -> })
        drain()
        assertEquals(
            listOf("upload.saveFilePart"),
            watcher.js.dispatches.map { it.method },
            "the transfer is the app's, and a plugin that intercepts uploads must see it",
        )

        uploaded(staged.absolutePath)
        drain()
        assertEquals(
            listOf("upload.saveFilePart"),
            watcher.js.dispatches.map { it.method },
            "the send walked into a chain",
        )
        assertTrue(connections().lastSent()!!.request is TLRPC.TL_messages_sendMedia, "the send did not go out")
    }

    @Test
    fun `a transfer still running when its plugin goes away lets go of the centre`() {
        val plugin = granted()
        val message = withMedia()
        FileLoader.getInstance(0).inu_paths[message.id] = File(scratch, "note.txt")
        assertNull(write(plugin, PluginWrites.OP_DOWNLOAD_MEDIA, JSONObject(), arrayOf(messageWire(message))))
        val staged = onDisk("transfer-1.bin", "12345")
        assertNull(
            write(
                plugin,
                PluginWrites.OP_UPLOAD_FILE,
                JSONObject(),
                arrayOf(stagedWire(staged, "payload.bin", "")),
                requestId = 2,
            ),
        )
        drain()
        assertTrue(centre().inu_observerCount() >= 2, "neither transfer is listening")

        PluginMedia.detach(plugin)
        drain()
        assertEquals(
            0,
            centre().inu_observerCount(),
            "a transfer only settles from an event, and there is no event for one stock never started",
        )
    }

    @Test
    fun `a plugin that came back is not settled by the transfer the one before it started`() {
        val plugin = granted()
        val message = withMedia()
        val target = File(scratch, "note.txt")
        FileLoader.getInstance(0).inu_paths[message.id] = target
        assertNull(write(plugin, PluginWrites.OP_DOWNLOAD_MEDIA, JSONObject(), arrayOf(messageWire(message))))
        drain()

        PluginMedia.detach(plugin)
        drain()
        target.writeText("hello world")
        centre().postNotificationName(NotificationCenter.fileLoaded, fileNameOf(message), target)
        drain()
        assertTrue(plugin.js.writeResults.isEmpty(), "a dropped transfer answered anyway")
    }

    @Test
    fun `PluginManager drops a plugin's transfers wherever it drops the plugin`() {
        val source = bridgeSource("PluginManager.kt").readText()
        val lines = source.lines()
        val drops = lines.withIndex().filter { it.value.trim() == "PluginRpc.detach(plugin)" }
        assertEquals(1, drops.size, "the teardown sequence is one function, so a plugin is dropped one way")
        assertEquals(
            "PluginMedia.detach(plugin)",
            lines[drops[0].index + 1].trim(),
            "a dropped plugin keeps its transfers, and through them its engine",
        )
        assertEquals(
            2,
            Regex("""teardown\(plugin, engine""").findAll(source).count(),
            "the load-failure path and the stop path must both run it",
        )
    }

    @Test
    fun `sendMedia uploads first and posts the uploaded document`() {
        val plugin = granted()
        val staged = onDisk("transfer-1.bin", "12345")

        assertNull(
            write(
                plugin,
                PluginWrites.OP_SEND_MEDIA,
                JSONObject().put("peer", "D$alice").put("text", "look"),
                arrayOf(stagedWire(staged, "payload.bin", "application/octet-stream")),
            ),
        )
        drain()
        assertNull(connections().lastSent(), "the send went out before its file was up")
        uploaded(staged.absolutePath)
        drain()
        val request = connections().lastSent()!!.request as TLRPC.TL_messages_sendMedia
        assertEquals("look", request.message)
        val media = request.media as TLRPC.TL_inputMediaUploadedDocument
        assertEquals(5L, media.file.id)
        assertEquals("application/octet-stream", media.mime_type)
        assertEquals(
            "payload.bin",
            (media.attributes.single() as TLRPC.TL_documentAttributeFilename).file_name,
        )
    }

    @Test
    fun `an image goes up as a photo unless asDocument says otherwise`() {
        val plugin = granted()
        val first = onDisk("transfer-1.bin", "12345")
        val second = onDisk("transfer-2.bin", "12345")

        assertNull(
            write(
                plugin,
                PluginWrites.OP_SEND_MEDIA,
                JSONObject().put("peer", "D$alice").put("text", ""),
                arrayOf(stagedWire(first, "cat.jpg", "image/jpeg")),
            ),
        )
        drain()
        uploaded(first.absolutePath)
        drain()
        assertTrue(
            (connections().lastSent()!!.request as TLRPC.TL_messages_sendMedia).media is TLRPC.TL_inputMediaUploadedPhoto,
        )

        assertNull(
            write(
                plugin,
                PluginWrites.OP_SEND_MEDIA,
                JSONObject().put("peer", "D$alice").put("text", "").put("asDocument", true),
                arrayOf(stagedWire(second, "cat.jpg", "image/jpeg")),
                requestId = 2,
            ),
        )
        drain()
        uploaded(second.absolutePath)
        drain()
        assertTrue(
            (connections().lastSent()!!.request as TLRPC.TL_messages_sendMedia).media is TLRPC.TL_inputMediaUploadedDocument,
        )
    }

    @Test
    fun `a send into a secret chat is refused before anything is uploaded`() {
        val plugin = granted()
        val staged = onDisk("transfer-1.bin", "12345")
        val secret = 0x4000000000000000L or 7L
        assertPluginError(
            "forbidden",
            write(
                plugin,
                PluginWrites.OP_SEND_MEDIA,
                JSONObject().put("peer", "D$secret").put("text", ""),
                arrayOf(stagedWire(staged, "payload.bin", "")),
            ),
        )
        assertTrue(FileLoader.getInstance(0).uploads.isEmpty(), "the upload started before the peer was checked")
    }

    @Test
    fun `an album is one grouped request rather than a send per item`() {
        val plugin = granted()
        val first = onDisk("transfer-1.bin", "12345")
        val second = onDisk("transfer-2.bin", "12345")
        val items = org.json.JSONArray()
        items.put(JSONObject().put("text", "first").put("fileName", "a.bin"))
        items.put(JSONObject().put("text", "").put("fileName", "b.bin"))

        assertNull(
            write(
                plugin,
                PluginWrites.OP_SEND_MULTI_MEDIA,
                JSONObject().put("peer", "D$alice").put("items", items),
                arrayOf(stagedWire(first, "a.bin", ""), stagedWire(second, "b.bin", "")),
            ),
        )
        drain()
        uploaded(first.absolutePath, id = 5L)
        uploaded(second.absolutePath, id = 6L)
        drain()
        val request = connections().lastSent()!!.request as TLRPC.TL_messages_sendMultiMedia
        assertEquals(2, request.multi_media.size)
        assertEquals("first", request.multi_media[0].message)
        assertTrue(
            request.multi_media[0].random_id != request.multi_media[1].random_id,
            "two items sharing a random_id is one message, not an album",
        )
    }
}
