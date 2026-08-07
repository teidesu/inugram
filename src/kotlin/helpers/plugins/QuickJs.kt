package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire

/**
 * JNI wrapper over an rquickjs (quickjs-ng) context; the engine itself is the rust crate in
 * src/rust/inu_native.
 *
 * NOT thread-safe: a context is created, used and closed on one thread, and every `*Listener`
 * below is called synchronously on it - the one exception being [XposedListener], which a hooked
 * method's own thread enters.
 *
 * Two channel shapes cross here and they are not interchangeable:
 *
 * - a **value** channel (`String`) always carries a [desu.inugram.core.plugins.PluginWire] value:
 *   `S`/`N`/`J<json>`, `H<O|V><W|R><id>` for a live handle, or `E`/`P`/`R` for an error.
 * - an **error** channel (`String?`) carries nothing but errors, so null means SUCCESS and an `E`
 *   wire is forbidden: native cannot tell that tag from a message that happens to start with `E`,
 *   and would eat the first character. Return a bare message or a `P`/`R` wire.
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

    /** 0=log 1=info 2=warn 3=error 4=debug, plus [LEVEL_FAULT] */
    var consoleListener: ((level: Int, message: String) -> Unit)? = null

    /** `target` is null for an unscoped check; `mode` is a [desu.inugram.core.plugins.ScopeMatch] ordinal */
    var grantChecker: ((name: String, target: String?, mode: Int) -> Boolean)? = null

    // every listener below must be set before the install call that can reach it
    var rpcListener: RpcListener? = null

    var tlListener: TlListener? = null

    var deserializeListener: DeserializeListener? = null

    var apiListener: ApiListener? = null

    var readsListener: ReadsListener? = null

    var fetchListener: FetchListener? = null
    var canvasListener: CanvasListener? = null

    var notificationListener: NotificationListener? = null

    var writesListener: WritesListener? = null

    var jvmListener: JvmListener? = null
    var xposedListener: XposedListener? = null

    /** post a call to [runTimers] `delayMs` from now, withdrawing any earlier wake; negative only withdraws */
    var timerScheduler: ((delayMs: Long) -> Unit)? = null

    interface ApiListener {
        /** [op] keeps in sync with rust `api::KV_*`; unused [key]/[value] arrive as "" */
        fun kv(op: Int, key: String, value: String): String

        /**
         * `[{id, userId, isCurrent, isPremium}]`. Anything unparseable is taken as "no accounts",
         * so this must never be an error wire.
         */
        fun accounts(): String

        fun uiToast(text: String)

        fun uiDialog(requestId: Long, optionsJson: String): String?

        fun uiPrompt(requestId: Long, optionsJson: String): String?

        fun uiChooser(requestId: Long, optionsJson: String): String?

        fun uiCurrentScreen(): String

        fun openUrl(url: String)

        /** **not a wire**: it carries whatever the user copied, so no tag could be told from content */
        fun clipboardRead(): String

        fun clipboardWrite(text: String)

        fun uiOpenPage(pageId: Long): String?

        fun uiOpenFragment(handle: Long): String?

        fun uiRegisterSettings(pageId: Long)

        fun uiUnregisterSettings(pageId: Long)

        fun uiInvalidate(pageId: Long)

        fun uiOpenMenu(menuId: Long, pageId: Long, anchorKey: String, itemsJson: String): String?

        /** [kind] keeps in sync with rust `icons::KIND_*` */
        fun iconResolves(kind: Int, value: String): Boolean

        /** [kind] keeps in sync with rust `actions::KIND_*` */
        fun actionRegister(kind: Int, token: Int, id: String): String?

        fun actionUnregister(kind: Int, token: Int)

        /** [op] keeps in sync with rust `actions::EDITOR_*` */
        fun actionEditor(op: Int, surface: Long, payloadJson: String): String?
    }

    interface RpcListener {
        /**
         * [scope] is the grant to gate on: "" when every method in [methods] is its own scope, and
         * `interceptSendMessage` for the narrowing of this chain that api is.
         */
        fun onRpcRegister(methods: Array<String>, callbackId: Int, scope: String): String?

        fun onRpcUnregister(callbackId: Int)

        /** [slot] is [ANY_ACCOUNT] for the account-less `inu.invokeRpc` */
        fun onInvokeRpc(invokeId: Long, slot: Int, requestWire: String): String?

        fun onRpcNext(dispatchId: Long, requestWire: String): String?

        fun onRpcComplete(dispatchId: Long, resultWire: String)

        /**
         * [scope] is "" when every constructor in [types] is its own scope, else the demuxed event
         * name (`new_message`, ...). [dispatchUpdate] must not be called for a type no registration
         * named.
         */
        fun onUpdateRegister(callbackId: Int, types: Array<String>, scope: String): String?

        fun onUpdateUnregister(callbackId: Int)

        /** a separate table from [onUpdateRegister]'s: these decide whether the app is handed the batch at all */
        fun onInterceptUpdateRegister(callbackId: Int, types: Array<String>): String?

        fun onInterceptUpdateUnregister(callbackId: Int)

        /** called from inside the engine, so the host must post rather than walk the chain on */
        fun onUpdateVerdict(dispatchId: Long, deliver: Boolean)
    }

    interface DeserializeListener {
        /**
         * [rulesJson] is a normalized `[{type, when, set}]`. Native has checked the grant and the
         * shapes; what is left is what only the host knows - which constructors and fields exist,
         * and which of them it refuses to let a rule rewrite.
         */
        fun onDeserializeRegister(callbackId: Int, rulesJson: String): String?

        fun onDeserializeUnregister(callbackId: Int)

        fun onDeserializeMiddlewareRegister(callbackId: Int, typesJson: String): String?

        fun onDeserializeMiddlewareUnregister(callbackId: Int)
    }

    interface ReadsListener {
        /**
         * [op] keeps in sync with rust `reads::OP_*`; [arg] and a batch answer both join on
         * [PluginReads.LIST_SEPARATOR]. An op that failed as a whole answers a single `P`/`E` wire,
         * which native checks for before it splits.
         */
        fun accountRead(accountId: Int, op: Int, arg: String): String

        fun resolvePeer(accountId: Int, requestId: Long, spec: String, kind: Int): String?

        fun accountFetch(accountId: Int, requestId: Long, op: Int, arg: String): String?
    }

    interface WritesListener {
        /**
         * [op] keeps in sync with rust `writes::OP_*`. [values] carries the positions JSON could
         * not: a staged file (`F<json>`), a live handle (`H...`) or a plain TL object (`J<json>`).
         */
        fun accountWrite(accountId: Int, requestId: Long, op: Int, arg: String, values: Array<String>): String?

        /** the one synchronous member here: it reads the app's file-path database and transfers nothing */
        fun messageFile(accountId: Int, value: String): String
    }

    /**
     * unlike every other listener this is entered on whichever thread called the hooked method,
     * not the engine's own.
     */
    interface XposedListener {
        /**
         * [op] keeps in sync with rust `xposed::OP_*`. A value channel carrying [PluginJvm]'s wire:
         * a scalar, `G<kind><id>`, or `T` followed by one of those when an invoked original threw.
         */
        fun xposed(op: Int, target: Long, name: String, args: Array<String>): String
    }

    interface JvmListener {
        /**
         * [op] keeps in sync with rust `jvm::OP_*`. A handle answers as `G<kind><id>` but crosses
         * back in an argument as `G<id>` with no kind: the side that owns the table is the side
         * that knows.
         */
        fun jvm(op: Int, target: Long, name: String, args: Array<String>): String
    }

    /** one trap invocation each, never a whole object graph */
    interface TlListener {
        /** proxy `get` trap; `key == "_"` reads the TL type name, `"length"` a vector's size */
        fun tlGet(handle: Long, key: String): String

        /** [valueWire] is `N` for `deleteProperty` */
        fun tlSet(handle: Long, key: String, valueWire: String): String?

        /** proxy `has`/`getOwnPropertyDescriptor` existence probe: 1 = present, 0 = absent, -1 = expired */
        fun tlHas(handle: Long, key: String): Int

        fun tlOwnKeys(handle: Long): String?

        fun tlCopy(handle: Long): String?

        fun tlRelease(handle: Long)
    }

    /**
     * The engine has checked the *first* url against the `fetch` grant, but not the hosts a
     * redirect leads to and not what any of them resolve to - only the host connects, so only it
     * can screen those. See [PluginFetch].
     */
    interface FetchListener {
        /** [specJson] is `{method, headers: {name: [value...]}, redirect}` */
        fun fetch(requestId: Long, url: String, specJson: String, body: ByteArray?): String?

        /** the engine has already settled the promise: this is about the socket, not the caller */
        fun abort(requestId: Long)
    }

    /**
     * One entry point for the whole api: the engine records drawing into a command buffer and only
     * asks the host for the few things that need real pixels, so a method per op would be nine
     * bindings for nine callers arriving on the same queue. [arg] carries the op's own fields and
     * [bytes] the command buffer.
     */
    interface CanvasListener {
        fun canvas(op: Int, id: Long, arg: String, bytes: ByteArray?): String
    }

    interface NotificationListener {
        /**
         * [events] are [org.telegram.messenger.NotificationCenter]'s own constants, and the
         * vocabulary is closed: an unknown name refuses the whole registration, since a plugin
         * cannot otherwise tell a typo from an event that never fires.
         */
        fun register(callbackId: Int, events: Array<String>): String?

        fun unregister(callbackId: Int)
    }

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
    // names and signatures in sync with it and with proguard
    @Suppress("unused")
    private fun onConsole(level: Int, message: String) {
        consoleListener?.invoke(level, message)
    }

    @Suppress("unused")
    private fun onCheckGrant(name: String, target: String?, mode: Int): Boolean =
        grantChecker?.invoke(name, target, mode) ?: false

    @Suppress("unused")
    private fun onRpcRegister(methods: Array<String>, callbackId: Int, scope: String): String? =
        rpcListener.orMissing("rpc") { it.onRpcRegister(methods, callbackId, scope) }

    @Suppress("unused")
    private fun onRpcUnregister(callbackId: Int) {
        rpcListener?.onRpcUnregister(callbackId)
    }

    @Suppress("unused")
    private fun onInvokeRpc(invokeId: Long, slot: Int, requestWire: String): String? =
        rpcListener.orMissing("rpc") { it.onInvokeRpc(invokeId, slot, requestWire) }

    @Suppress("unused")
    private fun onRpcNext(dispatchId: Long, requestWire: String): String? =
        rpcListener.orMissing("rpc") { it.onRpcNext(dispatchId, requestWire) }

    @Suppress("unused")
    private fun onRpcComplete(dispatchId: Long, resultWire: String) {
        rpcListener?.onRpcComplete(dispatchId, resultWire)
    }

    @Suppress("unused")
    private fun onUpdateRegister(callbackId: Int, types: Array<String>, scope: String): String? =
        rpcListener.orMissing("rpc") { it.onUpdateRegister(callbackId, types, scope) }

    @Suppress("unused")
    private fun onUpdateUnregister(callbackId: Int) {
        rpcListener?.onUpdateUnregister(callbackId)
    }

    @Suppress("unused")
    private fun onInterceptUpdateRegister(callbackId: Int, types: Array<String>): String? =
        rpcListener.orMissing("rpc") { it.onInterceptUpdateRegister(callbackId, types) }

    @Suppress("unused")
    private fun onInterceptUpdateUnregister(callbackId: Int) {
        rpcListener?.onInterceptUpdateUnregister(callbackId)
    }

    @Suppress("unused")
    private fun onUpdateVerdict(dispatchId: Long, deliver: Boolean) {
        rpcListener?.onUpdateVerdict(dispatchId, deliver)
    }

    @Suppress("unused")
    private fun onDeserializeRegister(callbackId: Int, rulesJson: String): String? =
        deserializeListener.orMissing("deserialize") { it.onDeserializeRegister(callbackId, rulesJson) }

    @Suppress("unused")
    private fun onDeserializeUnregister(callbackId: Int) {
        deserializeListener?.onDeserializeUnregister(callbackId)
    }

    @Suppress("unused")
    private fun onDeserializeMiddlewareRegister(callbackId: Int, typesJson: String): String? =
        deserializeListener.orMissing("deserialize") { it.onDeserializeMiddlewareRegister(callbackId, typesJson) }

    @Suppress("unused")
    private fun onDeserializeMiddlewareUnregister(callbackId: Int) {
        deserializeListener?.onDeserializeMiddlewareUnregister(callbackId)
    }

    @Suppress("unused")
    private fun onAccountRead(accountId: Int, op: Int, arg: String): String =
        readsListener.orMissingWire("reads") { it.accountRead(accountId, op, arg) }

    @Suppress("unused")
    private fun onResolvePeer(accountId: Int, requestId: Long, spec: String, kind: Int): String? =
        readsListener.orMissing("reads") { it.resolvePeer(accountId, requestId, spec, kind) }

    @Suppress("unused")
    private fun onAccountFetch(accountId: Int, requestId: Long, op: Int, arg: String): String? =
        readsListener.orMissing("reads") { it.accountFetch(accountId, requestId, op, arg) }

    @Suppress("unused")
    private fun onAccountWrite(accountId: Int, requestId: Long, op: Int, arg: String, values: Array<String>): String? =
        writesListener.orMissing("writes") { it.accountWrite(accountId, requestId, op, arg, values) }

    @Suppress("unused")
    private fun onMessageFile(accountId: Int, value: String): String =
        writesListener.orMissingWire("writes") { it.messageFile(accountId, value) }

    @Suppress("unused")
    private fun onTlGet(handle: Long, key: String): String =
        tlListener.orMissingWire("tl") { it.tlGet(handle, key) }

    @Suppress("unused")
    private fun onTlSet(handle: Long, key: String, valueWire: String): String? =
        tlListener.orMissing("tl") { it.tlSet(handle, key, valueWire) }

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

    @Suppress("unused")
    private fun onKv(op: Int, key: String, value: String): String =
        apiListener.orMissingWire("api") { it.kv(op, key, value) }

    // "[]" rather than an error wire: native caches whatever comes back, and a host that cannot
    // answer has no accounts to speak of
    @Suppress("unused")
    private fun onAccounts(): String = apiListener?.accounts() ?: "[]"

    @Suppress("unused")
    private fun onUiToast(text: String) {
        apiListener?.uiToast(text)
    }

    @Suppress("unused")
    private fun onUiDialog(requestId: Long, optionsJson: String): String? =
        apiListener.orMissing("api") { it.uiDialog(requestId, optionsJson) }

    @Suppress("unused")
    private fun onUiPrompt(requestId: Long, optionsJson: String): String? =
        apiListener.orMissing("api") { it.uiPrompt(requestId, optionsJson) }

    @Suppress("unused")
    private fun onUiChooser(requestId: Long, optionsJson: String): String? =
        apiListener.orMissing("api") { it.uiChooser(requestId, optionsJson) }

    // "N" rather than an error wire: a getter that cannot answer answers "nothing on screen"
    @Suppress("unused")
    private fun onUiCurrentScreen(): String = apiListener?.uiCurrentScreen() ?: "N"

    @Suppress("unused")
    private fun onOpenUrl(url: String) {
        apiListener?.openUrl(url)
    }

    @Suppress("unused")
    private fun onClipboardRead(): String = apiListener?.clipboardRead() ?: ""

    @Suppress("unused")
    private fun onClipboardWrite(text: String) {
        apiListener?.clipboardWrite(text)
    }

    @Suppress("unused")
    private fun onUiOpenPage(pageId: Long): String? =
        apiListener.orMissing("api") { it.uiOpenPage(pageId) }

    @Suppress("unused")
    private fun onUiOpenFragment(handle: Long): String? =
        apiListener.orMissing("api") { it.uiOpenFragment(handle) }

    @Suppress("unused")
    private fun onUiRegisterSettings(pageId: Long) {
        apiListener?.uiRegisterSettings(pageId)
    }

    @Suppress("unused")
    private fun onUiUnregisterSettings(pageId: Long) {
        apiListener?.uiUnregisterSettings(pageId)
    }

    @Suppress("unused")
    private fun onUiInvalidate(pageId: Long) {
        apiListener?.uiInvalidate(pageId)
    }

    @Suppress("unused")
    private fun onUiOpenMenu(menuId: Long, pageId: Long, anchorKey: String, itemsJson: String): String? =
        apiListener.orMissing("api") { it.uiOpenMenu(menuId, pageId, anchorKey, itemsJson) }

    // fails closed like every other bool upcall: an icon nobody can resolve is better refused at
    // the call that made it than drawn as a blank
    @Suppress("unused")
    private fun onIconResolves(kind: Int, value: String): Boolean =
        apiListener?.iconResolves(kind, value) ?: false

    @Suppress("unused")
    private fun onActionRegister(kind: Int, token: Int, id: String): String? =
        apiListener.orMissing("api") { it.actionRegister(kind, token, id) }

    @Suppress("unused")
    private fun onActionUnregister(kind: Int, token: Int) {
        apiListener?.actionUnregister(kind, token)
    }

    @Suppress("unused")
    private fun onActionEditor(op: Int, surface: Long, payloadJson: String): String? =
        apiListener.orMissing("api") { it.actionEditor(op, surface, payloadJson) }

    @Suppress("unused")
    private fun onRandomBytes(count: Int): ByteArray = ByteArray(count).also { secureRandom.nextBytes(it) }

    @Suppress("unused")
    private fun onTimerSchedule(delayMs: Long) {
        timerScheduler?.invoke(delayMs)
    }

    @Suppress("unused")
    private fun onFetch(requestId: Long, url: String, specJson: String, body: ByteArray?): String? =
        fetchListener.orMissing("fetch") { it.fetch(requestId, url, specJson, body) }

    @Suppress("unused")
    private fun onFetchAbort(requestId: Long) {
        fetchListener?.abort(requestId)
    }

    @Suppress("unused")
    private fun onCanvas(op: Int, id: Long, arg: String, bytes: ByteArray?): String =
        canvasListener.orMissingWire("canvas") { it.canvas(op, id, arg, bytes) }

    @Suppress("unused")
    private fun onNotificationRegister(callbackId: Int, events: Array<String>): String? =
        notificationListener.orMissing("notification") { it.register(callbackId, events) }

    @Suppress("unused")
    private fun onNotificationUnregister(callbackId: Int) {
        notificationListener?.unregister(callbackId)
    }

    @Suppress("unused")
    private fun onJvm(op: Int, target: Long, name: String, args: Array<String>): String =
        jvmListener.orMissingWire("jvm") { it.jvm(op, target, name, args) }

    @Suppress("unused")
    private fun onXposed(op: Int, target: Long, name: String, args: Array<String>): String =
        xposedListener.orMissingWire("xposed") { it.xposed(op, target, name, args) }

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
        private val secureRandom = java.security.SecureRandom()

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

/** null means SUCCESS on an error channel, so a missing listener cannot be folded into an elvis */
private inline fun <T : Any> T?.orMissing(what: String, call: (T) -> String?): String? =
    if (this == null) "internal: $what listener not installed" else call(this)

private inline fun <T : Any> T?.orMissingWire(what: String, call: (T) -> String): String =
    if (this == null) PluginWire.encodeError("internal: $what listener not installed") else call(this)
