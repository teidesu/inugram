package desu.inugram.helpers.plugins

import android.util.Log
import desu.inugram.helpers.plugins.tg.PluginRpc
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.MessagesController
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_update

/**
 * The `interceptUpdate` chain end to end: the app's `processUpdates` on one side, scripted
 * middlewares on the other, and the real queues (virtual, single-threaded) in between.
 *
 * The engine's half lives in `rpc.rs`'s tests; what only this target can reach is the part that
 * needs a batch, a chain and a queue at once - who is walked in what order, what the app is finally
 * handed, and what happens when a stage never answers.
 */
class PluginRpcUpdateChainTest {
    @Before
    fun setUp() = resetBridge()

    private fun newMessage(id: Int, text: String = "m$id"): TL_update.TL_updateNewMessage =
        TL_update.TL_updateNewMessage().apply {
            message = TLRPC.TL_message().apply {
                this.id = id
                peer_id = peerUser(7L)
                message = text
            }.synced()
        }

    private fun batchOf(vararg updates: TLRPC.Update): TLRPC.TL_updates =
        TLRPC.TL_updates().apply { this.updates = ArrayList(updates.toList()) }

    /** a plugin whose middleware answers [deliver] for every stage it is handed */
    private fun verdictPlugin(name: String, deliver: Boolean, vararg types: String): Plugin {
        val plugin = startPlugin(name, *types.map { "interceptUpdate($it)" }.toTypedArray())
        assertNull(plugin.interceptUpdate(*types))
        plugin.js.onDispatchUpdateIntercept = { plugin.updateVerdict(it.dispatchId, deliver) }
        return plugin
    }

    @Test
    fun `a batch nobody claims is left with the app`() {
        verdictPlugin("p", true, "updateUserTyping")

        assertFalse(deliverUpdates(batchOf(newMessage(1))))
        drain()

        assertEquals(0, applied().size, "the app kept the batch, so nothing is handed back")
    }

    @Test
    fun `a claimed batch is taken over and handed back once the chain settles`() {
        val plugin = verdictPlugin("p", true, "updateNewMessage")
        val batch = batchOf(newMessage(1))

        assertTrue(deliverUpdates(batch), "the app must not apply a batch a middleware is rewriting")
        assertEquals(0, applied().size)
        drain()

        assertEquals(1, plugin.js.updateDispatches.size)
        assertSame(batch, applied().singleOrNull(), "the very instance, rewritten in place")
    }

    @Test
    fun `the view a stage is handed is writable and scoped to the batch`() {
        val plugin = verdictPlugin("p", true, "updateNewMessage")
        val update = newMessage(1)

        deliverUpdates(batchOf(update))
        drain()

        val handle = handleOf(plugin.js.updateDispatches[0].updateWire)
        assertFalse(handle.readOnly, "rewriting in place is the point of this api")
        assertNull(plugin.resolved(handle.id), "the batch's scope is released before the app applies it")
    }

    @Test
    fun `a dropped update is taken out of the batch and the rest still arrives`() {
        val plugin = startPlugin("p", "interceptUpdate(updateNewMessage)")
        assertNull(plugin.interceptUpdate("updateNewMessage"))
        val doomed = newMessage(2)
        plugin.js.onDispatchUpdateIntercept = { dispatch ->
            plugin.updateVerdict(dispatch.dispatchId, plugin.resolved(handleId(dispatch.updateWire)) !== doomed)
        }

        deliverUpdates(batchOf(newMessage(1), doomed, newMessage(3)))
        drain()

        val applied = applied().single()
        assertEquals(listOf(1, 3), applied.updates.map { (it as TL_update.TL_updateNewMessage).message.id })
    }

    /**
     * a `TL_updates` that lost every update still carries the seq and date advance for the batch,
     * and withholding that desyncs strictly more than the drop already did
     */
    @Test
    fun `a batch every update of which was dropped is still handed over, empty`() {
        verdictPlugin("p", false, "updateNewMessage")

        deliverUpdates(batchOf(newMessage(1)))
        drain()

        assertEquals(0, applied().single().updates.size)
    }

