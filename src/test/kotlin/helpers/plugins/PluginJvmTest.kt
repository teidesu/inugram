package desu.inugram.helpers.plugins

import org.json.JSONArray
import android.os.Bundle
import android.util.Base64
import android.util.LruCache
import android.util.SparseArray
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.platform.PluginJvm
import desu.inugram.jvmfixture.JvmFixture
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

class PluginJvmTest {
    private val fixtureClass = JvmFixture::class.java.name
    private val scoped = "unsafe.jvm"
    @Before
    fun setUp() {
        resetBridge()
        PluginJvm.dexDir(BLANK_ID).parentFile!!.deleteRecursively()
    }

    private fun engineFor(vararg grants: String, name: String = "reflective"): Plugin = startEngine(name, *grants)

    private fun engineWith(fixture: JvmFixture = JvmFixture(), vararg grants: String = arrayOf(scoped)): Plugin {
        val plugin = engineFor(*grants)
        JvmFixture.shared = fixture
        plugin.js("globalThis.F = inu.jvm.cls('$fixtureClass'); globalThis.o = F.getStaticField('shared')")
        return plugin
    }

    private fun Plugin.outcome(expr: String): String = js(
        """
        (() => {
          try { return 'V' + String($expr) }
          catch (e) {
            if (!(e instanceof inu.PluginError)) return 'X' + e.name + ': ' + e.message
            return 'P' + e.code + '|' + (e.grant ?? '')
          }
        })()
        """,
    )

    private fun Plugin.assertRefused(code: String, expr: String) {
        val outcome = outcome(expr)
        assertTrue(outcome.startsWith("P$code|"), "expected a '$code' PluginError from `$expr`, got $outcome")
    }

    @Test
    fun the_api_is_installed_only_for_a_plugin_that_holds_the_grant() {
        val without = startPlugin("plain", "openUrl")
        assertNull(without.js.listener?.jvm)
        assertFalse(without.js.jvmInstalled)

        val with = startPlugin("reflective", scoped)
        assertTrue(with.js.jvmInstalled)
    }

    @Test
    fun the_current_screen_is_a_reference_or_null_when_there_is_no_ui() {
        val plugin = startPlugin("reflective", scoped)
        testAppScreen.fragment = JvmFixture()
        testAppScreen.activity = JvmFixture()
        assertEquals('O', readHandleKind(plugin.jvm(PluginJvm.OP_CURRENT_FRAGMENT)))
        assertEquals('O', readHandleKind(plugin.jvm(PluginJvm.OP_CURRENT_ACTIVITY)))

        testAppScreen.fragment = null
        testAppScreen.activity = null

        assertEquals(PluginWire.Value.Null, PluginWire.decode(plugin.jvm(PluginJvm.OP_CURRENT_FRAGMENT)))
        assertEquals(PluginWire.Value.Null, PluginWire.decode(plugin.jvm(PluginJvm.OP_CURRENT_ACTIVITY)))
    }

    @Test
    fun bundle_method_uses_runtime_types() {
        val plugin = startPlugin("reflective", "unsafe.jvm")
        fun method(value: Any): String = decodeString(plugin.jvm(PluginJvm.OP_BUNDLE_METHOD, plugin.mint(value)))

        assertEquals("putBundle", method(Bundle()))
        assertEquals("putString", method("text"))
        assertEquals("putIntArray", method(intArrayOf(1)))
        assertEquals("putParcelableArray", method(arrayOf(Bundle())))
        assertEquals("putStringArrayList", method(arrayListOf("one")))
        assertEquals("putSerializable", method(arrayListOf<String>()))
        assertEquals("putSparseParcelableArray", method(SparseArray<Bundle>().apply { put(1, Bundle()) }))
        assertEquals(PluginWire.Value.Null, PluginWire.decode(plugin.jvm(PluginJvm.OP_BUNDLE_METHOD, plugin.mint(Any()))))
    }

    @Test
    fun a_bundle_is_built_through_the_member_ops() {
        val plugin = engineFor("unsafe.jvm")
        JvmFixture.shared = JvmFixture()
        assertEquals(
            "7,text,true,[1,2,3],4,1.5",
            plugin.js(
                """
                const b = inu.android.bundle({ n: 7, s: 'text', f: true, y: new Uint8Array([1, 2, 3]), peer: 4n, ratio: 1.5 });
                [b.call('getInt', 'n'), b.call('getString', 's'), b.call('getBoolean', 'f'), JSON.stringify(Array.from(b.call('getByteArray', 'y'))), b.call('getLong', 'peer'), b.call('getDouble', 'ratio')].join(',')
                """,
            ),
        )
        plugin.assertRefused("invalid-argument", "inu.android.bundle({ value: null })")
        plugin.assertRefused("invalid-argument", "inu.android.bundle({ value: [] })")
    }

