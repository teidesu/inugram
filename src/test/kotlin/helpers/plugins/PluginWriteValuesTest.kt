package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.telegram.PluginWrites
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

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
        val handles = plugin.session!!.tl
        return PluginWire.encodeHandle(vector = false, id = handles.mintForPlugin(value, readOnly), readOnly = readOnly)
    }

    private fun contact() = TLRPC.TL_inputMediaContact().apply {
        phone_number = "+100"
        first_name = "alice"
    }

    private fun sendMedia(plugin: Plugin, wire: String): String? = plugin.js.listener!!.accountWrite(
        0,
        1L,
        PluginWrites.OP_SEND_MEDIA,
        JSONObject().put("peer", "D$alice").put("text", "").put("optimistic", false).toString(),
        arrayOf(wire),
    )

    @Test
    fun invalid_write_options_fail_before_sending() {
        val plugin = granted()
        for ((key, value) in listOf("scheduleDate" to "2147483648", "silent" to "true", "optimistic" to "false")) {
            val json = JSONObject().put("peer", "D$alice").put("text", "hello")
                .put("optimistic", false).put(key, value)
            val refusal = plugin.js.listener!!.accountWrite(0, 1L, PluginWrites.OP_SEND_MESSAGE, json.toString(), emptyArray())
            assertPluginError("invalid-argument", refusal)
        }
        drain()
        assertTrue(connections().sent.isEmpty())
    }

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
