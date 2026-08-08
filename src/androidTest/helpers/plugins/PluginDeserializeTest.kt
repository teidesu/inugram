package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.TlCtorIds
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.telegram.PluginDeserialize
import desu.inugram.helpers.plugins.tl.TlReflect
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

/**
 * `inu.interceptDeserialize`'s declarative tier. The engine's half is `deserialize.rs`'s; what is
 * decided here is everything only the host knows - which constructors and fields exist, which of
 * them a rule may not touch, and what a rule does to an object stock just parsed.
 *
 * The refusals are the point rather than a footnote: a rewritten object goes back to sqlite, so a
 * rule this file lets through wrong is one the user cannot restart away from.
 */
class PluginDeserializeTest {
    @Before
    fun setUp() {
        resetBridge()
    }

    private fun granted(vararg scopes: String): Plugin =
        startPlugin("deserialize", "interceptDeserialize(${scopes.joinToString(",")})")

    private fun Plugin.rules(json: String, callbackId: Int = 1): String? =
        js.listener!!.onDeserializeRegister(callbackId, json)

    private fun Plugin.dropRules(callbackId: Int = 1) =
        js.listener!!.onDeserializeUnregister(callbackId)

    /** the wire `deserialize.rs` builds: `type` is always an array and `when` always present */
    private fun rule(type: String, set: String, matching: String = "{}"): String =
        """[{"type":["$type"],"when":$matching,"set":$set}]"""

    private fun ctorOf(cls: Class<*>): Int =
        cls.getDeclaredField("constructor").also { it.isAccessible = true }.getInt(null)

    /** what stock's `TLObject.TLdeserialize` does with an object it has just read */
    private fun <T : TLObject> T.deserialized(): T = apply { PluginDeserialize.apply(this, ctorOf(javaClass)) }

    @Test
    fun a_rule_rewrites_the_object_stock_just_parsed() {
        val plugin = granted("user")
        assertNull(plugin.rules(rule("user", """{"premium":true}""")))
        assertTrue(TLRPC.TL_user().deserialized().premium)
    }

    @Test
    fun nothing_is_rewritten_while_no_rule_is_live() {
        assertNull(PluginDeserialize.rules, "the hot path must cost one null check with no plugins")
        assertFalse(TLRPC.TL_user().deserialized().premium)
    }

    @Test
    fun a_when_that_does_not_hold_leaves_the_object_alone() {
        val plugin = granted("user")
        assertNull(plugin.rules(rule("user", """{"premium":true}""", """{"bot":true}""")))
        assertFalse(TLRPC.TL_user().deserialized().premium, "the matcher did not hold")
        assertTrue(TLRPC.TL_user().apply { bot = true }.deserialized().premium)
    }

    @Test
    fun a_rule_reaches_every_constructor_of_the_family_it_names() {
        val plugin = granted("user")
        assertNull(plugin.rules(rule("user", """{"premium":true}""")))
        assertTrue(TLRPC.TL_user_layer218().deserialized().premium, "a legacy row is still parsed at startup")
    }

    @Test
    fun a_rule_never_reaches_a_constructor_it_did_not_name() {
        val plugin = granted("user")
        assertNull(plugin.rules(rule("user", """{"premium":true}""")))
        val chat = TLRPC.TL_chat().deserialized()
        assertFalse(chat.creator)
    }

    @Test
    fun setting_an_optional_field_sets_the_bit_that_gates_it() {
        val plugin = granted("userFull")
        assertNull(plugin.rules(rule("userFull", """{"about":"rewritten"}""")))
        val full = TLRPC.TL_userFull().deserialized()
        assertEquals("rewritten", full.about)
        // without the bit the app re-serializes the row without the field, so the rewrite would
        // survive in memory and vanish the moment it was written back
        assertTrue(flagBitIsSet(full, "about"), "'about' is flags-gated and its bit must follow the value")
    }

