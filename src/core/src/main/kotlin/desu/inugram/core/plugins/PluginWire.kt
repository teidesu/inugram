package desu.inugram.core.plugins

/** Keep tags synchronized with `src/native/src/tl/proxy.rs`. */
object PluginWire {
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
    fun encodeLongAsString(value: Long): String = "S$value"
    fun encodeInt(value: Long): String = "I$value"
    fun encodeDouble(value: Double): String = "D$value"
    fun encodeBool(value: Boolean): String = if (value) "B1" else "B0"
    fun encodeBytes(base64: String): String = "Y$base64"
    fun encodeHandle(vector: Boolean, id: Long, readOnly: Boolean): String =
        "H${if (vector) "V" else "O"}${if (readOnly) "R" else "W"}$id"

    /**
     * a handle plus the scalar fields already read off the object, as a JSON object: rust seeds the
     * view's cache with them, so reading one never crosses. [projection] must not contain a newline,
     * the list separator; `JSONObject` never emits a raw one.
     */
    fun encodeHandle(vector: Boolean, id: Long, readOnly: Boolean, projection: String): String =
        "${encodeHandle(vector, id, readOnly)}$PROJECTION_SEPARATOR$projection"

    const val PROJECTION_SEPARATOR = '|'
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

    fun describePluginError(wire: String): String {
        val error = decode(wire) as Value.PluginErr
        return buildString {
            append(error.code).append(": ").append(error.message)
            error.grant?.let { append(" [grant=").append(it).append(']') }
            error.usage?.let { append(" [usage=").append(it).append(']') }
            error.quota?.let { append(" [quota=").append(it).append(']') }
        }
    }

    fun encodeNotGranted(name: String, scope: String? = null): String {
        val token = if (scope == null) name else "$name($scope)"
        return encodePluginError("not-granted", "missing grant: $token", grant = token)
    }

    fun decode(wire: String): Value {
        if (wire.isEmpty()) throw IllegalArgumentException("PluginWire.decode: empty wire value")
        val payload = wire.substring(1)
        return when (wire[0]) {
            'N' -> Value.Null
            'S' -> Value.Str(payload)
            'I' -> Value.IntNum(payload.toLong())
            'D' -> Value.DoubleNum(payload.toDouble())
            'B' -> Value.Bool(payload == "1")
            'Y' -> Value.Bytes(payload)
            'H' -> {
                if (payload.length < 3) throw IllegalArgumentException("PluginWire.decode: truncated handle payload")
                val kind = payload[0]
                val mode = payload[1]
                if ((kind != 'O' && kind != 'V') || (mode != 'W' && mode != 'R')) {
                    throw IllegalArgumentException("PluginWire.decode: bad handle payload '$payload'")
                }
                val id = payload.substring(2).substringBefore(PROJECTION_SEPARATOR)
                Value.Handle(vector = kind == 'V', id = id.toLong(), readOnly = mode == 'R')
            }
            'J' -> Value.Json(payload)
            'E' -> Value.Error(payload)
            'R' -> {
                val sep = payload.indexOf(':')
                if (sep < 0) throw IllegalArgumentException("PluginWire.decode: bad rpc error payload")
                Value.RpcError(code = payload.substring(0, sep).toInt(), text = payload.substring(sep + 1))
            }
            'P' -> {
                val n1 = payload.indexOf('\n')
                val n2 = if (n1 >= 0) payload.indexOf('\n', n1 + 1) else -1
                val n3 = if (n2 >= 0) payload.indexOf('\n', n2 + 1) else -1
                val n4 = if (n3 >= 0) payload.indexOf('\n', n3 + 1) else -1
                if (n1 < 0 || n2 < 0 || n3 < 0 || n4 < 0) {
                    throw IllegalArgumentException("PluginWire.decode: bad plugin error payload")
                }
                Value.PluginErr(
                    code = payload.substring(0, n1),
                    grant = payload.substring(n1 + 1, n2).ifEmpty { null },
                    usage = payload.substring(n2 + 1, n3).ifEmpty { null }?.toLong(),
                    quota = payload.substring(n3 + 1, n4).ifEmpty { null }?.toLong(),
                    message = payload.substring(n4 + 1),
                )
            }
            else -> throw IllegalArgumentException("PluginWire.decode: unknown tag '${wire[0]}'")
        }
    }
}