    @Test
    fun the_bundled_jvm_oracle_passes() {
        val (plugin, lines) = startOracle("jvm-test.js")
        plugin.js("new (inu.jvm.cls('java.lang.Thread'))(onClick).call('start')")
        val deadline = System.nanoTime() + 5_000_000_000
        while (plugin.js("clicks") != "1" && System.nanoTime() < deadline) {
            if (drain() == 0) Thread.sleep(10)
        }
        plugin.js("__jvmDone()")
        assertOracleExact(lines, "jvm test done", 32)
    }

    @Test
    fun the_engines_own_package_is_refused_whatever_the_grant_says() {
        val plugin = startPlugin("reflective", "unsafe.jvm")
        assertEquals('C', readHandleKind(plugin.jvm(PluginJvm.OP_CLASS, name = "java.util.ArrayList")))

        for (name in listOf(PluginJvm::class.java.name, QuickJs::class.java.name, "$PLUGIN_PACKAGE.io.PluginLocalStorage")) {
            assertPluginError("forbidden", plugin.jvm(PluginJvm.OP_CLASS, name = name))
        }
    }

    /** direct calls convert in `jvm/native.rs`, routines in [PluginJvm.convertArguments] */
    @Test
    fun a_routine_and_a_direct_call_pick_the_same_overload_for_every_value() {
        val plugin = engineWith()
        val methods = listOf("width", "boxed", "echo", "sized", "letter", "boxedLetter")
        for (value in listOf("7", "7.0", "1.5", "2 ** 40", "9007199254740993n", "'x'", "true", "null", "new Uint8Array([1, 2])")) {
            val outcome = JSONArray(plugin.js("""
                (() => {
                  const describe = (run) => { try { return 'V' + String(run()) } catch (e) { return 'P' + e.code } }
                  const methods = ${JSONArray(methods)}
                  const direct = methods.map((m) => describe(() => o.call(m, $value)))
                  const routed = methods.map((m) => describe(() => {
                    o.setField('payload', 'unset')
                    o.call('runNow', inu.jvm.routine({ v: 1, source: '', captures: ['o', 'm', 'value'], slots: 0, tries: [], code: [['capture', 0], ['capture', 1], ['capture', 2], ['call', 0, 1, [2]], ['set', 0, ['payload'], 3]] }, [o, m, $value]))
                    return o.getField('payload')
                  }))
                  return JSON.stringify([direct, routed])
                })()
            """))
            for (index in methods.indices) {
                val direct = outcome.getJSONArray(0).getString(index).let { if (it.startsWith("P")) "refused" else it }
                val routed = outcome.getJSONArray(1).getString(index).let { if (it == "Vunset" || it.startsWith("P")) "refused" else it }
                assertEquals(direct, routed, "${methods[index]}($value)")
            }
        }
    }

    @Test
    fun a_handle_does_not_outlive_the_engine_that_minted_it() {
        val plugin = engineWith()
        assertEquals("V3", plugin.outcome("o.getField('count')"))

        PluginJvm.detach(plugin.session!!)
        plugin.assertRefused("handle-expired", "o.getField('count')")
        plugin.assertRefused("handle-expired", "F.getStaticField('tag')")
    }

    @Test
    fun an_id_is_only_ever_the_plugins_own() {
        val mine = startPlugin("mine", scoped)
        val theirs = startPlugin("theirs", scoped)
        val handle = mine.mint(JvmFixture())

        assertEquals(PluginWire.Value.Null, PluginWire.decode(mine.jvm(PluginJvm.OP_BUNDLE_METHOD, handle)))
        assertPluginError("handle-expired", theirs.jvm(PluginJvm.OP_BUNDLE_METHOD, handle))
    }

    @Test
    fun a_released_handle_reads_as_expired() {
        val plugin = startPlugin("reflective", scoped)
        val handle = plugin.mint(JvmFixture())
        plugin.js.jvmRelease(handle)
        assertPluginError("handle-expired", plugin.jvm(PluginJvm.OP_BUNDLE_METHOD, handle))
    }

