package desu.inugram.core.plugins

/**
 * What a declarative `interceptDeserialize` rule may not rewrite, whatever its grants. Not a
 * filter, so `unsafe.disableApiFiltering` does not lift it.
 *
 * The hazard no other api has: the app writes its own parsed objects back to sqlite
 * (`MessagesStorage` re-serializes the live `users`/`chats`/`messages_v2`/`dialogs` rows), so a
 * bad rule is on disk until that row is replaced - for a row the server never re-sends, never.
 */
object DeserializeGuards {
    /**
     * TL's convention is that `id` and anything ending in `_id` *address* another object, and
     * stock keys its sqlite rows on exactly those. A rewritten one is well-formed, so the app
     * cannot notice, and a refetch writes to the row the wrong id names.
     */
    private fun isReferenceField(name: String): Boolean = name == "id" || name.endsWith("_id")

    /**
     * the addressing fields carrying no `_id` suffix: without the real `access_hash` the peer is
     * unaddressable, a wrong `dc_id` sends every file request to the wrong datacenter, and
     * `file_reference` is what stock's refresh path keys on - a rewritten one is permanent.
     */
    private val ADDRESSING_FIELDS = setOf("access_hash", "dc_id", "file_reference")

    fun isProtectedField(name: String): Boolean = isReferenceField(name) || name in ADDRESSING_FIELDS

    fun protectedFieldReason(name: String): String =
        "'$name' addresses the object rather than describing it, and a rewrite of it is written " +
            "back to the local database where nothing can repair it"

    private val CIPHER = Regex("(en|de)crypt", RegexOption.IGNORE_CASE)

    /**
     * secret-chat traffic, which no rule may target. A substring rather than a prefix list: the
     * family is spread over `encrypted*`, `decrypted*`, `updateNewEncryptedMessage`,
     * `messages.sentEncryptedMessage`, `documentEncrypted` and more, and a prefix list is one a
     * layer bump silently gets wrong. The stem stops at `crypt` because the key exchange
     * conjugates differently: `crypted` walks straight past `updateEncryption`. `message_secret`
     * is separate - its layer variants carry no canonical name and go by constructor id in
     * `PluginDeserialize`.
     */
    fun isSecretName(name: String): Boolean =
        CIPHER.containsMatchIn(name) || name.startsWith("message_secret")
}
