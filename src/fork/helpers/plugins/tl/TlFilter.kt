package desu.inugram.helpers.plugins.tl

import desu.inugram.core.plugins.TlTables
import desu.inugram.core.plugins.PluginPermissions
import desu.inugram.core.plugins.ScopeMatch
import java.util.concurrent.ConcurrentHashMap
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

/**
 * Login codes and sensitive fields are filtered regardless of grants; takeover filtering unless
 * `unsafe.disableApiFiltering`; draft text needs `account.read(draft)` wherever it appears.
 *
 * [HIDDEN_FIELDS] matches by constructor id: name checks miss legacy names like `message_old7`.
 * Decisions are cached per class because every field read asks.
 */
object TlFilter {
    /** decides which fields exist, so it is read at materialization, not at the api's entry */
    class Policy(val takeover: Boolean, val drafts: Boolean)

    fun policyFor(permissions: PluginPermissions): Policy = Policy(
        takeover = !permissions.has("unsafe.disableApiFiltering"),
        drafts = permissions.allows("account.read", "draft", ScopeMatch.EXACT),
    )

    private const val REDACTED_MESSAGE_FIELD = "message"

    /** sealed, or a writable view could clear the sender and re-read the text in clear */
    private val REDACTION_EVIDENCE_FIELDS: Set<String> = setOf("from_id", "peer_id", "fwd_from", "out")

    /** short updates carry the text inline and name the sender with bare `long` ids */
    private val SHORT_REDACTION_EVIDENCE_FIELDS: Set<String> =
        setOf("from_id", "user_id", "chat_id", "fwd_from", "out")

    /** `updateServiceNotification` also rides inside `getDifference`, where dropping it would punch a hole in a vector */
    internal val HIDDEN_FIELDS: Map<String, Set<String>> = mapOf(
        "config" to setOf("autologin_token"),
        "updateServiceNotification" to setOf("message", "media", "entities"),
    )

    private val LOGIN_CODE = Regex("[0-9-]{5,}")

    private val hiddenTypeByCtorId: Map<Int, String> by lazy { indexByCtorId(HIDDEN_FIELDS.keys) }

    /** internal because no hidden type has a legacy variant today; tests state the whole family through this */
    internal fun indexByCtorId(names: Set<String>): Map<Int, String> {
        val out = HashMap<Int, String>()
        for (name in names) {
            for (id in TlTables.getConstructorIds(name).orEmpty()) out[id] = name
        }
        return out
    }

    // ConcurrentHashMap cannot hold null, so "not hidden" memoizes as ""
    private val hiddenTypeByClass = ConcurrentHashMap<Class<*>, String>()

    fun hidesField(policy: Policy, info: TlReflect.FieldInfo): Boolean =
        (policy.takeover && info.hiddenInTakeover) || (!policy.drafts && info.isDraft)

    internal fun hidesTakeoverField(cls: Class<*>, key: String): Boolean {
        val tlName = hiddenTypeByClass.getOrPut(cls) {
            generateSequence(cls) { it.superclass }.firstNotNullOfOrNull(TlTables::readConstructorId)
                ?.let { hiddenTypeByCtorId[it] } ?: ""
        }
        if (tlName.isEmpty()) return false
        return HIDDEN_FIELDS[tlName]?.contains(key) == true
    }

    /** a bare literal at every stock site (`MessagesController.storiesChangelogUserId` is the same number, meaning something else) */
    const val SERVICE_USER_ID = 777000L

    fun isServiceSender(userId: Long): Boolean = userId == SERVICE_USER_ID || userId == UserObject.VERIFY

    /**
     * `from_id` is `flags.8?Peer`, omitted by the server in a 1:1 dialog, and stock backfills it only on the
     * cache load path. A forward keeps the original sender in `fwd_from` alone.
     */
    fun isServiceMessage(
        fromUserId: Long?,
        peerUserId: Long?,
        forwardedFromUserId: Long?,
        out: Boolean,
    ): Boolean {
        if (fromUserId != null && isServiceSender(fromUserId)) return true
        if (forwardedFromUserId != null && isServiceSender(forwardedFromUserId)) return true
        return !out && peerUserId != null && isServiceSender(peerUserId)
    }

    fun canRedactField(cls: Class<*>, key: String): Boolean =
        key == REDACTED_MESSAGE_FIELD &&
            (TLRPC.Message::class.java.isAssignableFrom(cls) || TLRPC.Updates::class.java.isAssignableFrom(cls))

    fun filterFieldValue(target: TLObject, key: String, value: Any?): Any? {
        if (key != REDACTED_MESSAGE_FIELD || value !is String) return value
        val service = when (target) {
            is TLRPC.Message -> isServiceMessage(
                fromUserId = findPeerUserId(target.from_id),
                peerUserId = findPeerUserId(target.peer_id),
                forwardedFromUserId = findPeerUserId(target.fwd_from?.from_id),
                out = target.out,
            )
            // `updateShortMessage` is how a 1:1 message actually arrives, with its own `message` field rather than
            // a `Message`. The fan-out normalizes it first, but TL views can still show the real object.
            is TLRPC.Updates -> isServiceMessage(
                fromUserId = (if (target.chat_id != 0L) target.from_id else target.user_id)
                    .takeIf { !target.out },
                peerUserId = target.user_id.takeIf { target.chat_id == 0L },
                forwardedFromUserId = findPeerUserId(target.fwd_from?.from_id),
                out = target.out,
            )
            else -> return value
        }
        if (!service) return value
        return LOGIN_CODE.replace(value) { "*".repeat(it.value.length) }
    }

    /** read-only and unwritable, or a plugin could clear the sender to reveal redacted text */
    fun decidesRedaction(cls: Class<*>, key: String): Boolean = when {
        TLRPC.Message::class.java.isAssignableFrom(cls) ->
            key in REDACTION_EVIDENCE_FIELDS
        TLRPC.Updates::class.java.isAssignableFrom(cls) ->
            key in SHORT_REDACTION_EVIDENCE_FIELDS
        else -> false
    }

    private fun findPeerUserId(peer: TLRPC.Peer?): Long? = (peer as? TLRPC.TL_peerUser)?.user_id
}
