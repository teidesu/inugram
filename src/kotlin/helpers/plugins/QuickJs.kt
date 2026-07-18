package desu.inugram.helpers.plugins

/**
 * Thin JNI wrapper over an rquickjs (quickjs-ng) context (impl in the rust crate src/rust/inu_native).
 *
 * NOT thread-safe: a context must be created, used and closed on the same thread.
 */
class QuickJs {
    private var ptr: Long = nativeCreate()

    /** invoked from native on console.* — (level, message). 0=log 1=info 2=warn 3=error 4=debug */
    var consoleListener: ((level: Int, message: String) -> Unit)? = null

    /** invoked from native for `inu.interceptRpc`/`inu.invokeRpc`/`inu.onUpdate`; set before [installRpc] */
    var rpcListener: RpcListener? = null

    /** invoked from native for live-TL-proxy trap upcalls (rust: `tl_proxy`); set before [installRpc] */
    var tlListener: TlListener? = null

    /** invoked from native for `inu.kv`/`inu.ui.*` upcalls (rust: `api::ApiHost`); set before [installApi] */
    var apiListener: ApiListener? = null

    /**
     * Kotlin side of the `inu.kv`/`inu.ui` bridge (rust: `api::ApiHost`). Every method is called
     * synchronously from native, on this [QuickJs]'s owning thread.
     */
    interface ApiListener {
        /**
         * one `inu.kv.*` operation. [op]: 0=get 1=set 2=del 3=keys 4=clear 5=getAll 6=insertAll
         * (keep in sync with rust `api::KV_*`). unused [key]/[value] args come in as "".
         * returns a tagged wire string: `S<value>`, `N` (null/ok), `J<json>`, `E<message>` (thrown into JS).
         */
        fun kv(op: Int, key: String, value: String): String

        /** `inu.ui.toast(text)`; fire-and-forget */
        fun uiToast(text: String)

        /**
         * `inu.ui.dialog(options)`; [optionsJson] is the options object serialized. null == dialog
         * shown, settle later via [resolveDialog]; non-null == error message, rejects immediately
         */
        fun uiDialog(requestId: Long, optionsJson: String): String?

        /** `inu.ui.prompt(options)`; same contract as [uiDialog], settle via [resolvePrompt] */
        fun uiPrompt(requestId: Long, optionsJson: String): String?

        /** `inu.ui.openPage(page)`; null == presenting, non-null == error thrown into JS */
        fun uiOpenPage(pageId: Long): String?

        /** `inu.registerSettings(page)`; remember [pageId] as the plugin's settings entry */
        fun uiRegisterSettings(pageId: Long)

        /** `page.invalidate()`; re-render the page if it's currently open (no-op otherwise) */
        fun uiInvalidate(pageId: Long)

        /**
         * `inu.ui.openMenu(items)` fired synchronously inside a [uiRender]-page event dispatch;
         * [itemsJson] is `[{text, checked, danger}]`, item index == click slot for [uiMenuClick].
         * null == showing, non-null == error thrown into JS
         */
        fun uiOpenMenu(menuId: Long, itemsJson: String): String?
    }

    /**
     * Kotlin side of the `inu.interceptRpc`/`inu.invokeRpc`/`inu.onUpdate` bridge (rust: `rpc::RpcHost`).
     * Every method is called synchronously from native, on this [QuickJs]'s owning thread. A `null`
     * return from the `String?`-returning methods means "ok, proceed"; a non-null return is an error
     * message that gets thrown into JS as the registration/call's rejection.
     *
     * request/response payloads are single [desu.inugram.core.plugins.TlWire]-encoded values, never
     * raw JSON: `H<O|V><id>` (a live handle - object or vector), `J<json>` (a plain object literal to
     * be constructed), `E<message>` (generic error), or `R<code>:<text>` (rpc error, surfaced to JS
     * as `inu.RpcError`). See [TlListener] for what a handle dereferences to.
     */
    interface RpcListener {
        /** `inu.interceptRpc(methods, cb)`; permission-check [methods], then remember [callbackId] */
        fun onRpcRegister(methods: Array<String>, callbackId: Int): String?