    @Test
    fun a_long_takes_the_string_form_a_js_number_cannot_hold() {
        val plugin = granted("document")
        assertNull(plugin.rules(rule("document", """{"size":"9007199254740993"}""")))
        assertEquals(9007199254740993L, TLRPC.TL_document().deserialized().size)
    }

    @Test
    fun every_reference_shaped_field_on_every_rule_target_is_refused() {
        val plugin = granted("user", "message", "document", "photo")
        val refused = listOf(
            "user" to "id",
            "user" to "access_hash",
            "message" to "id",
            "message" to "via_bot_id",
            "document" to "dc_id",
            "document" to "file_reference",
            "photo" to "id",
        )
        for ((type, field) in refused) {
            assertPluginError("forbidden", plugin.rules(rule(type, """{"$field":1}""")))
        }
        assertNull(PluginDeserialize.rules, "not one of those may have registered")
        // the control: a field that describes rather than addresses is fine on the same class
        assertNull(plugin.rules(rule("document", """{"mime_type":"image/png"}""")))
    }

    @Test
    fun a_flag_word_cannot_be_named_at_all() {
        val plugin = granted("user")
        assertPluginError("forbidden", plugin.rules(rule("user", """{"flags":3}""")))
        assertPluginError("forbidden", plugin.rules(rule("user", """{"premium":true}""", """{"flags":3}""")))
    }

    @Test
    fun a_secret_message_is_not_reachable_through_the_family_that_carries_it() {
        val plugin = granted("message")
        assertNull(plugin.rules(rule("message", """{"pinned":true}""")))
        assertTrue(TLRPC.TL_message().deserialized().pinned)
        for (cls in secretMessageClasses()) {
            val instance = cls.getDeclaredConstructor().newInstance() as TLRPC.Message
            PluginDeserialize.apply(instance, ctorOf(cls))
            assertFalse(instance.pinned, "${cls.simpleName} is secret-chat traffic and no rule may touch it")
        }
    }

    @Test
    fun naming_secret_chat_traffic_outright_is_refused() {
        val plugin = granted("encryptedMessage", "decryptedMessage", "message_secret")
        for (name in listOf("encryptedMessage", "decryptedMessage", "message_secret")) {
            assertPluginError("forbidden", plugin.rules(rule(name, """{"date":1}""")))
        }
    }

    /**
     * stock's own classes rather than a list restated here, so a rebase that adds a secret variant
     * fails this instead of quietly widening the api
     */
    private fun secretMessageClasses(): List<Class<*>> =
        TLRPC::class.java.declaredClasses.filter { it.simpleName.startsWith("TL_message_secret") }

    @Test
    fun the_harness_has_the_secret_classes_this_is_about() {
        assertEquals(3, secretMessageClasses().size, "stock declares TL_message_secret{,_old,_layer72}")
    }

    /**
     * the stripping has to take the three and only the three, or `message` either leaks secret-chat
     * content or stops being a rule target worth having
     */
    @Test
    fun stripping_the_secret_constructors_leaves_the_rest_of_the_family() {
        val plugin = granted("message")
        assertNull(plugin.rules(rule("message", """{"pinned":true}""")))
        val declared = TlCtorIds.idsOf("message")!!.size
        assertEquals(declared - 3, PluginDeserialize.rules!!.tableSize())
    }

    @Test
    fun an_auth_constructor_is_not_a_rule_target() {
        val plugin = granted("auth.authorization")
        assertPluginError("forbidden", plugin.rules(rule("auth.authorization", """{"tmp_sessions":1}""")))
    }

    @Test
    fun a_constructor_whose_fields_the_api_filter_hides_is_not_a_rule_target() {
        val plugin = granted("updateServiceNotification", "config")
        assertPluginError("forbidden", plugin.rules(rule("updateServiceNotification", """{"popup":true}""")))
        assertPluginError("forbidden", plugin.rules(rule("config", """{"test_mode":true}""")))
    }

