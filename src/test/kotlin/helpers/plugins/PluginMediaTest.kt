package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.telegram.PluginMedia
import desu.inugram.helpers.plugins.telegram.PluginWrites
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
import org.telegram.messenger.NotificationCenter
import org.telegram.tgnet.TLRPC

class PluginMediaTest {
    private val alice = 222L
    private lateinit var scratch: File

    @Before
    fun setUp() {
        resetBridge()
        TestApp.signIn(0, id = 100L)
        TestApp.putUser(TLRPC.TL_user().apply { id = alice; access_hash = 7L })
        scratch = Files.createTempDirectory("inu-media-test").toFile()
    }

    private fun granted(vararg extra: String) =
        startPlugin("media", "account.read(messages)", "account.write(send)", *extra)

    private fun noMedia(): TLRPC.TL_message = TLRPC.TL_message().apply {
        id = 1
        message = "hi"
    }.synced()

    private fun messageWire(message: TLRPC.Message): String =
        PluginWire.encodeJson(TlJson.toJson(message, TlFilter.Policy(takeover = true, drafts = true)).toString())

    private fun onDisk(name: String, content: String): File =
        File(scratch, name).apply { writeText(content) }

    private fun centre() = NotificationCenter.getInstance(0)

    private fun readDocumentAttachName(message: TLRPC.Message): String =
        FileLoader.getAttachFileName((message.media as TLRPC.TL_messageMediaDocument).document)

    @Test
    fun getmessagefile_answers_where_the_media_would_land_and_null_when_there_is_none() {
        val plugin = granted()
        val message = withMedia()
        TestApp.fileLoader(0).paths[message.id] = onDisk("note.txt", "hello world")

        val wire = plugin.js.listener!!.messageFile(0, messageWire(message))
        val json = JSONObject((PluginWire.decode(wire) as PluginWire.Value.Json).json)
        assertTrue(json.getString("path").endsWith("note.txt"), json.getString("path"))
        assertTrue(json.getBoolean("exists"))

        assertEquals("N", plugin.js.listener!!.messageFile(0, messageWire(noMedia())))

        TestApp.fileLoader(0).paths[message.id] = File(scratch, "missing.txt")
        val missing = JSONObject(
            (PluginWire.decode(plugin.js.listener!!.messageFile(0, messageWire(message))) as PluginWire.Value.Json).json,
        )
        assertTrue(missing.getString("path").endsWith("missing.txt"))
        assertTrue(!missing.getBoolean("exists"), "a path that is not there must not read as downloaded")
    }

    @Test
    fun media_already_on_disk_resolves_without_asking_the_loader_for_it() {
        val plugin = granted()
        val message = withMedia()
        TestApp.fileLoader(0).paths[message.id] = onDisk("note.txt", "hello world")

        assertNull(write(plugin, PluginWrites.OP_DOWNLOAD_MEDIA, JSONObject(), arrayOf(messageWire(message))))
        settle()

        assertTrue(TestApp.fileLoader(0).loads.isEmpty(), "a file already there was downloaded again")
        val json = JSONObject((PluginWire.decode(plugin.js.writeResults.last().resultWire) as PluginWire.Value.Json).json)
        assertEquals(11L, json.getLong("size"))
        assertEquals("note.txt", json.getString("name"))
        assertEquals("text/plain", json.getString("mime"))
    }

