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
import org.junit.Before
import org.junit.Test

/**
 * `inu.jvm`'s Kotlin half. Everything the scope list decides lives here, because this is the side
 * that knows what a class is: rust checks the *name* `cls` was handed and nothing else can be
 * checked there.
 */
class PluginJvmTest {
    private val fixtureClass = JvmFixture::class.java.name
    private val scoped = "unsafe.jvm(desu.inugram.jvmfixture.*)"

    @Before
    fun setUp() {
        resetBridge()
        JvmFixture.tag = "static"
        // a staged dex is real storage keyed by install id, and `startPlugin` derives that id from
        // the plugin's name - so without this every test naming one stands on the last one's files
        PluginJvm.dexDir(BLANK_ID).parentFile!!.deleteRecursively()
    }

    @Test
    fun theApiIsInstalledOnlyForAPluginThatHoldsTheGrant() {
        val without = startPlugin("plain", "kv")
        assertNull(without.js.listener?.jvm)
        assertFalse(without.js.jvmInstalled)

        val with = startPlugin("reflective", scoped)
        assertTrue(with.js.jvmInstalled)
    }

    /**
     * they mint through the same path every other reference takes, which is what keeps the scope
     * list meaning something: a plugin scoped to one package cannot be handed a fragment from
     * another just because it asked for "whatever is on screen".
     */
    @Test
    fun theCurrentScreenIsMintedThroughTheScopeListLikeAnyOtherReference() {
        val plugin = startPlugin("reflective", scoped)
        testAppScreen.fragment = JvmFixture()
        testAppScreen.activity = JvmFixture()

        assertEquals('O', kindOf(plugin.jvm(PluginJvm.OP_CURRENT_FRAGMENT)))
        assertEquals('O', kindOf(plugin.jvm(PluginJvm.OP_CURRENT_ACTIVITY)))

        testAppScreen.fragment = ArrayList<String>()
        assertGrantRefusal("unsafe.jvm(java.util.ArrayList)", plugin.jvm(PluginJvm.OP_CURRENT_FRAGMENT))
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

    @Test
    fun aClassInsideTheScopeListResolvesAndOneOutsideIsRefusedBeforeItIsLookedUp() {
        val plugin = startPlugin("reflective", scoped)
        assertEquals('C', kindOf(plugin.jvm(PluginJvm.OP_CLASS, name = fixtureClass)))

        // exists, out of scope
        assertGrantRefusal("unsafe.jvm(java.util.ArrayList)", plugin.jvm(PluginJvm.OP_CLASS, name = "java.util.ArrayList"))
        // does not exist, out of scope: the same answer, which is what "before it is looked up" means
        assertGrantRefusal(
            "unsafe.jvm(desu.inugram.jvmfixtures.Fake)",
            plugin.jvm(PluginJvm.OP_CLASS, name = "desu.inugram.jvmfixtures.Fake"),
        )
    }

    @Test
    fun aNamespaceScopeIsNotAPrefixMatch() {
        val plugin = startPlugin("reflective", "unsafe.jvm(desu.inugram.jvmfixture)")
        // the scope names one class, so the namespace under it is not granted
        assertGrantRefusal("unsafe.jvm($fixtureClass)", plugin.jvm(PluginJvm.OP_CLASS, name = fixtureClass))
    }

    @Test
    fun everyReferenceHandedBackIsCheckedAgainstTheScopeList() {
        val plugin = startPlugin("reflective", scoped)
        val fixture = JvmFixture()
        fixture.payload = ArrayList<String>()
        val handle = plugin.mint(fixture)

        assertEquals("inugram", stringOf(plugin.jvm(PluginJvm.OP_GET, handle, "label")))
        assertGrantRefusal(
            "unsafe.jvm(java.util.ArrayList)",
            plugin.jvm(PluginJvm.OP_GET, handle, "payload"),
        )
    }

    @Test
    fun aClassHandedBackIsCheckedAsTheClassItNames() {
        val plugin = startPlugin("reflective", scoped)
        // an in-scope member handing back a `Class`: checked as the class it *names*, or the check
        // would be asking about `java.lang.Class`, which is nobody's scope list
        assertGrantRefusal(
            "unsafe.jvm(java.util.ArrayList)",
            plugin.jvm(PluginJvm.OP_CALL, plugin.cls(fixtureClass), "classOfSomethingElse"),
        )
    }

    @Test
    fun everyMemberIsCheckedAgainstTheClassThatDeclaresIt() {
        val plugin = startPlugin("reflective", scoped)
        val handle = plugin.mint(JvmFixture())
        // declared on java.lang.Object, which this plugin's scope list does not name
        assertGrantRefusal("unsafe.jvm(java.lang.Object)", plugin.jvm(PluginJvm.OP_CALL, handle, "hashCode"))
    }

    @Test
    fun theEnginesOwnPackageIsRefusedWhateverTheGrantSays() {
        // unscoped: satisfies every scope check by the standing rule, which is exactly why this
        // needs a check of its own
        val plugin = startPlugin("reflective", "unsafe.jvm")
        assertEquals('C', kindOf(plugin.jvm(PluginJvm.OP_CLASS, name = "java.util.ArrayList")))

        for (name in listOf(PluginJvm::class.java.name, QuickJs::class.java.name, "$PLUGIN_PACKAGE.PluginKv")) {
            assertPluginError("forbidden", plugin.jvm(PluginJvm.OP_CLASS, name = name))
        }
    }

    @Test
    fun aHandleDoesNotOutliveTheEngineThatMintedIt() {
        val plugin = startPlugin("reflective", scoped)
        val listener = plugin.js.listener!!
        val handle = plugin.mint(JvmFixture())
        assertEquals(3L, intOf(plugin.jvm(PluginJvm.OP_GET, handle, "count")))

        // detach closes the session rather than unhooking it: the bridge is fixed for the life of
        // the engine, and `PluginManager` closes the engine on the same runnable
        PluginJvm.detach(plugin.js)
        assertPluginError("handle-expired", listener.jvm(PluginJvm.OP_GET, handle, "count", emptyArray()))
    }

    @Test
    fun anIdIsOnlyEverThePluginsOwn() {
        val mine = startPlugin("mine", scoped)
        val theirs = startPlugin("theirs", scoped)
        val handle = mine.mint(JvmFixture())

        assertEquals(3L, intOf(mine.jvm(PluginJvm.OP_GET, handle, "count")))
        assertPluginError("handle-expired", theirs.jvm(PluginJvm.OP_GET, handle, "count"))
    }

    @Test
    fun aReleasedHandleReadsAsExpired() {
        val plugin = startPlugin("reflective", scoped)
        val handle = plugin.mint(JvmFixture())
        plugin.jvm(PluginJvm.OP_RELEASE, handle)
        assertPluginError("handle-expired", plugin.jvm(PluginJvm.OP_GET, handle, "count"))
    }

    @Test
    fun valuesCrossAsTheTypesTheContractNames() {
        val plugin = startPlugin("reflective", scoped)
        val handle = plugin.mint(JvmFixture())

        assertEquals(3L, intOf(plugin.jvm(PluginJvm.OP_GET, handle, "count")))
        assertEquals("inugram", stringOf(plugin.jvm(PluginJvm.OP_GET, handle, "label")))
        assertEquals(PluginWire.Value.Bool(true), PluginWire.decode(plugin.jvm(PluginJvm.OP_GET, handle, "flag")))
        assertEquals(PluginWire.Value.Null, PluginWire.decode(plugin.jvm(PluginJvm.OP_GET, handle, "nothing")))
        // a long past 2^53 is not a number js can hold, so it stays exact on the wire and rust is
        // what turns it into a bigint
        assertEquals(9007199254740993L, intOf(plugin.jvm(PluginJvm.OP_GET, handle, "big")))
        assertEquals(
            listOf<Byte>(1, 2, 3),
            Base64.decode(bytesOf(plugin.jvm(PluginJvm.OP_GET, handle, "digest")), Base64.NO_WRAP).toList(),
        )
    }

    @Test
    fun aValuePastTheBoundIsRefusedRatherThanCopied() {
        val plugin = startPlugin("reflective", scoped)
        val fixture = JvmFixture()
        fixture.label = "x".repeat(PluginJvm.VALUE_LIMIT_BYTES + 1)
        val handle = plugin.mint(fixture)
        assertPluginError("quota-exceeded", plugin.jvm(PluginJvm.OP_GET, handle, "label"))
    }

    @Test
    fun anArgumentIsConvertedByTheParameterItLandsIn() {
        val plugin = startPlugin("reflective", scoped)
        val handle = plugin.mint(JvmFixture())

        assertEquals("echo:hi", stringOf(plugin.jvm(PluginJvm.OP_CALL, handle, "echo", PluginWire.encodeString("hi"))))
        assertEquals(2L, intOf(plugin.jvm(PluginJvm.OP_CALL, handle, "sized", PluginWire.encodeBytes(base64("ab")))))
        // a js number is an integer or a double and nothing narrower, so the parameter decides
        assertEquals("int", stringOf(plugin.jvm(PluginJvm.OP_CALL, handle, "width", PluginWire.encodeInt(5))))
        assertEquals("double", stringOf(plugin.jvm(PluginJvm.OP_CALL, handle, "width", PluginWire.encodeDouble(1.5))))
        // an Object-shaped parameter takes the box a java literal would have been
        assertEquals(
            "java.lang.Integer",
            stringOf(plugin.jvm(PluginJvm.OP_CALL, handle, "boxed", PluginWire.encodeInt(5))),
        )
        assertEquals(
            "java.lang.Long",
            stringOf(plugin.jvm(PluginJvm.OP_CALL, handle, "boxed", PluginWire.encodeInt(1L shl 40))),
        )
    }

    @Test
    fun anArgumentThatDoesNotFitIsRefusedRatherThanTruncated() {
        val plugin = startPlugin("reflective", scoped)
        val handle = plugin.mint(JvmFixture())
        // `count` is an int and this is not one
        assertPluginError(
            "invalid-argument",
            plugin.jvm(PluginJvm.OP_SET, handle, "count", PluginWire.encodeInt(1L shl 40)),
        )
        assertEquals(3, JvmFixture().count)
    }

    @Test
    fun anAmbiguousOverloadIsRefusedRatherThanPicked() {
        val plugin = startPlugin("reflective", scoped)
        val handle = plugin.mint(JvmFixture())
        assertPluginError(
            "invalid-argument",
            plugin.jvm(PluginJvm.OP_CALL, handle, "ambiguous", PluginWire.encodeString("x")),
        )
    }

    @Test
    fun aDescriptorPinsTheOverloadTheNarrowestRuleWouldNotHavePicked() {
        val plugin = startPlugin("reflective", scoped)
        val handle = plugin.mint(JvmFixture())
        assertEquals(
            "long",
            stringOf(plugin.jvm(PluginJvm.OP_CALL, handle, "width(J)Ljava/lang/String;", PluginWire.encodeInt(5))),
        )
        assertEquals(
            "charSequence",
            stringOf(
                plugin.jvm(
                    PluginJvm.OP_CALL,
                    handle,
                    "ambiguous(Ljava/lang/CharSequence;)Ljava/lang/String;",
                    PluginWire.encodeString("x"),
                )
            ),
        )
    }

    @Test
    fun aPrivateMemberIsReachableAndAFinalOneIsNotAssignable() {
        val plugin = startPlugin("reflective", scoped)
        val handle = plugin.mint(JvmFixture())
        assertEquals("private", stringOf(plugin.jvm(PluginJvm.OP_GET, handle, "secret")))
        assertPluginError(
            "forbidden",
            plugin.jvm(PluginJvm.OP_SET, handle, "sealed", PluginWire.encodeString("nope")),
        )
    }

    @Test
    fun theStaticFormsReachTheClassAndTheInstanceFormsReachTheObject() {
        val plugin = startPlugin("reflective", scoped)
        val cls = plugin.cls(fixtureClass)
        assertEquals("static", stringOf(plugin.jvm(PluginJvm.OP_GET, cls, "tag")))
        plugin.jvm(PluginJvm.OP_SET, cls, "tag", PluginWire.encodeString("assigned"))
        assertEquals("assigned", JvmFixture.tag)
        assertEquals(7L, intOf(plugin.jvm(PluginJvm.OP_CALL, cls, "sum", PluginWire.encodeInt(3), PluginWire.encodeInt(4))))

        val made = plugin.jvm(PluginJvm.OP_CALL, cls, "make")
        assertEquals("inugram", stringOf(plugin.jvm(PluginJvm.OP_GET, idOf(made), "label")))

        val built = plugin.jvm(PluginJvm.OP_NEW, cls)
        assertEquals(3L, intOf(plugin.jvm(PluginJvm.OP_GET, idOf(built), "count")))
    }

    @Test
    fun aPinnedMemberIsTheSameCallOneHopLater() {
        val plugin = startPlugin("reflective", scoped)
        val cls = plugin.cls(fixtureClass)
        val handle = plugin.mint(JvmFixture())

        val echo = idOf(plugin.jvm(PluginJvm.OP_METHOD, cls, "echo"))
        assertEquals(
            "echo:hi",
            stringOf(plugin.jvm(PluginJvm.OP_INVOKE, echo, "", "G$handle", PluginWire.encodeString("hi"))),
        )

        val count = idOf(plugin.jvm(PluginJvm.OP_FIELD, cls, "count"))
        assertEquals(3L, intOf(plugin.jvm(PluginJvm.OP_MEMBER_GET, count, "", "G$handle")))
        plugin.jvm(PluginJvm.OP_MEMBER_SET, count, "", "G$handle", PluginWire.encodeInt(9))
        assertEquals(9L, intOf(plugin.jvm(PluginJvm.OP_GET, handle, "count")))

        // nothing is being called yet, so only a descriptor can say which `width` was meant
        assertPluginError("invalid-argument", plugin.jvm(PluginJvm.OP_METHOD, cls, "width"))
        assertEquals(
            'M',
            kindOf(plugin.jvm(PluginJvm.OP_METHOD, cls, "width(J)Ljava/lang/String;")),
        )
    }

    /**
     * `invokePinned` and the two field ops skip [PluginJvm]'s member check on the assumption that a
     * member handle can only exist because its declaring class was checked - which holds only if a
     * member is checked and minted as one wherever it *reaches* js, and a member reaches js as an
     * ordinary return value. Minted as a plain object it was both unusable (`ctx.method.invoke` is
     * not a function, so `inu.xposed` did not work as declared) and a member handle nothing checked.
     */
    @Test
    fun aMemberCrossingAsAValueIsAMemberHandleCheckedAsItsDeclaringClass() {
        val plugin = startPlugin("reflective", scoped)
        val handle = plugin.mint(JvmFixture())

        val method = plugin.jvm(PluginJvm.OP_CALL, handle, "ownMethod")
        assertEquals('M', kindOf(method), "a member crossing as a value was not minted as one")
        assertEquals(
            "echo:hi",
            stringOf(plugin.jvm(PluginJvm.OP_INVOKE, idOf(method), "", "G$handle", PluginWire.encodeString("hi"))),
        )

        val outOfScope = JvmFixture()
        outOfScope.payload = String::class.java.getDeclaredMethod("length")
        assertPluginError("not-granted", plugin.jvm(PluginJvm.OP_GET, plugin.mint(outOfScope), "payload"))
    }

    @Test
    fun aJavaThrowIsAPlainErrorAndNotAPluginError() {
        val plugin = startPlugin("reflective", scoped)
        val handle = plugin.mint(JvmFixture())
        val decoded = PluginWire.decode(plugin.jvm(PluginJvm.OP_CALL, handle, "boom"))
        assertTrue(decoded is PluginWire.Value.Error && decoded.message.contains("IllegalStateException: boom"), "$decoded")
    }

    @Test
    fun a_callback_dispatches_synchronously_to_its_engine() {
        val plugin = startPlugin("reflective", scoped)
        val handle = plugin.mint(JvmFixture())
        val runnable = idOf(plugin.jvm(PluginJvm.OP_RUNNABLE, name = "", args = arrayOf(PluginWire.encodeInt(7))))
        assertEquals("ran", stringOf(plugin.jvm(PluginJvm.OP_CALL, handle, "runNow", "G$runnable")))
        assertEquals(listOf(7), plugin.js.jvmCallbacks)
    }

    @Test
    fun a_callback_never_fires_into_a_successor_engine() {
        val plugin = startPlugin("reflective", scoped)
        val runnable = idOf(plugin.jvm(PluginJvm.OP_RUNNABLE, name = "", args = arrayOf(PluginWire.encodeInt(7))))
        val task = PluginJvm.bridgeFor(plugin.js)!!.decode("G$runnable") as Runnable
        val stopped = plugin.js
        plugin.engine = RecordingQuickJs()
        task.run()
        assertEquals(emptyList(), stopped.jvmCallbacks)
        assertEquals(emptyList(), plugin.js.jvmCallbacks)
    }

    @Test
    fun theRunnableItselfCannotBeReachedInto() {
        val plugin = startPlugin("reflective", "unsafe.jvm")
        val runnable = idOf(plugin.jvm(PluginJvm.OP_RUNNABLE, name = "", args = arrayOf(PluginWire.encodeInt(7))))
        assertPluginError("forbidden", plugin.jvm(PluginJvm.OP_CALL, runnable, "run"))
    }

    @Test
    fun loadDexNeedsTheWholeGrantAndNotAScopedOne() {
        val scopedPlugin = startPlugin("reflective", scoped)
        assertGrantRefusal(
            "unsafe.jvm(*)",
            scopedPlugin.jvm(PluginJvm.OP_LOAD_DEX, name = "/data/local/tmp/patch.dex"),
        )

        // a `*` scope is the same statement as no scope list at all, and this is the one op that
        // can tell the difference between that and a namespace
        val starred = startPlugin("starred", "unsafe.jvm(*)")
        assertPluginError("not-found", starred.jvm(PluginJvm.OP_LOAD_DEX, name = "/nonexistent/patch.dex"))
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
        val plugin = startPlugin("reflective", "unsafe.jvm")
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
        assertEquals(
            PluginWire.Value.Str("loaded from dex"),
            PluginWire.decode(plugin.jvm(PluginJvm.OP_CALL, plugin.cls(PROBE_CLASS), "greet")),
        )

        PluginJvm.wipe(plugin.id)
        assertFalse(PluginJvm.dexDir(plugin.id).exists())
    }

    @Test
    fun aDexPathIsTakenAsGivenAndOnlyIfItIsAbsolute() {
        val plugin = startPlugin("reflective", "unsafe.jvm")
        assertPluginError("invalid-argument", plugin.jvm(PluginJvm.OP_LOAD_DEX, name = "patch.dex"))

        // the platform refuses to map a writable file as code, whoever wrote it
        val file = File(PluginJvm.dexDir(plugin.id), "own.dex").also {
            it.parentFile!!.mkdirs()
            it.writeBytes(testAsset("probe.dex"))
            it.setReadOnly()
        }
        assertEquals(PluginWire.Value.Null, PluginWire.decode(plugin.jvm(PluginJvm.OP_LOAD_DEX, name = file.absolutePath)))
        assertEquals(
            PluginWire.Value.Str("loaded from dex"),
            PluginWire.decode(plugin.jvm(PluginJvm.OP_CALL, plugin.cls(PROBE_CLASS), "greet")),
        )
    }

    // --- member resolution cache ---

    /** the process-wide member table on [PluginJvm], by reflection: the cache is the fix */
    @Suppress("UNCHECKED_CAST")
    private fun tableCache(): MutableMap<Any, Any> =
        PluginJvm::class.java.getDeclaredField("tableCache").apply { isAccessible = true }
            .get(PluginJvm) as MutableMap<Any, Any>

    /**
     * `getDeclaredMethods()`/`getMethods()` allocate fresh `Method` objects every call, so resolving
     * one member of a deep class walked and allocated thousands - per call. It is memoized now, and
     * a rescan would not fail loudly, it would just be slow again.
     */
    @Test
    fun a_class_is_scanned_once_however_many_of_its_members_are_used() {
        val plugin = startPlugin("reflective", scoped)
        val handle = plugin.mint(JvmFixture())
        tableCache().clear()
        val before = tableCache().size
        repeat(5) { assertEquals("echo:hi", stringOf(plugin.jvm(PluginJvm.OP_CALL, handle, "echo", PluginWire.encodeString("hi")))) }
        assertEquals(before + 1, tableCache().size, "five calls must add exactly one entry")

        // a different member, and a field rather than a method: the one table already answers it
        repeat(5) { plugin.jvm(PluginJvm.OP_GET, handle, "tag") }
        assertEquals(before + 1, tableCache().size, "another member of the same class must not rescan it")
    }

    /**
     * `getMethods()`/`getFields()` were the walk's most expensive call and only interface members
     * needed them, so the table reaches interfaces itself. A constant is inherited from one; a
     * static declared on one is not.
     */
    @Test
    fun the_table_reaches_interface_members_without_getfields() {
        val plugin = startPlugin("reflective", scoped)
        val handle = plugin.mint(JvmFixture())
        assertEquals("stamped", stringOf(plugin.jvm(PluginJvm.OP_GET, handle, "STAMP")))

        val wire = plugin.jvm(PluginJvm.OP_CALL, handle, "notInherited")
        assertEquals("not-found", (PluginWire.decode(wire) as PluginWire.Value.PluginErr).code)
    }

    /** the answer is shared between plugins; the permission gate on what it picks is not */
    @Test
    fun a_cached_member_is_still_refused_to_a_plugin_without_the_grant() {
        val allowed = startPlugin("reflective", scoped)
        tableCache().clear()
        val handle = allowed.mint(JvmFixture())
        assertEquals("echo:hi", stringOf(allowed.jvm(PluginJvm.OP_CALL, handle, "echo", PluginWire.encodeString("hi"))))
        assertTrue(tableCache().isNotEmpty(), "the first plugin must have populated the cache")

        // `hashCode` resolves off java.lang.Object, which this plugin's scope list does not cover -
        // and the cache must not turn that into an answer
        assertGrantRefusal("unsafe.jvm(java.lang.Object)", allowed.jvm(PluginJvm.OP_CALL, handle, "hashCode"))
    }

    /** a name that does not exist is answered out of the table too, not rescanned per attempt */
    @Test
    fun a_missing_member_stays_a_miss() {
        val plugin = startPlugin("reflective", scoped)
        tableCache().clear()
        val handle = plugin.mint(JvmFixture())
        repeat(3) {
            val wire = plugin.jvm(PluginJvm.OP_GET, handle, "noSuchField")
            assertEquals("not-found", (PluginWire.decode(wire) as PluginWire.Value.PluginErr).code)
        }
        assertEquals(1, tableCache().size)
    }

    /** overload selection still happens per call, against the cached candidates */
    @Test
    fun caching_the_candidates_does_not_freeze_which_overload_is_picked() {
        val plugin = startPlugin("reflective", scoped)
        tableCache().clear()
        val handle = plugin.mint(JvmFixture())
        assertEquals("int", stringOf(plugin.jvm(PluginJvm.OP_CALL, handle, "width", PluginWire.encodeInt(5))))
        assertEquals("double", stringOf(plugin.jvm(PluginJvm.OP_CALL, handle, "width", PluginWire.encodeDouble(1.5))))
        assertEquals("int", stringOf(plugin.jvm(PluginJvm.OP_CALL, handle, "width", PluginWire.encodeInt(7))))
        assertEquals(1, tableCache().size, "both overloads come out of the one cached scan")
    }

    private fun Plugin.jvm(op: Int, target: Long = 0, name: String = "", vararg args: String): String =
        js.listener!!.jvm(op, target, name, arrayOf(*args))

    private fun Plugin.cls(name: String): Long = idOf(jvm(PluginJvm.OP_CLASS, name = name))

    /** the handle a plugin would hold for [value], minted the way a call that returned it would */
    private fun Plugin.mint(value: Any): Long {
        val cls = cls(JvmFixture::class.java.name)
        val made = jvm(PluginJvm.OP_CALL, cls, "make")
        val handle = idOf(made)
        // the fixture the test built, rather than the one the factory did, so a test can set fields
        val table = PluginJvm::class.java.declaredClasses.single { it.simpleName == "Session" }
        val handles = table.getDeclaredField("handles").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (handles.get(js.listener!!.jvm) as MutableMap<Long, Any>)[handle] = value
        return handle
    }

    private fun kindOf(wire: String): Char {
        assertTrue(wire.length > 2 && wire[0] == 'G', "not a jvm handle: $wire")
        return wire[1]
    }

    private fun idOf(wire: String): Long {
        assertTrue(wire.length > 2 && wire[0] == 'G', "not a jvm handle: $wire")
        return wire.substring(2).toLong()
    }

    private fun intOf(wire: String): Long = (PluginWire.decode(wire) as PluginWire.Value.IntNum).value

    private fun bytesOf(wire: String): String = (PluginWire.decode(wire) as PluginWire.Value.Bytes).base64

    private fun base64(text: String): String = base64(text.toByteArray())

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun assertGrantRefusal(grant: String, wire: String) {
        val decoded = PluginWire.decode(wire)
        assertTrue(
            decoded is PluginWire.Value.PluginErr && decoded.code == "not-granted" && decoded.grant == grant,
            "expected a not-granted naming $grant, got $decoded",
        )
    }

    private companion object {
        const val PLUGIN_PACKAGE = "desu.inugram.helpers.plugins"

        /** the one class in `src/test/assets/probe.dex` */
        const val PROBE_CLASS = "desu.inugram.probe.Probe"

        /** any well-formed install id: [PluginJvm.dexDir] validates the shape, and only its parent is wanted */
        const val BLANK_ID = "00000000000000000000000000000000"
    }
}
