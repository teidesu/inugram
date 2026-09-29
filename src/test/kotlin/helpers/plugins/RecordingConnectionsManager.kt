package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.telegram.PluginRpc
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.QuickAckDelegate
import org.telegram.tgnet.RequestDelegate
import org.telegram.tgnet.RequestDelegateTimestamp
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.WriteToSocketDelegate

/**
 * A [ConnectionsManager] that records instead of reaching the network: it never answers on its own,
 * so a test answers the send it wants to, which is how a passthrough racing a cancel or stock's
 * `CONNECTION_NOT_INITED` re-send is written.
 *
 * Built by [allocate], **without running any constructor**: the real one calls `native_init` and
 * opens real connections. That leaves every field it would have set at zero, so nothing inherited
 * may be called - only the members overridden below.
 */
class RecordingConnectionsManager : ConnectionsManager {
    class Sent(
        val request: TLObject,
        val onComplete: RequestDelegate?,
        val onCompleteTimestamp: RequestDelegateTimestamp?,
        val requestToken: Int,
    ) {
        /** answers the delegate the way stock's own tail does, on stageQueue */
        fun answer(response: TLObject?, error: TLRPC.TL_error?, responseTime: Long = 0L) {
            Utilities.stageQueue.postRunnable {
                if (onCompleteTimestamp != null) {
                    onCompleteTimestamp.run(response, error, responseTime)
                } else {
                    onComplete?.run(response, error)
                }
                response?.freeResources()
            }
        }
    }

    class Cancel(val token: Int, val notifyServer: Boolean, val onCancelled: Runnable?)

    // [allocate] runs no constructor, so no field initializer here runs either: every one of these
    // starts as the zero value and has to build itself on first read.
    private var sentList: ArrayList<Sent>? = null
    private var cancelList: ArrayList<Cancel>? = null

    val sent: ArrayList<Sent> get() = sentList ?: ArrayList<Sent>().also { sentList = it }

    val cancels: ArrayList<Cancel> get() = cancelList ?: ArrayList<Cancel>().also { cancelList = it }

    @JvmField var inu_currentTimeMillis: Long = 0

    private var lastRequestToken = 0

    /**
     * The app's own networking is **live in this process**: the real tgnet library is loaded and its
     * `onUpdate` callback drives `MessagesController.updateTimerProc` on stock's own `stageQueue`
     * thread, which sends `help.getPromoData`, `help.getTermsOfServiceUpdate` and
     * `account.updateStatus` at moments no test controls. Recorded, those land in [sent] beside the
     * request a test is actually about, and `lastSent()` then answers the wrong one.
     *
     * Everything the bridge causes arrives on the thread that drains the queues, i.e. the one that
     * installed this recorder. Anything on another thread is the app talking to itself, and is
     * dropped rather than recorded or intercepted.
     */
    private var testThread: Thread? = null

    private constructor() : super(0)

    override fun getCurrentTimeMillis(): Long = inu_currentTimeMillis

    override fun sendRequest(request: TLObject, onComplete: RequestDelegate): Int =
        sendRequest(request, onComplete, 0)

    /**
     * Stock posts the internal call to stageQueue and answers the token first; here it is
     * synchronous, so a test can read [lastSent] without draining. What the hop buys - a cancel
     * arriving before the send it names - is [sendRequestInternal]'s own to exercise, and the tests
     * that do call it directly.
     */
    override fun sendRequest(request: TLObject, onComplete: RequestDelegate, flags: Int): Int {
        val requestToken = ++lastRequestToken
        sendRequestInternal(request, onComplete, null, null, null, flags, 0, 0, true, requestToken)
        return requestToken
    }

    /** the hook and the request free are stock's, in stock's order */
    override fun sendRequestInternal(
        request: TLObject,
        onComplete: RequestDelegate?,
        onCompleteTimestamp: RequestDelegateTimestamp?,
        onQuickAck: QuickAckDelegate?,
        onWriteToSocket: WriteToSocketDelegate?,
        flags: Int,
        datacenterId: Int,
        connectionType: Int,
        immediate: Boolean,
        requestToken: Int,
    ) {
        if (Thread.currentThread() !== testThread) {
            request.freeResources()
            return
        }
        if (PluginRpc.maybeIntercept(
                this, request, onComplete, onCompleteTimestamp, onQuickAck, onWriteToSocket,
                flags, datacenterId, connectionType, immediate, requestToken, currentAccount,
            )
        ) {
            return
        }
        request.freeResources()
        sent.add(Sent(request, onComplete, onCompleteTimestamp, requestToken))
    }

