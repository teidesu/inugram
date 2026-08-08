package desu.inugram.helpers.plugins.platform

import android.util.Base64
import dalvik.system.DexClassLoader
import desu.inugram.core.plugins.PluginInstalls
import desu.inugram.core.plugins.ScopeMatch
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.JvmListener
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginDispatch
import desu.inugram.helpers.plugins.QuickJs
import java.io.File
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.Utilities

/**
 * Kotlin side of `inu.jvm` (rust: `jvm.rs`).
 *
 * Scopes are checked here because this is the side that knows: the *runtime* class of every
 * reference minted and the *declaring* class of every member reached are heap facts rust cannot
 * see. A value that crosses is not checked - a value is data, and only a reference is a capability.
 * That buys reach, not safety: a scope naming `java.lang.reflect.*`, `java.lang.Class`,
 * `java.lang.ClassLoader` or `dalvik.system.*` is the same statement as the bare grant.
 *
 * Two rules are call-time rather than scope entries, an unscoped grant satisfying every scope
 * check: `loadDex` needs the whole grant, dex running with the app's permissions and never crossing
 * this bridge again; and [ENGINE_PACKAGE] is unreachable, a reflected call running on
 * [Utilities.globalQueue] inside a JNI upcall with the engine's `RefCell` already borrowed. The
 * latter guards one hop, not a boundary - `java.lang.reflect` walks around it.
 */
object PluginJvm {
    // keep in sync with rust `jvm::OP_*` and `jvm.js`
    const val OP_CLASS = 0
    const val OP_NEW = 1
    const val OP_GET = 2
    const val OP_SET = 3
    const val OP_CALL = 4
    const val OP_METHOD = 5
    const val OP_FIELD = 6
    const val OP_INVOKE = 7
    const val OP_MEMBER_GET = 8
    const val OP_MEMBER_SET = 9
    const val OP_RUNNABLE = 10
    const val OP_LOAD_DEX = 11
    const val OP_RELEASE = 12
    const val OP_CURRENT_FRAGMENT = 13
    const val OP_CURRENT_ACTIVITY = 14

    const val GRANT = "unsafe.jvm"

    /** keep in sync with rust `jvm::VALUE_LIMIT_BYTES` and the number `android.jvm.d.ts` states */
    const val VALUE_LIMIT_BYTES = 1024 * 1024

    /** keep in sync with rust `jvm::DEX_LIMIT_BYTES` and the number `android.jvm.d.ts` states */
    const val DEX_LIMIT_BYTES = 8L * 1024 * 1024

    private const val ENGINE_PACKAGE = "desu.inugram.helpers.plugins"

    private const val ROOT = "inu_plugin_dex"

    // handle kinds; keep in sync with rust `jvm::KIND_*` and `jvm.js`
    private const val KIND_CLASS = 'C'
    private const val KIND_OBJECT = 'O'
    private const val KIND_METHOD = 'M'
    private const val KIND_FIELD = 'F'

    /** handed in rather than read here, so a test can put a screen in front of the api without an `Activity` */
    interface AppScreen {
        fun currentFragment(): Any?
        fun currentActivity(): Any?
    }

    /** nothing at all for a plugin without the grant: the api is the whole app */
    fun listenerFor(plugin: Plugin, engine: QuickJs, screen: AppScreen): JvmListener? =
        if (plugin.permissions.has(GRANT)) Session(plugin, engine, screen) else null

    fun install(engine: QuickJs) {
        if (engine.listener?.jvm != null) engine.installJvm()
    }

    /** the scope list already decided this: a handle only exists because [Session.checkClass] let it be minted */
    fun objectAt(engine: QuickJs, handle: Long): Any? = (engine.listener?.jvm as? Session)?.objectAt(handle)

    fun detach(engine: QuickJs) {
        (engine.listener?.jvm as? Session)?.close()
    }

