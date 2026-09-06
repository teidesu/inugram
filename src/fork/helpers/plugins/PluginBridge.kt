package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire

/**
 * The one object rust calls back into, composed out of the per-subsystem listeners their owners
 * build. Kotlin's `by` writes every forwarder, so adding a member to one of the interfaces in
 * [PluginListener] costs nothing here.
 *
 * It is also the engine's registry: the parts that carry per-engine state ([canvas], [jvm],
 * [xposed]) are readable back, which is what their owners look up instead of the slots [QuickJs]
 * used to hold. A part is fixed for the life of the bridge - teardown is `close()` on the part
 * itself, never swapping it out, since `PluginManager.teardown` closes the engine on the same
 * runnable and rust cannot call a closed one.
 *
 * Only [jvm] and [xposed] are optional, and only because their api is not installed at all without
 * `unsafe.jvm`. Everything else is required so that a listener added later cannot silently default
 * to nothing in the app while the harness keeps passing.
 */
class PluginBridge(
    core: CoreListener,
    rpc: RpcListener,
    updates: UpdatesListener,
    val tl: TlListener,
    storage: StorageListener,
    account: AccountListener,
    ui: UiListener,
    platform: PlatformListener,
    fetch: FetchListener,
    val canvas: CanvasListener,
    notifications: NotificationListener,
    val jvm: JvmListener? = null,
    val xposed: XposedListener? = null,
) : PluginListener,
    CoreListener by core,
    RpcListener by rpc,
    UpdatesListener by updates,
    TlListener by tl,
    StorageListener by storage,
    AccountListener by account,
    UiListener by ui,
    PlatformListener by platform,
    FetchListener by fetch,
    CanvasListener by canvas,
    NotificationListener by notifications,
    JvmListener by (jvm ?: MissingJvm),
    XposedListener by (xposed ?: MissingXposed) {

    override fun onRandomBytes(count: Int): ByteArray =
        ByteArray(count).also { secureRandom.nextBytes(it) }

    private companion object {
        val secureRandom = java.security.SecureRandom()
    }
}

// unreachable rather than merely unused: without `unsafe.jvm` rust installs neither api, so nothing
// can call these. They exist so the delegation has a target, and answer the way the nullable slots
// they replaced used to.
private object MissingJvm : JvmListener {
    override fun jvm(op: Int, target: Long, name: String, args: Array<String>): String =
        PluginWire.encodeError("internal: jvm listener not installed")
}

private object MissingXposed : XposedListener {
    override fun xposed(op: Int, target: Long, name: String, args: Array<String>): String =
        PluginWire.encodeError("internal: xposed listener not installed")
}