    @Test
    fun disableApiFiltering_lifts_the_takeover_refusal_and_nothing_else() {
        val plugin = startPlugin(
            "deserialize",
            "interceptDeserialize(auth.authorization,message)",
            "unsafe.disableApiFiltering",
        )
        assertNull(plugin.rules(rule("auth.authorization", """{"tmp_sessions":1}""")))
        // the redaction seal is part of the filter, so it goes too
        assertNull(plugin.rules(rule("message", """{"out":true}"""), callbackId = 2))
        // the addressing guard is not a filter and nothing lifts it
        assertPluginError("forbidden", plugin.rules(rule("message", """{"id":1}"""), callbackId = 3))
    }

    @Test
    fun matching_on_redacted_message_text_is_refused() {
        val plugin = granted("message")
        // the value is redacted per object, so whether a rule fired is a confirmed guess of it
        assertPluginError("forbidden", plugin.rules(rule("message", """{"pinned":true}""", """{"message":"Login code: 12345"}""")))
        assertNull(plugin.rules(rule("message", """{"message":"rewritten"}""")), "setting it leaks nothing")
    }

    @Test
    fun a_redaction_evidence_field_cannot_be_set() {
        val plugin = granted("message")
        assertPluginError("forbidden", plugin.rules(rule("message", """{"out":true}""")))
    }

    /**
     * both are `invalid-argument`, and that is the point: a plugin that could tell "there is no
     * such field" from "there is, and you may not have it" would have read what the filter withheld.
     * So the message is what this asserts, not the code.
     */
    @Test
    fun a_draft_field_is_not_there_without_the_grant_that_hands_drafts_out() {
        val plugin = granted("dialog")
        assertTrue(
            messageOf(plugin.rules(rule("dialog", """{"draft":null}"""))).contains("'dialog' has no field 'draft'"),
            "a filtered field has to read as absent here too",
        )
        val allowed = startPlugin("deserialize", "interceptDeserialize(dialog)", "account.read(draft)")
        assertTrue(
            messageOf(allowed.rules(rule("dialog", """{"draft":null}"""))).contains("no constant can be"),
            "with the grant the field is there, and it is the type that refuses it",
        )
    }

    private fun messageOf(wire: String?): String =
        (desu.inugram.core.plugins.PluginWire.decode(wire ?: "N") as desu.inugram.core.plugins.PluginWire.Value.PluginErr).message

    @Test
    fun a_name_that_is_not_a_constructor_is_refused() {
        val plugin = granted("nope")
        assertPluginError("invalid-argument", plugin.rules(rule("nope", """{"premium":true}""")))
    }

    @Test
    fun an_rpc_method_is_refused_rather_than_silently_never_firing() {
        val plugin = granted("users.getUsers")
        assertTrue("users.getUsers" in TlCtorIds.methodNames)
        assertPluginError("invalid-argument", plugin.rules(rule("users.getUsers", """{"id":1}""")))
    }

    @Test
    fun a_field_the_constructor_does_not_have_is_refused() {
        val plugin = granted("user")
        assertPluginError("invalid-argument", plugin.rules(rule("user", """{"nope":true}""")))
    }

    @Test
    fun a_constant_that_does_not_fit_the_field_is_refused() {
        val plugin = granted("user")
        assertPluginError("invalid-argument", plugin.rules(rule("user", """{"premium":"yes"}""")))
        assertPluginError("invalid-argument", plugin.rules(rule("user", """{"first_name":5}""")))
        assertPluginError("invalid-argument", plugin.rules(rule("user", """{"premium":null}""")))
    }

    @Test
    fun a_field_no_constant_can_be_is_refused() {
        val plugin = granted("user")
        assertPluginError("invalid-argument", plugin.rules(rule("user", """{"photo":null}""")))
        assertPluginError("invalid-argument", plugin.rules(rule("user", """{"restriction_reason":null}""")))
        // null is a value for a string and a structural rewrite for anything else
        assertNull(plugin.rules(rule("user", """{"first_name":null}""")))
        assertNull(TLRPC.TL_user().apply { first_name = "x" }.deserialized().first_name)
    }

