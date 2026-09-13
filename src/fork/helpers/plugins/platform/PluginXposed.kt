package desu.inugram.helpers.plugins.platform

import desu.inugram.core.plugins.PluginRefusal
import desu.inugram.core.plugins.PluginWire.refuse
import desu.inugram.helpers.plugins.EngineDispatch

import android.util.Log
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.JvmListener
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.XposedListener
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.telegram.messenger.Utilities

/**
 * Kotlin side of `inu.xposed` (rust: `xposed::XposedHost`), per `android.xposed.d.ts`.
 *
 * **Callback registries are rust's; physical ART hooks are shared here.** A `XposedBridge` with `hook0` on it would *be* the grant for anyone
 * holding `unsafe.jvm`, so the property is structural: [Native] is private to this file, and the one
 * class lsplant can reach ([Hooker]) dispatches an existing shared site and has no installation authority.
 *
 * JS and native phases run synchronously on the hooked thread. Rust serializes engine entry;
 * a busy or recursively entered engine bypasses the JS phase. Promise jobs run on the plugin queue.
 * A plugin's own callback phases bypass that plugin only; the original and the other plugins'
 * layers still dispatch. A site cannot mix JS and native hooks within one plugin.
 *
 * Values are [PluginJvm]'s, borrowed through [PluginJvm.ValueBridge] rather than kept twice.
 */
object PluginXposed {
    private const val TAG = "InuPluginXposed"

    /** keep in sync with rust `xposed::NOT_DISPATCHED`: the after phase never ran, so it took nothing */
    private const val NOT_DISPATCHED = "X"

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
     * The hooker only invokes an existing site; it cannot install hooks.
     * Its closure retains the backup even if the last registration is removed mid-call.
     * [callback] must be `public Object callback(Object[])`; that signature is lsplant's.
     */
    internal class Hooker(private val dispatch: (Array<Any?>) -> Any?) {
        @Suppress("unused")
        fun callback(args: Array<Any?>): Any? = dispatch(args)
    }

    private val sharedSites = HashMap<Member, SharedSite>()

    /** lsplant's backup is always a `Method`, a constructor's included: invoking it on a receiver runs the original `<init>` on that object */
    private class SharedSite(val target: Member) {
        @Volatile var backup: Method? = null
        @Volatile var registrations: List<Site> = emptyList()

        fun dispatch(receiver: Any?, args: List<Any?>): Any? {
            val original = backup ?: synchronized(sharedSites) { checkNotNull(backup) }
            val snapshot = registrations
            fun next(index: Int, arguments: List<Any?>): Any? {
                if (index == snapshot.size) return invokeOriginal(original, receiver, arguments)
                val site = snapshot[index]
                return site.session.dispatch(site, receiver, arguments) { next(index + 1, it) }
            }
            return next(0, args)
        }
    }

    private fun invokeOriginal(backup: Method, receiver: Any?, args: List<Any?>): Any? = try {
        backup.invoke(receiver, *args.toTypedArray())
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }

    private fun callMember(member: Member, receiver: Any?, args: Array<Any?>): Any? = when (member) {
        is Method -> {
            member.isAccessible = true
            member.invoke(receiver, *args)
        }
        is Constructor<*> -> {
            member.isAccessible = true
            member.newInstance(*args)
        }
        else -> refuse("invalid-argument", "xposed: callOriginalMethod needs a method or constructor")
    }

    private fun allocateInstance(cls: Class<*>): Any =
        Native.nativeAllocateInstance(cls) ?: refuse("internal", "xposed: could not allocate ${cls.name}")

    /** [jvm] is a hard dependency rather than an implicit grant: every entry point takes a handle only `inu.jvm` mints */
    fun listenerFor(session: PluginSession, jvm: JvmListener?): XposedListener? {
        if (!session.permissions.has(GRANT) || jvm == null) return null
        return Session(session)
    }

    /** an ART entry point stays rewritten, so a site left behind dispatches into an engine that is gone */
    fun detach(engine: QuickJs) {
        (engine.listener?.xposed as? Session)?.close()
    }

    private class NativeHook(val token: String, val before: Any?, val after: Any?)

    private class Site(val session: Session, val id: Long, val shared: SharedSite, val native: Boolean) {
        val target: Member get() = shared.target
        @Volatile var nativeHooks: List<NativeHook> = emptyList()
    }

