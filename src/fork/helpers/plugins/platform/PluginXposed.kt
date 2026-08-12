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
 * **A dispatch parks the calling thread; it does not enter the engine on it.** lsplant's callback
 * runs on whichever app thread called the hooked method, where entering an engine races every
 * queue-confined bridge map and re-entering one is a `BorrowMutError` abort. So [Session.dispatch]
 * posts `before`, waits [budgetMs], **calls the original itself** (a hooked method may be one only
 * the ui thread may run), and posts `after`; past the budget the original runs as the app called it.
 * A dispatch reached from *inside* plugin code is already on globalQueue and skips the hooks.
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

    private class Refusal(val wire: String) : RuntimeException(null, null, false, false)

    private fun refuse(code: String, message: String, grant: String? = null): Nothing =
        throw Refusal(PluginWire.encodePluginError(code, message, grant = grant))

    private class Site(val target: Member, val backup: Member)

    private class Session(private val plugin: Plugin, private val engine: QuickJs) : XposedListener {
        // concurrent because [dispatch] reads this on whichever thread called the hooked method, while install/remove run on globalQueue
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
            OP_HOOK -> PluginWire.encodeString(install(listOf(values.memberAt(target))))
            OP_HOOK_ALL -> PluginWire.encodeString(install(overloads(values.classAt(target), name)))
            OP_UNHOOK -> uninstall(target)
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

        private fun install(targets: List<Member>): String {
            if (!ensureReady()) {
                refuse("unsupported", "xposed: method hooking is unavailable on this device")
            }
            val installed = ArrayList<Long>(targets.size)
            for (member in targets) {
                checkTarget(member)
                installed.add(installOne(member))
            }
            return installed.joinToString(",")
        }

        private fun checkTarget(member: Member) {
            val declaring = member.declaringClass.name
            // hooking into the engine's own package would re-enter this engine from inside a JNI upcall, which is a process abort rather than an error
            if (declaring.startsWith(ENGINE_PACKAGE)) {
                refuse("forbidden", "xposed: $declaring is the plugin engine's own bridge")
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
        private fun installOne(member: Member): Long {
            sites.entries.firstOrNull { it.value.target == member }?.let { return it.key }
            val site = nextSite.getAndIncrement()
            val isStatic = java.lang.reflect.Modifier.isStatic(member.modifiers)
            val hooker = Hooker(engine, site, isStatic)
            val callback = Hooker::class.java.getDeclaredMethod("callback", Array<Any?>::class.java)
            val backup = Native.nativeHook(member, hooker, callback)
                ?: refuse("internal", "xposed: lsplant declined to hook $member")
            (backup as? java.lang.reflect.AccessibleObject)?.isAccessible = true
            sites[site] = Site(member, backup)
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
            // already inside the engine's queue: this is a hooked method plugin code reached, and parking here would be parking on ourselves
            if (Utilities.globalQueue as Any === Thread.currentThread() || dispatching.get() == true) {
                Log.d(TAG, "[${plugin.manifest.name}] xposed site $site bypassed re-entry")
                return runOriginal(site, receiver, args, originalArgs = true)
            }
            dispatching.set(true)
            return try {
                Log.d(TAG, "[${plugin.manifest.name}] xposed site $site dispatching")
                dispatchOnce(site, receiver, args)
            } finally {
                dispatching.remove()
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
            val before = await { engine.xposedBefore(id, site, request.method, request.receiver, request.args) }
            if (before == null) {
                // the queued phase still finishes and may have parked after callbacks under this id
                release(id)
                return runOriginal(site, receiver, args, originalArgs = true)
            }
            val wantsAfter = before.firstOrNull() == "P1"
            if (before.firstOrNull() == "A") {
                val answer = answerOf(before.getOrNull(1) ?: PluginWire.encodeNull())
                    ?: return runOriginal(site, receiver, args)
                return returnValue(site, answer)
            }

            val callArgs = try {
                before.drop(1).map { values.decode(it) }
            } catch (e: Throwable) {
                Log.e(TAG, "[${plugin.manifest.name}] xposed: unreadable arguments; calling with the app's", e)
                args
            }
            val outcome = runCatching { runOriginal(site, receiver, callArgs) }
            if (!wantsAfter) return outcome.getOrThrow()

            // the phase is owed a call however this goes, or its GC roots outlive the dispatch
            val wire = try {
                wireOf(outcome)
            } catch (e: Throwable) {
                Log.e(TAG, "[${plugin.manifest.name}] xposed: the result does not cross; skipping the after phase", e)
                release(id)
                return outcome.getOrThrow()
            }
            val after = await { engine.xposedAfter(id, wire) }
            if (after == null) {
                release(id)
                return outcome.getOrThrow()
            }
            val answer = answerOf(after) ?: outcome
            release(id)
            return returnValue(site, answer)
        }

        private class Request(val method: String, val receiver: String, val args: Array<String>)

        /** a phase that started still finishes and its writes still land; what expires is this thread's willingness to wait, not the work */
        private fun <T> await(phase: () -> T): T? {
            val latch = CountDownLatch(1)
            val answer = AtomicReference<T?>(null)
            Utilities.globalQueue.postRunnable {
                try {
                    answer.set(phase())
                } finally {
                    latch.countDown()
                }
            }
            if (!latch.await(budgetMs, TimeUnit.MILLISECONDS)) return null
            return answer.get()
        }

        private fun release(id: Long) {
            Utilities.globalQueue.postRunnable { engine.xposedRelease(id) }
        }

        private fun wireOf(outcome: Result<Any?>): String =
            outcome.fold({ values.encode(it) }, { "T" + values.encode(it) })

        /** a hook answering with a throwable is the plugin's decision and the app's to receive, while a wire this side could not decode is ours and may not surface in app code as one */
        private fun answerOf(wire: String): Result<Any?>? {
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
                Log.e(TAG, "[${plugin.manifest.name}] xposed: unreadable answer", e)
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
            Log.e(TAG, "[${plugin.manifest.name}] xposed dispatch failed; running the original", cause)
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

    internal fun deoptimize(method: Member): Boolean = ensureReady() && Native.nativeDeoptimize(method)

    internal fun makeInheritable(cls: Class<*>): Boolean = ensureReady() && Native.nativeMakeInheritable(cls)

    internal fun isHooked(method: Member): Boolean = ensureReady() && Native.nativeIsHooked(method)
}
