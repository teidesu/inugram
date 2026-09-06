package desu.inugram.helpers.plugins.platform

import android.util.Log
import desu.inugram.core.plugins.ScopeMatch
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.JvmListener
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.XposedListener
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.telegram.messenger.Utilities

/**
 * Kotlin side of `inu.xposed` (rust: `xposed::XposedHost`), per `android.xposed.d.ts`.
 *
 * **The registry is rust's.** A `XposedBridge` with `hook0` on it would *be* the grant for anyone
 * holding `unsafe.jvm`, so the property is structural: [Native] is private to this file, and the one
 * class lsplant can reach ([Hooker]) carries a rust-minted site id and no authority of its own.
 *
 * JS and native phases run synchronously on the hooked thread. Rust serializes engine entry;
 * a busy or recursively entered engine bypasses the JS phase. Promise jobs run on globalQueue.
 * Both modes bypass recursive dispatch. A site cannot mix JS and native hooks within one plugin.
 *
 * Values are [PluginJvm]'s, borrowed through [PluginJvm.ValueBridge] rather than kept twice.
 */
object PluginXposed {
    private const val TAG = "InuPluginXposed"

    // keep in sync with rust `xposed::OP_*` and `xposed.js`
    const val OP_HOOK = 0
    const val OP_HOOK_ALL = 1
    const val OP_UNHOOK = 2
    const val OP_CALL_ORIGINAL = 3
    const val OP_ALLOCATE = 4
    const val OP_DISABLE_PROFILE_SAVER = 5
    const val OP_NATIVE_ADD = 6
    const val OP_NATIVE_REMOVE = 7

    const val GRANT = "unsafe.xposed"

    /** reached through the C binding `patches-native/lsplant-c-abi.patch` adds. `private` because holding one of these *is* the grant: `nativeHook` rewrites an ART entry point and asks nobody */
    private object Native {
        external fun nativeInit(): Boolean
        external fun nativeHook(target: Member, hooker: Any, callback: Method): Member?
        external fun nativeUnhook(target: Member): Boolean
        external fun nativeIsHooked(target: Member): Boolean
        external fun nativeDeoptimize(method: Member): Boolean
        external fun nativeMakeInheritable(target: Class<*>): Boolean
        external fun nativeAllocateInstance(target: Class<*>): Any?
        external fun nativeDisableProfileSaver(): Boolean
    }

    /** set at first use rather than at boot: `nativeInit` prefetches ART symbols and installs hooks of its own, a cost no plugin should pay for unasked */
    private var ready: Boolean? = null

    @Synchronized
    private fun ensureReady(): Boolean = ready ?: runCatching {
        System.loadLibrary("lsplant")
        Native.nativeInit()
    }.getOrElse {
        Log.e(TAG, "load xposed libraries failed", it)
        false
    }.also {
        ready = it
        Log.d(TAG, "native xposed ${if (it) "ready" else "unavailable"}")
    }

    /**
     * Everything about it that matters is what it is *not*: it holds no callback list, decides
     * nothing, and its [site] is a rust-minted token, so reaching this class buys the ability to run
     * a hook that already exists - which calling the hooked method would have done anyway.
     *
     * [callback] must be `public Object callback(Object[])`; that signature is lsplant's.
     */
    internal class Hooker(private val engine: QuickJs, private val site: Long, private val isStatic: Boolean) {
        @Suppress("unused")
        fun callback(args: Array<Any?>): Any? {
            // args[0] is the receiver for an instance method and there is no placeholder for a static one, so the split is the method's shape rather than the array's
            val session = engine.listener?.xposed as? Session ?: return null
            val receiver = if (isStatic) null else args.firstOrNull()
            val rest = if (isStatic) args.toList() else args.drop(1)
            return session.dispatch(site, receiver, rest)
        }
    }

    /** [jvm] is a hard dependency rather than an implicit grant: every entry point takes a handle only `inu.jvm` mints */
    fun listenerFor(plugin: Plugin, engine: QuickJs, jvm: JvmListener?): XposedListener? {
        if (!plugin.permissions.has(GRANT) || jvm == null) return null
        return Session(plugin, engine)
    }

    /** an ART entry point stays rewritten, so a site left behind dispatches into an engine that is gone */
    fun detach(engine: QuickJs) {
        (engine.listener?.xposed as? Session)?.close()
    }

