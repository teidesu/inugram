package desu.inugram.helpers.plugins

/**
 * stands in for the real [QuickJs], which cannot exist here: its constructor calls `nativeCreate()`
 * and its class initializer loads `libinu_native`.
 *
 * Only the native side is stubbed. The listener surface is the real [PluginListener], compiled from
 * the app's own source, so a member added there reaches the bridge classes under test without
 * anything in this file changing.
 *
 * Everything an engine would do in JS is a recorded call plus an optional [onDispatchRpc] /
 * [onCompleteNext] hook, which is how a test plays a middleware.
 */
class QuickJs {
    companion object {
        const val ANY_ACCOUNT = -1
    }

    class Dispatch(val callbackId: Int, val dispatchId: Long, val method: String, val accountId: Int, val requestWire: String)
    class DeserializeDispatch(val callbackId: Int, val objectWire: String)

    val deserializeDispatches = ArrayList<DeserializeDispatch>()

    /** what this engine does with the view it is handed, standing in for the plugin's middleware */
    var onDispatchDeserialize: ((DeserializeDispatch) -> Unit)? = null

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

    /** set once, exactly as `PluginManager` does; nothing ever clears it */
    var listener: PluginBridge? = null

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
