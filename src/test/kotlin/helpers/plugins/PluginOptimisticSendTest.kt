package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.io.PluginTransfers
import desu.inugram.helpers.plugins.telegram.PluginWrites
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_update

class PluginOptimisticSendTest {
    private val self = 100L
    private val alice = 222L
    private lateinit var scratch: File

    @Before
    fun setUp() {
        scratch = Files.createTempDirectory("inu-optimistic-test").toFile()
        resetBridge()
        TestApp.signInAs(0, user(self))
        TestApp.putUser(user(alice))
    }

    private fun granted(vararg extra: String) = startPlugin(
        "optimistic",
        "account.write(send)",
        "account.read(messages)",
        *extra,
    )

    private fun send(peer: String = "D$alice", text: String = "hi") = JSONObject()
        .put("peer", peer)
        .put("text", text)

    private fun drawn(): List<MessageObject> {
        val map = MessagesController.getInstance(0).dialogMessage
        return (0 until map.size()).flatMap { at -> map.valueAt(at).orEmpty().filterNotNull() }
    }

    private fun localMessages(): List<TLRPC.Message> = drawn().map { it.messageOwner }

    private fun original(id: Int, text: String) = TLRPC.TL_message().apply {
        this.id = id
        message = text
        dialog_id = alice
        peer_id = TLRPC.TL_peerUser().apply { user_id = alice }
    }

