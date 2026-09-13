package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.telegram.PluginUpdates
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
    fun a_future_update_stage_cannot_dispatch_into_a_reloaded_engine() {
        val first = verdictPlugin("first", true, "updateNewMessage")
        first.js.onDispatchUpdateIntercept = null
        val second = verdictPlugin("second", true, "updateNewMessage")
        val batch = batchOf(newMessage(1))
        assertTrue(deliverUpdates(batch))
        drain()
        val old = second.session!!
        old.stopDispatching()
        PluginUpdates.detach(second.session!!)
        old.tl.releaseAll()
        second.session = PluginSession(second, RecordingQuickJs())
        attachBridge(second.session!!)
        assertNull(second.interceptUpdate("updateNewMessage"))
        first.updateVerdict(first.js.updateDispatches.single().dispatchId, true)
        drain()
        assertTrue(second.js.updateDispatches.isEmpty())
        assertTrue((old.engine as RecordingQuickJs).updateDispatches.isEmpty())
        assertSame(batch, applied().single())
    }

    @Test
    fun a_batch_nobody_claims_is_left_with_the_app() {
        verdictPlugin("p", true, "updateUserTyping")

        assertFalse(deliverUpdates(batchOf(newMessage(1))))
        drain()

        assertEquals(0, applied().size, "the app kept the batch, so nothing is handed back")
    }

    @Test
    fun a_claimed_batch_is_taken_over_and_handed_back_once_the_chain_settles() {
        val plugin = verdictPlugin("p", true, "updateNewMessage")
        val batch = batchOf(newMessage(1))

        assertTrue(deliverUpdates(batch), "the app must not apply a batch a middleware is rewriting")
        assertEquals(0, applied().size)
        drain()

        assertEquals(1, plugin.js.updateDispatches.size)
        assertSame(batch, applied().singleOrNull(), "the very instance, rewritten in place")
    }

    @Test
    fun the_view_a_stage_is_handed_is_writable_and_scoped_to_the_batch() {
        val plugin = verdictPlugin("p", true, "updateNewMessage")
        val update = newMessage(1)

        deliverUpdates(batchOf(update))
        drain()

        val wire = plugin.js.updateDispatches[0].updateWire
        val handle = handleOf(wire)
        assertFalse(handle.readOnly, "rewriting in place is the point of this api")
        assertEquals(
            PluginWire.encodeHandle(vector = false, id = handle.id, readOnly = false, classId = plugin.session!!.tl.classIdOf(update.javaClass)),
            wire,
            "a dispatch view caches nothing, so no scalars ride along",
        )
        assertNull(plugin.resolved(handle.id), "the batch's scope is released before the app applies it")
    }

    /**
     * the batch keeps every update it arrived with, dropped ones included: taking one out leaves
     * its pts unaccounted for, and the app then runs a catch-up that hands the message straight
     * back. What makes it a drop is [PluginUpdates.isDropped], which stock's own loop asks.
     */
    @Test
    fun a_dropped_update_stays_in_the_batch_and_is_refused_at_the_apply_instead() {
        val plugin = startPlugin("p", "interceptUpdate(updateNewMessage)")
        assertNull(plugin.interceptUpdate("updateNewMessage"))
        val doomed = newMessage(2)
        plugin.js.onDispatchUpdateIntercept = { dispatch ->
            plugin.updateVerdict(dispatch.dispatchId, plugin.resolved(handleId(dispatch.updateWire)) !== doomed)
        }

        deliverUpdates(batchOf(newMessage(1), doomed, newMessage(3)))
        drain()

        val applied = applied().single()
        assertEquals(
            listOf(1, 2, 3),
            applied.updates.map { (it as TL_update.TL_updateNewMessage).message.id },
            "the pts of all three has to reach the app, or it fetches the dropped one right back",
        )
        assertTrue(PluginUpdates.isDropped(doomed), "and the app is told not to apply that one")
        assertFalse(PluginUpdates.isDropped(applied.updates[0]))
        assertFalse(PluginUpdates.isDropped(applied.updates[2]))
    }

    @Test
    fun a_batch_every_update_of_which_was_dropped_is_still_handed_over_whole() {
        verdictPlugin("p", false, "updateNewMessage")
        val update = newMessage(1)

        deliverUpdates(batchOf(update))
        drain()

        assertEquals(1, applied().single().updates.size, "the seq, date and pts advance all still land")
        assertTrue(PluginUpdates.isDropped(update))
    }

    @Test
    fun a_dropped_updateshort_is_handed_over_carrying_its_one_refused_update() {
        verdictPlugin("p", false, "updateNewMessage")
        val update = newMessage(1)

        deliverUpdates(TLRPC.TL_updateShort().apply { this.update = update })
        drain()

        assertEquals(1, applied().size, "stock wraps it into a one-element array for the same loop")
        assertTrue(PluginUpdates.isDropped(update))
    }

    /**
     * stock parks a batch whose pts does not line up and re-feeds it around the same instances,
     * without re-intercepting it - so a mark cleared after the hand-back would let the second pass
     * apply what the first one dropped.
     */
    @Test
    fun a_re_fed_batch_still_knows_which_of_its_updates_were_dropped() {
        verdictPlugin("p", false, "updateNewMessage")
        val update = newMessage(1)

        deliverUpdates(batchOf(update))
        drain()
        deliverUpdates(batchOf(update), fromQueue = true)
        drain()

        assertTrue(PluginUpdates.isDropped(update), "the verdict has to outlive the hand-back")
    }

    @Test
    fun a_drop_ends_the_chain_for_that_update_and_the_stages_below_never_see_it() {
        val first = verdictPlugin("a", false, "updateNewMessage")
        val second = verdictPlugin("b", true, "updateNewMessage")

        deliverUpdates(batchOf(newMessage(1)))
        drain()

        assertEquals(1, first.js.updateDispatches.size)
        assertEquals(0, second.js.updateDispatches.size, "a drop is a verdict, not a vote")
    }

    @Test
    fun stages_run_in_plugin_list_order_and_a_later_drag_re_orders_them() {
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

        setInstalledPlugins(listOf(second, first))
        PluginUpdates.refreshOrder()
        drain()
        order.clear()
        deliverUpdates(batchOf(newMessage(2)))
        drain()
        assertEquals(listOf("b", "a"), order)
    }

    @Test
    fun a_batch_arriving_while_one_is_being_walked_waits_for_it_rather_than_overtaking_it() {
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
     * dropping loses the user a message with nothing to re-request it, so a stall must never
     * produce one - what the plugin already decided stands, and everything else is applied
     */
    @Test
    fun the_budget_expiring_delivers_everything_still_undecided() {
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

        val applied = applied().single().updates
        assertEquals(listOf(1, 2, 3), applied.map { (it as TL_update.TL_updateNewMessage).message.id })
        assertTrue(PluginUpdates.isDropped(applied[0]), "the one verdict there was still stands")
        assertFalse(PluginUpdates.isDropped(applied[1]), "and an undecided update is applied, never dropped")
        assertFalse(PluginUpdates.isDropped(applied[2]))
        assertEquals(1, plugin.js.updateAbandons.size, "the parked stage is told it no longer matters")
    }

    @Test
    fun a_verdict_that_arrives_after_the_budget_expired_changes_nothing() {
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
    fun stopping_a_plugin_mid_chain_delivers_its_batch_rather_than_losing_it() {
        val plugin = startPlugin("p", "interceptUpdate(updateNewMessage)")
        assertNull(plugin.interceptUpdate("updateNewMessage"))
        plugin.js.onDispatchUpdateIntercept = {}

        deliverUpdates(batchOf(newMessage(1)))
        drain()
        assertEquals(0, applied().size)

        EngineDispatch.scheduler.postRunnable { detachPlugin(plugin) }
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
    fun stopping_a_plugin_mid_chain_does_not_dispatch_the_rest_of_its_batch_into_it() {
        val plugin = startPlugin("p", "interceptUpdate(updateNewMessage)")
        assertNull(plugin.interceptUpdate("updateNewMessage"))
        plugin.js.onDispatchUpdateIntercept = {}

        deliverUpdates(batchOf(newMessage(1), newMessage(2)))
        drain()
        assertEquals(1, plugin.js.updateDispatches.size, "one stage at a time")

        EngineDispatch.scheduler.postRunnable { detachPlugin(plugin) }
        drain()

        assertEquals(1, plugin.js.updateDispatches.size, "the second unit must not enter a dying engine")
        assertEquals(2, applied().single().updates.size, "and the app is handed the whole batch")
    }

    @Test
    fun a_re_fed_batch_is_not_run_through_the_middlewares_twice() {
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
    fun a_secret_chat_never_reaches_an_update_interceptor() {
        val plugin = startPlugin("p", "interceptUpdate(updateNewEncryptedMessage)", "unsafe.disableApiFiltering")
        assertNull(plugin.interceptUpdate("updateNewEncryptedMessage"))
        plugin.js.onDispatchUpdateIntercept = { plugin.updateVerdict(it.dispatchId, false) }

        assertFalse(deliverUpdates(batchOf(TL_update.TL_updateNewEncryptedMessage())))
        drain()

        assertEquals(0, plugin.js.updateDispatches.size, "nothing lifts this one")
    }

    @Test
    fun a_service_notification_only_reaches_a_plugin_that_turned_the_filter_off() {
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
    fun observation_sees_what_the_app_is_handed_not_what_arrived() {
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
    fun a_short_form_message_a_middleware_only_looked_at_stays_on_stock_s_own_path() {
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
    fun a_rewritten_short_form_message_is_handed_over_as_the_batch_the_server_would_have_sent() {
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

    /**
     * the compressed form has no loop to be refused in, so a dropped one is substituted like a
     * rewritten one and the substitute is what carries the pts into the loop above.
     */
    @Test
    fun a_dropped_short_form_message_is_substituted_so_its_pts_still_lands() {
        verdictPlugin("p", false, "updateNewMessage")

        deliverUpdates(TLRPC.TL_updateShortMessage().apply { id = 5; user_id = 7; message = "hi"; pts = 9 })
        drain()

        val applied = applied().single()
        assertTrue(applied is TLRPC.TL_updates, "got ${applied.javaClass.simpleName}")
        val update = applied.updates.single() as TL_update.TL_updateNewMessage
        assertEquals(9, update.pts)
        assertTrue(PluginUpdates.isDropped(update), "and nothing of it is applied")
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
    fun a_rewritten_short_form_is_substituted_even_when_the_sender_is_not_cached() {
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
    fun the_substituted_batch_carries_the_pts_arithmetic_stock_groups_it_by() {
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
     * a drop no longer rewrites the batch, so the list stock arrived with is the list it gets back
     * even when that list refuses to be edited. This is the shape that used to strand the queue.
     */
    @Test
    fun a_batch_whose_list_refuses_to_be_edited_is_still_handed_back_whole() {
        val plugin = startPlugin("p", "interceptUpdate(updateNewMessage)")
        assertNull(plugin.interceptUpdate("updateNewMessage"))
        val doomed = newMessage(1)
        plugin.js.onDispatchUpdateIntercept = { dispatch ->
            plugin.updateVerdict(dispatch.dispatchId, plugin.resolved(handleId(dispatch.updateWire)) !== doomed)
        }

        val hostile = TLRPC.TL_updates().apply { updates = ExplodingList(doomed) }
        assertTrue(deliverUpdates(hostile))
        drain()

        assertSame(hostile, applied().singleOrNull())
        assertTrue(PluginUpdates.isDropped(doomed))
    }

    /**
     * the account's whole stream is parked behind the head of its queue, so a hand-back that throws
     * would otherwise stop that account receiving anything for the life of the process. That the
     * throw is also *reported* is `Log.e` in `deliverBatch` and is not observed here.
     */
    @Test
    fun a_hand_back_the_app_throws_on_still_drains_the_queue() {
        val plugin = verdictPlugin("p", true, "updateNewMessage")
        TestApp.updatesController(0).failHandBack = true
        assertTrue(deliverUpdates(batchOf(newMessage(1))))
        drain()
        TestApp.updatesController(0).failHandBack = false

        val second = batchOf(newMessage(2))
        assertTrue(deliverUpdates(second))
        drain()

        assertSame(second, applied().singleOrNull(), "the next batch never moved")
        assertEquals(2, plugin.js.updateDispatches.size)
    }

    @Test
    fun a_difference_nobody_claims_is_applied_by_its_own_runnable() {
        verdictPlugin("p", true, "updateUserTyping")

        val run = deliverDifference(otherUpdates = listOf(newMessage(1)))
        drain()

        assertEquals(1, run.applied, "the app walked its own difference, in its own runnable")
    }

    @Test
    fun a_claimed_difference_is_parked_and_its_runnable_re_run_once_the_chain_settles() {
        val plugin = verdictPlugin("p", true, "updateNewMessage")

        val run = deliverDifference(otherUpdates = listOf(newMessage(1)))
        assertEquals(0, run.applied, "the app must not apply a difference a middleware is rewriting")
        drain()

        assertEquals(1, plugin.js.updateDispatches.size)
        assertEquals(1, run.applied, "handed back exactly once")
    }

    /**
     * a difference unit wraps the very `Message` the app is about to apply, so unlike the batch path
     * there is nothing to substitute: the rewrite is already where stock will read it.
     */
    @Test
    fun a_rewrite_of_a_difference_message_lands_on_the_object_the_app_applies() {
        val plugin = startPlugin("p", "interceptUpdate(updateNewMessage)")
        assertNull(plugin.interceptUpdate("updateNewMessage"))
        plugin.js.onDispatchUpdateIntercept = { dispatch ->
            val update = plugin.resolved(handleId(dispatch.updateWire)) as TL_update.TL_updateNewMessage
            update.message.message = "rewritten"
            plugin.updateVerdict(dispatch.dispatchId, true)
        }

        val message = TLRPC.TL_message().apply { id = 1; peer_id = peerUser(7L); message = "before" }.synced()
        val run = deliverDifference(listOf(message))
        drain()

        assertEquals(1, run.applied)
        assertEquals("rewritten", run.newMessages.single().message)
    }

    /** a drop is a removal from the list the runnable is about to walk, there being no batch to rebuild */
    @Test
    fun a_dropped_difference_message_is_gone_from_the_list_the_app_walks() {
        val plugin = verdictPlugin("p", false, "updateNewMessage")
        val kept = TLRPC.TL_message().apply { id = 1; peer_id = peerUser(7L) }.synced()
        val dropped = TLRPC.TL_message().apply { id = 2; peer_id = peerUser(7L) }.synced()
        val update = TL_update.TL_updateUserTyping()

        val run = deliverDifference(listOf(kept, dropped), listOf(update))
        drain()

        assertEquals(1, run.applied)
        assertEquals(listOf<TLRPC.Update>(update), run.otherUpdates, "nothing named that constructor, so it stays")
        assertEquals(emptyList<TLRPC.Message>(), run.newMessages, "both were dropped")
        assertEquals(2, plugin.js.updateDispatches.size)
    }

    /** the same fifo as the batch path, or a catch-up would overtake an arrival already being walked */
    @Test
    fun a_difference_queues_behind_a_batch_already_being_walked() {
        val plugin = verdictPlugin("p", true, "updateNewMessage")

        assertTrue(deliverUpdates(batchOf(newMessage(1))))
        val run = deliverDifference(otherUpdates = listOf(newMessage(2)))
        assertEquals(0, run.applied, "the difference waits its turn")
        drain()

        assertEquals(1, run.applied)
        assertEquals(2, plugin.js.updateDispatches.size)
    }

    /** `deliverable` rebuilds a batch that lost an update through `removeAt`; this one refuses */
    private class ExplodingList(update: TLRPC.Update) : ArrayList<TLRPC.Update>(listOf(update)) {
        override fun removeAt(index: Int): TLRPC.Update = throw IllegalStateException("cannot rebuild")
    }

    private fun Plugin.resolved(handle: Long): TLObject? = tlTableOf(this)?.resolveTlObject(handle)
}