    @Test
    fun `a dropped updateShort is the whole batch, since it is its update`() {
        verdictPlugin("p", false, "updateNewMessage")

        deliverUpdates(TLRPC.TL_updateShort().apply { update = newMessage(1) })
        drain()

        assertEquals(0, applied().size, "nothing of that arrival survived")
    }

    @Test
    fun `a drop ends the chain for that update and the stages below never see it`() {
        val first = verdictPlugin("a", false, "updateNewMessage")
        val second = verdictPlugin("b", true, "updateNewMessage")

        deliverUpdates(batchOf(newMessage(1)))
        drain()

        assertEquals(1, first.js.updateDispatches.size)
        assertEquals(0, second.js.updateDispatches.size, "a drop is a verdict, not a vote")
    }

    @Test
    fun `stages run in plugin-list order and a later drag re-orders them`() {
        val first = verdictPlugin("a", true, "updateNewMessage")
        val second = verdictPlugin("b", true, "updateNewMessage")
        val order = ArrayList<String>()
        for (plugin in listOf(first, second)) {
            plugin.js.onDispatchUpdateIntercept = {
                order.add(plugin.manifest.name)
                plugin.updateVerdict(it.dispatchId, true)
            }
        }

        deliverUpdates(batchOf(newMessage(1)))
        drain()
        assertEquals(listOf("a", "b"), order)

        PluginManager.installed = listOf(second, first)
        PluginRpc.refreshChainOrder()
        drain()
        order.clear()
        deliverUpdates(batchOf(newMessage(2)))
        drain()
        assertEquals(listOf("b", "a"), order)
    }

    @Test
    fun `a batch arriving while one is being walked waits for it rather than overtaking it`() {
        val plugin = startPlugin("p", "interceptUpdate(updateNewMessage)")
        assertNull(plugin.interceptUpdate("updateNewMessage"))
        val parked = ArrayList<Long>()
        plugin.js.onDispatchUpdateIntercept = { parked.add(it.dispatchId) }

        val first = batchOf(newMessage(1))
        val second = batchOf(newMessage(2))
        assertTrue(deliverUpdates(first))
        drain()
        // claimed or not, a later batch cannot walk past one still being rewritten
        assertTrue(deliverUpdates(batchOf(TL_update.TL_updateUserTyping())))
        assertTrue(deliverUpdates(second))
        drain()
        assertEquals(1, parked.size, "the second batch must not be walked yet")
        assertEquals(0, applied().size)

        plugin.updateVerdict(parked[0], true)
        drain()
        assertEquals(2, parked.size, "the queue moves on only once the batch ahead is done")
        plugin.updateVerdict(parked[1], true)
        drain()

        assertEquals(
            listOf(first, second),
            applied().filter { it !== applied()[1] },
            "arrival order survives the takeover",
        )
    }

    /**
     * dropping is the one verdict that desyncs pts, so a stall must never produce it - what the
     * plugin already decided stands, and everything else is delivered untouched
     */
    @Test
    fun `the budget expiring delivers everything still undecided`() {
        val plugin = startPlugin("p", "interceptUpdate(updateNewMessage)")
        assertNull(plugin.interceptUpdate("updateNewMessage"))
        var seen = 0
        plugin.js.onDispatchUpdateIntercept = { dispatch ->
            // the first update is dropped, the second parks forever
            if (seen++ == 0) plugin.updateVerdict(dispatch.dispatchId, false)
        }
        val batch = batchOf(newMessage(1), newMessage(2), newMessage(3))

        deliverUpdates(batch)
        drain()
        assertEquals(0, applied().size, "nothing is handed over while a stage is still thinking")

        advanceBy(2_000)
        drain()

        assertEquals(listOf(2, 3), applied().single().updates.map { (it as TL_update.TL_updateNewMessage).message.id })
        assertEquals(1, plugin.js.updateAbandons.size, "the parked stage is told it no longer matters")
    }

    @Test
    fun `a verdict that arrives after the budget expired changes nothing`() {
        val plugin = startPlugin("p", "interceptUpdate(updateNewMessage)")
        assertNull(plugin.interceptUpdate("updateNewMessage"))
        val parked = ArrayList<Long>()
        plugin.js.onDispatchUpdateIntercept = { parked.add(it.dispatchId) }

        deliverUpdates(batchOf(newMessage(1)))
        drain()
        advanceBy(2_000)
        drain()
        assertEquals(1, applied().single().updates.size)

        plugin.updateVerdict(parked[0], false)
        drain()
        assertEquals(1, applied().single().updates.size, "the app was already handed the update")
    }

