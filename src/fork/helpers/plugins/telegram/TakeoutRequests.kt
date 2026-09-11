package desu.inugram.helpers.plugins.telegram

import org.telegram.tgnet.InputSerializedData
import org.telegram.tgnet.OutputSerializedData
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

/**
 * The takeout corner of the schema, which stock android does not implement: it never exports, so
 * `TLRPC.java` declares no class for any of these and the generated tables cannot name them either.
 * Written by hand rather than generated, and deliberately kept out of [desu.inugram.helpers.plugins.tl.TlReflect]'s
 * class index: a plugin reaches takeout through `account.initTakeoutSession()` and the session
 * object it answers, never by naming one of these constructors in `invokeRpc`.
 *
 * Ids and layouts are layer 230's, from `TMessagesProj_AppTests/tlscheme/230.json`. They have not
 * moved since layer 100 and the wrapper cannot change without breaking every exporting client, so
 * a rebase has nothing to do here.
 *
 * Flags are computed in [TLObject.serializeToStream] instead of riding on a `flags` field, because
 * `tl_flags.txt` only covers classes the generator found in stock's tree.
 */
class TakeoutInitRequest : TLObject() {
    @JvmField var contacts = false
    @JvmField var messageUsers = false
    @JvmField var messageChats = false
    @JvmField var messageMegagroups = false
    @JvmField var messageChannels = false
    @JvmField var files = false

    /** only written when [files] is set - the schema gates both on flags.5 */
    @JvmField var fileMaxSize = 0L

    override fun serializeToStream(stream: OutputSerializedData) {
        stream.writeInt32(CONSTRUCTOR)
        var flags = 0
        if (contacts) flags = flags or (1 shl 0)
        if (messageUsers) flags = flags or (1 shl 1)
        if (messageChats) flags = flags or (1 shl 2)
        if (messageMegagroups) flags = flags or (1 shl 3)
        if (messageChannels) flags = flags or (1 shl 4)
        if (files) flags = flags or (1 shl 5)
        stream.writeInt32(flags)
        if (files) stream.writeInt64(fileMaxSize)
    }

    override fun deserializeResponse(stream: InputSerializedData, constructor: Int, exception: Boolean): TLObject? {
        if (constructor != TakeoutSession.CONSTRUCTOR) {
            if (exception) throw RuntimeException("unexpected response 0x${Integer.toHexString(constructor)} for account.initTakeoutSession")
            return null
        }
        return TakeoutSession().apply { id = stream.readInt64(exception) }
    }

    override fun toString(): String = "account.initTakeoutSession"

    companion object {
        val CONSTRUCTOR = 0x8ef3eab0.toInt()
    }
}

/** `account.takeout#4dba4501 id:long = account.Takeout` - the id is all a session is */
class TakeoutSession : TLObject() {
    @JvmField var id = 0L

    override fun serializeToStream(stream: OutputSerializedData) {
        stream.writeInt32(CONSTRUCTOR)
        stream.writeInt64(id)
    }

    override fun toString(): String = "account.takeout"

    companion object {
        const val CONSTRUCTOR = 0x4dba4501
    }
}

/** must itself be sent inside [TakeoutWrapper], which is what closes the session it names */
class TakeoutFinishRequest(private val success: Boolean) : TLObject() {
    override fun serializeToStream(stream: OutputSerializedData) {
        stream.writeInt32(CONSTRUCTOR)
        stream.writeInt32(if (success) 1 shl 0 else 0)
    }

    override fun deserializeResponse(stream: InputSerializedData, constructor: Int, exception: Boolean): TLObject? =
        TLRPC.Bool.TLdeserialize(stream, constructor, exception)

    override fun toString(): String = "account.finishTakeoutSession"

    companion object {
        const val CONSTRUCTOR = 0x1d2652ee
    }
}

/**
 * `invokeWithTakeout#aca9fd2e {X:Type} takeout_id:long query:!X = X` - an envelope, so it answers
 * whatever [query] would have answered and frees whatever [query] holds.
 */
class TakeoutWrapper(private val takeoutId: Long, @JvmField val query: TLObject) : TLObject() {
    override fun serializeToStream(stream: OutputSerializedData) {
        stream.writeInt32(CONSTRUCTOR)
        stream.writeInt64(takeoutId)
        query.serializeToStream(stream)
    }

    override fun deserializeResponse(stream: InputSerializedData, constructor: Int, exception: Boolean): TLObject? =
        query.deserializeResponse(stream, constructor, exception)

    override fun freeResources() = query.freeResources()

    override fun toString(): String = "invokeWithTakeout($query)"

    companion object {
        val CONSTRUCTOR = 0xaca9fd2e.toInt()
    }
}
