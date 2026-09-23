package desu.inugram.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertFailsWith

class PluginWireTest {
    @Test
    fun a_refusal_carries_the_plugin_error_wire_it_was_decided_with() {
        val refusal = assertFailsWith<PluginRefusal> { PluginWire.refuse("not-granted", "missing grant: fs", grant = "fs") }
        assertEquals(
            PluginWire.Value.PluginErr(code = "not-granted", message = "missing grant: fs", grant = "fs"),
            PluginWire.decode(refusal.wire),
        )
    }

    @Test
    fun round_trips_null() {
        assertEquals(PluginWire.Value.Null, PluginWire.decode(PluginWire.encodeNull()))
    }

    @Test
    fun round_trips_string() {
        assertEquals(PluginWire.Value.Str("hello world"), PluginWire.decode(PluginWire.encodeString("hello world")))
    }

    @Test
    fun round_trips_int_including_negative() {
        assertEquals(PluginWire.Value.IntNum(-42L), PluginWire.decode(PluginWire.encodeInt(-42L)))
        assertEquals(PluginWire.Value.IntNum(9223372036854775807L), PluginWire.decode(PluginWire.encodeInt(Long.MAX_VALUE)))
    }

    @Test
    fun round_trips_double() {
        assertEquals(PluginWire.Value.DoubleNum(3.14), PluginWire.decode(PluginWire.encodeDouble(3.14)))
    }

    @Test
    fun round_trips_booleans() {
        assertEquals(PluginWire.Value.Bool(true), PluginWire.decode(PluginWire.encodeBool(true)))
        assertEquals(PluginWire.Value.Bool(false), PluginWire.decode(PluginWire.encodeBool(false)))
    }

    @Test
    fun round_trips_bytes_as_base64_payload() {
        assertEquals(PluginWire.Value.Bytes("AQID"), PluginWire.decode(PluginWire.encodeBytes("AQID")))
    }

    @Test
    fun round_trips_every_handle_kind_and_mutability_mode() {
        for (vector in listOf(false, true)) {
            for (readOnly in listOf(false, true)) {
                val wire = PluginWire.encodeHandle(vector = vector, id = 7L, readOnly = readOnly)
                assertEquals(PluginWire.Value.Handle(vector = vector, id = 7L, readOnly = readOnly), PluginWire.decode(wire))
            }
        }
    }

    @Test
    fun handle_wire_spells_out_kind_then_mode_then_id() {
        assertEquals("HOW12", PluginWire.encodeHandle(vector = false, id = 12L, readOnly = false))
        assertEquals("HOR12", PluginWire.encodeHandle(vector = false, id = 12L, readOnly = true))
        assertEquals("HVW12", PluginWire.encodeHandle(vector = true, id = 12L, readOnly = false))
        assertEquals("HVR12", PluginWire.encodeHandle(vector = true, id = 12L, readOnly = true))
    }

    @Test
    fun a_projected_handle_carries_its_scalars_after_the_id_and_decodes_to_the_same_handle() {
        val wire = PluginWire.encodeHandle(vector = false, id = 12L, readOnly = true, projection = """{"id":"5","date":9}""")
        assertEquals("""HOR12|{"id":"5","date":9}""", wire)
        assertEquals(PluginWire.Value.Handle(vector = false, id = 12L, readOnly = true), PluginWire.decode(wire))
    }

    @Test
    fun rejects_handles_with_an_unknown_kind_or_mode_char() {
        assertFailsWith<IllegalArgumentException> { PluginWire.decode("HXW1") }
        assertFailsWith<IllegalArgumentException> { PluginWire.decode("HOX1") }
    }

    @Test
    fun rejects_truncated_handle_payload() {
        assertFailsWith<IllegalArgumentException> { PluginWire.decode("H") }
        assertFailsWith<IllegalArgumentException> { PluginWire.decode("HOW") }
        assertFailsWith<IllegalArgumentException> { PluginWire.decode("HO1") }
    }

    @Test
    fun rejects_handle_with_a_non_numeric_id() {
        assertFailsWith<IllegalArgumentException> { PluginWire.decode("HOWnope") }
    }

