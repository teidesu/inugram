package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.TlCtorIds
import desu.inugram.core.plugins.TlWire
import desu.inugram.helpers.plugins.tg.PluginDeserialize
import desu.inugram.helpers.plugins.tl.TlJson
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
        js.deserializeListener!!.onDeserializeRegister(callbackId, json)

    private fun Plugin.dropRules(callbackId: Int = 1) =
        js.deserializeListener!!.onDeserializeUnregister(callbackId)

    /** the wire `deserialize.rs` builds: `type` is always an array and `when` always present */
    private fun rule(type: String, set: String, matching: String = "{}"): String =
        """[{"type":["$type"],"when":$matching,"set":$set}]"""

    private fun ctorOf(cls: Class<*>): Int =
        cls.getDeclaredField("constructor").also { it.isAccessible = true }.getInt(null)

    /** what stock's `TLObject.TLdeserialize` does with an object it has just read */
    private fun <T : TLObject> T.deserialized(): T = apply { PluginDeserialize.apply(this, ctorOf(javaClass)) }

    @Test
    fun `a rule rewrites the object stock just parsed`() {
        val plugin = granted("user")
        assertNull(plugin.rules(rule("user", """{"premium":true}""")))
        assertTrue(TLRPC.TL_user().deserialized().premium)
    }

    @Test
    fun `nothing is rewritten while no rule is live`() {
        assertNull(PluginDeserialize.rules, "the hot path must cost one null check with no plugins")
        assertFalse(TLRPC.TL_user().deserialized().premium)
    }

    @Test
    fun `a when that does not hold leaves the object alone`() {
        val plugin = granted("user")
        assertNull(plugin.rules(rule("user", """{"premium":true}""", """{"bot":true}""")))
        assertFalse(TLRPC.TL_user().deserialized().premium, "the matcher did not hold")
        assertTrue(TLRPC.TL_user().apply { bot = true }.deserialized().premium)
    }

    @Test
    fun `a rule reaches every constructor of the family it names`() {
        val plugin = granted("user")
        assertNull(plugin.rules(rule("user", """{"premium":true}""")))
        assertTrue(TLRPC.TL_user_layer218().deserialized().premium, "a legacy row is still parsed at startup")
    }

    @Test
    fun `a rule never reaches a constructor it did not name`() {
        val plugin = granted("user")
        assertNull(plugin.rules(rule("user", """{"premium":true}""")))
        val chat = TLRPC.TL_chat().deserialized()
        assertFalse(chat.creator)
    }

    @Test
    fun `setting an optional field sets the bit that gates it`() {
        val plugin = granted("userFull")
        assertNull(plugin.rules(rule("userFull", """{"about":"rewritten"}""")))
        val full = TLRPC.TL_userFull().deserialized()
        assertEquals("rewritten", full.about)
        // without the bit the app re-serializes the row without the field, so the rewrite would
        // survive in memory and vanish the moment it was written back
        assertTrue(flagBitIsSet(full, "about"), "'about' is flags-gated and its bit must follow the value")
    }

    @Test
    fun `a long takes the string form a js number cannot hold`() {
        val plugin = granted("document")
        assertNull(plugin.rules(rule("document", """{"size":"9007199254740993"}""")))
        assertEquals(9007199254740993L, TLRPC.TL_document().deserialized().size)
    }

    @Test
    fun `every reference-shaped field on every rule target is refused`() {
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
    fun `a flag word cannot be named at all`() {
        val plugin = granted("user")
        assertPluginError("forbidden", plugin.rules(rule("user", """{"flags":3}""")))
        assertPluginError("forbidden", plugin.rules(rule("user", """{"premium":true}""", """{"flags":3}""")))
    }

    @Test
    fun `a secret message is not reachable through the family that carries it`() {
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
    fun `naming secret-chat traffic outright is refused`() {
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
    fun `the harness has the secret classes this is about`() {
        assertEquals(3, secretMessageClasses().size, "stock declares TL_message_secret{,_old,_layer72}")
    }

    /**
     * the stripping has to take the three and only the three, or `message` either leaks secret-chat
     * content or stops being a rule target worth having
     */
    @Test
    fun `stripping the secret constructors leaves the rest of the family`() {
        val plugin = granted("message")
        assertNull(plugin.rules(rule("message", """{"pinned":true}""")))
        val declared = TlCtorIds.idsOf("message")!!.size
        assertEquals(declared - 3, PluginDeserialize.rules!!.tableSize())
    }

    @Test
    fun `an auth constructor is not a rule target`() {
        val plugin = granted("auth.authorization")
        assertPluginError("forbidden", plugin.rules(rule("auth.authorization", """{"tmp_sessions":1}""")))
    }

    @Test
    fun `a constructor whose fields the api filter hides is not a rule target`() {
        val plugin = granted("updateServiceNotification", "config")
        assertPluginError("forbidden", plugin.rules(rule("updateServiceNotification", """{"popup":true}""")))
        assertPluginError("forbidden", plugin.rules(rule("config", """{"test_mode":true}""")))
    }

    @Test
    fun `disableApiFiltering lifts the takeover refusal and nothing else`() {
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
    fun `matching on redacted message text is refused`() {
        val plugin = granted("message")
        // the value is redacted per object, so whether a rule fired is a confirmed guess of it
        assertPluginError("forbidden", plugin.rules(rule("message", """{"pinned":true}""", """{"message":"Login code: 12345"}""")))
        assertNull(plugin.rules(rule("message", """{"message":"rewritten"}""")), "setting it leaks nothing")
    }

    @Test
    fun `a redaction evidence field cannot be set`() {
        val plugin = granted("message")
        assertPluginError("forbidden", plugin.rules(rule("message", """{"out":true}""")))
    }

    /**
     * both are `invalid-argument`, and that is the point: a plugin that could tell "there is no
     * such field" from "there is, and you may not have it" would have read what the filter withheld.
     * So the message is what this asserts, not the code.
     */
    @Test
    fun `a draft field is not there without the grant that hands drafts out`() {
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
        (desu.inugram.core.plugins.TlWire.decode(wire ?: "N") as desu.inugram.core.plugins.TlWire.Value.PluginErr).message

    @Test
    fun `a name that is not a constructor is refused`() {
        val plugin = granted("nope")
        assertPluginError("invalid-argument", plugin.rules(rule("nope", """{"premium":true}""")))
    }

    @Test
    fun `an rpc method is refused rather than silently never firing`() {
        val plugin = granted("users.getUsers")
        assertTrue("users.getUsers" in TlCtorIds.methodNames)
        assertPluginError("invalid-argument", plugin.rules(rule("users.getUsers", """{"id":1}""")))
    }

    @Test
    fun `a field the constructor does not have is refused`() {
        val plugin = granted("user")
        assertPluginError("invalid-argument", plugin.rules(rule("user", """{"nope":true}""")))
    }

    @Test
    fun `a constant that does not fit the field is refused`() {
        val plugin = granted("user")
        assertPluginError("invalid-argument", plugin.rules(rule("user", """{"premium":"yes"}""")))
        assertPluginError("invalid-argument", plugin.rules(rule("user", """{"first_name":5}""")))
        assertPluginError("invalid-argument", plugin.rules(rule("user", """{"premium":null}""")))
    }

    @Test
    fun `a field no constant can be is refused`() {
        val plugin = granted("user")
        assertPluginError("invalid-argument", plugin.rules(rule("user", """{"photo":null}""")))
        assertPluginError("invalid-argument", plugin.rules(rule("user", """{"restriction_reason":null}""")))
        // null is a value for a string and a structural rewrite for anything else
        assertNull(plugin.rules(rule("user", """{"first_name":null}""")))
        assertNull(TLRPC.TL_user().apply { first_name = "x" }.deserialized().first_name)
    }

    @Test
    fun `a required field cannot be cleared, since stock never sees one that is not there`() {
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
    fun `clearing an optional string is what null is for`() {
        val plugin = granted("user")
        assertNull(plugin.rules(rule("user", """{"username":null}""")))
        val user = TLRPC.TL_user().apply { username = "someone"; flags = 1 shl 3 }.deserialized()
        assertNull(user.username)
        assertEquals(0, user.flags and (1 shl 3), "clearing a field clears the bit that gates it")
    }

    @Test
    fun `a type outside the grant scope is refused by the host too`() {
        val plugin = granted("user")
        assertPluginError("not-granted", plugin.rules(rule("message", """{"pinned":true}""")))
    }

    @Test
    fun `a refused rule set registers nothing`() {
        val plugin = granted("user")
        assertPluginError("forbidden", plugin.rules(rule("user", """{"id":1}""")))
        assertNull(PluginDeserialize.rules)
        assertFalse(TLRPC.TL_user().deserialized().premium)
    }

    @Test
    fun `disposing a registration takes its rules out of the table`() {
        val plugin = granted("user")
        assertNull(plugin.rules(rule("user", """{"premium":true}""")))
        plugin.dropRules()
        assertNull(PluginDeserialize.rules, "the last registration going away must clear the hot path")
        assertFalse(TLRPC.TL_user().deserialized().premium)
    }

    @Test
    fun `unloading the plugin takes its rules with it`() {
        val plugin = granted("user")
        assertNull(plugin.rules(rule("user", """{"premium":true}""")))
        PluginDeserialize.detach(plugin.js)
        assertNull(PluginDeserialize.rules, "a dead plugin must not keep rewriting what the app parses")
        assertFalse(TLRPC.TL_user().deserialized().premium)
    }

    @Test
    fun `two registrations both apply and one going away leaves the other`() {
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
        js.deserializeListener!!.onDeserializeMiddlewareRegister(callbackId, typesJson)

    private fun Plugin.dropMiddleware(callbackId: Int = 1) =
        js.deserializeListener!!.onDeserializeMiddlewareUnregister(callbackId)

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
    fun `a middleware is handed a writable view of the object stock just parsed`() {
        val plugin = granted("user")
        plugin.js.onDispatchDeserialize = { dispatch ->
            plugin.tl().tlSet(handleId(dispatch.objectWire), "premium", TlWire.encodeJson("true"))
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
    fun `a middleware cannot rewrite what addresses the object`() {
        val plugin = granted("user")
        var refusal: String? = null
        plugin.js.onDispatchDeserialize = { dispatch ->
            refusal = plugin.tl().tlSet(handleId(dispatch.objectWire), "access_hash", TlWire.encodeJson("7"))
        }
        assertNull(plugin.middleware("""["user"]"""))
        val user = TLRPC.TL_user().apply { access_hash = 1 }
        parseOffThread(user)
        assertPluginError("forbidden", refusal)
        assertEquals(1L, user.access_hash)
    }

    @Test
    fun `the view a middleware was handed dies with the dispatch`() {
        val plugin = granted("user")
        var handle = 0L
        plugin.js.onDispatchDeserialize = { dispatch ->
            handle = handleId(dispatch.objectWire)
        }
        assertNull(plugin.middleware("""["user"]"""))
        parseOffThread(TLRPC.TL_user())
        assertEquals(TlWire.encodeExpired(), plugin.tl().tlGet(handle, "premium"))
    }

    @Test
    fun `a middleware only sees the constructors it named`() {
        val plugin = granted("user")
        assertNull(plugin.middleware("""["user"]"""))
        parseOffThread(TLRPC.TL_chat())
        assertTrue(plugin.js.deserializeDispatches.isEmpty())
        parseOffThread(TLRPC.TL_user())
        assertEquals(1, plugin.js.deserializeDispatches.size)
    }

    @Test
    fun `a middleware over a type no plugin api reaches is refused like a rule is`() {
        val plugin = granted("encryptedMessage", "auth.authorization", "invokeWithLayer", "nosuchthing")
        assertPluginError("forbidden", plugin.middleware("""["encryptedMessage"]"""))
        assertPluginError("forbidden", plugin.middleware("""["auth.authorization"]"""))
        assertPluginError("invalid-argument", plugin.middleware("""["invokeWithLayer"]"""))
        assertPluginError("invalid-argument", plugin.middleware("""["nosuchthing"]"""))
        assertNull(PluginDeserialize.middleware, "not one of those may have registered")
    }

    @Test
    fun `a middleware over a type the grant does not name is refused`() {
        val plugin = granted("user")
        assertPluginError("not-granted", plugin.middleware("""["chat"]"""))
    }

    @Test
    fun `disposing a middleware stops the dispatch`() {
        val plugin = granted("user")
        assertNull(plugin.middleware("""["user"]"""))
        plugin.dropMiddleware()
        assertNull(PluginDeserialize.middleware)
        parseOffThread(TLRPC.TL_user())
        assertTrue(plugin.js.deserializeDispatches.isEmpty())
    }

    @Test
    fun `unloading the plugin takes its middleware with it`() {
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
    fun `the stock gate is on either tier`() {
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
    fun `the table is keyed by constructor id, so an unmatched object costs one lookup`() {
        val plugin = granted("user")
        assertNull(plugin.rules(rule("user", """{"premium":true}""")))
        val expected = TlCtorIds.idsOf("user")!!.size
        assertEquals(expected, PluginDeserialize.rules!!.tableSize())
    }

    /**
     * Nothing else notices this going away: every test above drives [PluginDeserialize.apply]
     * directly, because the stub tree has no `readParams` to deserialize through, so the one thing
     * that makes any of it reach a real object is the call site in stock.
     */
    @Test
    fun `stock still calls the hook from the one place every TL object is parsed`() {
        val source = File(forkRoot(), "worktree/TMessagesProj/src/main/java/org/telegram/tgnet/TLObject.java").readText()
        val body = Regex(
            """object\.readParams\(stream, exception\);(.*?)return object;""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(source)?.groupValues?.get(1)
        assertNotNull(body, "TLObject.TLdeserialize no longer has the shape the hook lives in")
        assertTrue(
            body.contains("PluginDeserialize.hot") && body.contains("PluginDeserialize.apply(object, constructor)"),
            "the interceptDeserialize hook is gone from TLObject.TLdeserialize: $body",
        )
    }

    /**
     * `deserialize.rs`'s oracle run doubles the host, so the refusals it asserts are only as honest
     * as this: the same rule, against the real host, reaching the same code. The oracle's own grant
     * line is read rather than restated, so changing the types it exercises fails here instead of
     * quietly leaving the double answering for rules nobody sends.
     */
    @Test
    fun `the refusals the bundled oracle expects are the ones the real host gives`() {
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
        val source = File(forkRoot(), "src/res/assets-debug/inu_plugins/deserialize-test.js").readText()
        val scopes = Regex("""@grant\s+interceptDeserialize\(([^)]*)\)""").find(source)?.groupValues?.get(1)
        assertNotNull(scopes, "the oracle no longer declares an interceptDeserialize grant")
        return scopes.split(",").map { it.trim() }
    }

    @Test
    fun `the rule ceiling the contract states is the one the engine enforces`() {
        // the host counts nothing; the engine does, and the contract is what both are read against
        assertEquals(32, statedNumber(contract(), "at most {} rules live at once"))
    }

    /**
     * read out of the contract rather than off the constant: every other test here injects or
     * awaits its own budget, so a suite that only ever compared the constant to itself would stay
     * green with the parking window raised to anything at all
     */
    @Test
    fun `the middleware budget the contract states is the one the parse waits for`() {
        assertEquals(
            PluginDeserialize.MIDDLEWARE_BUDGET_MS,
            statedNumber(contract(), "parked on the answer for at most {}ms"),
        )
    }

    private fun contract(): String = File(forkRoot(), "src/plugins/common.d.ts").readText()
}

/** [PluginDeserialize] syncs a flag bit through [TlJson]; this reads the same bit back */
private fun flagBitIsSet(obj: TLObject, field: String): Boolean {
    val gate = desu.inugram.core.plugins.TlFlags.gateOf(obj.javaClass, field) ?: return true
    val word = desu.inugram.core.plugins.TlFlags.wordName(gate.word) ?: return true
    val holder = TlJson.publicFields(obj.javaClass)[word] ?: return true
    return (holder.getInt(obj) and (1 shl gate.bit)) != 0
}

private fun PluginDeserialize.Compiled.tableSize(): Int {
    val field = PluginDeserialize.Compiled::class.java.getDeclaredField("byCtorId").apply { isAccessible = true }
    return (field.get(this) as android.util.SparseArray<*>).size()
}
