package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginPermissions
import java.util.concurrent.atomic.AtomicBoolean
import org.telegram.messenger.Utilities

/**
 * JNI wrapper over an rquickjs (quickjs-ng) context; the engine itself is the rust crate in
 * src/native.
 *
 * Native entries serialize access. Synchronous callbacks run on their caller thread.
 *
 * There are no upcalls here: rust calls [PluginBridge] directly, caching its method ids off that
 * class. So construction is two-phase - the listeners need this object, and [start] needs them.
 *
 * `open` for the on-device suite alone. It runs in the app's own process, where this class is on
 * the classpath already, so it cannot shadow it the way the JVM harness does; the members a test
 * double records are the ones opened. Nothing in the app subclasses it.
 */
open class QuickJs {
    data class Config(
        val spillDir: String,
        /** where a send's or an upload's file is staged; "" stages beside the spills */
        val transferDir: String,
        val fsDir: String,
        val fsQuotaBytes: Long,
        val fsUnscoped: Boolean,
        val installFs: Boolean,
        val androidDirs: String,
        val kvPath: String,
        val installJvm: Boolean,
        val installXposed: Boolean,
        val grants: PluginPermissions,
    )

    /**
     * 0 until [start], published as 0 before teardown. Native validates this generation-tagged
     * handle under the registry lock, including callers racing close().
     */
    @Volatile private var ptr: Long = 0

    /**
     * Every native call below picks one of these two, and which one is a claim about the caller.
     *
     * [requireLive] is for a call whose caller has already passed [PluginSession.isCurrent] - a closed
     * engine there is a bug in the caller, not a race, and throwing is how it gets found. [ifLive]
     * and [ifLiveOr] are for the ones reachable with no such gate: a hooked method's own thread, the
     * notification centre, a timer wake, a menu render on the ui thread. Those may find the engine
     * closed at any point and answering nothing is the correct outcome.
     *
     * Picking the lenient one everywhere would turn a caller that skipped its gate into silence.
     */
    private inline fun <T> requireLive(call: (Long) -> T): T {
        val live = ptr
        check(live != 0L) { "QuickJs context is closed or was never started" }
        return call(live)
    }

    private inline fun ifLive(call: (Long) -> Unit) {
        val live = ptr
        if (live != 0L) call(live)
    }

    private inline fun <T> ifLiveOr(fallback: T, call: (Long) -> T): T {
        val live = ptr
        return if (live == 0L) fallback else call(live)
    }

    /** whatever [start] was handed; the parts that carry per-engine state are read back off it */
    var listener: PluginBridge? = null
        protected set

    /**
     * creates the native context and hands rust the object it will call back into. Throws if the
     * lookup of any upcall fails, which is one wrong descriptor away and takes every plugin with it.
     */
    open fun start(listener: PluginBridge, config: Config) {
        check(ptr == 0L) { "QuickJs is already started" }
        this.listener = listener
        ptr = nativeCreate(
            listener,
            config.spillDir,
            config.transferDir,
            config.fsDir,
            config.fsQuotaBytes,
            config.fsUnscoped,
            config.installFs,
            config.androidDirs,
            config.kvPath,
            config.installJvm,
            config.installXposed,
            config.grants.toPairs().toTypedArray(),
        )
        check(ptr != 0L) { "QuickJs initialization failed" }
    }

    open fun dispatchNotification(callbackId: Int, name: String, accountId: Int, args: Array<String>) =
        ifLive { nativeDispatchNotification(it, callbackId, name, accountId, args) }

    fun evaluate(code: String, filename: String = "<plugin>"): String? = requireLive { nativeEvaluate(it, code, filename) }


    /**
     * Runs on the hooked thread, with bounded engine admission. Answers `["A", wire]` to answer the call with `wire`,
     * or `["P0" | "P1", ...args]` to run the original with those args - `P1` also meaning
     * [xposedAfter] is owed a call for [dispatchId]. `null` means the phase never ran, so nothing
     * took the argument wires and whatever the caller minted for them is still the caller's.
     */
    open fun xposedBefore(
        dispatchId: Long,
        site: Long,
        methodWire: String,
        thisWire: String,
        args: Array<String>,
    ): Array<String>? = try {
        ifLiveOr(null) { nativeXposedBefore(it, dispatchId, site, methodWire, thisWire, args) }
    } finally { scheduleJobs() }

