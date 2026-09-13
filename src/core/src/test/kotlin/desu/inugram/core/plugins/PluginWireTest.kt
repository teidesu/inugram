package desu.inugram.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertFailsWith

class PluginWireTest {
    @Test
    fun `a refusal carries the plugin error wire it was decided with`() {
        val refusal = assertFailsWith<PluginRefusal> { PluginWire.refuse("not-granted", "missing grant: kv", grant = "kv") }
        assertEquals(
            PluginWire.Value.PluginErr(code = "not-granted", message = "missing grant: kv", grant = "kv"),
            PluginWire.decode(refusal.wire),
        )
    }

    @Test
    fun `round-trips null`() {
        assertEquals(PluginWire.Value.Null, PluginWire.decode(PluginWire.encodeNull()))
    }

    @Test
    fun `round-trips string`() {
        assertEquals(PluginWire.Value.Str("hello world"), PluginWire.decode(PluginWire.encodeString("hello world")))
    }

    @Test
    fun `round-trips int including negative`() {
        assertEquals(PluginWire.Value.IntNum(-42L), PluginWire.decode(PluginWire.encodeInt(-42L)))
        assertEquals(PluginWire.Value.IntNum(9223372036854775807L), PluginWire.decode(PluginWire.encodeInt(Long.MAX_VALUE)))
    }

    @Test
    fun `round-trips double`() {
        assertEquals(PluginWire.Value.DoubleNum(3.14), PluginWire.decode(PluginWire.encodeDouble(3.14)))
    }

    @Test
    fun `round-trips booleans`() {
        assertEquals(PluginWire.Value.Bool(true), PluginWire.decode(PluginWire.encodeBool(true)))
        assertEquals(PluginWire.Value.Bool(false), PluginWire.decode(PluginWire.encodeBool(false)))
    }

    @Test
    fun `round-trips bytes as base64 payload`() {
        assertEquals(PluginWire.Value.Bytes("AQID"), PluginWire.decode(PluginWire.encodeBytes("AQID")))
    }

    @Test
    fun `round-trips every handle kind and mutability mode`() {
        for (vector in listOf(false, true)) {
            for (readOnly in listOf(false, true)) {
                val wire = PluginWire.encodeHandle(vector = vector, id = 7L, readOnly = readOnly)
                assertEquals(PluginWire.Value.Handle(vector = vector, id = 7L, readOnly = readOnly), PluginWire.decode(wire))
            }
        }
    }

    @Test
    fun `handle wire spells out kind then mode then id`() {
        assertEquals("HOW12", PluginWire.encodeHandle(vector = false, id = 12L, readOnly = false))
        assertEquals("HOR12", PluginWire.encodeHandle(vector = false, id = 12L, readOnly = true))
        assertEquals("HVW12", PluginWire.encodeHandle(vector = true, id = 12L, readOnly = false))
        assertEquals("HVR12", PluginWire.encodeHandle(vector = true, id = 12L, readOnly = true))
    }

    @Test
    fun `a projected handle carries its scalars after the id and decodes to the same handle`() {
        val wire = PluginWire.encodeHandle(vector = false, id = 12L, readOnly = true, projection = """{"id":"5","date":9}""")
        assertEquals("""HOR12|{"id":"5","date":9}""", wire)
        assertEquals(PluginWire.Value.Handle(vector = false, id = 12L, readOnly = true), PluginWire.decode(wire))
    }

    @Test
    fun `rejects handles with an unknown kind or mode char`() {
        assertFailsWith<IllegalArgumentException> { PluginWire.decode("HXW1") }
        assertFailsWith<IllegalArgumentException> { PluginWire.decode("HOX1") }
    }

    @Test
    fun `rejects truncated handle payload`() {
        assertFailsWith<IllegalArgumentException> { PluginWire.decode("H") }
        assertFailsWith<IllegalArgumentException> { PluginWire.decode("HOW") }
        assertFailsWith<IllegalArgumentException> { PluginWire.decode("HO1") }
    }

    @Test
    fun `rejects handle with a non-numeric id`() {
        assertFailsWith<IllegalArgumentException> { PluginWire.decode("HOWnope") }
    }

    @Test
    fun `round-trips json construct payload`() {
        val json = """{"_":"inputPeerSelf"}"""
        assertEquals(PluginWire.Value.Json(json), PluginWire.decode(PluginWire.encodeJson(json)))
    }