    @Test
    fun a_send_is_drawn_before_it_goes_out_and_its_request_is_leased_out_of_the_chains() {
        val watcher = startPlugin("watcher", "interceptRpc(messages.sendMessage)")
        assertNull(watcher.interceptRpc("messages.sendMessage"))
        val plugin = granted()
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send()))
        settle()

        val request = connections().lastSent()?.request
        assertTrue(request is TLRPC.TL_messages_sendMessage, "the composer never sent: $request")
        assertEquals("hi", request.message)
        assertTrue(
            localMessages().any { it.message == "hi" && it.id < 0 },
            "no local message was drawn for the send",
        )
        assertTrue(watcher.js.dispatches.isEmpty(), "the plugin's own send re-entered the chain")
    }

    @Test
    fun the_promise_answers_with_the_message_the_server_made() {
        val plugin = granted()
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send()))
        settle()
        assertNull(plugin.js.writeResults.lastOrNull(), "the promise settled before the server answered")

        val sent = assertNotNull(connections().lastSent())
        val request = sent.request as TLRPC.TL_messages_sendMessage
        val message = TLRPC.TL_message().apply {
            id = 4242
            this.message = "hi"
            peer_id = TLRPC.TL_peerUser().apply { user_id = alice }
            out = true
        }
        val updates = TLRPC.TL_updates().apply {
            // stock maps its local message onto the real one by random id before the new message
            updates.add(
                TL_update.TL_updateMessageID().apply {
                    id = message.id
                    random_id = request.random_id
                },
            )
            updates.add(TL_update.TL_updateNewMessage().apply { this.message = message })
        }
        sent.answer(updates, null)

        val wire = awaitValue("the promise never settled") { plugin.js.writeResults.lastOrNull()?.resultWire }
        assertTrue(wire.isNotEmpty() && !wire.startsWith("E"), "the send failed: $wire")
    }

    @Test
    fun opting_out_or_naming_a_send_as_peer_keeps_the_request_path() {
        val plugin = granted("account.read(peers)")
        for ((requestId, arg) in listOf(1L to send().put("optimistic", false), 2L to send().put("sendAs", "S"))) {
            connections().sent.clear()
            assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, arg, requestId = requestId))
            settle()

            assertNotNull(connections().lastSent()?.request as? TLRPC.TL_messages_sendMessage, "nothing was sent for $arg")
            assertTrue(localMessages().none { it.message == "hi" }, "a bubble was drawn for a request send: $arg")
        }
        val request = connections().lastSent()!!.request as TLRPC.TL_messages_sendMessage
        assertNotNull(request.send_as, "the composer was used for a send it cannot say")
    }

    // stock renders the quote off the object the composer was handed
    @Test
    fun a_reply_carries_the_message_it_replies_to_and_not_only_its_id() {
        val plugin = granted()
        TestApp.cacheDialogMessage(0, alice, original(77, "the original"))

        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send().put("replyTo", "77")))
        settle()

        val local = assertNotNull(drawn().firstOrNull { it.id < 0 }, "nothing was drawn")
        assertEquals(77, local.replyMessageObject?.id, "the reply was named by id alone")
        assertEquals("the original", local.replyMessageObject?.messageOwner?.message)
    }

    @Test
    fun a_reply_to_a_message_the_app_never_saw_keeps_the_request_path() {
        val plugin = granted()

        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send().put("replyTo", "4040")))
        val request = awaitValue("nothing was sent") { connections().lastSent()?.request as? TLRPC.TL_messages_sendMessage }
        assertEquals(4040, (request.reply_to as TLRPC.TL_inputReplyToMessage).reply_to_msg_id)
        assertTrue(localMessages().none { it.id < 0 }, "a quote with nothing in it was drawn")
    }

    // a local message keeps pointing at the file it was sent from, and stock's loader reads the
    // file name to tell an mp4 is an animation
    @Test
    fun media_is_handed_to_the_app_to_upload_rather_than_uploaded_first() {
        val plugin = granted("fs")
        val staged = File(scratch, "optimistic.bin").apply { writeText("12345") }
        val wire = "F" + JSONObject()
            .put("path", staged.absolutePath)
            .put("name", "payload.mp4")
            .put("mime", "video/mp4")
            .toString()

        assertNull(write(plugin, PluginWrites.OP_SEND_MEDIA, send(text = "look"), arrayOf(wire)))
        settle()

        val uploaded = assertNotNull(
            TestApp.fileLoader(0).uploads.singleOrNull(),
            "the app was asked to upload ${TestApp.fileLoader(0).uploads}",
        )
        assertNotEquals(staged.absolutePath, uploaded, "the composer was handed the file rust staged")
        assertTrue(uploaded.endsWith(".mp4"), "the copy lost the name the send gave it: $uploaded")
        assertEquals("12345", File(uploaded).readText())
        val local = assertNotNull(
            localMessages().firstOrNull { it.id < 0 },
            "no local message was drawn for the media send",
        )
        assertEquals(uploaded, local.attachPath, "the drawn message reads its media from somewhere else")
    }

    @Test
    fun an_unnamed_mp4_blob_gets_an_animation_extension() {
        val plugin = granted("fs")
        val staged = File(scratch, "transfer-1.bin").apply { writeText("12345") }
        val wire = "F" + JSONObject()
            .put("path", staged.absolutePath)
            .put("name", "")
            .put("mime", "video/mp4")
        assertNull(write(plugin, PluginWrites.OP_SEND_MEDIA, send(text = "look"), arrayOf(wire)))
        settle()
        val uploaded = TestApp.fileLoader(0).uploads.single()
        assertTrue(uploaded.endsWith(".mp4"), uploaded)
        val local = localMessages().first { it.id < 0 }
        assertEquals(uploaded, local.attachPath)
        val filename = local.media.document.attributes.filterIsInstance<TLRPC.TL_documentAttributeFilename>().single()
        assertEquals("transfer-1.mp4", filename.file_name)
    }

    @Test
    fun a_staged_transfer_is_moved_to_the_composer_rather_than_copied() {
        val plugin = granted("fs")
        val staged = File(PluginTransfers.dirFor(plugin.id), "transfer-7.bin").apply { writeText("12345") }
        val wire = "F" + JSONObject()
            .put("path", staged.absolutePath)
            .put("name", "payload.mp4")
            .put("mime", "video/mp4")
            .toString()

        assertNull(write(plugin, PluginWrites.OP_SEND_MEDIA, send(text = "look"), arrayOf(wire)))
        settle()

        val uploaded = assertNotNull(TestApp.fileLoader(0).uploads.singleOrNull(), "nothing was uploaded")
        assertFalse(staged.exists(), "the staged transfer was copied rather than taken")
        assertTrue(uploaded.endsWith(".mp4"), "the taken file lost the name the send gave it: $uploaded")
        assertEquals("12345", File(uploaded).readText())
    }

    @Test
    fun a_path_the_plugin_named_is_uploaded_from_where_it_is() {
        val plugin = granted("fs")
        val own = File(scratch, "clip.mp4").apply { writeText("12345") }
        val wire = "F" + JSONObject()
            .put("path", own.absolutePath)
            .put("name", "clip.mp4")
            .put("mime", "video/mp4")
            .toString()

        assertNull(write(plugin, PluginWrites.OP_SEND_MEDIA, send(text = "look"), arrayOf(wire)))
        settle()

        assertEquals(listOf(own.absolutePath), TestApp.fileLoader(0).uploads, "the plugin's own file was copied")
        assertTrue(own.exists(), "the plugin's own file was moved")
        assertEquals(own.absolutePath, localMessages().first { it.id < 0 }.attachPath)
    }
}
