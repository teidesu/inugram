package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlHandles
import desu.inugram.helpers.plugins.tl.TlJson
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Test
import org.telegram.tgnet.TLRPC

class TlJsonTest {
    private val unfiltered = TlFilter.Policy(takeover = false, drafts = true)

    @Test
    fun private_keyboard_types_round_trip_their_public_fields() {
        fun roundTrip(json: String) = TlJson.toJson(TlJson.fromJson(JSONObject(json)), TlFilter.Policy(takeover = true, drafts = false))

        val auth = roundTrip("""{"_":"inputInlineButtonTypeUrlAuth","url":"https://example.com","fwd_text":"Open","request_write_access":true}""")
        assertEquals("https://example.com", auth.getString("url"))
        assertEquals("Open", auth.getString("fwd_text"))
        assertTrue(auth.getBoolean("request_write_access"))
        assertTrue(!auth.has("flags"))

        val peer = roundTrip("""{"_":"inputButtonTypeRequestPeer","button_id":7,"max_quantity":3,"name_requested":true}""")
        assertEquals(7, peer.getInt("button_id"))
        assertEquals(3, peer.getInt("max_quantity"))
        assertTrue(peer.getBoolean("name_requested"))

        val profile = roundTrip("""{"_":"inputInlineButtonTypeUserProfile","user_id":{"_":"inputUserSelf"}}""")
        assertEquals("inputUserSelf", profile.getJSONObject("user_id").getString("_"))
    }

    @Test
    fun a_flagged_number_written_as_zero_is_sent_and_one_left_out_is_not() {
        val reply = TlJson.fromJson(JSONObject("""{"_":"inputReplyToMessage","reply_to_msg_id":5,"quote_text":"hi","quote_offset":0}"""))
        val json = TlJson.toJson(reply, unfiltered)
        assertEquals(0, json.getInt("quote_offset"))
        assertTrue(!json.has("top_msg_id"))
    }

    @Test
    fun setting_a_flagged_number_to_zero_keeps_it_and_clearing_it_drops_it() {
        val handles = TlHandles(unfiltered)
        val reply = TLRPC.TL_inputReplyToMessage().apply { reply_to_msg_id = 5 }
        val handle = handles.mintForScope(reply, TlHandles.newScope())

        assertNull(handles.tlSet(handle, "quote_offset", PluginWire.encodeJson("0")))
        assertEquals(0, TlJson.toJson(reply, unfiltered).getInt("quote_offset"))
        assertNull(handles.tlSet(handle, "quote_offset", PluginWire.encodeNull()))
        assertTrue(!TlJson.toJson(reply, unfiltered).has("quote_offset"))
    }
}
