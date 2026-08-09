package desu.inugram.helpers.plugins

/**
 * Everything rust can call back into, split by the subsystem that answers it.
 *
 * Rust resolves a method id per member off [PluginBridge] at `nativeCreate` and calls it directly,
 * so these names and signatures are the wire: `jni/tests.rs` reads this file and cross-checks every
 * one of them against the descriptors `JniBridge` looks up.
 *
 * They live here rather than inside [QuickJs] because [QuickJs] cannot be compiled anywhere but a
 * device ([QuickJs.start] calls `nativeCreate()`, and its class initializer loads `libinu_native`),
 * while the contract it carries can be - so the cargo test cross-checking rust's method ids reads *these* declarations
 * instead of a hand-kept copy of them.
 *
 * Two channel shapes cross here and they are not interchangeable:
 *
 * - a **value** channel (`String`) always carries a [desu.inugram.core.plugins.PluginWire] value:
 *   `S`/`N`/`J<json>`, `H<O|V><W|R><id>` for a live handle, or `E`/`P`/`R` for an error.
 * - an **error** channel (`String?`) carries nothing but errors, so null means SUCCESS and an `E`
 *   wire is forbidden: native cannot tell that tag from a message that happens to start with `E`,
 *   and would eat the first character. Return a bare message or a `P`/`R` wire.
 *
 * Every member is called synchronously on the engine's own thread, the one exception being
 * [XposedListener], which a hooked method's thread enters.
 */
interface PluginListener :
    CoreListener,
    RpcListener,
    UpdatesListener,
    TlListener,
    DeserializeListener,
    StorageListener,
    AccountListener,
    UiListener,
    PlatformListener,
    FetchListener,
    CanvasListener,
    NotificationListener,
    JvmListener,
    XposedListener {
    /** `SecureRandom`, and it must be that: `crypto.getRandomValues` throws rather than weaken */
    fun onRandomBytes(count: Int): ByteArray
}

/** the three that belong to no subsystem: the engine's own diagnostics, gate and clock */
interface CoreListener {
    /** 0=log 1=info 2=warn 3=error 4=debug, plus [QuickJs.LEVEL_FAULT] */
    fun onConsole(level: Int, message: String)

    /** `target` is null for an unscoped check; `mode` is a [desu.inugram.core.plugins.ScopeMatch] ordinal */
    fun onCheckGrant(name: String, target: String?, mode: Int): Boolean

    /** post a call to `runTimers` `delayMs` from now, withdrawing any earlier wake; negative only withdraws */
    fun onTimerSchedule(delayMs: Long)
}

interface StorageListener {
    /** [op] keeps in sync with rust `api::KV_*`; unused [key]/[value] arrive as "" */
    fun kv(op: Int, key: String, value: String): String

}

interface AccountListener : ReadsListener, WritesListener {
    /** `[{id, userId, isCurrent, isPremium}]`; anything unparseable is taken as "no accounts". */
    fun accounts(): String

}

interface ReadsListener {
    /** [op] keeps in sync with rust `reads::OP_*`; a failed batch answers one `P`/`E` wire. */
    fun accountRead(accountId: Int, op: Int, arg: String): String

    fun resolvePeer(accountId: Int, requestId: Long, spec: String, kind: Int): String?

    fun accountFetch(accountId: Int, requestId: Long, op: Int, arg: String): String?
}

interface WritesListener {
    /** [op] keeps in sync with rust `writes::OP_*`; [values] carries staged files and TL values. */
    fun accountWrite(accountId: Int, requestId: Long, op: Int, arg: String, values: Array<String>): String?

    /** synchronous: reads the app's file-path database and transfers nothing */
    fun messageFile(accountId: Int, value: String): String
}

interface UiListener {

    fun uiToast(text: String)

    /**
     * `inu.ui.dialog`/`prompt`/`chooser`, which are one member because they are one contract: null
     * means shown and settled later by the matching `resolve*` native, non-null an immediate
     * refusal. [op] keeps in sync with rust `api::ui::OP_*`.
     */
    fun uiModal(op: Int, requestId: Long, optionsJson: String): String?

    fun uiCurrentScreen(): String

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

interface PlatformListener {
    fun openUrl(url: String)

    /** **not a wire**: it carries whatever the user copied, so no tag could be told from content */
    fun clipboardRead(): String

    fun clipboardWrite(text: String)
}

interface RpcListener {
    /**
     * [scope] is the grant to gate on: "" when every method in [methods] is its own scope, and
     * `interceptSendMessage` for the narrowing of this chain that api is.
     */
    fun onRpcRegister(methods: Array<String>, callbackId: Int, scope: String): String?

    fun onRpcUnregister(callbackId: Int)

    /** [slot] is [QuickJs.ANY_ACCOUNT] for the account-less `inu.invokeRpc` */
    fun onInvokeRpc(invokeId: Long, slot: Int, requestWire: String): String?

    fun onRpcNext(dispatchId: Long, requestWire: String): String?

    fun onRpcComplete(dispatchId: Long, resultWire: String)

}

/** the arriving update stream, which is [PluginUpdates] rather than [PluginRpc]: a different table in rust too */
interface UpdatesListener {
    /**
     * [scope] is "" when every constructor in [types] is its own scope, else the demuxed event
     * name (`new_message`, ...). `dispatchUpdate` must not be called for a type no registration
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

/**
 * unlike every other listener this is entered on whichever thread called the hooked method,
 * not the engine's own.
 */
interface XposedListener {
    /**
     * [op] keeps in sync with rust `xposed::OP_*`. A value channel carrying `PluginJvm`'s wire:
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
 * can screen those. See `PluginFetch`.
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
