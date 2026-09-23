package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlHandles
import desu.inugram.helpers.plugins.tl.TlNames
import desu.inugram.core.plugins.TlTables
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_update

class TlFilterViewTest {
    @Before
    fun setUp() = resetBridge()

    private fun view(filtering: Boolean = true): Pair<TlHandles, (Any) -> Long> {
        val handles = TlHandles(TlFilter.Policy(takeover = filtering, drafts = true))
        return handles to { target -> handles.mintForScope(target, TlHandles.newScope()) }
    }

    @Test
    fun a_login_code_from_the_service_account_is_redacted_on_read() {
        val (handles, mint) = view()
        val message = serviceMessage("Login code: 63527. Do not give this code to anyone.")

        val read = decodeString(handles.tlGet(mint(message), "message"))

        assertEquals("Login code: *****. Do not give this code to anyone.", read)
        assertEquals(
            "Login code: 63527. Do not give this code to anyone.",
            message.message,
            "the app's own object must never be rewritten",
        )
    }

    /** stock delivers 1:1 messages as `updateShortMessage`, text on `Updates` rather than a `Message` */
    @Test
    fun a_login_code_in_the_compressed_update_form_is_redacted_too() {
        val (handles, mint) = view()
        val short = TLRPC.TL_updateShortMessage().apply {
            user_id = 777000L
            message = "Login code: 63527. Do not give this code to anyone."
        }

        assertEquals(
            "Login code: *****. Do not give this code to anyone.",
            decodeString(handles.tlGet(mint(short), "message")),
        )
        assertEquals(
            "Login code: 63527. Do not give this code to anyone.",
            short.message,
            "the app's own object must never be rewritten",
        )
    }

    @Test
    fun the_compressed_form_s_redaction_evidence_cannot_be_cleared_through_a_writable_view() {
        val (handles, mint) = view()
        val short = TLRPC.TL_updateShortMessage().apply {
            user_id = 777000L
            message = "Login code: 63527."
        }
        val handle = mint(short)

        assertPluginError("forbidden", handles.tlSet(handle, "user_id", PluginWire.encodeJson("1")))
        assertPluginError("forbidden", handles.tlSet(handle, "out", PluginWire.encodeJson("true")))
        assertPluginError("forbidden", handles.tlSet(handle, "chat_id", PluginWire.encodeJson("5")))
        assertEquals(777000L, short.user_id)
        assertEquals("Login code: *****.", decodeString(handles.tlGet(handle, "message")))
    }

    @Test
    fun an_ordinary_compressed_update_still_reads_in_clear() {
        val (handles, mint) = view()
        val short = TLRPC.TL_updateShortMessage().apply {
            user_id = 4242L
            message = "Login code: 63527."
        }

        assertEquals("Login code: 63527.", decodeString(handles.tlGet(mint(short), "message")))
    }

    @Test
    fun every_field_the_verdict_is_keyed_on_is_sealed_not_just_from_id() {
        val (handles, mint) = view()
        val message = serviceMessage("Login code: 63527.")
        message.fwd_from = TLRPC.TL_messageFwdHeader()
        val handle = mint(message)

        for (field in listOf("from_id", "peer_id", "fwd_from")) {
            assertPluginError("forbidden", handles.tlSet(handle, field, PluginWire.encodeNull()))
        }
        assertPluginError("forbidden", handles.tlSet(handle, "out", PluginWire.encodeBool(true)))
        assertEquals(777000L, (message.from_id as TLRPC.TL_peerUser).user_id)
        assertEquals("Login code: *****.", decodeString(handles.tlGet(handle, "message")))
    }

    @Test
    fun the_peer_behind_a_sealed_field_is_handed_out_read_only() {
        val (handles, mint) = view()
        val message = serviceMessage("Login code: 63527.")
        val handle = mint(message)

        val child = decodeHandle(handles.tlGet(handle, "from_id"))
        assertTrue(child.readOnly, "from_id's peer must not be writable while filtering is on")

        assertPluginError("forbidden", handles.tlSet(child.id, "user_id", PluginWire.encodeJson("0")))
        assertEquals(777000L, (message.from_id as TLRPC.TL_peerUser).user_id)
        assertEquals("Login code: *****.", decodeString(handles.tlGet(handle, "message")))
    }

    @Test
    fun a_writable_view_of_an_ordinary_message_still_writes_through() {
        val (handles, mint) = view()
        val message = TLRPC.TL_message().apply {
            id = 7
            message = "hi"
            from_id = peerUser(42L)
            peer_id = peerUser(43L)
        }.synced()
        val handle = mint(message)

        assertNull(handles.tlSet(handle, "message", PluginWire.encodeJson("\"edited\"")))
        assertEquals("edited", message.message)
        assertNull(handles.tlSet(handle, "id", PluginWire.encodeJson("8")))
        assertEquals(8, message.id)
        // sealed regardless of the verdict: a write could make this a service message
        assertTrue(decodeHandle(handles.tlGet(handle, "from_id")).readOnly)
    }

