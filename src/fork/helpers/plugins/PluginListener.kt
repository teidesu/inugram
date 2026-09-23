package desu.inugram.helpers.plugins

interface CoreListener {
    /** 0=log 1=info 2=warn 3=error 4=debug, plus [QuickJs.LEVEL_FAULT] */
    fun onConsole(level: Int, message: String)

    /** withdraws any earlier wake; negative only withdraws */
    fun onTimerSchedule(delayMs: Long)
}

interface AccountListener : ReadsListener, WritesListener {
    /** `[{id, userId, isCurrent, isPremium}]`; unparseable means no accounts */
    fun accounts(): String
}

interface ReadsListener {
    /** [op] keeps in sync with rust `reads::OP_*` */
    fun accountRead(accountId: Int, op: Int, arg: String): String

    fun resolvePeer(accountId: Int, requestId: Long, spec: String, kind: Int): String?

    /** [cursor] is a page payload this side minted, or empty */
    fun accountFetch(accountId: Int, requestId: Long, op: Int, peer: String, args: String, cursor: String): String?
}

interface WritesListener {
    /** [op] keeps in sync with rust `writes::OP_*` */
    fun accountWrite(accountId: Int, requestId: Long, op: Int, arg: String, values: Array<String>): String?

    fun messageFile(accountId: Int, value: String): String
}

interface UiListener {
    fun uiToast(text: String)

    /** null: settled later through [QuickJs.settle]. [op] keeps in sync with rust `api::ui::OP_*` */
    fun uiModal(op: Int, requestId: Long, optionsJson: String): String?

    fun uiCurrentScreen(): String

    fun uiOpenPage(pageId: Long): String?

    fun uiOpenFragment(handle: Long): String?

    fun uiOpenScreen(optionsJson: String): String?

    fun uiRegisterSettings(pageId: Long)

    fun uiUnregisterSettings(pageId: Long)

    fun uiInvalidate(pageId: Long)

    fun uiOpenMenu(menuId: Long, pageId: Long, anchorKey: String, itemsJson: String): String?

    /** [kind] keeps in sync with rust `icons::KIND_*` */
    fun iconResolves(kind: Int, value: String): Boolean

    fun commonIcon(name: String): String?

    /** [kind] keeps in sync with rust `actions::KIND_*` */
    fun actionRegister(
        kind: Int,
        token: Int,
        id: String,
        placements: Int,
        text: String?,
        icon: String?,
        dynamicFields: Int,
    ): String?

    fun actionUnregister(kind: Int, token: Int)

    /** [op] keeps in sync with rust `actions::EDITOR_*` */
    fun actionEditor(op: Int, surface: Long, payloadJson: String): String?
}

interface PlatformListener {
    /** [op] keeps in sync with rust `tl::utils::FORMAT_*` */
    fun format(op: Int, value: Long): String

    fun openUrl(url: String)

    /** not a wire: user content, so no tag could be told from it */
    fun clipboardRead(): String

    fun clipboardWrite(text: String)
}

interface RpcListener {
    /** [scope] "" means each method is its own scope. [strict] fails malformed returns instead of skipping the stage */
    fun onRpcRegister(methods: Array<String>, callbackId: Int, scope: String, strict: Boolean, filterJson: String): String?

    fun onRpcUnregister(callbackId: Int)

    /** [slot] is [QuickJs.ANY_ACCOUNT] for the account-less `inu.invokeRpc` */
    fun onInvokeRpc(invokeId: Long, slot: Int, requestWire: String): String?

    fun onRpcNext(dispatchId: Long, requestWire: String): String?

    fun onRpcComplete(dispatchId: Long, resultWire: String)

    /** bytes both ways: base64 in a wire would cost two conversions and 2.7x the payload */
    fun onInvokeRaw(invokeId: Long, slot: Int, method: ByteArray): String?

    /** [takeoutId] is empty for [OP_TAKEOUT_INIT]. [arg]: options json for INIT, "1"/"0" for FINISH, request wire for INVOKE */
    fun onTakeout(invokeId: Long, slot: Int, op: Int, takeoutId: String, arg: String): String?

