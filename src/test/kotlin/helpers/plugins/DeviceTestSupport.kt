package desu.inugram.helpers.plugins

import androidx.test.platform.app.InstrumentationRegistry
import desu.inugram.core.plugins.PluginManifest
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.io.PluginBlobs
import desu.inugram.helpers.plugins.io.PluginFetch
import desu.inugram.helpers.plugins.platform.PluginJvm
import desu.inugram.helpers.plugins.platform.PluginNotifications
import desu.inugram.helpers.plugins.platform.PluginXposed
import desu.inugram.helpers.plugins.telegram.PluginReads
import desu.inugram.helpers.plugins.telegram.PluginRpc
import desu.inugram.helpers.plugins.telegram.PluginSendHold
import desu.inugram.helpers.plugins.telegram.PluginUpdates
import desu.inugram.helpers.plugins.telegram.PluginWrites
import desu.inugram.helpers.plugins.tl.TlHandles
import desu.inugram.helpers.plugins.tl.TlReflect
import desu.inugram.helpers.plugins.ui.PluginActions
import desu.inugram.helpers.plugins.ui.PluginCanvas
import desu.inugram.jvmfixture.JvmFixture
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.telegram.SQLite.SQLiteDatabase
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.RequestDelegate
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_update

/** the app's own singletons, so what a test changed has to be changed back */
fun resetBridge() {
    ApplicationLoader.applicationContext = deviceContext()
    goOffline()
    for (plugin in installedPlugins()) plugin.session?.let { session ->
        session.engine.stopCallbacks()
        PluginXposed.detach(session)
        PluginJvm.detach(session)
        PluginActions.detach(session)
        PluginNotifications.detach(session)
        PluginCanvas.detach(session)
        session.stopDispatching()
        session.tl.releaseAll()
        if (session.engine !is RecordingQuickJs) closeEngine(plugin)
    }
    JvmFixture.tag = "static"
    JvmFixture.shared = null
    JvmFixture.task = null
    JvmFixture.callbackEntered = null
    JvmFixture.callbackRelease = null
    JvmFixture.sharedHookCalls.set(0)
    flushUi()
    clearPluginObservers()
    setInstalledPlugins(emptyList())
    TestQueues.install()
    RecordingConnectionsManager.reset()
    TestApp.reset()
    clearRpcState()
}

/**
 * stock's native `ConnectionsManager::select()` calls `JniAbort` on a response it cannot parse under
 * CheckJNI, killing the process mid-test, so native networking is paused for every account
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

/** storage keyed by install id is real here and outlives the test */
fun freshInstallId(): String = "%032x".format(nextInstallId.getAndIncrement() or (System.nanoTime() shl 16))

private fun clearRpcState() {
    for (owner in listOf(PluginRpc, PluginUpdates, PluginSendHold)) {
        for (field in owner.javaClass.declaredFields) {
            field.isAccessible = true
            when (field.name) {
                "interceptorsByMethod", "updateListenersByType", "updateInterceptorsByType" ->
                    field.set(owner, emptyMap<String, Any>())
                "updateRegs", "updateInterceptRegs" -> field.set(owner, emptyList<Any>())
                "hasInterceptors", "hasUpdateListeners", "hasUpdateInterceptors" -> field.setBoolean(owner, false)
                else -> when (val value = field.get(owner)) {
                    is MutableMap<*, *> -> value.clear()
                    is MutableCollection<*> -> value.clear()
                }
            }
        }
    }
}

private fun snapshotField() =
    PluginManager::class.java.getDeclaredField("snapshot").apply { isAccessible = true }

@Suppress("UNCHECKED_CAST")
fun installedPlugins(): List<Plugin> = snapshotField().get(PluginManager) as List<Plugin>

fun setInstalledPlugins(plugins: List<Plugin>) {
    snapshotField().set(PluginManager, plugins)
    PluginManager.refreshAnyRunning()
}

fun deviceContext(): android.content.Context =
    InstrumentationRegistry.getInstrumentation().targetContext