    /**
     * [resultWire] is what the original answered, `T`-prefixed when it threw; `U` preserves that
     * outcome. `X`, or `null`, means the after phase never ran, so nothing took [resultWire] and
     * whatever it minted is still the caller's to release.
     */
    open fun xposedAfter(dispatchId: Long, resultWire: String): String? = try {
        ifLiveOr(null) { nativeXposedAfter(it, dispatchId, resultWire) }
    } finally { scheduleJobs() }

    /** Shared budget for native phases and Rust engine admission. */
    open fun xposedBudgetMs(): Long = nativeXposedBudgetMs()

    open fun xposedRelease(dispatchId: Long) = ifLive { nativeXposedRelease(it, dispatchId) }

    /** Runs synchronously; native rejects recursive entry and admission past the hook budget. */
    open fun jvmCallback(callbackId: Int) {
        try { ifLive { nativeJvmCallback(it, callbackId) } }
        finally { scheduleJobs() }
    }

    open fun jvmMethod(callbackId: Int, self: String, args: Array<String>): String = try {
        requireLive { nativeJvmMethod(it, callbackId, self, args) }
    } finally { scheduleJobs() }

    /**
     * The reference table behind `inu.jvm` handles is rust's; these reach it from any thread and
     * without the engine lease. [kind] is the handle kind char; 0 means the table has closed.
     */
    open fun jvmMint(value: Any, kind: Char): Long = ifLiveOr(0L) { nativeJvmMint(it, value, kind.code) }

    open fun jvmObjectAt(id: Long): Any? = ifLiveOr(null) { nativeJvmObjectAt(it, id) }

    open fun jvmRelease(id: Long) = ifLive { nativeJvmRelease(it, id) }

    open fun jvmCloseHandles() = ifLive { nativeJvmClose(it) }

    private val jobsScheduled = AtomicBoolean()

    private fun scheduleJobs() {
        if (!jobsScheduled.compareAndSet(false, true)) return
        EngineDispatch.scheduler.postRunnable {
            jobsScheduled.set(false)
            ifLive { nativePumpJobs(it) }
        }
    }

    /** Quiesce caller-thread callbacks before detaching any host state. */
    fun stopCallbacks() = ifLive { nativeStopCallbacks(it) }


    /**
     * the one way a request the host took is answered: [api] is one of [SETTLE_FETCH]..[SETTLE_INVOKE],
     * naming the table [requestId] belongs to, and [wire] is a value wire or an error wire. A request
     * that is no longer outstanding - aborted, or answered already - drops the settle.
     */
    open fun settle(api: Int, requestId: Long, wire: String) = requireLive { nativeSettle(it, api, requestId, wire) }

    /** [settle] for the one answer that is bytes and nothing else: `invokeRaw`'s response body */
    open fun settleBytes(api: Int, requestId: Long, bytes: ByteArray) =
        requireLive { nativeSettleBytes(it, api, requestId, bytes) }

    fun runTimers() = ifLive { nativeRunTimers(it) }

    /**
     * Throttles this engine's timers while hidden; nothing else slows down. A fresh engine assumes
     * the foreground, so push the current state before evaluating a plugin whenever the app is not
     * in it.
     */
    fun appVisibilityChanged(mode: Int) = requireLive { nativeAppVisibilityChanged(it, mode) }

    /** call right before [close]; JS throws are logged, never propagated */
    fun notifyUnload() = requireLive { nativeNotifyUnload(it) }
    fun pollUnload(): Boolean = ifLiveOr(true) { nativePollUnload(it) }

    fun uiRender(pageId: Long): String? = requireLive { nativeUiRender(it, pageId) }

    fun uiEvent(pageId: Long, slot: Int, argJson: String) = requireLive { nativeUiEvent(it, pageId, slot, argJson) }

    fun uiMenuClick(menuId: Long, slot: Int) = requireLive { nativeUiMenuClick(it, menuId, slot) }

    fun uiPageClosed(pageId: Long) = requireLive { nativeUiPageClosed(it, pageId) }

    /** never call it off [EngineDispatch.scheduler] */
    open fun renderActions(kind: Int, surfaceJson: String): String? = ifLiveOr(null) { nativeRenderActions(it, kind, surfaceJson) }