    @Test
    fun a_download_is_the_app_s_own_reports_its_progress_and_lets_go_when_it_lands() {
        val plugin = granted()
        val message = withMedia()
        val target = File(scratch, "note.txt")
        TestApp.fileLoader(0).paths[message.id] = target

        assertNull(write(plugin, PluginWrites.OP_DOWNLOAD_MEDIA, JSONObject(), arrayOf(messageWire(message))))
        settle()
        val load = TestApp.fileLoader(0).loads.single()
        assertTrue(load.what is TLRPC.Document, "a document downloads as a document")
        assertTrue(load.parent is TLRPC.Message, "the message is the parent, or a stale file reference cannot be refreshed")
        assertTrue(pluginObserverCount(centre()) > 0, "nothing is listening for the transfer")

        val name = readDocumentAttachName(message)
        centre().postOnUi(NotificationCenter.fileLoadProgressChanged, name, 4L, 11L)
        centre().postOnUi(NotificationCenter.fileLoadProgressChanged, name, 11L, 11L)
        settle()
        assertEquals(
            listOf(4L to 11L, 11L to 11L),
            plugin.js.writeProgress.map { it.loaded to it.total },
            "every report reaches native, which is what coalesces them",
        )
        assertTrue(plugin.js.writeResults.isEmpty(), "progress settled the transfer")

        target.writeText("hello world")
        centre().postOnUi(NotificationCenter.fileLoaded, name, target)
        settle()
        val json = JSONObject((PluginWire.decode(plugin.js.writeResults.single().resultWire) as PluginWire.Value.Json).json)
        assertEquals(target.absolutePath, json.getString("path"))
        assertEquals(0, pluginObserverCount(centre()), "a finished transfer kept listening")
    }

    @Test
    fun a_report_for_somebody_else_s_file_is_not_this_transfer_s() {
        val plugin = granted()
        val message = withMedia()
        TestApp.fileLoader(0).paths[message.id] = File(scratch, "note.txt")
        assertNull(write(plugin, PluginWrites.OP_DOWNLOAD_MEDIA, JSONObject(), arrayOf(messageWire(message))))

        centre().postOnUi(NotificationCenter.fileLoadProgressChanged, "someone-else.bin", 4L, 11L)
        settle()
        assertTrue(plugin.js.writeProgress.isEmpty(), "another file's progress was reported as this one's")
    }

    @Test
    fun a_failed_download_rejects_and_lets_go() {
        val plugin = granted()
        val message = withMedia()
        TestApp.fileLoader(0).paths[message.id] = File(scratch, "note.txt")
        assertNull(write(plugin, PluginWrites.OP_DOWNLOAD_MEDIA, JSONObject(), arrayOf(messageWire(message))))

        centre().postOnUi(NotificationCenter.fileLoadFailed, readDocumentAttachName(message), 0)
        settle()
        assertPluginError("internal", plugin.js.writeResults.single().resultWire)
        assertEquals(0, pluginObserverCount(centre()))
    }

    @Test
    fun a_message_with_no_media_is_refused_before_anything_is_asked_of_the_loader() {
        val plugin = granted()
        assertPluginError(
            "invalid-argument",
            write(plugin, PluginWrites.OP_DOWNLOAD_MEDIA, JSONObject(), arrayOf(messageWire(noMedia()))),
        )
        assertTrue(TestApp.fileLoader(0).loads.isEmpty())
    }

    @Test
    fun the_to_file_form_answers_a_path_and_nothing_a_blob_could_be_minted_from() {
        val plugin = granted()
        val message = withMedia()
        TestApp.fileLoader(0).paths[message.id] = onDisk("note.txt", "hello world")
        assertNull(write(plugin, PluginWrites.OP_DOWNLOAD_MEDIA_TO_FILE, JSONObject(), arrayOf(messageWire(message))))
        settle()
        val json = JSONObject((PluginWire.decode(plugin.js.writeResults.single().resultWire) as PluginWire.Value.Json).json)
        assertTrue(json.has("path"))
        assertTrue(!json.has("size") && !json.has("mime"), "the path form must not describe a File")
    }

    private fun stagedWire(file: File, name: String, mime: String): String =
        "F" + JSONObject().put("path", file.absolutePath).put("name", name).put("mime", mime).toString()

    private fun uploaded(path: String, id: Long = 5L) {
        val input = TLRPC.TL_inputFile().apply { this.id = id; parts = 1 }
        centre().postOnUi(NotificationCenter.fileUploaded, path, input, null, null, null, 5L)
    }

    @Test
    fun without_a_name_of_its_own_it_keeps_the_content_s() {
        val plugin = granted()
        val staged = onDisk("transfer-1.bin", "12345")
        assertNull(
            write(plugin, PluginWrites.OP_UPLOAD_FILE, JSONObject(), arrayOf(stagedWire(staged, "payload.bin", ""))),
        )
        settle()
        assertEquals(listOf(staged.absolutePath), TestApp.fileLoader(0).uploads)
        uploaded(staged.absolutePath)
        settle()
        val json = JSONObject((PluginWire.decode(plugin.js.writeResults.single().resultWire) as PluginWire.Value.Json).json)
        assertEquals("inputFile", json.getString("_"))
        assertEquals("payload.bin", json.getString("name"))
    }

