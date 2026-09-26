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
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_update

class PluginRpcUpdateChainTest {
    @Before
    fun setUp() = resetBridge()

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
        val batch = createUpdatesBatch(newMessage(1))
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

        assertFalse(deliverUpdates(createUpdatesBatch(newMessage(1))))
        drain()

        assertEquals(0, applied().size, "the app kept the batch, so nothing is handed back")
    }

    @Test
    fun a_claimed_batch_is_taken_over_and_handed_back_once_the_chain_settles() {
        val plugin = verdictPlugin("p", true, "updateNewMessage")
        val batch = createUpdatesBatch(newMessage(1))

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

        deliverUpdates(createUpdatesBatch(update))
        drain()

        val wire = plugin.js.updateDispatches[0].updateWire
        val handle = decodeHandle(wire)
        assertFalse(handle.readOnly, "rewriting in place is the point of this api")
        assertEquals(
            PluginWire.encodeHandle(vector = false, id = handle.id, readOnly = false, classId = plugin.session!!.tl.getClassId(update.javaClass)),
            wire,
            "a dispatch view caches nothing, so no scalars ride along",
        )
        assertNull(plugin.resolved(handle.id), "the batch's scope is released before the app applies it")
    }

    // removing a dropped update would leave its pts unaccounted for, and stock would re-fetch it
    @Test
    fun a_dropped_update_stays_in_the_batch_and_is_refused_at_the_apply_instead() {
        val plugin = startPlugin("p", "interceptUpdate(updateNewMessage)")
        assertNull(plugin.interceptUpdate("updateNewMessage"))
        val doomed = newMessage(2)
        plugin.js.onDispatchUpdateIntercept = { dispatch ->
            plugin.updateVerdict(dispatch.dispatchId, plugin.resolved(handleId(dispatch.updateWire)) !== doomed)
        }

        deliverUpdates(createUpdatesBatch(newMessage(1), doomed, newMessage(3)))
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

        deliverUpdates(createUpdatesBatch(update))
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

    // stock re-feeds a parked batch around the same instances without re-intercepting it
    @Test
    fun a_re_fed_batch_still_knows_which_of_its_updates_were_dropped() {
        verdictPlugin("p", false, "updateNewMessage")
        val update = newMessage(1)

        deliverUpdates(createUpdatesBatch(update))
        drain()
        deliverUpdates(createUpdatesBatch(update), fromQueue = true)
        drain()

        assertTrue(PluginUpdates.isDropped(update), "the verdict has to outlive the hand-back")
    }

    @Test
    fun a_drop_ends_the_chain_for_that_update_and_the_stages_below_never_see_it() {
        val first = verdictPlugin("a", false, "updateNewMessage")
        val second = verdictPlugin("b", true, "updateNewMessage")

        deliverUpdates(createUpdatesBatch(newMessage(1)))
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

        deliverUpdates(createUpdatesBatch(newMessage(1)))
        drain()
        assertEquals(listOf("a", "b"), order)

        setInstalledPlugins(listOf(second, first))
        PluginUpdates.refreshOrder()
        drain()
        order.clear()
        deliverUpdates(createUpdatesBatch(newMessage(2)))
        drain()
        assertEquals(listOf("b", "a"), order)
    }

    @Test
    fun a_batch_arriving_while_one_is_being_walked_waits_for_it_rather_than_overtaking_it() {
        val plugin = startPlugin("p", "interceptUpdate(updateNewMessage)")
        assertNull(plugin.interceptUpdate("updateNewMessage"))
        val parked = ArrayList<Long>()
        plugin.js.onDispatchUpdateIntercept = { parked.add(it.dispatchId) }

        val first = createUpdatesBatch(newMessage(1))
        val second = createUpdatesBatch(newMessage(2))
        assertTrue(deliverUpdates(first))
        drain()
        assertTrue(deliverUpdates(createUpdatesBatch(TL_update.TL_updateUserTyping())))
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

    // nothing re-requests a dropped update, so a stall must never produce one
    @Test
    fun the_budget_expiring_delivers_everything_still_undecided() {
        val plugin = startPlugin("p", "interceptUpdate(updateNewMessage)")
        assertNull(plugin.interceptUpdate("updateNewMessage"))
        var seen = 0
        plugin.js.onDispatchUpdateIntercept = { dispatch ->
            if (seen++ == 0) plugin.updateVerdict(dispatch.dispatchId, false)
        }
        val batch = createUpdatesBatch(newMessage(1), newMessage(2), newMessage(3))

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

        deliverUpdates(createUpdatesBatch(newMessage(1)))
        drain()
        advanceBy(2_000)
        drain()
        assertEquals(1, applied().single().updates.size)

        plugin.updateVerdict(parked[0], false)
        drain()
        assertEquals(1, applied().single().updates.size, "the app was already handed the update")
    }

    // the batch snapshot still names the stopped plugin, and `PluginManager` clears `plugin.engine`
    // only after `engine.close()`
    @Test
    fun stopping_a_plugin_mid_chain_delivers_its_batch_without_dispatching_the_rest_into_it() {
        val plugin = startPlugin("p", "interceptUpdate(updateNewMessage)")
        assertNull(plugin.interceptUpdate("updateNewMessage"))
        plugin.js.onDispatchUpdateIntercept = {}

        deliverUpdates(createUpdatesBatch(newMessage(1), newMessage(2)))
        drain()
        assertEquals(1, plugin.js.updateDispatches.size, "one stage at a time")

        EngineDispatch.scheduler.postRunnable { detachPlugin(plugin) }
        drain()

        assertEquals(1, plugin.js.updateDispatches.size, "the second unit must not enter a dying engine")
        assertEquals(2, applied().single().updates.size, "and the app is handed the whole batch")
        assertEquals(1, plugin.js.updateAbandons.size)
    }

    @Test
    fun a_re_fed_batch_is_not_run_through_the_middlewares_twice() {
        val plugin = verdictPlugin("p", true, "updateNewMessage")
        val batch = createUpdatesBatch(newMessage(1))

        deliverUpdates(batch)
        drain()
        assertEquals(1, plugin.js.updateDispatches.size)

        assertFalse(deliverUpdates(batch, fromQueue = true), "nothing left to intercept, so nothing to take over")
        drain()
        assertEquals(1, plugin.js.updateDispatches.size)
    }

    @Test
    fun a_secret_chat_never_reaches_an_update_interceptor() {
        val plugin = startPlugin("p", "interceptUpdate(updateNewEncryptedMessage)", "unsafe.disableApiFiltering")
        assertNull(plugin.interceptUpdate("updateNewEncryptedMessage"))
        plugin.js.onDispatchUpdateIntercept = { plugin.updateVerdict(it.dispatchId, false) }

        assertFalse(deliverUpdates(createUpdatesBatch(TL_update.TL_updateNewEncryptedMessage())))
        drain()

        assertEquals(0, plugin.js.updateDispatches.size, "nothing lifts this one")
    }

    @Test
    fun a_service_notification_only_reaches_a_plugin_that_turned_the_filter_off() {
        val filtered = verdictPlugin("a", false, "updateServiceNotification")
        val unfiltered = startPlugin("b", "interceptUpdate(updateServiceNotification)", "unsafe.disableApiFiltering")
        assertNull(unfiltered.interceptUpdate("updateServiceNotification"))
        unfiltered.js.onDispatchUpdateIntercept = { unfiltered.updateVerdict(it.dispatchId, true) }

        deliverUpdates(createUpdatesBatch(TL_update.TL_updateServiceNotification()))
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

        deliverUpdates(createUpdatesBatch(newMessage(1), doomed))
        drain()

        assertEquals(1, plugin.js.updates.size, "a dropped update never happened, for observers too")
    }

    // stock applies a short form from its own fields; its branch prefetches the sender and does its
    // own pts bookkeeping
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

    // stock groups a `TL_updates` by pts and pts_count before resolving the sender, and turns an
    // uncached one into `needGetDiff` itself; a lost pts parks the batch until the hole timer. seq 0
    // keeps it off the seq check
    @Test
    fun a_rewritten_short_form_is_handed_over_as_a_batch_carrying_its_pts_even_with_the_sender_uncached() {
        val plugin = startPlugin("p", "interceptUpdate(updateNewMessage)")
        assertNull(plugin.interceptUpdate("updateNewMessage"))
        plugin.js.onDispatchUpdateIntercept = { dispatch ->
            val update = plugin.resolved(handleId(dispatch.updateWire)) as TL_update.TL_updateNewMessage
            update.message.message = "rewritten"
            plugin.updateVerdict(dispatch.dispatchId, true)
        }
        assertNull(MessagesController.getInstance(0).getUser(7L), "the sender must be unknown to the app")

        deliverUpdates(
            TLRPC.TL_updateShortMessage().apply {
                id = 5; user_id = 7; message = "hi"; pts = 9; pts_count = 1; date = 1234
            }
        )
        drain()

        val applied = applied().single() as TLRPC.TL_updates
        val update = applied.updates.single() as TL_update.TL_updateNewMessage
        assertEquals("rewritten", update.message.message, "the rewrite must not be thrown away over a cache miss")
        assertEquals(7L, (update.message.from_id as TLRPC.TL_peerUser).user_id)
        assertEquals(7L, (update.message.peer_id as TLRPC.TL_peerUser).user_id)
        assertEquals(9, update.pts)
        assertEquals(1, update.pts_count)
        assertEquals(1234, applied.date)
        assertEquals(0, applied.seq, "a synthetic batch has no seq of its own to advance")
    }

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

    @Test
    fun a_hand_back_the_app_throws_on_still_drains_the_queue() {
        val plugin = verdictPlugin("p", true, "updateNewMessage")
        TestApp.updatesController(0).failHandBack = true
        assertTrue(deliverUpdates(createUpdatesBatch(newMessage(1))))
        drain()
        TestApp.updatesController(0).failHandBack = false

        val second = createUpdatesBatch(newMessage(2))
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

    @Test
    fun a_difference_queues_behind_a_batch_already_being_walked() {
        val plugin = verdictPlugin("p", true, "updateNewMessage")

        assertTrue(deliverUpdates(createUpdatesBatch(newMessage(1))))
        val run = deliverDifference(otherUpdates = listOf(newMessage(2)))
        assertEquals(0, run.applied, "the difference waits its turn")
        drain()

        assertEquals(1, run.applied)
        assertEquals(2, plugin.js.updateDispatches.size)
    }

    private class ExplodingList(update: TLRPC.Update) : ArrayList<TLRPC.Update>(listOf(update)) {
        override fun removeAt(index: Int): TLRPC.Update = throw IllegalStateException("cannot rebuild")
    }

    private fun Plugin.resolved(handle: Long): TLObject? = getTlHandles(this)?.resolveTlObject(handle)
}