    open fun dispatchAction(kind: Int, token: Int, surfaceJson: String) = ifLive { nativeDispatchAction(it, kind, token, surfaceJson) }

    /** the diff is the host's ([desu.inugram.core.plugins.ScreenStack]), so only call this for an actual change */
    fun dispatchScreenChange(changeJson: String, stackJson: String) = requireLive { nativeDispatchScreenChange(it, changeJson, stackJson) }

    /** native coalesces these on a time interval, so calling it per chunk is what the contract expects */
    open fun writeProgress(requestId: Long, loaded: Long, total: Long) = ifLive { nativeWriteProgress(it, requestId, loaded, total) }

    open fun dispatchRpc(callbackId: Int, dispatchId: Long, method: String, accountId: Int, requestWire: String) =
        requireLive { nativeDispatchRpc(it, callbackId, dispatchId, method, accountId, requestWire) }

    open fun completeNext(dispatchId: Long, resultWire: String) = requireLive { nativeCompleteNext(it, dispatchId, resultWire) }

    /** the host has already answered the app, so no completion comes back */
    open fun abandonDispatch(dispatchId: Long, reasonWire: String) = requireLive { nativeAbandonDispatch(it, dispatchId, reasonWire) }

    /** only for a type some registration named; the payload is decoded either way, since nothing else frees its handle */
    open fun dispatchUpdate(typeName: String, accountId: Int, updateWire: String) = requireLive { nativeDispatchUpdate(it, typeName, accountId, updateWire) }

    /** answered exactly once through [RpcListener.onUpdateVerdict], whatever the middleware does */
    open fun dispatchUpdateIntercept(callbackId: Int, dispatchId: Long, typeName: String, accountId: Int, updateWire: String) =
        requireLive { nativeDispatchUpdateIntercept(it, callbackId, dispatchId, typeName, accountId, updateWire) }

    /** nothing is rejected, but a middleware settling later can no longer drop an update the app already has */
    open fun abandonUpdateDispatch(dispatchId: Long, reasonWire: String) = requireLive { nativeAbandonUpdateDispatch(it, dispatchId, reasonWire) }

    fun notifyAccountsChanged() = requireLive { nativeAccountsChanged(it) }

    /**
     * [header] crosses as two parallel arrays with the key repeated per value: a directive may
     * appear several times (`@grant`, `@description:xx`), so native regroups the runs.
     */
    fun installInfo(
        appVersion: String,
        appBuild: String,
        apiVersion: Int,
        layer: Int,
        language: String,
        header: Map<String, List<String>>,
    ) {
        val keys = ArrayList<String>()
        val values = ArrayList<String>()
        for ((key, entries) in header) {
            for (value in entries) {
                keys.add(key)
                values.add(value)
            }
        }
        requireLive {
            nativeInstallInfo(
                it, appVersion, appBuild, apiVersion, layer, language,
                keys.toTypedArray(), values.toTypedArray(),
            )
        }
    }

    /** publishes the closed state before native teardown so a queued caller cannot use this handle */
    open fun close() {
        val live = ptr
        if (live == 0L) return
        ptr = 0L
        nativeDestroy(live)
    }

    private external fun nativeCreate(
        listener: PluginBridge,
        spillDir: String,
        transferDir: String,
        fsDir: String,
        fsQuotaBytes: Long,
        fsUnscoped: Boolean,
        installFs: Boolean,
        androidDirs: String,
        kvPath: String,
        installJvm: Boolean,
        installXposed: Boolean,
        grants: Array<String>,
    ): Long
    private external fun nativeEvaluate(ptr: Long, code: String, filename: String): String?
    private external fun nativeInstallInfo(
        ptr: Long,
        appVersion: String,
        appBuild: String,
        apiVersion: Int,
        layer: Int,
        language: String,
        headerKeys: Array<String>,
        headerValues: Array<String>,
    )
    private external fun nativeXposedBefore(
        ptr: Long,
        dispatchId: Long,
        site: Long,
        methodWire: String,
        thisWire: String,
        args: Array<String>,
    ): Array<String>?

    private external fun nativeXposedAfter(ptr: Long, dispatchId: Long, resultWire: String): String?

