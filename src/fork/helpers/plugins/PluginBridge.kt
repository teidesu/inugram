package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire

/**
 * Receives Rust callbacks through subsystem listeners. Kotlin `by` delegation generates
 * forwarders for the interfaces in [PluginListener].
 *
 * Also provides access to per-engine [canvas], [jvm], and [xposed] state. Each part stays fixed
 * for the bridge's lifetime and closes itself during teardown. `PluginManager.teardown` closes
 * the engine in the same runnable, after which Rust cannot call back.
 *
 * Only [jvm] and [xposed] are optional because their APIs require `unsafe.jvm` to be installed.
 * All other listeners are required to catch missing app wiring even if tests supply it.
 */
class PluginBridge(
    core: CoreListener,
    rpc: RpcListener,
    updates: UpdatesListener,
    val tl: TlListener,
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
     * Shared buffer for [TlListener.readField] results. Rust caches its address once per engine.
     * Reads are serialized by the engine lease, so reusing it across JS calls is safe.
     *
     * Uses little-endian encoding. Values too large for the buffer fall back to `tlGet`.
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
private fun notInstalled(api: String): String = PluginWire.encodeError("internal: $api listener not installed")

private object MissingJvm : JvmListener {
    override fun jvm(op: Int, target: Long, name: String, args: Array<String>): String = notInstalled("jvm")

    override fun jvmResolve(target: Any, name: String, mode: Int): Array<Any?> = arrayOf("E", notInstalled("jvm"))
}

private object MissingXposed : XposedListener {
    override fun xposed(op: Int, target: Long, name: String, args: Array<String>): String = notInstalled("xposed")
}
