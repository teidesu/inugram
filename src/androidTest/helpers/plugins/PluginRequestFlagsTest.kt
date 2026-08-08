package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.TlFlags
import desu.inugram.helpers.plugins.tg.PluginWrites
import desu.inugram.helpers.plugins.tl.TlJson
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

/**
 * Stock's `serializeToStream` recomputes only the *boolean* bits (`setFlag`); every other optional
 * field is written under `if (hasFlag(flags, FLAG_n))` and stock's own call sites set that bit by
 * hand. So a request this bridge builds and never syncs goes out silently missing its `reply_to`,
 * `entities`, `schedule_date` and `send_as`.
 *
 * Asserting on the built Java object's fields cannot see that - the fields are set either way, and
 * the generated stubs carry no `serializeToStream` to round-trip through. The flag word is the
 * observable, so these assert it directly.
 */
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

    private fun user(id: Long) = TLRPC.TL_user().apply {
        this.id = id
        access_hash = id * 10
    }

    private fun broadcast(id: Long) = TLRPC.TL_channel().apply {
        this.id = id
        access_hash = id * 10
        broadcast = true
    }

    private fun granted() = startPlugin(
        "flags",
        "account.write(send,edit,delete,forward,react,read,typing,draft)",
        "account.read(messages)",
    )

    private fun write(plugin: Plugin, op: Int, arg: JSONObject): String? =
        plugin.js.listener!!.accountWrite(0, 1L, op, arg.toString(), emptyArray())

    private fun send(peer: String) = JSONObject().put("peer", peer).put("text", "hi")

    @Test
    fun a_send_carrying_a_reply_entities_and_a_schedule_sets_their_bits() {
        val plugin = granted()
        val entity = JSONObject().put("_", "messageEntityBold").put("offset", 0).put("length", 2)
        val arg = send("D$alice")
            .put("entities", listOf(entity).toJsonArray())
            .put("replyTo", "5")
            .put("scheduleDate", "1700")
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, arg))
        drain()

        val request = connections().lastSent()!!.request as TLRPC.TL_messages_sendMessage
        // messages.sendMessage: reply_to=0, entities=3, schedule_date=10
        assertTrue(request.flags and (1 shl 0) != 0, "reply_to's bit is clear, so the reply is dropped on the wire")
        assertTrue(request.flags and (1 shl 3) != 0, "entities' bit is clear, so the formatting is dropped")
        assertTrue(request.flags and (1 shl 10) != 0, "schedule_date's bit is clear, so the message sends immediately")
    }

    @Test
    fun a_topic_reply_sets_the_bit_on_the_nested_reply_not_only_the_request() {
        val plugin = granted()
        assertNull(write(plugin, PluginWrites.OP_SEND_MESSAGE, send("D-$channel").put("topicId", "12")))
        drain()

        val request = connections().lastSent()!!.request as TLRPC.TL_messages_sendMessage
        val reply = request.reply_to as TLRPC.TL_inputReplyToMessage
        // inputReplyToMessage.top_msg_id=0, and it is the nested object that carries the word - a
        // top-level-only sync leaves this clear and the post lands in General
        assertEquals(12, reply.top_msg_id)
        assertTrue(reply.flags and (1 shl 0) != 0, "top_msg_id's bit is clear, so a forum post lands in General")
    }

    @Test
    fun a_reaction_carries_its_reaction_vector() {
        val plugin = granted()
        val reaction = JSONObject().put("_", "reactionEmoji").put("emoticon", "👍")
        val arg = JSONObject()
            .put("peer", "D$alice")
            .put("id", "7")
            .put("reactions", listOf(reaction).toJsonArray())
        assertNull(write(plugin, PluginWrites.OP_SET_REACTION, arg))
        drain()

        val request = connections().lastSent()!!.request as TLRPC.TL_messages_sendReaction
        // messages.sendReaction.reaction=0. an absent vector means "clear all my reactions", so a
        // clear bit turns the documented add into a removal
        assertTrue(request.flags and (1 shl 0) != 0, "reaction's bit is clear, so this clears reactions instead")
    }

    private fun inconsistent(obj: TLObject, path: String, out: MutableList<String>) {
        val cls = obj.javaClass
        val fields = TlJson.publicFields(cls)
        for (word in TlFlags.wordsOf(cls)) {
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
            PluginWrites.OP_SEND_MESSAGE to send("D-$channel").put("topicId", "12"),
            PluginWrites.OP_EDIT_MESSAGE to JSONObject()
                .put("peer", "D$alice").put("id", "7").put("text", "edited")
                .put("entities", listOf(entity).toJsonArray()),
            PluginWrites.OP_DELETE_MESSAGES to JSONObject()
                .put("peer", "D-$channel").put("ids", ids).put("revoke", true),
            PluginWrites.OP_FORWARD_MESSAGES to JSONObject()
                .put("peer", "D-$channel").put("toPeer", "D$alice").put("ids", ids)
                .put("topicId", "4").put("scheduleDate", "1700"),
            PluginWrites.OP_SET_REACTION to JSONObject()
                .put("peer", "D$alice").put("id", "7")
                .put("reactions", listOf(reaction).toJsonArray()),
            PluginWrites.OP_SEND_TYPING to JSONObject()
                .put("peer", "D-$channel").put("topicId", "4").put("action", "typing"),
            PluginWrites.OP_READ_HISTORY to JSONObject()
                .put("peer", "D-$channel").put("maxId", "9").put("topicId", "3"),
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
