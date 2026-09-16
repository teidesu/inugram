package desu.inugram.core.plugins

/**
 * maps stock's java TL classes onto the `namespace.member` names plugins see over the JSON bridge
 * (`TLRPC.TL_messages_sendMessage` -> `messages.sendMessage`).
 *
 * the name is whatever the TL schema calls the constructor, so [TlTables.Table.nameOverrides] lists
 * every class stock spells differently. the exception is the legacy variants of one predicate:
 * `TL_message` and `TL_message_old7` are both `message` on the wire, so whichever loses the name
 * keeps its derived one.
 *
 * that derivation is also the fallback for classes the schema dumps don't cover, and it can only
 * guess the namespace. a leading `foo_` is one only when `foo` really is a namespace - otherwise
 * `TL_user_old` would read as `user.old` - and the ~200 classes stock declares without a `TL_`
 * prefix carry nothing to read at all. nor does the container decide it: `TL_stars.transferStarGift`
 * is `payments.transferStarGift`, and `TL_stories.TL_storyView` is namespaced nowhere.
 *
 * the overrides and namespaces are generated into [TlTables] from stock's own layer dumps.
 */
object TlNames {
    private val LAYER_SUFFIX = Regex("_layer\\d+$")

    fun isLayerVariant(className: String): Boolean = LAYER_SUFFIX.containsMatchIn(className)

    fun stripLayerSuffix(className: String): String = className.replace(LAYER_SUFFIX, "")

    fun classNameToTlName(cls: Class<*>): String =
        classNameToTlName(cls.enclosingClass?.simpleName ?: "", cls.simpleName)

    /** [container] is the enclosing class's simple name, e.g. `TLRPC` or `TL_account` */
    fun classNameToTlName(container: String, className: String): String {
        val stripped = stripLayerSuffix(className)
        val table = TlTables.table
        table.nameOverrides["$container.$stripped"]?.let { return it }

        val member = stripped.removePrefix("TL_")
        val underscoreIdx = member.indexOf('_')
        if (underscoreIdx > 0) {
            val prefix = member.substring(0, underscoreIdx)
            if (prefix in table.namespaces) return "$prefix.${member.substring(underscoreIdx + 1)}"
        }
        return member
    }
}
