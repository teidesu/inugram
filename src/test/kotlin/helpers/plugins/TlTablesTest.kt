package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.TlFlags
import desu.inugram.core.plugins.TlInt53
import desu.inugram.core.plugins.TlNames
import desu.inugram.core.plugins.TlTables
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlJson
import desu.inugram.helpers.plugins.tl.TlReflect
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Test
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

/**
 * The table `:InuCore` ships (`TlTables`, and through it `TlNames`, `TlFlags`, `TlInt53`)
 * against the classes it describes. It is generated from the worktree by `pnpm run generate-tl`, so
 * a rebase nobody reran it after, moving a constructor id, renaming a class or adding a flag word,
 * leaves it describing a tree that is no longer there - and every grant scope, every api filter
 * entry and every optional field read is keyed off it.
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
        org.telegram.tgnet.tl.TL_ephemeral::class.java,
        org.telegram.tgnet.tl.TL_forum::class.java,
        org.telegram.tgnet.tl.TL_fragment::class.java,
        org.telegram.tgnet.tl.TL_iv::class.java,
        org.telegram.tgnet.tl.TL_keyboard::class.java,
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
        val brokenInt53 = ArrayList<String>()
        for (cls in serializableClasses()) {
            declared.add(cls.getDeclaredField("constructor").also { it.isAccessible = true }.getInt(null))
            if (brokenFlags.size <= 5) {
                val fields = publicFieldNames(cls)
                for (word in TlFlags.wordsOf(cls)) {
                    val name = TlFlags.wordName(word) ?: continue
                    if (name !in fields) brokenFlags.add("${cls.name}.$name")
                }
            }
            if (brokenInt53.size <= 5) {
                for (name in TlInt53.fieldsOf(cls)) {
                    if (!isLongField(cls, name)) brokenInt53.add("${cls.name}.$name")
                }
            }
        }
        assertTrue(brokenFlags.isEmpty(), "flag words with no java field behind them: $brokenFlags")
        assertTrue(brokenInt53.isEmpty(), "int53 entries with no long field behind them: $brokenInt53")

        val names = TlTables.allNames - notInAnyContainer
        val missing = names.filter { name -> TlTables.idsOf(name).orEmpty().none { it in declared } }
        assertTrue(missing.isEmpty(), "the tables are stale, re-run `pnpm run generate-tl`: ${missing.take(10)}")

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

    @Test
    fun private_keyboard_types_round_trip_their_public_fields() {
        val auth = TlJson.toJson(
            TlJson.fromJson(JSONObject("""{"_":"inputInlineButtonTypeUrlAuth","url":"https://example.com","fwd_text":"Open","request_write_access":true}""")),
            TlFilter.Policy(takeover = true, drafts = false),
        )
        assertEquals("https://example.com", auth.getString("url"))
        assertEquals("Open", auth.getString("fwd_text"))
        assertTrue(auth.getBoolean("request_write_access"))
        assertTrue(!auth.has("flags"))

        val peer = TlJson.toJson(
            TlJson.fromJson(JSONObject("""{"_":"inputButtonTypeRequestPeer","button_id":7,"max_quantity":3,"name_requested":true}""")),
            TlFilter.Policy(takeover = true, drafts = false),
        )
        assertEquals(7, peer.getInt("button_id"))
        assertEquals(3, peer.getInt("max_quantity"))
        assertTrue(peer.getBoolean("name_requested"))

        val profile = TlJson.toJson(
            TlJson.fromJson(JSONObject("""{"_":"inputInlineButtonTypeUserProfile","user_id":{"_":"inputUserSelf"}}""")),
            TlFilter.Policy(takeover = true, drafts = false),
        )
        assertEquals("inputUserSelf", profile.getJSONObject("user_id").getString("_"))
    }

    private fun isLongField(cls: Class<*>, name: String): Boolean {
        var current: Class<*>? = cls
        while (current != null) {
            val field = current.declaredFields.firstOrNull { it.name == name && !Modifier.isStatic(it.modifiers) }
            if (field != null) {
                if (field.type == java.lang.Long.TYPE || field.type == java.lang.Long::class.java) return true
                val generic = field.genericType as? java.lang.reflect.ParameterizedType ?: return false
                return generic.rawType == ArrayList::class.java && generic.actualTypeArguments[0] == java.lang.Long::class.java
            }
            current = current.superclass
        }
        return false
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
