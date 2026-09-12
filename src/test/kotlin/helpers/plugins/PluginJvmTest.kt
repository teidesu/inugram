package desu.inugram.helpers.plugins

import android.os.Bundle
import android.util.Base64
import android.util.SparseArray
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.platform.PluginJvm
import desu.inugram.jvmfixture.JvmFixture
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * `inu.jvm` end to end. The member ops run in rust against real `jmethodID`s off a plan kotlin
 * answers once per class and name - so the tests drive the plugin's own surface through a real
 * engine, and the few ops that still cross as text (`cls`, `loadDex`, the runnable, the screen)
 * through the listener.
 */
class PluginJvmTest {
    private val fixtureClass = JvmFixture::class.java.name
    private val scoped = "unsafe.jvm"
    private val engines = ArrayList<QuickJs>()

    @Before
    fun setUp() {
        resetBridge()
        JvmFixture.tag = "static"
        JvmFixture.shared = null
        // a staged dex is real storage keyed by install id, and `startPlugin` derives that id from
        // the plugin's name - so without this every test naming one stands on the last one's files
        PluginJvm.dexDir(BLANK_ID).parentFile!!.deleteRecursively()
    }

    @After
    fun tearDown() {
        for (engine in engines) {
            engine.stopCallbacks()
            PluginJvm.detach(engine)
            engine.close()
        }
        engines.clear()
    }

    /** a plugin on a real engine: the member ops have no listener form, so this is the only way to reach them */
    private fun engineFor(vararg grants: String, name: String = "reflective"): Plugin {
        val plugin = startPlugin(name, *grants)
        val engine = QuickJs()
        plugin.session = PluginSession(plugin, engine)
        attachBridge(plugin.session!!, object : CoreListener {
            override fun onConsole(level: Int, message: String) = Unit
            override fun onTimerSchedule(delayMs: Long) = Unit
        })
        engines.add(engine)
        return plugin
    }

    /** a plugin holding `F`, the fixture class, and `o`, [fixture], the way its own code would hold them */
    private fun engineWith(fixture: JvmFixture = JvmFixture(), vararg grants: String = arrayOf(scoped)): Plugin {
        val plugin = engineFor(*grants)
        JvmFixture.shared = fixture
        plugin.js("globalThis.F = inu.jvm.cls('$fixtureClass'); globalThis.o = F.getStaticField('shared')")
        return plugin
    }

    private fun Plugin.js(code: String): String = engine!!.evaluate(code.trimIndent()) ?: "null"

    /** what [expr] refuses with: `code|grant`, or the value it answered as text */
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
    fun theApiIsInstalledOnlyForAPluginThatHoldsTheGrant() {
        val without = startPlugin("plain", "kv")
        assertNull(without.js.listener?.jvm)
        assertFalse(without.js.jvmInstalled)

        val with = startPlugin("reflective", scoped)
        assertTrue(with.js.jvmInstalled)
    }

    /** they mint through the same path every other reference takes, engine package rule included */
    @Test
    fun theCurrentScreenIsMintedLikeAnyOtherReference() {
        val plugin = startPlugin("reflective", scoped)
        testAppScreen.fragment = JvmFixture()
        testAppScreen.activity = JvmFixture()

        assertEquals('O', kindOf(plugin.jvm(PluginJvm.OP_CURRENT_FRAGMENT)))
        assertEquals('O', kindOf(plugin.jvm(PluginJvm.OP_CURRENT_ACTIVITY)))
    }

    /** no ui at all - a process a push woke - is `null`, never an error */
    @Test
    fun theCurrentScreenIsNullWhenThereIsNoUi() {
        val plugin = startPlugin("reflective", scoped)
        testAppScreen.fragment = null
        testAppScreen.activity = null

        assertEquals(PluginWire.Value.Null, PluginWire.decode(plugin.jvm(PluginJvm.OP_CURRENT_FRAGMENT)))
        assertEquals(PluginWire.Value.Null, PluginWire.decode(plugin.jvm(PluginJvm.OP_CURRENT_ACTIVITY)))
    }

