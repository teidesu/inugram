package desu.inugram.helpers.plugins

import androidx.test.platform.app.InstrumentationRegistry
import desu.inugram.core.plugins.PluginManifest
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.io.PluginFetch
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
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

/**
 * Everything process-global the bridge reaches, put back to a known state. Unlike the JVM harness
 * none of this is a fake being re-created: it is the app's own singletons, so what a test changed
 * has to be changed back rather than dropped.
 */
fun resetBridge() {
    ApplicationLoader.applicationContext = deviceContext()
    goOffline()
    for (plugin in installedPlugins()) plugin.engine?.let {
        PluginActions.detach(it)
        PluginNotifications.detach(it)
    }
    flushUi()
    clearPluginObservers()
    setInstalledPlugins(emptyList())
    TestQueues.install()
    RecordingConnectionsManager.reset()
    TestApp.reset()
    clearRpcState()
    clearDeserializeRules()
}

/**
 * The app's *native* connection threads are running around the test, and one of them aborts the
 * whole process: `libtmessages`'s `ConnectionsManager::select()` calls `JniAbort` on a response it
 * cannot parse (`can't parse magic ... in TL_config` under CheckJNI), which lands as an empty
 * failure on whichever test happened to be running. Nothing java-side can catch that, and the
 * queue and send filters cannot reach it - it never crosses either boundary. So native is told
 * there is no network at all, for every account, before anything else.
 */
private fun goOffline() {
    for (account in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
        runCatching {
            ConnectionsManager.native_setNetworkAvailable(account, false, 0, false)
            ConnectionsManager.native_pauseNetwork(account)
        }
    }
}

private val nextInstallId = java.util.concurrent.atomic.AtomicLong(1)

/**
 * A 32-hex install id nothing else in the process is using. Anything the bridge keys by install id
 * is *real* storage here (`inu.kv`'s prefs file, `inu.fs`'s directory) and outlives the test that
 * wrote it, which the JVM harness could not show: its `Context` was a fresh temp dir per test.
 */
fun freshInstallId(): String = "%032x".format(nextInstallId.getAndIncrement() or (System.nanoTime() shl 16))

private fun clearDeserializeRules() {
    for (name in listOf("live", "liveMiddleware")) {
        val field = PluginDeserialize::class.java.getDeclaredField(name).apply { isAccessible = true }
        (field.get(PluginDeserialize) as MutableMap<*, *>).clear()
    }
    PluginDeserialize.rules = null
    PluginDeserialize.middleware = null
    PluginDeserialize.hot = false
}

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

/**
 * the real [PluginManager]'s published list, whose *order* is the chain order. Reflection because
 * the app has no reason to expose a setter, and because a test must not reach the installer that
 * would put real plugin files on the device.
 */
private fun snapshotField() =
    PluginManager::class.java.getDeclaredField("snapshot").apply { isAccessible = true }

@Suppress("UNCHECKED_CAST")
fun installedPlugins(): List<Plugin> = snapshotField().get(PluginManager) as List<Plugin>

fun setInstalledPlugins(plugins: List<Plugin>) = snapshotField().set(PluginManager, plugins)

/** the app under test, which is this suite's own package: the bridge reads it through stock */
fun deviceContext(): android.content.Context =
    InstrumentationRegistry.getInstrumentation().targetContext

/**
 * runs everything already posted to the main looper. The bridge reaches the ui thread through
 * `AndroidUtilities.runOnUIThread`, which always posts, and a test runs on the instrumentation
 * thread - so a ui hop is genuinely deferred here rather than deferred by a flag.
 */
fun flushUi() = InstrumentationRegistry.getInstrumentation().runOnMainSync {}

