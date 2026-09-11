package desu.inugram.helpers.plugins.telegram

import org.telegram.tgnet.InputSerializedData
import org.telegram.tgnet.OutputSerializedData
import org.telegram.tgnet.TLObject

/**
 * What `inu.invokeRaw` sends: a method stock has no class for, as the bytes the plugin serialized
 * itself. The payload is the whole method - constructor id first - because that is what a TL method
 * is, and stock's `sendRequestInternal` writes it into the request body verbatim.
 *
 * The response comes back the same way: [RawTlResponse] carries the constructor and everything
 * after it, copied out of the buffer stock reuses the moment the delegate returns.
 */
class RawTlRequest(private val payload: ByteArray) : TLObject() {
    override fun serializeToStream(stream: OutputSerializedData) = stream.writeBytes(payload)

    override fun deserializeResponse(stream: InputSerializedData, constructor: Int, exception: Boolean): TLObject {
        val rest = stream.readData(stream.remaining(), exception) ?: ByteArray(0)
        val bytes = ByteArray(Int.SIZE_BYTES + rest.size)
        bytes[0] = constructor.toByte()
        bytes[1] = (constructor ushr 8).toByte()
        bytes[2] = (constructor ushr 16).toByte()
        bytes[3] = (constructor ushr 24).toByte()
        rest.copyInto(bytes, Int.SIZE_BYTES)
        return RawTlResponse(bytes)
    }

    /** the constructor the payload opens with, or `null` when it is too short to name one */
    fun constructorId(): Int? {
        if (payload.size < Int.SIZE_BYTES) return null
        return (payload[0].toInt() and 0xFF) or
            ((payload[1].toInt() and 0xFF) shl 8) or
            ((payload[2].toInt() and 0xFF) shl 16) or
            ((payload[3].toInt() and 0xFF) shl 24)
    }

    override fun toString(): String = "invokeRaw(${payload.size} bytes)"
}

class RawTlResponse(@JvmField val bytes: ByteArray) : TLObject() {
    override fun serializeToStream(stream: OutputSerializedData) = stream.writeBytes(bytes)

    override fun toString(): String = "invokeRaw response (${bytes.size} bytes)"
}