    private class Refusal(val wire: String) : RuntimeException(PluginWire.describePluginError(wire), null, false, false)

    private fun refuse(code: String, message: String, grant: String? = null): Nothing =
        throw Refusal(PluginWire.encodePluginError(code, message, grant = grant))

    private class NativeHook(val token: String, val before: Any?, val after: Any?)

    private class Site(val target: Member, val backup: Member, val native: Boolean) {
        @Volatile var nativeHooks: List<NativeHook> = emptyList()
    }

    private class Session(private val plugin: Plugin, private val engine: QuickJs) : XposedListener {
        // Dispatch may overlap installation/removal on another thread.
        private val sites = ConcurrentHashMap<Long, Site>()
        private val nextSite = AtomicLong(1)
        private val nextDispatch = AtomicLong(1)
        private val budgetMs = engine.xposedBudgetMs()
        private val dispatching = ThreadLocal<Boolean>()

        private val values: PluginJvm.ValueBridge
            get() = PluginJvm.bridgeFor(engine)
                ?: refuse("internal", "xposed: inu.jvm is not installed")

        override fun xposed(op: Int, target: Long, name: String, args: Array<String>): String = try {
            run(op, target, name, args)
        } catch (e: Refusal) {
            e.wire
        } catch (e: Throwable) {
            values.wireOf(e) ?: PluginWire.encodePluginError("internal", "xposed: ${e.javaClass.simpleName}: ${e.message}")
        }

        private fun run(op: Int, target: Long, name: String, args: Array<String>): String = when (op) {
            OP_HOOK -> PluginWire.encodeString(install(listOf(values.memberAt(target)), args))
            OP_HOOK_ALL -> PluginWire.encodeString(install(overloads(values.classAt(target), name), args))
            OP_UNHOOK -> uninstall(target)
            OP_NATIVE_ADD -> {
                val site = sites[target] ?: refuse("expired-handle", "xposed: hook site is gone")
                if (!site.native) refuse("invalid-argument", "xposed: cannot add a native phase to a JS hook site")
                val (before, after) = readNativeHooks(args)
                if (site.nativeHooks.any { it.token == name }) refuse("invalid-argument", "xposed: duplicate native hook")
                site.nativeHooks = site.nativeHooks + NativeHook(name, before, after)
                PluginWire.encodeNull()
            }
            OP_NATIVE_REMOVE -> {
                sites[target]?.let { site ->
                    site.nativeHooks = site.nativeHooks.filterNot { it.token == name }
                }
                PluginWire.encodeNull()
            }
            OP_CALL_ORIGINAL -> callOriginal(values.memberAt(target), args)
            OP_ALLOCATE -> allocate(values.classAt(target))
            OP_DISABLE_PROFILE_SAVER -> values.encode(ensureReady() && Native.nativeDisableProfileSaver())
            else -> refuse("invalid-argument", "xposed: unknown op $op")
        }

        /** declared rather than inherited: hooking a method the class did not declare would rewrite the superclass's entry point, which is every subclass at once */
        private fun overloads(cls: Class<*>, name: String): List<Member> {
            val found: List<Member> = if (name.isEmpty()) {
                cls.declaredConstructors.toList()
            } else {
                cls.declaredMethods.filter { it.name == name }
            }
            if (found.isEmpty()) {
                refuse("not-found", "xposed: ${cls.name} declares no ${if (name.isEmpty()) "constructor" else name}")
            }
            return found
        }

        private fun readNativeHooks(args: Array<String>): Pair<Any?, Any?> {
            if (args.size != 2) refuse("invalid-argument", "xposed: expected two native phases")
            fun read(wire: String): Any? {
                val value = values.decode(wire) ?: return null
                if (value !is Runnable && value !is java.util.function.Consumer<*>) {
                    refuse("invalid-argument", "xposed: phase must implement Runnable or Consumer")
                }
                return value
            }
            val before = read(args[0])
            val after = read(args[1])
            if (before == null && after == null) refuse("invalid-argument", "xposed: no native phases")
            return before to after
        }

