package desu.inugram.helpers.plugins.telegram

import desu.inugram.core.plugins.PluginWire
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

/**
 * The `InputPeerLike` vocabulary, which is the *argument* shape both tg surfaces take rather than
 * part of either of them: `reads.js`'s `toSpec` normalizes every form a plugin can write into one
 * of `S` (myself), `D<dialog id>` and `U<username>`, and nothing else about a peer ever crosses.
 *
 * Here rather than in [PluginReads] because [PluginWrites] names its peers the same way and a
 * second decoder is how the refusals below would come apart. Two of them are the point:
 *
 * - **an encrypted dialog id is never a peer** ([dialogIdOf] answering null, and `PluginWrites`
 *   refusing outright), which is what makes "secret chats, which plugin code never reaches at all"
 *   true of the whole surface rather than of whichever call site remembered to check.
 * - **"not cached" and "cached, wrong kind" are different answers** ([Built]), because the first is
 *   worth a `resolvePeer` and the second never will be.
 */
object PeerSpecs {
    // keep in sync with rust `reads::KIND_*`
    const val KIND_PEER = 0
    const val KIND_USER = 1
    const val KIND_CHANNEL = 2

    /** neither a handle nor `N` can contain a newline; a whole-op failure is a single `P` wire, which rust checks for before it splits */
    const val LIST_SEPARATOR = "\n"

    // the shapes rust's `reads.js` normalizes an `InputPeerLike` into, so nothing but a dialog id, a
    // username or "myself" ever crosses
    const val SPEC_SELF = 'S'
    const val SPEC_DIALOG_ID = 'D'
    const val SPEC_USERNAME = 'U'

    fun controllerFor(accountId: Int): MessagesController? {
        if (accountId < 0 || accountId >= UserConfig.MAX_ACCOUNT_COUNT) return null
        if (!UserConfig.isValidAccount(accountId)) return null
        return MessagesController.getInstance(accountId)
    }

    /**
     * `null` when the spec names a username the app has never seen; never fetches.
     *
     * `0` is a dialog id no dialog has, and the message reads give it a meaning of its own - the
     * common message box, the one sequence telegram numbers every user chat and basic group out
     * of. Everything else treats it as the miss it is, and [buildInputPeer] refuses it outright.
     */
    fun dialogIdOf(controller: MessagesController, accountId: Int, spec: String): Long? {
        if (spec.isEmpty()) return null
        val payload = spec.substring(1)
        val id = when (spec[0]) {
            SPEC_SELF -> UserConfig.getInstance(accountId).getClientUserId()
            SPEC_DIALOG_ID -> payload.toLongOrNull()
            SPEC_USERNAME -> when (val found = controller.getUserOrChat(payload)) {
                is TLRPC.User -> found.id
                is TLRPC.Chat -> -found.id
                else -> null
            }
            else -> null
        } ?: return null
        // every read here names its target through this one function, so refusing encrypted dialog
        // ids here is what makes `common.d.ts`'s "secret chats, which plugin code never reaches at
        // all" true of the whole surface rather than of whichever getters remembered to check
        if (DialogObject.isEncryptedDialog(id)) return null
        return id
    }

    /** the spec vocabulary makes this exact: nothing else an op sends (ids, counts, a cursor) is `S` */
    fun namesSelf(arg: String): Boolean =
        arg.splitToSequence(LIST_SEPARATOR).any { it.length == 1 && it[0] == SPEC_SELF }

    fun splitOnce(arg: String): Pair<String, String> {
        val at = arg.indexOf(LIST_SEPARATOR)
        return if (at < 0) arg to "" else arg.substring(0, at) to arg.substring(at + 1)
    }

    fun splitList(arg: String): List<String> =
        if (arg.isEmpty()) emptyList() else arg.split(LIST_SEPARATOR)

    /** "not cached" may still be worth a request; "cached, wrong kind" is a plugin's own mistake no amount of resolving changes */
    sealed class Built {
        object Missing : Built()
        class WrongKind(val kind: Int) : Built()
        class Peer(val value: TLObject) : Built()
    }

    /** stock's own `getInputPeer` answers for anything, filling in a zero `access_hash` the server refuses - the deferred failure `null` exists to avoid */
    fun buildInputPeer(controller: MessagesController, accountId: Int, spec: String, kind: Int): Built {
        val id = dialogIdOf(controller, accountId, spec) ?: return Built.Missing
        if (id == 0L) return Built.Missing
        val self = UserConfig.getInstance(accountId).getClientUserId()
        if (id == self) {
            // built rather than looked up: the logged-in user is not always in the entity cache, and stock's getInputUser answers TL_inputUserEmpty when it isn't
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

    fun wrongKind(spec: String, kind: Int): String =
        PluginWire.encodePluginError("invalid-argument", "${describeSpec(spec)} is not ${describeKind(kind)}")

    /** the spec back in the terms the plugin wrote it in, for an error message */
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
