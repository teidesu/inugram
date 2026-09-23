package desu.inugram.helpers.plugins.tl

import desu.inugram.core.plugins.TlTables

/**
 * [TlTables.Table.nameOverrides] supplies schema names where stock's java names differ; a legacy
 * variant sharing a schema name keeps its derived name. In derivation, `foo_` is a namespace only if
 * `foo` is known (`TL_user_old` is not `user.old`), and containers are unreliable:
 * `TL_stars.transferStarGift` belongs to `payments`.
 */
object TlNames {
    private val LAYER_SUFFIX = Regex("_layer\\d+$")

    fun isLayerVariant(className: String): Boolean = LAYER_SUFFIX.containsMatchIn(className)

    fun classNameToTlName(cls: Class<*>): String {
        val container = cls.enclosingClass?.simpleName ?: ""
        val stripped = cls.simpleName.replace(LAYER_SUFFIX, "")
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
