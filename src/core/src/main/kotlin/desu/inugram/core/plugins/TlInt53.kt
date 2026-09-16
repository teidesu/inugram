package desu.inugram.core.plugins

/**
 * Long fields that cross to plugins as a js number rather than a decimal string. Telegram guarantees
 * user/chat/channel ids and file sizes fit in 53 bits; the list is mtcute's, vendored in
 * `scripts/data/int53-overrides.json` and generated per constructor id.
 */
object TlInt53 {
    fun fieldsOf(cls: Class<*>): Set<String> {
        val id = TlTables.constructorIdOf(cls) ?: return emptySet()
        return TlTables.table.int53ById[id].orEmpty()
    }

    fun isInt53(cls: Class<*>, name: String): Boolean = name in fieldsOf(cls)
}
