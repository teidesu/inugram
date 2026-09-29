package desu.inugram.helpers.plugins.tl

import desu.inugram.core.plugins.TlTables

/** telegram guarantees ids and file sizes fit in 53 bits. list from mtcute, vendored in `scripts/data/int53-overrides.json` */
object TlInt53 {
    fun getInt53Fields(cls: Class<*>): Set<String> {
        val id = TlTables.readConstructorId(cls) ?: return emptySet()
        return TlTables.table.int53ById[id].orEmpty()
    }

    fun isInt53(cls: Class<*>, name: String): Boolean = name in getInt53Fields(cls)
}
