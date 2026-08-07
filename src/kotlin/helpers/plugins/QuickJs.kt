package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire

/**
 * JNI wrapper over an rquickjs (quickjs-ng) context; the engine itself is the rust crate in
 * src/rust/inu_native.
 *
 * NOT thread-safe: a context is created, used and closed on one thread.
 *
 * Everything rust calls back into is [PluginListener], which is where the wire rules those calls
 * follow are written down. It lives in its own file because this class cannot be compiled off a
 * device and that contract can.
 */
class QuickJs {
    /**
     * published as null *before* the free: a `long` is not read atomically off the owning thread,
     * and one reader (the `inu.xposed` dispatch) is posted by an arbitrary app thread, where a
     * stale pointer is a use-after-free rather than a wrong answer.
     */
    @Volatile private var ptr: Long = nativeCreate()

    /**
     * Every native call below picks one of these two, and which one is a claim about the caller.
     *
     * [requireLive] is for a call whose caller has already passed [PluginDispatch.isLive] - a closed
     * engine there is a bug in the caller, not a race, and throwing is how it gets found. [ifLive]
     * and [ifLiveOr] are for the ones reachable with no such gate: a hooked method's own thread, the
     * notification centre, a timer wake, a menu render on the ui thread. Those may find the engine
     * closed at any point and answering nothing is the correct outcome.
     *
     * Picking the lenient one everywhere would turn a caller that skipped its gate into silence.
     */
    private inline fun <T> requireLive(call: (Long) -> T): T {
        val live = ptr
        check(live != 0L) { "QuickJs context is closed" }
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

    /** set before any `install*` call, which is the first thing that can reach it */
    var listener: PluginBridge? = null

    fun dispatchNotification(callbackId: Int, name: String, accountId: Int, argsJson: String) =
        ifLive { nativeDispatchNotification(it, callbackId, name, accountId, argsJson) }

    fun evaluate(code: String, filename: String = "<plugin>"): String? = requireLive { nativeEvaluate(it, code, filename) }

    /** [spillDir] "" leaves the engine unable to spill blob content, which costs it only headroom */
    fun installApi(spillDir: String) = requireLive { nativeInstallApi(it, spillDir) }

    /**
     * [dir] "" is a directory the host could not make, and every `inu.fs` call then fails rather
     * than landing somewhere else. [quotaBytes] is [PluginFs.UNCAPPED] under `unsafe.fs`, which is
     * also what [unscoped] is, and turns the containment check off.
     */
    fun installFs(dir: String, quotaBytes: Long, unscoped: Boolean, androidDirs: String) =
        requireLive { nativeInstallFs(it, dir, quotaBytes, unscoped, androidDirs) }

    /** its own call because the host only makes it for a plugin holding `unsafe.jvm` */
    fun installJvm() = requireLive { nativeInstallJvm(it) }

    /** after [installJvm]: every entry point takes a `JavaMethod`, which is a handle in that api's table */
    fun installXposed() = requireLive { nativeInstallXposed(it) }

    /**
     * **Call from globalQueue**: the thread that called the hooked method parks on the answer
     * rather than entering the engine itself. Answers `["A", wire]` to answer the call with `wire`,
     * or `["P0" | "P1", ...args]` to run the original with those args - `P1` also meaning
     * [xposedAfter] is owed a call for [dispatchId].
     */
    fun xposedBefore(
        dispatchId: Long,
        site: Long,
        methodWire: String,
        thisWire: String,
        args: Array<String>,
    ): Array<String>? = ifLiveOr(null) { nativeXposedBefore(it, dispatchId, site, methodWire, thisWire, args) }

    /** [resultWire] is what the original answered, `T`-prefixed when it threw */
    fun xposedAfter(dispatchId: Long, resultWire: String): String = ifLiveOr(resultWire) { nativeXposedAfter(it, dispatchId, resultWire) ?: resultWire }

    /** the waiting is the host's, but the number is rust's (`xposed::HOOK_BUDGET_MS`) so there is one of it */
    fun xposedBudgetMs(): Long = nativeXposedBudgetMs()

    fun xposedRelease(dispatchId: Long) = ifLive { nativeXposedRelease(it, dispatchId) }

    /** **Post it**, never call it from inside the reflected call that handed the object over: that call is already inside this engine */
    fun jvmCallback(callbackId: Int) = ifLive { nativeJvmCallback(it, callbackId) }

    /** [resultWire] is `J{status, statusText, url, headers, body: {path, type}}` or an error wire */
    fun fetchResult(requestId: Long, resultWire: String) = requireLive { nativeFetchResult(it, requestId, resultWire) }

    /** `J{path, type}` for an encode, `J{width, height}` for a decode, `""` for a font, or an error wire */
    fun canvasResult(requestId: Long, resultWire: String) = requireLive { nativeCanvasResult(it, requestId, resultWire) }

    fun runTimers() = ifLive { nativeRunTimers(it) }

    /**
     * Throttles this engine's timers while hidden; nothing else slows down. A fresh engine assumes
     * the foreground, so push the current state before evaluating a plugin whenever the app is not
     * in it.
     */
    fun appVisibilityChanged(visible: Boolean) = requireLive { nativeAppVisibilityChanged(it, visible) }

    fun resolveDialog(requestId: Long, result: String) = requireLive { nativeResolveDialog(it, requestId, result) }

    /** call right before [close]; JS throws are logged, never propagated */
    fun notifyUnload() = requireLive { nativeNotifyUnload(it) }

    fun uiRender(pageId: Long): String? = requireLive { nativeUiRender(it, pageId) }

    fun uiEvent(pageId: Long, slot: Int, argJson: String) = requireLive { nativeUiEvent(it, pageId, slot, argJson) }

    fun uiMenuClick(menuId: Long, slot: Int) = requireLive { nativeUiMenuClick(it, menuId, slot) }

    fun uiPageClosed(pageId: Long) = requireLive { nativeUiPageClosed(it, pageId) }

    fun resolvePrompt(requestId: Long, text: String?) = requireLive { nativeResolvePrompt(it, requestId, text) }

    /** never call it off [org.telegram.messenger.Utilities.globalQueue] */
    fun renderActions(kind: Int, surfaceJson: String): String? = ifLiveOr(null) { nativeRenderActions(it, kind, surfaceJson) }

    fun dispatchAction(kind: Int, token: Int, surfaceJson: String) = ifLive { nativeDispatchAction(it, kind, token, surfaceJson) }

    /** [picked] null == dismissed, else a comma-separated index list - one in single mode, any number in multiple */
    fun resolveChooser(requestId: Long, picked: String?) = requireLive { nativeResolveChooser(it, requestId, picked) }

    /** the diff is the host's ([desu.inugram.core.plugins.ScreenStack]), so only call this for an actual change */
    fun dispatchScreenChange(changeJson: String, stackJson: String) = requireLive { nativeDispatchScreenChange(it, changeJson, stackJson) }

    /** after [installApi], whose `inu.Message` the demuxed `inu.onNewMessage` family is built on */
    fun installRpc() = requireLive { nativeInstallRpc(it) }

    fun resolvePeerResult(requestId: Long, resultWire: String) = requireLive { nativeResolvePeerResult(it, requestId, resultWire) }

    /** [resultWire] is a single value, or (for a paged op) the cursor payload followed by the elements */
    fun accountFetchResult(requestId: Long, resultWire: String) = requireLive { nativeAccountFetchResult(it, requestId, resultWire) }

    /** [resultWire] is a single value, or the `J{path,size,mime,name,mtime}` a `File` is minted from */
    fun writeResult(requestId: Long, resultWire: String) = requireLive { nativeWriteResult(it, requestId, resultWire) }

    /** native coalesces these on a time interval, so calling it per chunk is what the contract expects */
    fun writeProgress(requestId: Long, loaded: Long, total: Long) = ifLive { nativeWriteProgress(it, requestId, loaded, total) }

    fun dispatchRpc(callbackId: Int, dispatchId: Long, method: String, accountId: Int, requestWire: String) =
        requireLive { nativeDispatchRpc(it, callbackId, dispatchId, method, accountId, requestWire) }

    fun completeNext(dispatchId: Long, resultWire: String) = requireLive { nativeCompleteNext(it, dispatchId, resultWire) }

    /** the host has already answered the app, so no completion comes back */
    fun abandonDispatch(dispatchId: Long, reasonWire: String) = requireLive { nativeAbandonDispatch(it, dispatchId, reasonWire) }

    fun resolveInvoke(invokeId: Long, resultWire: String) = requireLive { nativeResolveInvoke(it, invokeId, resultWire) }

    /** only for a type some registration named; the payload is decoded either way, since nothing else frees its handle */
    fun dispatchUpdate(typeName: String, accountId: Int, updateWire: String) = requireLive { nativeDispatchUpdate(it, typeName, accountId, updateWire) }

    /** answered exactly once through [RpcListener.onUpdateVerdict], whatever the middleware does */
    fun dispatchUpdateIntercept(callbackId: Int, dispatchId: Long, typeName: String, accountId: Int, updateWire: String) =
        requireLive { nativeDispatchUpdateIntercept(it, callbackId, dispatchId, typeName, accountId, updateWire) }

    /** returns once the middleware has: the thread that parsed [objectWire] is blocked on this */
    fun dispatchDeserialize(callbackId: Int, objectWire: String) = requireLive { nativeDispatchDeserialize(it, callbackId, objectWire) }

    /** nothing is rejected, but a middleware settling later can no longer drop an update the app already has */
    fun abandonUpdateDispatch(dispatchId: Long) = requireLive { nativeAbandonUpdateDispatch(it, dispatchId) }

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

    /**
     * the null is published *before* the free, or a runnable another thread posted spends the whole
     * of `nativeDestroy` holding a pointer this has already handed to `Box::from_raw`
     */
    fun close() {
        val live = ptr
        if (live == 0L) return
        ptr = 0L
        nativeDestroy(live)
    }

    // every `on*` below is looked up by name from rust (src/rust/inu_native/src/lib.rs); keep the
    // names and signatures in sync with it and with proguard. [listener] is null only between
    // `nativeCreate` and the assignment that follows it, since nothing ever clears it again
    @Suppress("unused")
    private fun onConsole(level: Int, message: String) {
        listener?.onConsole(level, message)
    }

    @Suppress("unused")
    private fun onCheckGrant(name: String, target: String?, mode: Int): Boolean =
        listener?.onCheckGrant(name, target, mode) ?: false

    @Suppress("unused")
    private fun onRpcRegister(methods: Array<String>, callbackId: Int, scope: String): String? =
        listener?.onRpcRegister(methods, callbackId, scope) ?: NO_LISTENER

    @Suppress("unused")
    private fun onRpcUnregister(callbackId: Int) {
        listener?.onRpcUnregister(callbackId)
    }

    @Suppress("unused")
    private fun onInvokeRpc(invokeId: Long, slot: Int, requestWire: String): String? =
        listener?.onInvokeRpc(invokeId, slot, requestWire) ?: NO_LISTENER

    @Suppress("unused")
    private fun onRpcNext(dispatchId: Long, requestWire: String): String? =
        listener?.onRpcNext(dispatchId, requestWire) ?: NO_LISTENER

    @Suppress("unused")
    private fun onRpcComplete(dispatchId: Long, resultWire: String) {
        listener?.onRpcComplete(dispatchId, resultWire)
    }

    @Suppress("unused")
    private fun onUpdateRegister(callbackId: Int, types: Array<String>, scope: String): String? =
        listener?.onUpdateRegister(callbackId, types, scope) ?: NO_LISTENER

    @Suppress("unused")
    private fun onUpdateUnregister(callbackId: Int) {
        listener?.onUpdateUnregister(callbackId)
    }

    @Suppress("unused")
    private fun onInterceptUpdateRegister(callbackId: Int, types: Array<String>): String? =
        listener?.onInterceptUpdateRegister(callbackId, types) ?: NO_LISTENER

    @Suppress("unused")
    private fun onInterceptUpdateUnregister(callbackId: Int) {
        listener?.onInterceptUpdateUnregister(callbackId)
    }

    @Suppress("unused")
    private fun onUpdateVerdict(dispatchId: Long, deliver: Boolean) {
        listener?.onUpdateVerdict(dispatchId, deliver)
    }

    @Suppress("unused")
    private fun onDeserializeRegister(callbackId: Int, rulesJson: String): String? =
        listener?.onDeserializeRegister(callbackId, rulesJson) ?: NO_LISTENER

    @Suppress("unused")
    private fun onDeserializeUnregister(callbackId: Int) {
        listener?.onDeserializeUnregister(callbackId)
    }

    @Suppress("unused")
    private fun onDeserializeMiddlewareRegister(callbackId: Int, typesJson: String): String? =
        listener?.onDeserializeMiddlewareRegister(callbackId, typesJson) ?: NO_LISTENER

    @Suppress("unused")
    private fun onDeserializeMiddlewareUnregister(callbackId: Int) {
        listener?.onDeserializeMiddlewareUnregister(callbackId)
    }

    @Suppress("unused")
    private fun onAccountRead(accountId: Int, op: Int, arg: String): String =
        listener?.accountRead(accountId, op, arg) ?: NO_LISTENER_WIRE

    @Suppress("unused")
    private fun onResolvePeer(accountId: Int, requestId: Long, spec: String, kind: Int): String? =
        listener?.resolvePeer(accountId, requestId, spec, kind) ?: NO_LISTENER

    @Suppress("unused")
    private fun onAccountFetch(accountId: Int, requestId: Long, op: Int, arg: String): String? =
        listener?.accountFetch(accountId, requestId, op, arg) ?: NO_LISTENER

    @Suppress("unused")
    private fun onAccountWrite(accountId: Int, requestId: Long, op: Int, arg: String, values: Array<String>): String? =
        listener?.accountWrite(accountId, requestId, op, arg, values) ?: NO_LISTENER

    @Suppress("unused")
    private fun onMessageFile(accountId: Int, value: String): String =
        listener?.messageFile(accountId, value) ?: NO_LISTENER_WIRE

    @Suppress("unused")
    private fun onTlGet(handle: Long, key: String): String =
        listener?.tlGet(handle, key) ?: NO_LISTENER_WIRE

    @Suppress("unused")
    private fun onTlSet(handle: Long, key: String, valueWire: String): String? =
        listener?.tlSet(handle, key, valueWire) ?: NO_LISTENER

    @Suppress("unused")
    private fun onTlHas(handle: Long, key: String): Int = listener?.tlHas(handle, key) ?: -1

    @Suppress("unused")
    private fun onTlOwnKeys(handle: Long): String? = listener?.tlOwnKeys(handle)

    @Suppress("unused")
    private fun onTlCopy(handle: Long): String? = listener?.tlCopy(handle)

    @Suppress("unused")
    private fun onTlRelease(handle: Long) {
        listener?.tlRelease(handle)
    }

    @Suppress("unused")
    private fun onKv(op: Int, key: String, value: String): String =
        listener?.kv(op, key, value) ?: NO_LISTENER_WIRE

    // "[]" rather than an error wire: native caches whatever comes back, and a host that cannot
    // answer has no accounts to speak of
    @Suppress("unused")
    private fun onAccounts(): String = listener?.accounts() ?: "[]"

    @Suppress("unused")
    private fun onUiToast(text: String) {
        listener?.uiToast(text)
    }

    @Suppress("unused")
    private fun onUiDialog(requestId: Long, optionsJson: String): String? =
        listener?.uiDialog(requestId, optionsJson) ?: NO_LISTENER

    @Suppress("unused")
    private fun onUiPrompt(requestId: Long, optionsJson: String): String? =
        listener?.uiPrompt(requestId, optionsJson) ?: NO_LISTENER

    @Suppress("unused")
    private fun onUiChooser(requestId: Long, optionsJson: String): String? =
        listener?.uiChooser(requestId, optionsJson) ?: NO_LISTENER

    // "N" rather than an error wire: a getter that cannot answer answers "nothing on screen"
    @Suppress("unused")
    private fun onUiCurrentScreen(): String = listener?.uiCurrentScreen() ?: "N"

    @Suppress("unused")
    private fun onOpenUrl(url: String) {
        listener?.openUrl(url)
    }

    @Suppress("unused")
    private fun onClipboardRead(): String = listener?.clipboardRead() ?: ""

    @Suppress("unused")
    private fun onClipboardWrite(text: String) {
        listener?.clipboardWrite(text)
    }

    @Suppress("unused")
    private fun onUiOpenPage(pageId: Long): String? =
        listener?.uiOpenPage(pageId) ?: NO_LISTENER

    @Suppress("unused")
    private fun onUiOpenFragment(handle: Long): String? =
        listener?.uiOpenFragment(handle) ?: NO_LISTENER

    @Suppress("unused")
    private fun onUiRegisterSettings(pageId: Long) {
        listener?.uiRegisterSettings(pageId)
    }

    @Suppress("unused")
    private fun onUiUnregisterSettings(pageId: Long) {
        listener?.uiUnregisterSettings(pageId)
    }

    @Suppress("unused")
    private fun onUiInvalidate(pageId: Long) {
        listener?.uiInvalidate(pageId)
    }

    @Suppress("unused")
    private fun onUiOpenMenu(menuId: Long, pageId: Long, anchorKey: String, itemsJson: String): String? =
        listener?.uiOpenMenu(menuId, pageId, anchorKey, itemsJson) ?: NO_LISTENER

    // fails closed like every other bool upcall: an icon nobody can resolve is better refused at
    // the call that made it than drawn as a blank
    @Suppress("unused")
    private fun onIconResolves(kind: Int, value: String): Boolean =
        listener?.iconResolves(kind, value) ?: false

    @Suppress("unused")
    private fun onActionRegister(kind: Int, token: Int, id: String): String? =
        listener?.actionRegister(kind, token, id) ?: NO_LISTENER

    @Suppress("unused")
    private fun onActionUnregister(kind: Int, token: Int) {
        listener?.actionUnregister(kind, token)
    }

    @Suppress("unused")
    private fun onActionEditor(op: Int, surface: Long, payloadJson: String): String? =
        listener?.actionEditor(op, surface, payloadJson) ?: NO_LISTENER

    // a short answer is a clean failure on the rust side, and `crypto.getRandomValues` then throws
    // rather than hand back anything weaker than it promised
    @Suppress("unused")
    private fun onRandomBytes(count: Int): ByteArray = listener?.onRandomBytes(count) ?: ByteArray(0)

    @Suppress("unused")
    private fun onTimerSchedule(delayMs: Long) {
        listener?.onTimerSchedule(delayMs)
    }

    @Suppress("unused")
    private fun onFetch(requestId: Long, url: String, specJson: String, body: ByteArray?): String? =
        listener?.fetch(requestId, url, specJson, body) ?: NO_LISTENER

    @Suppress("unused")
    private fun onFetchAbort(requestId: Long) {
        listener?.abort(requestId)
    }

    @Suppress("unused")
    private fun onCanvas(op: Int, id: Long, arg: String, bytes: ByteArray?): String =
        listener?.canvas(op, id, arg, bytes) ?: NO_LISTENER_WIRE

    @Suppress("unused")
    private fun onNotificationRegister(callbackId: Int, events: Array<String>): String? =
        listener?.register(callbackId, events) ?: NO_LISTENER

    @Suppress("unused")
    private fun onNotificationUnregister(callbackId: Int) {
        listener?.unregister(callbackId)
    }

    @Suppress("unused")
    private fun onJvm(op: Int, target: Long, name: String, args: Array<String>): String =
        listener?.jvm(op, target, name, args) ?: NO_LISTENER_WIRE

    @Suppress("unused")
    private fun onXposed(op: Int, target: Long, name: String, args: Array<String>): String =
        listener?.xposed(op, target, name, args) ?: NO_LISTENER_WIRE

    private external fun nativeCreate(): Long
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
    private external fun nativeInstallApi(ptr: Long, spillDir: String)
    private external fun nativeInstallFs(ptr: Long, dir: String, quotaBytes: Long, unscoped: Boolean, androidDirs: String)
    private external fun nativeInstallJvm(ptr: Long)
    private external fun nativeInstallXposed(ptr: Long)
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
    private external fun nativeJvmCallback(ptr: Long, callbackId: Int)
    private external fun nativeFetchResult(ptr: Long, requestId: Long, resultWire: String)

    private external fun nativeCanvasResult(ptr: Long, requestId: Long, resultWire: String)
    private external fun nativeResolveDialog(ptr: Long, requestId: Long, result: String)
    private external fun nativeNotifyUnload(ptr: Long)
    private external fun nativeUiRender(ptr: Long, pageId: Long): String?
    private external fun nativeUiEvent(ptr: Long, pageId: Long, slot: Int, argJson: String)
    private external fun nativeUiMenuClick(ptr: Long, menuId: Long, slot: Int)
    private external fun nativeUiPageClosed(ptr: Long, pageId: Long)
    private external fun nativeRenderActions(ptr: Long, kind: Int, surfaceJson: String): String?
    private external fun nativeDispatchAction(ptr: Long, kind: Int, token: Int, surfaceJson: String)
    private external fun nativeResolvePrompt(ptr: Long, requestId: Long, text: String?)
    private external fun nativeResolveChooser(ptr: Long, requestId: Long, picked: String?)
    private external fun nativeDispatchScreenChange(ptr: Long, changeJson: String, stackJson: String)
    private external fun nativeDispatchNotification(ptr: Long, callbackId: Int, name: String, accountId: Int, argsJson: String)
    private external fun nativeInstallRpc(ptr: Long)
    private external fun nativeResolvePeerResult(ptr: Long, requestId: Long, resultWire: String)
    private external fun nativeAccountFetchResult(ptr: Long, requestId: Long, resultWire: String)
    private external fun nativeWriteResult(ptr: Long, requestId: Long, resultWire: String)
    private external fun nativeWriteProgress(ptr: Long, requestId: Long, loaded: Long, total: Long)
    private external fun nativeDispatchRpc(ptr: Long, callbackId: Int, dispatchId: Long, method: String, accountId: Int, requestWire: String)
    private external fun nativeCompleteNext(ptr: Long, dispatchId: Long, resultWire: String)
    private external fun nativeAbandonDispatch(ptr: Long, dispatchId: Long, reasonWire: String)
    private external fun nativeResolveInvoke(ptr: Long, invokeId: Long, resultWire: String)
    private external fun nativeDispatchUpdate(ptr: Long, typeName: String, accountId: Int, updateWire: String)
    private external fun nativeDispatchUpdateIntercept(ptr: Long, callbackId: Int, dispatchId: Long, typeName: String, accountId: Int, updateWire: String)
    private external fun nativeDispatchDeserialize(ptr: Long, callbackId: Int, objectWire: String)
    private external fun nativeAbandonUpdateDispatch(ptr: Long, dispatchId: Long)
    private external fun nativeAccountsChanged(ptr: Long)
    private external fun nativeRunTimers(ptr: Long)
    private external fun nativeAppVisibilityChanged(ptr: Long, visible: Boolean)
    private external fun nativeDestroy(ptr: Long)

    companion object {
        /**
         * null means SUCCESS on an error channel, so an absent bridge cannot be folded into an
         * elvis there; the value channels carry the same text as a wire.
         */
        private const val NO_LISTENER = "internal: plugin listener not installed"
        private val NO_LISTENER_WIRE = PluginWire.encodeError(NO_LISTENER)

        /**
         * a fault: plugin code threw at a site the engine catches rather than propagates.
         * `console.*` binds 0..4 only, so plugin JS cannot forge one.
         */
        const val LEVEL_FAULT = 5

        /** [RpcListener.onInvokeRpc]'s slot for the account-less `inu.invokeRpc` (rust: `ANY_ACCOUNT`) */
        const val ANY_ACCOUNT = -1

        // standalone rust cdylib (rquickjs); separate from the stock tmessages.NN lib
        init {
            System.loadLibrary("inu_native")
        }
    }
}
