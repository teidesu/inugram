package desu.inugram.helpers.plugins.tl

import desu.inugram.core.plugins.ApiFilter
import desu.inugram.core.plugins.PluginPermissions
import desu.inugram.core.plugins.ScopeMatch
import desu.inugram.core.plugins.TlTables
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

/**
 * Filters TL values consistently across [TlHandles] views and [TlJson] snapshots.
 * Applies takeover filtering unless `unsafe.disableApiFiltering` is granted, and requires
 * `account.read(draft)` for draft text wherever it appears, not just through `getDraft`.
 *
 * Build one [Policy] per plugin in [PluginRpc.attach], so new API methods use the same rules.
 *
 * Match [ApiFilter.HIDDEN_FIELDS] by constructor ID. Name checks would miss legacy names such
 * as `message_old7`; `idsOf("message")` includes all variants. Cache decisions per class
 * because every field read uses this check.
 */
object TlFilter {
    /**
     * neither is a *call* gate - both decide which fields exist, which is why they are read at
     * materialization and not at the api's entry.
     */
    class Policy(val takeover: Boolean, val drafts: Boolean)

    fun policyFor(permissions: PluginPermissions): Policy = Policy(
        takeover = !permissions.has("unsafe.disableApiFiltering"),
        drafts = permissions.allows("account.read", "draft", ScopeMatch.EXACT),
    )

    private val hiddenTypeByCtorId: Map<Int, String> by lazy { indexByCtorId(ApiFilter.HIDDEN_FIELDS.keys) }

    /** internal rather than private because no hidden type has a legacy variant today: taking the name set is the only way left to state that the whole family is matched */
    internal fun indexByCtorId(names: Set<String>): Map<Int, String> {
        val out = HashMap<Int, String>()
        for (name in names) {
            for (id in TlTables.idsOf(name).orEmpty()) out[id] = name
        }
        return out
    }

    // ConcurrentHashMap cannot hold a null value, so "not a hidden type" memoizes as the empty name
    private val hiddenTypeByClass = ConcurrentHashMap<Class<*>, String>()

    fun hidesField(policy: Policy, info: TlReflect.FieldInfo): Boolean =
        (policy.takeover && info.hiddenInTakeover) || (!policy.drafts && info.isDraft)

    internal fun hidesTakeoverField(cls: Class<*>, key: String): Boolean {
        val tlName = hiddenTypeByClass.getOrPut(cls) {
            findConstructorId(cls)?.let { hiddenTypeByCtorId[it] } ?: ""
        }
        if (tlName.isEmpty()) return false
        return ApiFilter.HIDDEN_FIELDS[tlName]?.contains(key) == true
    }

    /** the only field [filterFieldValue] ever touches: the message body on the shapes a login code arrives in */
    fun canRedactField(cls: Class<*>, key: String): Boolean =
        key == ApiFilter.REDACTED_MESSAGE_FIELD &&
            (TLRPC.Message::class.java.isAssignableFrom(cls) || TLRPC.Updates::class.java.isAssignableFrom(cls))

    fun filterFieldValue(target: TLObject, key: String, value: Any?): Any? {
        if (key != ApiFilter.REDACTED_MESSAGE_FIELD || value !is String) return value
        val service = when (target) {
            is TLRPC.Message -> ApiFilter.isServiceMessage(
                fromUserId = findPeerUserId(target.from_id),
                peerUserId = findPeerUserId(target.peer_id),
                forwardedFromUserId = findPeerUserId(target.fwd_from?.from_id),
                out = target.out,
            )
            // `updateShortMessage` is the form a 1:1 message actually arrives in, and `Updates`
            // declares its own `message` rather than carrying a `Message` - so a check keyed on the
            // Message class walks past the very shape a login code reaches the device in. The update
            // fan-out never sees this because `PluginRpc.normalizeShortMessage` builds a synthetic
            // `TL_message` first; TL views can still represent the real object.
            is TLRPC.Updates -> ApiFilter.isServiceMessage(
                fromUserId = (if (target.chat_id != 0L) target.from_id else target.user_id)
                    .takeIf { !target.out },
                peerUserId = target.user_id.takeIf { target.chat_id == 0L },
                forwardedFromUserId = findPeerUserId(target.fwd_from?.from_id),
                out = target.out,
            )
            else -> return value
        }
        if (!service) return value
        return ApiFilter.redactLoginCodes(value)
    }

    /**
     * Identifies fields used by [filterFieldValue]. Reject writes and expose their values
     * read-only so a plugin cannot clear the sender to reveal redacted text. Decisions are
     * recomputed on every read, including [TlJson] snapshots; protecting the inputs keeps
     * those decisions consistent.
     */
    fun decidesRedaction(cls: Class<*>, key: String): Boolean = when {
        TLRPC.Message::class.java.isAssignableFrom(cls) ->
            key in ApiFilter.REDACTION_EVIDENCE_FIELDS
        TLRPC.Updates::class.java.isAssignableFrom(cls) ->
            key in ApiFilter.SHORT_REDACTION_EVIDENCE_FIELDS
        else -> false
    }

    private fun findPeerUserId(peer: TLRPC.Peer?): Long? = (peer as? TLRPC.TL_peerUser)?.user_id

    private fun findConstructorId(cls: Class<*>): Int? {
        var current: Class<*>? = cls
        while (current != null) {
            val field = try {
                current.getDeclaredField("constructor")
            } catch (e: NoSuchFieldException) {
                null
            }
            if (field != null && Modifier.isStatic(field.modifiers) && field.type == Integer.TYPE) {
                return try {
                    field.isAccessible = true
                    field.getInt(null)
                } catch (e: Exception) {
                    null
                }
            }
            current = current.superclass
        }
        return null
    }
}