/** posts on the ui thread, which is the only thread [NotificationCenter] may be touched from */
fun onUi(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

/** stock throws outright on a post from anywhere but the ui thread, and a test is never on it */
fun NotificationCenter.postOnUi(id: Int, vararg args: Any?) = onUi { postNotificationName(id, *args) }

/** drives the dispatch queues the way the app's threads would */
fun drain(): Int = TestQueues.drain()

/**
 * drain plus the ui thread, until neither has anything left. A menu render crosses both - the
 * engine is asked on `globalQueue` and the answer is applied on the ui thread - so draining one of
 * them settles nothing on its own.
 */
fun settle() {
    repeat(8) {
        val ran = drain()
        flushUi()
        if (ran == 0) return
    }
}

fun connections(account: Int = 0): RecordingConnectionsManager = RecordingConnectionsManager.forAccount(account)

/** moves the queues' clock forward, so a test reaches a timeout without waiting for it */
fun advanceBy(millis: Long) = TestQueues.advanceBy(millis)

/**
 * what `inu.android.getCurrentFragment`/`getCurrentActivity` answer. No test here opens a real
 * screen, so a test that wants one puts an object of its own in.
 */
object testAppScreen : PluginJvm.AppScreen {
    var fragment: Any? = null
    var activity: Any? = null

    override fun currentFragment(): Any? = fragment

    override fun currentActivity(): Any? = activity
}

/** a running plugin with [grants], wired the way `PluginManager` does on start */
fun startPlugin(name: String, vararg grants: String): Plugin {
    testAppScreen.fragment = null
    testAppScreen.activity = null
    val plugin = Plugin(
        id = "%032x".format(name.hashCode().toLong() and 0xffffffffL),
        file = File("/dev/null"),
        source = "",
        manifest = manifestOf(name, grants.toList()),
    )
    plugin.engine = RecordingQuickJs()
    setInstalledPlugins(installedPlugins() + plugin)
    attachBridge(plugin, plugin.engine as RecordingQuickJs)
    return plugin
}

/**
 * builds [engine]'s [PluginBridge] and runs the installs, the way `PluginManager.start` does.
 *
 * `PluginApi` and `PluginCanvas` come from [DeviceMissing] rather than their owners even though
 * both compile here: their installs reach an `Activity` and a real engine. Nothing under test
 * touches them, so they refuse loudly instead of recording.
 */
fun attachBridge(plugin: Plugin, engine: RecordingQuickJs) {
    val tl = PluginRpc.tlFor(plugin)
    val jvm = PluginJvm.listenerFor(plugin, engine, testAppScreen)
    engine.start(PluginBridge(
        core = DeviceMissing,
        rpc = PluginRpc.listenerFor(plugin, engine, tl),
        tl = tl,
        deserialize = PluginDeserialize.listenerFor(plugin, engine),
        api = DeviceMissing,
        reads = PluginReads.listenerFor(plugin, engine),
        writes = PluginWrites.listenerFor(plugin, engine),
        fetch = PluginFetch.listenerFor(plugin, engine),
        canvas = DeviceMissing,
        notifications = PluginNotifications.listenerFor(plugin, engine),
        jvm = jvm,
        xposed = null,
    ))
    PluginJvm.install(engine)
    PluginRpc.install(engine)
}

private object DeviceMissing : CoreListener, ApiListener, CanvasListener {
    private fun no(what: String): Nothing = throw UnsupportedOperationException("this suite has no $what")

    override fun onConsole(level: Int, message: String) = no("console")

    override fun onCheckGrant(name: String, target: String?, mode: Int) = no("grant checker")

    override fun onTimerSchedule(delayMs: Long) = no("timer scheduler")

    override fun canvas(op: Int, id: Long, arg: String, bytes: ByteArray?) = no("canvas")

    override fun kv(op: Int, key: String, value: String) = no("kv")

    override fun accounts() = no("accounts")

    override fun uiToast(text: String) = no("ui")

    override fun uiDialog(requestId: Long, optionsJson: String) = no("ui")

    override fun uiPrompt(requestId: Long, optionsJson: String) = no("ui")

    override fun uiChooser(requestId: Long, optionsJson: String) = no("ui")

    override fun uiCurrentScreen() = no("ui")

    override fun openUrl(url: String) = no("ui")

    override fun clipboardRead() = no("clipboard")

    override fun clipboardWrite(text: String) = no("clipboard")

    override fun uiOpenPage(pageId: Long) = no("ui")

    override fun uiOpenFragment(handle: Long) = no("ui")

    override fun uiRegisterSettings(pageId: Long) = no("ui")

    override fun uiUnregisterSettings(pageId: Long) = no("ui")

    override fun uiInvalidate(pageId: Long) = no("ui")

    override fun uiOpenMenu(menuId: Long, pageId: Long, anchorKey: String, itemsJson: String) = no("ui")

    override fun iconResolves(kind: Int, value: String) = no("icons")

    override fun actionRegister(kind: Int, token: Int, id: String) = no("actions")

    override fun actionUnregister(kind: Int, token: Int) = no("actions")

    override fun actionEditor(op: Int, surface: Long, payloadJson: String) = no("actions")
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

val Plugin.js: RecordingQuickJs get() = engine as RecordingQuickJs

/** the `inu.interceptRpc(methods)` a plugin's own JS would have called */
fun Plugin.interceptRpc(vararg methods: String, callbackId: Int = 1): String? =
    js.listener!!.onRpcRegister(arrayOf(*methods), callbackId, "")

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
    js.listener!!.onRpcRegister(SEND_METHODS, callbackId, "interceptSendMessage")

/** the `inu.interceptUpdate(types, cb)` a plugin's own JS would have called */
fun Plugin.interceptUpdate(vararg types: String, callbackId: Int = 1): String? =
    js.listener!!.onInterceptUpdateRegister(callbackId, arrayOf(*types))

/** the verdict a middleware handed back, delivered the way the engine delivers one */
fun Plugin.updateVerdict(dispatchId: Long, deliver: Boolean) =
    js.listener!!.onUpdateVerdict(dispatchId, deliver)

/** the `inu.onUpdate(types)` a plugin's own JS would have called */
fun Plugin.onUpdate(vararg types: String, callbackId: Int = 1): String? =
    js.listener!!.onUpdateRegister(callbackId, arrayOf(*types), "")

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
    js.listener!!.onUpdateRegister(callbackId, event.types, event.scope)

fun Plugin.next(dispatchId: Long, requestWire: String): String? =
    js.listener!!.onRpcNext(dispatchId, requestWire)

fun Plugin.complete(dispatchId: Long, resultWire: String) =
    js.listener!!.onRpcComplete(dispatchId, resultWire)

fun Plugin.tl(): TlListener = js.listener!!

/**
 * the handle table [PluginRpc] mints into for a plugin, so a test can ask what a wire it handed the
 * engine actually points at.
 */
@Suppress("UNCHECKED_CAST")
fun tlTableOf(plugin: Plugin): TlHandles? {
    val field = PluginRpc::class.java.getDeclaredField("tlTables").apply { isAccessible = true }
    return (field.get(PluginRpc) as Map<Plugin, TlHandles>)[plugin]
}

/**
 * every observer the *bridge* is holding, on every centre a post could come from. The app's own
 * run into the hundreds here, so they are told apart by the package the delegate came from.
 */
fun pluginObserverCount(): Int {
    var total = pluginObserverCount(NotificationCenter.getGlobalInstance())
    for (account in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
        total += pluginObserverCount(NotificationCenter.getInstance(account))
    }
    return total
}

/**
 * The centres are the app's, so an observer a test left behind is still there for the next one.
 * `detach` only reaches an engine a [Plugin] still points at, and dropping a plugin's engine is
 * itself something these tests do, so the sweep has to be by package rather than by owner.
 */
@Suppress("UNCHECKED_CAST")
fun clearPluginObservers() = onUi {
    val field = NotificationCenter::class.java.getDeclaredField("observers").apply { isAccessible = true }
    val centres = listOf(NotificationCenter.getGlobalInstance()) +
        (0 until UserConfig.MAX_ACCOUNT_COUNT).map { NotificationCenter.getInstance(it) }
    for (centre in centres) {
        val observers = field.get(centre) as android.util.SparseArray<ArrayList<NotificationCenter.NotificationCenterDelegate>>
        val doomed = ArrayList<Pair<NotificationCenter.NotificationCenterDelegate, Int>>()
        for (i in 0 until observers.size()) {
            val id = observers.keyAt(i)
            for (observer in observers.valueAt(i)) {
                if (observer.javaClass.name.startsWith("desu.inugram.helpers.plugins.")) doomed.add(observer to id)
            }
        }
        for ((observer, id) in doomed) centre.removeObserver(observer, id)
    }
}

/** the same count for one centre, which is what a transfer's own observer is registered on */
@Suppress("UNCHECKED_CAST")
fun pluginObserverCount(centre: NotificationCenter): Int {
    val field = NotificationCenter::class.java.getDeclaredField("observers").apply { isAccessible = true }
    val observers = field.get(centre) as android.util.SparseArray<ArrayList<NotificationCenter.NotificationCenterDelegate>>
    var total = 0
    for (i in 0 until observers.size()) {
        total += observers.valueAt(i).count { it.javaClass.name.startsWith("desu.inugram.helpers.plugins.") }
    }
    return total
}

/**
 * Reads the number the plugin contract states, given the sentence it appears in with `{}` standing
 * in for the number. A ceiling pinned to a constant the app also ships is pinned to a copy of
 * itself and can be raised to its maximum with the suite green; this is what makes the promise the
 * number. Exactly one place in the document may match. Mirrors rust `testutil::stated_number`.
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

/** the `.d.ts` sources, bundled as test assets because a device has no repo to read */
private fun contractAsset(name: String): String =
    InstrumentationRegistry.getInstrumentation()
        .context.assets.open("plugins/$name").bufferedReader().use { it.readText() }

/** `src/plugins/common.d.ts`, the normative contract */
fun contract(): String = contractAsset("common.d.ts")

/** a bundled oracle's own source, a test asset for the same reason the contract is one */
fun bundledPlugin(name: String): String =
    InstrumentationRegistry.getInstrumentation()
        .context.assets.open("inu_plugins/$name").bufferedReader().use { it.readText() }

/** `src/androidTestAssets`, for what the suite needs as a file rather than as source */
fun testAsset(name: String): ByteArray =
    InstrumentationRegistry.getInstrumentation().context.assets.open("inu/$name").use { it.readBytes() }

/** `src/plugins/fs.d.ts`, which states `inu.fs`'s own numbers */
fun fsContract(): String = contractAsset("fs.d.ts")

/** `src/plugins/android.jvm.d.ts`, which states `inu.jvm`'s own numbers */
fun jvmContract(): String = contractAsset("android.jvm.d.ts")

/**
 * the app's `processUpdates` hook, driven the way stock drives it. `true` means the batch was taken
 * over: the app was handed nothing and gets it back later.
 */
fun deliverUpdates(updates: TLRPC.Updates, account: Int = 0, fromQueue: Boolean = false): Boolean =
    PluginRpc.onUpdates(TestApp.updatesController(account), updates, account, fromQueue)

/** what the app was actually handed, in order */
fun applied(account: Int = 0): List<TLRPC.Updates> = TestApp.updatesController(account).processed

/**
 * one run of a difference's own stageQueue runnable: [applied] counts the times its body ran, which
 * is once for a difference nothing claimed and once more when a claimed one is handed back. The
 * lists are the runnable's own, so a drop is visible as their contents.
 */
class DifferenceRun(
    val newMessages: MutableList<TLRPC.Message>,
    val otherUpdates: MutableList<TLRPC.Update>,
) {
    var applied = 0
}

/**
 * the difference hook, driven the way stock's runnable drives it: the hook answers first and the
 * body runs only if it did not claim the walk.
 */
fun deliverDifference(
    newMessages: List<TLRPC.Message> = emptyList(),
    otherUpdates: List<TLRPC.Update> = emptyList(),
    account: Int = 0,
): DifferenceRun {
    val run = DifferenceRun(ArrayList(newMessages), ArrayList(otherUpdates))
    lateinit var runnable: Runnable
    runnable = Runnable {
        if (PluginRpc.onDifference(run.newMessages, run.otherUpdates, account, runnable)) return@Runnable
        run.applied++
    }
    runnable.run()
    return run
}

fun peerUser(id: Long): TLRPC.TL_peerUser = TLRPC.TL_peerUser().apply { user_id = id }

fun peerChannel(id: Long): TLRPC.TL_peerChannel = TLRPC.TL_peerChannel().apply { channel_id = id }

/**
 * sets every flag bit from what the object currently holds, which is what `readParams` leaves behind
 * for anything that came off the wire. without it an optional field a test just assigned reads back
 * as absent, exactly as it would for the app.
 */
fun <T : TLObject> T.synced(): T = apply { TlJson.syncFlags(this) }

/** a message from Telegram's own service account, i.e. one login code redaction applies to */
fun serviceMessage(text: String, id: Int = 1): TLRPC.TL_message = TLRPC.TL_message().apply {
    this.id = id
    message = text
    from_id = peerUser(777000L)
    peer_id = peerUser(100L)
}.synced()

fun handleId(wire: String): Long = (PluginWire.decode(wire) as PluginWire.Value.Handle).id

fun handleOf(wire: String): PluginWire.Value.Handle = PluginWire.decode(wire) as PluginWire.Value.Handle

fun stringOf(wire: String): String = (PluginWire.decode(wire) as PluginWire.Value.Str).value

fun assertPluginError(code: String, wire: String?) {
    val decoded = PluginWire.decode(wire ?: "N")
    assertTrue(
        decoded is PluginWire.Value.PluginErr && decoded.code == code,
        "expected a '$code' PluginError, got $decoded",
    )
}

fun List<Any>.toJsonArray(): org.json.JSONArray {
    val array = org.json.JSONArray()
    for (item in this) array.put(item)
    return array
}
