package desu.inugram.core.plugins

/**
 * What the plugin api never materializes, whatever the grants (`common.d.ts`: "account-takeover
 * surfaces are filtered") - login code redaction and the hidden-field table; method refusal is
 * [TakeoverMethods]. `unsafe.disableApiFiltering` bypasses all of it.
 */
object ApiFilter {
    const val SERVICE_USER_ID = 777000L
    const val VERIFY_USER_ID = 489000L

    fun isServiceSender(userId: Long): Boolean = userId == SERVICE_USER_ID || userId == VERIFY_USER_ID

    /**
     * `from_id` is `flags.8?Peer` and the server omits it for a 1:1 dialog, where the sender is
     * the dialog peer; stock backfills it only on the cache load path. A forward keeps the
     * original sender in `fwd_from` alone.
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

    const val REDACTED_MESSAGE_FIELD = "message"

    /** sealed against writes, or a writable view could clear the sender and re-read the text in clear */
    val REDACTION_EVIDENCE_FIELDS: Set<String> = setOf("from_id", "peer_id", "fwd_from", "out")

    /**
     * the same evidence on `updateShortMessage`/`updateShortChatMessage`, which carry the text
     * inline and name their sender with bare `long` ids rather than a `Peer`.
     */
    val SHORT_REDACTION_EVIDENCE_FIELDS: Set<String> =
        setOf("from_id", "user_id", "chat_id", "fwd_from", "out")

    /**
     * `updateServiceNotification` is dropped on the update fan-out, but the same constructor also
     * rides inside a `getDifference` response, where dropping it would punch a hole in a vector.
     */
    val HIDDEN_FIELDS: Map<String, Set<String>> = mapOf(
        "config" to setOf("autologin_token"),
        "updateServiceNotification" to setOf("message", "media", "entities"),
    )

    private val LOGIN_CODE = Regex("[0-9-]{5,}")

    /**
     * same length as the match: TL entity offsets are UTF-16 offsets into this very string, so an
     * equal-length rewrite cannot desync `entities`. Wider than stock's `[\d\-]{5,8}` on purpose -
     * an upper bound leaves the tail of a longer run readable.
     */
    fun redactLoginCodes(text: String): String = LOGIN_CODE.replace(text) { "*".repeat(it.value.length) }
}
