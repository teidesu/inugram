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

    /**
     * Where [TlListener.readField] puts a value and rust reads it, at the address it took once from
     * this buffer. One per engine and reused by every read, which is safe because a TL read only
     * ever originates from JS and the engine lease admits one thread at a time.
     *
     * Little-endian to match every other binary the engine carries. A value too big for it is
     * refused by the writer rather than truncated, and read again through `tlGet`.
     */
    private val tlReplies: java.nio.ByteBuffer =
        java.nio.ByteBuffer.allocateDirect(TL_REPLY_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN)

    fun tlBuffer(): java.nio.ByteBuffer = tlReplies

    fun tlReadField(handle: Long, classId: Int, ordinal: Int): Int = tl.readField(handle, classId, ordinal, tlReplies)

    private companion object {
        val secureRandom = java.security.SecureRandom()

        const val TL_REPLY_BYTES = 64 * 1024
    }
}

// unreachable rather than merely unused: without `unsafe.jvm` rust installs neither api, so nothing
// can call these. They exist so the delegation has a target, and answer the way the nullable slots
// they replaced used to.
private object MissingJvm : JvmListener {
    override fun jvm(op: Int, target: Long, name: String, args: Array<String>): String =
        PluginWire.encodeError("internal: jvm listener not installed")

    override fun jvmResolve(target: Any, name: String, mode: Int): Array<Any?> =
        arrayOf("E", PluginWire.encodeError("internal: jvm listener not installed"))

}

private object MissingXposed : XposedListener {
    override fun xposed(op: Int, target: Long, name: String, args: Array<String>): String =
        PluginWire.encodeError("internal: xposed listener not installed")
}