        private fun install(targets: List<Member>, args: Array<String>): String {
            val native = args.isNotEmpty()
            if (native) readNativeHooks(args)
            for (member in targets) {
                checkTarget(member)
                if (sites.values.any { it.target == member && it.native != native }) {
                    refuse("invalid-argument", "xposed: cannot mix native phases and JS hooks on the same method")
                }
            }
            if (!ensureReady()) {
                refuse("unsupported", "xposed: method hooking is unavailable on this device")
            }
            val installed = ArrayList<Long>(targets.size)
            val previous = sites.keys.toSet()
            try {
                for (member in targets) installed.add(installOne(member, native))
            } catch (e: Throwable) {
                for (site in installed) if (site !in previous) uninstall(site)
                throw e
            }
            return installed.joinToString(",")
        }

        private fun checkTarget(member: Member) {
            val declaring = member.declaringClass.name
            // Do not expose the engine bridge through hooks.
            if (declaring.startsWith(ENGINE_PACKAGE)) {
                refuse("forbidden", "xposed: $declaring is the plugin engine's own bridge")
            }
            // lsplant's generated stub boxes its own primitive arguments through these classes
            // (Integer.valueOf -> new Integer), so a hook on one re-enters the stub before any
            // callback dispatch and overflows the stack on whatever thread boxes next
            if (declaring in BOX_CLASSES) {
                refuse("unsupported", "xposed: $declaring backs primitive boxing, which the hook stub itself uses, so a hook here would recurse until the stack is gone")
            }
            if (!plugin.permissions.allows(GRANT, declaring, ScopeMatch.NAMESPACE)) {
                refuse("not-granted", "xposed: $declaring is not in this plugin's $GRANT scope list", "$GRANT($declaring)")
            }
        }

        /**
         * One ART method is one site, however many hooks stand on it. A second `nativeHook` over an
         * already-hooked method is undefined behaviour in lsplant (the second backup points into the
         * first trampoline), and rust refcounts *per site*, which a fresh id per registration would
         * turn into an unhook after the first. `Member.equals` is value-based, so two reflective
         * lookups of one method answer the same key.
         */
        private fun installOne(member: Member, native: Boolean): Long {
            sites.entries.firstOrNull { it.value.target == member }?.let { return it.key }
            val site = nextSite.getAndIncrement()
            val isStatic = java.lang.reflect.Modifier.isStatic(member.modifiers)
            val hooker = Hooker(engine, site, isStatic)
            val callback = Hooker::class.java.getDeclaredMethod("callback", Array<Any?>::class.java)
            val backup = Native.nativeHook(member, hooker, callback)
                ?: refuse("internal", "xposed: lsplant declined to hook $member")
            (backup as? java.lang.reflect.AccessibleObject)?.isAccessible = true
            sites[site] = Site(member, backup, native)
            Log.d(TAG, "[${plugin.manifest.name}] installed xposed site $site: $member")
            return site
        }

        private fun uninstall(site: Long): String {
            val removed = sites.remove(site) ?: return PluginWire.encodeNull()
            Native.nativeUnhook(removed.target)
            Log.d(TAG, "[${plugin.manifest.name}] removed xposed site $site: ${removed.target}")
            return PluginWire.encodeNull()
        }

        /** its backup when this plugin hooked the method, and the method itself when it did not - the same call either way for the caller */
        private fun callOriginal(member: Member, args: Array<String>): String {
            val backup = sites.values.firstOrNull { it.target == member }?.backup
            return invoke(backup ?: member, args)
        }

        private fun allocate(cls: Class<*>): String {
            checkTarget(cls.declaredConstructors.firstOrNull() ?: refuse("invalid-argument", "xposed: ${cls.name} has no constructor"))
            return Native.nativeAllocateInstance(cls)?.let(values::encode)
                ?: refuse("internal", "xposed: could not allocate ${cls.name}")
        }

        private fun invoke(member: Member, args: Array<String>): String {
            val decoded = args.map { values.decode(it) }
            val receiver = decoded.firstOrNull()
            val parameters = when (member) {
                is Method -> member.parameterTypes
                is java.lang.reflect.Constructor<*> -> member.parameterTypes
                else -> refuse("invalid-argument", "xposed: callOriginalMethod needs a method or constructor")
            }
            val rest = PluginJvm.convertArguments(parameters, decoded.drop(1))
                ?: refuse("invalid-argument", "xposed: ${member.name} does not take these arguments")
            return try {
                values.encode(when (member) {
                    is Method -> {
                        member.isAccessible = true
                        member.invoke(receiver, *rest)
                    }
                    is java.lang.reflect.Constructor<*> -> {
                        member.isAccessible = true
                        member.newInstance(*rest)
                    }
                    else -> error("unreachable")
                })
            } catch (e: InvocationTargetException) {
                // the method's own outcome, handed back as a throwable rather than reported as this bridge failing
                "T" + values.encode(e.targetException)
            }
        }