    companion object {
        const val OP_TAKEOUT_INIT = 0
        const val OP_TAKEOUT_FINISH = 1
        const val OP_TAKEOUT_INVOKE = 2
    }
}

/** a separate table in rust from [RpcListener]'s */
interface UpdatesListener {
    /** [scope] "" means each constructor is its own scope, else the demuxed event name */
    fun onUpdateRegister(callbackId: Int, types: Array<String>, scope: String): String?

    fun onUpdateUnregister(callbackId: Int)

    fun onInterceptUpdateRegister(callbackId: Int, types: Array<String>): String?

    fun onInterceptUpdateUnregister(callbackId: Int)

    /** called from inside the engine: the host must post */
    fun onUpdateVerdict(dispatchId: Long, deliver: Boolean)
}

/** entered on the thread that called the hooked method, not the engine's */
interface XposedListener {
    /** [op] keeps in sync with rust `xposed::OP_*`. Answers a scalar, `G<kind><id>`, or `T` plus one of those when the original threw */
    fun xposed(op: Int, target: Long, name: String, args: Array<String>): String
}

interface JvmListener {
    /**
     * [op] keeps in sync with rust `jvm::OP_*`. A handle answers as `G<kind><id>` but comes back in an
     * argument as `G<id>`. Ids index rust's table (`QuickJs.jvmMint`/`jvmObjectAt`).
     */
    fun jvm(op: Int, target: Long, name: String, args: Array<String>): String

    /**
     * [mode] keeps in sync with rust `jvm::native::RESOLVE_*`; `Native::resolve` reads `["E", wire]`,
     * `["M", className, (member, params, descriptor, static, refusal)*]`, or
     * `["F", declaringClassName, field, type, descriptor, static, final, refusal, typeName, name]`
     */
    fun jvmResolve(target: Any, name: String, mode: Int): Array<Any?>
}

interface TlListener {
    /** `key == "_"` reads the TL type name, `"length"` a vector's size */
    fun tlGet(handle: Long, key: String): String

    /**
     * answers the byte count, or [TlHandles.ORDINAL_FALLBACK] to send rust back to [tlGet]. Must refuse an
     * ordinal whose [classId] is not the class the handle holds.
     */
    fun readField(handle: Long, classId: Int, ordinal: Int, out: java.nio.ByteBuffer): Int

    /** the ordinal [readField] takes for [key] on [classId], or [TlHandles.ORDINAL_FALLBACK] */
    fun resolveField(classId: Int, key: String): Int

    /** [valueWire] is `N` for `deleteProperty` */
    fun tlSet(handle: Long, key: String, valueWire: String): String?

    fun tlSetBytes(handle: Long, key: String, value: ByteArray): String?

    /** 1 = present, 0 = absent, -1 = expired */
    fun tlHas(handle: Long, key: String): Int

    fun tlOwnKeys(handle: Long): String?

    fun tlCopy(handle: Long): String?

    fun tlRelease(handle: Long)
}

/** the engine checks the initial URL; the host checks redirects and resolved addresses */
interface FetchListener {
    /** names are lowercased; rust has checked all of it */
    fun fetch(requestId: Long, url: String, method: String, redirect: String, headers: Array<String>, body: ByteArray?): String?

    /** the engine already settled the promise */
    fun abort(requestId: Long)
}

interface CanvasListener {
    fun canvas(op: Int, id: Long, arg: String, bytes: ByteArray?): String
}

interface NotificationListener {
    /** NotificationCenter constants. An unknown name refuses the whole registration: a typo is otherwise indistinguishable from a silent event */
    fun register(callbackId: Int, events: Array<String>): String?

    fun unregister(callbackId: Int)

    /** while any token is held, the app posts no notification of its own for [account], or all with [PluginNotifications.ANY_ACCOUNT] */
    fun suppress(token: Int, account: Int, on: Boolean)
}
