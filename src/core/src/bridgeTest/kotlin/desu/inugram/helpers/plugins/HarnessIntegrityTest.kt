package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.TlCtorIds
import desu.inugram.core.plugins.TlFlags
import desu.inugram.core.plugins.TlNames
import desu.inugram.helpers.plugins.tl.TlJson
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.json.JSONObject
import org.junit.Test
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

/**
 * The harness checking itself. Two things can rot here and nothing else would notice: the generated
 * TL tree (`src/bridgeTest/tlstubs`, re-run `pnpm run generate-tl-stubs` after a rebase) drifting
 * from the tables `:InuCore` ships, and the hand-written doubles drifting from the bridge surface
 * they stand in for.
 */
class HarnessIntegrityTest {
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
     * TL container - so it is in the id table and in neither the generated tree nor [TlJson]'s own
     * container list. A plugin asking to construct one gets `invalid-argument` in the app too.
     */
    private val notInAnyContainer = setOf("vector")

    @Test
    fun `every constructor id the tables carry is on a class of the generated tree`() {
        val declared = HashSet<Int>()
        for (cls in serializableClasses()) {
            declared.add(cls.getDeclaredField("constructor").also { it.isAccessible = true }.getInt(null))
        }
        val missing = (TlCtorIds.allNames - notInAnyContainer).filter { name ->
            TlCtorIds.idsOf(name).orEmpty().none { it in declared }
        }
        assertTrue(missing.isEmpty(), "the stub tree is stale, re-run `pnpm run generate-tl-stubs`: ${missing.take(10)}")
    }

    @Test
    fun `every name in the constructor table constructs and reports itself back`() {
        val failures = (TlCtorIds.allNames - notInAnyContainer).mapNotNull { name ->
            val built = runCatching { TlJson.fromJson(JSONObject("""{"_":"$name"}""")) }
            val error = built.exceptionOrNull()
            when {
                error != null -> "$name: ${error.message}"
                else -> TlNames.classNameToTlName(built.getOrThrow().javaClass)
                    .takeIf { it != name }
                    ?.let { "$name: resolved to a class that calls itself '$it'" }
            }
        }
        assertTrue(failures.isEmpty(), "${failures.size} of ${TlCtorIds.allNames.size} names failed: ${failures.take(5)}")
    }

    @Test
    fun `every flag-gated field the table names is a real field on its class`() {
        val broken = ArrayList<String>()
        for (cls in serializableClasses()) {
            val fields = TlJson.publicFields(cls)
            for (word in TlFlags.wordsOf(cls)) {
                val name = TlFlags.wordName(word) ?: continue
                if (name !in fields) broken.add("${cls.name}.$name")
            }
        }
        assertTrue(broken.isEmpty(), "flag words with no java field behind them: ${broken.take(5)}")
    }

    @Test
    fun `the generated tree carries the layer the tables were generated from`() {
        assertTrue(TLRPC.LAYER > 200, "TLRPC.LAYER should come from stock, got ${TLRPC.LAYER}")
    }

    @Test
    fun `the PluginManager double still matches the real source it stands in for`() {
        val source = bridgeSource("PluginManager.kt").readText()
        assertTrue(
            Regex("""fun plugins\(\)\s*:\s*List<Plugin>""").containsMatchIn(source),
            "PluginManager.plugins() changed shape; update src/bridgeTest/kotlin/StubPluginManager.kt",
        )
    }

    /**
     * `bridgeExcluded` is read out of build.gradle rather than restated: a second copy drifts, and
     * the drift is invisible because both halves of this check are written against the copy. Every
     * file that is *not* excluded has to load, with no whitelist standing in front of the failure -
     * a whitelist is how a file added to `bridgeExcluded` to unblock a build takes its whole surface
     * out of the harness without failing anything.
     */
    @Test
    fun `every bridge source is either compiled here or listed as deliberately excluded`() {
        val excluded = bridgeExcludedInBuildScript()
        val root = bridgeSourceDir()
        val present = root.walkTopDown().filter { it.name.endsWith(".kt") }
            .associate { it.name to it.relativeTo(root).parentFile?.path.orEmpty() }
        val stale = excluded - present.keys
        assertTrue(stale.isEmpty(), "build.gradle excludes files the bridge no longer has: $stale")

        val notLoaded = (present.keys - excluded).filter { name ->
            val pkg = present.getValue(name).replace(File.separatorChar, '.')
            val cls = "desu.inugram.helpers.plugins${if (pkg.isEmpty()) "" else ".$pkg"}.${name.removeSuffix(".kt")}"
            runCatching { Class.forName(cls) }.isFailure
        }
        assertTrue(
            notLoaded.isEmpty(),
            "in the harness source set but no class of that name loaded, so nothing in them is " +
                "under test: $notLoaded. A file whose class is named differently needs renaming, " +
                "not a hole here",
        )
    }

    private fun bridgeExcludedInBuildScript(): Set<String> {
        val script = File(forkRoot(), "src/core/build.gradle").readText()
        val list = Regex("""def bridgeExcluded\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(script)
            ?.groupValues
            ?.get(1)
            ?: fail("build.gradle no longer declares bridgeExcluded as a list literal")
        // the list carries `//` comments, and an apostrophe inside one reads as a quoted name
        val entries = list.lines().joinToString("\n") { it.substringBefore("//") }
        val names = Regex("""'([^']+\.kt)'""").findAll(entries).map { it.groupValues[1] }.toSet()
        assertTrue(names.isNotEmpty(), "read no names out of bridgeExcluded")
        return names
    }

    /**
     * `PluginXposed` is in `bridgeExcluded` (it reaches lsplant through JNI), so nothing loads it
     * here and every rust test drives the op numbering off rust's own constants. A renumbered op is
     * therefore green on both sides and wrong only on a device, where it lands as one hooking op
     * doing another's work. The `keep in sync` comment on each half is what this makes true.
     */
    @Test
    fun `the xposed ops the bridge sends are the ones rust reads`() {
        val bridge = Regex("""const val (OP_\w+) = (\d+)""")
            .findAll(bridgeSource("PluginXposed.kt").readText())
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        val native = Regex("""const (OP_\w+): i32 = (\d+);""")
            .findAll(File(forkRoot(), "src/rust/inu_native/src/platform/xposed.rs").readText())
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertTrue(bridge.isNotEmpty(), "read no ops out of PluginXposed.kt")
        assertTrue(native.isNotEmpty(), "read no ops out of xposed.rs")
        assertEquals(native, bridge, "PluginXposed and xposed.rs disagree about the xposed op numbering")
    }
}
