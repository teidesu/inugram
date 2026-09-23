package desu.inugram.helpers.plugins.platform

import desu.inugram.helpers.plugins.SessionResource
import desu.inugram.core.plugins.PluginRefusal
import desu.inugram.core.plugins.PluginWire.refuse
import android.os.Bundle
import android.os.IBinder
import android.os.Parcelable
import android.util.Base64
import android.util.Size
import android.util.SizeF
import android.util.SparseArray
import dalvik.system.DexClassLoader
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.io.PluginPaths
import desu.inugram.helpers.plugins.JvmListener
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.QuickJs
import java.io.File
import java.io.Serializable
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLObject

/**
 * Kotlin bindings for `inu.jvm` (Rust: `jvm.rs`).
 *
 * The grant is unscoped. Direct access to [ENGINE_PACKAGE] is rejected to avoid exposing engine
 * objects while a reflected call holds the engine lease. This is not a sandbox boundary:
 * `java.lang.reflect` can bypass the check.
 */
object PluginJvm : SessionResource {
    private object Native {
        external fun nativeCallNonvirtual(
            method: Method,
            descriptor: String,
            params: Array<Class<*>>,
            receiver: Any,
            args: Array<Any?>,
        ): Any?
    }

    // keep in sync with rust `jvm::OP_*` and `jvm.js`; member access is rust's own, through cached jni ids, and never reaches this side
    const val OP_CLASS = 0
    const val OP_RUNNABLE = 10
    const val OP_LOAD_DEX = 11
    const val OP_CURRENT_FRAGMENT = 13
    const val OP_CURRENT_ACTIVITY = 14
    const val OP_ROUTINE = 16
    const val OP_XPOSED_ROUTINE = 17
    const val OP_PREPARE_CLASS = 18
    const val OP_LOAD_CLASS = 20
    const val OP_CANCEL_CLASS = 21
    const val OP_FROM_TL = 22
    const val OP_TO_TL = 23
    const val OP_BUNDLE_METHOD = 15

    // keep in sync with rust `jvm::native::RESOLVE_*`
    const val RESOLVE_METHODS = 0
    const val RESOLVE_CONSTRUCTORS = 1
    const val RESOLVE_FIELD = 2
    const val RESOLVE_MEMBER = 3

    const val GRANT = "unsafe.jvm"

    /** keep in sync with rust `jvm::VALUE_LIMIT_BYTES` and the number `android.jvm.d.ts` states */
    const val VALUE_LIMIT_BYTES = 1024 * 1024

    /** keep in sync with rust `jvm::DEX_LIMIT_BYTES` and the number `android.jvm.d.ts` states */
    const val DEX_LIMIT_BYTES = 8L * 1024 * 1024

    /** the one hop that would put the engine's own objects in a plugin's hands, refused wherever a name crosses */
    internal const val ENGINE_PACKAGE = "desu.inugram.helpers.plugins."

    internal fun isEnginePackage(name: String): Boolean = name.startsWith(ENGINE_PACKAGE)

    private const val ROOT = "inu_plugin_dex"

    // handle kinds; keep in sync with rust `jvm::KIND_*` and `jvm.js`
    private const val KIND_CLASS = 'C'
    private const val KIND_OBJECT = 'O'
    private const val KIND_METHOD = 'M'
    private const val KIND_CONSTRUCTOR = 'K'
    private const val KIND_FIELD = 'F'

    /** handed in rather than read here, so a test can put a screen in front of the api without an `Activity` */
    interface AppScreen {
        fun currentFragment(): Any?
        fun currentActivity(): Any?
    }

    /** nothing at all for a plugin without the grant: the api is the whole app */
    fun listenerFor(session: PluginSession, screen: AppScreen): JvmListener? =
        if (session.permissions.has(GRANT)) Session(session, screen) else null

    /** [Session.checkClass] already let this be minted, which is the only way a handle exists */
    fun objectAt(engine: QuickJs, handle: Long): Any? = (engine.listener?.jvm as? Session)?.objectAt(handle)

    override fun detach(session: PluginSession) {
        (session.engine.listener?.jvm as? Session)?.close()
    }

    fun dexDir(installId: String): File = PluginPaths.scopedFile(installId, ROOT)

    /** **only on uninstall**: a class cannot be unloaded, so a merely-stopped plugin's code may still be running */
    fun wipe(installId: String) = PluginPaths.wipe(installId, ::dexDir)

    fun sweepOrphans(live: Set<String>) = PluginPaths.sweepOrphans(ROOT, live) { it }

    /**
     * `inu.xposed` takes a `JavaMethod` at every entry point and hands a hook java values, so it
     * borrows this table rather than keeping a second one - which is what makes a `JavaObject` a
     * hook is handed something `inu.jvm` can call methods on. Every member applies the same scope
     * check the op reaching it would.
     */
    internal interface ValueBridge {
        fun memberAt(target: Long): Member

        fun classAt(target: Long): Class<*>

        fun decode(wire: String): Any?

        fun encode(value: Any?): String

        /** forget a handle [encode] minted that never reached the engine, so the reference it holds goes with it */
        fun release(wire: String)

        fun wireOf(failure: Throwable): String?
    }

    internal fun bridgeFor(engine: QuickJs): ValueBridge? = engine.listener?.jvm as? ValueBridge

    /**
     * A wire the engine never took stays minted in the reference table with nothing to drop it, so
     * whoever encoded a batch releases it when the engine did not take it. A scalar minted nothing
     * and releasing one is a no-op, which is what lets a caller hand back everything it encoded.
     *
     * Callers are app code's own frames, such as a notification observer, so the bridge already
     * being gone is one more thing that may not surface there.
     */
    internal fun releaseUntaken(engine: QuickJs, wires: List<String>) {
        val bridge = bridgeFor(engine) ?: return
        for (wire in wires) runCatching { bridge.release(wire) }
    }

    private fun checkStringSize(value: String, what: String) {
        val size = value.toByteArray(Charsets.UTF_8).size
        if (size > VALUE_LIMIT_BYTES) tooBig(what, size.toLong())
    }