    private class Session(private val session: PluginSession) : XposedListener {
        private val sites = ConcurrentHashMap<Long, Site>()
        private val nextSite = AtomicLong(1)
        private val nextDispatch = AtomicLong(1)
        private val budgetMs = session.engine.xposedBudgetMs()
        private val dispatching = ThreadLocal<Boolean>()
        @Volatile private var closed = false

        private val values: PluginJvm.ValueBridge
            get() = PluginJvm.bridgeFor(session.engine)
                ?: refuse("internal", "xposed: inu.jvm is not installed")

        override fun xposed(op: Int, target: Long, name: String, args: Array<String>): String = try {
            run(op, target, name, args)
        } catch (e: PluginRefusal) {
            e.wire
        } catch (e: Throwable) {
            values.wireOf(e) ?: PluginWire.encodePluginError("internal", "xposed: ${e.javaClass.simpleName}: ${e.message}")
        }

        private fun run(op: Int, target: Long, name: String, args: Array<String>): String = when (op) {
            OP_HOOK -> PluginWire.encodeString(install(listOf(values.memberAt(target)), args))
            OP_HOOK_ALL -> PluginWire.encodeString(install(overloads(values.classAt(target), name), args))
            OP_UNHOOK -> uninstall(target)
            OP_NATIVE_ADD -> {
                val site = sites[target] ?: refuse("handle-expired", "xposed: hook site is gone")
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
            if (declaring.startsWith(ENGINE_PACKAGE)) {
                refuse("forbidden", "xposed: $declaring is the plugin engine's own bridge")
            }
            // lsplant's generated stub boxes its own primitive arguments through these classes
            // (Integer.valueOf -> new Integer), so a hook on one re-enters the stub before any
            // callback dispatch and overflows the stack on whatever thread boxes next
            if (declaring in BOX_CLASSES) {
                refuse("unsupported", "xposed: $declaring backs primitive boxing, which the hook stub itself uses, so a hook here would recurse until the stack is gone")
            }
        }

        /**
         * One physical ART hook per method, with a session-local site for each plugin.
         * Member.equals merges independent reflective lookups. Rust retains its per-site refcounts.
         */
        private fun installOne(member: Member, native: Boolean): Long {
            val (site, plugins) = synchronized(sharedSites) {
                if (closed) refuse("handle-expired", "xposed: session has closed")
                sites.values.firstOrNull { it.target == member }?.let { return it.id }
                val shared = sharedSites[member] ?: SharedSite(member).also { entry ->
                    val isStatic = java.lang.reflect.Modifier.isStatic(member.modifiers)
                    val hooker = Hooker { args ->
                        // args[0] is the receiver for an instance method; static methods have no placeholder.
                        entry.dispatch(if (isStatic) null else args.firstOrNull(), if (isStatic) args.toList() else args.drop(1))
                    }
                    val callback = Hooker::class.java.getDeclaredMethod("callback", Array<Any?>::class.java)
                    val backup = Native.nativeHook(member, hooker, callback) as? Method
                        ?: refuse("internal", "xposed: lsplant declined to hook $member")
                    backup.isAccessible = true
                    entry.backup = backup
                    sharedSites[member] = entry
                }
                val site = Site(this, nextSite.getAndIncrement(), shared, native)
                sites[site.id] = site
                shared.registrations = shared.registrations + site
                site to shared.registrations.size
            }
            Log.d(TAG, "[${session.manifest.name}] installed xposed site ${site.id}: $member ($plugins plugins)")
            return site.id
        }

        private fun uninstall(site: Long): String {
            val removed = sites.remove(site) ?: return PluginWire.encodeNull()
            val shared = removed.shared
            val declined = synchronized(sharedSites) {
                shared.registrations = shared.registrations.filterNot { it === removed }
                if (shared.registrations.isNotEmpty()) return@synchronized false
                val unhooked = runCatching { Native.nativeUnhook(shared.target) }.getOrElse {
                    Log.e(TAG, "xposed: failed to unhook ${shared.target}", it)
                    false
                }
                if (unhooked || runCatching { !Native.nativeIsHooked(shared.target) }.getOrDefault(false)) {
                    sharedSites.remove(shared.target)
                    false
                } else {
                    true
                }
            }
            if (declined) Log.e(TAG, "xposed: lsplant declined to unhook ${shared.target}; its dispatcher stays and calls the original")
            Log.d(TAG, "[${session.manifest.name}] removed xposed site $site: ${removed.target}")
            return PluginWire.encodeNull()
        }

        /** Resolve the shared backup even when only another plugin registered this method. */
        private fun callOriginal(member: Member, args: Array<String>): String {
            val backup = synchronized(sharedSites) { sharedSites[member]?.backup } ?: return invoke(member, args)
            checkTarget(member)
            return invoke(backup, args, member)
        }

        private fun allocate(cls: Class<*>): String {
            checkTarget(cls.declaredConstructors.firstOrNull() ?: refuse("invalid-argument", "xposed: ${cls.name} has no constructor"))
            return values.encode(allocateInstance(cls))
        }

        /** [target] is the member the plugin named; [member] is what actually runs, its backup when hooked. A hooked constructor answers with the receiver it initialised. */
        private fun invoke(member: Member, args: Array<String>, target: Member = member): String {
            val decoded = args.map { values.decode(it) }
            val parameters = (target as? Executable)?.parameterTypes
                ?: refuse("invalid-argument", "xposed: callOriginalMethod needs a method or constructor")
            val constructing = target is Constructor<*> && member is Method
            val receiver = decoded.firstOrNull() ?: if (constructing) allocateInstance(target.declaringClass) else null
            val rest = PluginJvm.convertArguments(parameters, decoded.drop(1))
                ?: refuse("invalid-argument", "xposed: ${target.name} does not take these arguments")
            return try {
                val result = callMember(member, receiver, rest)
                values.encode(if (constructing) receiver else result)
            } catch (e: InvocationTargetException) {
                // the method's own outcome, handed back as a throwable rather than reported as this bridge failing
                "T" + values.encode(e.targetException)
            }
        }

        /**
         * The bridge's own failures may not escape here: this frame belongs to whichever stock
         * method the user just invoked, so a [PluginRefusal] thrown while encoding an argument would
         * surface as a `RuntimeException` out of app code. A failed layer continues through the
         * remaining plugins instead, eventually reaching the original exactly once.
         */
        fun dispatch(entry: Site, receiver: Any?, args: List<Any?>, next: (List<Any?>) -> Any?): Any? {
            if (closed || sites[entry.id] !== entry) return next(args)
            if (dispatching.get() == true) {
                Log.d(TAG, "[${session.manifest.name}] xposed site ${entry.id} bypassed re-entry")
                return next(args)
            }
            Log.d(TAG, "[${session.manifest.name}] xposed site ${entry.id} dispatching")
            return if (entry.native) dispatchNativeHooks(entry, receiver, args, next)
            else dispatchOnce(entry, receiver, args, next)
        }

        private inline fun <T> runCallbackPhase(block: () -> T): T {
            dispatching.set(true)
            return try { block() } finally { dispatching.remove() }
        }

        private fun dispatchNativeHooks(entry: Site, receiver: Any?, args: List<Any?>, next: (List<Any?>) -> Any?): Any? {
            val site = entry.id
            val hooks = entry.nativeHooks
            val context = PluginHookContext(entry.target, receiver, args.toMutableList())
            fun runPhase(before: Boolean) = runCallbackPhase {
                val deadline = System.nanoTime() + budgetMs * 1_000_000L
                for (hook in hooks) {
                    if (closed || System.nanoTime() >= deadline) break
                    try {
                        when (val phase = if (before) hook.before else hook.after) {
                            is java.util.function.Consumer<*> -> {
                                @Suppress("UNCHECKED_CAST")
                                (phase as java.util.function.Consumer<PluginHookContext>).accept(context)
                            }
                            is Runnable -> phase.run()
                        }
                    } catch (e: Throwable) {
                        Log.d(TAG, "[${session.manifest.name}] native hook at site $site (${entry.target}) failed", e)
                    }
                    if (before && context.answered) break
                }
            }
            return try {
                runPhase(true)
                if (!context.answered) context.outcome = runCatching { next(context.arguments) }
                context.answered = false
                runPhase(false)
                context.outcome.getOrThrow()
            } finally {
                context.close()
            }
        }

        private fun dispatchOnce(entry: Site, receiver: Any?, args: List<Any?>, next: (List<Any?>) -> Any?): Any? {
            val site = entry.id
            // a wire the engine never took stays minted in the reference table with nothing to drop it, so each one is released on the way out
            val minted = ArrayList<String>(args.size + 2)
            val encode = { value: Any? -> values.encode(value).also { minted.add(it) } }
            val request = try {
                runCallbackPhase {
                    Request(encode(entry.target), encode(receiver), args.map(encode).toTypedArray())
                }
            } catch (e: Throwable) {
                Log.e(TAG, "[${session.manifest.name}] xposed site $site (${entry.target}) dispatch failed; continuing", e)
                releaseUntaken(minted)
                return next(args)
            }

            val id = nextDispatch.getAndIncrement()
            var owed = false
            try {
                val before = try {
                    runCallbackPhase { session.engine.xposedBefore(id, site, request.method, request.receiver, request.args) }
                } catch (error: Throwable) {
                    Log.e(TAG, "[${session.manifest.name}] xposed before failed at ${entry.target}; continuing", error)
                    null
                }
                if (before == null) {
                    releaseUntaken(minted)
                    return next(args)
                }
                val wantsAfter = before.firstOrNull() == "P1"
                owed = wantsAfter
                if (before.firstOrNull() == "A") {
                    val answer = answerOf(before.getOrNull(1) ?: PluginWire.encodeNull(), site, "before")
                        ?: return next(args)
                    val converted = try { convertReturn(entry, answer) } catch (error: Throwable) {
                        Log.e(TAG, "[${session.manifest.name}] xposed invalid before result at ${entry.target}; continuing", error)
                        return next(args)
                    }
                    return converted.getOrThrow()
                }

                val callArgs = try {
                    val wires = before.drop(1)
                    require(wires.size == args.size) { "xposed: wrong argument count" }
                    val parameters = (entry.target as Executable).parameterTypes
                    wires.mapIndexed { index, wire ->
                        val original = request.args[index]
                        if (wire == original || original.startsWith("G") && wire == "G" + original.substring(2)) args[index]
                        else requireNotNull(PluginJvm.convertArguments(arrayOf(parameters[index]), listOf(values.decode(wire)))) {
                            "xposed: invalid argument $index"
                        }[0]
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "[${session.manifest.name}] xposed site $site (${entry.target}): unreadable arguments; calling with the app's", e)
                    args
                }
                val outcome = runCatching { next(callArgs) }
                if (!wantsAfter || closed || sites[site] !== entry) return outcome.getOrThrow()

                // the same hand-off the request wires make: the engine owns what it read, and a
                // phase that never ran leaves this one minted with nothing to drop it
                var outcomeWire: String? = null
                val after = try {
                    runCallbackPhase { session.engine.xposedAfter(id, wireOf(outcome).also { outcomeWire = it }) }
                } catch (error: Throwable) {
                    Log.e(TAG, "[${session.manifest.name}] xposed after failed at ${entry.target}; preserving the outcome", error)
                    null
                }
                if (after == null || after == NOT_DISPATCHED) {
                    releaseUntaken(listOfNotNull(outcomeWire))
                    return outcome.getOrThrow()
                }
                if (after == "U") return outcome.getOrThrow()
                val answer = answerOf(after, site, "after") ?: return outcome.getOrThrow()
                val converted = try { convertReturn(entry, answer) } catch (error: Throwable) {
                    Log.e(TAG, "[${session.manifest.name}] xposed invalid after result at ${entry.target}; preserving the outcome", error)
                    outcome
                }
                return converted.getOrThrow()
            } finally {
                if (owed) release(id)
            }
        }

        /** this frame is app code's, so the bridge being gone is one more thing that may not surface here */
        private fun releaseUntaken(wires: List<String>) {
            val bridge = PluginJvm.bridgeFor(session.engine) ?: return
            for (wire in wires) runCatching { bridge.release(wire) }
        }

        private class Request(val method: String, val receiver: String, val args: Array<String>)

        private fun release(id: Long) {
            EngineDispatch.scheduler.postRunnable { session.engine.xposedRelease(id) }
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
                Log.e(TAG, "[${session.manifest.name}] xposed: unreadable $phase answer at site $site (${sites[site]?.target}); wire prefix=${wire.take(when { wire.startsWith("TG") -> 3; wire.startsWith("G") || wire.startsWith("T") -> 2; else -> 1 })}, length=${wire.length}", e)
                null
            }
        }

        private fun convertReturn(entry: Site, answer: Result<Any?>): Result<Any?> = answer.map { value ->
            val type = (entry.target as? Method)?.returnType ?: return@map null
            if (type == Void.TYPE) return@map null
            PluginJvm.convertArguments(arrayOf(type), listOf(value))?.single()
                ?: throw IllegalArgumentException("xposed: cannot return that from ${type.name}")
        }

        fun close() {
            val open = synchronized(sharedSites) {
                closed = true
                sites.keys.toList()
            }
            for (site in open) {
                runCatching { uninstall(site) }.onFailure {
                    Log.e(TAG, "[${session.manifest.name}] failed to remove site $site", it)
                }
            }
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
