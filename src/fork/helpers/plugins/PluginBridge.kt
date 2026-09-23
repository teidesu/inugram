package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire

/**
 * Rust resolves these method ids at `nativeCreate`. Names and signatures are ABI;
 * `jni/tests.rs` checks them against `JniBridge`'s descriptors.
 *
 * Value channels (`String`) carry [PluginWire] values. Error channels (`String?`) are null on success,
 * else a `P`/`R` wire; other strings become `internal`. Never `E` there: native cannot tell the tag from
 * a message starting with E.
 *
 * Calls are synchronous on the thread running JS. Void hosts do their own queue handoff;
 * queue-confined hosts returning values may only run on the engine queue.
 *
 * Per-engine parts close themselves in teardown, in the same runnable that closes the engine.
 * Only [jvm] and [xposed] are optional: their apis need `unsafe.jvm`.
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
) : CoreListener by core,
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

    fun onRandomBytes(count: Int): ByteArray =
        ByteArray(count).also { secureRandom.nextBytes(it) }

    /** rust caches its address once per engine; reads are serialized by the engine lease. little-endian */
    private val tlReplies: java.nio.ByteBuffer =
        java.nio.ByteBuffer.allocateDirect(TL_REPLY_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN)

    fun tlBuffer(): java.nio.ByteBuffer = tlReplies

    fun tlReadField(handle: Long, classId: Int, ordinal: Int): Int = tl.readField(handle, classId, ordinal, tlReplies)

    private companion object {
        val secureRandom = java.security.SecureRandom()

        const val TL_REPLY_BYTES = 64 * 1024
    }
}

// without `unsafe.jvm` rust installs neither api, so these only give the delegation a target

private object MissingJvm : JvmListener {
    override fun jvm(op: Int, target: Long, name: String, args: Array<String>): String = PluginWire.encodeError("internal: jvm listener not installed")

    override fun jvmResolve(target: Any, name: String, mode: Int): Array<Any?> = arrayOf("E", PluginWire.encodeError("internal: jvm listener not installed"))
}

private object MissingXposed : XposedListener {
    override fun xposed(op: Int, target: Long, name: String, args: Array<String>): String = PluginWire.encodeError("internal: xposed listener not installed")
}
