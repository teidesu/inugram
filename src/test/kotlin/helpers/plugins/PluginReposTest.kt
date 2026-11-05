package desu.inugram.helpers.plugins

import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Before
import org.junit.Test
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_iv

class PluginReposTest {
    private val repo = PluginRepo(1001L, "plugins", "Plugins")
    private class PageResult(val page: PluginRepos.Page?)
    private class DownloadResult(val source: String?)

    @Before
    fun setUp() {
        resetBridge()
        TestApp.signInAs(0, user(100L, "selfuser"))
        TestApp.putChat(broadcast(repo.channelId, repo.username))
        // Stock controllers can send requests during their first initialization.
        settle()
        connections().sent.clear()
    }

    private fun document(id: Long, name: String, reference: Byte = 1) = TLRPC.TL_document().apply {
        this.id = id
        access_hash = id * 10
        file_reference = byteArrayOf(reference)
        dc_id = 2
        size = 1
        mime_type = "application/javascript"
        attributes.add(TLRPC.TL_documentAttributeFilename().apply { file_name = name })
    }

    private fun message(id: Int) = TLRPC.TL_message().apply {
        this.id = id
        peer_id = TLRPC.TL_peerChannel().apply { channel_id = repo.channelId }
        message = ""
    }

    private fun rich(id: Int, vararg documents: TLRPC.Document, partial: Boolean = false) = message(id).apply {
        rich_message = TL_iv.RichMessage().apply {
            part = partial
            this.documents.addAll(documents)
        }
    }

    private fun page(offset: Int = 0, min: Int = 0): AtomicReference<PageResult> {
        val result = AtomicReference<PageResult>()
        onUi { PluginRepos.loadPage(0, repo, offset, min) { result.set(PageResult(it)) } }
        settle()
        return result
    }

    private fun answer(vararg messages: TLRPC.Message) {
        connections().lastSent()!!.answer(TLRPC.TL_messages_messages().apply {
            this.messages.addAll(messages)
        }, null)
        settle()
    }

    @Test
    fun regular_and_rich_documents_keep_channel_order_and_their_installation_origin() {
        val regular = message(100).apply {
            media = TLRPC.TL_messageMediaDocument().apply { document = document(11, "First.inu.js") }
        }
        val first = document(12, "Second.inu.js")
        val result = page()
        assertIs<TLRPC.TL_messages_getHistory>(connections().lastSent()!!.request)
        answer(regular, rich(99, first, first, document(13, "Third.INU.JS"), document(14, "notes.txt")))

        val loaded = assertNotNull(awaitValue("repository page") { result.get() }.page)
        assertEquals(listOf(11L, 12L, 13L), loaded.posts.map { it.document.id })
        assertEquals(listOf(100, 99, 99), loaded.posts.map { it.origin.messageId })
        assertEquals(listOf(repo.channelId, repo.channelId, repo.channelId), loaded.posts.map { it.origin.channelId })
        assertEquals(0, loaded.nextOffsetId)
        assertEquals(1, connections().sent.size)
    }

    @Test
    fun partial_rich_messages_are_completed_before_deciding_which_plugins_the_page_contains() {
        val result = page()
        answer(rich(100, document(11, "notes.txt"), partial = true))
        val request = assertIs<TL_iv.getRichMessage>(connections().lastSent()!!.request)
        assertEquals(100, request.id)
        assertEquals(repo.channelId, assertIs<TLRPC.TL_inputPeerChannel>(request.peer).channel_id)
        assertNull(result.get())

        answer(rich(100, document(12, "Plugin.inu.js")))
        val loaded = assertNotNull(awaitValue("completed rich page") { result.get() }.page)
        assertEquals(listOf(12L), loaded.posts.map { it.document.id })
    }

    @Test
    fun a_failed_rich_message_load_is_a_retryable_page_failure_rather_than_an_empty_repository() {
        val result = page()
        answer(rich(100, partial = true))
        connections().lastSent()!!.answer(null, TLRPC.TL_error().apply { code = 500; text = "SERVER_ERROR" })
        assertNull(awaitValue("failed rich page") { result.get() }.page)
        assertEquals(2, connections().sent.size)
    }

    @Test
    fun an_unrelated_or_still_partial_response_cannot_complete_a_repository_page() {
        for (completion in listOf(rich(99, document(12, "Plugin.inu.js")), rich(100, partial = true))) {
            val result = page()
            answer(rich(100, partial = true))
            answer(completion)
            assertNull(awaitValue("incomplete rich page") { result.get() }.page)
        }
    }

    @Test
    fun a_full_page_without_plugins_still_advances_the_history_cursor() {
        val result = page(offset = 200, min = 20)
        val request = assertIs<TLRPC.TL_messages_getHistory>(connections().lastSent()!!.request)
        assertEquals(200, request.offset_id)
        assertEquals(20, request.min_id)
        answer(*(199 downTo 150).map(::message).toTypedArray())

        val loaded = assertNotNull(awaitValue("text-only history page") { result.get() }.page)
        assertEquals(emptyList(), loaded.posts)
        assertEquals(150, loaded.nextOffsetId)
    }

    private fun expiredDownload(): AtomicReference<DownloadResult> {
        val result = AtomicReference<DownloadResult>()
        onUi {
            PluginRepos.downloadSource(0, PluginRepoPost(repo, 100, document(12, "Plugin.inu.js"))) {
                result.set(DownloadResult(it))
            }
        }
        settle()
        assertIs<TLRPC.TL_upload_getFile>(connections().lastSent()!!.request)
        connections().lastSent()!!.answer(null, TLRPC.TL_error().apply { code = 400; text = "FILE_REFERENCE_EXPIRED" })
        settle()
        assertIs<TLRPC.TL_channels_getMessages>(connections().lastSent()!!.request)
        return result
    }

    @Test
    fun an_expired_rich_document_reference_refreshes_the_same_document_even_when_another_plugin_comes_first() {
        val result = expiredDownload()
        answer(rich(100, partial = true))
        assertIs<TL_iv.getRichMessage>(connections().lastSent()!!.request)
        answer(rich(100, document(11, "Other.inu.js"), document(12, "Plugin.inu.js", reference = 2)))

        val request = assertIs<TLRPC.TL_upload_getFile>(connections().lastSent()!!.request)
        val location = assertIs<TLRPC.TL_inputDocumentFileLocation>(request.location)
        assertEquals(12L, location.id)
        assertContentEquals(byteArrayOf(2), location.file_reference)
        assertNull(result.get())
        // A second expiry fails instead of entering an unbounded refresh loop.
        connections().lastSent()!!.answer(null, TLRPC.TL_error().apply { code = 400; text = "FILE_REFERENCE_EXPIRED" })
        assertNull(awaitValue("expired retry") { result.get() }.source)
        assertEquals(4, connections().sent.size)
    }

    @Test
    fun replacing_an_installer_in_a_rich_post_cannot_switch_an_in_flight_download_to_the_new_file() {
        val result = expiredDownload()
        answer(rich(100, document(99, "Plugin.inu.js", reference = 2)))
        assertNull(awaitValue("replaced installer") { result.get() }.source)
        assertEquals(2, connections().sent.size)
    }
}
