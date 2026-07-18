package desu.inugram.core.plugins

/**
 * maps between java `TL_*` simple class names and the `namespace.method`-ish names plugins see
 * over the JSON bridge (e.g. `TL_messages_sendMessage` <-> `messages.sendMessage`).
 *
 * rule: strip the `TL_` prefix; if the leading segment up to the first underscore is entirely
 * lowercase, that segment is a namespace — replace that first underscore with a dot. any
 * remaining underscores are left as-is (they're part of the member name, e.g. `updateShort_message`
 * style names don't occur in practice, but nested legacy names might carry extra underscores).
 */
object TlNames {
    private val LAYER_SUFFIX = Regex("_layer\\d+$")

    /** true if [className] is a layer-suffixed legacy variant, e.g. `TL_someType_layer131` */
    fun isLayerVariant(className: String): Boolean = LAYER_SUFFIX.containsMatchIn(className)

    /** strips a trailing `_layerNNN` suffix, if present */
    fun stripLayerSuffix(className: String): String = className.replace(LAYER_SUFFIX, "")

    /** `TL_messages_sendMessage` -> `messages.sendMessage`; `TL_updateNewMessage` -> `updateNewMessage` */
    fun classNameToTlName(className: String): String {
        val withoutPrefix = className.removePrefix("TL_")
        val withoutLayer = stripLayerSuffix(withoutPrefix)
        val underscoreIdx = withoutLayer.indexOf('_')
        if (underscoreIdx < 0) return withoutLayer
        val namespace = withoutLayer.substring(0, underscoreIdx)
        if (namespace.isNotEmpty() && namespace.all { it.isLowerCase() }) {
            return namespace + "." + withoutLayer.substring(underscoreIdx + 1)
        }
        return withoutLayer
    }

    /** `messages.sendMessage` -> `TL_messages_sendMessage`; `updateNewMessage` -> `TL_updateNewMessage` */
    fun tlNameToClassName(tlName: String): String {
        val dotIdx = tlName.indexOf('.')
        val body = if (dotIdx >= 0) {
            tlName.substring(0, dotIdx) + "_" + tlName.substring(dotIdx + 1)
        } else {
            tlName
        }
        return "TL_$body"
    }
}