    @Test
    fun `stopping a plugin mid-chain delivers its batch rather than losing it`() {
        val plugin = startPlugin("p", "interceptUpdate(updateNewMessage)")
        assertNull(plugin.interceptUpdate("updateNewMessage"))
        plugin.js.onDispatchUpdateIntercept = {}

        deliverUpdates(batchOf(newMessage(1)))
        drain()
        assertEquals(0, applied().size)

        Utilities.globalQueue.postRunnable { PluginRpc.detach(plugin) }
        drain()

        assertEquals(1, applied().single().updates.size)
        assertEquals(1, plugin.js.updateAbandons.size)
    }

    /**
     * the batch is a snapshot, so every unit's chain still names the plugin being stopped. Liveness
     * therefore cannot be `plugin.engine != null` - `PluginManager` clears that only after
     * `engine.close()`, so the walk `detach` restarts would dispatch unit 1 into an engine already
     * told it is unloading, with nothing left to abandon it and the account's whole update stream
     * stalled behind it until the batch budget expired.
     */
    @Test
    fun `stopping a plugin mid-chain does not dispatch the rest of its batch into it`() {
        val plugin = startPlugin("p", "interceptUpdate(updateNewMessage)")
        assertNull(plugin.interceptUpdate("updateNewMessage"))
        plugin.js.onDispatchUpdateIntercept = {}

        deliverUpdates(batchOf(newMessage(1), newMessage(2)))
        drain()
        assertEquals(1, plugin.js.updateDispatches.size, "one stage at a time")

        Utilities.globalQueue.postRunnable { PluginRpc.detach(plugin) }
        drain()

        assertEquals(1, plugin.js.updateDispatches.size, "the second unit must not enter a dying engine")
        assertEquals(2, applied().single().updates.size, "and the app is handed the whole batch")
    }

    @Test
    fun `a re-fed batch is not run through the middlewares twice`() {
        val plugin = verdictPlugin("p", true, "updateNewMessage")
        val batch = batchOf(newMessage(1))

        deliverUpdates(batch)
        drain()
        assertEquals(1, plugin.js.updateDispatches.size)

        // stock parks a batch whose pts does not line up and re-feeds it around the same instances
        assertFalse(deliverUpdates(batch, fromQueue = true), "nothing left to intercept, so nothing to take over")
        drain()
        assertEquals(1, plugin.js.updateDispatches.size)
    }

    @Test
    fun `a secret chat never reaches an update interceptor`() {
        val plugin = startPlugin("p", "interceptUpdate(updateNewEncryptedMessage)", "unsafe.disableApiFiltering")
        assertNull(plugin.interceptUpdate("updateNewEncryptedMessage"))
        plugin.js.onDispatchUpdateIntercept = { plugin.updateVerdict(it.dispatchId, false) }

        assertFalse(deliverUpdates(batchOf(TL_update.TL_updateNewEncryptedMessage())))
        drain()

        assertEquals(0, plugin.js.updateDispatches.size, "nothing lifts this one")
    }

    @Test
    fun `a service notification only reaches a plugin that turned the filter off`() {
        val filtered = verdictPlugin("a", false, "updateServiceNotification")
        val unfiltered = startPlugin("b", "interceptUpdate(updateServiceNotification)", "unsafe.disableApiFiltering")
        assertNull(unfiltered.interceptUpdate("updateServiceNotification"))
        unfiltered.js.onDispatchUpdateIntercept = { unfiltered.updateVerdict(it.dispatchId, true) }

        deliverUpdates(batchOf(TL_update.TL_updateServiceNotification()))
        drain()

        assertEquals(0, filtered.js.updateDispatches.size)
        assertEquals(1, unfiltered.js.updateDispatches.size)
    }