    @Test
    fun an_upload_that_failed_rejects_rather_than_resolving_with_nothing() {
        val plugin = granted()
        val staged = onDisk("transfer-1.bin", "12345")
        assertNull(
            write(plugin, PluginWrites.OP_UPLOAD_FILE, JSONObject(), arrayOf(stagedWire(staged, "payload.bin", ""))),
        )
        settle()
        centre().postOnUi(NotificationCenter.fileUploadFailed, staged.absolutePath, false)
        settle()
        assertPluginError("internal", plugin.js.writeResults.single().resultWire)
        assertEquals(0, pluginObserverCount(centre()))
    }

    @Test
    fun an_upload_reports_its_progress_the_way_a_download_does() {
        val plugin = granted()
        val staged = onDisk("transfer-1.bin", "12345")
        assertNull(
            write(plugin, PluginWrites.OP_UPLOAD_FILE, JSONObject(), arrayOf(stagedWire(staged, "payload.bin", ""))),
        )
        settle()
        centre().postOnUi(
            NotificationCenter.fileUploadProgressChanged, staged.absolutePath, 2L, 5L, false,
        )
        settle()
        assertEquals(listOf(2L to 5L), plugin.js.writeProgress.map { it.loaded to it.total })
    }

    @Test
    fun two_uploads_of_one_path_both_settle_because_stock_only_ever_runs_one_operation() {
        val plugin = granted()
        val staged = onDisk("transfer-1.bin", "12345")
        val wire = stagedWire(staged, "payload.bin", "")
        val named = { name: String -> JSONObject().put("fileName", name) }
        assertNull(write(plugin, PluginWrites.OP_UPLOAD_FILE, named("one.dat"), arrayOf(wire), requestId = 1))
        assertNull(write(plugin, PluginWrites.OP_UPLOAD_FILE, named("two.dat"), arrayOf(wire), requestId = 2))
        settle()
        // stock runs one operation per path (`uploadOperationPaths`), which the recorder cannot model
        assertEquals(setOf(staged.absolutePath), TestApp.fileLoader(0).uploads.toSet())

        uploaded(staged.absolutePath)
        settle()
        val names = plugin.js.writeResults.associate {
            it.requestId to JSONObject((PluginWire.decode(it.resultWire) as PluginWire.Value.Json).json).getString("name")
        }
        assertEquals(
            mapOf(1L to "one.dat", 2L to "two.dat"),
            names,
            "one operation reports one instance to every waiter, so naming it in place renames somebody else's",
        )
        assertEquals(0, pluginObserverCount(centre()))
    }

    @Test
    fun a_staged_file_that_is_not_there_is_not_uploaded() {
        val plugin = granted()
        val wire = stagedWire(File(scratch, "gone.bin"), "gone.bin", "")
        assertPluginError("not-found", write(plugin, PluginWrites.OP_UPLOAD_FILE, JSONObject(), arrayOf(wire)))
        assertTrue(TestApp.fileLoader(0).uploads.isEmpty())
    }

    // stock runs one upload operation per file for every caller waiting on it
    @Test
    fun the_send_a_media_api_makes_is_the_plugin_s_and_the_transfer_it_causes_is_the_app_s() {
        val watcher = startPlugin("watcher", "interceptRpc(messages.sendMedia)", "interceptRpc(upload.saveFilePart)")
        assertNull(watcher.interceptRpc("messages.sendMedia", "upload.saveFilePart"))
        val plugin = granted()
        val staged = onDisk("transfer-1.bin", "12345")

        assertNull(
            write(
                plugin,
                PluginWrites.OP_SEND_MEDIA,
                JSONObject().put("peer", "D$alice").put("text", "").put("optimistic", false),
                arrayOf(stagedWire(staged, "payload.bin", "")),
            ),
        )
        settle()
        connections().sendRequest(TLRPC.TL_upload_saveFilePart(), { _, _ -> })
        settle()
        assertEquals(
            listOf("upload.saveFilePart"),
            watcher.js.dispatches.map { it.method },
            "the transfer is the app's, and a plugin that intercepts uploads must see it",
        )

        uploaded(staged.absolutePath)
        settle()
        assertEquals(
            listOf("upload.saveFilePart"),
            watcher.js.dispatches.map { it.method },
            "the send walked into a chain",
        )
        assertTrue(connections().lastSent()!!.request is TLRPC.TL_messages_sendMedia, "the send did not go out")
    }

