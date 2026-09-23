package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginPermissions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rust calls [PluginBridge] directly, caching its method ids off that class, so construction is
 * two-phase: the listeners need this object and [start] needs them.
 *
 * `open` for the on-device suite only, which runs in the app process and cannot shadow this class.
 */
open class QuickJs {
    data class Config(
        val spillDir: String,
        /** "" stages beside the spills */
        val transferDir: String,
        val fsDir: String,
        val fsQuotaBytes: Long,
        val fsUnscoped: Boolean,
        val installFs: Boolean,
        val androidDirs: String,
        val localStoragePath: String,
        val installJvm: Boolean,
        val installXposed: Boolean,
        val grants: PluginPermissions,
    )

    /** published as 0 before teardown. Native validates this generation-tagged handle under the registry lock */
    @Volatile private var ptr: Long = 0

    /**
     * [requireLive] after [PluginSession.isCurrent] passed, where a closed engine is a caller bug. [ifLive]/[ifLiveOr]
     * for callers racing teardown. Lenient forms everywhere would hide missing identity checks.
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

    var listener: PluginBridge? = null
        protected set

    /** throws if any upcall descriptor fails to resolve; that affects all plugins */
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
            config.localStoragePath,
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
     * Runs on the hooked thread. [invocation] is `[method, this, ...args]`. Answers `["A", wire]`, or
     * `["P0" | "P1", ...args]` to run the original, `=` keeping an argument; `P1` means [xposedAfter] is
     * owed a call. `null` means the phase never ran.
     */
    open fun xposedBefore(dispatchId: Long, site: Long, invocation: Array<Any?>): Array<String>? =
        ifLiveOr(null) { nativeXposedBefore(it, dispatchId, site, invocation, invocation.size) }

    /**
     * `null` preserves the outcome; `X` means the after phase never ran and the dispatch still owes a
     * release. The engine borrows [invocation]'s references per call, so it is handed them again.
     */
    open fun xposedAfter(dispatchId: Long, invocation: Array<Any?>, threw: Boolean): String? =
        ifLiveOr(NOT_DISPATCHED) { nativeXposedAfter(it, dispatchId, invocation, invocation.size, threw) }

    /** `[method, this, ...args, result]`: one array crosses instead of two */
    open fun xposedAfterOnly(site: Long, invocation: Array<Any?>, threw: Boolean): String? =
        ifLiveOr(NOT_DISPATCHED) { nativeXposedAfterOnly(it, site, invocation, invocation.size, threw) }

    open fun xposedBudgetMs(): Long = nativeXposedBudgetMs()

    open fun xposedRelease(dispatchId: Long) = ifLive { nativeXposedRelease(it, dispatchId) }

    /** native rejects recursive entry and admission past the hook budget */
    open fun jvmCallback(callbackId: Int) = ifLive { nativeJvmCallback(it, callbackId) }

    open fun jvmMethod(callbackId: Int, self: String, args: Array<String>): String =
        requireLive { nativeJvmMethod(it, callbackId, self, args) }

    /** rust's table, reachable from any thread without the engine lease. 0 means the table has closed */
    open fun jvmMint(value: Any, kind: Char): Long = ifLiveOr(0L) { nativeJvmMint(it, value, kind.code) }

    open fun jvmObjectAt(id: Long): Any? = ifLiveOr(null) { nativeJvmObjectAt(it, id) }

    open fun jvmRelease(id: Long) = ifLive { nativeJvmRelease(it, id) }

    open fun jvmCloseHandles() = ifLive { nativeJvmClose(it) }

    private val jobsScheduled = AtomicBoolean()

    /** native calls this by name */
    fun scheduleJobs() {
        if (!jobsScheduled.compareAndSet(false, true)) return
        EngineDispatch.scheduler.postRunnable {
            jobsScheduled.set(false)
            ifLive { nativePumpJobs(it) }
        }
    }

    /** Quiesce caller-thread callbacks before detaching any host state. */
    fun stopCallbacks() = ifLive { nativeStopCallbacks(it) }

    /** ignores requests already settled or aborted */
    open fun settle(api: Int, requestId: Long, wire: String) = requireLive { nativeSettle(it, api, requestId, wire) }

    open fun settleBytes(api: Int, requestId: Long, bytes: ByteArray) =
        requireLive { nativeSettleBytes(it, api, requestId, bytes) }

    fun runTimers() = ifLive { nativeRunTimers(it) }

    /** a fresh engine assumes foreground, so push the state before evaluating a plugin in the background */
    fun appVisibilityChanged(mode: Int) = requireLive { nativeAppVisibilityChanged(it, mode) }