    @Test
    fun values_cross_as_the_types_the_contract_names() {
        val plugin = engineWith()
        assertEquals(
            "3:number|inugram:string|true:boolean|null|9007199254740993:bigint|1,2,3",
            plugin.js(
                """
                const count = o.getField('count'), label = o.getField('label'), flag = o.getField('flag');
                const big = o.getField('big');
                [count + ':' + typeof count, label + ':' + typeof label, flag + ':' + typeof flag, String(o.getField('nothing')), big + ':' + typeof big, Array.from(o.getField('digest')).join(',')].join('|')
                """,
            ),
        )
    }

    @Test
    fun a_value_past_the_bound_is_refused_rather_than_copied() {
        val fixture = JvmFixture()
        fixture.label = "x".repeat(PluginJvm.VALUE_LIMIT_BYTES + 1)
        val plugin = engineWith(fixture)
        plugin.assertRefused("quota-exceeded", "o.getField('label')")
    }

    @Test
    fun an_argument_is_converted_by_the_parameter_it_lands_in() {
        val plugin = engineWith()
        assertEquals("Vecho:hi", plugin.outcome("o.call('echo', 'hi')"))
        assertEquals("V2", plugin.outcome("o.call('sized', new Uint8Array([1, 2]))"))
        assertEquals("Vint", plugin.outcome("o.call('width', 5)"))
        assertEquals("Vdouble", plugin.outcome("o.call('width', 1.5)"))
        assertEquals("Vjava.lang.Integer", plugin.outcome("o.call('boxed', 5)"))
        assertEquals("Vjava.lang.Long", plugin.outcome("o.call('boxed', 2 ** 40)"))
        assertEquals("Vjava.lang.Integer", plugin.outcome("o.call('boxed', 5n)"))
        assertEquals("Vjava.lang.Long", plugin.outcome("o.call('boxed', 2n ** 40n)"))
        assertEquals("Vjava.lang.Double", plugin.outcome("o.call('boxed', 0.5)"))
        assertEquals("Vjava.lang.String", plugin.outcome("o.call('boxed', 'text')"))
        assertEquals("Vjava.lang.Boolean", plugin.outcome("o.call('boxed', true)"))
        assertEquals("V$fixtureClass", plugin.outcome("o.call('boxed', o)"))
    }

    @Test
    fun an_argument_that_does_not_fit_is_refused_rather_than_truncated() {
        val fixture = JvmFixture()
        val plugin = engineWith(fixture)
        plugin.assertRefused("invalid-argument", "o.setField('count', 2 ** 40)")
        assertEquals(3, fixture.count)
        plugin.assertRefused("not-found", "o.call('width', 'text')")
    }

    @Test
    fun a_one_character_string_prefers_the_overload_that_takes_it_as_text() {
        val plugin = engineWith()
        assertEquals("Vstring", plugin.outcome("o.call('letter', 'f')"))
        assertEquals("Vobject", plugin.outcome("o.call('boxedLetter', 'f')"))
        assertEquals("Vchar", plugin.outcome("o.call('letter(C)Ljava/lang/String;', 'f')"))
        assertEquals("Vcharacter", plugin.outcome("o.call('boxedLetter', null)"))
    }

    @Test
    fun a_covariant_override_is_one_method_rather_than_an_overload_of_its_bridge() {
        val plugin = engineWith()
        plugin.js("globalThis.child = new (inu.jvm.cls('desu.inugram.jvmfixture.JvmSuperChild'))()")
        assertEquals("Vtrue", plugin.outcome("child.call('itself') !== null"))
        assertEquals("Vchild", plugin.outcome("child.call('describe')"))
    }

    @Test
    fun a_descriptor_pins_the_overload_the_narrowest_rule_would_not_have_picked() {
        val plugin = engineWith()
        assertEquals("Vlong", plugin.outcome("o.call('width(J)Ljava/lang/String;', 5)"))
        plugin.assertRefused("invalid-argument", "o.call('ambiguous', 'x')")
        assertEquals("VcharSequence", plugin.outcome("o.call('ambiguous(Ljava/lang/CharSequence;)Ljava/lang/String;', 'x')"))
        plugin.assertRefused("invalid-argument", "o.call('width(J)Ljava/lang/String;', 'text')")
        plugin.assertRefused("not-found", "o.call('width(Z)Ljava/lang/String;', true)")
    }

