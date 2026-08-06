package desu.inugram.helpers.plugins

/**
 * stands in for the real [QuickJs], which cannot exist here: its constructor calls `nativeCreate()`
 * and its class initializer loads `libinu_native`.
 *
 * The two nested interfaces are declared, not stubbed away, so the bridge classes under test
 * implement *these*: if the real interface gains a method or changes a signature, the real
 * implementers stop compiling against this file and the harness fails loudly rather than testing a
 * shape stock no longer has.
 *
 * Everything an engine would do in JS is a recorded call plus an optional [onDispatchRpc] /
 * [onCompleteNext] hook, which is how a test plays a middleware.
 */
class QuickJs {
    companion object {
        const val ANY_ACCOUNT = -1
    }

    interface RpcListener {
        fun onRpcRegister(methods: Array<String>, callbackId: Int, scope: String): String?
        fun onRpcUnregister(callbackId: Int)
        fun onInvokeRpc(invokeId: Long, slot: Int, requestWire: String): String?
        fun onRpcNext(dispatchId: Long, requestWire: String): String?
        fun onRpcComplete(dispatchId: Long, resultWire: String)
        fun onUpdateRegister(callbackId: Int, types: Array<String>, scope: String): String?
        fun onUpdateUnregister(callbackId: Int)
        fun onInterceptUpdateRegister(callbackId: Int, types: Array<String>): String?
        fun onInterceptUpdateUnregister(callbackId: Int)
        fun onUpdateVerdict(dispatchId: Long, deliver: Boolean)
    }

    interface TlListener {
        fun tlGet(handle: Long, key: String): String
        fun tlSet(handle: Long, key: String, valueWire: String): String?
        fun tlHas(handle: Long, key: String): Int
        fun tlOwnKeys(handle: Long): String?
        fun tlCopy(handle: Long): String?
        fun tlRelease(handle: Long)
    }

    class Dispatch(val callbackId: Int, val dispatchId: Long, val method: String, val accountId: Int, val requestWire: String)
    interface ReadsListener {
        fun accountRead(accountId: Int, op: Int, arg: String): String
        fun resolvePeer(accountId: Int, requestId: Long, spec: String, kind: Int): String?
        fun accountFetch(accountId: Int, requestId: Long, op: Int, arg: String): String?
    }

    interface FetchListener {
        fun fetch(requestId: Long, url: String, specJson: String, body: ByteArray?): String?
        fun abort(requestId: Long)
    }

    interface WritesListener {
        fun accountWrite(accountId: Int, requestId: Long, op: Int, arg: String, values: Array<String>): String?
        fun messageFile(accountId: Int, value: String): String
    }

    interface NotificationListener {
        fun register(callbackId: Int, events: Array<String>): String?
        fun unregister(callbackId: Int)
    }

    interface DeserializeListener {
        fun onDeserializeRegister(callbackId: Int, rulesJson: String): String?
        fun onDeserializeUnregister(callbackId: Int)
        fun onDeserializeMiddlewareRegister(callbackId: Int, typesJson: String): String?
        fun onDeserializeMiddlewareUnregister(callbackId: Int)
    }

    class DeserializeDispatch(val callbackId: Int, val objectWire: String)

    val deserializeDispatches = ArrayList<DeserializeDispatch>()

    /** what this engine does with the view it is handed, standing in for the plugin's middleware */
    var onDispatchDeserialize: ((DeserializeDispatch) -> Unit)? = null

    interface JvmListener {
        fun jvm(op: Int, target: Long, name: String, args: Array<String>): String
    }

    class Completion(val dispatchId: Long, val resultWire: String)
    class Abandon(val dispatchId: Long, val reasonWire: String)
    class Update(val typeName: String, val accountId: Int, val updateWire: String)
    class UpdateDispatch(val callbackId: Int, val dispatchId: Long, val typeName: String, val accountId: Int, val updateWire: String)
    class Invoke(val invokeId: Long, val resultWire: String)
    class PeerResult(val requestId: Long, val resultWire: String)
    class FetchResult(val requestId: Long, val resultWire: String)
    class HttpResult(val requestId: Long, val resultWire: String)
    class WriteResult(val requestId: Long, val resultWire: String)
    class WriteProgress(val requestId: Long, val loaded: Long, val total: Long)
    class Notification(val callbackId: Int, val name: String, val accountId: Int, val argsJson: String)

    var rpcListener: RpcListener? = null
    var tlListener: TlListener? = null
    var readsListener: ReadsListener? = null
    var writesListener: WritesListener? = null
    var fetchListener: FetchListener? = null
    var notificationListener: NotificationListener? = null
    var deserializeListener: DeserializeListener? = null
    var jvmListener: JvmListener? = null

    var rpcInstalled = false
        private set

    var jvmInstalled = false
        private set

    /** callback ids java asked to run, in the order the engine would have run them */
    val jvmCallbacks = ArrayList<Int>()