        /** `inu.invokeRpc(obj)`; [requestWire] wire-encodes the request TLObject */
        fun onInvokeRpc(invokeId: Long, requestWire: String): String?

        /** a middleware called `next(req)`; [requestWire] wire-encodes the (possibly rewritten) request */
        fun onRpcNext(dispatchId: Long, requestWire: String): String?

        /** a dispatch settled (short-circuit or after `next()` resolved); [resultWire] wire-encodes the result or error */
        fun onRpcComplete(dispatchId: Long, resultWire: String)

        /** `inu.onUpdate(cb)`; remember [callbackId] for the next [dispatchUpdate] fan-out */
        fun onUpdateRegister(callbackId: Int): String?
    }

    /**
     * Kotlin side of the live-TL-proxy trap bridge (rust: `tl_proxy::TlHost`). Every method is
     * called synchronously from native, on this [QuickJs]'s owning thread, once per JS-side trap
     * invocation (get/set/ownKeys/...) - never a whole object graph. [handle] identifies a live
     * TLObject or TL vector minted by [desu.inugram.helpers.plugins.TlHandles]; an unknown/expired
     * handle is reported back to JS as a thrown error (see `TlWire.HANDLE_EXPIRED_MESSAGE`).
     */
    interface TlListener {
        /** proxy `get` trap; `key == "_"` reads the TL type name, `"length"` a vector's size */
        fun tlGet(handle: Long, key: String): String

        /** proxy `set`/`deleteProperty` trap ([valueWire] `N` for delete); null on success, else an error message */
        fun tlSet(handle: Long, key: String, valueWire: String): String?

        /** proxy `has`/`getOwnPropertyDescriptor` existence probe: 1 = present, 0 = absent, -1 = expired */
        fun tlHas(handle: Long, key: String): Int

        /** proxy `ownKeys` trap (object handles only); a comma-separated key list, or null if [handle] is expired */
        fun tlOwnKeys(handle: Long): String?

        /** `obj.toJSON()`; a full detached JSON snapshot of [handle], or null if expired */
        fun tlCopy(handle: Long): String?

        /** [handle]'s backing JS proxy target was garbage-collected; free it */
        fun tlRelease(handle: Long)
    }

    private fun requirePtr(): Long {
        check(ptr != 0L) { "QuickJs context is closed" }
        return ptr
    }

    /** evaluates [code] as a global script; throws RuntimeException carrying the JS error+stack */
    fun evaluate(code: String, filename: String = "<plugin>"): String? {
        return nativeEvaluate(requirePtr(), code, filename)
    }

    /** installs `inu.kv` (grant-gated) + `inu.ui.*` + `inu.onUnload`. set [apiListener] first. */
    fun installApi(allowKv: Boolean) {
        nativeInstallApi(requirePtr(), allowKv)
    }

    /** settles a pending `inu.ui.dialog()` promise with the user's action ("positive", "dismissed", ...) */
    fun resolveDialog(requestId: Long, result: String) {
        nativeResolveDialog(requirePtr(), requestId, result)
    }

    /** runs every `inu.onUnload` callback (JS throws are logged, never propagated). call right before [close]. */
    fun notifyUnload() {
        nativeNotifyUnload(requirePtr())
    }

    /** runs the page's `items()` and returns the serialized element tree, or null on failure (logged to console) */
    fun uiRender(pageId: Long): String? {
        return nativeUiRender(requirePtr(), pageId)
    }

    /** fires the page callback behind [slot]; [argJson] is "" for no-arg callbacks, else one JSON scalar */
    fun uiEvent(pageId: Long, slot: Int, argJson: String) {
        nativeUiEvent(requirePtr(), pageId, slot, argJson)
    }

    /** settles an open `inu.ui.openMenu` menu; [slot] = clicked item index, -1 = dismissed */
    fun uiMenuClick(menuId: Long, slot: Int) {
        nativeUiMenuClick(requirePtr(), menuId, slot)
    }

    /** the last open view of the page closed: fires `onClose`, releases its render callbacks */
    fun uiPageClosed(pageId: Long) {
        nativeUiPageClosed(requirePtr(), pageId)
    }

