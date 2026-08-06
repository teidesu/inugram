package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.TlWire
import desu.inugram.helpers.plugins.tg.PluginWrites
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
        val config = UserConfig.getInstance(0)
        config.clientUserId = 100L
        config.currentUser = TLRPC.TL_user().apply { id = 100L }
        MessagesController.getInstance(0).inu_putUser(TLRPC.TL_user().apply { id = alice; access_hash = 7L })
    }

    private fun granted() = startPlugin("writes", "account.write(send)", "account.read(messages)")

    private fun handleFor(plugin: Plugin, value: TLObject, readOnly: Boolean): String {
        val handles = TlHandles.of(plugin.js)
        return TlWire.encodeHandle(vector = false, id = handles.mintForPlugin(value, readOnly), readOnly = readOnly)
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

    private fun sendMedia(plugin: Plugin, wire: String): String? = plugin.js.writesListener!!.accountWrite(
        0,
        1L,
        PluginWrites.OP_SEND_MEDIA,
        JSONObject().put("peer", "D$alice").put("text", "").toString(),
        arrayOf(wire),
    )

    private fun connections() = ConnectionsManager.getInstance(0)

    @Test
    fun `a media a send carries may not be a handle onto the app's own object`() {
        val plugin = granted()
        val refusal = sendMedia(plugin, handleFor(plugin, contact(), readOnly = true))

        assertPluginError("forbidden", refusal)
        assertEquals(0, connections().sent.size, "the app's object went out inside a request anyway")
    }

    @Test
    fun `a media the plugin owns is sent as it is`() {
        val plugin = granted()
        assertNull(sendMedia(plugin, handleFor(plugin, contact(), readOnly = false)))
        drain()

        val request = connections().sent.map { it.request }.filterIsInstance<TLRPC.TL_messages_sendMedia>().single()
        assertTrue(request.media is TLRPC.TL_inputMediaContact)
    }

    @Test
    fun `a transfer names its message with the read-only handle every read hands over`() {
        val plugin = granted()
        val message = withMedia()
        val wire = handleFor(plugin, message, readOnly = true)
        FileLoader.getInstance(0).inu_paths[message.id] = File(
            Files.createTempDirectory("inu-write-values-test").toFile(),
            "note.txt",
        ).apply { writeText("hello world") }

        val answer = plugin.js.writesListener!!.messageFile(0, wire)
        val json = JSONObject((TlWire.decode(answer) as TlWire.Value.Json).json)
        assertTrue(json.getBoolean("exists"), "getMessageFile refused a message off an Account")

        assertNull(
            plugin.js.writesListener!!.accountWrite(
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
    fun `a handle whose table entry is gone is expired, not forbidden`() {
        val plugin = granted()
        assertPluginError("handle-expired", sendMedia(plugin, TlWire.encodeHandle(vector = false, id = 9999L, readOnly = false)))
    }
}