    @Test
    fun `round-trips error message`() {
        assertEquals(PluginWire.Value.Error("boom"), PluginWire.decode(PluginWire.encodeError("boom")))
    }

    @Test
    fun `round-trips rpc error with code and text`() {
        assertEquals(PluginWire.Value.RpcError(400, "PEER_ID_INVALID"), PluginWire.decode(PluginWire.encodeRpcError(400, "PEER_ID_INVALID")))
        assertEquals(PluginWire.Value.RpcError(-1000, "text: with colons"), PluginWire.decode(PluginWire.encodeRpcError(-1000, "text: with colons")))
    }

    @Test
    fun `rejects rpc error payload without separator`() {
        assertFailsWith<IllegalArgumentException> { PluginWire.decode("R400") }
    }

    @Test
    fun `expired helper carries the exact spec wording as a typed plugin error`() {
        val decoded = PluginWire.decode(PluginWire.encodeExpired()) as PluginWire.Value.PluginErr
        assertEquals("handle-expired", decoded.code)
        assertEquals(PluginWire.HANDLE_EXPIRED_MESSAGE, decoded.message)
        assertEquals(null, decoded.grant)
        assertEquals(null, decoded.usage)
        assertEquals(null, decoded.quota)
    }

    @Test
    fun `rejects empty wire value`() {
        assertFailsWith<IllegalArgumentException> { PluginWire.decode("") }
    }

    @Test
    fun `rejects unknown tag`() {
        assertFailsWith<IllegalArgumentException> { PluginWire.decode("Zfoo") }
    }

    @Test
    fun `round-trips plugin error with all fields`() {
        val wire = PluginWire.encodePluginError(
            code = "quota-exceeded",
            message = "kv store is full",
            grant = "kv",
            usage = 1_048_576L,
            quota = 1_048_576L,
        )
        assertEquals(
            PluginWire.Value.PluginErr(
                code = "quota-exceeded",
                message = "kv store is full",
                grant = "kv",
                usage = 1_048_576L,
                quota = 1_048_576L,
            ),
            PluginWire.decode(wire),
        )
    }

    @Test
    fun `round-trips plugin error with only code and message`() {
        assertEquals(
            PluginWire.Value.PluginErr(code = "internal", message = "boom"),
            PluginWire.decode(PluginWire.encodePluginError("internal", "boom")),
        )
    }

    @Test
    fun `plugin error message may itself contain newlines`() {
        val decoded = PluginWire.decode(PluginWire.encodePluginError("internal", "line one\nline two\nline three"))
            as PluginWire.Value.PluginErr
        assertEquals("line one\nline two\nline three", decoded.message)
    }

    @Test
    fun `rejects malformed plugin error payload missing separators`() {
        assertFailsWith<IllegalArgumentException> { PluginWire.decode("Pnot-granted\nkv\n\n") }
    }

    @Test
    fun `rejects plugin error with non-numeric usage`() {
        assertFailsWith<IllegalArgumentException> { PluginWire.decode("Pquota-exceeded\n\nnot-a-number\n\nboom") }
    }

    // the whole point of the encoder is that a refusal reads the same whichever side caught it, so
    // these are rust's `error::check_grant` wires spelled out rather than rebuilt from the encoder
    @Test
    fun `a scoped refusal is byte-identical to the engine's own`() {
        assertEquals(
            "Pnot-granted\ninvokeRpc(messages.sendMessage)\n\n\nmissing grant: invokeRpc(messages.sendMessage)",
            PluginWire.encodeNotGranted("invokeRpc", "messages.sendMessage"),
        )
    }

    @Test
    fun `an unscoped refusal carries the bare grant name`() {
        assertEquals("Pnot-granted\nkv\n\n\nmissing grant: kv", PluginWire.encodeNotGranted("kv"))
    }
    @Test
    fun `describes refusal code message and optional details`() {
        assertEquals(
            "invalid-argument: jvm: malformed handle argument",
            PluginWire.describePluginError(PluginWire.encodePluginError("invalid-argument", "jvm: malformed handle argument")),
        )
        assertEquals(
            "quota-exceeded: too large [grant=unsafe.jvm] [usage=12] [quota=8]",
            PluginWire.describePluginError(PluginWire.encodePluginError("quota-exceeded", "too large", "unsafe.jvm", 12, 8)),
        )
    }
}