/** stock `AndroidUtilities.runOnUIThread` always posts */
fun flushUi() = InstrumentationRegistry.getInstrumentation().runOnMainSync {}

/** [NotificationCenter] may only be touched from the ui thread */
fun onUi(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

fun NotificationCenter.postOnUi(id: Int, vararg args: Any?) = onUi { postNotificationName(id, *args) }

fun drain(): Int = TestQueues.drain()

fun settle() {
    // a ui flush can post back onto a queue just found empty
    var idle = 0
    repeat(16) {
        val ran = drain()
        flushUi()
        idle = if (ran == 0) idle + 1 else 0
        if (idle == 2) return
    }
}

/** for work that hops through stock's own queues, which the harness does not drive */
fun <T : Any> awaitValue(what: String, probe: () -> T?): T {
    repeat(100) {
        settle()
        probe()?.let { return it }
        Thread.sleep(20)
    }
    throw AssertionError(what)
}

fun startEngine(
    name: String,
    vararg grants: String,
    canvas: Boolean = false,
    localStoragePath: String = "",
    onLog: (String) -> Unit = {},
): Plugin {
    val plugin = startPlugin(name, *grants)
    val session = PluginSession(plugin, QuickJs())
    plugin.session = session
    if (canvas) PluginCanvas.wipe(plugin.id)
    attachBridge(
        session,
        object : CoreListener {
            override fun onConsole(level: Int, message: String) = onLog(message)
            override fun onTimerSchedule(delayMs: Long) = Unit
        },
        canvas = if (canvas) PluginCanvas.listenerFor(session) else DeviceMissing,
        spillDir = if (canvas) PluginBlobs.dirFor(plugin.id) else "",
        localStoragePath = localStoragePath,
    )
    return plugin
}

fun closeEngine(plugin: Plugin) {
    val session = plugin.session ?: return
    session.engine.stopCallbacks()
    PluginXposed.detach(session)
    PluginJvm.detach(session)
    PluginCanvas.detach(session)
    session.stopDispatching()
    session.engine.close()
    plugin.session = null
    PluginManager.refreshAnyRunning()
}

/** as an app thread calling into a plugin would */
fun runOnCaller(task: Runnable, name: String = "caller"): Thread {
    val worker = Thread(task, name)
    worker.start()
    worker.join(5000)
    assertFalse(worker.isAlive, "$name never returned")
    return worker
}

fun Plugin.js(code: String): String = engine!!.evaluate(code.trimIndent()) ?: "null"

/** the resolved value, as JSON */
fun Plugin.await(code: String, timeoutMillis: Long = 20_000, pollMillis: Long = 20): String {
    js(
        "globalThis.done = false; globalThis.failure = null; ($code)"
            + ".then((value) => { globalThis.result = value; globalThis.done = true })"
            + ".catch((e) => { globalThis.failure = e && e.stack ? String(e) + '\\n' + e.stack : String(e); globalThis.done = true })",
    )
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        settle()
        if (js("String(globalThis.done)") == "true") {
            val failure = js("String(globalThis.failure)")
            if (failure != "null") error(failure)
            return js("JSON.stringify(globalThis.result ?? null)")
        }
        Thread.sleep(pollMillis)
    }
    error("promise never settled")
}

fun connections(account: Int = 0): RecordingConnectionsManager = RecordingConnectionsManager.forAccount(account)

fun advanceBy(millis: Long) = TestQueues.advanceBy(millis)

fun sendThroughPlugins(
    request: TLObject,
    token: Int = 11,
    account: Int = 0,
    onDone: RequestDelegate? = RequestDelegate { _, _ -> },
): Boolean = PluginRpc.maybeIntercept(connections(account), request, onDone, null, null, null, 0, 0, 0, false, token, account)

object testAppScreen : PluginJvm.AppScreen {
    var fragment: Any? = null
    var activity: Any? = null

    override fun currentFragment(): Any? = fragment

    override fun currentActivity(): Any? = activity
}

fun startPlugin(name: String, vararg grants: String): Plugin =
    startPlugin(name, grants.toList()) {}

