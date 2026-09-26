package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlJson
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Test

class TlJsonTest {
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
}
