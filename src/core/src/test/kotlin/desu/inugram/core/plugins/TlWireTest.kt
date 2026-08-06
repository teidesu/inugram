package desu.inugram.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertFailsWith

class TlWireTest {
    @Test
    fun `round-trips null`() {
        assertEquals(TlWire.Value.Null, TlWire.decode(TlWire.encodeNull()))
    }

    @Test
    fun `round-trips string`() {
        assertEquals(TlWire.Value.Str("hello world"), TlWire.decode(TlWire.encodeString("hello world")))
    }

    @Test
    fun `round-trips int including negative`() {
        assertEquals(TlWire.Value.IntNum(-42L), TlWire.decode(TlWire.encodeInt(-42L)))
        assertEquals(TlWire.Value.IntNum(9223372036854775807L), TlWire.decode(TlWire.encodeInt(Long.MAX_VALUE)))
    }

    @Test
    fun `round-trips double`() {
        assertEquals(TlWire.Value.DoubleNum(3.14), TlWire.decode(TlWire.encodeDouble(3.14)))
    }

    @Test
    fun `round-trips booleans`() {
        assertEquals(TlWire.Value.Bool(true), TlWire.decode(TlWire.encodeBool(true)))
        assertEquals(TlWire.Value.Bool(false), TlWire.decode(TlWire.encodeBool(false)))
    }

    @Test
    fun `round-trips bytes as base64 payload`() {
        assertEquals(TlWire.Value.Bytes("AQID"), TlWire.decode(TlWire.encodeBytes("AQID")))
    }

    @Test
    fun `round-trips every handle kind and mutability mode`() {
        for (vector in listOf(false, true)) {
            for (readOnly in listOf(false, true)) {
                val wire = TlWire.encodeHandle(vector = vector, id = 7L, readOnly = readOnly)
                assertEquals(TlWire.Value.Handle(vector = vector, id = 7L, readOnly = readOnly), TlWire.decode(wire))
            }
        }
    }

    @Test
    fun `handle wire spells out kind then mode then id`() {
        assertEquals("HOW12", TlWire.encodeHandle(vector = false, id = 12L, readOnly = false))
        assertEquals("HOR12", TlWire.encodeHandle(vector = false, id = 12L, readOnly = true))
        assertEquals("HVW12", TlWire.encodeHandle(vector = true, id = 12L, readOnly = false))
        assertEquals("HVR12", TlWire.encodeHandle(vector = true, id = 12L, readOnly = true))
    }

    @Test
    fun `rejects handles with an unknown kind or mode char`() {
        assertFailsWith<IllegalArgumentException> { TlWire.decode("HXW1") }
        assertFailsWith<IllegalArgumentException> { TlWire.decode("HOX1") }
    }

    @Test
    fun `rejects truncated handle payload`() {
        assertFailsWith<IllegalArgumentException> { TlWire.decode("H") }
        assertFailsWith<IllegalArgumentException> { TlWire.decode("HOW") }
        assertFailsWith<IllegalArgumentException> { TlWire.decode("HO1") }
    }

    @Test
    fun `rejects handle with a non-numeric id`() {
        assertFailsWith<IllegalArgumentException> { TlWire.decode("HOWnope") }
    }

    @Test
    fun `round-trips json construct payload`() {
        val json = """{"_":"inputPeerSelf"}"""
        assertEquals(TlWire.Value.Json(json), TlWire.decode(TlWire.encodeJson(json)))
    }

    @Test
    fun `round-trips error message`() {
        assertEquals(TlWire.Value.Error("boom"), TlWire.decode(TlWire.encodeError("boom")))
    }

    @Test
    fun `round-trips rpc error with code and text`() {
        assertEquals(TlWire.Value.RpcError(400, "PEER_ID_INVALID"), TlWire.decode(TlWire.encodeRpcError(400, "PEER_ID_INVALID")))
        assertEquals(TlWire.Value.RpcError(-1000, "text: with colons"), TlWire.decode(TlWire.encodeRpcError(-1000, "text: with colons")))
    }

    @Test
    fun `rejects rpc error payload without separator`() {
        assertFailsWith<IllegalArgumentException> { TlWire.decode("R400") }
    }

    @Test
    fun `expired helper carries the exact spec wording as a typed plugin error`() {
        val decoded = TlWire.decode(TlWire.encodeExpired()) as TlWire.Value.PluginErr
        assertEquals("handle-expired", decoded.code)
        assertEquals(TlWire.HANDLE_EXPIRED_MESSAGE, decoded.message)
        assertEquals(null, decoded.grant)
        assertEquals(null, decoded.usage)
        assertEquals(null, decoded.quota)
    }

    @Test
    fun `rejects empty wire value`() {
        assertFailsWith<IllegalArgumentException> { TlWire.decode("") }
    }

    @Test
    fun `rejects unknown tag`() {
        assertFailsWith<IllegalArgumentException> { TlWire.decode("Zfoo") }
    }

    @Test
    fun `round-trips plugin error with all fields`() {
        val wire = TlWire.encodePluginError(
            code = "quota-exceeded",
            message = "kv store is full",
            grant = "kv",
            usage = 1_048_576L,
            quota = 1_048_576L,
        )
        assertEquals(
            TlWire.Value.PluginErr(
                code = "quota-exceeded",
                message = "kv store is full",
                grant = "kv",
                usage = 1_048_576L,
                quota = 1_048_576L,
            ),
            TlWire.decode(wire),
        )
    }

    @Test
    fun `round-trips plugin error with only code and message`() {
        assertEquals(
            TlWire.Value.PluginErr(code = "internal", message = "boom"),
            TlWire.decode(TlWire.encodePluginError("internal", "boom")),
        )
    }

    @Test
    fun `plugin error message may itself contain newlines`() {
        val decoded = TlWire.decode(TlWire.encodePluginError("internal", "line one\nline two\nline three"))
            as TlWire.Value.PluginErr
        assertEquals("line one\nline two\nline three", decoded.message)
    }

    @Test
    fun `rejects malformed plugin error payload missing separators`() {
        assertFailsWith<IllegalArgumentException> { TlWire.decode("Pnot-granted\nkv\n\n") }
    }

    @Test
    fun `rejects plugin error with non-numeric usage`() {
        assertFailsWith<IllegalArgumentException> { TlWire.decode("Pquota-exceeded\n\nnot-a-number\n\nboom") }
    }

    // the whole point of the encoder is that a refusal reads the same whichever side caught it, so
    // these are rust's `error::check_grant` wires spelled out rather than rebuilt from the encoder
    @Test
    fun `a scoped refusal is byte-identical to the engine's own`() {
        assertEquals(
            "Pnot-granted\ninvokeRpc(messages.sendMessage)\n\n\nmissing grant: invokeRpc(messages.sendMessage)",
            TlWire.encodeNotGranted("invokeRpc", "messages.sendMessage"),
        )
    }

    @Test
    fun `an unscoped refusal carries the bare grant name`() {
        assertEquals("Pnot-granted\nkv\n\n\nmissing grant: kv", TlWire.encodeNotGranted("kv"))
    }
}