fun startPlugin(name: String, grants: List<String>, configureEngine: (RecordingQuickJs) -> Unit): Plugin {
    testAppScreen.fragment = null
    testAppScreen.activity = null
    val plugin = Plugin(
        id = "%032x".format(name.hashCode().toLong() and 0xffffffffL),
        file = File("/dev/null"),
        source = "",
        manifest = createManifest(name, grants),
    )
    plugin.session = PluginSession(plugin, RecordingQuickJs().also(configureEngine))
    setInstalledPlugins(installedPlugins() + plugin)
    attachBridge(plugin.session!!)
    return plugin
}

fun attachBridge(
    session: PluginSession,
    core: CoreListener = DeviceMissing,
    canvas: CanvasListener = DeviceMissing,
    ui: UiListener = DeviceMissing,
    accountsJson: (() -> String)? = null,
    spillDir: String = "",
    transferDir: String = "",
    localStoragePath: String = "",
) {
    val tl = session.tl
    val jvm = PluginJvm.listenerFor(session, testAppScreen)
    val bridge = PluginBridge(
        core = core,
        rpc = PluginRpc.listenerFor(session),
        updates = PluginUpdates.listenerFor(session),
        tl = tl,
        account = object : AccountListener,
            ReadsListener by PluginReads.listenerFor(session),
            WritesListener by PluginWrites.listenerFor(session) {
            override fun accounts(): String =
                accountsJson?.invoke() ?: throw UnsupportedOperationException("this suite has no accounts")
        },
        ui = ui,
        platform = DeviceMissing,
        fetch = PluginFetch.listenerFor(session),
        canvas = canvas,
        notifications = PluginNotifications.listenerFor(session),
        jvm = jvm,
        xposed = PluginXposed.listenerFor(session, jvm),
    )
    session.engine.start(
        bridge,
        QuickJs.Config(
            spillDir = spillDir,
            transferDir = transferDir,
            fsDir = "",
            fsQuotaBytes = 0,
            fsUnscoped = false,
            installFs = false,
            androidDirs = "",
            localStoragePath = localStoragePath,
            installJvm = bridge.jvm != null,
            installXposed = bridge.xposed != null,
            grants = session.permissions,
        ),
    )
}

internal object DeviceMissing : CoreListener, UiListener, PlatformListener, CanvasListener {
    override fun onConsole(level: Int, message: String) = throw UnsupportedOperationException("this suite has no console")

    override fun onTimerSchedule(delayMs: Long) = throw UnsupportedOperationException("this suite has no timer scheduler")

    override fun canvas(op: Int, id: Long, arg: String, bytes: ByteArray?) = throw UnsupportedOperationException("this suite has no canvas")

    override fun uiToast(text: String) = throw UnsupportedOperationException("this suite has no ui")

    override fun uiModal(op: Int, requestId: Long, optionsJson: String) = throw UnsupportedOperationException("this suite has no ui")

    override fun uiCurrentScreen() = throw UnsupportedOperationException("this suite has no ui")

    override fun format(op: Int, value: Long) = throw UnsupportedOperationException("this suite has no formatting")

    override fun openUrl(url: String) = throw UnsupportedOperationException("this suite has no ui")

    override fun clipboardRead() = throw UnsupportedOperationException("this suite has no clipboard")

    override fun clipboardWrite(text: String) = throw UnsupportedOperationException("this suite has no clipboard")

    override fun uiOpenPage(pageId: Long) = throw UnsupportedOperationException("this suite has no ui")

    override fun uiOpenFragment(handle: Long) = throw UnsupportedOperationException("this suite has no ui")

    override fun uiOpenScreen(optionsJson: String) = throw UnsupportedOperationException("this suite has no ui")

    override fun uiRegisterSettings(pageId: Long) = throw UnsupportedOperationException("this suite has no ui")

    override fun uiUnregisterSettings(pageId: Long) = throw UnsupportedOperationException("this suite has no ui")

    override fun uiInvalidate(pageId: Long) = throw UnsupportedOperationException("this suite has no ui")

