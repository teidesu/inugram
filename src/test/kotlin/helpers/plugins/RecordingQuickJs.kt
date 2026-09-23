package desu.inugram.helpers.plugins

/** [start] skips `nativeCreate`, so any member not overridden here throws on `ptr == 0` */
class RecordingQuickJs : QuickJs() {
    class Dispatch(val callbackId: Int, val dispatchId: Long, val method: String, val accountId: Int, val requestWire: String)
    class Completion(val dispatchId: Long, val resultWire: String)
    class Abandon(val dispatchId: Long, val reasonWire: String)
    class Update(val typeName: String, val accountId: Int, val updateWire: String)
    class UpdateDispatch(val callbackId: Int, val dispatchId: Long, val typeName: String, val accountId: Int, val updateWire: String)
    class Invoke(val invokeId: Long, val resultWire: String)
    class InvokeBytes(val invokeId: Long, val response: ByteArray)
    class Settle(val api: Int, val requestId: Long, val wire: String)
    class ReadResult(val requestId: Long, val resultWire: String)
    class HttpResult(val requestId: Long, val resultWire: String)
    class WriteResult(val requestId: Long, val resultWire: String)
    class WriteProgress(val requestId: Long, val loaded: Long, val total: Long)
    class Notification(val callbackId: Int, val name: String, val accountId: Int, val args: Array<String>)
    class ActionDispatch(val kind: Int, val token: Int, val surfaceJson: String)
    class XposedBefore(val dispatchId: Long, val site: Long, val invocation: Array<Any?>) {
        val args: List<Any?> get() = invocation.drop(2)
    }
    class XposedAfter(val dispatchId: Long, val invocation: Array<Any?>, val threw: Boolean) {
        val args: List<Any?> get() = invocation.drop(2).dropLast(1)
        val result: Any? get() = invocation.last()
    }
    class XposedAfterOnly(val site: Long, val invocation: Array<Any?>, val threw: Boolean) {
        val args: List<Any?> get() = invocation.drop(2).dropLast(1)
        val result: Any? get() = invocation.last()
    }

    val dispatches = ArrayList<Dispatch>()
    val completions = ArrayList<Completion>()
    val abandons = ArrayList<Abandon>()
    val updates = ArrayList<Update>()
    val updateDispatches = ArrayList<UpdateDispatch>()
    val updateAbandons = ArrayList<Long>()
    val invokes = ArrayList<Invoke>()
    val invokeBytes = ArrayList<InvokeBytes>()
    val settles = ArrayList<Settle>()
    val readResults = ArrayList<ReadResult>()
    val httpResults = ArrayList<HttpResult>()
    val writeResults = ArrayList<WriteResult>()
    val writeProgress = ArrayList<WriteProgress>()
    val notifications = ArrayList<Notification>()
    val actionRenders = ArrayList<Pair<Int, String>>()
    val actionDispatches = ArrayList<ActionDispatch>()
    val xposedBefores = ArrayList<XposedBefore>()
    val xposedAfters = ArrayList<XposedAfter>()
    val xposedAfterOnlys = ArrayList<XposedAfterOnly>()
    val xposedReleases = ArrayList<Long>()

    val jvmCallbacks = ArrayList<Int>()

    var rpcInstalled = false
        private set

    var jvmInstalled = false
        private set

    var xposedInstalled = false
        private set

    var onDispatchRpc: ((Dispatch) -> Unit)? = null

    var onCompleteNext: ((Completion) -> Unit)? = null

    /** the real engine rejects a parked `await` synchronously, so plugin code runs inside this call */
    var onAbandonDispatch: ((Abandon) -> Unit)? = null

    var onDispatchUpdateIntercept: ((UpdateDispatch) -> Unit)? = null

    var onWriteResult: ((WriteResult) -> Unit)? = null

    var onRenderActions: ((Int, String) -> String?)? = null

    var onXposedBefore: ((XposedBefore) -> Array<String>?)? = null
    var onXposedAfter: ((XposedAfter) -> String?)? = null
    var onXposedAfterOnly: ((XposedAfterOnly) -> String?)? = null
    var xposedBudgetMillis = 1_000L

    override fun start(listener: PluginBridge, config: Config) {
        check(this.listener == null) { "QuickJs is already started" }
        this.listener = listener
        rpcInstalled = true
        jvmInstalled = config.installJvm
        xposedInstalled = config.installXposed
    }

    override fun close() = Unit

    /** `PluginJvm` mints through these without an engine, so they cannot refuse like the rest */
    private val handles = java.util.concurrent.ConcurrentHashMap<Long, Any>()
    private val nextHandle = java.util.concurrent.atomic.AtomicLong(1)