    @Test
    fun a_required_field_cannot_be_cleared_since_stock_never_sees_one_that_is_not_there() {
        // `writeString(null)` substitutes "", so the row is blanked for good the first time stock
        // saves the object back; before that a null is a shape stock does not guard everywhere
        assertPluginError("invalid-argument", granted("message").rules(rule("message", """{"message":null}""")))
        assertPluginError("invalid-argument", granted("chat").rules(rule("chat", """{"title":null}""")))
        assertEquals(
            "x",
            TLRPC.TL_message().apply { message = "x" }.deserialized().message,
            "the refusal registers nothing, so the object is untouched",
        )
    }

    @Test
    fun clearing_an_optional_string_is_what_null_is_for() {
        val plugin = granted("user")
        assertNull(plugin.rules(rule("user", """{"username":null}""")))
        val user = TLRPC.TL_user().apply { username = "someone"; flags = 1 shl 3 }.deserialized()
        assertNull(user.username)
        assertEquals(0, user.flags and (1 shl 3), "clearing a field clears the bit that gates it")
    }

    @Test
    fun a_type_outside_the_grant_scope_is_refused_by_the_host_too() {
        val plugin = granted("user")
        assertPluginError("not-granted", plugin.rules(rule("message", """{"pinned":true}""")))
    }

    @Test
    fun a_refused_rule_set_registers_nothing() {
        val plugin = granted("user")
        assertPluginError("forbidden", plugin.rules(rule("user", """{"id":1}""")))
        assertNull(PluginDeserialize.rules)
        assertFalse(TLRPC.TL_user().deserialized().premium)
    }

    @Test
    fun disposing_a_registration_takes_its_rules_out_of_the_table() {
        val plugin = granted("user")
        assertNull(plugin.rules(rule("user", """{"premium":true}""")))
        plugin.dropRules()
        assertNull(PluginDeserialize.rules, "the last registration going away must clear the hot path")
        assertFalse(TLRPC.TL_user().deserialized().premium)
    }

    @Test
    fun unloading_the_plugin_takes_its_rules_with_it() {
        val plugin = granted("user")
        assertNull(plugin.rules(rule("user", """{"premium":true}""")))
        PluginDeserialize.detach(plugin.js)
        assertNull(PluginDeserialize.rules, "a dead plugin must not keep rewriting what the app parses")
        assertFalse(TLRPC.TL_user().deserialized().premium)
    }

    @Test
    fun two_registrations_both_apply_and_one_going_away_leaves_the_other() {
        val plugin = granted("user")
        assertNull(plugin.rules(rule("user", """{"premium":true}"""), callbackId = 1))
        assertNull(plugin.rules(rule("user", """{"bot":true}"""), callbackId = 2))
        val both = TLRPC.TL_user().deserialized()
        assertTrue(both.premium && both.bot)
        plugin.dropRules(callbackId = 1)
        val one = TLRPC.TL_user().deserialized()
        assertFalse(one.premium)
        assertTrue(one.bot)
    }

    private fun Plugin.middleware(typesJson: String, callbackId: Int = 1): String? =
        js.listener!!.onDeserializeMiddlewareRegister(callbackId, typesJson)

    private fun Plugin.dropMiddleware(callbackId: Int = 1) =
        js.listener!!.onDeserializeMiddlewareUnregister(callbackId)

    /**
     * The parsing thread blocks on a `globalQueue` runnable, so a test that called [apply] on its
     * own thread would deadlock against a queue only it can pump. This is the real shape: the parse
     * on another thread, the queue pumped here.
     */
    private fun parseOffThread(obj: TLObject) {
        val thread = Thread { PluginDeserialize.apply(obj, ctorOf(obj.javaClass)) }
        thread.start()
        while (thread.isAlive) drain()
        thread.join()
    }

    @Test
    fun a_middleware_is_handed_a_writable_view_of_the_object_stock_just_parsed() {
        val plugin = granted("user")
        plugin.js.onDispatchDeserialize = { dispatch ->
            plugin.tl().tlSet(handleId(dispatch.objectWire), "premium", PluginWire.encodeJson("true"))
        }
        assertNull(plugin.middleware("""["user"]"""))
        val user = TLRPC.TL_user()
        parseOffThread(user)
        assertEquals(1, plugin.js.deserializeDispatches.size)
        assertTrue(user.premium, "what the middleware wrote is what the app keeps")
    }