    val dispatches = ArrayList<Dispatch>()
    val completions = ArrayList<Completion>()
    val abandons = ArrayList<Abandon>()
    val updates = ArrayList<Update>()
    val updateDispatches = ArrayList<UpdateDispatch>()
    val updateAbandons = ArrayList<Long>()
    val invokes = ArrayList<Invoke>()
    val peerResults = ArrayList<PeerResult>()
    val fetchResults = ArrayList<FetchResult>()
    val httpResults = ArrayList<HttpResult>()
    val writeResults = ArrayList<WriteResult>()
    val writeProgress = ArrayList<WriteProgress>()
    val notifications = ArrayList<Notification>()

    /** the middleware: called with every dispatch the host hands this engine */
    var onDispatchRpc: ((Dispatch) -> Unit)? = null

    /** what the middleware does when its own `await next()` settles */
    var onCompleteNext: ((Completion) -> Unit)? = null

    /**
     * the engine rejects a parked `await` synchronously here, so plugin code runs *inside* this
     * call - which is what makes the order of a collapse observable at all
     */
    var onAbandonDispatch: ((Abandon) -> Unit)? = null

    /** the `interceptUpdate` middleware: called with every update stage handed to this engine */
    var onDispatchUpdateIntercept: ((UpdateDispatch) -> Unit)? = null

    /** called as a write settles, so a test can see the world the way the settle saw it */
    var onWriteResult: ((WriteResult) -> Unit)? = null

    fun installRpc() {
        rpcInstalled = true
    }

    fun installJvm() {
        jvmInstalled = true
    }

    fun jvmCallback(callbackId: Int) {
        jvmCallbacks.add(callbackId)
    }

    fun dispatchRpc(callbackId: Int, dispatchId: Long, method: String, accountId: Int, requestWire: String) {
        val dispatch = Dispatch(callbackId, dispatchId, method, accountId, requestWire)
        dispatches.add(dispatch)
        onDispatchRpc?.invoke(dispatch)
    }

    fun resolvePeerResult(requestId: Long, resultWire: String) {
        peerResults.add(PeerResult(requestId, resultWire))
    }

    fun accountFetchResult(requestId: Long, resultWire: String) {
        fetchResults.add(FetchResult(requestId, resultWire))
    }

    /** the global `fetch`, as opposed to [accountFetchResult]'s `Account` reads */
    fun fetchResult(requestId: Long, resultWire: String) {
        httpResults.add(HttpResult(requestId, resultWire))
    }

    fun writeResult(requestId: Long, resultWire: String) {
        val result = WriteResult(requestId, resultWire)
        writeResults.add(result)
        onWriteResult?.invoke(result)
    }

    fun writeProgress(requestId: Long, loaded: Long, total: Long) {
        writeProgress.add(WriteProgress(requestId, loaded, total))
    }

    fun dispatchNotification(callbackId: Int, name: String, accountId: Int, argsJson: String) {
        notifications.add(Notification(callbackId, name, accountId, argsJson))
    }

    fun completeNext(dispatchId: Long, resultWire: String) {
        val completion = Completion(dispatchId, resultWire)
        completions.add(completion)
        onCompleteNext?.invoke(completion)
    }

    fun abandonDispatch(dispatchId: Long, reasonWire: String) {
        val abandon = Abandon(dispatchId, reasonWire)
        abandons.add(abandon)
        onAbandonDispatch?.invoke(abandon)
    }

    fun resolveInvoke(invokeId: Long, resultWire: String) {
        invokes.add(Invoke(invokeId, resultWire))
    }

    fun dispatchUpdate(typeName: String, accountId: Int, updateWire: String) {
        updates.add(Update(typeName, accountId, updateWire))
    }

    fun dispatchUpdateIntercept(callbackId: Int, dispatchId: Long, typeName: String, accountId: Int, updateWire: String) {
        val dispatch = UpdateDispatch(callbackId, dispatchId, typeName, accountId, updateWire)
        updateDispatches.add(dispatch)
        onDispatchUpdateIntercept?.invoke(dispatch)
    }

    fun dispatchDeserialize(callbackId: Int, objectWire: String) {
        val dispatch = DeserializeDispatch(callbackId, objectWire)
        deserializeDispatches.add(dispatch)
        onDispatchDeserialize?.invoke(dispatch)
    }

    fun abandonUpdateDispatch(dispatchId: Long) {
        updateAbandons.add(dispatchId)
    }

    class ActionDispatch(val kind: Int, val token: Int, val surfaceJson: String)

    /** what this engine answers a menu render with; `null` is an engine that could not answer */
    var onRenderActions: ((Int, String) -> String?)? = null

    val actionRenders = ArrayList<Pair<Int, String>>()
    val actionDispatches = ArrayList<ActionDispatch>()

    fun renderActions(kind: Int, surfaceJson: String): String? {
        actionRenders.add(kind to surfaceJson)
        return onRenderActions?.invoke(kind, surfaceJson)
    }

    fun dispatchAction(kind: Int, token: Int, surfaceJson: String) {
        actionDispatches.add(ActionDispatch(kind, token, surfaceJson))
    }
}