    @Volatile private var handlesOpen = true

    override fun jvmMint(value: Any, kind: Char): Long {
        if (!handlesOpen) return 0
        val id = nextHandle.getAndIncrement()
        handles[id] = value
        return id
    }

    override fun jvmObjectAt(id: Long): Any? = handles[id]

    override fun jvmRelease(id: Long) {
        handles.remove(id)
    }

    val liveHandles: Int get() = handles.size

    override fun jvmCloseHandles() {
        handlesOpen = false
        handles.clear()
    }

    override fun xposedBefore(dispatchId: Long, site: Long, invocation: Array<Any?>): Array<String>? {
        val dispatch = XposedBefore(dispatchId, site, invocation)
        xposedBefores.add(dispatch)
        return onXposedBefore?.invoke(dispatch)
    }

    override fun xposedAfter(dispatchId: Long, invocation: Array<Any?>, threw: Boolean): String? {
        val dispatch = XposedAfter(dispatchId, invocation, threw)
        xposedAfters.add(dispatch)
        return onXposedAfter?.invoke(dispatch)
    }

    override fun xposedAfterOnly(site: Long, invocation: Array<Any?>, threw: Boolean): String? {
        val dispatch = XposedAfterOnly(site, invocation, threw)
        xposedAfterOnlys.add(dispatch)
        return onXposedAfterOnly?.invoke(dispatch)
    }

    override fun xposedBudgetMs(): Long = xposedBudgetMillis

    override fun xposedRelease(dispatchId: Long) {
        xposedReleases.add(dispatchId)
    }

    override fun jvmCallback(callbackId: Int) {
        jvmCallbacks.add(callbackId)
    }

    override fun dispatchRpc(callbackId: Int, dispatchId: Long, method: String, accountId: Int, requestWire: String) {
        val dispatch = Dispatch(callbackId, dispatchId, method, accountId, requestWire)
        dispatches.add(dispatch)
        onDispatchRpc?.invoke(dispatch)
    }

    override fun settle(api: Int, requestId: Long, wire: String) {
        when (api) {
            SETTLE_READS -> readResults.add(ReadResult(requestId, wire))
            SETTLE_FETCH -> httpResults.add(HttpResult(requestId, wire))
            SETTLE_WRITES -> {
                val result = WriteResult(requestId, wire)
                writeResults.add(result)
                onWriteResult?.invoke(result)
            }
            SETTLE_INVOKE -> invokes.add(Invoke(requestId, wire))
            else -> settles.add(Settle(api, requestId, wire))
        }
    }

    override fun settleBytes(api: Int, requestId: Long, bytes: ByteArray) {
        invokeBytes.add(InvokeBytes(requestId, bytes))
    }

    override fun writeProgress(requestId: Long, loaded: Long, total: Long) {
        writeProgress.add(WriteProgress(requestId, loaded, total))
    }

    override fun dispatchNotification(callbackId: Int, name: String, accountId: Int, args: Array<String>) {
        notifications.add(Notification(callbackId, name, accountId, args))
    }

    override fun completeNext(dispatchId: Long, resultWire: String) {
        val completion = Completion(dispatchId, resultWire)
        completions.add(completion)
        onCompleteNext?.invoke(completion)
    }

    override fun abandonDispatch(dispatchId: Long, reasonWire: String) {
        val abandon = Abandon(dispatchId, reasonWire)
        abandons.add(abandon)
        onAbandonDispatch?.invoke(abandon)
    }

    override fun dispatchUpdate(typeName: String, accountId: Int, updateWire: String) {
        updates.add(Update(typeName, accountId, updateWire))
    }

    override fun dispatchUpdateIntercept(callbackId: Int, dispatchId: Long, typeName: String, accountId: Int, updateWire: String) {
        val dispatch = UpdateDispatch(callbackId, dispatchId, typeName, accountId, updateWire)
        updateDispatches.add(dispatch)
        onDispatchUpdateIntercept?.invoke(dispatch)
    }


    override fun abandonUpdateDispatch(dispatchId: Long, reasonWire: String) {
        updateAbandons.add(dispatchId)
    }

    override fun renderActions(kind: Int, surfaceJson: String): String? {
        actionRenders.add(kind to surfaceJson)
        return onRenderActions?.invoke(kind, surfaceJson)
    }

    override fun dispatchAction(kind: Int, token: Int, surfaceJson: String) {
        actionDispatches.add(ActionDispatch(kind, token, surfaceJson))
    }
}