    fun dexDir(installId: String): File {
        require(PluginInstalls.isValidId(installId)) { "malformed install id" }
        return File(ApplicationLoader.applicationContext.filesDir, "$ROOT/$installId")
    }

    /** **only on uninstall**: a class cannot be unloaded, so a merely-stopped plugin's code may still be running */
    fun wipe(installId: String) {
        if (!PluginInstalls.isValidId(installId)) return
        dexDir(installId).deleteRecursively()
    }

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

        fun wireOf(failure: Throwable): String?
    }

    internal fun bridgeFor(engine: QuickJs): ValueBridge? = engine.listener?.jvm as? ValueBridge

    private class Refusal(val wire: String) : RuntimeException(null, null, false, false)

    private fun refuse(code: String, message: String, grant: String? = null): Nothing =
        throw Refusal(PluginWire.encodePluginError(code, message, grant = grant))

    private fun tooBig(what: String, size: Long): Nothing =
        throw Refusal(
            PluginWire.encodePluginError(
                "quota-exceeded",
                "jvm: $what is $size bytes, over the $VALUE_LIMIT_BYTES this bridge carries",
                usage = size,
                quota = VALUE_LIMIT_BYTES.toLong(),
            )
        )

    private class Session(private val plugin: Plugin, private val engine: QuickJs, private val screen: AppScreen) :
        JvmListener, ValueBridge {
        // concurrent because [ValueBridge] is reached from off globalQueue: `inu.xposed` encodes on the hooked method's own thread, and `PluginApi.showDialog` on the ui thread
        private val handles = ConcurrentHashMap<Long, Any>()
        private val nextId = AtomicLong(1)
        private val loaders = ArrayList<ClassLoader>()
        private var dexCount = 0

        @Volatile
        private var live = true

        override fun jvm(op: Int, target: Long, name: String, args: Array<String>): String = try {
            if (!live) expired() else handle(op, target, name, args)
        } catch (e: Refusal) {
            e.wire
        } catch (e: InvocationTargetException) {
            // a java exception is not part of this api's taxonomy, so it arrives as a plain Error rather than a PluginError
            PluginWire.encodeError(describe(e.cause ?: e))
        } catch (e: Throwable) {
            PluginWire.encodeError("jvm: ${describe(e)}")
        }

        private fun handle(op: Int, target: Long, name: String, args: Array<String>): String = when (op) {
            // the name before the lookup, so a class outside the scope list reads the same whether or not it exists
            OP_CLASS -> {
                checkName(name)
                encodeValue(classFor(name))
            }
            OP_NEW -> construct(classHandleAt(target), decodeArgs(args))
            OP_GET -> readField(findField(receiverClass(target), name), instanceAt(target))
            OP_SET -> writeField(findField(receiverClass(target), name), instanceAt(target), decodeArgs(args))
            OP_CALL -> callMethod(receiverClass(target), instanceAt(target), name, decodeArgs(args))
            OP_METHOD -> mint(resolvePinned(classHandleAt(target), name), KIND_METHOD)
            OP_FIELD -> mint(findField(classHandleAt(target), name), KIND_FIELD)
            OP_INVOKE -> invokePinned(methodAt(target), decodeArgs(args))
            OP_MEMBER_GET -> readField(fieldAt(target), self(decodeArgs(args), 0))
            OP_MEMBER_SET -> {
                val decoded = decodeArgs(args)
                writeField(fieldAt(target), self(decoded, 0), decoded.drop(1))
            }
            OP_RUNNABLE -> mintRunnable(args)
            OP_LOAD_DEX -> {
                // dex runs with the app's permissions and never crosses this bridge again. Only an unscoped grant, or a literal `*`, passes
                if (!plugin.permissions.allows(GRANT, "*", ScopeMatch.NAMESPACE)) {
                    refuse("not-granted", "loadDex: this needs $GRANT with no scope list", "$GRANT(*)")
                }
                loadDex(name, args)
            }
            OP_RELEASE -> {
                handles.remove(target)
                PluginWire.encodeNull()
            }
            // `encodeValue` mints through `checkClass`, so a plugin scoped to one package still cannot be handed a fragment from another
            OP_CURRENT_FRAGMENT -> encodeValue(screen.currentFragment())
            OP_CURRENT_ACTIVITY -> encodeValue(screen.currentActivity())
            else -> PluginWire.encodeError("jvm: unknown op $op")
        }

        fun objectAt(handle: Long): Any? = if (live) handles[handle] else null

        fun close() {
            live = false
            handles.clear()
            loaders.clear()
        }

        /** an array is checked by its element type: `[Ljava.lang.String;` is not a name any namespace list can hold */
        private fun checkClass(cls: Class<*>) {
            var element = cls
            while (element.isArray) element = element.componentType!!
            if (element.isPrimitive) return
            checkName(element.name)
        }

        private fun checkName(name: String) {
            // whatever the scopes say: this is the one hop that would put the engine's own objects in a plugin's hands
            if (name.startsWith("$ENGINE_PACKAGE.")) {
                refuse("forbidden", "jvm: $name is the plugin engine's own bridge and is never reachable")
            }
            if (!plugin.permissions.allows(GRANT, name, ScopeMatch.NAMESPACE)) {
                refuse("not-granted", "jvm: $name is not in this plugin's $GRANT scope list", "$GRANT($name)")
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

        override fun wireOf(failure: Throwable): String? = (failure as? Refusal)?.wire

        private fun mint(value: Any, kind: Char): String {
            val id = nextId.getAndIncrement()
            handles[id] = value
            return "G$kind$id"
        }

        private fun expired(): Nothing =
            refuse("handle-expired", "jvm: that handle was released; a plugin's handles do not outlive it")

        private fun at(target: Long): Any = handles[target] ?: expired()

        private fun classHandleAt(target: Long): Class<*> = at(target) as? Class<*>
            ?: refuse("invalid-argument", "jvm: that handle is not a class")

        private fun methodAt(target: Long): Method = at(target) as? Method
            ?: refuse("invalid-argument", "jvm: that handle is not a method")

        private fun fieldAt(target: Long): Field = at(target) as? Field
            ?: refuse("invalid-argument", "jvm: that handle is not a field")

        private fun receiverClass(target: Long): Class<*> {
            val value = at(target)
            return value as? Class<*> ?: value.javaClass
        }

        private fun instanceAt(target: Long): Any? = at(target).takeIf { it !is Class<*> }

        private fun self(args: List<Any?>, index: Int): Any? {
            val value = args.getOrNull(index)
            if (value != null && value is Class<*>) {
                refuse("invalid-argument", "jvm: a class is not a receiver")
            }
            return value
        }

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
                is PluginWire.Value.Str -> decoded.value.also {
                    val size = it.toByteArray(Charsets.UTF_8).size
                    if (size > VALUE_LIMIT_BYTES) tooBig("a string argument", size.toLong())
                }
                is PluginWire.Value.IntNum -> decoded.value
                is PluginWire.Value.DoubleNum -> decoded.value
                is PluginWire.Value.Bool -> decoded.value
                is PluginWire.Value.Bytes -> Base64.decode(decoded.base64, Base64.NO_WRAP)
                else -> refuse("invalid-argument", "jvm: cannot pass $wire to java")
            }
        }

        private fun encodeValue(value: Any?): String = when (value) {
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
            is String -> {
                val size = value.toByteArray(Charsets.UTF_8).size
                if (size > VALUE_LIMIT_BYTES) tooBig("a string", size.toLong())
                PluginWire.encodeString(value)
            }
            is ByteArray -> {
                if (value.size > VALUE_LIMIT_BYTES) tooBig("a byte[]", value.size.toLong())
                PluginWire.encodeBytes(Base64.encodeToString(value, Base64.NO_WRAP))
            }
            // a `Class` is checked as the class it *names*, or `getClass()` on something out of scope would be checked as `java.lang.Class`
            is Class<*> -> {
                checkClass(value)
                mint(value, KIND_CLASS)
            }
            // and a member by the class it *declares*. The ops that skip [checkMember] can, because a member handle only exists if its declaring class was checked wherever one is minted
            is Member -> {
                checkMember(value)
                mint(value, if (value is Method) KIND_METHOD else if (value is Field) KIND_FIELD else KIND_OBJECT)
            }
            else -> {
                checkClass(value.javaClass)
                mint(value, KIND_OBJECT)
            }
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
            var current: Class<*>? = cls
            while (current != null) {
                for (field in current.declaredFields) {
                    if (field.name != name) continue
                    checkMember(field)
                    field.isAccessible = true
                    return field
                }
                current = current.superclass
            }
            // interface constants are not on the superclass chain
            for (field in cls.fields) {
                if (field.name != name) continue
                checkMember(field)
                field.isAccessible = true
                return field
            }
            refuse("not-found", "jvm: ${cls.name} has no field named $name")
        }

        private fun readField(field: Field, self: Any?): String = encodeValue(field.get(self))

        private fun writeField(field: Field, self: Any?, args: List<Any?>): String {
            if (args.size != 1) refuse("invalid-argument", "jvm: a field takes exactly one value")
            if (Modifier.isFinal(field.modifiers)) {
                refuse("forbidden", "jvm: ${field.declaringClass.name}.${field.name} is final")
            }
            val value = convert(args[0], field.type)
                ?: refuse("invalid-argument", "jvm: cannot assign that to a ${field.type.name}")
            field.set(self, value.value)
            return PluginWire.encodeNull()
        }

        private fun callMethod(cls: Class<*>, self: Any?, name: String, args: List<Any?>): String {
            val method = resolve(cls, name, args, staticOnly = self == null)
            method.isAccessible = true
            return encodeValue(method.invoke(self, *convertAll(method.parameterTypes, args)))
        }

        private fun invokePinned(method: Method, args: List<Any?>): String {
            val self = self(args, 0)
            val rest = args.drop(1)
            if (!matches(method.parameterTypes, rest)) {
                refuse("invalid-argument", "jvm: ${describe(method)} does not take these arguments")
            }
            method.isAccessible = true
            return encodeValue(method.invoke(self, *convertAll(method.parameterTypes, rest)))
        }

        private fun construct(cls: Class<*>, args: List<Any?>): String {
            val candidates = cls.declaredConstructors.filter { matches(it.parameterTypes, args) }
            val ctor = pick(candidates, "${cls.name} constructor", args)
            ctor.isAccessible = true
            return encodeValue(ctor.newInstance(*convertAll(ctor.parameterTypes, args)))
        }

        private fun resolvePinned(cls: Class<*>, name: String): Method {
            val descriptor = descriptorIn(name)
            val simple = simpleName(name)
            val candidates = candidateMethods(cls, simple)
                .filter { descriptor == null || descriptorOf(it) == descriptor }
            if (candidates.isEmpty()) refuse("not-found", "jvm: ${cls.name} has no method named $name")
            if (candidates.size > 1) {
                refuse(
                    "invalid-argument",
                    "jvm: ${cls.name}.$simple is overloaded; pin one with a descriptor, e.g. " +
                        candidates.take(3).joinToString(", ") { "$simple${descriptorOf(it)}" },
                )
            }
            val method = candidates[0]
            checkMember(method)
            return method
        }

        private fun resolve(cls: Class<*>, name: String, args: List<Any?>, staticOnly: Boolean): Method {
            val descriptor = descriptorIn(name)
            val simple = simpleName(name)
            var candidates = candidateMethods(cls, simple)
            if (staticOnly) candidates = candidates.filter { Modifier.isStatic(it.modifiers) }
            if (descriptor != null) {
                candidates = candidates.filter { descriptorOf(it) == descriptor }
                if (candidates.isEmpty()) refuse("not-found", "jvm: ${cls.name} has no method $name")
                // pinning an overload says *which* one, never that the arguments fit: without this `convertAll` turns whatever does not convert into a null and java reports it from somewhere else
                candidates = candidates.filter { matches(it.parameterTypes, args) }
                if (candidates.isEmpty()) {
                    refuse("invalid-argument", "jvm: ${cls.name}.$name does not take these arguments")
                }
            } else {
                candidates = candidates.filter { matches(it.parameterTypes, args) }
            }
            val method = pick(candidates, "${cls.name}.$simple", args)
            checkMember(method)
            return method
        }

        private fun <T : Executable> pick(candidates: List<T>, what: String, args: List<Any?>): T {
            if (candidates.isEmpty()) {
                refuse("not-found", "jvm: no $what takes ${args.size} argument(s) of these types")
            }
            if (candidates.size == 1) return candidates[0]
            val narrowest = candidates.filter { candidate ->
                candidates.none { other -> other !== candidate && moreSpecific(other, candidate) }
            }
            if (narrowest.size != 1) {
                refuse(
                    "invalid-argument",
                    "jvm: $what is ambiguous for these arguments; pin one with a descriptor, e.g. " +
                        candidates.take(3).joinToString(", ") { descriptorOf(it) },
                )
            }
            return narrowest[0]
        }

        private fun candidateMethods(cls: Class<*>, name: String): List<Method> {
            val out = LinkedHashMap<String, Method>()
            var current: Class<*>? = cls
            while (current != null) {
                for (method in current.declaredMethods) {
                    if (method.name != name) continue
                    val key = descriptorOf(method)
                    // the most derived override wins, and an interface default is only reached through `methods` below
                    if (!out.containsKey(key)) out[key] = method
                }
                current = current.superclass
            }
            for (method in cls.methods) {
                if (method.name != name) continue
                val key = descriptorOf(method)
                if (!out.containsKey(key)) out[key] = method
            }
            return out.values.toList()
        }

        private fun mintRunnable(args: Array<String>): String {
            val callbackId = (decodeArg(args.firstOrNull() ?: "N") as? Long)
                ?: refuse("internal", "jvm: runnable without a callback id")
            // not the app's object but one the engine made at the plugin's request. Reaching *into* it is still refused, being in [ENGINE_PACKAGE]
            val id = nextId.getAndIncrement()
            handles[id] = JsRunnable(this, callbackId.toInt())
            return "G$KIND_OBJECT$id"
        }

        /** **posted, never inline**: the reflected call that handed the object over may still be on the stack, inside the engine */
        fun fire(callbackId: Int) {
            // `live` on top of the engine identity: a disposed runnable java kept hold of
            PluginDispatch.onEngine(plugin, engine) { if (live) engine.jvmCallback(callbackId) }
        }

        private fun loadDex(path: String, args: Array<String>): String {
            val file = if (path.isNotEmpty()) fromPath(path) else stage(args)
            val length = file.length()
            if (length == 0L) refuse("invalid-argument", "loadDex: ${file.name} is empty")
            if (length > DEX_LIMIT_BYTES) {
                throw Refusal(
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
            if (bytes.size > DEX_LIMIT_BYTES) {
                refuse("quota-exceeded", "loadDex: ${bytes.size} bytes is over the $DEX_LIMIT_BYTES this api loads")
            }
            val dir = dexDir(plugin.id)
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
            is Double -> fromDouble(value, type)
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
    private fun moreSpecific(a: Executable, b: Executable): Boolean {
        val pa = a.parameterTypes
        val pb = b.parameterTypes
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

    private fun descriptorOf(member: Executable): String {
        val params = member.parameterTypes.joinToString("") { descriptorOf(it) }
        val returns = if (member is Method) descriptorOf(member.returnType) else "V"
        return "($params)$returns"
    }

    private fun descriptorOf(type: Class<*>): String = when {
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