    /** stock's CONNECTION_NOT_INITED path: the same instance, a fresh token, delegate untouched */
    fun inu_retryNotInited(original: Sent, newToken: Int) {
        sendRequestInternal(
            original.request, original.onComplete, original.onCompleteTimestamp,
            null, null, 0, 0, 0, false, newToken,
        )
    }

    override fun cancelRequest(token: Int, notifyServer: Boolean, onCancelled: Runnable?) {
        cancels.add(Cancel(token, notifyServer, onCancelled))
    }

    fun lastSent(): Sent? = sent.lastOrNull()

    companion object {
        private val current = HashMap<Int, RecordingConnectionsManager>()

        /**
         * a fresh recorder per test, on **every** slot and before the test runs a line of it: a
         * lazily installed one is only there once a test has asked for it, and a read that sends
         * before that would reach the app's real manager and the network behind it.
         */
        fun reset() {
            current.clear()
            for (account in 0 until org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT) {
                current[account] = install(account)
            }
        }

        fun forAccount(account: Int): RecordingConnectionsManager =
            current.getOrPut(account) { install(account) }

        private val instanceArray: Array<ConnectionsManager?>
            @Suppress("UNCHECKED_CAST")
            get() = ConnectionsManager::class.java.getDeclaredField("Instance")
                .apply { isAccessible = true }
                .get(null) as Array<ConnectionsManager?>

        /**
         * `Instance` is `private static final`, but the *array* is not: only the reference to it is,
         * so a slot can be written
         */
        private fun install(account: Int): RecordingConnectionsManager {
            val recorder = allocate(account)
            instanceArray[account] = recorder
            return recorder
        }

        private fun allocate(account: Int): RecordingConnectionsManager {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val recorder = unsafeClass
                .getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, RecordingConnectionsManager::class.java) as RecordingConnectionsManager
            if (account != 0) {
                org.telegram.messenger.BaseController::class.java.getDeclaredField("currentAccount")
                    .apply { isAccessible = true }
                    .setInt(recorder, account)
            }
            fillContainers(recorder)
            recorder.inu_currentTimeMillis = 1_700_000_000_000L
            recorder.testThread = Thread.currentThread()
            // the overloads not overridden below are stock's own and still mint a token: `getRemote`
            // inside `MessagesController` reaches the 9-argument one, which posts to stageQueue and
            // lands back in `sendRequestInternal` here
            ConnectionsManager::class.java.getDeclaredField("lastRequestToken")
                .apply { isAccessible = true }
                .set(recorder, java.util.concurrent.atomic.AtomicInteger(1))
            return recorder
        }

        /**
         * **The native connection threads call back into this object**, and they are running for
         * the whole test run: `onRequestComplete` reads `requestCallbacks`, which a constructor-less
         * allocation leaves null. The NPE is raised inside a JNI upcall, so it is not an exception
         * any test can fail on - CheckJNI aborts the process, and it lands as an empty failure on
         * whichever test happened to be running.
         *
         * So every plain container the class declares is given the empty value its initializer
         * would have. This makes no claim about stock: a container is what a callback reads, and an
         * empty one is what it would have found before the test sent anything.
         */
        private fun fillContainers(recorder: RecordingConnectionsManager) {
            var cls: Class<*>? = ConnectionsManager::class.java
            while (cls != null && cls != Any::class.java) {
                for (field in cls.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                    if (field.type.name !in CONTAINER_TYPES) continue
                    field.isAccessible = true
                    if (field.get(recorder) == null) field.set(recorder, field.type.getConstructor().newInstance())
                }
                cls = cls.superclass
            }
        }

        private val CONTAINER_TYPES = setOf(
            "java.util.concurrent.ConcurrentHashMap",
            "java.util.HashMap",
            "java.util.ArrayList",
            "java.util.HashSet",
            "android.util.SparseArray",
            "android.util.LongSparseArray",
            "androidx.collection.LongSparseArray",
        )
    }
}
