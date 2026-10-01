package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.telegram.PluginRpc
import desu.inugram.helpers.plugins.telegram.PluginSends
import desu.inugram.helpers.plugins.telegram.PluginWrites
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.MessageObject
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.SendMessagesHelper.SendMessageParams
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_update

class PluginSendInterceptTest {
    private val self = 100L
    private val alice = 222L
    private val bob = 333L
    private val drawn = ArrayList<TLRPC.Message>()
    private var observer: NotificationCenter.NotificationCenterDelegate? = null
    private lateinit var scratch: File

    @Before
    fun setUp() {
        resetBridge()
        scratch = Files.createTempDirectory("inu-send-test").toFile()
        TestApp.signInAs(0, user(self))
        TestApp.putUser(user(alice))
        TestApp.putUser(user(bob))
        onUi {
            val delegate = NotificationCenter.NotificationCenterDelegate { _, _, args ->
                @Suppress("UNCHECKED_CAST")
                (args.getOrNull(1) as? ArrayList<MessageObject>)?.mapTo(drawn) { it.messageOwner }
            }
            observer = delegate
            NotificationCenter.getInstance(0).addObserver(delegate, NotificationCenter.didReceiveNewMessages)
        }
    }

    @After
    fun tearDown() = onUi {
        observer?.let { NotificationCenter.getInstance(0).removeObserver(it, NotificationCenter.didReceiveNewMessages) }
        observer = null
    }

    private fun compose(text: String) = onUi {
        SendMessagesHelper.getInstance(0).sendMessage(SendMessageParams.of(text, alice))
    }

    private fun fromBob(id: Int) = MessageObject(0, TLRPC.TL_message().apply {
        this.id = id
        peer_id = TLRPC.TL_peerUser().apply { user_id = bob }
        from_id = TLRPC.TL_peerUser().apply { user_id = bob }
        dialog_id = bob
        message = "forwarded $id"
        date = 1_700_000_000
    }, false, false)

    /** the composer sends its comment, then its forward, in one ui tick */
    private fun sendWithComment(text: String?, vararg ids: Int) = onUi {
        text?.let { SendMessagesHelper.getInstance(0).sendMessage(SendMessageParams.of(it, alice)) }
        SendMessagesHelper.getInstance(0).sendMessage(ArrayList(ids.map(::fromBob)), alice, false, false, true, 0, 0L)
    }

    private fun sendDocument(caption: String) {
        val path = File(scratch, "note.txt").apply { writeText("hello") }.absolutePath
        onUi {
            SendMessagesHelper.prepareSendingDocuments(
                AccountInstance.getInstance(0), arrayListOf(path), arrayListOf(path), null, caption, null, alice,
                null, null, null, null, null, true, 0, null, null, 0, false, 0,
            )
        }
    }

    private fun sentRequests() = connections().sent.map { it.request }.filter {
        it is TLRPC.TL_messages_sendMessage || it is TLRPC.TL_messages_sendMedia ||
            it is TLRPC.TL_messages_sendMultiMedia || it is TLRPC.TL_messages_forwardMessages
    }

    private fun filtered(source: String): String = """{"text":{"source":"$source","flags":""}}"""

    private fun text(value: String) = JSONObject().put("text", value).put("entities", JSONArray())

    private fun uploadedPhoto(id: Long) = TLRPC.TL_inputMediaUploadedPhoto().apply {
        file = TLRPC.TL_inputFile().apply {
            this.id = id
            parts = 1
            name = "$id.jpg"
            md5_checksum = ""
        }
    }

    /** a media send as stock binds it once uploaded, and the message it drew for it */
    private fun bindMediaSend(caption: String): TLRPC.TL_messages_sendMedia {
        val request = TLRPC.TL_messages_sendMedia().apply {
            peer = TLRPC.TL_inputPeerUser().apply { user_id = alice }
            media = uploadedPhoto(1L)
            message = caption
            random_id = 42L
        }
        val message = MessageObject(0, TLRPC.TL_message().apply {
            id = -5
            peer_id = TLRPC.TL_peerUser().apply { user_id = alice }
            dialog_id = alice
            message = caption
            out = true
        }, false, false)
        PluginRpc.bindOptimisticMessage(request, 0, message)
        return request
    }