    /**
     * the one refusal the middleware tier gets for free rather than by restating it: the guard is on
     * the handle, so it holds for a field reached one level down too
     */
    @Test
    fun a_middleware_cannot_rewrite_what_addresses_the_object() {
        val plugin = granted("user")
        var refusal: String? = null
        plugin.js.onDispatchDeserialize = { dispatch ->
            refusal = plugin.tl().tlSet(handleId(dispatch.objectWire), "access_hash", PluginWire.encodeJson("7"))
        }
        assertNull(plugin.middleware("""["user"]"""))
        val user = TLRPC.TL_user().apply { access_hash = 1 }
        parseOffThread(user)
        assertPluginError("forbidden", refusal)
        assertEquals(1L, user.access_hash)
    }

    @Test
    fun the_view_a_middleware_was_handed_dies_with_the_dispatch() {
        val plugin = granted("user")
        var handle = 0L
        plugin.js.onDispatchDeserialize = { dispatch ->
            handle = handleId(dispatch.objectWire)
        }
        assertNull(plugin.middleware("""["user"]"""))
        parseOffThread(TLRPC.TL_user())
        assertEquals(PluginWire.encodeExpired(), plugin.tl().tlGet(handle, "premium"))
    }

    @Test
    fun a_middleware_only_sees_the_constructors_it_named() {
        val plugin = granted("user")
        assertNull(plugin.middleware("""["user"]"""))
        parseOffThread(TLRPC.TL_chat())
        assertTrue(plugin.js.deserializeDispatches.isEmpty())
        parseOffThread(TLRPC.TL_user())
        assertEquals(1, plugin.js.deserializeDispatches.size)
    }

    @Test
    fun a_middleware_over_a_type_no_plugin_api_reaches_is_refused_like_a_rule_is() {
        val plugin = granted("encryptedMessage", "auth.authorization", "invokeWithLayer", "nosuchthing")
        assertPluginError("forbidden", plugin.middleware("""["encryptedMessage"]"""))
        assertPluginError("forbidden", plugin.middleware("""["auth.authorization"]"""))
        assertPluginError("invalid-argument", plugin.middleware("""["invokeWithLayer"]"""))
        assertPluginError("invalid-argument", plugin.middleware("""["nosuchthing"]"""))
        assertNull(PluginDeserialize.middleware, "not one of those may have registered")
    }

    @Test
    fun a_middleware_over_a_type_the_grant_does_not_name_is_refused() {
        val plugin = granted("user")
        assertPluginError("not-granted", plugin.middleware("""["chat"]"""))
    }

    @Test
    fun disposing_a_middleware_stops_the_dispatch() {
        val plugin = granted("user")
        assertNull(plugin.middleware("""["user"]"""))
        plugin.dropMiddleware()
        assertNull(PluginDeserialize.middleware)
        parseOffThread(TLRPC.TL_user())
        assertTrue(plugin.js.deserializeDispatches.isEmpty())
    }

    @Test
    fun unloading_the_plugin_takes_its_middleware_with_it() {
        val plugin = granted("user")
        assertNull(plugin.middleware("""["user"]"""))
        PluginDeserialize.detach(plugin.js)
        assertNull(PluginDeserialize.middleware)
        assertFalse(PluginDeserialize.hot)
        parseOffThread(TLRPC.TL_user())
        assertTrue(plugin.js.deserializeDispatches.isEmpty())
    }

    /**
     * the hook is one volatile read, and it has to answer for both tiers - a middleware with no
     * rules beside it must still get its object
     */
    @Test
    fun the_stock_gate_is_on_either_tier() {
        assertFalse(PluginDeserialize.hot)
        val plugin = granted("user")
        assertNull(plugin.middleware("""["user"]"""))
        assertTrue(PluginDeserialize.hot)
        assertNull(PluginDeserialize.rules)
    }

