package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.ApiFilter
import desu.inugram.core.plugins.TlCtorIds
import desu.inugram.core.plugins.TlNames
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlHandles
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

/**
 * The account-takeover filter as a plugin actually meets it: through a live [TlHandles] view and
 * through a [TlJson] snapshot. Everything here fails open if a guard is removed, which is the point
 * - the policy itself is `:InuCore`'s `ApiFilterTest`, this is the wiring that applies it.
 */
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

        val read = stringOf(handles.tlGet(mint(message), "message"))

        assertEquals("Login code: *****. Do not give this code to anyone.", read)
        assertEquals(
            "Login code: 63527. Do not give this code to anyone.",
            message.message,
            "the app's own object must never be rewritten",
        )
    }

    /**
     * `updateShortMessage` is the form a 1:1 message actually arrives in, and it carries the text on
     * `Updates` rather than in a `Message` - so a redaction predicate keyed on the `Message` class
     * misses the one shape a login code reaches the device in. The update fan-out never shows this
     * object to a plugin (`PluginRpc.normalizeShortMessage` builds a synthetic `TL_message` first),
     * but `interceptDeserialize` materializes the real one.
     */
    @Test
    fun a_login_code_in_the_compressed_update_form_is_redacted_too() {
        val (handles, mint) = view()
        val short = TLRPC.TL_updateShortMessage().apply {
            user_id = 777000L
            message = "Login code: 63527. Do not give this code to anyone."
        }

        assertEquals(
            "Login code: *****. Do not give this code to anyone.",
            stringOf(handles.tlGet(mint(short), "message")),
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

        // the short form names its sender with bare ids, so these are the fields the verdict reads
        assertPluginError("forbidden", handles.tlSet(handle, "user_id", PluginWire.encodeJson("1")))
        assertPluginError("forbidden", handles.tlSet(handle, "out", PluginWire.encodeJson("true")))
        assertPluginError("forbidden", handles.tlSet(handle, "chat_id", PluginWire.encodeJson("5")))
        assertEquals(777000L, short.user_id)
        assertEquals("Login code: *****.", stringOf(handles.tlGet(handle, "message")))
    }

    /** an outgoing short message is the user's own text, and nothing about it is a service message */
    @Test
    fun an_ordinary_compressed_update_still_reads_in_clear() {
        val (handles, mint) = view()
        val short = TLRPC.TL_updateShortMessage().apply {
            user_id = 4242L
            message = "Login code: 63527."
        }

        assertEquals("Login code: 63527.", stringOf(handles.tlGet(mint(short), "message")))
    }

    @Test
    fun the_same_message_reads_in_clear_for_a_plugin_holding_unsafe_disableApiFiltering() {
        val (handles, mint) = view(filtering = false)
        val message = serviceMessage("Login code: 63527.")

        assertEquals("Login code: 63527.", stringOf(handles.tlGet(mint(message), "message")))
    }

    @Test
    fun redaction_evidence_cannot_be_cleared_through_a_writable_view() {
        val (handles, mint) = view()
        val message = serviceMessage("Login code: 63527.")
        val handle = mint(message)

        // this is the attack: drop the sender, then re-read the text with nothing to key redaction on
        val refusal = handles.tlSet(handle, "from_id", PluginWire.encodeNull())

        assertPluginError("forbidden", refusal)
        assertEquals(777000L, (message.from_id as TLRPC.TL_peerUser).user_id)
        assertEquals("Login code: *****.", stringOf(handles.tlGet(handle, "message")))
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
    }

    @Test
    fun the_peer_behind_a_sealed_field_is_handed_out_read_only() {
        val (handles, mint) = view()
        val message = serviceMessage("Login code: 63527.")
        val handle = mint(message)

        // one level down: `m.from_id.user_id = 0` would defeat the verdict just as well
        val child = handleOf(handles.tlGet(handle, "from_id"))
        assertTrue(child.readOnly, "from_id's peer must not be writable while filtering is on")

        assertPluginError("forbidden", handles.tlSet(child.id, "user_id", PluginWire.encodeJson("0")))
        assertEquals(777000L, (message.from_id as TLRPC.TL_peerUser).user_id)
        assertEquals("Login code: *****.", stringOf(handles.tlGet(handle, "message")))
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
        // the seal is not conditional on the verdict: it is recomputed on every read, so a message
        // that is not from a service peer now may be one after a write the seal is what refuses
        assertTrue(handleOf(handles.tlGet(handle, "from_id")).readOnly)
    }

    @Test
    fun nothing_is_sealed_while_api_filtering_is_off() {
        val (handles, mint) = view(filtering = false)
        val message = serviceMessage("Login code: 63527.")
        val handle = mint(message)

        assertFalse(handleOf(handles.tlGet(handle, "from_id")).readOnly)
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

    /**
     * a legacy variant is a class of its own, and it does not read as the name the schema gave the
     * predicate: `TL_message_old7` is `message` on the wire but calls itself `message_old7`. so the
     * hidden-field table is keyed on a wire name and *matched* on the constructor ids that name
     * carries, and a check by name walks past every variant of a hidden type.
     */
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
        // `message` stands in for a hidden type with legacy variants, which none of the real ones
        // has: without it this asserts nothing, every hidden type today having a single id
        val family = TlCtorIds.idsOf("message")!!
        assertTrue(family.size > 1, "'message' lost its legacy variants; pick another predicate")
        assertEquals(family, TlFilter.indexByCtorId(setOf("message")).keys)

        for (name in ApiFilter.HIDDEN_FIELDS.keys) {
            val ids = TlCtorIds.idsOf(name) ?: fail("'$name' is not a constructor this build has")
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
        assertEquals("code *****", stringOf(handles.tlGet(mint(forwarded), "message")))

        // `from_id` is flags.8?Peer and the server omits it in a 1:1 dialog
        val incoming = TLRPC.TL_message().apply {
            message = "code 63527"
            peer_id = peerUser(777000L)
            out = false
        }.synced()
        assertEquals("code *****", stringOf(handles.tlGet(mint(incoming), "message")))
    }
}
