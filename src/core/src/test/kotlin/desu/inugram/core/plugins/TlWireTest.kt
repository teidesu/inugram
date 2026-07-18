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
    fun `round-trips object and vector handles`() {
        assertEquals(TlWire.Value.Handle(vector = false, id = 7L), TlWire.decode(TlWire.encodeHandle(vector = false, id = 7L)))
        assertEquals(TlWire.Value.Handle(vector = true, id = 8L), TlWire.decode(TlWire.encodeHandle(vector = true, id = 8L)))
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
    fun `expired helper carries the exact spec wording`() {
        val decoded = TlWire.decode(TlWire.encodeExpired()) as TlWire.Value.Error
        assertEquals(TlWire.HANDLE_EXPIRED_MESSAGE, decoded.message)
    }

    @Test
    fun `rejects empty wire value`() {
        assertFailsWith<IllegalArgumentException> { TlWire.decode("") }
    }

    @Test
    fun `rejects unknown tag`() {
        assertFailsWith<IllegalArgumentException> { TlWire.decode("Zfoo") }
    }
}
