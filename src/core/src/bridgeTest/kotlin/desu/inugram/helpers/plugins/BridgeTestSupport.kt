package desu.inugram.helpers.plugins

import android.content.Context
import android.os.SystemClock
import desu.inugram.core.plugins.PluginManifest
import desu.inugram.core.plugins.TlWire
import desu.inugram.helpers.plugins.platform.PluginJvm
import desu.inugram.helpers.plugins.platform.PluginNotifications
import desu.inugram.helpers.plugins.tg.PluginDeserialize
import desu.inugram.helpers.plugins.tg.PluginReads
import desu.inugram.helpers.plugins.tg.PluginRpc
import desu.inugram.helpers.plugins.tg.PluginWrites
import desu.inugram.helpers.plugins.tl.TlHandles
import desu.inugram.helpers.plugins.tl.TlJson
import desu.inugram.helpers.plugins.ui.PluginActions
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLoader
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

/**
 * Everything the bridge reaches that is process-global: the two dispatch queues, the virtual clock,
 * the per-account singletons and [PluginRpc]'s own tables. Call [resetBridge] from every `@Before`.
 */
fun resetBridge() {
    Utilities.inu_reset()
    ConnectionsManager.inu_reset()
    MessagesController.inu_reset()
    MediaDataController.inu_reset()
    FileLoader.inu_reset()
    NotificationCenter.inu_reset()
    AndroidUtilities.inu_reset()
    UserConfig.inu_reset()
    // a fresh cache dir per test: PluginBlobs files everything under it and sweeps what it does not own
    ApplicationLoader.applicationContext = Context(java.nio.file.Files.createTempDirectory("inu-bridge-test").toFile())
    for (plugin in PluginManager.plugins()) plugin.engine?.let {
        PluginActions.detach(it)
        PluginNotifications.detach(it)
    }
    PluginManager.installed = emptyList()
    clearRpcState()
    clearDeserializeRules()
}

/**
 * [PluginDeserialize]'s snapshot is read by stock's own deserializer, so a rule one test registered
 * would rewrite the next test's fixtures. Reflection for the reason [clearRpcState] uses it.
 */
private fun clearDeserializeRules() {
    for (name in listOf("live", "liveMiddleware")) {
        val field = PluginDeserialize::class.java.getDeclaredField(name).apply { isAccessible = true }
        (field.get(PluginDeserialize) as MutableMap<*, *>).clear()
    }
    PluginDeserialize.rules = null
    PluginDeserialize.middleware = null
    PluginDeserialize.hot = false
}

/**
 * [PluginRpc] is an object with a process lifetime, so one test's chains would otherwise be another
 * test's starting state. Reflection rather than a test-only `reset()` on the bridge itself: the
 * harness must not ask the code under test to grow a seam it does not need in the app.
 */
private fun clearRpcState() {
    for (field in PluginRpc::class.java.declaredFields) {
        field.isAccessible = true
        when (field.name) {
            "interceptorsByMethod", "updateListenersByType", "updateInterceptorsByType" ->
                field.set(PluginRpc, emptyMap<String, Any>())
            "updateRegs", "updateInterceptRegs" -> field.set(PluginRpc, emptyList<Any>())
            "hasInterceptors", "hasUpdateListeners", "hasUpdateInterceptors" -> field.setBoolean(PluginRpc, false)
            else -> when (val value = field.get(PluginRpc)) {
                is MutableMap<*, *> -> value.clear()
                is MutableCollection<*> -> value.clear()
            }
        }
    }
}

/** a running plugin with [grants], attached to [PluginRpc] the way `PluginManager` does on start */
/**
 * what `inu.android.getCurrentFragment`/`getCurrentActivity` answer in the harness. There is no
 * `LaunchActivity` here, so a test that wants one puts an object of its own in.
 */
object testAppScreen : PluginJvm.AppScreen {
    var fragment: Any? = null
    var activity: Any? = null

    override fun currentFragment(): Any? = fragment

    override fun currentActivity(): Any? = activity
}

fun startPlugin(name: String, vararg grants: String): Plugin {
    testAppScreen.fragment = null
    testAppScreen.activity = null
    val plugin = Plugin(
        id = "%032x".format(name.hashCode().toLong() and 0xffffffffL),
        file = File("/dev/null"),
        source = "",
        manifest = manifestOf(name, grants.toList()),
    )
    plugin.engine = QuickJs()
    PluginManager.installed = PluginManager.installed + plugin
    // in the app `PluginApi.attach` runs first, and the read surface needs the handle table
    // `PluginRpc.attach` mints into - which is why it reads it off the engine rather than holding one
    PluginReads.attach(plugin, plugin.engine!!)
    PluginWrites.attach(plugin, plugin.engine!!)
    PluginNotifications.attach(plugin, plugin.engine!!)
    PluginJvm.attach(plugin, plugin.engine!!, testAppScreen)
    PluginRpc.attach(plugin, plugin.engine!!)
    return plugin
}