    /**
     * the cost of this api is what an object *not* named by any rule pays, and that is the table
     * being keyed by constructor id rather than walked. Pinning the key set is how a rewrite of
     * [PluginDeserialize.publish] into a scan over rules would be caught.
     */
    @Test
    fun the_table_is_keyed_by_constructor_id_so_an_unmatched_object_costs_one_lookup() {
        val plugin = granted("user")
        assertNull(plugin.rules(rule("user", """{"premium":true}""")))
        val expected = TlCtorIds.idsOf("user")!!.size
        assertEquals(expected, PluginDeserialize.rules!!.tableSize())
    }

    /**
     * `deserialize.rs`'s oracle run doubles the host, so the refusals it asserts are only as honest
     * as this: the same rule, against the real host, reaching the same code. The oracle's own grant
     * line is read rather than restated, so changing the types it exercises fails here instead of
     * quietly leaving the double answering for rules nobody sends.
     */
    @Test
    fun the_refusals_the_bundled_oracle_expects_are_the_ones_the_real_host_gives() {
        val plugin = startPlugin("deserialize", "interceptDeserialize(${oracleScopes().joinToString(",")})")
        assertEquals(listOf("userFull", "user", "message", "encryptedMessage"), oracleScopes())
        assertNull(
            plugin.rules(rule("userFull", """{"noforwards_my_enabled":false,"noforwards_peer_enabled":false}""")),
            "the rule the oracle registers has to be one the host accepts",
        )
        assertPluginError("invalid-argument", plugin.rules(rule("user", """{"nope":true}""")))
        assertPluginError("forbidden", plugin.rules(rule("user", """{"access_hash":"1"}""")))
        assertPluginError("forbidden", plugin.rules(rule("user", """{"flags":3}""")))
        assertPluginError("forbidden", plugin.rules(rule("encryptedMessage", """{"date":1}""")))
        assertPluginError(
            "forbidden",
            plugin.rules(rule("message", """{"pinned":true}""", """{"message":"Login code: 12345"}""")),
        )
    }

    private fun oracleScopes(): List<String> {
        val source = bundledPlugin("deserialize-test.js")
        val scopes = Regex("""@grant\s+interceptDeserialize\(([^)]*)\)""").find(source)?.groupValues?.get(1)
        assertNotNull(scopes, "the oracle no longer declares an interceptDeserialize grant")
        return scopes.split(",").map { it.trim() }
    }

    @Test
    fun the_rule_ceiling_the_contract_states_is_the_one_the_engine_enforces() {
        // the host counts nothing; the engine does, and the contract is what both are read against
        assertEquals(32, statedNumber(contract(), "at most {} rules live at once"))
    }

    /**
     * read out of the contract rather than off the constant: every other test here injects or
     * awaits its own budget, so a suite that only ever compared the constant to itself would stay
     * green with the parking window raised to anything at all
     */
    @Test
    fun the_middleware_budget_the_contract_states_is_the_one_the_parse_waits_for() {
        assertEquals(
            PluginDeserialize.MIDDLEWARE_BUDGET_MS,
            statedNumber(contract(), "parked on the answer for at most {}ms"),
        )
    }
}

/** [PluginDeserialize] syncs a flag bit through [TlJson]; this reads the same bit back */
private fun flagBitIsSet(obj: TLObject, field: String): Boolean {
    val gate = desu.inugram.core.plugins.TlFlags.gateOf(obj.javaClass, field) ?: return true
    val word = desu.inugram.core.plugins.TlFlags.wordName(gate.word) ?: return true
    val holder = TlReflect.publicFields(obj.javaClass)[word] ?: return true
    return (holder.getInt(obj) and (1 shl gate.bit)) != 0
}

private fun PluginDeserialize.Compiled.tableSize(): Int {
    val field = PluginDeserialize.Compiled::class.java.getDeclaredField("byCtorId").apply { isAccessible = true }
    return (field.get(this) as android.util.SparseArray<*>).size()
}