    private external fun nativeXposedRelease(ptr: Long, dispatchId: Long)

    private external fun nativeXposedBudgetMs(): Long
    private external fun nativeStopCallbacks(ptr: Long)
    private external fun nativePumpJobs(ptr: Long)
    private external fun nativeJvmCallback(ptr: Long, callbackId: Int)
    private external fun nativeJvmMethod(ptr: Long, callbackId: Int, self: String, args: Array<String>): String
    private external fun nativeJvmMint(ptr: Long, value: Any, kind: Int): Long
    private external fun nativeJvmObjectAt(ptr: Long, id: Long): Any?
    private external fun nativeJvmRelease(ptr: Long, id: Long)
    private external fun nativeJvmClose(ptr: Long)
    private external fun nativeNotifyUnload(ptr: Long)
    private external fun nativePollUnload(ptr: Long): Boolean
    private external fun nativeUiRender(ptr: Long, pageId: Long): String?
    private external fun nativeUiEvent(ptr: Long, pageId: Long, slot: Int, argJson: String)
    private external fun nativeUiMenuClick(ptr: Long, menuId: Long, slot: Int)
    private external fun nativeUiPageClosed(ptr: Long, pageId: Long)
    private external fun nativeRenderActions(ptr: Long, kind: Int, surfaceJson: String): String?
    private external fun nativeDispatchAction(ptr: Long, kind: Int, token: Int, surfaceJson: String)
    private external fun nativeDispatchScreenChange(ptr: Long, changeJson: String, stackJson: String)
    private external fun nativeDispatchNotification(ptr: Long, callbackId: Int, name: String, accountId: Int, args: Array<String>)
    private external fun nativeWriteProgress(ptr: Long, requestId: Long, loaded: Long, total: Long)
    private external fun nativeDispatchRpc(ptr: Long, callbackId: Int, dispatchId: Long, method: String, accountId: Int, requestWire: String)
    private external fun nativeCompleteNext(ptr: Long, dispatchId: Long, resultWire: String)
    private external fun nativeAbandonDispatch(ptr: Long, dispatchId: Long, reasonWire: String)
    private external fun nativeDispatchUpdate(ptr: Long, typeName: String, accountId: Int, updateWire: String)
    private external fun nativeDispatchUpdateIntercept(ptr: Long, callbackId: Int, dispatchId: Long, typeName: String, accountId: Int, updateWire: String)
    private external fun nativeAbandonUpdateDispatch(ptr: Long, dispatchId: Long, reasonWire: String)
    private external fun nativeAccountsChanged(ptr: Long)
    private external fun nativeSettle(ptr: Long, api: Int, requestId: Long, wire: String)
    private external fun nativeSettleBytes(ptr: Long, api: Int, requestId: Long, bytes: ByteArray)
    private external fun nativeRunTimers(ptr: Long)
    private external fun nativeAppVisibilityChanged(ptr: Long, mode: Int)
    private external fun nativeDestroy(ptr: Long)

    companion object {
        /**
         * a fault: plugin code threw at a site the engine catches rather than propagates.
         * `console.*` binds 0..4 only, so plugin JS cannot forge one.
         */
        const val LEVEL_FAULT = 5

        /** [RpcListener.onInvokeRpc]'s slot for the account-less `inu.invokeRpc` (rust: `ANY_ACCOUNT`) */
        const val ANY_ACCOUNT = -1

        /**
         * the tables [settle] answers into; keep in step with rust `runtime::SETTLE_*`. A modal is a
         * dialog (`S` and the button), a prompt (`S` and the text, or `N`) or a chooser (`N`, or `J`
         * and the picked indices); a file request is a pick (`J` and the copies) or a save (`B1`/`B0`).
         */
        const val SETTLE_FETCH = 0
        const val SETTLE_CANVAS = 1
        const val SETTLE_MODAL = 2
        const val SETTLE_FILES = 3
        const val SETTLE_READS = 4
        const val SETTLE_WRITES = 5
        const val SETTLE_INVOKE = 6

        // standalone rust cdylib (rquickjs); separate from the stock tmessages.NN lib
        init {
            System.loadLibrary("inu_native")
        }

        /** for a class with natives of its own in the same library: touching the companion loads it */
        @JvmStatic
        fun ensureLoaded() = Unit
    }
}
