package desu.inugram.helpers.plugins.telegram

import org.telegram.tgnet.InputSerializedData
import org.telegram.tgnet.OutputSerializedData
import org.telegram.tgnet.TLObject

/**
 * Stock's `sendRequestInternal` writes the payload verbatim. [RawTlResponse] copies the response out of
 * the buffer stock reuses when the delegate returns.
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

    override fun toString(): String = "invokeRaw(${payload.size} bytes)"
}

class RawTlResponse(@JvmField val bytes: ByteArray) : TLObject() {
    override fun serializeToStream(stream: OutputSerializedData) = stream.writeBytes(bytes)

    override fun toString(): String = "invokeRaw response (${bytes.size} bytes)"
}
