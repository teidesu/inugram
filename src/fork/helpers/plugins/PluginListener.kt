package desu.inugram.helpers.plugins

/**
 * Rust callbacks, grouped by subsystem. Rust resolves their method IDs on [PluginBridge]
 * at `nativeCreate`. Names and signatures are ABI; `jni/tests.rs` checks them against
 * `JniBridge`'s descriptors.
 *
 * Kept separate from [QuickJs] so tests can read the actual contract without loading its
 * native library or starting a device engine.
 *
 * The two channel formats are distinct:
 *
 * - Value channels (`String`) carry [desu.inugram.core.plugins.PluginWire] values:
 *   `S`/`N`/`J<json>`, `H<O|V><W|R><id>` for handles, or `E`/`P`/`R` errors.
 * - Error channels (`String?`) return null on success and a `P`/`R` error wire on failure,
 *   usually from [desu.inugram.core.plugins.PluginRefusal]. Other strings become `internal`
 *   errors. Never use `E` here: native cannot distinguish the tag from a message starting with E.
 *
 * Calls are synchronous on the thread running JS. Void hosts handle their own queue handoff;
 * queue-confined hosts returning values may only run on the engine queue.
 */
interface PluginListener :
    CoreListener,
    RpcListener,
    UpdatesListener,
    TlListener,
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

/** the two that belong to no subsystem: the engine's own diagnostics and clock */
interface CoreListener {
    /** 0=log 1=info 2=warn 3=error 4=debug, plus [QuickJs.LEVEL_FAULT] */
    fun onConsole(level: Int, message: String)

    /** post a call to `runTimers` `delayMs` from now, withdrawing any earlier wake; negative only withdraws */
    fun onTimerSchedule(delayMs: Long)
}

interface AccountListener : ReadsListener, WritesListener {
    /** `[{id, userId, isCurrent, isPremium}]`; anything unparseable is taken as "no accounts". */
    fun accounts(): String

}

interface ReadsListener {
    /** [op] keeps in sync with rust `reads::OP_*`; a failed batch answers one `P`/`E` wire. */
    fun accountRead(accountId: Int, op: Int, arg: String): String

    fun resolvePeer(accountId: Int, requestId: Long, spec: String, kind: Int): String?

    /** [peer] is a spec or empty, [args] the op's JSON object, [cursor] a page payload this side minted or empty */
    fun accountFetch(accountId: Int, requestId: Long, op: Int, peer: String, args: String, cursor: String): String?
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
     * Shared entry for `inu.ui.dialog`/`prompt`/`chooser`/`pickFile`/`saveFile`.
     * Null means accepted and settled later through [QuickJs.settle]; non-null is an immediate
     * error. Keep [op] in sync with Rust `api::ui::OP_*`.
     */
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

    /** the `inu.icons.common` table: the drawable name for a curated [name], null when unknown */
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
    /** [op] keeps in sync with rust `tl::utils::FORMAT_*`. */
    fun format(op: Int, value: Long): String

    fun openUrl(url: String)

    /** **not a wire**: it carries whatever the user copied, so no tag could be told from content */
    fun clipboardRead(): String

    fun clipboardWrite(text: String)
}

interface RpcListener {
    /**
     * [scope] is the grant to gate on: "" when every method in [methods] is its own scope, and
     * `interceptSendMessage` for the narrowing of this chain that api is. [strict] fails malformed
     * returns instead of logging and advancing past this stage.
     */
    fun onRpcRegister(methods: Array<String>, callbackId: Int, scope: String, strict: Boolean, filterJson: String): String?

    fun onRpcUnregister(callbackId: Int)

    /** [slot] is [QuickJs.ANY_ACCOUNT] for the account-less `inu.invokeRpc` */
    fun onInvokeRpc(invokeId: Long, slot: Int, requestWire: String): String?

    fun onRpcNext(dispatchId: Long, requestWire: String): String?

    fun onRpcComplete(dispatchId: Long, resultWire: String)

