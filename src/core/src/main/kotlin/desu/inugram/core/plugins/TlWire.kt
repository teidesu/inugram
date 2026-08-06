package desu.inugram.core.plugins

/**
 * Compact single-value wire codec crossing the JNI boundary for the live-proxy TL bridge
 * (desu.inugram.helpers.plugins.tl.TlHandles <-> src/rust/inu_native/src/tl/proxy.rs).
 *
 * Carries exactly ONE field/element value per call, never a whole object graph, so a get()/set()
 * trap costs O(1).
 *
 * tag chars: N=null, S=string, I=int, D=double, B=bool, Y=bytes(base64),
 * H=handle(kind + mode + id, e.g. "HOW12" = object/writable/#12, "HVR7" = vector/read-only/#7),
 * J=json(construct), E=error, R=rpc error ("code:text", surfaced as `inu.RpcError`),
 * P=plugin error (surfaced as `inu.PluginError`). First char is the tag; rest is the payload.
 */
object TlWire {
    sealed class Value {
        data object Null : Value()
        data class Str(val value: String) : Value()
        data class IntNum(val value: Long) : Value()
        data class DoubleNum(val value: Double) : Value()
        data class Bool(val value: Boolean) : Value()
        data class Bytes(val base64: String) : Value()
        data class Handle(val vector: Boolean, val id: Long, val readOnly: Boolean) : Value()
        data class Json(val json: String) : Value()
        data class Error(val message: String) : Value()
        data class RpcError(val code: Int, val text: String) : Value()
        data class PluginErr(
            val code: String,
            val message: String,
            val grant: String? = null,
            val usage: Long? = null,
            val quota: Long? = null,
        ) : Value()
    }

    const val HANDLE_EXPIRED_MESSAGE =
        "TL handle expired — object escaped back to native code; copy fields you need before returning"

    fun encodeNull(): String = "N"
    fun encodeString(value: String): String = "S$value"
    fun encodeInt(value: Long): String = "I$value"
    fun encodeDouble(value: Double): String = "D$value"
    fun encodeBool(value: Boolean): String = if (value) "B1" else "B0"
    fun encodeBytes(base64: String): String = "Y$base64"
    fun encodeHandle(vector: Boolean, id: Long, readOnly: Boolean): String =
        "H${if (vector) "V" else "O"}${if (readOnly) "R" else "W"}$id"
    fun encodeJson(json: String): String = "J$json"
    fun encodeError(message: String): String = "E$message"
    fun encodeRpcError(code: Int, text: String): String = "R$code:$text"

    fun encodePluginError(
        code: String,
        message: String,
        grant: String? = null,
        usage: Long? = null,
        quota: Long? = null,
    ): String = "P$code\n${grant.orEmpty()}\n${usage?.toString().orEmpty()}\n${quota?.toString().orEmpty()}\n$message"

    fun encodeExpired(): String = encodePluginError("handle-expired", HANDLE_EXPIRED_MESSAGE)

    /**
     * Both sides gate every op - the engine at the binding (rust `error::check_grant`), the host again
     * where the data lives - and the same denial has to read the same either way, so the wording here
     * is that function's, byte for byte. [scope] is null for an unscoped grant, matching rust's
     * `grant_token`.
     */
    fun encodeNotGranted(name: String, scope: String? = null): String {
        val token = if (scope == null) name else "$name($scope)"
        return encodePluginError("not-granted", "missing grant: $token", grant = token)
    }

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
                if (payload.length < 3) throw IllegalArgumentException("TlWire.decode: truncated handle payload")
                val kind = payload[0]
                val mode = payload[1]
                if ((kind != 'O' && kind != 'V') || (mode != 'W' && mode != 'R')) {
                    throw IllegalArgumentException("TlWire.decode: bad handle payload '$payload'")
                }
                Value.Handle(vector = kind == 'V', id = payload.substring(2).toLong(), readOnly = mode == 'R')
            }
            'J' -> Value.Json(payload)
            'E' -> Value.Error(payload)
            'R' -> {
                val sep = payload.indexOf(':')
                if (sep < 0) throw IllegalArgumentException("TlWire.decode: bad rpc error payload")
                Value.RpcError(code = payload.substring(0, sep).toInt(), text = payload.substring(sep + 1))
            }
            'P' -> {
                val n1 = payload.indexOf('\n')
                val n2 = if (n1 >= 0) payload.indexOf('\n', n1 + 1) else -1
                val n3 = if (n2 >= 0) payload.indexOf('\n', n2 + 1) else -1
                val n4 = if (n3 >= 0) payload.indexOf('\n', n3 + 1) else -1
                if (n1 < 0 || n2 < 0 || n3 < 0 || n4 < 0) {
                    throw IllegalArgumentException("TlWire.decode: bad plugin error payload")
                }
                Value.PluginErr(
                    code = payload.substring(0, n1),
                    grant = payload.substring(n1 + 1, n2).ifEmpty { null },
                    usage = payload.substring(n2 + 1, n3).ifEmpty { null }?.toLong(),
                    quota = payload.substring(n3 + 1, n4).ifEmpty { null }?.toLong(),
                    message = payload.substring(n4 + 1),
                )
            }
            else -> throw IllegalArgumentException("TlWire.decode: unknown tag '${wire[0]}'")
        }
    }
}
