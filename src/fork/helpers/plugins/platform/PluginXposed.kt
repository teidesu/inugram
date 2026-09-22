package desu.inugram.helpers.plugins.platform

import desu.inugram.helpers.plugins.SessionResource
import desu.inugram.core.plugins.PluginRefusal
import desu.inugram.core.plugins.PluginWire.refuse
import desu.inugram.helpers.plugins.EngineDispatch

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.JvmListener
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginLog
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
 * Implements `inu.xposed` (Rust: `xposed::XposedHost`), per `android.xposed.d.ts`.
 * Rust owns callback registries; Kotlin shares physical ART hooks across plugins.
 *
 * Keep [Native] private: exposing hook installation would let `unsafe.jvm` bypass the Xposed
 * grant. LSPlant can reach [Hooker], which only dispatches existing sites and cannot install hooks.
 *
 * JS and native phases run synchronously on the hooked thread. Rust serializes engine entry;
 * busy or recursive entry skips JS phases. Promise jobs run on the plugin queue. Recursion
 * suppression applies only to the plugin's callback phases; the original method and other
 * plugins' layers still dispatch. One plugin cannot mix JS and native hooks at a site.
 *
 * Shares values through [PluginJvm.ValueBridge] instead of keeping a second handle table.
 */
object PluginXposed : SessionResource {

    /** keep in sync with rust `xposed::KEEP_ARGUMENT` */
    private const val KEEP_ARGUMENT = "="

    // keep in sync with rust `xposed::OP_*`
    const val OP_HOOK = 0
    const val OP_HOOK_ALL = 1
    const val OP_UNHOOK = 2
    const val OP_CALL_ORIGINAL = 3
    const val OP_ALLOCATE = 4
    const val OP_DISABLE_PROFILE_SAVER = 5
    const val OP_NATIVE_ADD = 6
    const val OP_NATIVE_REMOVE = 7
    const val OP_JS_BEFORES = 8
    const val OP_JS_FILTER = 9

    const val GRANT = "unsafe.xposed"

    /** reached through the C binding `patches-native/lsplant-c-abi.patch` adds. `private` because holding one of these *is* the grant: `nativeHook` rewrites an ART entry point and asks nobody */
    private object Native {
        external fun nativeInit(): Boolean
        external fun nativeHook(target: Member, hooker: Any, callback: Method): Member?
        external fun nativeUnhook(target: Member): Boolean
        external fun nativeIsHooked(target: Member): Boolean
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
        PluginLog.HOST.e("xposed", "load xposed libraries failed", it)
        false
    }.also {
        ready = it
        PluginLog.HOST.d("xposed", "native xposed ${if (it) "ready" else "unavailable"}")
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

        fun dispatch(receiver: Any?, args: Array<Any?>): Any? {
            val original = backup ?: synchronized(sharedSites) { checkNotNull(backup) }
            val snapshot = registrations
            fun next(index: Int, arguments: Array<Any?>): Any? {
                if (index == snapshot.size) return invokeOriginal(original, receiver, arguments)
                val site = snapshot[index]
                return site.session.dispatch(site, receiver, arguments) { next(index + 1, it) }
            }
            return next(0, args)
        }
    }