    @Test
    fun with_api_filtering_off_a_login_code_reads_in_clear_and_nothing_is_sealed() {
        val (handles, mint) = view(filtering = false)
        val message = serviceMessage("Login code: 63527.")
        val handle = mint(message)

        assertEquals("Login code: 63527.", decodeString(handles.tlGet(handle, "message")))
        assertFalse(decodeHandle(handles.tlGet(handle, "from_id")).readOnly)
        assertNull(handles.tlSet(handle, "from_id", PluginWire.encodeNull()))
        assertNull(message.from_id)
    }

    @Test
    fun a_hidden_field_reads_as_absent_and_refuses_writes() {
        val (handles, mint) = view()
        val notification = TL_update.TL_updateServiceNotification().apply {
            message = "your code is 63527"
            type = "auth"
        }
        val handle = mint(notification)

        assertEquals(PluginWire.Value.Null, PluginWire.decode(handles.tlGet(handle, "message")))
        assertEquals(0, handles.tlHas(handle, "message"))
        assertFalse(handles.tlOwnKeys(handle)!!.split(",").contains("message"))
        assertTrue(handles.tlSet(handle, "message", PluginWire.encodeJson("\"x\""))!!.contains("no such field"))
        assertEquals("your code is 63527", notification.message, "the app's own object stays intact")
    }

    /** stock legacy variants are separate classes named e.g. `message_old7`, so a check by name misses them */
    private class TL_updateServiceNotification_old : TL_update.TL_updateServiceNotification()

    @Test
    fun a_variant_that_does_not_read_as_its_own_wire_name_is_hidden_all_the_same() {
        val (handles, mint) = view()
        val variant = TL_updateServiceNotification_old().apply { message = "your code is 63527" }

        assertEquals(
            "updateServiceNotification_old",
            TlNames.classNameToTlName(variant.javaClass),
            "the case only proves anything while this class calls itself something else",
        )
        val handle = mint(variant)
        assertEquals(PluginWire.Value.Null, PluginWire.decode(handles.tlGet(handle, "message")))
        assertEquals(0, handles.tlHas(handle, "message"))
        assertEquals("your code is 63527", variant.message, "the app's own object stays intact")
    }

    @Test
    fun every_constructor_id_a_hidden_type_carries_is_matched_not_just_the_one_its_class_declares() {
        // no hidden type has legacy variants today, so `message` stands in for one
        val family = TlTables.getConstructorIds("message")!!
        assertTrue(family.size > 1, "'message' lost its legacy variants; pick another predicate")
        assertEquals(family, TlFilter.indexByCtorId(setOf("message")).keys)

        for (name in TlFilter.HIDDEN_FIELDS.keys) {
            val ids = TlTables.getConstructorIds(name) ?: fail("'$name' is not a constructor this build has")
            assertEquals(ids, TlFilter.indexByCtorId(setOf(name)).keys)
        }
    }

    @Test
    fun the_snapshot_path_filters_exactly_like_the_live_view() {
        val message = serviceMessage("Login code: 63527.")
        val notification = TL_update.TL_updateServiceNotification()
        notification.message = "code 63527"

        val snapshot = JSONObject(TlHandles(TlFilter.Policy(takeover = true, drafts = true)).let { handles ->
            handles.tlCopy(handles.mintForScope(message, TlHandles.newScope()))!!
        })
        assertEquals("Login code: *****.", snapshot.getString("message"))

        val hidden = JSONObject(TlHandles(TlFilter.Policy(takeover = true, drafts = true)).let { handles ->
            handles.tlCopy(handles.mintForScope(notification, TlHandles.newScope()))!!
        })
        assertFalse(hidden.has("message"), "a hidden field must not ride along in a toJSON() copy")
    }

    @Test
    fun redaction_follows_the_sender_through_fwd_from_and_through_the_dialog_peer() {
        val (handles, mint) = view()

        val forwarded = TLRPC.TL_message().apply {
            message = "code 63527"
            from_id = peerUser(42L)
            peer_id = peerUser(42L)
            fwd_from = TLRPC.TL_messageFwdHeader().apply { from_id = peerUser(777000L) }.synced()
        }.synced()
        assertEquals("code *****", decodeString(handles.tlGet(mint(forwarded), "message")))

        // `from_id` is flags.8?Peer and the server omits it in a 1:1 dialog
        val incoming = TLRPC.TL_message().apply {
            message = "code 63527"
            peer_id = peerUser(777000L)
            out = false
        }.synced()
        assertEquals("code *****", decodeString(handles.tlGet(mint(incoming), "message")))
    }
}