        /**
         * The bridge's own failures may not escape here: this frame belongs to whichever stock
         * method the user just invoked, so a [Refusal] thrown while encoding an argument would
         * surface as a message-less `RuntimeException` out of app code. It falls back to the
         * original instead, as rust already does for a site whose hooks are gone.
         */
        fun dispatch(site: Long, receiver: Any?, args: List<Any?>): Any? {
            // Keep recursive calls to hooked methods on their original path.
            if (dispatching.get() == true) {
                Log.d(TAG, "[${plugin.manifest.name}] xposed site $site bypassed re-entry")
                return runOriginal(site, receiver, args, originalArgs = true)
            }
            dispatching.set(true)
            return try {
                Log.d(TAG, "[${plugin.manifest.name}] xposed site $site dispatching")
                if (sites[site]?.native == true) dispatchNativeHooks(site, receiver, args)
                else dispatchOnce(site, receiver, args)
            } finally {
                dispatching.remove()
            }
        }

        private fun dispatchNativeHooks(site: Long, receiver: Any?, args: List<Any?>): Any? {
            val entry = sites[site] ?: return runOriginal(site, receiver, args, originalArgs = true)
            val hooks = entry.nativeHooks
            val context = PluginHookContext(entry.target, receiver, args.toMutableList())
            fun runPhase(before: Boolean) {
                val deadline = System.nanoTime() + budgetMs * 1_000_000L
                for (hook in hooks) {
                    if (System.nanoTime() >= deadline) break
                    try {
                        when (val phase = if (before) hook.before else hook.after) {
                            is java.util.function.Consumer<*> -> {
                                @Suppress("UNCHECKED_CAST")
                                (phase as java.util.function.Consumer<PluginHookContext>).accept(context)
                            }
                            is Runnable -> phase.run()
                        }
                    } catch (e: Throwable) {
                        Log.d(TAG, "[${plugin.manifest.name}] native hook failed", e)
                    }
                    if (before && context.answered) break
                }
            }
            return try {
                runPhase(true)
                if (!context.answered) context.outcome = runCatching { runOriginal(site, receiver, context.arguments, originalArgs = true) }
                context.answered = false
                runPhase(false)
                context.outcome.getOrThrow()
            } finally {
                context.close()
            }
        }

        private fun dispatchOnce(site: Long, receiver: Any?, args: List<Any?>): Any? {
            val request = try {
                Request(
                    values.encode(sites[site]?.target),
                    values.encode(receiver),
                    args.map { values.encode(it) }.toTypedArray(),
                )
            } catch (e: Throwable) {
                return unhooked(e, site, receiver, args)
            }

            val id = nextDispatch.getAndIncrement()
            val before = engine.xposedBefore(id, site, request.method, request.receiver, request.args)
            if (before == null) {
                // Admission failed or the engine has stopped accepting callbacks.
                release(id)
                return runOriginal(site, receiver, args, originalArgs = true)
            }
            val wantsAfter = before.firstOrNull() == "P1"
            if (before.firstOrNull() == "A") {
                val answer = answerOf(before.getOrNull(1) ?: PluginWire.encodeNull(), site, "before")
                    ?: return runOriginal(site, receiver, args)
                return returnValue(site, answer)
            }

            val callArgs = try {
                before.drop(1).map { values.decode(it) }
            } catch (e: Throwable) {
                Log.e(TAG, "[${plugin.manifest.name}] xposed site $site (${sites[site]?.target}): unreadable arguments; calling with the app's", e)
                args
            }
            val outcome = runCatching { runOriginal(site, receiver, callArgs) }
            if (!wantsAfter) return outcome.getOrThrow()

            // the phase is owed a call however this goes, or its GC roots outlive the dispatch
            val wire = try {
                wireOf(outcome)
            } catch (e: Throwable) {
                Log.e(TAG, "[${plugin.manifest.name}] xposed site $site (${sites[site]?.target}): the result does not cross; skipping the after phase", e)
                release(id)
                return outcome.getOrThrow()
            }
            val after = engine.xposedAfter(id, wire)
            if (after == null || after == "U") {
                release(id)
                return outcome.getOrThrow()
            }
            val answer = answerOf(after, site, "after") ?: outcome
            release(id)
            return returnValue(site, answer)
        }