    @Test
    fun `observation sees what the app is handed, not what arrived`() {
        val plugin = startPlugin(
            "p",
            "interceptUpdate(updateNewMessage)",
            "onUpdate(updateNewMessage)",
        )
        assertNull(plugin.interceptUpdate("updateNewMessage", callbackId = 1))
        assertNull(plugin.onUpdate("updateNewMessage", callbackId = 2))
        val doomed = newMessage(2)
        plugin.js.onDispatchUpdateIntercept = { dispatch ->
            val id = handleId(dispatch.updateWire)
            plugin.updateVerdict(dispatch.dispatchId, plugin.resolved(id) !== doomed)
        }

        deliverUpdates(batchOf(newMessage(1), doomed))
        drain()

        assertEquals(1, plugin.js.updates.size, "a dropped update never happened, for observers too")
    }

    /**
     * the app applies the two compressed short forms from their own fields and never builds the
     * `Update` a middleware is handed, so a rewritten one has to be handed over in a shape that
     * carries the rewrite - and an untouched one must stay on stock's own branch, which prefetches
     * the sender and does its own pts bookkeeping
     */
    @Test
    fun `a short-form message a middleware only looked at stays on stock's own path`() {
        verdictPlugin("p", true, "updateNewMessage")
        val short = TLRPC.TL_updateShortMessage().apply {
            id = 5
            user_id = 7
            message = "hi"
            pts = 9
        }

        assertTrue(deliverUpdates(short))
        drain()

        assertSame(short, applied().single())
    }

    @Test
    fun `a rewritten short-form message is handed over as the batch the server would have sent`() {
        val plugin = startPlugin("p", "interceptUpdate(updateNewMessage)")
        assertNull(plugin.interceptUpdate("updateNewMessage"))
        plugin.js.onDispatchUpdateIntercept = { dispatch ->
            val update = plugin.resolved(handleId(dispatch.updateWire)) as TL_update.TL_updateNewMessage
            update.message.message = "rewritten"
            plugin.updateVerdict(dispatch.dispatchId, true)
        }
        val short = TLRPC.TL_updateShortMessage().apply {
            id = 5
            user_id = 7
            message = "hi"
            pts = 9
        }

        deliverUpdates(short)
        drain()

        val applied = applied().single()
        assertTrue(applied is TLRPC.TL_updates, "got ${applied.javaClass.simpleName}")
        val update = applied.updates.single() as TL_update.TL_updateNewMessage
        assertEquals("rewritten", update.message.message)
        assertEquals(9, update.pts, "the pts advance the short form carried has to come with it")
    }

    @Test
    fun `a dropped short-form message never reaches the app in any shape`() {
        verdictPlugin("p", false, "updateNewMessage")

        deliverUpdates(TLRPC.TL_updateShortMessage().apply { id = 5; user_id = 7; message = "hi"; pts = 9 })
        drain()

        assertEquals(0, applied().size)
    }

    /**
     * an uncached sender is not this code's problem to solve and must not become one: stock's
     * `TL_updates` branch resolves every peer the message names through the app's cache and then
     * `MessagesStorage.getUserSync`, and turns a miss into `needGetDiff` exactly as its short-form
     * branch does. Falling back to the compressed form here instead would silently discard the
     * rewrite the substitution exists for, so the substitution does not look at the cache at all -
     * it only has to name the sender so stock has something to resolve.
     */
    @Test
    fun `a rewritten short form is substituted even when the sender is not cached`() {
        val plugin = startPlugin("p", "interceptUpdate(updateNewMessage)")
        assertNull(plugin.interceptUpdate("updateNewMessage"))
        plugin.js.onDispatchUpdateIntercept = { dispatch ->
            val update = plugin.resolved(handleId(dispatch.updateWire)) as TL_update.TL_updateNewMessage
            update.message.message = "rewritten"
            plugin.updateVerdict(dispatch.dispatchId, true)
        }
        assertNull(MessagesController.getInstance(0).getUser(7L), "the sender must be unknown to the app")

        deliverUpdates(TLRPC.TL_updateShortMessage().apply { id = 5; user_id = 7; message = "hi"; pts = 9 })
        drain()

        val applied = applied().single()
        assertTrue(applied is TLRPC.TL_updates, "got ${applied.javaClass.simpleName}")
        val message = (applied.updates.single() as TL_update.TL_updateNewMessage).message
        assertEquals("rewritten", message.message, "the rewrite must not be thrown away over a cache miss")
        assertEquals(7L, (message.from_id as TLRPC.TL_peerUser).user_id)
        assertEquals(7L, (message.peer_id as TLRPC.TL_peerUser).user_id)
    }