fun manifestOf(name: String, grants: List<String>): PluginManifest = PluginManifest(
    name = name,
    author = null,
    version = null,
    description = null,
    localizedDescriptions = emptyMap(),
    icon = null,
    grants = grants,
    pluginApi = null,
    platform = null,
    raw = emptyMap(),
)

val Plugin.js: QuickJs get() = engine!!

/** the `inu.interceptRpc(methods)` a plugin's own JS would have called */
fun Plugin.interceptRpc(vararg methods: String, callbackId: Int = 1): String? =
    js.rpcListener!!.onRpcRegister(arrayOf(*methods), callbackId, "")

/**
 * what the engine registers for `inu.interceptSendMessage`: the fixed method list `rpc.rs` owns
 * (`SEND_METHODS`), plus the api's own grant scope rather than the four methods'.
 */
val SEND_METHODS = arrayOf(
    "messages.sendMessage",
    "messages.sendMedia",
    "messages.sendMultiMedia",
    "messages.editMessage",
)

fun Plugin.interceptSendMessage(callbackId: Int = 1): String? =
    js.rpcListener!!.onRpcRegister(SEND_METHODS, callbackId, "interceptSendMessage")

/** the `inu.interceptUpdate(types, cb)` a plugin's own JS would have called */
fun Plugin.interceptUpdate(vararg types: String, callbackId: Int = 1): String? =
    js.rpcListener!!.onInterceptUpdateRegister(callbackId, arrayOf(*types))

/** the verdict a middleware handed back, delivered the way the engine delivers one */
fun Plugin.updateVerdict(dispatchId: Long, deliver: Boolean) =
    js.rpcListener!!.onUpdateVerdict(dispatchId, deliver)

/** the `inu.onUpdate(types)` a plugin's own JS would have called */
fun Plugin.onUpdate(vararg types: String, callbackId: Int = 1): String? =
    js.rpcListener!!.onUpdateRegister(callbackId, arrayOf(*types), "")

/**
 * what the engine registers for one of the demuxed events: the fixed constructor list `rpc.rs`
 * owns (`DEMUX_EVENTS`), plus the event's own grant scope rather than the constructors'.
 */
enum class DemuxedEvent(val scope: String, val types: Array<String>) {
    NEW_MESSAGE("new_message", arrayOf("updateNewMessage", "updateNewChannelMessage")),
    EDIT_MESSAGE("edit_message", arrayOf("updateEditMessage", "updateEditChannelMessage")),
    DELETE_MESSAGE("delete_message", arrayOf("updateDeleteMessages", "updateDeleteChannelMessages")),
}

/** the `inu.onNewMessage(cb)`/`onMessageEdited(cb)`/`onMessageDeleted(cb)` a plugin's own JS would have called */
fun Plugin.onDemuxedEvent(event: DemuxedEvent, callbackId: Int = 1): String? =
    js.rpcListener!!.onUpdateRegister(callbackId, event.types, event.scope)

fun Plugin.next(dispatchId: Long, requestWire: String): String? =
    js.rpcListener!!.onRpcNext(dispatchId, requestWire)

fun Plugin.complete(dispatchId: Long, resultWire: String) =
    js.rpcListener!!.onRpcComplete(dispatchId, resultWire)

fun Plugin.tl(): QuickJs.TlListener = js.tlListener!!

/**
 * the handle table [PluginRpc] mints into for a plugin, so a test can ask what a wire it handed the
 * engine actually points at. Reflection for the reason [resetBridge] uses it: the bridge must not
 * grow a seam the app has no use for.
 */
@Suppress("UNCHECKED_CAST")
fun tlTableOf(plugin: Plugin): TlHandles? {
    val field = PluginRpc::class.java.getDeclaredField("tlTables").apply { isAccessible = true }
    return (field.get(PluginRpc) as Map<Plugin, TlHandles>)[plugin]
}

/** drives both queues the way the app's threads would, with the clock frozen */
fun drain() {
    Utilities.inu_drain()
}

fun advanceBy(millis: Long) {
    Utilities.inu_advanceBy(millis)
}

fun now(): Long = SystemClock.uptimeMillis()

fun connections(account: Int = 0): ConnectionsManager = ConnectionsManager.getInstance(account)

/**
 * the app's `processUpdates` hook. `true` means the batch was taken over: the app was handed
 * nothing and gets it back through `MessagesController.processed` once the chain settles.
 */