        private class Request(val method: String, val receiver: String, val args: Array<String>)

        private fun release(id: Long) {
            Utilities.globalQueue.postRunnable { engine.xposedRelease(id) }
        }

        private fun wireOf(outcome: Result<Any?>): String =
            outcome.fold({ values.encode(it) }, { "T" + values.encode(it) })

        /** a hook answering with a throwable is the plugin's decision and the app's to receive, while a wire this side could not decode is ours and may not surface in app code as one */
        private fun answerOf(wire: String, site: Long, phase: String): Result<Any?>? {
            val thrown = wire.removePrefix("T")
            return try {
                if (thrown.length != wire.length) {
                    Result.failure(
                        values.decode(thrown) as? Throwable
                            ?: RuntimeException("a plugin hook answered with something that is not a throwable"),
                    )
                } else {
                    Result.success(values.decode(wire))
                }
            } catch (e: Throwable) {
                Log.e(TAG, "[${plugin.manifest.name}] xposed: unreadable $phase answer at site $site (${sites[site]?.target}); wire prefix=${wire.take(when { wire.startsWith("TG") -> 3; wire.startsWith("G") || wire.startsWith("T") -> 2; else -> 1 })}, length=${wire.length}", e)
                null
            }
        }

        private fun returnValue(site: Long, answer: Result<Any?>): Any? = answer.map { value ->
            val type = (sites[site]?.backup as? Method)?.returnType ?: return@map null
            if (type == Void.TYPE) return@map null
            PluginJvm.convertArguments(arrayOf(type), listOf(value))?.single()
                ?: throw IllegalArgumentException("xposed: cannot return that from ${type.name}")
        }.getOrThrow()

        private fun unhooked(cause: Throwable, site: Long, receiver: Any?, args: List<Any?>): Any? {
            Log.e(TAG, "[${plugin.manifest.name}] xposed site $site (${sites[site]?.target}) dispatch failed; running the original", cause)
            return runOriginal(site, receiver, args, originalArgs = true)
        }

        private fun runOriginal(site: Long, receiver: Any?, args: List<Any?>, originalArgs: Boolean = false): Any? {
            val backup = sites[site]?.backup ?: return null
            return try {
                when (backup) {
                    is Method -> {
                        val converted = if (originalArgs) args.toTypedArray() else PluginJvm.convertArguments(backup.parameterTypes, args)
                            ?: throw IllegalArgumentException("xposed: ${backup.name} does not take these arguments")
                        backup.isAccessible = true
                        backup.invoke(receiver, *converted)
                    }
                    is java.lang.reflect.Constructor<*> -> {
                        val converted = if (originalArgs) args.toTypedArray() else PluginJvm.convertArguments(backup.parameterTypes, args)
                            ?: throw IllegalArgumentException("xposed: constructor does not take these arguments")
                        backup.isAccessible = true
                        backup.newInstance(*converted)
                    }
                    else -> null
                }
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }

        fun close() {
            for (site in sites.values) {
                runCatching { Native.nativeUnhook(site.target) }
            }
            sites.clear()
        }
    }

    private const val ENGINE_PACKAGE = "desu.inugram.helpers.plugins."

    private val BOX_CLASSES = setOf(
        "java.lang.Boolean",
        "java.lang.Byte",
        "java.lang.Character",
        "java.lang.Short",
        "java.lang.Integer",
        "java.lang.Long",
        "java.lang.Float",
        "java.lang.Double",
    )

    internal fun deoptimize(method: Member): Boolean = ensureReady() && Native.nativeDeoptimize(method)

    internal fun makeInheritable(cls: Class<*>): Boolean = ensureReady() && Native.nativeMakeInheritable(cls)

    internal fun isHooked(method: Member): Boolean = ensureReady() && Native.nativeIsHooked(method)
}