    @Test
    fun a_private_member_is_reachable_and_a_final_one_is_not_assignable() {
        val plugin = engineWith()
        assertEquals("Vprivate", plugin.outcome("o.getField('secret')"))
        plugin.assertRefused("forbidden", "o.setField('sealed', 'nope')")
    }

    @Test
    fun the_static_forms_reach_the_class_and_the_instance_forms_reach_the_object() {
        val plugin = engineWith()
        assertEquals("Vstatic", plugin.outcome("F.getStaticField('tag')"))
        plugin.js("F.setStaticField('tag', 'assigned')")
        assertEquals("assigned", JvmFixture.tag)
        assertEquals("V7", plugin.outcome("F.callStatic('sum', 3, 4)"))
        assertEquals("Vinugram", plugin.outcome("F.callStatic('make').getField('label')"))
        assertEquals("V3", plugin.outcome("new F().getField('count')"))
        plugin.assertRefused("not-found", "F.call('echo', 'hi')")
    }

    @Test
    fun a_pinned_member_is_the_same_call_one_hop_later() {
        val fixture = JvmFixture()
        val plugin = engineWith(fixture)
        assertEquals("Vecho:hi", plugin.outcome("F.getDeclaredMethod('echo').invoke(o, 'hi')"))
        assertEquals("V3", plugin.outcome("F.getDeclaredField('count').get(o)"))
        plugin.js("F.getDeclaredField('count').set(o, 9)")
        assertEquals(9, fixture.count)
        assertEquals("V9", plugin.outcome("o.getField('count')"))
        assertEquals("V3", plugin.outcome("F.getDeclaredConstructor('()V').newInstance().getField('count')"))

        plugin.assertRefused("invalid-argument", "F.getDeclaredMethod('width')")
        assertEquals("Vfunction", plugin.outcome("typeof F.getDeclaredMethod('width(J)Ljava/lang/String;').invoke"))
        plugin.assertRefused("invalid-argument", "F.getDeclaredMethod('echo').invoke(F, 'hi')")
        plugin.assertRefused("invalid-argument", "F.getDeclaredMethod('echo').invoke(o, 5)")
    }

    @Test
    fun a_declared_constructor_picks_the_overload_new_cannot_name() {
        val plugin = engineWith()
        assertEquals("Vint", plugin.outcome("new F(5).getField('madeBy')"))
        assertEquals("Vlong", plugin.outcome("F.getDeclaredConstructor('(J)V').newInstance(5).getField('madeBy')"))
        assertEquals("V5", plugin.outcome("F.getDeclaredConstructor('(J)V').newInstance(5).getField('big')"))
        assertEquals("Vundefined", plugin.outcome("typeof F.getDeclaredConstructor('()V').invoke"))
        assertEquals("Vundefined", plugin.outcome("typeof F.getDeclaredMethod('echo').newInstance"))
        plugin.assertRefused("invalid-argument", "F.getDeclaredConstructor('(J)V').newInstance('text')")
        plugin.assertRefused("not-found", "F.getDeclaredConstructor('(Z)V')")
    }

    @Test
    fun a_member_crossing_as_a_value_is_a_member_handle() {
        val plugin = engineWith()
        assertEquals("Vfunction", plugin.outcome("typeof o.call('ownMethod').invoke"))
        assertEquals("Vecho:hi", plugin.outcome("o.call('ownMethod').invoke(o, 'hi')"))
        assertEquals("Vfunction", plugin.outcome("typeof o.call('ownConstructor').newInstance"))
        assertEquals("Vint", plugin.outcome("o.call('ownConstructor').newInstance(5).getField('madeBy')"))
    }

    @Test
    fun a_callback_dispatches_synchronously_to_its_engine() {
        val plugin = startPlugin("reflective", scoped)
        val runnable = jvmHandleId(plugin.jvm(PluginJvm.OP_RUNNABLE, name = "", args = arrayOf(PluginWire.encodeInt(7))))
        val task = PluginJvm.bridgeFor(plugin.js)!!.decode("G$runnable") as Runnable
        assertEquals("ran", JvmFixture().runNow(task))
        assertEquals(listOf(7), plugin.js.jvmCallbacks)
    }