fun deliverUpdates(updates: TLRPC.Updates, account: Int = 0, fromQueue: Boolean = false): Boolean =
    PluginRpc.onUpdates(MessagesController.getInstance(account), updates, account, fromQueue)

/** what the app actually applied, in order */
fun applied(account: Int = 0): List<TLRPC.Updates> = MessagesController.getInstance(account).processed

fun peerUser(id: Long): TLRPC.TL_peerUser = TLRPC.TL_peerUser().apply { user_id = id }

fun peerChannel(id: Long): TLRPC.TL_peerChannel = TLRPC.TL_peerChannel().apply { channel_id = id }

/**
 * sets every flag bit from what the object currently holds, which is what `readParams` leaves
 * behind for anything that came off the wire. without it an optional field a test just assigned
 * reads back as absent, exactly as it would for the app.
 */
fun <T : TLObject> T.synced(): T = apply { TlJson.syncFlags(this) }

/** a message from Telegram's own service account, i.e. one login code redaction applies to */
fun serviceMessage(text: String, id: Int = 1): TLRPC.TL_message = TLRPC.TL_message().apply {
    this.id = id
    message = text
    from_id = peerUser(777000L)
    peer_id = peerUser(100L)
}.synced()

fun handleId(wire: String): Long = (TlWire.decode(wire) as TlWire.Value.Handle).id

fun handleOf(wire: String): TlWire.Value.Handle = TlWire.decode(wire) as TlWire.Value.Handle

fun stringOf(wire: String): String = (TlWire.decode(wire) as TlWire.Value.Str).value

fun assertPluginError(code: String, wire: String?) {
    val decoded = TlWire.decode(wire ?: "N")
    assertTrue(
        decoded is TlWire.Value.PluginErr && decoded.code == code,
        "expected a '$code' PluginError, got $decoded",
    )
}

/** the fork's own checkout, located from the build dir the harness runs out of */
fun forkRoot(): File {
    var dir: File? = File({}.javaClass.protectionDomain.codeSource.location.toURI()).canonicalFile
    while (dir != null && !File(dir, "src/kotlin/helpers/plugins").isDirectory) dir = dir.parentFile
    return dir ?: error("could not locate the repo root")
}

/**
 * the fork's own `src/kotlin/helpers/plugins`, for the wiring a bridge source cannot assert on
 * because the file that does the wiring is excluded from the harness.
 */
fun bridgeSourceDir(): File = File(forkRoot(), "src/kotlin/helpers/plugins")

/** [bridgeSourceDir] is a package tree, so a bridge file is found by name rather than by path */
fun bridgeSource(name: String): File =
    bridgeSourceDir().walkTopDown().firstOrNull { it.name == name }
        ?: error("no bridge source named $name")

/**
 * Reads the number the plugin contract states, given the sentence it appears in with `{}` standing
 * in for the number. A ceiling pinned to a constant the app also ships is pinned to a copy of
 * itself and can be raised to its maximum with the suite green; this is what makes the promise the
 * number. Exactly one place in the document may match, so a ceiling stated twice cannot be pinned
 * to whichever of the two happened to be updated. Mirrors rust `testutil::stated_number`.
 */
fun statedNumber(doc: String, phrase: String): Long {
    val marker = phrase.indexOf("{}")
    require(marker >= 0) { "mark the number with {}" }
    val head = phrase.substring(0, marker)
    val tail = phrase.substring(marker + 2)
    require(tail.isNotEmpty()) { "the phrase must carry text after the number to anchor on" }
    val found = ArrayList<Long>()
    var from = 0
    while (true) {
        val end = doc.indexOf(tail, from)
        if (end < 0) break
        from = end + tail.length
        var start = end
        while (start > 0 && doc[start - 1].isDigit()) start--
        if (start < end && doc.regionMatches(start - head.length, head, 0, head.length)) {
            found.add(doc.substring(start, end).toLong())
        }
    }
    assertEquals(1, found.size, "the contract states '$phrase' ${found.size} time(s)")
    return found[0]
}

/** `src/plugins/common.d.ts`, the normative contract */
fun contract(): String = File(forkRoot(), "src/plugins/common.d.ts").readText()

/** `src/plugins/fs.d.ts`, which states `inu.fs`'s own numbers */
fun fsContract(): String = File(forkRoot(), "src/plugins/fs.d.ts").readText()

/** `src/plugins/android.jvm.d.ts`, which states `inu.jvm`'s own numbers */
fun jvmContract(): String = File(forkRoot(), "src/plugins/android.jvm.d.ts").readText()

fun List<Any>.toJsonArray(): org.json.JSONArray {
    val array = org.json.JSONArray()
    for (item in this) array.put(item)
    return array
}