    /** settles a pending `inu.ui.prompt()`; [text] null == cancelled/dismissed (resolves as null) */
    fun resolvePrompt(requestId: Long, text: String?) {
        nativeResolvePrompt(requirePtr(), requestId, text)
    }

    /** installs `inu.interceptRpc`/`inu.invokeRpc`/`inu.onUpdate` per the given coarse grants. set [rpcListener] first. */
    fun installRpc(allowIntercept: Boolean, allowInvoke: Boolean, allowUpdates: Boolean) {
        nativeInstallRpc(requirePtr(), allowIntercept, allowInvoke, allowUpdates)
    }

    /** dispatches an intercepted request into the middleware registered as [callbackId]; [method] is the TL method name (for diagnostics), [requestWire] wire-encodes it */
    fun dispatchRpc(callbackId: Int, dispatchId: Long, method: String, requestWire: String) {
        nativeDispatchRpc(requirePtr(), callbackId, dispatchId, method, requestWire)
    }

    /** settles the `next()` promise for a pending dispatch; [resultWire] wire-encodes the (possibly further-intercepted) response or error */
    fun completeNext(dispatchId: Long, resultWire: String) {
        nativeCompleteNext(requirePtr(), dispatchId, resultWire)
    }

    /** settles a pending `inu.invokeRpc()` promise; [resultWire] wire-encodes the response or error */
    fun resolveInvoke(invokeId: Long, resultWire: String) {
        nativeResolveInvoke(requirePtr(), invokeId, resultWire)
    }

    /** fans [updateJson] out to every `inu.onUpdate` callback registered in this context */
    fun dispatchUpdate(updateJson: String) {
        nativeDispatchUpdate(requirePtr(), updateJson)
    }

    /** populates `inu.info()` for this context. call once, before evaluating the plugin. */
    fun installInfo(
        appVersion: String,
        appBuild: String,
        apiVersion: Int,
        layer: Int,
        language: String,
        header: Map<String, String>,
    ) {
        val keys = header.keys.toTypedArray()
        val values = Array(keys.size) { header[keys[it]] ?: "" }
        nativeInstallInfo(requirePtr(), appVersion, appBuild, apiVersion, layer, language, keys, values)
    }

    fun close() {
        if (ptr == 0L) return
        nativeDestroy(ptr)
        ptr = 0L
    }

    // called from native (see g_onConsole lookup); keep name/signature in sync with the bridge + proguard
    @Suppress("unused")
    private fun onConsole(level: Int, message: String) {
        consoleListener?.invoke(level, message)
    }

    // called from native (rust: JniBridge); names/signatures must stay in sync with the JNI upcall
    // lookups in src/rust/inu_native/src/lib.rs + proguard.
    // careful with the `String?`-returning ones: null means SUCCESS, so a missing listener needs
    // an explicit guard - `listener?.foo() ?: "error"` would turn every success into that error
    @Suppress("unused")
    private fun onRpcRegister(methods: Array<String>, callbackId: Int): String? {
        val listener = rpcListener ?: return "internal: rpc listener not installed"
        return listener.onRpcRegister(methods, callbackId)
    }

    @Suppress("unused")
    private fun onInvokeRpc(invokeId: Long, requestWire: String): String? {
        val listener = rpcListener ?: return "internal: rpc listener not installed"
        return listener.onInvokeRpc(invokeId, requestWire)
    }

    @Suppress("unused")
    private fun onRpcNext(dispatchId: Long, requestWire: String): String? {
        val listener = rpcListener ?: return "internal: rpc listener not installed"
        return listener.onRpcNext(dispatchId, requestWire)
    }

    @Suppress("unused")
    private fun onRpcComplete(dispatchId: Long, resultWire: String) {
        rpcListener?.onRpcComplete(dispatchId, resultWire)
    }

    @Suppress("unused")
    private fun onUpdateRegister(callbackId: Int): String? {
        val listener = rpcListener ?: return "internal: rpc listener not installed"
        return listener.onUpdateRegister(callbackId)
    }