    private fun tooBig(what: String, size: Long): Nothing =
        throw PluginRefusal(
            PluginWire.encodePluginError(
                "quota-exceeded",
                "jvm: $what is $size bytes, over the $VALUE_LIMIT_BYTES this bridge carries",
                usage = size,
                quota = VALUE_LIMIT_BYTES.toLong(),
            )
        )

    internal open class Session(val session: PluginSession, private val screen: AppScreen) :
        JvmListener, ValueBridge {
        private val nextTicket = AtomicLong(1)
        private val loaders = ArrayList<ClassLoader>()
        private var dexCount = 0
        private val routinees = ArrayList<java.lang.ref.WeakReference<PluginJvmRoutine>>()
        private val definedClasses = ArrayList<PluginJvmClass.Definition>()
        private val pendingClasses = HashMap<Long, PluginJvmClass.Prepared>()

        @Volatile
        internal var live = true
            private set

        override fun jvm(op: Int, target: Long, name: String, args: Array<String>): String = try {
            if (!live) expired() else handle(op, target, name, args)
        } catch (e: PluginRefusal) {
            e.wire
        } catch (e: InvocationTargetException) {
            // a java exception is not part of this api's taxonomy, so it arrives as a plain Error rather than a PluginError
            PluginWire.encodeError(describe(e.cause ?: e))
        } catch (e: Throwable) {
            PluginWire.encodeError("jvm: ${describe(e)}")
        }

        private fun handle(op: Int, target: Long, name: String, args: Array<String>): String = when (op) {
            OP_CLASS -> {
                checkName(name)
                encodeValue(classFor(name))
            }
            OP_RUNNABLE -> mintRunnable(args)
            OP_PREPARE_CLASS -> {
                if (definedClasses.size + pendingClasses.size >= 128) refuse("quota-exceeded", "defineClass: at most 128 classes per engine")
                val previousLoaders = loaders.toList()
                val parent = object : ClassLoader(PluginJvm::class.java.classLoader) {
                    override fun findClass(name: String): Class<*> {
                        for (loader in previousLoaders) {
                            try { return Class.forName(name, false, loader) } catch (_: ClassNotFoundException) {}
                        }
                        throw ClassNotFoundException(name)
                    }
                }
                val prepared = try {
                    PluginJvmClass.prepare(name, decodeArgs(args), { type -> Class.forName(type, false, parent).also(::checkClass) }, parent, session.plugin.id) { callback, self, arguments ->
                        check(live && session.isCurrent()) { "defineClass: plugin has unloaded" }
                        val inputs = ArrayList<String>()
                        try {
                            inputs.add(encodeValue(self))
                            arguments.forEach { inputs.add(encodeValue(it)) }
                            val result = session.engine.jvmMethod(callback, inputs[0], inputs.drop(1).toTypedArray())
                            if (result.startsWith("E")) throw IllegalStateException(result.substring(1))
                            readRoutineResult(result)
                        } finally {
                            for (wire in inputs) if (wire.startsWith("G")) session.engine.jvmRelease(wire.substring(2).toLong())
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    refuse("invalid-argument", "defineClass: ${e.message}")
                }
                val ticket = nextTicket.getAndIncrement()
                val metadata = prepared.getMetadata(ticket)
                if (metadata.toByteArray(Charsets.UTF_8).size > VALUE_LIMIT_BYTES) {
                    prepared.close()
                    refuse("quota-exceeded", "defineClass: normalized metadata exceeds 1 MB")
                }
                pendingClasses[ticket] = prepared
                PluginWire.encodeString(metadata)
            }
            OP_LOAD_CLASS -> {
                val prepared = pendingClasses.remove(target) ?: expired()
                try {
                    require(args.size == 1) { "expected class DEX bytes" }
                    val bytes = decodeArg(args[0]) as? ByteArray ?: throw IllegalArgumentException("expected class DEX bytes")
                    val defined = prepared.load(bytes)
                    definedClasses.add(defined)
                    loaders.add(defined.type.classLoader!!)
                    encodeValue(defined.type)
                } catch (error: Throwable) {
                    prepared.close()
                    throw error
                }
            }
            OP_CANCEL_CLASS -> {
                pendingClasses.remove(target)?.close()
                PluginWire.encodeNull()
            }
            OP_ROUTINE -> createRoutine(name, args)
            OP_XPOSED_ROUTINE -> {
                if (!session.permissions.has("unsafe.xposed")) refuse("not-granted", "xposed routine requires unsafe.xposed", "unsafe.xposed")
                createRoutine(name, args, hookMode = true)
            }
            OP_LOAD_DEX -> loadDex(name, args)
            OP_CURRENT_FRAGMENT -> encodeValue(screen.currentFragment())
            OP_CURRENT_ACTIVITY -> encodeValue(screen.currentActivity())
            OP_BUNDLE_METHOD -> encodeValue(bundleMethod(at(target)))
            OP_FROM_TL -> encodeValue(session.tl.objectFromWire(name, "jvm: fromTl"))
            OP_TO_TL -> {
                val value = at(target) as? TLObject
                    ?: refuse("invalid-argument", "jvm: that handle is not a TLObject")
                session.tl.mintWireForPlugin(value, readOnly = false)
            }
            else -> PluginWire.encodeError("jvm: unknown op $op")
        }

        private fun bundleMethod(value: Any): String? = when (value) {
            is Bundle -> "putBundle"
            is Boolean -> "putBoolean"
            is Byte -> "putByte"
            is Char -> "putChar"
            is Short -> "putShort"
            is Int -> "putInt"
            is Long -> "putLong"
            is Float -> "putFloat"
            is Double -> "putDouble"
            is String -> "putString"
            is CharSequence -> "putCharSequence"
            is BooleanArray -> "putBooleanArray"
            is ByteArray -> "putByteArray"
            is ShortArray -> "putShortArray"
            is CharArray -> "putCharArray"
            is IntArray -> "putIntArray"
            is LongArray -> "putLongArray"
            is FloatArray -> "putFloatArray"
            is DoubleArray -> "putDoubleArray"
            is Array<*> -> value.javaClass.componentType?.let { component ->
                when {
                    String::class.java.isAssignableFrom(component) -> "putStringArray"
                    CharSequence::class.java.isAssignableFrom(component) -> "putCharSequenceArray"
                    Parcelable::class.java.isAssignableFrom(component) -> "putParcelableArray"
                    else -> null
                }
            }
            is ArrayList<*> -> when {
                value.isNotEmpty() && value.all { it == null || it is Int } -> "putIntegerArrayList"
                value.isNotEmpty() && value.all { it == null || it is String } -> "putStringArrayList"
                value.isNotEmpty() && value.all { it == null || it is CharSequence } -> "putCharSequenceArrayList"
                value.isNotEmpty() && value.all { it == null || it is Parcelable } -> "putParcelableArrayList"
                else -> "putSerializable"
            }
            is SparseArray<*> -> if ((0 until value.size()).all { value.valueAt(it) == null || value.valueAt(it) is Parcelable }) {
                "putSparseParcelableArray"
            } else {
                null
            }
            is IBinder -> "putBinder"
            is Size -> "putSize"
            is SizeF -> "putSizeF"
            is Parcelable -> "putParcelable"
            is Serializable -> "putSerializable"
            else -> null
        }

        fun objectAt(handle: Long): Any? = if (live) session.engine.jvmObjectAt(handle) else null

        fun close() {
            live = false
            definedClasses.forEach { it.close() }
            definedClasses.clear()
            pendingClasses.values.forEach { it.close() }
            pendingClasses.clear()
            for (routine in routinees) routine.get()?.close()
            routinees.clear()
            session.engine.jvmCloseHandles()
            loaders.clear()
        }

        override fun jvmResolve(target: Any, name: String, mode: Int): Array<Any?> = try {
            if (!live) expired()
            when (mode) {
                RESOLVE_METHODS -> {
                    val cls = target as? Class<*> ?: refuse("invalid-argument", "jvm: that handle is not a class")
                    val descriptor = descriptorIn(name)
                    var candidates = candidateMethods(cls, simpleName(name))
                    if (descriptor != null) candidates = candidates.filter { it.descriptor == descriptor }
                    if (candidates.isEmpty()) refuse("not-found", "jvm: ${cls.name} has no method named $name")
                    methodsAnswer(cls, candidates)
                }
                RESOLVE_CONSTRUCTORS -> {
                    val cls = target as? Class<*> ?: refuse("invalid-argument", "jvm: that handle is not a class")
                    var candidates = cachedConstructors(cls)
                    if (name.isNotEmpty()) candidates = candidates.filter { it.descriptor == name }
                    if (candidates.isEmpty()) refuse("not-found", "jvm: ${cls.name} has no constructor $name")
                    methodsAnswer(cls, candidates)
                }
                RESOLVE_FIELD -> {
                    val cls = target as? Class<*> ?: refuse("invalid-argument", "jvm: that handle is not a class")
                    val field = cachedField(cls, name) ?: refuse("not-found", "jvm: ${cls.name} has no field named $name")
                    fieldAnswer(field)
                }
                RESOLVE_MEMBER -> when (target) {
                    is Field -> fieldAnswer(target)
                    is Executable -> methodsAnswer(target.declaringClass, listOf(MemberInfo(target)))
                    else -> refuse("invalid-argument", "jvm: that handle is not a method, constructor or field")
                }
                else -> refuse("internal", "jvm: unknown resolve mode $mode")
            }
        } catch (e: PluginRefusal) {
            arrayOf("E", e.wire)
        } catch (e: Throwable) {
            arrayOf("E", PluginWire.encodeError("jvm: ${describe(e)}"))
        }

        /** the verdict on the declaring class, per member: rust applies it to the one it picks */
        private fun refusalOf(member: Member): String? = try {
            checkMember(member)
            null
        } catch (e: PluginRefusal) {
            e.wire
        }

        private fun methodsAnswer(cls: Class<*>, candidates: List<MemberInfo>): Array<Any?> {
            val answer = ArrayList<Any?>(2 + candidates.size * 6)
            answer.add("M")
            answer.add(cls.name)
            for (info in candidates) {
                answer.add(info.member)
                answer.add(info.params)
                answer.add(info.descriptor)
                answer.add(Modifier.isStatic(info.member.modifiers))
                answer.add(Modifier.isAbstract(info.member.modifiers))
                answer.add(refusalOf(info.member))
            }
            return answer.toTypedArray()
        }

        /** the class named is the declaring one: it is what a refusal or a final-field message names */
        private fun fieldAnswer(field: Field): Array<Any?> {
            return arrayOf(
                "F",
                field.declaringClass.name,
                field,
                field.type,
                descriptorOf(field.type),
                Modifier.isStatic(field.modifiers),
                Modifier.isFinal(field.modifiers),
                refusalOf(field),
                field.type.name,
                field.name,
            )
        }

        /** an array is checked by its element type: `[Ljava.lang.String;` is not a name any namespace list can hold */
        private fun checkClass(cls: Class<*>) {
            var element = cls
            while (element.isArray) element = element.componentType!!
            if (element.isPrimitive) return
            checkName(element.name)
        }

        private fun checkName(name: String) {
            if (isEnginePackage(name)) {
                refuse("forbidden", "jvm: $name is the plugin engine's own bridge and is never reachable")
            }
        }

        private fun checkMember(member: Member) {
            checkClass(member.declaringClass)
        }

        override fun memberAt(target: Long): Member {
            val member = at(target) as? Member
                ?: refuse("invalid-argument", "jvm: that handle is not a method or constructor")
            checkMember(member)
            return member
        }

        override fun classAt(target: Long): Class<*> = classHandleAt(target).also { checkClass(it) }

        override fun decode(wire: String): Any? = decodeArg(wire)

        override fun encode(value: Any?): String = encodeValue(value)

        override fun release(wire: String) {
            // a throwable answer rides under a `T`, and its handle is the one that would be left behind
            val handle = wire.removePrefix("T")
            if (!handle.startsWith("G")) return
            handle.drop(2).toLongOrNull()?.let { session.engine.jvmRelease(it) }
        }

        override fun wireOf(failure: Throwable): String? = (failure as? PluginRefusal)?.wire

        /** the table is rust's, and a mint it refuses is one whose engine has already closed */
        private fun mint(value: Any, kind: Char): String {
            val id = session.engine.jvmMint(value, kind)
            if (id == 0L) expired()
            return "G$kind$id"
        }

        private fun expired(): Nothing =
            refuse("handle-expired", "jvm: that handle was released; a plugin's handles do not outlive it")

        private fun at(target: Long): Any = objectAt(target) ?: expired()

        private fun classHandleAt(target: Long): Class<*> = at(target) as? Class<*>
            ?: refuse("invalid-argument", "jvm: that handle is not a class")

        private fun decodeArgs(args: Array<String>): List<Any?> = args.map { decodeArg(it) }

        /** the scalars are [PluginWire]'s own; `G<id>` carries no kind, because the table that answers it is the one that minted it */
        private fun decodeArg(wire: String): Any? {
            if (wire.isEmpty()) refuse("invalid-argument", "jvm: empty argument wire")
            if (wire[0] == 'G') {
                val id = wire.substring(1).toLongOrNull()
                    ?: refuse("invalid-argument", "jvm: malformed handle argument")
                return at(id)
            }
            return when (val decoded = PluginWire.decode(wire)) {
                is PluginWire.Value.Null -> null
                is PluginWire.Value.Str -> decoded.value.also { checkStringSize(it, "a string argument") }
                is PluginWire.Value.IntNum -> decoded.value
                is PluginWire.Value.DoubleNum -> decoded.value
                is PluginWire.Value.Bool -> decoded.value
                is PluginWire.Value.Bytes -> Base64.decode(decoded.base64, Base64.NO_WRAP)
                else -> refuse("invalid-argument", "jvm: cannot pass $wire to java")
            }
        }

        /** what may cross at all, whether it then crosses as a handle or stays on this side: a value within the size limit, of a class that is not the engine's own */
        private fun checkValue(value: Any?) {
            when (value) {
                null, is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double, is Char -> {}
                is String -> checkStringSize(value, "a string")
                is ByteArray -> if (value.size > VALUE_LIMIT_BYTES) tooBig("a byte[]", value.size.toLong())
                // a `Class` is checked as the class it *names*, or every one would be checked as `java.lang.Class`
                is Class<*> -> checkClass(value)
                // and a member by the class it *declares*
                is Member -> checkMember(value)
                else -> checkClass(value.javaClass)
            }
        }

        private fun encodeValue(value: Any?): String {
            checkValue(value)
            return encodeChecked(value)
        }

        private fun encodeChecked(value: Any?): String = when (value) {
            null -> PluginWire.encodeNull()
            is Boolean -> PluginWire.encodeBool(value)
            is Byte -> PluginWire.encodeInt(value.toLong())
            is Short -> PluginWire.encodeInt(value.toLong())
            is Int -> PluginWire.encodeInt(value.toLong())
            is Long -> PluginWire.encodeInt(value)
            is Float -> PluginWire.encodeDouble(value.toDouble())
            is Double -> PluginWire.encodeDouble(value)
            // a char is one character of text rather than its code point: that is what goes back into a `char` parameter unchanged
            is Char -> PluginWire.encodeString(value.toString())
            is String -> PluginWire.encodeString(value)
            is ByteArray -> PluginWire.encodeBytes(Base64.encodeToString(value, Base64.NO_WRAP))
            is Class<*> -> mint(value, KIND_CLASS)
            is Method -> mint(value, KIND_METHOD)
            is java.lang.reflect.Constructor<*> -> mint(value, KIND_CONSTRUCTOR)
            is Field -> mint(value, KIND_FIELD)
            else -> mint(value, KIND_OBJECT)
        }

        private fun classFor(name: String): Class<*> {
            for (loader in loaders) {
                runCatching { return Class.forName(name, false, loader) }
            }
            // deliberately without initializing: naming a class must not be what runs its static block
            return try {
                Class.forName(name, false, PluginJvm::class.java.classLoader)
            } catch (e: ClassNotFoundException) {
                refuse("not-found", "jvm: no class named $name")
            } catch (e: LinkageError) {
                refuse("not-found", "jvm: $name did not load: ${describe(e)}")
            }
        }

        private fun findField(cls: Class<*>, name: String): Field {
            val field = cachedField(cls, name)
                ?: refuse("not-found", "jvm: ${cls.name} has no field named $name")
            // on the member the scan picked, never inside it: the scan is shared between plugins
            // and this is the gate that is not
            checkMember(field)
            field.isAccessible = true
            return field
        }

        private fun readField(field: Field, self: Any?): Any? = field.get(self)

        private fun writeField(field: Field, self: Any?, args: List<Any?>): Any? {
            if (args.size != 1) refuse("invalid-argument", "jvm: a field takes exactly one value")
            if (Modifier.isFinal(field.modifiers)) {
                refuse("forbidden", "jvm: ${field.declaringClass.name}.${field.name} is final")
            }
            val value = convert(args[0], field.type)
                ?: refuse("invalid-argument", "jvm: cannot assign that to a ${field.type.name}")
            field.set(self, value.value)
            return null
        }

        /**
         * What a routine's java instructions run through. The table is rust's, so encoding a result
         * here would be a mint and a release across jni per operation.
         */
        private inline fun bridged(block: () -> Any?): Any? {
            val result = try {
                block()
            } catch (e: InvocationTargetException) {
                // reflection wraps whatever the callee threw, and a routine's `catch` binds what it
                // catches: the wrapper is this side's, not the plugin's
                throw e.cause ?: e
            }
            return checkedOperand(result)
        }

        private inline fun onMember(target: Any, block: (Class<*>, Any?) -> Any?): Any? = bridged {
            val cls = target as? Class<*> ?: target.javaClass
            checkClass(cls)
            block(cls, target.takeUnless { it is Class<*> })
        }

        internal open fun getMember(target: Any, name: String): Any? =
            onMember(target) { cls, receiver -> readField(findField(cls, name), receiver) }

        internal open fun setMember(target: Any, name: String, value: Any?): Any? =
            onMember(target) { cls, receiver -> writeField(findField(cls, name), receiver, listOf(value)) }

        internal open fun callMember(target: Any, name: String, args: List<Any?>): Any? =
            onMember(target) { cls, receiver -> callMethod(cls, receiver, name, args) }

        internal open fun newInstanceOf(target: Any, args: List<Any?>): Any? = bridged { construct(target, args) }

        internal open fun callSuper(target: Any, receiver: Any?, name: String, args: List<Any?>): Any? =
            bridged { callSuperMethod(target, receiver, name, args) }

        internal open fun getElement(target: Any, index: Int): Any? = bridged { readElement(target, index) }

        internal open fun setElement(target: Any, index: Int, value: Any?): Any? =
            bridged { writeElement(target, index, value) }

        internal open fun getArrayLength(target: Any): Any? = bridged { arrayLength(target) }

        internal open fun iterate(target: Any): Iterator<*> = try {
            iteratorOf(target)
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }

        private fun construct(target: Any, args: List<Any?>): Any {
            val info = when (target) {
                is Class<*> -> {
                    checkClass(target)
                    pick(cachedConstructors(target).filter { matches(it.params, args) }, "${target.name} constructor", args)
                }
                is java.lang.reflect.Constructor<*> -> MemberInfo(target).also {
                    if (!matches(it.params, args)) {
                        refuse("invalid-argument", "jvm: ${target.declaringClass.name} does not take these arguments")
                    }
                }
                else -> refuse("invalid-argument", "routine: new expects a java class or constructor")
            }
            checkMember(info.member)
            val constructor = info.member as java.lang.reflect.Constructor<*>
            constructor.isAccessible = true
            return constructor.newInstance(*convertAll(info.params, args))
        }

        private fun arrayOperand(target: Any, what: String): Any =
            target.takeIf { it.javaClass.isArray }?.also { checkClass(it.javaClass) }
                ?: refuse("invalid-argument", "routine: $what expects a java array")

        private fun arrayLength(target: Any): Int =
            java.lang.reflect.Array.getLength(arrayOperand(target, "length"))

        private fun readElement(target: Any, index: Int): Any? =
            java.lang.reflect.Array.get(arrayOperand(target, "indexing"), index)

        private fun writeElement(target: Any, index: Int, value: Any?): Any? {
            val array = arrayOperand(target, "indexing")
            val component = array.javaClass.componentType!!
            val converted = convert(value, component)
                ?: refuse("invalid-argument", "jvm: cannot assign that to a ${component.name}")
            java.lang.reflect.Array.set(array, index, converted.value)
            return null
        }

        private fun iteratorOf(target: Any): Iterator<*> {
            checkClass(target.javaClass)
            if (target.javaClass.isArray) {
                val length = java.lang.reflect.Array.getLength(target)
                return object : Iterator<Any?> {
                    private var at = 0
                    override fun hasNext(): Boolean = at < length
                    override fun next(): Any? = java.lang.reflect.Array.get(target, at++)
                }
            }
            val iterable = target as? Iterable<*>
                ?: refuse("invalid-argument", "routine: for-of expects a java array or an Iterable")
            return iterable.iterator()
        }

        private fun callMethod(cls: Class<*>, self: Any?, name: String, args: List<Any?>): Any? {
            val info = resolve(cls, name, args, staticOnly = self == null)
            info.method.isAccessible = true
            return info.method.invoke(self, *convertAll(info.params, args))
        }

        private fun callSuperMethod(target: Any, receiver: Any?, name: String, args: List<Any?>): Any? {
            val cls = target as? Class<*> ?: refuse("invalid-argument", "routine: callSuper expects a java class")
            checkClass(cls)
            if (receiver == null) refuse("invalid-argument", "jvm: callSuper needs a java object to call on")
            if (!cls.isInstance(receiver)) refuse("invalid-argument", "jvm: that receiver is not an instance of ${cls.name}")
            val parent = cls.superclass ?: refuse("invalid-argument", "jvm: ${cls.name} has no superclass")
            val info = resolve(parent, name, args, staticOnly = false)
            val method = info.method
            val converted = convertAll(info.params, args)
            if (Modifier.isStatic(method.modifiers)) {
                method.isAccessible = true
                return method.invoke(null, *converted)
            }
            if (Modifier.isAbstract(method.modifiers)) {
                refuse(
                    "invalid-argument",
                    "jvm: ${parent.name}.${method.name}${info.descriptor} is abstract, so there is no super implementation to call",
                )
            }
            return Native.nativeCallNonvirtual(method, info.descriptor, info.params, receiver, converted)
        }

        private fun resolve(cls: Class<*>, name: String, args: List<Any?>, staticOnly: Boolean): MemberInfo {
            val descriptor = descriptorIn(name)
            val simple = simpleName(name)
            var candidates = candidateMethods(cls, simple)
            if (staticOnly) candidates = candidates.filter { Modifier.isStatic(it.member.modifiers) }
            if (descriptor != null) {
                candidates = candidates.filter { it.descriptor == descriptor }
                if (candidates.isEmpty()) refuse("not-found", "jvm: ${cls.name} has no method $name")
                // pinning an overload says *which* one, never that the arguments fit: without this `convertAll` turns whatever does not convert into a null and java reports it from somewhere else
                candidates = candidates.filter { matches(it.params, args) }
                if (candidates.isEmpty()) {
                    refuse("invalid-argument", "jvm: ${cls.name}.$name does not take these arguments")
                }
            } else {
                candidates = candidates.filter { matches(it.params, args) }
            }
            val info = pick(candidates, "${cls.name}.$simple", args)
            checkMember(info.member)
            return info
        }

        private fun pick(candidates: List<MemberInfo>, what: String, args: List<Any?>): MemberInfo {
            if (candidates.isEmpty()) {
                refuse("not-found", "jvm: no $what takes ${args.size} argument(s) of these types")
            }
            if (candidates.size == 1) return candidates[0]
            val asText = candidates.filter { !convertsTextToChar(it.params, args) }
            val pool = asText.ifEmpty { candidates }
            val narrowest = pool.filter { candidate ->
                pool.none { other -> other !== candidate && moreSpecific(other, candidate) }
            }
            if (narrowest.size != 1) {
                refuse(
                    "invalid-argument",
                    "jvm: $what is ambiguous for these arguments; pin one with a descriptor, e.g. " +
                        candidates.take(3).joinToString(", ") { it.descriptor },
                )
            }
            return narrowest[0]
        }

        private fun candidateMethods(cls: Class<*>, name: String): List<MemberInfo> = cachedMethods(cls, name)

        private fun readRoutineResult(wire: String): Any? {
            if (wire.startsWith("L")) return readArrayResult(JSONArray(wire.substring(1)))
            if (!wire.startsWith("G")) return decodeArg(wire)
            val id = wire.substring(2).toLong()
            return try { at(id) } finally { session.engine.jvmRelease(id) }
        }

        /** every copied handle in the list is this side's to release, including the ones after an item that fails */
        private fun readArrayResult(wires: JSONArray): Array<Any?> {
            val result = arrayOfNulls<Any?>(wires.length())
            var failure: Throwable? = null
            for (index in 0 until wires.length()) {
                val wire = wires.getString(index)
                if (failure != null) {
                    if (wire.startsWith("G")) session.engine.jvmRelease(wire.substring(2).toLong())
                    continue
                }
                try {
                    result[index] = readRoutineResult(wire)
                } catch (e: Throwable) {
                    failure = e
                }
            }
            failure?.let { throw it }
            return result
        }

        /**
         * a routine reads java values without a wire between them, so this is where the wire's own
         * checks and its one conversion happen: a char is one character of text everywhere a
         * plugin can see one, and a routine must not be the place it is not.
         */
        internal fun checkedOperand(value: Any?): Any? {
            checkValue(value)
            return if (value is Char) value.toString() else value
        }

        private fun createRoutine(definition: String, args: Array<String>, hookMode: Boolean = false): String {
            routinees.removeAll { it.get() == null }
            if (routinees.size >= 512) refuse("quota-exceeded", "routine: at most 512 live routinees")
            val values = decodeArgs(args).map { if (it is ByteArray) it.copyOf() else it }
            val routine = try {
                PluginJvmRoutine(definition, values, this, hookMode)
            } catch (e: Exception) {
                refuse("invalid-argument", "routine: ${e.message}")
            }
            routinees.add(java.lang.ref.WeakReference(routine))
            return mint(if (hookMode) PluginXposedRoutine(routine) else routine, KIND_OBJECT)
        }

        private fun mintRunnable(args: Array<String>): String {
            val callbackId = (decodeArg(args.firstOrNull() ?: "N") as? Long)
                ?: refuse("internal", "jvm: runnable without a callback id")
            // not the app's object but one the engine made at the plugin's request. Reaching *into* it is still refused, being in [ENGINE_PACKAGE]
            return mint(JsRunnable(this, callbackId.toInt()), KIND_OBJECT)
        }

        /** Native admission serializes callbacks and refuses recursive entry. */
        fun fire(callbackId: Int) {
            // `live` on top of the engine identity: a disposed runnable java kept hold of
            if (live && session.isCurrent()) session.engine.jvmCallback(callbackId)
        }

        private fun loadDex(path: String, args: Array<String>): String {
            val file = if (path.isNotEmpty()) fromPath(path) else stage(args)
            val length = file.length()
            if (length == 0L) refuse("invalid-argument", "loadDex: ${file.name} is empty")
            if (length > DEX_LIMIT_BYTES) {
                throw PluginRefusal(
                    PluginWire.encodePluginError(
                        "quota-exceeded",
                        "loadDex: ${file.name} is $length bytes, over the $DEX_LIMIT_BYTES this api loads",
                        usage = length,
                        quota = DEX_LIMIT_BYTES,
                    )
                )
            }
            // the platform's verifier owns dex validation, and reports by failing to define the class rather than by refusing the file
            loaders.add(DexClassLoader(file.absolutePath, null, null, PluginJvm::class.java.classLoader))
            return PluginWire.encodeNull()
        }

        private fun fromPath(path: String): File {
            if (!path.startsWith("/")) {
                refuse(
                    "invalid-argument",
                    "loadDex: '$path' is relative; pass an absolute path or the bytes themselves",
                )
            }
            val file = File(path)
            if (!file.isFile) refuse("not-found", "loadDex: no file at $path")
            if (!file.canRead()) refuse("not-found", "loadDex: $path is not readable")
            return file
        }

        private fun stage(args: Array<String>): File {
            val bytes = decodeArg(args.firstOrNull() ?: "N") as? ByteArray
                ?: refuse("invalid-argument", "loadDex: expected a path or a Uint8Array")
            val dir = dexDir(session.plugin.id)
            if (!dir.isDirectory && !dir.mkdirs()) refuse("internal", "loadDex: could not make ${dir.path}")
            val file = File(dir, "staged_${dexCount++}.dex")
            // the counter restarts per engine while the directory outlives it, so after a reload this name is a read-only file the previous session left
            file.delete()
            file.writeBytes(bytes)
            // android's w^x guidance for anything the runtime is about to map as code
            file.setReadOnly()
            return file
        }
    }

    /** deliberately tiny: everything reachable from it is refused by [Session.checkClass] anyway, being in [ENGINE_PACKAGE] */
    private class JsRunnable(private val session: Session, private val callbackId: Int) : Runnable {
        override fun run() {
            session.fire(callbackId)
        }
    }

    private class Converted(val value: Any?)

    internal fun convertArguments(types: Array<Class<*>>, args: List<Any?>): Array<Any?>? =
        if (matches(types, args)) convertAll(types, args) else null

    private fun convertAll(types: Array<Class<*>>, args: List<Any?>): Array<Any?> =
        Array(types.size) { convert(args[it], types[it])?.value }

    private fun matches(types: Array<Class<*>>, args: List<Any?>): Boolean =
        types.size == args.size && types.indices.all { convert(args[it], types[it]) != null }

    /** a js number is an integer or a double and nothing narrower, so the parameter's type decides - and one that does not fit exactly is refused rather than truncated */
    private fun convert(value: Any?, type: Class<*>): Converted? {
        if (value == null) return if (type.isPrimitive) null else Converted(null)
        return when (value) {
            is Boolean -> if (type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java ||
                type.isAssignableFrom(java.lang.Boolean::class.java)
            ) Converted(value) else null

            is Long -> fromLong(value, type)
            is Byte -> fromLong(value.toLong(), type)
            is Short -> fromLong(value.toLong(), type)
            is Int -> fromLong(value.toLong(), type)
            is Double -> fromDouble(value, type)
            is Float -> if (type.isInstance(value)) Converted(value) else fromDouble(value.toDouble(), type)
            is Char -> if (type == Char::class.javaPrimitiveType || type.isInstance(value)) Converted(value) else fromLong(value.code.toLong(), type)
            is String -> when {
                type == Char::class.javaPrimitiveType || type == java.lang.Character::class.java ->
                    if (value.length == 1) Converted(value[0]) else null
                type.isAssignableFrom(String::class.java) -> Converted(value)
                else -> null
            }

            is ByteArray -> if (type.isAssignableFrom(ByteArray::class.java)) Converted(value) else null
            else -> if (type.isInstance(value)) Converted(value) else null
        }
    }

    private fun fromLong(value: Long, type: Class<*>): Converted? = when (type) {
        Byte::class.javaPrimitiveType, java.lang.Byte::class.java ->
            if (value in Byte.MIN_VALUE..Byte.MAX_VALUE) Converted(value.toByte()) else null
        Short::class.javaPrimitiveType, java.lang.Short::class.java ->
            if (value in Short.MIN_VALUE..Short.MAX_VALUE) Converted(value.toShort()) else null
        Int::class.javaPrimitiveType, java.lang.Integer::class.java ->
            if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) Converted(value.toInt()) else null
        Char::class.javaPrimitiveType, java.lang.Character::class.java ->
            if (value in 0..0xffff) Converted(value.toInt().toChar()) else null
        Long::class.javaPrimitiveType, java.lang.Long::class.java -> Converted(value)
        Float::class.javaPrimitiveType, java.lang.Float::class.java -> Converted(value.toFloat())
        Double::class.javaPrimitiveType, java.lang.Double::class.java -> Converted(value.toDouble())
        // an `Object`-shaped parameter takes the box a java literal would have: `Integer` when it fits, `Long` when it does not
        else -> {
            val boxed: Any = if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else value
            if (type.isInstance(boxed)) Converted(boxed) else null
        }
    }

    private fun fromDouble(value: Double, type: Class<*>): Converted? = when (type) {
        Double::class.javaPrimitiveType, java.lang.Double::class.java -> Converted(value)
        Float::class.javaPrimitiveType, java.lang.Float::class.java ->
            if (value.isFinite() && Math.abs(value) > Float.MAX_VALUE.toDouble()) null else Converted(value.toFloat())
        else -> if (type.isInstance(value)) Converted(value) else null
    }

    /** a narrower numeric parameter wins and a more derived reference type beats a less derived one; anything still tied is refused rather than picked */
    /**
     * A js string is a `String` before it is anything else, so an overload that takes it as text
     * wins over one that only fits by reading it as a `char`, the way java's strict phase wins
     * over its conversions. Keep in step with rust `Native::converts_text_to_char`.
     */
    private fun convertsTextToChar(types: Array<Class<*>>, args: List<Any?>): Boolean =
        types.indices.any {
            args[it] is String &&
                (types[it] == Char::class.javaPrimitiveType || types[it] == java.lang.Character::class.java)
        }

    private fun moreSpecific(a: MemberInfo, b: MemberInfo): Boolean {
        val pa = a.params
        val pb = b.params
        if (pa.size != pb.size) return false
        var strictly = false
        for (i in pa.indices) {
            if (pa[i] == pb[i]) continue
            if (!narrower(pa[i], pb[i])) return false
            strictly = true
        }
        return strictly
    }

    private fun narrower(a: Class<*>, b: Class<*>): Boolean {
        val ra = numericRank(a)
        val rb = numericRank(b)
        if (ra != null && rb != null) return ra < rb
        if (ra != null || rb != null) return false
        return b.isAssignableFrom(a)
    }

    private fun numericRank(type: Class<*>): Int? = when (type) {
        Byte::class.javaPrimitiveType -> 0
        Short::class.javaPrimitiveType -> 1
        Char::class.javaPrimitiveType -> 1
        Int::class.javaPrimitiveType -> 2
        Long::class.javaPrimitiveType -> 3
        Float::class.javaPrimitiveType -> 4
        Double::class.javaPrimitiveType -> 5
        else -> null
    }

    private fun descriptorIn(name: String): String? {
        val open = name.indexOf('(')
        if (open < 0) return null
        return name.substring(open)
    }

    private fun simpleName(name: String): String {
        val open = name.indexOf('(')
        return if (open < 0) name else name.substring(0, open)
    }

    /**
     * Caches member resolution process-wide, one table per declaring class.
     *
     * ART allocates new arrays and `Method` objects for each `getDeclaredMethods()` and `getMethods()`
     * call. Resolving a deep class can allocate thousands of objects. On-device measurements showed
     * ~5 ms for a `TextView` call versus ~0.1 ms for a constructor, which skips this scan.
     *
     * Each table holds only declared members. Lookups combine superclass and interface tables,
     * most derived first, so all widgets reuse `View`'s table. Cache by class rather than member
     * because one scan costs the same for one name or all names. Walk interface tables directly;
     * `getMethods()` would merge and deduplicate public members already covered by the superclass scan.
     *
     * Permission checks and overload selection remain per call, allowing tables to be shared
     * between plugins. Use a bounded LRU: weak keys would stay alive through their `Method` values,
     * and an unbounded cache would retain every class a plugin touched or defined.
     */
    private const val CLASS_CACHE_LIMIT = 256

    /**
     * `Executable.getParameterTypes()` allocates a fresh array every call, and every invoke asks for
     * it twice - once to check the arguments fit and once to convert them. The descriptor is a
     * string built from it, and a pinned call names one. Both are settled when the table is built.
     */
    private class MemberInfo(val member: Executable, val descriptor: String, val params: Array<Class<*>>) {
        constructor(member: Executable) : this(member, descriptorOf(member), member.parameterTypes)

        val method: Method get() = member as Method
    }

    private class MemberTable(cls: Class<*>) {
        val methods: Map<String, List<MemberInfo>>
        val constructors: List<MemberInfo> = cls.declaredConstructors.map { MemberInfo(it) }
        val fields: Map<String, Field> = cls.declaredFields.associateBy { it.name }
        val superclass: Class<*>? = cls.superclass
        val interfaces: Array<Class<*>> = cls.interfaces

        /**
         * the tables a lookup on *this* class composes, settled on first use: the walk is the same
         * every time, and a routine or a hook resolves a member per call rather than per class.
         * Racing threads compute the same lists, so the write needs no lock.
         */
        @Volatile var lineage: Lineage? = null

        /** what a lookup composes out of [lineage] for one name; an empty list is "this class has none" */
        val composedMethods = ConcurrentHashMap<String, List<MemberInfo>>()
        val composedFields = ConcurrentHashMap<String, List<Field>>()

        init {
            val methods = HashMap<String, ArrayList<MemberInfo>>()
            for (method in cls.declaredMethods) methods.getOrPut(method.name) { ArrayList() }.add(MemberInfo(method))
            this.methods = methods
        }
    }

    private val tableCache: MutableMap<Class<*>, MemberTable> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<Class<*>, MemberTable>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Class<*>, MemberTable>): Boolean =
                size > CLASS_CACHE_LIMIT
        },
    )

    private fun tableOf(cls: Class<*>): MemberTable = tableCache.getOrPut(cls) { MemberTable(cls) }

    /** the superclass chain, most derived first, then every interface any of them implements */
    private class Lineage(val chain: List<MemberTable>, val interfaces: List<MemberTable>)

    private fun lineageOf(cls: Class<*>): Lineage {
        val table = tableOf(cls)
        table.lineage?.let { return it }
        val chain = ArrayList<MemberTable>()
        val interfaces = LinkedHashMap<Class<*>, MemberTable>()
        var current: MemberTable? = table
        while (current != null) {
            chain.add(current)
            collectInterfaces(current, interfaces)
            current = current.superclass?.let { tableOf(it) }
        }
        return Lineage(chain, interfaces.values.toList()).also { table.lineage = it }
    }

    private fun collectInterfaces(table: MemberTable, out: MutableMap<Class<*>, MemberTable>) {
        for (itf in table.interfaces) {
            if (out.containsKey(itf)) continue
            val itfTable = tableOf(itf)
            out[itf] = itfTable
            collectInterfaces(itfTable, out)
        }
    }

    private fun cachedMethods(cls: Class<*>, name: String): List<MemberInfo> {
        val table = tableOf(cls)
        table.composedMethods[name]?.let { return it }
        val lineage = lineageOf(cls)
        // keyed by parameters alone: a covariant override is the same method to a caller, not an overload of
        // the one it overrides, and a bridge only forwards to the real method it stands beside
        val byParameters = LinkedHashMap<String, MemberInfo>()
        // the most derived override wins, so the chain is walked downwards-first
        for (table in lineage.chain) for (info in table.methods[name].orEmpty()) {
            if (info.method.isBridge) continue
            byParameters.putIfAbsent(parametersOf(info.descriptor), info)
        }
        // defaults, which are not on the superclass chain; an interface's static and private methods are not inherited
        for (table in lineage.interfaces) for (info in table.methods[name].orEmpty()) {
            val modifiers = info.member.modifiers
            if (Modifier.isStatic(modifiers) || Modifier.isPrivate(modifiers) || info.method.isBridge) continue
            byParameters.putIfAbsent(parametersOf(info.descriptor), info)
        }
        return byParameters.values.toList().also { table.composedMethods[name] = it }
    }

    private fun parametersOf(descriptor: String): String = descriptor.substring(0, descriptor.indexOf(')') + 1)

    private fun cachedConstructors(cls: Class<*>): List<MemberInfo> = tableOf(cls).constructors

    private fun cachedField(cls: Class<*>, name: String): Field? {
        val table = tableOf(cls)
        table.composedFields[name]?.let { return it.firstOrNull() }
        val lineage = lineageOf(cls)
        val found = lineage.chain.firstNotNullOfOrNull { it.fields[name] }
            ?: lineage.interfaces.firstNotNullOfOrNull { it.fields[name] }
        table.composedFields[name] = listOfNotNull(found)
        return found
    }

    private fun descriptorOf(member: Executable): String {
        val params = member.parameterTypes.joinToString("") { descriptorOf(it) }
        val returns = if (member is Method) descriptorOf(member.returnType) else "V"
        return "($params)$returns"
    }

    internal fun descriptorOf(type: Class<*>): String = when {
        type == Void.TYPE -> "V"
        type == Boolean::class.javaPrimitiveType -> "Z"
        type == Byte::class.javaPrimitiveType -> "B"
        type == Char::class.javaPrimitiveType -> "C"
        type == Short::class.javaPrimitiveType -> "S"
        type == Int::class.javaPrimitiveType -> "I"
        type == Long::class.javaPrimitiveType -> "J"
        type == Float::class.javaPrimitiveType -> "F"
        type == Double::class.javaPrimitiveType -> "D"
        type.isArray -> "[${descriptorOf(type.componentType!!)}"
        else -> "L${type.name.replace('.', '/')};"
    }

    private fun describe(member: Method): String = "${member.declaringClass.name}.${member.name}${descriptorOf(member)}"

    private fun describe(error: Throwable): String {
        val message = error.message
        return if (message.isNullOrEmpty()) error.javaClass.name else "${error.javaClass.name}: $message"
    }
}