    @Test
    fun a_callback_never_fires_into_a_successor_engine() {
        val plugin = startPlugin("reflective", scoped)
        val runnable = jvmHandleId(plugin.jvm(PluginJvm.OP_RUNNABLE, name = "", args = arrayOf(PluginWire.encodeInt(7))))
        val task = PluginJvm.bridgeFor(plugin.js)!!.decode("G$runnable") as Runnable
        val stopped = plugin.js
        plugin.session = PluginSession(plugin, RecordingQuickJs())
        task.run()
        assertEquals(emptyList(), stopped.jvmCallbacks)
        assertEquals(emptyList(), plugin.js.jvmCallbacks)
    }

    @Test
    fun the_runnable_itself_cannot_be_reached_into() {
        val plugin = engineFor("unsafe.jvm")
        plugin.assertRefused("forbidden", "inu.jvm.runnable(() => {}).call('run')")
    }

    @Test
    fun a_staged_dex_lands_read_only_under_the_plugins_own_directory_and_loads() {
        val plugin = engineFor("unsafe.jvm")
        assertEquals(
            PluginWire.Value.Null,
            PluginWire.decode(
                plugin.jvm(PluginJvm.OP_LOAD_DEX, name = "", args = arrayOf(PluginWire.encodeBytes(base64(testAsset("probe.dex"))))),
            ),
        )

        // ART writes its own artifacts beside the dex
        val staged = PluginJvm.dexDir(plugin.id).listFiles()!!.single { it.isFile && it.name.endsWith(".dex") }
        assertFalse(staged.canWrite())
        assertEquals("loaded from dex", plugin.js("inu.jvm.cls('$PROBE_CLASS').callStatic('greet')"))

        PluginJvm.wipe(plugin.id)
        // ART writes the vdex from a background thread, so `oat/<isa>` may reappear after a wipe
        val left = PluginJvm.dexDir(plugin.id).walkTopDown().filter { it.isFile && it.extension == "dex" }.toList()
        assertTrue(left.isEmpty(), "left behind: $left")
    }

    @Test
    fun a_dex_path_is_taken_as_given_and_only_if_it_is_absolute() {
        val plugin = engineFor("unsafe.jvm")
        assertPluginError("invalid-argument", plugin.jvm(PluginJvm.OP_LOAD_DEX, name = "patch.dex"))

        // ART refuses to load a writable dex
        val file = File(PluginJvm.dexDir(plugin.id), "own.dex").also {
            it.parentFile!!.mkdirs()
            it.writeBytes(testAsset("probe.dex"))
            it.setReadOnly()
        }
        assertEquals(PluginWire.Value.Null, PluginWire.decode(plugin.jvm(PluginJvm.OP_LOAD_DEX, name = file.absolutePath)))
        assertEquals("loaded from dex", plugin.js("inu.jvm.cls('$PROBE_CLASS').callStatic('greet')"))
    }

    private fun tableCache(): LruCache<*, *> =
        PluginJvm::class.java.getDeclaredField("tableCache").apply { isAccessible = true }
            .get(PluginJvm) as LruCache<*, *>

    /** `getDeclaredMethods()` allocates per call, so a rescan would silently be slow again */
    @Test
    fun a_class_is_scanned_once_however_its_members_are_used() {
        val plugin = engineWith()
        assertEquals("Vecho:hi", plugin.outcome("o.call('echo', 'hi')"))
        tableCache().evictAll()
        repeat(3) { assertEquals("Vecho:hi", plugin.outcome("o.call('echo', 'hi')")) }
        assertEquals(0, tableCache().size(), "rust plans a resolved member, so kotlin is not asked again")

        assertEquals("Vint", plugin.outcome("o.call('width', 5)"))
        val scanned = tableCache().size()
        assertTrue(scanned >= 1)
        assertEquals("Vdouble", plugin.outcome("o.call('width', 1.5)"))
        plugin.js("o.getField('tag')")
        plugin.assertRefused("not-found", "o.getField('noSuchField')")
        plugin.assertRefused("not-found", "o.getField('noSuchField')")
        assertEquals(scanned, tableCache().size(), "other members, overloads and misses of a scanned class rescan nothing")
    }

    /** a constant is inherited from an interface; a static method declared on one is not */
    @Test
    fun the_table_reaches_interface_members_without_getfields() {
        val plugin = engineWith()
        assertEquals("Vstamped", plugin.outcome("o.getField('STAMP')"))
        plugin.assertRefused("not-found", "o.call('notInherited')")
    }

