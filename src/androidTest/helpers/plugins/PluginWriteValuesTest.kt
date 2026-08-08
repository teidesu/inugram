package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.telegram.PluginWrites
import desu.inugram.helpers.plugins.tl.TlHandles
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
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

/**
 * The values a write takes that could not be json: a live handle into the plugin's own table.
 *
 * Two directions, and they are not the same rule. What a send *carries* becomes part of a request
 * the host then builds and syncs the flag words of, so a handle onto an object the app owns is
 * refused there like it is on every other request path. What a transfer *names* is only read, and
 * read-only is the normal shape for it: everything an `Account` hands over is.
 */
class PluginWriteValuesTest {
    private val alice = 222L

    @Before
    fun setUp() {
        resetBridge()
        TestApp.signInAs(0, TLRPC.TL_user().apply { id = 100L })
        TestApp.putUser(account = 0, user = TLRPC.TL_user().apply { id = alice; access_hash = 7L })
    }

    private fun granted() = startPlugin("writes", "account.write(send)", "account.read(messages)")

    private fun handleFor(plugin: Plugin, value: TLObject, readOnly: Boolean): String {
        val handles = TlHandles.of(plugin.js)
        return PluginWire.encodeHandle(vector = false, id = handles.mintForPlugin(value, readOnly), readOnly = readOnly)
    }

    private fun contact() = TLRPC.TL_inputMediaContact().apply {
        phone_number = "+100"
        first_name = "alice"
    }

    private fun withMedia() = TLRPC.TL_message().apply {
        id = 4242
        media = TLRPC.TL_messageMediaDocument().apply {
            document = TLRPC.TL_document().apply {
                id = 99L
                access_hash = 1L
                dc_id = 2
                size = 11L
                mime_type = "text/plain"
                attributes.add(TLRPC.TL_documentAttributeFilename().apply { file_name = "note.txt" })
            }
        }.synced()
    }.synced()

    private fun sendMedia(plugin: Plugin, wire: String): String? = plugin.js.listener!!.accountWrite(
        0,
        1L,
        PluginWrites.OP_SEND_MEDIA,
        JSONObject().put("peer", "D$alice").put("text", "").toString(),
        arrayOf(wire),
    )

    @Test
    fun a_media_a_send_carries_may_not_be_a_handle_onto_the_app_s_own_object() {
        val plugin = granted()
        val refusal = sendMedia(plugin, handleFor(plugin, contact(), readOnly = true))

        assertPluginError("forbidden", refusal)
        assertEquals(0, connections().sent.size, "the app's object went out inside a request anyway")
    }

    @Test
    fun a_media_the_plugin_owns_is_sent_as_it_is() {
        val plugin = granted()
        assertNull(sendMedia(plugin, handleFor(plugin, contact(), readOnly = false)))
        drain()

        val request = connections().sent.map { it.request }.filterIsInstance<TLRPC.TL_messages_sendMedia>().single()
        assertTrue(request.media is TLRPC.TL_inputMediaContact)
    }

    @Test
    fun a_transfer_names_its_message_with_the_read_only_handle_every_read_hands_over() {
        val plugin = granted()
        val message = withMedia()
        val wire = handleFor(plugin, message, readOnly = true)
        TestApp.fileLoader(0).paths[message.id] = File(
            Files.createTempDirectory("inu-write-values-test").toFile(),
            "note.txt",
        ).apply { writeText("hello world") }

        val answer = plugin.js.listener!!.messageFile(0, wire)
        val json = JSONObject((PluginWire.decode(answer) as PluginWire.Value.Json).json)
        assertTrue(json.getBoolean("exists"), "getMessageFile refused a message off an Account")

        assertNull(
            plugin.js.listener!!.accountWrite(
                0,
                2L,
                PluginWrites.OP_DOWNLOAD_MEDIA,
                JSONObject().toString(),
                arrayOf(wire),
            ),
            "downloadMedia refused a message off an Account",
        )
    }

    @Test
    fun a_handle_whose_table_entry_is_gone_is_expired_not_forbidden() {
        val plugin = granted()
        assertPluginError("handle-expired", sendMedia(plugin, PluginWire.encodeHandle(vector = false, id = 9999L, readOnly = false)))
    }
}