    // called from native (rust: tl_proxy); names/signatures must stay in sync with the JNI upcall
    // lookups in src/rust/inu_native/src/lib.rs + proguard
    @Suppress("unused")
    private fun onTlGet(handle: Long, key: String): String =
        tlListener?.tlGet(handle, key) ?: "Einternal: tl listener not installed"

    @Suppress("unused")
    private fun onTlSet(handle: Long, key: String, valueWire: String): String? {
        val listener = tlListener ?: return "internal: tl listener not installed"
        return listener.tlSet(handle, key, valueWire)
    }

    @Suppress("unused")
    private fun onTlHas(handle: Long, key: String): Int = tlListener?.tlHas(handle, key) ?: -1

    @Suppress("unused")
    private fun onTlOwnKeys(handle: Long): String? = tlListener?.tlOwnKeys(handle)

    @Suppress("unused")
    private fun onTlCopy(handle: Long): String? = tlListener?.tlCopy(handle)

    @Suppress("unused")
    private fun onTlRelease(handle: Long) {
        tlListener?.tlRelease(handle)
    }

    // called from native (rust: api::ApiHost); names/signatures must stay in sync with the JNI
    // upcall lookups in src/rust/inu_native/src/lib.rs + proguard
    @Suppress("unused")
    private fun onKv(op: Int, key: String, value: String): String =
        apiListener?.kv(op, key, value) ?: "Einternal: api listener not installed"

    @Suppress("unused")
    private fun onUiToast(text: String) {
        apiListener?.uiToast(text)
    }

    @Suppress("unused")
    private fun onUiDialog(requestId: Long, optionsJson: String): String? {
        val listener = apiListener ?: return "internal: api listener not installed"
        return listener.uiDialog(requestId, optionsJson)
    }

    @Suppress("unused")
    private fun onUiPrompt(requestId: Long, optionsJson: String): String? {
        val listener = apiListener ?: return "internal: api listener not installed"
        return listener.uiPrompt(requestId, optionsJson)
    }

    @Suppress("unused")
    private fun onUiOpenPage(pageId: Long): String? {
        val listener = apiListener ?: return "internal: api listener not installed"
        return listener.uiOpenPage(pageId)
    }

    @Suppress("unused")
    private fun onUiRegisterSettings(pageId: Long) {
        apiListener?.uiRegisterSettings(pageId)
    }

    @Suppress("unused")
    private fun onUiInvalidate(pageId: Long) {
        apiListener?.uiInvalidate(pageId)
    }

    @Suppress("unused")
    private fun onUiOpenMenu(menuId: Long, itemsJson: String): String? {
        val listener = apiListener ?: return "internal: api listener not installed"
        return listener.uiOpenMenu(menuId, itemsJson)
    }

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
    private external fun nativeInstallApi(ptr: Long, allowKv: Boolean)
    private external fun nativeResolveDialog(ptr: Long, requestId: Long, result: String)
    private external fun nativeNotifyUnload(ptr: Long)
    private external fun nativeUiRender(ptr: Long, pageId: Long): String?
    private external fun nativeUiEvent(ptr: Long, pageId: Long, slot: Int, argJson: String)
    private external fun nativeUiMenuClick(ptr: Long, menuId: Long, slot: Int)
    private external fun nativeUiPageClosed(ptr: Long, pageId: Long)
    private external fun nativeResolvePrompt(ptr: Long, requestId: Long, text: String?)
    private external fun nativeInstallRpc(ptr: Long, allowIntercept: Boolean, allowInvoke: Boolean, allowUpdates: Boolean)
    private external fun nativeDispatchRpc(ptr: Long, callbackId: Int, dispatchId: Long, method: String, requestWire: String)
    private external fun nativeCompleteNext(ptr: Long, dispatchId: Long, resultWire: String)
    private external fun nativeResolveInvoke(ptr: Long, invokeId: Long, resultWire: String)
    private external fun nativeDispatchUpdate(ptr: Long, updateJson: String)
    private external fun nativeDestroy(ptr: Long)

    companion object {
        // standalone rust cdylib (rquickjs); separate from the stock tmessages.NN lib
        init {
            System.loadLibrary("inu_native")
        }
    }
}
