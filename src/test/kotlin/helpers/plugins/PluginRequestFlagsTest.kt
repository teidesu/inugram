package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.telegram.PeerSpecs
import desu.inugram.helpers.plugins.telegram.PluginWrites
import desu.inugram.helpers.plugins.tl.TlFlags
import desu.inugram.helpers.plugins.tl.TlReflect
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.telegram.tgnet.TLObject

// stock `serializeToStream` recomputes only the boolean flag bits; an optional field is written only
// if its bit was set by hand
class PluginRequestFlagsTest {
    private val self = 100L
    private val alice = 222L
    private val channel = 1001L

    @Before
    fun setUp() {
        resetBridge()
        TestApp.signInAs(0, user(self))
        TestApp.putUser(user(alice))
        TestApp.putChat(broadcast(channel))
    }

    private fun granted() = startPlugin(
        "flags",
        "account.write(send,edit,delete,forward,react,read,typing,draft)",
        "account.read(messages)",
    )

    private fun send(peer: String) = JSONObject().put("peer", peer).put("text", "hi").put("optimistic", false)

    private fun inconsistent(obj: TLObject, path: String, out: MutableList<String>) {
        val cls = obj.javaClass
        val fields = TlReflect.publicFields(cls)
        for (word in TlFlags.getFlagWords(cls)) {
            val name = TlFlags.wordName(word) ?: continue
            val stored = fields[name]?.getInt(obj) ?: continue
            val expected = TlFlags.computeWord(cls, word) { TlFlags.isPresent(fields[it]?.get(obj)) }
            if (stored != expected) out += "$path.$name: is $stored, fields say $expected"
        }
        for (field in fields.values) {
            when (val value = field.get(obj)) {
                is TLObject -> inconsistent(value, "$path.${field.name}", out)
                is List<*> -> value.forEachIndexed { i, item ->
                    if (item is TLObject) inconsistent(item, "$path.${field.name}[$i]", out)
                }
            }
        }
    }

    @Test
    fun every_request_the_write_surface_sends_agrees_with_its_own_fields() {
        val plugin = granted()
        val entity = JSONObject().put("_", "messageEntityBold").put("offset", 0).put("length", 2)
        val reaction = JSONObject().put("_", "reactionEmoji").put("emoticon", "👍")
        val ids = listOf("3", "4").toJsonArray()

        val ops = listOf(
            PluginWrites.OP_SEND_MESSAGE to send("D$alice")
                .put("entities", listOf(entity).toJsonArray())
                .put("replyTo", "5")
                .put("scheduleDate", "1700"),
            PluginWrites.OP_SEND_MESSAGE to send("D${PeerSpecs.ZERO_CHANNEL_ID - channel}").put("topicId", "12"),
            PluginWrites.OP_EDIT_MESSAGE to JSONObject()
                .put("peer", "D$alice").put("id", "7").put("text", "edited")
                .put("entities", listOf(entity).toJsonArray()),
            PluginWrites.OP_DELETE_MESSAGES to JSONObject()
                .put("peer", "D${PeerSpecs.ZERO_CHANNEL_ID - channel}").put("ids", ids).put("revoke", true),
            PluginWrites.OP_FORWARD_MESSAGES to JSONObject()
                .put("peer", "D${PeerSpecs.ZERO_CHANNEL_ID - channel}").put("toPeer", "D$alice").put("ids", ids)
                .put("topicId", "4").put("scheduleDate", "1700"),
            PluginWrites.OP_SET_REACTION to JSONObject()
                .put("peer", "D$alice").put("id", "7")
                .put("reactions", listOf(reaction).toJsonArray()),
            PluginWrites.OP_SEND_TYPING to JSONObject()
                .put("peer", "D${PeerSpecs.ZERO_CHANNEL_ID - channel}").put("topicId", "4").put("action", "typing"),
            PluginWrites.OP_READ_HISTORY to JSONObject()
                .put("peer", "D${PeerSpecs.ZERO_CHANNEL_ID - channel}").put("maxId", "9").put("topicId", "3"),
        )

        val problems = mutableListOf<String>()
        var sent = 0
        for ((op, arg) in ops) {
            connections().sent.clear()
            assertNull(write(plugin, op, arg))
            drain()
            val request = connections().lastSent()?.request ?: continue
            sent++
            inconsistent(request, request.javaClass.simpleName, problems)
        }

        assertEquals(ops.size, sent, "an op sent nothing, so this swept less than it looks like")
        assertTrue(problems.isEmpty(), "flag words that disagree with their fields:\n${problems.joinToString("\n")}")
    }
}
