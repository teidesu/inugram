package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.ApiFilter
import desu.inugram.core.plugins.TlCtorIds
import desu.inugram.core.plugins.TlNames
import desu.inugram.core.plugins.TlWire
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
    fun `a login code from the service account is redacted on read`() {
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
    fun `a login code in the compressed update form is redacted too`() {
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
    fun `the compressed form's redaction evidence cannot be cleared through a writable view`() {
        val (handles, mint) = view()
        val short = TLRPC.TL_updateShortMessage().apply {
            user_id = 777000L
            message = "Login code: 63527."
        }
        val handle = mint(short)

        // the short form names its sender with bare ids, so these are the fields the verdict reads
        assertPluginError("forbidden", handles.tlSet(handle, "user_id", TlWire.encodeJson("1")))
        assertPluginError("forbidden", handles.tlSet(handle, "out", TlWire.encodeJson("true")))
        assertPluginError("forbidden", handles.tlSet(handle, "chat_id", TlWire.encodeJson("5")))
        assertEquals(777000L, short.user_id)
        assertEquals("Login code: *****.", stringOf(handles.tlGet(handle, "message")))
    }

    /** an outgoing short message is the user's own text, and nothing about it is a service message */
    @Test
    fun `an ordinary compressed update still reads in clear`() {
        val (handles, mint) = view()
        val short = TLRPC.TL_updateShortMessage().apply {
            user_id = 4242L
            message = "Login code: 63527."
        }

        assertEquals("Login code: 63527.", stringOf(handles.tlGet(mint(short), "message")))
    }

    @Test
    fun `the same message reads in clear for a plugin holding unsafe disableApiFiltering`() {
        val (handles, mint) = view(filtering = false)
        val message = serviceMessage("Login code: 63527.")

        assertEquals("Login code: 63527.", stringOf(handles.tlGet(mint(message), "message")))
    }

    @Test
    fun `redaction evidence cannot be cleared through a writable view`() {
        val (handles, mint) = view()
        val message = serviceMessage("Login code: 63527.")
        val handle = mint(message)

        // this is the attack: drop the sender, then re-read the text with nothing to key redaction on
        val refusal = handles.tlSet(handle, "from_id", TlWire.encodeNull())

        assertPluginError("forbidden", refusal)
        assertEquals(777000L, (message.from_id as TLRPC.TL_peerUser).user_id)
        assertEquals("Login code: *****.", stringOf(handles.tlGet(handle, "message")))
    }

    @Test
    fun `every field the verdict is keyed on is sealed, not just from_id`() {
        val (handles, mint) = view()
        val message = serviceMessage("Login code: 63527.")
        message.fwd_from = TLRPC.TL_messageFwdHeader()
        val handle = mint(message)

        for (field in listOf("from_id", "peer_id", "fwd_from")) {
            assertPluginError("forbidden", handles.tlSet(handle, field, TlWire.encodeNull()))
        }
        assertPluginError("forbidden", handles.tlSet(handle, "out", TlWire.encodeBool(true)))
    }

    @Test
    fun `the peer behind a sealed field is handed out read-only`() {
        val (handles, mint) = view()
        val message = serviceMessage("Login code: 63527.")
        val handle = mint(message)

        // one level down: `m.from_id.user_id = 0` would defeat the verdict just as well
        val child = handleOf(handles.tlGet(handle, "from_id"))
        assertTrue(child.readOnly, "from_id's peer must not be writable while filtering is on")

        assertPluginError("forbidden", handles.tlSet(child.id, "user_id", TlWire.encodeJson("0")))
        assertEquals(777000L, (message.from_id as TLRPC.TL_peerUser).user_id)
        assertEquals("Login code: *****.", stringOf(handles.tlGet(handle, "message")))
    }

    @Test
    fun `a writable view of an ordinary message still writes through`() {
        val (handles, mint) = view()
        val message = TLRPC.TL_message().apply {
            id = 7
            message = "hi"
            from_id = peerUser(42L)
            peer_id = peerUser(43L)
        }.synced()
        val handle = mint(message)

        assertNull(handles.tlSet(handle, "message", TlWire.encodeJson("\"edited\"")))
        assertEquals("edited", message.message)
        assertNull(handles.tlSet(handle, "id", TlWire.encodeJson("8")))
        assertEquals(8, message.id)
        // the seal is not conditional on the verdict: it is recomputed on every read, so a message
        // that is not from a service peer now may be one after a write the seal is what refuses
        assertTrue(handleOf(handles.tlGet(handle, "from_id")).readOnly)
    }

    @Test
    fun `nothing is sealed while api filtering is off`() {
        val (handles, mint) = view(filtering = false)
        val message = serviceMessage("Login code: 63527.")
        val handle = mint(message)

        assertFalse(handleOf(handles.tlGet(handle, "from_id")).readOnly)
        assertNull(handles.tlSet(handle, "from_id", TlWire.encodeNull()))
        assertNull(message.from_id)
    }

    @Test
    fun `a hidden field reads as absent and refuses writes`() {
        val (handles, mint) = view()
        val notification = TL_update.TL_updateServiceNotification().apply {
            message = "your code is 63527"
            type = "auth"
        }
        val handle = mint(notification)

        assertEquals(TlWire.Value.Null, TlWire.decode(handles.tlGet(handle, "message")))
        assertEquals(0, handles.tlHas(handle, "message"))
        assertFalse(handles.tlOwnKeys(handle)!!.split(",").contains("message"))
        assertTrue(handles.tlSet(handle, "message", TlWire.encodeJson("\"x\""))!!.contains("no such field"))
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
    fun `a variant that does not read as its own wire name is hidden all the same`() {
        val (handles, mint) = view()
        val variant = TL_updateServiceNotification_old().apply { message = "your code is 63527" }

        assertEquals(
            "updateServiceNotification_old",
            TlNames.classNameToTlName(variant.javaClass),
            "the case only proves anything while this class calls itself something else",
        )
        val handle = mint(variant)
        assertEquals(TlWire.Value.Null, TlWire.decode(handles.tlGet(handle, "message")))
        assertEquals(0, handles.tlHas(handle, "message"))
        assertEquals("your code is 63527", variant.message, "the app's own object stays intact")
    }

    @Test
    fun `every constructor id a hidden type carries is matched, not just the one its class declares`() {
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
    fun `the snapshot path filters exactly like the live view`() {
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
    fun `redaction follows the sender through fwd_from and through the dialog peer`() {
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
