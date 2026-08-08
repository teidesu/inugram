package desu.inugram.helpers.plugins

/**
 * A [QuickJs] that records instead of entering an engine.
 *
 * A subclass rather than a stand-in class: this runs in the app's own process, where the real
 * [QuickJs] is already on the classpath, so the JVM harness's trick of compiling a same-named double
 * in its place is not available. [start] is overridden to skip `nativeCreate`, which leaves `ptr` at
 * 0, so any member *not* overridden here throws rather than reaching a context that was never made.
 *
 * Everything an engine would do in JS is a recorded call plus an optional hook, which is how a test
 * plays a middleware.
 */
class RecordingQuickJs : QuickJs() {
    class Dispatch(val callbackId: Int, val dispatchId: Long, val method: String, val accountId: Int, val requestWire: String)
    class DeserializeDispatch(val callbackId: Int, val objectWire: String)
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
    class ActionDispatch(val kind: Int, val token: Int, val surfaceJson: String)

    val deserializeDispatches = ArrayList<DeserializeDispatch>()
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
    val actionRenders = ArrayList<Pair<Int, String>>()
    val actionDispatches = ArrayList<ActionDispatch>()

    /** callback ids java asked to run, in the order the engine would have run them */
    val jvmCallbacks = ArrayList<Int>()

    var rpcInstalled = false
        private set

    var jvmInstalled = false
        private set

    /** what this engine does with the view it is handed, standing in for the plugin's middleware */
    var onDispatchDeserialize: ((DeserializeDispatch) -> Unit)? = null

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

    /** what this engine answers a menu render with; `null` is an engine that could not answer */
    var onRenderActions: ((Int, String) -> String?)? = null

    override fun start(listener: PluginBridge) {
        check(this.listener == null) { "QuickJs is already started" }
        this.listener = listener
    }

    override fun close() = Unit

    override fun installRpc() {
        rpcInstalled = true
    }

    override fun installJvm() {
        jvmInstalled = true
    }

    override fun jvmCallback(callbackId: Int) {
        jvmCallbacks.add(callbackId)
    }

    override fun dispatchRpc(callbackId: Int, dispatchId: Long, method: String, accountId: Int, requestWire: String) {
        val dispatch = Dispatch(callbackId, dispatchId, method, accountId, requestWire)
        dispatches.add(dispatch)
        onDispatchRpc?.invoke(dispatch)
    }

    override fun resolvePeerResult(requestId: Long, resultWire: String) {
        peerResults.add(PeerResult(requestId, resultWire))
    }

    override fun accountFetchResult(requestId: Long, resultWire: String) {
        fetchResults.add(FetchResult(requestId, resultWire))
    }

    /** the global `fetch`, as opposed to [accountFetchResult]'s `Account` reads */
    override fun fetchResult(requestId: Long, resultWire: String) {
        httpResults.add(HttpResult(requestId, resultWire))
    }

    override fun writeResult(requestId: Long, resultWire: String) {
        val result = WriteResult(requestId, resultWire)
        writeResults.add(result)
        onWriteResult?.invoke(result)
    }

    override fun writeProgress(requestId: Long, loaded: Long, total: Long) {
        writeProgress.add(WriteProgress(requestId, loaded, total))
    }

    override fun dispatchNotification(callbackId: Int, name: String, accountId: Int, argsJson: String) {
        notifications.add(Notification(callbackId, name, accountId, argsJson))
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

    override fun resolveInvoke(invokeId: Long, resultWire: String) {
        invokes.add(Invoke(invokeId, resultWire))
    }

    override fun dispatchUpdate(typeName: String, accountId: Int, updateWire: String) {
        updates.add(Update(typeName, accountId, updateWire))
    }

    override fun dispatchUpdateIntercept(callbackId: Int, dispatchId: Long, typeName: String, accountId: Int, updateWire: String) {
        val dispatch = UpdateDispatch(callbackId, dispatchId, typeName, accountId, updateWire)
        updateDispatches.add(dispatch)
        onDispatchUpdateIntercept?.invoke(dispatch)
    }

    override fun dispatchDeserialize(callbackId: Int, objectWire: String) {
        val dispatch = DeserializeDispatch(callbackId, objectWire)
        deserializeDispatches.add(dispatch)
        onDispatchDeserialize?.invoke(dispatch)
    }

    override fun abandonUpdateDispatch(dispatchId: Long) {
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
