package desu.inugram.helpers.plugins.telegram

import desu.inugram.core.plugins.PluginRefusal
import desu.inugram.core.plugins.PluginWire
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

// InputPeerLike on the wire
object PeerSpecs {
    // keep in sync with rust `reads::KIND_*`
    const val KIND_PEER = 0
    const val KIND_USER = 1
    const val KIND_CHANNEL = 2

    const val LIST_SEPARATOR = "\n"

    const val SPEC_SELF = 'S'
    const val SPEC_DIALOG_ID = 'D'
    const val SPEC_USERNAME = 'U'

    // a marked peer id is the bot api's scheme: a basic group is -id and a channel is -1000000000000 - id
    const val ZERO_CHANNEL_ID = -1_000_000_000_000L

    fun toSimpleDialogId(markedPeerId: Long): Long =
        if (markedPeerId < ZERO_CHANNEL_ID) markedPeerId - ZERO_CHANNEL_ID else markedPeerId

    fun toMarkedPeerId(controller: MessagesController, dialogId: Long): Long =
        if (dialogId < 0 && ChatObject.isChannel(controller.getChat(-dialogId))) ZERO_CHANNEL_ID + dialogId else dialogId

    fun controllerFor(accountId: Int): MessagesController? {
        if (!UserConfig.isValidAccount(accountId)) return null
        return MessagesController.getInstance(accountId)
    }

    fun resolveDialogId(controller: MessagesController, accountId: Int, spec: String): Long? {
        if (spec.isEmpty()) return null
        val payload = spec.substring(1)
        val id = when (spec[0]) {
            SPEC_SELF -> UserConfig.getInstance(accountId).getClientUserId()
            SPEC_DIALOG_ID -> payload.toLongOrNull()?.let(::toSimpleDialogId)
            SPEC_USERNAME -> when (val found = controller.getUserOrChat(payload)) {
                is TLRPC.User -> found.id
                is TLRPC.Chat -> -found.id
                else -> null
            }
            else -> null
        } ?: return null
        if (DialogObject.isEncryptedDialog(id)) return null
        return id
    }

    fun splitOnce(arg: String): Pair<String, String> {
        val at = arg.indexOf(LIST_SEPARATOR)
        return if (at < 0) arg to "" else arg.substring(0, at) to arg.substring(at + 1)
    }

    fun splitList(arg: String): List<String> =
        if (arg.isEmpty()) emptyList() else arg.split(LIST_SEPARATOR)

    sealed class Built {
        object Missing : Built()
        class WrongKind(val kind: Int) : Built()
        class Peer(val value: TLObject) : Built()
    }

    /** stock's `getInputPeer` fills in a zero `access_hash` the server refuses */
    fun buildInputPeer(controller: MessagesController, accountId: Int, spec: String, kind: Int): Built {
        val id = resolveDialogId(controller, accountId, spec) ?: return Built.Missing
        if (id == 0L) return Built.Missing
        val self = UserConfig.getInstance(accountId).getClientUserId()
        if (id == self) {
            // built: the logged-in user is not always cached, and stock's getInputUser answers TL_inputUserEmpty then
            return when (kind) {
                KIND_CHANNEL -> Built.WrongKind(kind)
                KIND_USER -> Built.Peer(TLRPC.TL_inputUserSelf())
                else -> Built.Peer(TLRPC.TL_inputPeerSelf())
            }
        }
        if (controller.getUserOrChat(id) == null) return Built.Missing
        return when (kind) {
            KIND_USER -> if (id > 0) Built.Peer(controller.getInputUser(id)) else Built.WrongKind(kind)
            KIND_CHANNEL -> {
                val channel = if (id < 0) controller.getInputChannel(-id) else null
                if (channel == null || channel is TLRPC.TL_inputChannelEmpty) Built.WrongKind(kind)
                else Built.Peer(channel)
            }
            else -> Built.Peer(controller.getInputPeer(id))
        }
    }

    fun noAccountWire(what: String, accountId: Int): String =
        PluginWire.encodePluginError("not-found", "$what: no account is logged in as #$accountId")

    fun requireInputPeer(controller: MessagesController, accountId: Int, spec: String, kind: Int): TLObject =
        when (val built = buildInputPeer(controller, accountId, spec, kind)) {
            is Built.Missing -> PluginWire.refuse(
                "not-found",
                "${describeSpec(spec)} is not cached; resolve it with resolvePeer() first",
            )
            is Built.WrongKind -> throw PluginRefusal(wrongKind(spec, built.kind))
            is Built.Peer -> built.value
        }

    fun wrongKind(spec: String, kind: Int): String =
        PluginWire.encodePluginError("invalid-argument", "${describeSpec(spec)} is not ${describeKind(kind)}")

    fun describeSpec(spec: String): String {
        val payload = spec.drop(1)
        return when (spec.firstOrNull()) {
            SPEC_SELF -> "'me'"
            SPEC_USERNAME -> "'@$payload'"
            else -> "'$payload'"
        }
    }

    fun describeKind(kind: Int): String = when (kind) {
        KIND_USER -> "a user"
        KIND_CHANNEL -> "a channel"
        else -> "a peer"
    }
}