    override fun uiOpenMenu(menuId: Long, pageId: Long, anchorKey: String, itemsJson: String) = throw UnsupportedOperationException("this suite has no ui")

    override fun iconResolves(kind: Int, value: String) = throw UnsupportedOperationException("this suite has no icons")

    override fun commonIcon(name: String) = throw UnsupportedOperationException("this suite has no icons")

    override fun actionRegister(
        kind: Int,
        token: Int,
        id: String,
        placements: Int,
        text: String?,
        icon: String?,
        dynamicFields: Int,
    ) = throw UnsupportedOperationException("this suite has no actions")

    override fun actionUnregister(kind: Int, token: Int) = throw UnsupportedOperationException("this suite has no actions")

    override fun actionEditor(op: Int, surface: Long, payloadJson: String) = throw UnsupportedOperationException("this suite has no actions")
}

fun createManifest(name: String, grants: List<String>): PluginManifest = PluginManifest(
    name = name,
    author = null,
    declaredId = null,
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

fun Plugin.interceptRpc(vararg methods: String, callbackId: Int = 1, strict: Boolean = false): String? =
    js.listener!!.onRpcRegister(arrayOf(*methods), callbackId, "", strict, "")

/** mirrors `SEND_METHODS` in `rpc.rs` */
val SEND_METHODS = arrayOf(
    "messages.sendMessage",
    "messages.sendMedia",
    "messages.sendMultiMedia",
    "messages.editMessage",
)

fun Plugin.interceptSendMessage(callbackId: Int = 1, filterJson: String = ""): String? =
    js.listener!!.onRpcRegister(SEND_METHODS, callbackId, "interceptSendMessage", true, filterJson)

fun Plugin.interceptUpdate(vararg types: String, callbackId: Int = 1): String? =
    js.listener!!.onInterceptUpdateRegister(callbackId, arrayOf(*types))

fun Plugin.updateVerdict(dispatchId: Long, deliver: Boolean) =
    js.listener!!.onUpdateVerdict(dispatchId, deliver)

fun Plugin.onUpdate(vararg types: String, callbackId: Int = 1): String? =
    js.listener!!.onUpdateRegister(callbackId, arrayOf(*types), "")

/** mirrors `DEMUX_EVENTS` in `rpc.rs` */
enum class DemuxedEvent(val scope: String, val types: Array<String>) {
    NEW_MESSAGE("new_message", arrayOf("updateNewMessage", "updateNewChannelMessage")),
    EDIT_MESSAGE("edit_message", arrayOf("updateEditMessage", "updateEditChannelMessage")),
    DELETE_MESSAGE("delete_message", arrayOf("updateDeleteMessages", "updateDeleteChannelMessages")),
}

fun Plugin.onDemuxedEvent(event: DemuxedEvent, callbackId: Int = 1): String? =
    js.listener!!.onUpdateRegister(callbackId, event.types, event.scope)

fun Plugin.next(dispatchId: Long, requestWire: String): String? =
    js.listener!!.onRpcNext(dispatchId, requestWire)

fun Plugin.complete(dispatchId: Long, resultWire: String) =
    js.listener!!.onRpcComplete(dispatchId, resultWire)

fun Plugin.tl(): TlListener = js.listener!!

fun getTlHandles(plugin: Plugin): TlHandles? = plugin.session?.tl

fun pluginObserverCount(): Int {
    var total = pluginObserverCount(NotificationCenter.getGlobalInstance())
    for (account in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
        total += pluginObserverCount(NotificationCenter.getInstance(account))
    }
    return total
}

/** tests drop engines without detaching, so the sweep is by package rather than by owner */
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

fun bundledPlugin(name: String): String {
    val assets = InstrumentationRegistry.getInstrumentation().context.assets
    return listOf("test-prelude.js", name).joinToString("") { file ->
        assets.open("inu_plugins/$file").bufferedReader().use { it.readText() }
    }
}

data class Oracle(val plugin: Plugin, val lines: List<String>)

fun startOracle(name: String): Oracle {
    val source = bundledPlugin(name)
    val lines = java.util.Collections.synchronizedList(ArrayList<String>())
    val grants = desu.inugram.core.plugins.PluginManifestParser.parse(source).grants
    val plugin = startEngine(name, *grants.toTypedArray(), onLog = { lines.add(it) })
    plugin.engine!!.evaluate(source)
    return Oracle(plugin, lines)
}

fun assertOracleExact(lines: List<String>, done: String, count: Int) {
    val snapshot = synchronized(lines) { lines.toList() }
    kotlin.test.assertTrue(snapshot.none { it.startsWith("FAIL") || it.startsWith("SKIP") }, snapshot.joinToString("\n"))
    kotlin.test.assertTrue(done in snapshot, "the oracle did not finish: $snapshot")
    kotlin.test.assertEquals(count, snapshot.count { it.startsWith("PASS") }, snapshot.joinToString("\n"))
}

fun testAsset(name: String): ByteArray =
    InstrumentationRegistry.getInstrumentation().context.assets.open("inu/$name").use { it.readBytes() }

fun deliverUpdates(updates: TLRPC.Updates, account: Int = 0, fromQueue: Boolean = false): Boolean =
    PluginUpdates.onUpdates(TestApp.updatesController(account), updates, account, fromQueue)

fun applied(account: Int = 0): List<TLRPC.Updates> = TestApp.updatesController(account).processed

class DifferenceRun(
    val newMessages: MutableList<TLRPC.Message>,
    val otherUpdates: MutableList<TLRPC.Update>,
) {
    var applied = 0
}

fun deliverDifference(
    newMessages: List<TLRPC.Message> = emptyList(),
    otherUpdates: List<TLRPC.Update> = emptyList(),
    account: Int = 0,
): DifferenceRun {
    val run = DifferenceRun(ArrayList(newMessages), ArrayList(otherUpdates))
    lateinit var runnable: Runnable
    runnable = Runnable {
        if (PluginUpdates.onDifference(run.newMessages, run.otherUpdates, account, runnable)) return@Runnable
        run.applied++
    }
    runnable.run()
    return run
}

fun peerUser(id: Long): TLRPC.TL_peerUser = TLRPC.TL_peerUser().apply { user_id = id }

fun peerChannel(id: Long): TLRPC.TL_peerChannel = TLRPC.TL_peerChannel().apply { channel_id = id }

/** stock `readParams` leaves flags in sync with fields; a hand-built object has to catch up */
fun <T : TLObject> T.synced(): T = apply { TlReflect.syncFlags(this) }

fun serviceMessage(text: String, id: Int = 1): TLRPC.TL_message = TLRPC.TL_message().apply {
    this.id = id
    message = text
    from_id = peerUser(777000L)
    peer_id = peerUser(100L)
}.synced()

fun user(id: Long, username: String? = null): TLRPC.TL_user = TLRPC.TL_user().apply {
    this.id = id
    this.username = username
    // stock writes the display name into a service message preview
    first_name = username ?: "user$id"
    access_hash = id * 10
}

fun broadcast(id: Long, username: String? = null): TLRPC.TL_channel = TLRPC.TL_channel().apply {
    this.id = id
    this.username = username
    access_hash = id * 10
    broadcast = true
}

fun basicGroup(id: Long): TLRPC.TL_chat = TLRPC.TL_chat().apply { this.id = id }

fun withMedia(id: Int = 4242): TLRPC.TL_message = TLRPC.TL_message().apply {
    this.id = id
    message = ""
    val document = TLRPC.TL_document().apply {
        this.id = 99L
        access_hash = 1L
        dc_id = 2
        size = 11L
        mime_type = "text/plain"
        attributes.add(TLRPC.TL_documentAttributeFilename().apply { file_name = "note.txt" })
    }
    // flag bits are per object, so the media needs its own sync
    media = TLRPC.TL_messageMediaDocument().apply { this.document = document }.synced()
}.synced()

fun newMessage(id: Int, peer: TLRPC.Peer = peerUser(7L), text: String = "m$id"): TL_update.TL_updateNewMessage =
    TL_update.TL_updateNewMessage().apply {
        message = TLRPC.TL_message().apply {
            this.id = id
            peer_id = peer
            message = text
        }.synced()
    }

fun createUpdatesBatch(vararg updates: TLRPC.Update): TLRPC.TL_updates =
    TLRPC.TL_updates().apply { this.updates = ArrayList(updates.toList()) }

fun write(
    plugin: Plugin,
    op: Int,
    arg: JSONObject,
    values: Array<String> = emptyArray(),
    requestId: Long = 1L,
    account: Int = 0,
): String? = plugin.js.listener!!.accountWrite(account, requestId, op, arg.toString(), values)

/** the harness does not replace stock's storage queue */
private fun onStorage(block: (SQLiteDatabase) -> Unit) {
    val storage = MessagesStorage.getInstance(0)
    val latch = CountDownLatch(1)
    var failure: Throwable? = null
    storage.storageQueue.postRunnable {
        try {
            block(assertNotNull(storage.getDatabase(), "the test process has no database"))
        } catch (e: Throwable) {
            failure = e
        } finally {
            latch.countDown()
        }
    }
    assertTrue(latch.await(10, TimeUnit.SECONDS), "the storage queue never ran")
    failure?.let { throw it }
}

fun storeMessageOnDisk(dialogId: Long, message: TLRPC.Message) = onStorage { database ->
    val state = database.executeFast(
        "REPLACE INTO messages_v2 (mid, uid, read_state, send_state, date, data, out, ttl, media, imp, " +
            "mention, forwards, thread_reply_id, is_channel, reply_to_message_id, group_id, reply_to_story_id) " +
            "VALUES(?, ?, 0, 0, 0, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)",
    )
    state.bindInteger(1, message.id)
    state.bindLong(2, dialogId)
    state.bindTlObject(3, message)
    state.step()
    state.dispose()
}

fun deleteMessageOnDisk(id: Int) = onStorage { it.executeFast("DELETE FROM messages_v2 WHERE mid = $id").stepThis().dispose() }

fun handleId(wire: String): Long = (PluginWire.decode(wire) as PluginWire.Value.Handle).id

fun decodeHandle(wire: String): PluginWire.Value.Handle = PluginWire.decode(wire) as PluginWire.Value.Handle

fun decodeJson(wire: String): JSONObject = JSONObject((PluginWire.decode(wire) as PluginWire.Value.Json).json)

fun readTlField(plugin: Plugin, wire: String, key: String): String = plugin.tl().tlGet(handleId(wire), key)

fun decodeString(wire: String): String = (PluginWire.decode(wire) as PluginWire.Value.Str).value

fun Plugin.jvm(op: Int, target: Long = 0, name: String = "", vararg args: String): String =
    engine!!.listener!!.jvm(op, target, name, arrayOf(*args))

fun Plugin.xposed(op: Int, target: Long, name: String = "", vararg args: String): String =
    engine!!.listener!!.xposed(op, target, name, arrayOf(*args))

fun jvmHandleId(wire: String): Long {
    assertTrue(wire.length > 2 && wire[0] == 'G', "not a jvm handle: $wire")
    return wire.substring(2).toLong()
}

fun Plugin.jvmHandle(value: Any): Long = jvmHandleId(PluginJvm.bridgeFor(engine!!)!!.encode(value))

/** the wire a plugin hands back to name [value] */
fun Plugin.jvmWire(value: Any): String = "G${jvmHandle(value)}"

fun Plugin.hookWithBefore(member: Long): Long {
    val site = decodeString(xposed(PluginXposed.OP_HOOK, member)).toLong()
    xposed(PluginXposed.OP_JS_BEFORES, site, "1")
    return site
}

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

/** chains abandon before the handle table is released, as in `PluginManager.teardown` */
fun detachPlugin(plugin: Plugin) {
    plugin.session!!.stopDispatching()
    PluginRpc.detach(plugin.session!!)
    PluginUpdates.detach(plugin.session!!)
    plugin.session!!.tl.releaseAll()
}