    @Test
    fun bundle_method_uses_runtime_types() {
        val plugin = startPlugin("reflective", "unsafe.jvm")
        fun method(value: Any): String = stringOf(plugin.jvm(PluginJvm.OP_BUNDLE_METHOD, plugin.mint(value)))

        assertEquals("putBundle", method(Bundle()))
        assertEquals("putString", method("text"))
        assertEquals("putIntArray", method(intArrayOf(1)))
        assertEquals("putParcelableArray", method(arrayOf(Bundle())))
        assertEquals("putStringArrayList", method(arrayListOf("one")))
        assertEquals("putSerializable", method(arrayListOf<String>()))
        assertEquals("putSparseParcelableArray", method(SparseArray<Bundle>().apply { put(1, Bundle()) }))
        assertEquals(PluginWire.Value.Null, PluginWire.decode(plugin.jvm(PluginJvm.OP_BUNDLE_METHOD, plugin.mint(Any()))))
    }

    /** the whole of `inu.android.bundle`, which builds through the same construct and call path a plugin uses */
    @Test
    fun a_bundle_is_built_through_the_member_ops() {
        val plugin = engineFor("unsafe.jvm")
        JvmFixture.shared = JvmFixture()
        assertEquals(
            "7,text,true,[1,2,3]",
            plugin.js(
                """
                const b = inu.android.bundle({ n: 7, s: 'text', f: true, y: new Uint8Array([1, 2, 3]) });
                [b.call('getInt', 'n'), b.call('getString', 's'), b.call('getBoolean', 'f'), JSON.stringify(Array.from(b.call('getByteArray', 'y')))].join(',')
                """,
            ),
        )
    }

    @Test
    fun theEnginesOwnPackageIsRefusedWhateverTheGrantSays() {
        val plugin = startPlugin("reflective", "unsafe.jvm")
        assertEquals('C', kindOf(plugin.jvm(PluginJvm.OP_CLASS, name = "java.util.ArrayList")))

        for (name in listOf(PluginJvm::class.java.name, QuickJs::class.java.name, "$PLUGIN_PACKAGE.PluginKv")) {
            assertPluginError("forbidden", plugin.jvm(PluginJvm.OP_CLASS, name = name))
        }
    }

    @Test
    fun aHandleDoesNotOutliveTheEngineThatMintedIt() {
        val plugin = engineWith()
        assertEquals("V3", plugin.outcome("o.getField('count')"))

        // detach closes the session rather than unhooking it: the bridge is fixed for the life of
        // the engine, and `PluginManager` closes the engine on the same runnable
        PluginJvm.detach(plugin.engine!!)
        plugin.assertRefused("handle-expired", "o.getField('count')")
        plugin.assertRefused("handle-expired", "F.getStaticField('tag')")
    }

    /** the table behind an id is the engine's own, so another plugin's id names nothing in it */
    @Test
    fun anIdIsOnlyEverThePluginsOwn() {
        val mine = startPlugin("mine", scoped)
        val theirs = startPlugin("theirs", scoped)
        val handle = mine.mint(JvmFixture())

        // the op only needs to *reach* the object; what it answers about a fixture is that it is
        // nothing a Bundle takes
        assertEquals(PluginWire.Value.Null, PluginWire.decode(mine.jvm(PluginJvm.OP_BUNDLE_METHOD, handle)))
        assertPluginError("handle-expired", theirs.jvm(PluginJvm.OP_BUNDLE_METHOD, handle))
    }

    @Test
    fun aReleasedHandleReadsAsExpired() {
        val plugin = startPlugin("reflective", scoped)
        val handle = plugin.mint(JvmFixture())
        plugin.js.jvmRelease(handle)
        assertPluginError("handle-expired", plugin.jvm(PluginJvm.OP_BUNDLE_METHOD, handle))
    }

    @Test
    fun valuesCrossAsTheTypesTheContractNames() {
        val plugin = engineWith()
        assertEquals(
            "3:number|inugram:string|true:boolean|null|9007199254740993:bigint|1,2,3",
            plugin.js(
                """
                const count = o.getField('count'), label = o.getField('label'), flag = o.getField('flag');
                // a long past 2^53 is not a number js can hold, so it arrives as a bigint
                const big = o.getField('big');
                [count + ':' + typeof count, label + ':' + typeof label, flag + ':' + typeof flag, String(o.getField('nothing')), big + ':' + typeof big, Array.from(o.getField('digest')).join(',')].join('|')
                """,
            ),
        )
    }

