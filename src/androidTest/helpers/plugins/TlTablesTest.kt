package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.TlCtorIds
import desu.inugram.core.plugins.TlFlags
import desu.inugram.core.plugins.TlNames
import desu.inugram.helpers.plugins.tl.TlJson
import desu.inugram.helpers.plugins.tl.TlReflect
import java.lang.reflect.Modifier
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Test
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

/**
 * The three tables `:InuCore` ships (`TlCtorIds`, `TlNames`, `TlFlags`) against the classes they
 * describe. They are generated from stock by `pnpm run generate-tl-typings` and committed, so a
 * rebase that moves a constructor id, renames a class or adds a flag word leaves them describing a
 * tree that is no longer there - and every grant scope, every api filter entry and every optional
 * field read is keyed off them.
 *
 * Here rather than off-device because the subject is stock's own `org.telegram.tgnet` tree: nothing
 * standing in for it can answer whether the tables still fit.
 */
class TlTablesTest {
    private val containers: List<Class<*>> = listOf(
        TLRPC::class.java,
        org.telegram.tgnet.tl.TL_account::class.java,
        org.telegram.tgnet.tl.TL_aicompose::class.java,
        org.telegram.tgnet.tl.TL_bots::class.java,
        org.telegram.tgnet.tl.TL_chatlists::class.java,
        org.telegram.tgnet.tl.TL_communities::class.java,
        org.telegram.tgnet.tl.TL_forum::class.java,
        org.telegram.tgnet.tl.TL_fragment::class.java,
        org.telegram.tgnet.tl.TL_iv::class.java,
        org.telegram.tgnet.tl.TL_payments::class.java,
        org.telegram.tgnet.tl.TL_phone::class.java,
        org.telegram.tgnet.tl.TL_stars::class.java,
        org.telegram.tgnet.tl.TL_stats::class.java,
        org.telegram.tgnet.tl.TL_stories::class.java,
        org.telegram.tgnet.tl.TL_update::class.java,
        org.telegram.tgnet.tl.legacy.TL_legacy_message::class.java,
    )

    private fun serializableClasses(): List<Class<*>> = containers
        .flatMap { it.declaredClasses.asSequence() }
        .filter { TLObject::class.java.isAssignableFrom(it) }
        .filter { !Modifier.isAbstract(it.modifiers) }
        .filter {
            runCatching { it.getDeclaredField("constructor") }.getOrNull()
                ?.let { field -> Modifier.isStatic(field.modifiers) && field.type == Integer.TYPE } == true
        }

    /**
     * `vector` is stock's `org.telegram.tgnet.Vector`, a top-level class rather than a member of a
     * TL container - so it is in the id table and in neither the walk below nor [TlJson]'s own
     * container list. A plugin asking to construct one gets `invalid-argument`.
     */
    private val notInAnyContainer = setOf("vector")

    /**
     * one case rather than three, and reading the fields itself rather than through
     * [TlReflect.publicFields]: walking the tree loads every TL class into the process, and a cache
     * keyed by class then holds a `Field` per field of each for the rest of the run. Three walks
     * plus that was enough to have the instrumentation process killed, in whichever suite happened
     * to be running next.
     */
    @Test
    fun the_tables_describe_the_tree_stock_actually_ships() {
        val declared = HashSet<Int>()
        val brokenFlags = ArrayList<String>()
        for (cls in serializableClasses()) {
            declared.add(cls.getDeclaredField("constructor").also { it.isAccessible = true }.getInt(null))
            if (brokenFlags.size > 5) continue
            val fields = publicFieldNames(cls)
            for (word in TlFlags.wordsOf(cls)) {
                val name = TlFlags.wordName(word) ?: continue
                if (name !in fields) brokenFlags.add("${cls.name}.$name")
            }
        }
        assertTrue(brokenFlags.isEmpty(), "flag words with no java field behind them: $brokenFlags")

        val names = TlCtorIds.allNames - notInAnyContainer
        val missing = names.filter { name -> TlCtorIds.idsOf(name).orEmpty().none { it in declared } }
        assertTrue(missing.isEmpty(), "the tables are stale, re-run `pnpm run generate-tl-typings`: ${missing.take(10)}")

        val failures = names.mapNotNull { name ->
            val built = runCatching { TlJson.fromJson(JSONObject("""{"_":"$name"}""")) }
            val error = built.exceptionOrNull()
            when {
                error != null -> "$name: ${error.message}"
                else -> TlNames.classNameToTlName(built.getOrThrow().javaClass)
                    .takeIf { it != name }
                    ?.let { "$name: resolved to a class that calls itself '$it'" }
            }
        }
        assertTrue(failures.isEmpty(), "${failures.size} of ${names.size} names failed: ${failures.take(5)}")
    }

    private fun publicFieldNames(cls: Class<*>): Set<String> {
        val names = HashSet<String>()
        var current: Class<*>? = cls
        while (current != null) {
            for (field in current.declaredFields) {
                if (!Modifier.isPublic(field.modifiers)) continue
                if (Modifier.isStatic(field.modifiers)) continue
                if (field.isSynthetic) continue
                names.add(field.name)
            }
            current = current.superclass
        }
        return names
    }
}
