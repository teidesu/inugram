package desu.inugram.core.plugins

/**
 * The single-value codec every channel across the [desu.inugram.helpers.plugins.QuickJs] boundary
 * speaks. Mirrored byte for byte by src/native/src/tl/proxy.rs; neither side may add a tag
 * without the other.
 *
 * It is named after its first caller no longer: the TL live-proxy bridge
 * (desu.inugram.helpers.plugins.tl.TlHandles) is one user among ~20, alongside kv, canvas, fetch,
 * jvm, xposed, notifications, actions, ui, media, reads, writes and deserialize. The reason they
 * share it rather than each getting typed JNI methods is that JNI has no sum type, and every one of
 * these channels carries a heterogeneous value that may also have failed - so a second
 * implementation of these tags is a second `Y` that forgets it is base64.
 *
 * Carries exactly ONE value per call, never a whole object graph, so a proxy get()/set() trap costs
 * O(1). First char is the tag; the rest is the payload.
 *
 * ```
 * N  null                    S  string                  I  int            D  double
 * B  bool                    Y  bytes (base64)          J  json construct
 * H  handle: kind + mode + id - "HOW12" = object/writable/#12, "HVR7" = vector/read-only/#7
 * E  error                   R  rpc error "code:text" -> `inu.RpcError`
 * P  plugin error -> `inu.PluginError`
 * ```
 *
 * Three more tags are minted by a single bridge each and decoded only by it, so they are not
 * encoded here - but they share this tag space and a fourth bridge must not collide with them:
 * `F<json>` a staged file ([desu.inugram.helpers.plugins.QuickJs.WritesListener]), `G<kind><id>` a
 * jvm handle ([desu.inugram.helpers.plugins.QuickJs.JvmListener]), and the `T` prefix on a thrown
 * original ([desu.inugram.helpers.plugins.QuickJs.XposedListener]).
 *
 * Note the two channel *shapes* on top of this vocabulary, which are not interchangeable - see
 * [desu.inugram.helpers.plugins.QuickJs]. A `String?` error channel must never carry an `E` wire.
 */
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
                Value.Handle(vector = kind == 'V', id = payload.substring(2).toLong(), readOnly = mode == 'R')
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