    @Test
    fun a_transfer_still_running_when_its_plugin_goes_away_lets_go_of_the_centre_and_never_settles() {
        val plugin = granted()
        val message = withMedia()
        TestApp.fileLoader(0).paths[message.id] = File(scratch, "note.txt")
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
        settle()
        assertTrue(pluginObserverCount(centre()) >= 2, "neither transfer is listening")

        PluginMedia.detach(plugin.session!!)
        settle()
        assertEquals(0, pluginObserverCount(centre()), "a transfer stock never started has no event to settle it")

        File(scratch, "note.txt").writeText("hello world")
        centre().postOnUi(NotificationCenter.fileLoaded, readDocumentAttachName(message), File(scratch, "note.txt"))
        settle()
        assertTrue(plugin.js.writeResults.isEmpty(), "a dropped transfer answered anyway")
    }

    @Test
    fun sendmedia_uploads_first_and_posts_the_uploaded_document() {
        val plugin = granted()
        val staged = onDisk("transfer-1.bin", "12345")

        assertNull(
            write(
                plugin,
                PluginWrites.OP_SEND_MEDIA,
                JSONObject().put("peer", "D$alice").put("text", "look").put("optimistic", false),
                arrayOf(stagedWire(staged, "payload.bin", "application/octet-stream")),
            ),
        )
        settle()
        assertNull(connections().lastSent(), "the send went out before its file was up")
        uploaded(staged.absolutePath)
        settle()
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
    fun an_image_goes_up_as_a_photo_unless_asdocument_says_otherwise() {
        val plugin = granted()
        val first = onDisk("transfer-1.bin", "12345")
        val second = onDisk("transfer-2.bin", "12345")

        assertNull(
            write(
                plugin,
                PluginWrites.OP_SEND_MEDIA,
                JSONObject().put("peer", "D$alice").put("text", "").put("optimistic", false),
                arrayOf(stagedWire(first, "cat.jpg", "image/jpeg")),
            ),
        )
        settle()
        uploaded(first.absolutePath)
        settle()
        assertTrue(
            (connections().lastSent()!!.request as TLRPC.TL_messages_sendMedia).media is TLRPC.TL_inputMediaUploadedPhoto,
        )

        assertNull(
            write(
                plugin,
                PluginWrites.OP_SEND_MEDIA,
                JSONObject().put("peer", "D$alice").put("text", "").put("asDocument", true).put("optimistic", false),
                arrayOf(stagedWire(second, "cat.jpg", "image/jpeg")),
                requestId = 2,
            ),
        )
        settle()
        uploaded(second.absolutePath)
        settle()
        assertTrue(
            (connections().lastSent()!!.request as TLRPC.TL_messages_sendMedia).media is TLRPC.TL_inputMediaUploadedDocument,
        )
    }

    @Test
    fun a_send_into_a_secret_chat_is_refused_before_anything_is_uploaded() {
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
        assertTrue(TestApp.fileLoader(0).uploads.isEmpty(), "the upload started before the peer was checked")
    }

    @Test
    fun an_album_is_one_grouped_request_rather_than_a_send_per_item() {
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
        settle()
        uploaded(first.absolutePath, id = 5L)
        uploaded(second.absolutePath, id = 6L)
        settle()
        val request = connections().lastSent()!!.request as TLRPC.TL_messages_sendMultiMedia
        assertEquals(2, request.multi_media.size)
        assertEquals("first", request.multi_media[0].message)
        assertTrue(
            request.multi_media[0].random_id != request.multi_media[1].random_id,
            "two items sharing a random_id is one message, not an album",
        )
    }
}