    /**
     * stock groups a `TL_updates` by `getUpdatePts`/`getUpdatePtsCount` and only then resolves the
     * sender (and answers a miss with `needGetDiff`), so a substituted batch that lost either number
     * would never reach that resolution at all: it would sit in `updatesQueuePts` against a pts of 0
     * until the 1.5 s hole timer gave up. `seq` 0 is what keeps it off the seq check as well.
     */
    @Test
    fun `the substituted batch carries the pts arithmetic stock groups it by`() {
        val plugin = startPlugin("p", "interceptUpdate(updateNewMessage)")
        assertNull(plugin.interceptUpdate("updateNewMessage"))
        plugin.js.onDispatchUpdateIntercept = { dispatch ->
            val update = plugin.resolved(handleId(dispatch.updateWire)) as TL_update.TL_updateNewMessage
            update.message.message = "rewritten"
            plugin.updateVerdict(dispatch.dispatchId, true)
        }

        deliverUpdates(
            TLRPC.TL_updateShortMessage().apply {
                id = 5; user_id = 7; message = "hi"; pts = 9; pts_count = 1; date = 1234
            }
        )
        drain()

        val applied = applied().single() as TLRPC.TL_updates
        val update = applied.updates.single() as TL_update.TL_updateNewMessage
        assertEquals(9, update.pts)
        assertEquals(1, update.pts_count)
        assertEquals(1234, applied.date)
        assertEquals(0, applied.seq, "a synthetic batch has no seq of its own to advance")
    }

    /**
     * the account's whole stream is parked behind the head of its queue, so a hand-back that throws
     * would otherwise stop that account receiving anything for the life of the process
     */
    @Test
    fun `a hand-back the app throws on still drains the queue`() {
        val plugin = verdictPlugin("p", true, "updateNewMessage")
        val throwing = object : MessagesController() {
            override fun processUpdates(updates: TLRPC.Updates, fromQueue: Boolean) {
                if (PluginRpc.onUpdates(this, updates, 0, fromQueue)) return
                throw IllegalStateException("processUpdates blew up")
            }
        }
        Log.lines.clear()

        assertTrue(PluginRpc.onUpdates(throwing, batchOf(newMessage(1)), 0, false))
        drain()
        assertTrue(
            Log.lines.any { it.contains("handing an update batch back to the app failed") },
            "the throw has to be reported, not only survived: ${Log.lines}",
        )

        val second = batchOf(newMessage(2))
        assertTrue(deliverUpdates(second))
        drain()

        assertSame(second, applied().singleOrNull(), "the next batch never moved")
        assertEquals(2, plugin.js.updateDispatches.size)
    }

    /** the other half of the same `try`: a batch this code cannot rebuild must not strand the queue */
    @Test
    fun `a batch that cannot be rebuilt after a drop still drains the queue`() {
        val plugin = startPlugin("p", "interceptUpdate(updateNewMessage)")
        assertNull(plugin.interceptUpdate("updateNewMessage"))
        val doomed = newMessage(1)
        plugin.js.onDispatchUpdateIntercept = { dispatch ->
            plugin.updateVerdict(dispatch.dispatchId, plugin.resolved(handleId(dispatch.updateWire)) !== doomed)
        }

        val hostile = TLRPC.TL_updates().apply { updates = ExplodingList(doomed) }
        assertTrue(deliverUpdates(hostile))
        drain()
        assertEquals(0, applied().size, "rebuilding that batch is what threw")

        val second = batchOf(newMessage(2))
        assertTrue(deliverUpdates(second))
        drain()

        assertSame(second, applied().singleOrNull(), "the next batch never moved")
    }

    /** `deliverable` rebuilds a batch that lost an update through `removeAt`; this one refuses */
    private class ExplodingList(update: TLRPC.Update) : ArrayList<TLRPC.Update>(listOf(update)) {
        override fun removeAt(index: Int): TLRPC.Update = throw IllegalStateException("cannot rebuild")
    }

    private fun Plugin.resolved(handle: Long): TLObject? = tlTableOf(this)?.resolveTlObject(handle)
}
