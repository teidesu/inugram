package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginAsset
import desu.inugram.core.plugins.PluginPermissions
import desu.inugram.helpers.plugins.telegram.PluginMessageFilters
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
        /** "" leaves `inu.sqlite` ungranted */
        val sqlitePath: String,
        val sqliteQuotaBytes: Long,
        val installJvm: Boolean,
        val installXposed: Boolean,
        val grants: PluginPermissions,
        /** each asset with the path of its decoded file */
        val assets: List<Pair<PluginAsset, String>>,
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
            config.sqlitePath,
            config.sqliteQuotaBytes,
            config.installJvm,
            config.installXposed,
            config.grants.toPairs().toTypedArray(),
            config.assets.flatMap { (asset, path) -> listOf(asset.name, asset.mime, path) }.toTypedArray(),
        )
        check(ptr != 0L) { "QuickJs initialization failed" }
    }

    open fun dispatchNotification(callbackId: Int, name: String, accountId: Int, args: Array<String>) =
        ifLive { nativeDispatchNotification(it, callbackId, name, accountId, args) }

    fun evaluate(code: String, filename: String = "<plugin>"): String? = requireLive { nativeEvaluate(it, code, filename) }

    /** json, see `describe_memory_usage`. Engine thread only */
    fun memoryUsage(): String? = ifLiveOr(null) { nativeMemoryUsage(it) }

    /** native calls [onResult] by name, from the engine queue or from whatever closes the engine */
    fun interface EvalCallback {
        fun onResult(fulfilled: Boolean, text: String)
    }

    /** `inu eval`: [callback] is answered exactly once, also when the engine closes before the result settles */
    fun evaluateInspected(code: String, callback: EvalCallback) =
        requireLive { nativeEvaluateInspected(it, code, callback) }

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

    /** answered exactly once through [UiListener.uiChatHistoryPage] */
    fun chatHistoryLoadMore(historyId: Long, requestId: Long, cursor: String, newer: Boolean) =
        requireLive { nativeChatHistoryLoadMore(it, historyId, requestId, cursor, newer) }

    /** answered exactly once through [UiListener.uiChatHistoryPage]; [target] is `I`, `L`, `K` + key or `M` + `${markedPeerId}:${messageId}` */
    fun chatHistoryLoadAround(historyId: Long, requestId: Long, target: String) =
        requireLive { nativeChatHistoryLoadAround(it, historyId, requestId, target) }

    fun chatHistoryClosed(historyId: Long) = requireLive { nativeChatHistoryClosed(it, historyId) }

    fun chatHistoryRead(historyId: Long, key: String) = requireLive { nativeChatHistoryRead(it, historyId, key) }

    fun chatHistoryButtonClick(historyId: Long) = requireLive { nativeChatHistoryButtonClick(it, historyId) }

    fun chatHistoryHeaderTap(historyId: Long) = requireLive { nativeChatHistoryHeaderTap(it, historyId) }

    fun chatHistoryMenu(historyId: Long, requestId: Long) = requireLive { nativeChatHistoryMenu(it, historyId, requestId) }

    fun chatHistoryMenuClick(historyId: Long, requestId: Long, index: Int) =
        requireLive { nativeChatHistoryMenuClick(it, historyId, requestId, index) }

    /** never call it off [EngineDispatch.scheduler]. Answered once through [UiListener.actionsRendered] unless abandoned; false when closed */
    open fun renderActions(renderId: Long, kind: Int, surfaceJson: String): Boolean = ifLiveOr(false) {
        nativeRenderActions(it, renderId, kind, surfaceJson)
        true
    }

    /** the rows that already settled, or null once the render answered or the engine closed */
    open fun abandonActionRender(renderId: Long): String? = ifLiveOr(null) { nativeAbandonActionRender(it, renderId) }

    open fun dispatchAction(kind: Int, token: Int, secondary: Boolean, surfaceJson: String) =
        ifLive { nativeDispatchAction(it, kind, token, secondary, surfaceJson) }

    /** answered exactly once through [TranslationListener.translationResult], unless abandoned first */
    open fun dispatchTranslation(token: Int, dispatchId: Long, requestJson: String) =
        requireLive { nativeDispatchTranslation(it, token, dispatchId, requestJson) }

    open fun abandonTranslation(dispatchId: Long, timedOut: Boolean) = ifLive { nativeAbandonTranslation(it, dispatchId, timedOut) }

    /**
     * Runs [callbackIds] over the message, on the engine thread. `H` hides; otherwise `S` (spoiler) or
     * `V` and the first rewrite's json. Empty when the engine has closed
     */
    open fun filterMessage(callbackIds: IntArray, accountId: Int, messageJson: String): String =
        ifLiveOr("") { nativeFilterMessage(it, callbackIds, accountId, messageJson) }

    /** answered once through [TextEntitiesListener.textEntitySettled], unless abandoned first */
    open fun dispatchTextEntity(token: Int, secondary: Boolean, tapId: Long, tapJson: String) =
        ifLive { nativeDispatchTextEntity(it, token, secondary, tapId, tapJson) }

    open fun abandonTextEntityTap(tapId: Long, timedOut: Boolean) = ifLive { nativeAbandonTextEntityTap(it, tapId, timedOut) }

    /** the host owns the diff ([desu.inugram.core.plugins.ScreenStack]) */
    fun dispatchScreenChange(changeJson: String, stackJson: String) = requireLive { nativeDispatchScreenChange(it, changeJson, stackJson) }

    /** native coalesces these, so call per chunk */
    open fun writeProgress(requestId: Long, loaded: Long, total: Long) = ifLive { nativeWriteProgress(it, requestId, loaded, total) }

    open fun dispatchRpc(callbackId: Int, dispatchId: Long, method: String, accountId: Int, requestWire: String) =
        requireLive { nativeDispatchRpc(it, callbackId, dispatchId, method, accountId, requestWire) }

    /** answered exactly once through [SendsListener.onSendVerdict] */
    open fun dispatchSend(callbackId: Int, dispatchId: Long, accountId: Int, messageJson: String) =
        requireLive { nativeDispatchSend(it, callbackId, dispatchId, accountId, messageJson) }

    open fun abandonSendDispatch(dispatchId: Long, reasonWire: String) = requireLive { nativeAbandonSendDispatch(it, dispatchId, reasonWire) }

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
        sqlitePath: String,
        sqliteQuotaBytes: Long,
        installJvm: Boolean,
        installXposed: Boolean,
        grants: Array<String>,
        assets: Array<String>,
    ): Long
    private external fun nativeEvaluate(ptr: Long, code: String, filename: String): String?

    private external fun nativeEvaluateInspected(ptr: Long, code: String, callback: EvalCallback)
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
    private external fun nativeChatHistoryLoadMore(ptr: Long, historyId: Long, requestId: Long, cursor: String, newer: Boolean)
    private external fun nativeChatHistoryLoadAround(ptr: Long, historyId: Long, requestId: Long, target: String)
    private external fun nativeChatHistoryClosed(ptr: Long, historyId: Long)
    private external fun nativeChatHistoryRead(ptr: Long, historyId: Long, key: String)
    private external fun nativeChatHistoryButtonClick(ptr: Long, historyId: Long)
    private external fun nativeChatHistoryHeaderTap(ptr: Long, historyId: Long)
    private external fun nativeChatHistoryMenu(ptr: Long, historyId: Long, requestId: Long)
    private external fun nativeChatHistoryMenuClick(ptr: Long, historyId: Long, requestId: Long, index: Int)
    private external fun nativeRenderActions(ptr: Long, renderId: Long, kind: Int, surfaceJson: String)
    private external fun nativeAbandonActionRender(ptr: Long, renderId: Long): String?
    private external fun nativeDispatchAction(ptr: Long, kind: Int, token: Int, secondary: Boolean, surfaceJson: String)
    private external fun nativeDispatchTranslation(ptr: Long, token: Int, dispatchId: Long, requestJson: String)
    private external fun nativeAbandonTranslation(ptr: Long, dispatchId: Long, timedOut: Boolean)
    private external fun nativeDispatchScreenChange(ptr: Long, changeJson: String, stackJson: String)
    private external fun nativeFilterMessage(ptr: Long, callbackIds: IntArray, accountId: Int, messageJson: String): String
    private external fun nativeDispatchTextEntity(ptr: Long, token: Int, secondary: Boolean, tapId: Long, tapJson: String)
    private external fun nativeAbandonTextEntityTap(ptr: Long, tapId: Long, timedOut: Boolean)
    private external fun nativeDispatchNotification(ptr: Long, callbackId: Int, name: String, accountId: Int, args: Array<String>)
    private external fun nativeWriteProgress(ptr: Long, requestId: Long, loaded: Long, total: Long)
    private external fun nativeDispatchRpc(ptr: Long, callbackId: Int, dispatchId: Long, method: String, accountId: Int, requestWire: String)
    private external fun nativeDispatchSend(ptr: Long, callbackId: Int, dispatchId: Long, accountId: Int, messageJson: String)
    private external fun nativeAbandonSendDispatch(ptr: Long, dispatchId: Long, reasonWire: String)
    private external fun nativeCompleteNext(ptr: Long, dispatchId: Long, resultWire: String)
    private external fun nativeAbandonDispatch(ptr: Long, dispatchId: Long, reasonWire: String)
    private external fun nativeDispatchUpdate(ptr: Long, typeName: String, accountId: Int, updateWire: String)
    private external fun nativeDispatchUpdateIntercept(ptr: Long, callbackId: Int, dispatchId: Long, typeName: String, accountId: Int, updateWire: String)
    private external fun nativeAbandonUpdateDispatch(ptr: Long, dispatchId: Long, reasonWire: String)
    private external fun nativeAccountsChanged(ptr: Long)
    private external fun nativeMemoryUsage(ptr: Long): String?
    private external fun nativeSettle(ptr: Long, api: Int, requestId: Long, wire: String)
    private external fun nativeSettleBytes(ptr: Long, api: Int, requestId: Long, bytes: ByteArray)
    private external fun nativeRunTimers(ptr: Long)
    private external fun nativeAppVisibilityChanged(ptr: Long, mode: Int)
    private external fun nativeDestroy(ptr: Long)

    companion object {
        /**
         * Any thread, without the engine lease. Each of [engines] in turn answers `[count, matched, ...callback ids]`,
         * `matched` being 0, 1 for a spoiler or 2 for a hide, a closed engine `[0]`; none follow a hide
         */
        fun matchMessageFilters(engines: List<QuickJs>, probe: PluginMessageFilters.Probe): IntArray? = with(probe) {
            nativeMatchMessageFilters(readPtrs(engines), text, entitiesJson, buttons, sender, peer, peerType, media, forward)
        }

        /**
         * Any thread, without the engine lease, like [matchMessageFilters]: the entities each of [engines] finds in
         * [shown], as one array of rust's `TextEntities::matches` entries with the `engine` index. Null without any;
         * a closed engine finds none
         */
        fun matchTextEntities(engines: List<QuickJs>, shown: String, probe: PluginMessageFilters.Probe): String? = with(probe) {
            nativeMatchTextEntities(
                readPtrs(engines), shown, text.takeIf { it != shown }, entitiesJson, buttons, sender, peer, peerType, media, forward,
            )
        }

        private fun readPtrs(engines: List<QuickJs>) = LongArray(engines.size) { engines[it].ptr }

        @JvmStatic
        private external fun nativeMatchMessageFilters(
            ptrs: LongArray,
            text: String,
            entitiesJson: String,
            buttons: String,
            sender: Long,
            peer: Long,
            peerType: Int,
            media: String,
            forward: Boolean,
        ): IntArray?

        @JvmStatic
        private external fun nativeMatchTextEntities(
            ptrs: LongArray,
            shown: String,
            text: String?,
            entitiesJson: String,
            buttons: String,
            sender: Long,
            peer: Long,
            peerType: Int,
            media: String,
            forward: Boolean,
        ): String?

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