    private fun invokeOriginal(backup: Method, receiver: Any?, args: Array<Any?>): Any? = try {
        backup.invoke(receiver, *args)
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
    override fun detach(session: PluginSession) {
        (session.engine.listener?.xposed as? Session)?.close()
    }

    private class NativeHook(val token: String, val before: Any?, val after: Any?)

    private class Site(val session: Session, val id: Long, val shared: SharedSite, val native: Boolean) {
        val target: Member get() = shared.target
        @Volatile var nativeHooks: List<NativeHook> = emptyList()
        /** -1 until the engine reports: a site whose hooks are still being registered dispatches both phases */
        @Volatile var jsBefores = -1
        @Volatile var filter: PluginJvmRoutine? = null
        /** the dispatch path's liveness check, so a hooked call needs no lookup in [Session.sites] */
        @Volatile var live = true
    }

    private class Session(private val session: PluginSession) : XposedListener {
        private val sites = ConcurrentHashMap<Long, Site>()
        private val nextSite = AtomicLong(1)
        private val nextDispatch = AtomicLong(1)
        private val budgetMs = session.engine.xposedBudgetMs()
        /** one cell per thread rather than a boxed value: a hooked call reads it and writes it twice */
        private val dispatching = ThreadLocal.withInitial { BooleanArray(1) }
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
            OP_JS_BEFORES -> {
                sites[target]?.jsBefores = name.toInt()
                PluginWire.encodeNull()
            }
            OP_JS_FILTER -> {
                val site = sites[target] ?: refuse("handle-expired", "xposed: hook site is gone")
                site.filter = values.decode(name) as? PluginJvmRoutine
                    ?: refuse("invalid-argument", "xposed: filter must be an inu.jvm.routine")
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
            if (PluginJvm.isEnginePackage(declaring)) {
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
                        if (isStatic) entry.dispatch(null, args)
                        else entry.dispatch(args[0], args.copyOfRange(1, args.size))
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
            session.log.d("xposed", "installed xposed site ${site.id}: $member ($plugins plugins)")
            return site.id
        }

        private fun uninstall(site: Long): String {
            val removed = sites.remove(site) ?: return PluginWire.encodeNull()
            removed.live = false
            val shared = removed.shared
            val declined = synchronized(sharedSites) {
                shared.registrations = shared.registrations.filterNot { it === removed }
                if (shared.registrations.isNotEmpty()) return@synchronized false
                val unhooked = runCatching { Native.nativeUnhook(shared.target) }.getOrElse {
                    session.log.e("xposed", "failed to unhook ${shared.target}", it)
                    false
                }
                if (unhooked || runCatching { !Native.nativeIsHooked(shared.target) }.getOrDefault(false)) {
                    sharedSites.remove(shared.target)
                    false
                } else {
                    true
                }
            }
            if (declined) session.log.e("xposed", "lsplant declined to unhook ${shared.target}; its dispatcher stays and calls the original")
            session.log.d("xposed", "removed xposed site $site: ${removed.target}")
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
        fun dispatch(entry: Site, receiver: Any?, args: Array<Any?>, next: (Array<Any?>) -> Any?): Any? {
            if (closed || !entry.live) return next(args)
            val guard = dispatching.get()
            if (guard[0]) {
                session.log.d("xposed", "site ${entry.id} bypassed re-entry")
                return next(args)
            }
            val filter = entry.filter
            if (filter != null && !runCallbackPhase(guard) { filter.decide(receiver, args) }) return next(args)
            return when {
                entry.native -> dispatchNativeHooks(entry, guard, receiver, args, next)
                entry.jsBefores == 0 -> dispatchAfterOnly(entry, guard, receiver, args, next)
                else -> dispatchOnce(entry, guard, receiver, args, next)
            }
        }

        private inline fun <T> runCallbackPhase(guard: BooleanArray, block: () -> T): T {
            guard[0] = true
            return try { block() } finally { guard[0] = false }
        }

        private fun dispatchNativeHooks(entry: Site, guard: BooleanArray, receiver: Any?, args: Array<Any?>, next: (Array<Any?>) -> Any?): Any? {
            val site = entry.id
            val hooks = entry.nativeHooks
            val context = PluginHookContext(entry.target, receiver, args.toMutableList())
            fun runPhase(before: Boolean) = runCallbackPhase(guard) {
                val deadline = System.nanoTime() + budgetMs * 1_000_000L
                for (hook in if (before) hooks else hooks.asReversed()) {
                    if (closed || !entry.live || System.nanoTime() >= deadline) break
                    try {
                        when (val phase = if (before) hook.before else hook.after) {
                            is java.util.function.Consumer<*> -> {
                                @Suppress("UNCHECKED_CAST")
                                (phase as java.util.function.Consumer<PluginHookContext>).accept(context)
                            }
                            is Runnable -> phase.run()
                        }
                    } catch (e: Throwable) {
                        session.log.w("xposed", "native hook at site $site (${entry.target}) failed", e)
                    }
                    if (before && context.answered) break
                }
            }
            return try {
                runPhase(true)
                if (!context.answered) context.outcome = runCatching { next(context.arguments.toTypedArray()) }
                context.answered = false
                runPhase(false)
                context.outcome.getOrThrow()
            } finally {
                context.close()
            }
        }

        private fun dispatchOnce(entry: Site, guard: BooleanArray, receiver: Any?, args: Array<Any?>, next: (Array<Any?>) -> Any?): Any? {
            val site = entry.id
            val id = nextDispatch.getAndIncrement()
            var owed = false
            try {
                val before = try {
                    runCallbackPhase(guard) { session.engine.xposedBefore(id, site, invocationOf(entry, receiver, args)) }
                } catch (error: Throwable) {
                    session.log.e("xposed", "before failed at ${entry.target}; continuing", error)
                    null
                }
                if (before == null) return next(args)
                val wantsAfter = before.firstOrNull() == "P1"
                owed = wantsAfter
                if (before.firstOrNull() == "A") {
                    val answer = answerOf(before.getOrNull(1) ?: PluginWire.encodeNull(), site, "before")
                        ?: return next(args)
                    val converted = try { convertReturn(entry, answer) } catch (error: Throwable) {
                        session.log.e("xposed", "invalid before result at ${entry.target}; continuing", error)
                        return next(args)
                    }
                    return converted.getOrThrow()
                }

                val callArgs = try {
                    require(before.size - 1 == args.size) { "xposed: wrong argument count" }
                    val parameters = (entry.target as Executable).parameterTypes
                    Array<Any?>(args.size) { index ->
                        val wire = before[index + 1]
                        if (wire == KEEP_ARGUMENT) args[index]
                        else requireNotNull(PluginJvm.convertArguments(arrayOf(parameters[index]), listOf(values.decode(wire)))) {
                            "xposed: invalid argument $index"
                        }[0]
                    }
                } catch (e: Throwable) {
                    session.log.e("xposed", "site $site (${entry.target}): unreadable arguments; calling with the app's", e)
                    args
                }
                var thrown: Throwable? = null
                var value: Any? = null
                try { value = next(callArgs) } catch (e: Throwable) { thrown = e }
                if (!wantsAfter || closed || !entry.live) return settle(value, thrown)

                val settled = settledInvocationOf(entry, receiver, args, value, thrown)
                val after = try {
                    runCallbackPhase(guard) { session.engine.xposedAfter(id, settled, thrown != null) }
                } catch (error: Throwable) {
                    session.log.e("xposed", "after failed at ${entry.target}; preserving the outcome", error)
                    QuickJs.NOT_DISPATCHED
                }
                if (after != QuickJs.NOT_DISPATCHED) owed = false
                return settleAfter(entry, value, thrown, after)
            } finally {
                if (owed) release(id)
            }
        }

        private fun dispatchAfterOnly(entry: Site, guard: BooleanArray, receiver: Any?, args: Array<Any?>, next: (Array<Any?>) -> Any?): Any? {
            var thrown: Throwable? = null
            var value: Any? = null
            try { value = next(args) } catch (e: Throwable) { thrown = e }
            if (closed || !entry.live) return settle(value, thrown)
            val invocation = settledInvocationOf(entry, receiver, args, value, thrown)
            val after = try {
                runCallbackPhase(guard) { session.engine.xposedAfterOnly(entry.id, invocation, thrown != null) }
            } catch (error: Throwable) {
                session.log.e("xposed", "after failed at ${entry.target}; preserving the outcome", error)
                null
            }
            return settleAfter(entry, value, thrown, after)
        }

        private fun settle(value: Any?, thrown: Throwable?): Any? = if (thrown != null) throw thrown else value

        private fun settleAfter(entry: Site, value: Any?, thrown: Throwable?, after: String?): Any? {
            if (after == null || after == QuickJs.NOT_DISPATCHED) return settle(value, thrown)
            val answer = answerOf(after, entry.id, "after") ?: return settle(value, thrown)
            val converted = try { convertReturn(entry, answer) } catch (error: Throwable) {
                session.log.e("xposed", "invalid after result at ${entry.target}; preserving the outcome", error)
                return settle(value, thrown)
            }
            return converted.getOrThrow()
        }

        private fun invocationOf(entry: Site, receiver: Any?, args: Array<Any?>, tail: Int = 0): Array<Any?> {
            val invocation = arrayOfNulls<Any?>(args.size + 2 + tail)
            invocation[0] = entry.target
            invocation[1] = receiver
            System.arraycopy(args, 0, invocation, 2, args.size)
            return invocation
        }

        private fun settledInvocationOf(
            entry: Site,
            receiver: Any?,
            args: Array<Any?>,
            value: Any?,
            thrown: Throwable?,
        ): Array<Any?> = invocationOf(entry, receiver, args, tail = 1).also { it[it.size - 1] = thrown ?: value }

        private fun release(id: Long) {
            EngineDispatch.scheduler.postRunnable { session.engine.xposedRelease(id) }
        }

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
                session.log.e("xposed", "unreadable $phase answer at site $site (${sites[site]?.target}); wire prefix=${wire.take(when { wire.startsWith("TG") -> 3; wire.startsWith("G") || wire.startsWith("T") -> 2; else -> 1 })}, length=${wire.length}", e)
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
                    session.log.e("xposed", "failed to remove site $site", it)
                }
            }
        }
    }

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

    internal fun isHooked(method: Member): Boolean = ensureReady() && Native.nativeIsHooked(method)
}