    /** JS throws are logged, never propagated */
    fun notifyUnload() = requireLive { nativeNotifyUnload(it) }
    fun pollUnload(): Boolean = ifLiveOr(true) { nativePollUnload(it) }

    fun uiRender(pageId: Long): String? = requireLive { nativeUiRender(it, pageId) }

    fun uiEvent(pageId: Long, slot: Int, argJson: String) = requireLive { nativeUiEvent(it, pageId, slot, argJson) }

    fun uiMenuClick(menuId: Long, slot: Int) = requireLive { nativeUiMenuClick(it, menuId, slot) }

    fun uiPageClosed(pageId: Long) = requireLive { nativeUiPageClosed(it, pageId) }

    /** never call it off [EngineDispatch.scheduler] */
    open fun renderActions(kind: Int, surfaceJson: String): String? = ifLiveOr(null) { nativeRenderActions(it, kind, surfaceJson) }

    open fun dispatchAction(kind: Int, token: Int, surfaceJson: String) = ifLive { nativeDispatchAction(it, kind, token, surfaceJson) }

    /** the host owns the diff ([desu.inugram.core.plugins.ScreenStack]) */
    fun dispatchScreenChange(changeJson: String, stackJson: String) = requireLive { nativeDispatchScreenChange(it, changeJson, stackJson) }

    /** native coalesces these, so call per chunk */
    open fun writeProgress(requestId: Long, loaded: Long, total: Long) = ifLive { nativeWriteProgress(it, requestId, loaded, total) }

    open fun dispatchRpc(callbackId: Int, dispatchId: Long, method: String, accountId: Int, requestWire: String) =
        requireLive { nativeDispatchRpc(it, callbackId, dispatchId, method, accountId, requestWire) }

    open fun completeNext(dispatchId: Long, resultWire: String) = requireLive { nativeCompleteNext(it, dispatchId, resultWire) }

    /** the host has already answered the app, so no completion comes back */
    open fun abandonDispatch(dispatchId: Long, reasonWire: String) = requireLive { nativeAbandonDispatch(it, dispatchId, reasonWire) }

    /** the payload is decoded either way, since nothing else frees its handle */
    open fun dispatchUpdate(typeName: String, accountId: Int, updateWire: String) = requireLive { nativeDispatchUpdate(it, typeName, accountId, updateWire) }

    /** answered exactly once through [RpcListener.onUpdateVerdict] */
    open fun dispatchUpdateIntercept(callbackId: Int, dispatchId: Long, typeName: String, accountId: Int, updateWire: String) =
        requireLive { nativeDispatchUpdateIntercept(it, callbackId, dispatchId, typeName, accountId, updateWire) }

    open fun abandonUpdateDispatch(dispatchId: Long, reasonWire: String) = requireLive { nativeAbandonUpdateDispatch(it, dispatchId, reasonWire) }

    fun notifyAccountsChanged() = requireLive { nativeAccountsChanged(it) }

    /** a directive may repeat (`@grant`, `@description:xx`), so native regroups the runs */
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
        localStoragePath: String,
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
    private external fun nativeXposedBefore(ptr: Long, dispatchId: Long, site: Long, invocation: Array<Any?>, count: Int): Array<String>?

    private external fun nativeXposedAfter(ptr: Long, dispatchId: Long, invocation: Array<Any?>, count: Int, threw: Boolean): String?

    private external fun nativeXposedAfterOnly(ptr: Long, site: Long, invocation: Array<Any?>, count: Int, threw: Boolean): String?

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
        /** `console.*` binds 0..4 only, so plugin JS cannot forge a fault */
        const val LEVEL_FAULT = 5

        /** keep in sync with rust `xposed::NOT_DISPATCHED` */
        const val NOT_DISPATCHED = "X"

        /** rust: `ANY_ACCOUNT` */
        const val ANY_ACCOUNT = -1

        /**
         * keep in step with rust `runtime::SETTLE_*`. A modal settles `S`+button (dialog), `S`+text or `N`
         * (prompt), `N` or `J`+indices (chooser); a file request `J`+copies (pick) or `B1`/`B0` (save).
         */
        const val SETTLE_FETCH = 0
        const val SETTLE_CANVAS = 1
        const val SETTLE_MODAL = 2
        const val SETTLE_FILES = 3
        const val SETTLE_READS = 4
        const val SETTLE_WRITES = 5
        const val SETTLE_INVOKE = 6

        // standalone rust cdylib, separate from the stock tmessages.NN lib
        init {
            System.loadLibrary("inu_native")
        }

        /** touching the companion loads the library */
        fun ensureLoaded() = Unit
    }
}