    @Test
    fun round_trips_json_construct_payload() {
        val json = """{"_":"inputPeerSelf"}"""
        assertEquals(PluginWire.Value.Json(json), PluginWire.decode(PluginWire.encodeJson(json)))
    }

    @Test
    fun round_trips_error_message() {
        assertEquals(PluginWire.Value.Error("boom"), PluginWire.decode(PluginWire.encodeError("boom")))
    }

    @Test
    fun round_trips_rpc_error_with_code_and_text() {
        assertEquals(PluginWire.Value.RpcError(400, "PEER_ID_INVALID"), PluginWire.decode(PluginWire.encodeRpcError(400, "PEER_ID_INVALID")))
        assertEquals(PluginWire.Value.RpcError(-1000, "text: with colons"), PluginWire.decode(PluginWire.encodeRpcError(-1000, "text: with colons")))
    }

    @Test
    fun rejects_rpc_error_payload_without_separator() {
        assertFailsWith<IllegalArgumentException> { PluginWire.decode("R400") }
    }

    @Test
    fun expired_helper_carries_the_exact_spec_wording_as_a_typed_plugin_error() {
        val decoded = PluginWire.decode(PluginWire.encodeExpired()) as PluginWire.Value.PluginErr
        assertEquals("handle-expired", decoded.code)
        assertEquals(PluginWire.HANDLE_EXPIRED_MESSAGE, decoded.message)
        assertEquals(null, decoded.grant)
        assertEquals(null, decoded.usage)
        assertEquals(null, decoded.quota)
    }

    @Test
    fun rejects_empty_wire_value() {
        assertFailsWith<IllegalArgumentException> { PluginWire.decode("") }
    }

    @Test
    fun rejects_unknown_tag() {
        assertFailsWith<IllegalArgumentException> { PluginWire.decode("Zfoo") }
    }

    @Test
    fun round_trips_plugin_error_with_all_fields() {
        val wire = PluginWire.encodePluginError(
            code = "quota-exceeded",
            message = "fs is full",
            grant = "fs",
            usage = 1_048_576L,
            quota = 1_048_576L,
        )
        assertEquals(
            PluginWire.Value.PluginErr(
                code = "quota-exceeded",
                message = "fs is full",
                grant = "fs",
                usage = 1_048_576L,
                quota = 1_048_576L,
            ),
            PluginWire.decode(wire),
        )
    }

    @Test
    fun round_trips_plugin_error_with_only_code_and_message() {
        assertEquals(
            PluginWire.Value.PluginErr(code = "internal", message = "boom"),
            PluginWire.decode(PluginWire.encodePluginError("internal", "boom")),
        )
    }

    @Test
    fun plugin_error_message_may_itself_contain_newlines() {
        val decoded = PluginWire.decode(PluginWire.encodePluginError("internal", "line one\nline two\nline three"))
            as PluginWire.Value.PluginErr
        assertEquals("line one\nline two\nline three", decoded.message)
    }

    @Test
    fun rejects_malformed_plugin_error_payload_missing_separators() {
        assertFailsWith<IllegalArgumentException> { PluginWire.decode("Pnot-granted\nfs\n\n") }
    }

    @Test
    fun rejects_plugin_error_with_non_numeric_usage() {
        assertFailsWith<IllegalArgumentException> { PluginWire.decode("Pquota-exceeded\n\nnot-a-number\n\nboom") }
    }

    // the whole point of the encoder is that a refusal reads the same whichever side caught it, so
    // these are rust's `error::check_grant` wires spelled out rather than rebuilt from the encoder
    @Test
    fun a_scoped_refusal_is_byte_identical_to_the_engine_s_own() {
        assertEquals(
            "Pnot-granted\ninvokeRpc(messages.sendMessage)\n\n\nmissing grant: invokeRpc(messages.sendMessage)",
            PluginWire.encodeNotGranted("invokeRpc", "messages.sendMessage"),
        )
    }

    @Test
    fun an_unscoped_refusal_carries_the_bare_grant_name() {
        assertEquals("Pnot-granted\nfs\n\n\nmissing grant: fs", PluginWire.encodeNotGranted("fs"))
    }
}