    /** JNI static access does not run `<clinit>`, and `cls` loads a class uninitialized */
    @Test
    fun a_static_use_initializes_the_class_where_reflection_would_have() {
        val plugin = engineFor(scoped)
        assertEquals("Vclinit", plugin.outcome("inu.jvm.cls('desu.inugram.jvmfixture.JvmLazy').getStaticField('initializedBy')"))
        assertEquals("Vclinit", plugin.outcome("inu.jvm.cls('desu.inugram.jvmfixture.JvmLazy').callStatic('whoInitialized')"))
    }

    @Test
    fun a_tl_value_crosses_to_java_and_back() {
        val plugin = engineWith()
        assertEquals(
            "Vtrue",
            plugin.outcome(
                "inu.jvm.cls('org.telegram.tgnet.TLRPC\$TL_messageEntityBold')" +
                    ".isInstance(inu.jvm.fromTl({ _: 'messageEntityBold', offset: 1, length: 2 }))",
            ),
        )
        assertEquals(
            "V1",
            plugin.outcome("inu.jvm.fromTl({ _: 'messageEntityBold', offset: 1, length: 2 }).getField('offset')"),
        )
        assertEquals(
            "VmessageEntityBold",
            plugin.outcome("inu.jvm.toTl(inu.jvm.fromTl({ _: 'messageEntityBold', offset: 1, length: 2 }))._"),
        )
        assertEquals(
            "V2",
            plugin.outcome("inu.jvm.toTl(inu.jvm.fromTl({ _: 'messageEntityBold', offset: 1, length: 2 })).length"),
        )
        plugin.js("globalThis.view = inu.jvm.toTl(inu.jvm.fromTl({ _: 'messageEntityBold', offset: 1, length: 2 }))")
        plugin.js("view.offset = 9")
        assertEquals("V9", plugin.outcome("view.offset"))
        plugin.assertRefused("invalid-argument", "inu.jvm.toTl(o)")
    }

    @Test
    fun is_instance_answers_the_class_a_handle_really_has() {
        val plugin = engineWith()
        plugin.js("globalThis.list = new (inu.jvm.cls('java.util.ArrayList'))()")
        assertEquals("Vtrue", plugin.outcome("inu.jvm.cls('java.util.ArrayList').isInstance(list)"))
        assertEquals("Vtrue", plugin.outcome("inu.jvm.cls('java.util.List').isInstance(list)"))
        assertEquals("Vtrue", plugin.outcome("inu.jvm.cls('java.lang.Object').isInstance(list)"))
        assertEquals("Vfalse", plugin.outcome("inu.jvm.cls('java.util.HashMap').isInstance(list)"))
        assertEquals("Vfalse", plugin.outcome("inu.jvm.cls('java.util.ArrayList').isInstance(null)"))
        assertEquals("Vtrue", plugin.outcome("inu.jvm.cls('java.lang.Class').isInstance(F)"))
        assertEquals("Vundefined", plugin.outcome("typeof F.getDeclaredField('count').isInstance"))
        for (value in listOf("7", "'text'", "true", "1.5", "({})")) {
            assertEquals("Vfalse", plugin.outcome("inu.jvm.cls('java.lang.Object').isInstance($value)"))
        }
    }

    @Test
    fun a_class_handle_is_callable_and_stays_a_handle_through_the_prototype_chain() {
        val plugin = engineWith()
        assertEquals("Vfunction", plugin.outcome("typeof F"))
        assertEquals("V3", plugin.outcome("Reflect.construct(F, []).getField('count')"))
        assertEquals(
            """V[[],0]""",
            plugin.outcome("JSON.stringify([Object.keys(o), Object.getOwnPropertySymbols(o).length])"),
        )
        val outcome = plugin.outcome("({ ...o }).getField('count')")
        assertTrue(outcome.startsWith("XTypeError"), outcome)
        plugin.assertRefused("invalid-argument", "Object.create(Object.getPrototypeOf(o)).getField('count')")
    }

    private fun Plugin.mint(value: Any): Long = engine!!.jvmMint(value, 'O')

    private fun readHandleKind(wire: String): Char {
        assertTrue(wire.length > 2 && wire[0] == 'G', "not a jvm handle: $wire")
        return wire[1]
    }

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val PLUGIN_PACKAGE = "desu.inugram.helpers.plugins"

        const val PROBE_CLASS = "desu.inugram.probe.Probe"

        const val BLANK_ID = "00000000000000000000000000000000"
    }
}