    @Test
    fun aValuePastTheBoundIsRefusedRatherThanCopied() {
        val fixture = JvmFixture()
        fixture.label = "x".repeat(PluginJvm.VALUE_LIMIT_BYTES + 1)
        val plugin = engineWith(fixture)
        plugin.assertRefused("quota-exceeded", "o.getField('label')")
        plugin.assertRefused("quota-exceeded", "o.call('echo', 'x'.repeat(${PluginJvm.VALUE_LIMIT_BYTES + 1}))")
    }

    @Test
    fun anArgumentIsConvertedByTheParameterItLandsIn() {
        val plugin = engineWith()
        assertEquals("Vecho:hi", plugin.outcome("o.call('echo', 'hi')"))
        assertEquals("V2", plugin.outcome("o.call('sized', new Uint8Array([1, 2]))"))
        // a js number is an integer or a double and nothing narrower, so the parameter decides
        assertEquals("Vint", plugin.outcome("o.call('width', 5)"))
        assertEquals("Vdouble", plugin.outcome("o.call('width', 1.5)"))
        // an Object-shaped parameter takes the box a java literal would have been
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
    fun anArgumentThatDoesNotFitIsRefusedRatherThanTruncated() {
        val fixture = JvmFixture()
        val plugin = engineWith(fixture)
        // `count` is an int and this is not one
        plugin.assertRefused("invalid-argument", "o.setField('count', 2 ** 40)")
        assertEquals(3, fixture.count)
        plugin.assertRefused("not-found", "o.call('width', 'text')")
    }

    @Test
    fun anAmbiguousOverloadIsRefusedRatherThanPicked() {
        val plugin = engineWith()
        plugin.assertRefused("invalid-argument", "o.call('ambiguous', 'x')")
    }

    @Test
    fun aDescriptorPinsTheOverloadTheNarrowestRuleWouldNotHavePicked() {
        val plugin = engineWith()
        assertEquals("Vlong", plugin.outcome("o.call('width(J)Ljava/lang/String;', 5)"))
        assertEquals("VcharSequence", plugin.outcome("o.call('ambiguous(Ljava/lang/CharSequence;)Ljava/lang/String;', 'x')"))
        // pinning says *which* one, never that the arguments fit
        plugin.assertRefused("invalid-argument", "o.call('width(J)Ljava/lang/String;', 'text')")
        plugin.assertRefused("not-found", "o.call('width(Z)Ljava/lang/String;', true)")
    }

    @Test
    fun aPrivateMemberIsReachableAndAFinalOneIsNotAssignable() {
        val plugin = engineWith()
        assertEquals("Vprivate", plugin.outcome("o.getField('secret')"))
        plugin.assertRefused("forbidden", "o.setField('sealed', 'nope')")
    }

    @Test
    fun theStaticFormsReachTheClassAndTheInstanceFormsReachTheObject() {
        val plugin = engineWith()
        assertEquals("Vstatic", plugin.outcome("F.getStaticField('tag')"))
        plugin.js("F.setStaticField('tag', 'assigned')")
        assertEquals("assigned", JvmFixture.tag)
        assertEquals("V7", plugin.outcome("F.callStatic('sum', 3, 4)"))
        assertEquals("Vinugram", plugin.outcome("F.callStatic('make').getField('label')"))
        assertEquals("V3", plugin.outcome("new F().getField('count')"))
        // a class handle is a static receiver and nothing else
        plugin.assertRefused("not-found", "F.call('echo', 'hi')")
    }

    @Test
    fun aPinnedMemberIsTheSameCallOneHopLater() {
        val fixture = JvmFixture()
        val plugin = engineWith(fixture)
        assertEquals("Vecho:hi", plugin.outcome("F.getDeclaredMethod('echo').invoke(o, 'hi')"))
        assertEquals("V3", plugin.outcome("F.getDeclaredField('count').get(o)"))
        plugin.js("F.getDeclaredField('count').set(o, 9)")
        assertEquals(9, fixture.count)
        assertEquals("V9", plugin.outcome("o.getField('count')"))
        assertEquals("V3", plugin.outcome("F.getDeclaredConstructor('()V').newInstance().getField('count')"))

        // nothing is being called yet, so only a descriptor can say which `width` was meant
        plugin.assertRefused("invalid-argument", "F.getDeclaredMethod('width')")
        assertEquals("Vfunction", plugin.outcome("typeof F.getDeclaredMethod('width(J)Ljava/lang/String;').invoke"))
        plugin.assertRefused("invalid-argument", "F.getDeclaredMethod('echo').invoke(F, 'hi')")
        plugin.assertRefused("invalid-argument", "F.getDeclaredMethod('echo').invoke(o, 5)")
    }

    /**
     * `new F(...)` matches on the arguments alone, and a js number fits both widths - so the
     * descriptor is the only way to say which of two constructors was meant.
     */
    @Test
    fun aDeclaredConstructorPicksTheOverloadNewCannotName() {
        val plugin = engineWith()
        assertEquals("Vint", plugin.outcome("new F(5).getField('madeBy')"))
        assertEquals("Vlong", plugin.outcome("F.getDeclaredConstructor('(J)V').newInstance(5).getField('madeBy')"))
        assertEquals("V5", plugin.outcome("F.getDeclaredConstructor('(J)V').newInstance(5).getField('big')"))
        assertEquals("Vundefined", plugin.outcome("typeof F.getDeclaredConstructor('()V').invoke"))
        assertEquals("Vundefined", plugin.outcome("typeof F.getDeclaredMethod('echo').newInstance"))
        plugin.assertRefused("invalid-argument", "F.getDeclaredConstructor('(J)V').newInstance('text')")
        plugin.assertRefused("not-found", "F.getDeclaredConstructor('(Z)V')")
    }

    /** a member reaches js as an ordinary return value too, and has to arrive as a member handle */
    @Test
    fun aMemberCrossingAsAValueIsAMemberHandle() {
        val plugin = engineWith()
        assertEquals("Vfunction", plugin.outcome("typeof o.call('ownMethod').invoke"))
        assertEquals("Vecho:hi", plugin.outcome("o.call('ownMethod').invoke(o, 'hi')"))
        assertEquals("Vfunction", plugin.outcome("typeof o.call('ownConstructor').newInstance"))
        assertEquals("Vint", plugin.outcome("o.call('ownConstructor').newInstance(5).getField('madeBy')"))
    }

    @Test
    fun aJavaThrowIsAPlainErrorAndNotAPluginError() {
        val plugin = engineWith()
        val outcome = plugin.outcome("o.call('boom')")
        assertTrue(outcome.startsWith("XError: ") && outcome.contains("IllegalStateException: boom"), outcome)
    }

    @Test
    fun a_callback_dispatches_synchronously_to_its_engine() {
        val plugin = startPlugin("reflective", scoped)
        val runnable = idOf(plugin.jvm(PluginJvm.OP_RUNNABLE, name = "", args = arrayOf(PluginWire.encodeInt(7))))
        val task = PluginJvm.bridgeFor(plugin.js)!!.decode("G$runnable") as Runnable
        assertEquals("ran", JvmFixture().runNow(task))
        assertEquals(listOf(7), plugin.js.jvmCallbacks)
    }

    @Test
    fun a_callback_never_fires_into_a_successor_engine() {
        val plugin = startPlugin("reflective", scoped)
        val runnable = idOf(plugin.jvm(PluginJvm.OP_RUNNABLE, name = "", args = arrayOf(PluginWire.encodeInt(7))))
        val task = PluginJvm.bridgeFor(plugin.js)!!.decode("G$runnable") as Runnable
        val stopped = plugin.js
        plugin.session = PluginSession(plugin, RecordingQuickJs())
        task.run()
        assertEquals(emptyList(), stopped.jvmCallbacks)
        assertEquals(emptyList(), plugin.js.jvmCallbacks)
    }

    @Test
    fun theRunnableItselfCannotBeReachedInto() {
        val plugin = engineFor("unsafe.jvm")
        plugin.assertRefused("forbidden", "inu.jvm.runnable(() => {}).call('run')")
    }

    @Test
    fun aDexPastTheBoundIsRefusedRatherThanStaged() {
        val plugin = startPlugin("reflective", "unsafe.jvm")
        val bytes = ByteArray(PluginJvm.DEX_LIMIT_BYTES.toInt() + 1)
        assertPluginError(
            "quota-exceeded",
            plugin.jvm(PluginJvm.OP_LOAD_DEX, name = "", args = arrayOf(PluginWire.encodeBytes(base64(bytes)))),
        )
        assertFalse(PluginJvm.dexDir(plugin.id).isDirectory)
    }

    /**
     * the real loader, so what is asserted is that the staged file is one it accepts and that the
     * class inside it is reachable - not that a recorder was handed a path
     */
    @Test
    fun aStagedDexLandsReadOnlyUnderThePluginsOwnDirectoryAndLoads() {
        val plugin = engineFor("unsafe.jvm")
        assertEquals(
            PluginWire.Value.Null,
            PluginWire.decode(
                plugin.jvm(PluginJvm.OP_LOAD_DEX, name = "", args = arrayOf(PluginWire.encodeBytes(base64(testAsset("probe.dex"))))),
            ),
        )

        // the runtime writes its own compiled artifacts beside the file it was given, so the staged
        // dex is named rather than assumed to be alone
        val staged = PluginJvm.dexDir(plugin.id).listFiles()!!.single { it.isFile && it.name.endsWith(".dex") }
        assertFalse(staged.canWrite())
        assertEquals("loaded from dex", plugin.js("inu.jvm.cls('$PROBE_CLASS').callStatic('greet')"))

        PluginJvm.wipe(plugin.id)
        assertFalse(PluginJvm.dexDir(plugin.id).exists())
    }

    @Test
    fun aDexPathIsTakenAsGivenAndOnlyIfItIsAbsolute() {
        val plugin = engineFor("unsafe.jvm")
        assertPluginError("invalid-argument", plugin.jvm(PluginJvm.OP_LOAD_DEX, name = "patch.dex"))

        // the platform refuses to map a writable file as code, whoever wrote it
        val file = File(PluginJvm.dexDir(plugin.id), "own.dex").also {
            it.parentFile!!.mkdirs()
            it.writeBytes(testAsset("probe.dex"))
            it.setReadOnly()
        }
        assertEquals(PluginWire.Value.Null, PluginWire.decode(plugin.jvm(PluginJvm.OP_LOAD_DEX, name = file.absolutePath)))
        assertEquals("loaded from dex", plugin.js("inu.jvm.cls('$PROBE_CLASS').callStatic('greet')"))
    }

    // --- member resolution cache ---

    /** the process-wide member table on [PluginJvm], by reflection: the cache is the fix */
    @Suppress("UNCHECKED_CAST")
    private fun tableCache(): MutableMap<Any, Any> =
        PluginJvm::class.java.getDeclaredField("tableCache").apply { isAccessible = true }
            .get(PluginJvm) as MutableMap<Any, Any>

    /**
     * `getDeclaredMethods()`/`getMethods()` allocate fresh `Method` objects every call, so resolving
     * one member of a deep class walked and allocated thousands - per call. A table holds what one
     * class declares, so the first lookup scans the lineage and nothing after it scans anything; a
     * rescan would not fail loudly, it would just be slow again.
     */
    @Test
    fun a_class_is_scanned_once_however_many_of_its_members_are_used() {
        val plugin = engineWith()
        tableCache().clear()
        assertEquals("Vecho:hi", plugin.outcome("o.call('echo', 'hi')"))
        val scanned = tableCache().size
        assertTrue(scanned >= 1, "the first call scans the class and what it inherits from")

        repeat(4) { assertEquals("Vecho:hi", plugin.outcome("o.call('echo', 'hi')")) }
        assertEquals(scanned, tableCache().size, "four more calls must scan nothing")

        // a different member, and a field rather than a method: the same tables already answer it
        repeat(5) { plugin.js("o.getField('tag')") }
        assertEquals(scanned, tableCache().size, "another member of the same class must not rescan it")
    }

    /**
     * `getMethods()`/`getFields()` were the walk's most expensive call and only interface members
     * needed them, so the table reaches interfaces itself. A constant is inherited from one; a
     * static declared on one is not.
     */
    @Test
    fun the_table_reaches_interface_members_without_getfields() {
        val plugin = engineWith()
        assertEquals("Vstamped", plugin.outcome("o.getField('STAMP')"))
        plugin.assertRefused("not-found", "o.call('notInherited')")
    }

    /**
     * Reflection ran `<clinit>` on the first static access; the JNI ids rust calls through never
     * do, and naming a class deliberately loads it uninitialized. A static read on an untouched
     * class answered its default instead of what the initializer set.
     */
    @Test
    fun a_static_use_initializes_the_class_where_reflection_would_have() {
        val plugin = engineFor(scoped)
        assertEquals("Vclinit", plugin.outcome("inu.jvm.cls('desu.inugram.jvmfixture.JvmLazy').getStaticField('initializedBy')"))
        assertEquals("Vclinit", plugin.outcome("inu.jvm.cls('desu.inugram.jvmfixture.JvmLazy').callStatic('whoInitialized')"))
    }

    /** a name that does not exist is answered out of the table too, not rescanned per attempt */
    @Test
    fun a_missing_member_stays_a_miss() {
        val plugin = engineWith()
        tableCache().clear()
        plugin.assertRefused("not-found", "o.getField('noSuchField')")
        val scanned = tableCache().size
        repeat(2) { plugin.assertRefused("not-found", "o.getField('noSuchField')") }
        assertEquals(scanned, tableCache().size, "a miss is settled by the same scan a hit is")
    }

    /** overload selection still happens per call, against the cached candidates */
    @Test
    fun caching_the_candidates_does_not_freeze_which_overload_is_picked() {
        val plugin = engineWith()
        tableCache().clear()
        assertEquals("Vint", plugin.outcome("o.call('width', 5)"))
        val scanned = tableCache().size
        assertEquals("Vdouble", plugin.outcome("o.call('width', 1.5)"))
        assertEquals("Vint", plugin.outcome("o.call('width', 7)"))
        assertEquals(scanned, tableCache().size, "both overloads come out of the one cached scan")
    }

    /**
     * rust keeps a plan per class and name, so after the first call a name never reaches kotlin
     * again - which is the whole point, and what makes the kotlin table's count above a count of
     * *distinct names*, not of calls
     */
    @Test
    fun a_resolved_member_is_not_asked_of_kotlin_twice() {
        val plugin = engineWith()
        assertEquals("Vecho:hi", plugin.outcome("o.call('echo', 'hi')"))
        tableCache().clear()
        repeat(3) { assertEquals("Vecho:hi", plugin.outcome("o.call('echo', 'hi')")) }
        assertTrue(tableCache().isEmpty(), "a planned call must not rescan the class on the kotlin side")
    }

    /** a class handle is a function, so that `new` works, and the reference rides on it where nothing js-side can lose it */
    @Test
    fun a_class_handle_is_callable_and_stays_a_handle_through_the_prototype_chain() {
        val plugin = engineWith()
        assertEquals("Vfunction", plugin.outcome("typeof F"))
        assertEquals("V3", plugin.outcome("Reflect.construct(F, []).getField('count')"))
        // a handle cannot be forged from what js can see of one
        assertEquals(
            """V[[],0]""",
            plugin.outcome("JSON.stringify([Object.keys(o), Object.getOwnPropertySymbols(o).length])"),
        )
        val outcome = plugin.outcome("({ ...o }).getField('count')")
        assertTrue(outcome.startsWith("XTypeError"), outcome)
        plugin.assertRefused("invalid-argument", "Object.create(Object.getPrototypeOf(o)).getField('count')")
    }

    // `engine` rather than `js`: half of these run on a real engine, which is not the recording one
    private fun Plugin.jvm(op: Int, target: Long = 0, name: String = "", vararg args: String): String =
        engine!!.listener!!.jvm(op, target, name, arrayOf(*args))

    /**
     * a handle for [value] in this plugin's own table. Straight into the table rather than through
     * `encode`, because a scalar crosses as a value and never gets one - and the ops below want a
     * handle to something whatever its wire form would be.
     */
    private fun Plugin.mint(value: Any): Long = engine!!.jvmMint(value, 'O')

    private fun kindOf(wire: String): Char {
        assertTrue(wire.length > 2 && wire[0] == 'G', "not a jvm handle: $wire")
        return wire[1]
    }

    private fun idOf(wire: String): Long {
        assertTrue(wire.length > 2 && wire[0] == 'G', "not a jvm handle: $wire")
        return wire.substring(2).toLong()
    }

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val PLUGIN_PACKAGE = "desu.inugram.helpers.plugins"

        /** the one class in `src/test/assets/probe.dex` */
        const val PROBE_CLASS = "desu.inugram.probe.Probe"

        /** any well-formed install id: [PluginJvm.dexDir] validates the shape, and only its parent is wanted */
        const val BLANK_ID = "00000000000000000000000000000000"
    }
}