    @Test
    fun interceptSendMessage_and_interceptRpc_over_the_send_methods_do_not_imply_each_other() {
        assertPluginError("not-granted", startPlugin("p", "interceptRpc(messages.sendMessage)").interceptSendMessage())
        assertPluginError("not-granted", startPlugin("q", "interceptSendMessage").interceptRpc("messages.sendMessage"))
    }

    @Test
    fun a_dropped_send_is_neither_drawn_nor_sent() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())
        plugin.js.onDispatchSend = { plugin.dropVerdict(it.dispatchId) }

        compose("drop me")
        awaitValue("the stage was never asked") { plugin.js.sendDispatches.firstOrNull() }
        Thread.sleep(400)
        settle()

        assertEquals(emptyList(), drawn.map { it.message }, "a dropped send was drawn")
        assertEquals(emptyList(), sentRequests(), "a dropped send reached the network")
    }

    @Test
    fun a_send_is_drawn_and_sent_with_the_text_a_stage_gave_it() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())
        plugin.js.onDispatchSend = { plugin.passSend(it) { message -> message.put("text", text("rewritten")) } }

        compose("hi")
        val sent = awaitValue("the send never went out") { sentRequests().singleOrNull() }

        assertEquals("rewritten", (sent as TLRPC.TL_messages_sendMessage).message)
        assertEquals(listOf("rewritten"), drawn.map { it.message })
        assertEquals("hi", JSONObject(plugin.js.sendDispatches.single().messageJson).getJSONObject("text").getString("text"))
    }

    // the message replied to is read from storage, which outlasts the stage
    @Test
    fun a_reply_a_stage_adds_is_on_the_message_when_it_is_drawn() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())
        plugin.js.onDispatchSend = {
            plugin.passSend(it) { message -> message.put("reply", JSONObject().put("messageId", 77).put("peer", JSONObject.NULL).put("quote", JSONObject.NULL)) }
        }

        compose("hi")
        val message = awaitValue("the message was never drawn") { drawn.firstOrNull() }

        assertEquals(77, message.reply_to?.reply_to_msg_id)
    }

    @Test
    fun a_send_no_stage_would_see_is_drawn_before_the_composer_returns() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage(filterJson = filtered("^\\\\.drop")))
        plugin.js.onDispatchSend = { plugin.dropVerdict(it.dispatchId) }

        onUi {
            SendMessagesHelper.getInstance(0).sendMessage(SendMessageParams.of("ordinary", alice))
            assertEquals(listOf("ordinary"), drawn.map { it.message })
        }
        assertEquals(0, plugin.js.sendDispatches.size)
    }

    @Test
    fun a_send_no_stage_would_see_still_waits_behind_a_held_one() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage(filterJson = filtered("^\\\\.hold")))
        var held: RecordingQuickJs.SendDispatch? = null
        plugin.js.onDispatchSend = { held = it }

        compose(".hold")
        compose("after")
        awaitValue("the stage was never asked") { held }
        assertEquals(emptyList(), drawn.map { it.message }, "a send overtook the one held ahead of it")

        plugin.passSend(held!!)
        awaitValue("the sends never went out") { sentRequests().takeIf { it.size == 2 } }
        assertEquals(listOf(".hold", "after"), sentRequests().map { (it as TLRPC.TL_messages_sendMessage).message })
    }

    @Test
    fun a_forward_sent_with_a_comment_reaches_the_stage_as_part_of_it() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())
        plugin.js.onDispatchSend = { plugin.passSend(it) }

        sendWithComment("look", 5)
        awaitValue("both requests never went out") { sentRequests().takeIf { it.size == 2 } }

        val message = JSONObject(plugin.js.sendDispatches.single().messageJson)
        assertEquals("look", message.getJSONObject("text").getString("text"))
        assertEquals(5, message.getJSONObject("forward").getJSONArray("messageIds").getInt(0))
        val (comment, forward) = sentRequests()
        assertTrue(comment is TLRPC.TL_messages_sendMessage && forward is TLRPC.TL_messages_forwardMessages)
    }

    @Test
    fun dropping_the_comment_drops_its_forward() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())
        plugin.js.onDispatchSend = { plugin.dropVerdict(it.dispatchId) }

        sendWithComment("look", 5)
        awaitValue("the stage was never asked") { plugin.js.sendDispatches.firstOrNull() }
        Thread.sleep(400)
        settle()

        assertEquals(emptyList(), sentRequests(), "a dropped comment let its forward out")
    }

    @Test
    fun a_forward_alone_has_no_text_for_a_filter_to_match() {
        val plugin = startPlugin("p", "interceptSendMessage")
        val filtered = startPlugin("q", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())
        assertNull(filtered.interceptSendMessage(filterJson = filtered("")))
        plugin.js.onDispatchSend = { plugin.passSend(it) }

        sendWithComment(null, 5)
        val forward = awaitValue("the forward never went out") { sentRequests().singleOrNull() }

        assertEquals(listOf(5), (forward as TLRPC.TL_messages_forwardMessages).id)
        assertEquals(0, filtered.js.sendDispatches.size, "a text filter matched a forward that has no text")
    }

    @Test
    fun a_comment_a_stage_gives_a_forward_goes_out_ahead_of_it() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())
        plugin.js.onDispatchSend = { plugin.passSend(it) { message -> message.put("text", text("look")) } }

        sendWithComment(null, 5)
        awaitValue("the forward never went out behind its comment") { sentRequests().takeIf { it.size == 2 } }

        val (comment, forward) = sentRequests()
        assertEquals("look", (comment as TLRPC.TL_messages_sendMessage).message)
        assertEquals(listOf(5), (forward as TLRPC.TL_messages_forwardMessages).id)
    }

    @Test
    fun a_forward_a_stage_takes_away_leaves_the_comment_to_go_alone() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())
        plugin.js.onDispatchSend = { plugin.passSend(it) { message -> message.put("forward", JSONObject.NULL) } }

        sendWithComment("look", 5)
        awaitValue("the comment never went out") { sentRequests().singleOrNull() }
        Thread.sleep(400)
        settle()

        assertEquals(listOf("look"), sentRequests().map { (it as TLRPC.TL_messages_sendMessage).message })
    }

    @Test
    fun a_stage_that_fails_sends_nothing() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())
        plugin.js.onDispatchSend = { plugin.js.listener!!.onSendVerdict(it.dispatchId, "Eboom") }

        compose("hi")
        awaitValue("the stage was never asked") { plugin.js.sendDispatches.firstOrNull() }
        Thread.sleep(400)
        settle()

        assertEquals(emptyList(), sentRequests())
        assertEquals(emptyList(), drawn)
    }

    @Test
    fun a_send_a_stage_never_answers_fails_after_its_budget() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())
        plugin.js.onDispatchSend = {}

        compose("hi")
        val dispatch = awaitValue("the stage was never asked") { plugin.js.sendDispatches.firstOrNull() }
        // the deadline clock is real uptime plus the offset, so leave margin for real time spent draining
        advanceBy(55_000)
        assertEquals(emptyList(), plugin.js.sendAbandons)

        advanceBy(5_001)
        settle()
        assertEquals(listOf(dispatch.dispatchId), plugin.js.sendAbandons)
        assertEquals(emptyList(), sentRequests())
    }

    @Test
    fun stages_run_in_plugin_list_order() {
        val first = startPlugin("a", "interceptSendMessage")
        val second = startPlugin("b", "interceptSendMessage")
        val order = ArrayList<String>()
        for (plugin in listOf(first, second)) {
            assertNull(plugin.interceptSendMessage())
            plugin.js.onDispatchSend = {
                order.add(plugin.manifest.name)
                plugin.passSend(it)
            }
        }

        compose("one")
        awaitValue("the send never went out") { sentRequests().singleOrNull() }
        assertEquals(listOf("a", "b"), order)

        setInstalledPlugins(listOf(second, first))
        PluginSends.refreshOrder()
        order.clear()
        compose("two")
        awaitValue("the send never went out") { sentRequests().takeIf { it.size == 2 } }
        assertEquals(listOf("b", "a"), order)
    }

    @Test
    fun a_stopped_plugin_s_stage_is_skipped() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())
        EngineDispatch.scheduler.postRunnable { detachPlugin(plugin) }
        drain()

        onUi {
            SendMessagesHelper.getInstance(0).sendMessage(SendMessageParams.of("hi", alice))
            assertEquals(listOf("hi"), drawn.map { it.message })
        }
        assertEquals(0, plugin.js.sendDispatches.size)
    }

    @Test
    fun a_dropped_file_is_neither_uploaded_nor_drawn() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())
        plugin.js.onDispatchSend = { plugin.dropVerdict(it.dispatchId) }

        sendDocument("look")
        val dispatch = awaitValue("the stage was never asked") { plugin.js.sendDispatches.firstOrNull() }
        Thread.sleep(400)
        settle()

        assertEquals("localMedia", JSONObject(dispatch.messageJson).getJSONArray("media").getJSONObject(0).getString("_"))
        assertTrue(TestApp.fileLoader(0).uploads.isEmpty(), "a dropped file was uploaded")
        assertEquals(emptyList(), drawn)
        assertEquals(emptyList(), sentRequests())
    }

    @Test
    fun a_file_a_stage_takes_away_leaves_its_caption_to_go_alone() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())
        plugin.js.onDispatchSend = { plugin.passSend(it) { message -> message.put("media", JSONArray()) } }

        sendDocument("look")
        val sent = awaitValue("the caption never went out") { sentRequests().singleOrNull() }

        assertEquals("look", (sent as TLRPC.TL_messages_sendMessage).message)
        assertTrue(TestApp.fileLoader(0).uploads.isEmpty(), "a file the stage took away was uploaded")
    }

    /** stages [count] files the way `account.createLocalMedia` does, and answers their ids */
    private fun createLocalMedia(plugin: Plugin, count: Int): List<String> = (1..count).map { at ->
        val staged = File(scratch, "part$at.txt").apply { writeText("part $at") }
        val wire = "F" + JSONObject().put("path", staged.absolutePath).put("name", "part$at.txt").put("mime", "text/plain")
        assertNull(write(plugin, PluginWrites.OP_CREATE_LOCAL_MEDIA, JSONObject(), arrayOf(wire), requestId = at.toLong()))
        val result = awaitValue("the file was never staged") { plugin.js.writeResults.firstOrNull { it.requestId == at.toLong() }?.resultWire }
        JSONObject(result.drop(1)).getString("id")
    }

    private fun commentWith(ids: List<String>): (JSONObject) -> Unit = { message ->
        message.put("media", JSONArray(ids.map { id ->
            JSONObject().put("kept", -1).put("local", JSONObject().put("id", id).put("kind", "document").put("name", "$id.txt").put("mimeType", "text/plain").put("spoiler", false))
        }))
    }

    /** finishes every upload the app started, and answers the `messages.uploadMedia` an album makes of each */
    private fun completeUploads(uploads: List<String>, answered: HashSet<RecordingConnectionsManager.Sent>) {
        onUi {
            for ((at, location) in uploads.withIndex()) {
                val file = TLRPC.TL_inputFile().apply {
                    id = at + 1L
                    parts = 1
                    name = "part$at.txt"
                    md5_checksum = ""
                }
                NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.fileUploaded, location, file, null, null, null, 7L)
            }
        }
        settle()
        for (pending in connections().sent.filter { it.request is TLRPC.TL_messages_uploadMedia && answered.add(it) }) {
            pending.answer(TLRPC.TL_messageMediaDocument().apply {
                document = TLRPC.TL_document().apply {
                    id = 100L + answered.size
                    file_reference = ByteArray(0)
                    mime_type = "text/plain"
                }
                flags = flags or 1
            }, null)
        }
    }

    @Test
    fun a_forward_given_several_files_as_its_comment_waits_for_their_album() {
        val plugin = startPlugin("p", "interceptSendMessage", "account.write(send)", "fs")
        assertNull(plugin.interceptSendMessage())
        val ids = createLocalMedia(plugin, 2)
        plugin.js.onDispatchSend = { plugin.passSend(it, commentWith(ids)) }

        sendWithComment(null, 5)
        val uploads = awaitValue("the files were never uploaded") { TestApp.fileLoader(0).uploads.takeIf { it.size == 2 }?.toList() }
        settle()
        assertEquals(emptyList(), sentRequests(), "the forward went out ahead of its comment")

        val answered = HashSet<RecordingConnectionsManager.Sent>()
        val sent = awaitValue("the album and its forward never went out") {
            completeUploads(uploads, answered)
            sentRequests().takeIf { it.size == 2 }
        }

        assertEquals(2, (sent[0] as TLRPC.TL_messages_sendMultiMedia).multi_media.size)
        assertEquals(listOf(5), (sent[1] as TLRPC.TL_messages_forwardMessages).id)
    }

    @Test
    fun a_file_and_media_already_on_the_server_go_out_as_one_album() {
        val plugin = startPlugin("p", "interceptSendMessage", "account.write(send)", "fs")
        assertNull(plugin.interceptSendMessage())
        val file = createLocalMedia(plugin, 1)
        plugin.js.onDispatchSend = {
            plugin.passSend(it) { message ->
                commentWith(file)(message)
                val added = JSONObject().put("kept", -1).put("tl", JSONObject().put("_", "inputMediaDocument").put("id", JSONObject().put("_", "inputDocument").put("id", "9").put("access_hash", "0").put("file_reference", JSONObject().put("\$inuBytes", ""))))
                message.put("media", JSONArray().put(added).put(message.getJSONArray("media").getJSONObject(0)))
            }
        }

        compose("look")
        val uploads = awaitValue("the file was never uploaded") { TestApp.fileLoader(0).uploads.takeIf { it.size == 1 }?.toList() }
        val answered = HashSet<RecordingConnectionsManager.Sent>()
        val sent = awaitValue("the album never went out") {
            completeUploads(uploads, answered)
            sentRequests().singleOrNull()
        } as TLRPC.TL_messages_sendMultiMedia

        val ids = sent.multi_media.map { ((it.media as TLRPC.TL_inputMediaDocument).id as TLRPC.TL_inputDocument).id }
        assertEquals(listOf(9L, 101L), ids)
        assertEquals(listOf("look", ""), sent.multi_media.map { it.message })

        val request = connections().sent.single { it.request === sent }
        request.answer(TLRPC.TL_updates().apply {
            for ((at, single) in sent.multi_media.withIndex()) {
                val message = TLRPC.TL_message().apply {
                    id = 1001 + at
                    peer_id = TLRPC.TL_peerUser().apply { user_id = alice }
                    from_id = TLRPC.TL_peerUser().apply { user_id = self }
                    out = true
                    message = single.message
                    date = 1_700_000_000
                    grouped_id = 55L
                    flags = flags or 131072
                    media = TLRPC.TL_messageMediaDocument().apply {
                        document = TLRPC.TL_document().apply {
                            // the test app has no file path database, which stock looks a document id up in
                            id = 0L
                            file_reference = ByteArray(0)
                            mime_type = "text/plain"
                        }
                        flags = flags or 1
                    }
                }
                updates.add(TL_update.TL_updateMessageID().apply {
                    id = message.id
                    random_id = single.random_id
                })
                updates.add(TL_update.TL_updateNewMessage().apply { this.message = message })
            }
        }, null)
        // the chat cannot pull the file it drew alone into the album
        awaitValue("the server's album was not drawn whole") { drawn.filter { it.grouped_id == 55L }.takeIf { it.size == 2 } }
    }

    @Test
    fun a_forward_waits_while_an_uploaded_stage_holds_its_comment() {
        val plugin = startPlugin("p", "interceptSendMessage", "account.write(send)", "fs")
        val uploaded = startPlugin("q", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage())
        assertNull(uploaded.interceptSendMessage(filterJson = """{"stage":"uploaded"}"""))
        val ids = createLocalMedia(plugin, 1)
        plugin.js.onDispatchSend = { plugin.passSend(it, commentWith(ids)) }
        var held: RecordingQuickJs.SendDispatch? = null
        uploaded.js.onDispatchSend = { held = it }

        sendWithComment(null, 5)
        val uploads = awaitValue("the file was never uploaded") { TestApp.fileLoader(0).uploads.takeIf { it.size == 1 }?.toList() }
        completeUploads(uploads, HashSet())
        awaitValue("the uploaded stage was never asked") { held }
        Thread.sleep(400)
        settle()
        assertEquals(emptyList(), sentRequests(), "the forward overtook the comment its stage was holding")

        uploaded.passSend(held!!)
        val sent = awaitValue("the comment and its forward never went out") { sentRequests().takeIf { it.size == 2 } }
        assertTrue(sent[0] is TLRPC.TL_messages_sendMedia, "the comment did not go first: $sent")
        assertEquals(listOf(5), (sent[1] as TLRPC.TL_messages_forwardMessages).id)
    }

    @Test
    fun the_uploaded_stage_rewrites_a_media_send_s_caption() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage(filterJson = """{"stage":"uploaded"}"""))
        plugin.js.onDispatchSend = { plugin.passSend(it) { message -> message.put("text", text("rewritten")) } }

        val request = bindMediaSend("cap")
        assertTrue(sendThroughPlugins(request))
        val sent = awaitValue("the send never went out") { sentRequests().singleOrNull() }

        assertSame(request, sent)
        assertEquals("rewritten", request.message)
        assertEquals("inputMediaUploadedPhoto", JSONObject(plugin.js.sendDispatches.single().messageJson).getJSONArray("media").getJSONObject(0).getString("_"))
    }

    @Test
    fun a_field_stock_left_unflagged_stays_off_a_send_the_uploaded_stage_rewrote() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage(filterJson = """{"stage":"uploaded"}"""))
        plugin.js.onDispatchSend = { plugin.passSend(it) { message -> message.put("text", text("rewritten")) } }

        val request = bindMediaSend("cap")
        // stock fills send_as on every send and flags it only where the chat takes one
        request.send_as = TLRPC.TL_inputPeerUser().apply { user_id = self }
        assertTrue(sendThroughPlugins(request))
        val sent = awaitValue("the send never went out") { sentRequests().singleOrNull() } as TLRPC.TL_messages_sendMedia

        assertEquals("rewritten", sent.message)
        assertEquals(0, sent.flags and TLObject.FLAG_13, "send_as was flagged")
    }

    @Test
    fun the_uploaded_stage_may_silence_a_send() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage(filterJson = """{"stage":"uploaded"}"""))
        plugin.js.onDispatchSend = { plugin.passSend(it) { message -> message.put("silent", true) } }

        val request = bindMediaSend("cap")
        assertTrue(sendThroughPlugins(request))
        val sent = awaitValue("the send never went out") { sentRequests().singleOrNull() } as TLRPC.TL_messages_sendMedia

        assertTrue(sent.silent)
    }

    @Test
    fun a_send_cancelled_while_the_uploaded_stage_holds_it_is_never_sent() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage(filterJson = """{"stage":"uploaded"}"""))
        var held: RecordingQuickJs.SendDispatch? = null
        plugin.js.onDispatchSend = { held = it }

        assertTrue(sendThroughPlugins(bindMediaSend("cap"), token = 11))
        awaitValue("the uploaded stage was never asked") { held }
        PluginRpc.onRequestCancelled(0, 11, true, null)
        settle()
        plugin.passSend(held!!)
        Thread.sleep(400)
        settle()

        assertEquals(emptyList(), sentRequests())
    }

    @Test
    fun the_uploaded_stage_turns_a_single_send_into_an_album() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage(filterJson = """{"stage":"uploaded"}"""))
        plugin.js.onDispatchSend = {
            plugin.passSend(it) { message ->
                val kept = JSONObject().put("kept", 0).put("tl", message.getJSONArray("media").getJSONObject(0))
                val added = JSONObject().put("kept", -1).put("tl", JSONObject().put("_", "inputMediaPhoto").put("id", JSONObject().put("_", "inputPhoto").put("id", "9").put("access_hash", "0").put("file_reference", JSONObject().put("\$inuBytes", ""))))
                message.put("media", JSONArray().put(kept).put(added))
            }
        }

        val request = bindMediaSend("cap")
        assertTrue(sendThroughPlugins(request))
        val upload = awaitValue("the uploaded photo never went through messages.uploadMedia") {
            connections().sent.singleOrNull { it.request is TLRPC.TL_messages_uploadMedia }
        }
        upload.answer(TLRPC.TL_messageMediaPhoto().apply {
            photo = TLRPC.TL_photo().apply {
                id = 77L
                file_reference = ByteArray(0)
            }
            flags = flags or 1
        }, null)
        val sent = awaitValue("the album never went out") { sentRequests().singleOrNull() } as TLRPC.TL_messages_sendMultiMedia

        assertEquals(2, sent.multi_media.size)
        assertEquals(77L, ((sent.multi_media[0].media as TLRPC.TL_inputMediaPhoto).id as TLRPC.TL_inputPhoto).id)
        assertEquals("cap", sent.multi_media[0].message)
        assertEquals(9L, ((sent.multi_media[1].media as TLRPC.TL_inputMediaPhoto).id as TLRPC.TL_inputPhoto).id)
    }

    @Test
    fun the_uploaded_stage_may_drop_a_send_and_the_app_hears_it_was_dropped() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage(filterJson = """{"stage":"uploaded"}"""))
        plugin.js.onDispatchSend = { plugin.dropVerdict(it.dispatchId) }
        var error: TLRPC.TL_error? = null

        assertTrue(sendThroughPlugins(bindMediaSend("cap")) { _, e -> error = e })
        awaitValue("the app was never answered") { error }

        assertEquals("MESSAGE_DROPPED_BY_PLUGIN", error?.text)
        assertEquals(emptyList(), sentRequests())
    }

    @Test
    fun the_uploaded_stage_left_without_media_sends_the_caption_alone() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage(filterJson = """{"stage":"uploaded"}"""))
        plugin.js.onDispatchSend = { plugin.passSend(it) { message -> message.put("media", JSONArray()) } }
        var error: TLRPC.TL_error? = null

        assertTrue(sendThroughPlugins(bindMediaSend("cap")) { _, e -> error = e })
        val sent = awaitValue("the caption never went out") { sentRequests().singleOrNull() }

        assertEquals("cap", (sent as TLRPC.TL_messages_sendMessage).message)
        assertEquals("MESSAGE_DROPPED_BY_PLUGIN", assertNotNull(error).text)
    }

    @Test
    fun the_uploaded_stage_may_not_move_a_send() {
        val plugin = startPlugin("p", "interceptSendMessage")
        assertNull(plugin.interceptSendMessage(filterJson = """{"stage":"uploaded"}"""))
        plugin.js.onDispatchSend = { plugin.passSend(it) { message -> message.put("peer", bob) } }
        var error: TLRPC.TL_error? = null

        assertTrue(sendThroughPlugins(bindMediaSend("cap")) { _, e -> error = e })
        awaitValue("the app was never answered") { error }

        assertTrue(assertNotNull(error?.text).startsWith("peer:"), "unexpected error ${error?.text}")
        assertEquals(emptyList(), sentRequests())
    }
}
