package desu.inugram.core.plugins

/**
 * Maps stock Java TL classes to plugin wire names, such as
 * `TLRPC.TL_messages_sendMessage` to `messages.sendMessage`.
 *
 * [TlTables.Table.nameOverrides] supplies schema names where stock uses different Java names.
 * Legacy variants can share a schema name, such as `TL_message` and `TL_message_old7` both using
 * `message`; the variant that does not get that name keeps its derived name.
 *
 * Derivation is also the fallback for classes absent from schema dumps. A leading `foo_` is
 * a namespace only if `foo` is known; otherwise `TL_user_old` would become `user.old`.
 * About 200 classes have no `TL_` prefix. Containers are not reliable namespaces either:
 * `TL_stars.transferStarGift` belongs to `payments`, while `TL_stories.TL_storyView` has none.
 *
 * Overrides and namespaces are generated into [TlTables] from stock's layer dumps.
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