    /**
     * `inu.invokeRaw`: [method] is a whole serialized method, constructor id first, and the answer
     * comes back through [QuickJs.settleBytes]. Bytes both ways, since that is all this api
     * ever carries and base64 in a wire string would cost two conversions and 2.7x the payload.
     */
    fun onInvokeRaw(invokeId: Long, slot: Int, method: ByteArray): String?

    /**
     * one takeout session op, all of which settle the invoke the way [onInvokeRpc] does.
     * [takeoutId] is the session's decimal id, empty for [OP_TAKEOUT_INIT]. [arg] is the options
     * json for [OP_TAKEOUT_INIT], "1"/"0" for [OP_TAKEOUT_FINISH], and the request wire for
     * [OP_TAKEOUT_INVOKE].
     */
    fun onTakeout(invokeId: Long, slot: Int, op: Int, takeoutId: String, arg: String): String?

    companion object {
        const val OP_TAKEOUT_INIT = 0
        const val OP_TAKEOUT_FINISH = 1
        const val OP_TAKEOUT_INVOKE = 2
    }
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
     * back in an argument as `G<id>` with no kind. The table behind an id is rust's
     * (`QuickJs.jvmMint`/`jvmObjectAt`), so an id means the same thing on both sides.
     */
    fun jvm(op: Int, target: Long, name: String, args: Array<String>): String

    /**
     * member resolution for rust's call path, once per class and name: [mode] keeps in sync with
     * rust `jvm::native::RESOLVE_*`, and the answer's layout is what `Native::resolve` reads -
     * `["E", wire]`, `["M", className, (member, params, descriptor, static, refusal)*]`,
     * or `["F", declaringClassName, field, type, descriptor, static, final, refusal, typeName, name]`.
     */
    fun jvmResolve(target: Any, name: String, mode: Int): Array<Any?>
}

/** one trap invocation each, never a whole object graph */
interface TlListener {
    /** proxy `get` trap; `key == "_"` reads the TL type name, `"length"` a vector's size */
    fun tlGet(handle: Long, key: String): String

    /**
     * The same read as [tlGet], by field ordinal and into [out] as bytes. Answers the byte count,
     * or [TlHandles.ORDINAL_FALLBACK] for anything it declines to serve, which sends rust back to
     * [tlGet] for that field. [classId] is what the handle's wire named, and an implementation must
     * refuse an ordinal whose class is not the one the handle actually holds.
     */
    fun readField(handle: Long, classId: Int, ordinal: Int, out: java.nio.ByteBuffer): Int

    /** the ordinal [readField] takes for [key] on [classId], or [TlHandles.ORDINAL_FALLBACK] */
    fun resolveField(classId: Int, key: String): Int

    /** [valueWire] is `N` for `deleteProperty` */
    fun tlSet(handle: Long, key: String, valueWire: String): String?

    /** assigning a `Uint8Array`, which crosses as bytes the way [readField] answers one */
    fun tlSetBytes(handle: Long, key: String, value: ByteArray): String?

    /** proxy `has`/`getOwnPropertyDescriptor` existence probe: 1 = present, 0 = absent, -1 = expired */
    fun tlHas(handle: Long, key: String): Int

    fun tlOwnKeys(handle: Long): String?

    fun tlCopy(handle: Long): String?

    fun tlRelease(handle: Long)
}

/**
 * The engine checks the initial URL against the `fetch` grant. The host must check
 * redirect destinations and resolved addresses because it opens the connections.
 * See `PluginFetch`.
 */
interface FetchListener {
    /** [headers] is `name, value` pairs with lowercased names; rust has checked all of it */
    fun fetch(requestId: Long, url: String, method: String, redirect: String, headers: Array<String>, body: ByteArray?): String?

    /** the engine has already settled the promise: this is about the socket, not the caller */
    fun abort(requestId: Long)
}

/**
 * Shared canvas entry point. The engine buffers drawing commands and calls the host for
 * operations needing pixels. [arg] holds operation fields; [bytes] holds the command buffer.
 * All operations use the same queue.
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

    /**
     * while any token is held, by any plugin, the app posts no notification of its own for
     * [account] - or for every account at once, when it is [PluginNotifications.ANY_ACCOUNT]
     */
    fun suppress(token: Int, account: Int, on: Boolean)
}
