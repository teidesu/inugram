package desu.inugram.core.plugins

/**
 * Compact single-value wire codec crossing the JNI boundary for the live-proxy TL bridge
 * (desu.inugram.helpers.plugins.TlHandles <-> src/rust/inu_native/src/tl_proxy.rs).
 *
 * Unlike [desu.inugram.core.plugins.TlNames] (used together with the reflection bridge in
 * `TlJson`), this codec carries exactly ONE field/element value per call - never a whole object
 * graph - so a get()/set() trap costs O(1), not O(graph size).
 *
 * tag chars: N=null, S=string, I=int, D=double, B=bool, Y=bytes(base64), H=handle(kind+id),
 * J=json(construct), E=error, R=rpc error ("code:text", surfaced to JS as `inu.RpcError`).
 * First char is the tag; rest is the payload.
 */
object TlWire {
    sealed class Value {
        data object Null : Value()
        data class Str(val value: String) : Value()
        data class IntNum(val value: Long) : Value()
        data class DoubleNum(val value: Double) : Value()
        data class Bool(val value: Boolean) : Value()
        data class Bytes(val base64: String) : Value()
        data class Handle(val vector: Boolean, val id: Long) : Value()
        data class Json(val json: String) : Value()
        data class Error(val message: String) : Value()
        data class RpcError(val code: Int, val text: String) : Value()
    }

    const val HANDLE_EXPIRED_MESSAGE =
        "TL handle expired — object escaped back to native code; copy fields you need before returning"

    fun encodeNull(): String = "N"
    fun encodeString(value: String): String = "S$value"
    fun encodeInt(value: Long): String = "I$value"
    fun encodeDouble(value: Double): String = "D$value"
    fun encodeBool(value: Boolean): String = if (value) "B1" else "B0"
    fun encodeBytes(base64: String): String = "Y$base64"
    fun encodeHandle(vector: Boolean, id: Long): String = "H${if (vector) "V" else "O"}$id"
    fun encodeJson(json: String): String = "J$json"
    fun encodeError(message: String): String = "E$message"
    fun encodeRpcError(code: Int, text: String): String = "R$code:$text"
    fun encodeExpired(): String = encodeError(HANDLE_EXPIRED_MESSAGE)

    fun decode(wire: String): Value {
        if (wire.isEmpty()) throw IllegalArgumentException("TlWire.decode: empty wire value")
        val payload = wire.substring(1)
        return when (wire[0]) {
            'N' -> Value.Null
            'S' -> Value.Str(payload)
            'I' -> Value.IntNum(payload.toLong())
            'D' -> Value.DoubleNum(payload.toDouble())
            'B' -> Value.Bool(payload == "1")
            'Y' -> Value.Bytes(payload)
            'H' -> {
                val kind = payload[0]
                val id = payload.substring(1).toLong()
                Value.Handle(vector = kind == 'V', id = id)
            }
            'J' -> Value.Json(payload)
            'E' -> Value.Error(payload)
            'R' -> {
                val sep = payload.indexOf(':')
                if (sep < 0) throw IllegalArgumentException("TlWire.decode: bad rpc error payload")
                Value.RpcError(code = payload.substring(0, sep).toInt(), text = payload.substring(sep + 1))
            }
            else -> throw IllegalArgumentException("TlWire.decode: unknown tag '${wire[0]}'")
        }
    }
}
