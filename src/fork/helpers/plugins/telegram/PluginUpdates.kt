package desu.inugram.helpers.plugins.telegram

import desu.inugram.helpers.plugins.SessionResource
import android.util.Log
import desu.inugram.core.plugins.BoundedIdentitySet
import desu.inugram.core.plugins.DispatchDeadline
import desu.inugram.core.plugins.PluginWire
import desu.inugram.core.plugins.ScopeMatch
import desu.inugram.core.plugins.TlCtorIds
import desu.inugram.core.plugins.TlNames
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.UpdatesListener
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlHandles
import desu.inugram.helpers.plugins.tl.TlJson
import java.util.Collections
import java.util.IdentityHashMap
import org.telegram.messenger.MessagesController
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_update

/**
 * Wires `inu.onUpdate`/`inu.interceptUpdate` into the app's arriving update stream (rust:
 * `tg/rpc.rs`, whose `update_dispatches` is a table of its own).
 *
 * Everything here runs on [EngineDispatch.scheduler] except the hand-back, which is
 * [Utilities.stageQueue] because that is the only queue `processUpdates` may run on: it mutates
 * pts/seq with no locking, and stock's own tail runs it there.
 *
 * **There are three arrival paths and they are not interchangeable.** [onUpdates] is the live batch,
 * answered by handing a whole `TLRPC.Updates` back; [onDifference] is the catch-up, which walks its
 * own lists and is answered by re-running the runnable that carries it; and the two compressed
 * short forms arrive through [onUpdates] carrying no `TLRPC.Update` at all, so the one a middleware
 * sees is [normalizeShortMessage]'s synthetic. Adding a fourth dispatch site rather than an arrival
 * path would be the bug: the dedup, the takeover filter and the `Disposer` rules all hang off these.
 *
 * A payload reaching a plugin is read-only and plugin-lifetime for `onUpdate`, writable and
 * scope-invalidated for an `interceptUpdate` stage. Both mint into the plugin's own [TlHandles].
 */
object PluginUpdates : SessionResource {
    private const val TAG = "InuPluginUpdates"

    /** a tenth of a send's: the app's whole arriving batch is parked behind this */
    private const val UPDATE_BUDGET_MS = 2_000L
    private const val DISPATCH_MEMORY = 2048

    // fast-path gate read from arbitrary stageQueue threads before paying for a globalQueue hop
    @Volatile private var hasUpdateListeners = false
    @Volatile private var hasUpdateInterceptors = false

    // published copy-on-write, so reads off globalQueue need no synchronization
    @Volatile private var updateRegs: List<UpdateReg> = emptyList()
    @Volatile private var updateListenersByType: Map<String, List<UpdateListener>> = emptyMap()
    @Volatile private var updateInterceptRegs: List<UpdateInterceptor> = emptyList()
    @Volatile private var updateInterceptorsByType: Map<String, List<UpdateInterceptor>> = emptyMap()

    // its own space: rust keeps interceptRpc and interceptUpdate dispatches in different tables
    private var nextDispatchId = 1L
    private val pendingUpdateDispatches = HashMap<Long, UpdateBatch>()

    // already fanned out, newest last, so a re-fed batch doesn't deliver twice. identity, because no TLRPC class overrides hashCode
    private val dispatchedUpdates = BoundedIdentitySet<TLObject>(DISPATCH_MEMORY)
    // one fifo per account: the head is the batch being walked, and everything behind it waits whether or not an interceptor claimed it
    private val updateQueues = HashMap<Int, ArrayDeque<UpdateBatch>>()
    // the batches this re-fed, so [onUpdates] lets its own hand-back through
    private val takenOver = Collections.newSetFromMap(IdentityHashMap<TLRPC.Updates, Boolean>())
    // the same, for [onDifference]: the runnable re-run is the one carrying the hook
    private val takenOverDifferences = Collections.newSetFromMap(IdentityHashMap<Runnable, Boolean>())
    // same bounded identity ring as [dispatchedUpdates]: stock re-feeds a parked batch around the very objects a first pass ran over
    private val interceptedUpdates = BoundedIdentitySet<TLObject>(DISPATCH_MEMORY)
    // a ring for the same reason: a parked batch is re-fed but not re-intercepted, so a verdict cleared after the hand-back would be lost and the update applied on the second pass
    private val droppedUpdates = BoundedIdentitySet<TLObject>(DISPATCH_MEMORY)

    fun listenerFor(session: PluginSession): UpdatesListener =
        object : UpdatesListener {
            private val onHost = EngineDispatch.createHostDispatcher { session.isCurrent() }
            override fun onUpdateRegister(callbackId: Int, types: Array<String>, scope: String): String? =
                registerUpdates(session, callbackId, types, scope)

            override fun onUpdateUnregister(callbackId: Int) =
                onHost { unregisterUpdates(session, callbackId) }

            override fun onInterceptUpdateRegister(callbackId: Int, types: Array<String>): String? =
                registerInterceptUpdates(session, callbackId, types)

            override fun onInterceptUpdateUnregister(callbackId: Int) =
                onHost { unregisterInterceptUpdates(session, callbackId) }

            override fun onUpdateVerdict(dispatchId: Long, deliver: Boolean) =
                onHost { onUpdateStageSettled(dispatchId, deliver) }
        }

    fun refreshOrder() {
        EngineDispatch.scheduler.postRunnable {
            publishUpdateRegs(updateRegs)
            publishUpdateInterceptors(updateInterceptRegs)
        }
    }

    /**
     * A batch parked on this plugin is **delivered**, never failed: a drop is final and nothing
     * re-requests what it took, so producing one out of an unload would lose the user's messages.
     */
    override fun detach(session: PluginSession) {
        publishUpdateRegs(updateRegs.filter { it.session !== session })
        publishUpdateInterceptors(updateInterceptRegs.filter { it.session !== session })
        for (dispatchId in pendingUpdateDispatches.filterValues { it.stageSession === session }.keys.toList()) {
            val batch = pendingUpdateDispatches.remove(dispatchId) ?: continue
            batch.stageSession?.engine?.abandonUpdateDispatch(dispatchId)
            advanceBatch(batch)
        }
    }

    /**
     * the type list is per registration, so the fan-out can mint a handle only for a plugin some
     * registration of which named that constructor.
     *
     * [grantScope] is what it was gated on - the constructor for the raw form, the demuxed event
     * name otherwise. `common.d.ts` keeps the two vocabularies apart, so it is not derivable.
     */
    private class UpdateReg(
        val session: PluginSession,
        val callbackId: Int,
        val types: Set<String>,
        val grantScope: String?,
    )

    private class UpdateListener(val session: PluginSession) {
        val grantScopes = HashSet<String>()
    }

    /** the scopes here are the constructors, unlike `onUpdate`'s */
    private class UpdateInterceptor(val session: PluginSession, val callbackId: Int, val types: Set<String>)

    /**
     * [arrival] is what the app holds - the `Update` itself, or the `Message` a difference carries
     * - while [update] is what a middleware is handed. [synthesized] marks the two compressed short
     * forms, where [update] is what [normalizeShortMessage] built rather than anything the app will
     * apply; [snapshot] is the pre-walk state [deliverable] compares against, null meaning it failed
     * and a rewrite is assumed.
     */
    private class UpdateUnit(
        val arrival: TLObject,
        val update: TLObject,
        val tlName: String,
        val chain: List<UpdateInterceptor>,
        val synthesized: Boolean,
        val snapshot: String?,
    )

    /**
     * how a walked batch is given back, which is the one thing the two arrival paths do not share:
     * `processUpdates` is answered with a whole `TLRPC.Updates` it has not seen yet, while a
     * difference is answered by letting its own runnable carry on over the lists it already holds.
     */
    private sealed class UpdateDelivery {
        class Batch(
            val controller: MessagesController,
            val updates: TLRPC.Updates,
            val fromQueue: Boolean,
        ) : UpdateDelivery()

        class Difference(
            val newMessages: MutableList<TLRPC.Message>?,
            val otherUpdates: MutableList<TLRPC.Update>?,
            val apply: Runnable,
        ) : UpdateDelivery()
    }

    /**
     * The app is blocked on this, so it carries [UPDATE_BUDGET_MS] shared by every stage of every
     * update in the batch: updates arrive in bursts, and a per-update budget would let one batch
     * hold the stream for its size times the budget.
     */
    private class UpdateBatch(
        val delivery: UpdateDelivery,
        val account: Int,
        val units: List<UpdateUnit>,
    ) {
        var scopeId = 0L
        var index = 0
        var stage = 0
        var dispatchId = 0L

        var stageSession: PluginSession? = null
        val deadline = DispatchDeadline(EngineDispatch.scheduler, UPDATE_BUDGET_MS) { expireBatch(this) }
        var expired = false
        var finished = false

        val dropped: MutableSet<TLObject> = Collections.newSetFromMap(IdentityHashMap())
    }


    /**
     * The types are checked against the constructor table as well as the grants: one no layer
     * defines can only be a typo, and one that silently never fires is an hour of debugging.
     *
     * [scope] empty is the raw form, where each constructor is its own grant scope; otherwise it is
     * the demuxed event name and [types] is that event's fixed list.
     */
    private fun registerUpdates(session: PluginSession, callbackId: Int, types: Array<String>, scope: String): String? {
        for (type in types) {
            if (type !in TlCtorIds.updateNames) {
                return PluginWire.encodePluginError("unknown-constructor", "onUpdate: unknown update type '$type'")
            }
        }
        val grantScope = scope.ifEmpty { null }
        for (target in grantScope?.let { listOf(it) } ?: types.toList()) {
            if (!session.permissions.allows("onUpdate", target, ScopeMatch.EXACT)) {
                return PluginWire.encodeNotGranted("onUpdate", target)
            }
        }
        publishUpdateRegs(updateRegs + UpdateReg(session, callbackId, types.toSet(), grantScope))
        return null
    }

    private fun unregisterUpdates(session: PluginSession, callbackId: Int) {
        publishUpdateRegs(updateRegs.filter { it.session !== session || it.callbackId != callbackId })
    }

    /** every `interceptUpdate` scope is a constructor name - there is no demuxed form over it */
    private fun registerInterceptUpdates(session: PluginSession, callbackId: Int, types: Array<String>): String? {
        for (type in types) {
            if (type !in TlCtorIds.updateNames) {
                return PluginWire.encodePluginError("unknown-constructor", "interceptUpdate: unknown update type '$type'")
            }
            if (!session.permissions.allows("interceptUpdate", type, ScopeMatch.EXACT)) {
                return PluginWire.encodeNotGranted("interceptUpdate", type)
            }
        }
        publishUpdateInterceptors(updateInterceptRegs + UpdateInterceptor(session, callbackId, types.toSet()))
        return null
    }

    private fun unregisterInterceptUpdates(session: PluginSession, callbackId: Int) {
        publishUpdateInterceptors(
            updateInterceptRegs.filter { it.session !== session || it.callbackId != callbackId }
        )
    }


    private fun publishUpdateRegs(updated: List<UpdateReg>) {
        val order = PluginManager.orderIndex()
        updateRegs = updated
        val byType = HashMap<String, MutableList<UpdateListener>>()
        for (reg in updated.sortedBy { order[it.session.plugin] ?: Int.MAX_VALUE }) {
            for (type in reg.types) {
                val listening = byType.getOrPut(type) { mutableListOf() }
                // one dispatch per plugin however many of its registrations named this type; the engine fans out from a single handle
                val listener = listening.firstOrNull { it.session === reg.session }
                    ?: UpdateListener(reg.session).also { listening.add(it) }
                listener.grantScopes.add(reg.grantScope ?: type)
            }
        }
        updateListenersByType = byType
        hasUpdateListeners = byType.isNotEmpty()
        if (!hasUpdateListeners) {
            dispatchedUpdates.clear()
        }
    }

    private fun publishUpdateInterceptors(updated: List<UpdateInterceptor>) {
        val order = PluginManager.orderIndex()
        updateInterceptRegs = updated
        val byType = HashMap<String, MutableList<UpdateInterceptor>>()
        for (reg in updated.sortedBy { order[it.session.plugin] ?: Int.MAX_VALUE }) {
            for (type in reg.types) byType.getOrPut(type) { mutableListOf() }.add(reg)
        }
        updateInterceptorsByType = byType
        hasUpdateInterceptors = byType.isNotEmpty()
        // with nothing left to intercept, the ring is strong references to update graphs nothing will look up again
        if (!hasUpdateInterceptors) {
            interceptedUpdates.clear()
        }
    }


    /**
     * Unpacking is synchronous because the batch is only whole on entry: a sub-update whose pts
     * does not line up is moved into a fresh wrapper stock parks, so by the time anything posted
     * from here runs the list may be empty. The fan-out then takes a stageQueue hop, so plugins
     * read the objects after `processUpdateArray` backfilled them rather than racing those writes -
     * and the two hops are the only happens-before edge to the plugin queue.
     */
    @JvmStatic
    fun onUpdates(controller: MessagesController, updates: TLRPC.Updates, account: Int, fromQueue: Boolean): Boolean {
        // our own hand-back: observers see exactly what the app is about to apply, which is what makes a dropped update invisible to `onUpdate` too
        if (takenOver.remove(updates)) {
            if (hasUpdateListeners) fanOut(unpackUpdates(updates, account), account)
            return false
        }
        val busy = updateQueues[account]?.isNotEmpty() == true
        if (!busy && !hasUpdateInterceptors) {
            if (hasUpdateListeners) fanOut(unpackUpdates(updates, account), account)
            return false
        }
        val units = if (hasUpdateInterceptors) interceptableUnits(unpackUpdates(updates, account), snapshots = true) else emptyList()
        // nothing to intercept and nothing ahead of it, so the app keeps the batch
        if (!busy && units.isEmpty()) {
            if (hasUpdateListeners) fanOut(unpackUpdates(updates, account), account)
            return false
        }
        return enqueue(UpdateBatch(UpdateDelivery.Batch(controller, updates, fromQueue), account, units))
    }

    private fun enqueue(batch: UpdateBatch): Boolean {
        val queue = updateQueues.getOrPut(batch.account) { ArrayDeque() }
        queue.addLast(batch)
        // ordering is why an unclaimed batch is queued too: the app applies updates in arrival order
        if (queue.size == 1) EngineDispatch.scheduler.postRunnable { runBatch(batch) }
        return true
    }

    /**
     * Snapshotted here rather than at dispatch, so a registration made mid-walk joins the next
     * batch - the rule every `Disposer` follows. The two short forms carry no `TLRPC.Update` at
     * all, so what a middleware is handed is [normalizeShortMessage]'s synthetic one.
     */
    private fun interceptableUnits(unpacked: List<UnpackedUpdate>, snapshots: Boolean): List<UpdateUnit> {
        val units = ArrayList<UpdateUnit>()
        for ((arrival, update) in unpacked) {
            val tlName = TlNames.classNameToTlName(update.javaClass)
            val chain = chainFor(update, tlName)
            if (chain.isEmpty()) continue
            // keyed on what stock would re-feed. A ring of its own, not [dispatchedUpdates]: sharing one would make the hand-back look already delivered
            if (!rememberIntercept(arrival)) continue
            val synthesized = arrival !== update
            val snapshot = if (snapshots && synthesized) rawSnapshotOf(update) else null
            units.add(UpdateUnit(arrival, update, tlName, chain, synthesized, snapshot))
        }
        return units
    }

    /** carries the two rules [dispatchUpdate] does: a secret chat never reaches plugin code, and `updateServiceNotification` is a takeover surface the bypass grant lifts */
    private fun chainFor(update: TLObject, tlName: String): List<UpdateInterceptor> {
        if (isSecretChatUpdate(update)) return emptyList()
        val listening = updateInterceptorsByType[tlName] ?: return emptyList()
        val serviceNotification = update is TL_update.TL_updateServiceNotification
        return listening.filter { interceptor ->
            val permissions = interceptor.session.permissions
            (!serviceNotification || permissions.has("unsafe.disableApiFiltering")) &&
                permissions.allows("interceptUpdate", tlName, ScopeMatch.EXACT)
        }
    }

    private fun rememberIntercept(arrival: TLObject): Boolean = interceptedUpdates.add(arrival)

    /** unfiltered on purpose: only ever compared against another snapshot of the same object, and a hidden field would hide a rewrite of one reachable through a nested view */
    private val RAW_POLICY = TlFilter.Policy(takeover = false, drafts = true)

    private fun rawSnapshotOf(update: TLObject): String? = try {
        TlJson.toJson(update, RAW_POLICY).toString()
    } catch (e: Exception) {
        Log.w(TAG, "cannot snapshot ${update.javaClass.simpleName}: $e")
        null
    }

    private fun runBatch(batch: UpdateBatch) {
        batch.scopeId = TlHandles.newScope()
        batch.deadline.resume()
        advanceBatch(batch)
    }

    /** a loop rather than a recursion: a batch of a hundred updates none of whose plugins are running would be a hundred frames deep */
    private fun advanceBatch(batch: UpdateBatch) {
        while (!batch.expired && batch.index < batch.units.size) {
            val unit = batch.units[batch.index]
            val interceptor = unit.chain.getOrNull(batch.stage)
            if (interceptor == null || unit.update in batch.dropped) {
                batch.index++
                batch.stage = 0
                continue
            }
            batch.stage++
            val session = interceptor.session
            if (!session.canDispatch()) continue
            val engine = session.engine
            val tl = session.tl
            val dispatchId = nextDispatchId++
            batch.dispatchId = dispatchId
            batch.stageSession = session
            pendingUpdateDispatches[dispatchId] = batch
            val handle = tl.mintForScope(unit.update, batch.scopeId)
            engine.dispatchUpdateIntercept(
                interceptor.callbackId,
                dispatchId,
                unit.tlName,
                batch.account,
                PluginWire.encodeHandle(vector = false, id = handle, readOnly = false),
            )
            return
        }
        finishBatch(batch)
    }

    /** posted, not run inline: this arrives from inside the engine's own JNI upcall, and the next stage may be the same engine */
    private fun onUpdateStageSettled(dispatchId: Long, deliver: Boolean) {
        EngineDispatch.scheduler.postRunnable {
            val batch = pendingUpdateDispatches.remove(dispatchId) ?: return@postRunnable
            batch.stageSession = null
            if (!deliver) {
                // a drop ends the chain for that update, exactly as a short-circuiting request stage ends its own
                batch.dropped.add(batch.units[batch.index].update)
            }
            advanceBatch(batch)
        }
    }

    /**
     * Everything still undecided is **delivered**, never dropped: a drop is final and nothing
     * re-requests what it took, so producing one out of a stall would turn any stall into lost
     * messages.
     */
    private fun expireBatch(batch: UpdateBatch) {
        if (batch.finished) return
        batch.expired = true
        val dispatchId = batch.dispatchId
        val stageSession = batch.stageSession
        if (dispatchId != 0L && pendingUpdateDispatches.remove(dispatchId) === batch) {
            stageSession?.engine?.abandonUpdateDispatch(dispatchId)
        }
        Log.w(TAG, "[${stageSession?.manifest?.name}] an update batch ran past the ${UPDATE_BUDGET_MS}ms budget")
        finishBatch(batch)
    }

    private fun finishBatch(batch: UpdateBatch) {
        if (batch.finished) return
        batch.finished = true
        batch.deadline.cancel()
        // before the hand-back, so no view can still read an update the app is about to apply
        for (session in batch.units.flatMap { it.chain }.map { it.session }.distinct()) {
            session.tl.releaseScope(batch.scopeId)
        }
        Utilities.stageQueue.postRunnable { deliverBatch(batch) }
    }

    /**
     * The hand-back is unbounded (`processUpdates` reaches most of the app) and the one place a
     * throw is unrecoverable: the account's whole stream is parked behind this queue entry, every
     * later arrival adds itself without posting a walk, and the budget is long spent. So the queue
     * advances whatever happens, and nothing escapes onto stageQueue.
     */
    private fun deliverBatch(batch: UpdateBatch) {
        try {
            when (val delivery = batch.delivery) {
                is UpdateDelivery.Batch -> {
                    val deliverable = deliverable(batch, delivery)
                    if (deliverable != null) {
                        takenOver.add(deliverable)
                        delivery.controller.processUpdates(deliverable, delivery.fromQueue)
                    }
                }
                is UpdateDelivery.Difference -> {
                    // no substitution: a difference unit wraps the very object the app is about to
                    // apply, so a rewrite has already landed and a drop is a removal from its list
                    if (batch.dropped.isNotEmpty()) {
                        val gone: MutableSet<TLObject> = Collections.newSetFromMap(IdentityHashMap())
                        batch.units.filterTo(ArrayList()) { it.update in batch.dropped }.mapTo(gone) { it.arrival }
                        delivery.otherUpdates?.removeAll { it in gone }
                        delivery.newMessages?.removeAll { it in gone }
                    }
                    takenOverDifferences.add(delivery.apply)
                    delivery.apply.run()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "handing an update batch back to the app failed", e)
        } finally {
            advanceUpdateQueue(batch)
        }
    }

    private fun advanceUpdateQueue(batch: UpdateBatch) {
        val queue = updateQueues[batch.account] ?: return
        if (queue.firstOrNull() !== batch) return
        queue.removeFirst()
        val next = queue.firstOrNull()
        if (next == null) updateQueues.remove(batch.account) else EngineDispatch.scheduler.postRunnable { runBatch(next) }
    }

    /**
     * **The batch is handed back whole, and a drop is a mark rather than a removal.** Stock applies
     * the pts of a group it accepted (`lastPts + pts_count == pts`, then `setLastPtsValue`) around
     * `processUpdateArray`, so an update taken *out* leaves its pts unaccounted for: the next group
     * no longer lines up, the app parks it and runs a catch-up, and the message a plugin dropped
     * comes back. Left in and marked, stock's own arithmetic is untouched and
     * [isDropped] skips the payload inside the loop, so the drop costs no round trip and cannot
     * desync. Nothing here rebuilds the batch, which is also why no shape of it can fail to.
     *
     * The two short forms are the exception, the app applying them from their own fields and never
     * building the `Update` a middleware was handed. A *rewritten* one is handed over as the
     * `TL_updates` the server would have sent; so is a *dropped* one, marked, because that batch is
     * what carries the pts advance into the loop above. Only those two cases: stock's branch
     * prefetches the sender and does its own pts bookkeeping, and there is no reason to leave it
     * for a plugin that only looked.
     */
    private fun deliverable(batch: UpdateBatch, delivery: UpdateDelivery.Batch): TLRPC.Updates? {
        val updates = delivery.updates
        val short = batch.units.firstOrNull { it.synthesized }
        if (short != null) {
            if (short.update in batch.dropped) {
                droppedUpdates.add(short.update)
                return asUpdatesBatch(short.update, updates)
            }
            val untouched = short.snapshot != null && rawSnapshotOf(short.update) == short.snapshot
            return if (untouched) updates else asUpdatesBatch(short.update, updates)
        }
        for (unit in batch.units) {
            if (unit.update in batch.dropped) droppedUpdates.add(unit.update)
        }
        return updates
    }

    /** the app's own update loop, asking whether it may apply this one */
    @JvmStatic
    fun isDropped(update: TLObject?): Boolean = update != null && update in droppedUpdates

    /**
     * `users`/`chats` stay empty on purpose. Stock groups by `getUpdatePts`/`getUpdatePtsCount`
     * (which is why the synthetic update carries both) and hands the group to `processUpdateArray`,
     * which resolves every peer through the batch's own `users`, then `MessagesController.getUser`,
     * then `MessagesStorage.getUserSync`, and answers a miss with `needGetDiff`. So an uncached
     * sender is backfilled exactly as on the path this replaces.
     */
    private fun asUpdatesBatch(update: TLObject, original: TLRPC.Updates): TLRPC.Updates =
        TLRPC.TL_updates().apply {
            updates = arrayListOf(update as TLRPC.Update)
            date = original.date
        }

    /**
     * the difference catch-up paths walk their payload themselves instead of feeding it through
     * [onUpdates], so without this a plugin sees nothing for anything that arrived while it was
     * offline. `new_messages` are bare messages, so each is wrapped in the update the server would
     * have sent had the client been online.
     *
     * Claiming one parks the caller's whole runnable, which is why [apply] is the runnable itself:
     * it is re-run once the walk is done and lets its own hand-back through. Answering `true` means
     * the caller must return, having applied nothing.
     *
     * Must stay at the top of the difference's own stageQueue runnable: the secret-chat messages
     * `getDifference` decrypts are appended to `new_messages` further down that same runnable.
     */
    @JvmStatic
    fun onDifference(
        newMessages: MutableList<TLRPC.Message>?,
        otherUpdates: MutableList<TLRPC.Update>?,
        account: Int,
        apply: Runnable,
    ): Boolean {
        // our own hand-back, as in [onUpdates]: observers see what the app is about to apply
        if (takenOverDifferences.remove(apply)) {
            if (hasUpdateListeners) fanOut(unpackDifference(newMessages, otherUpdates), account)
            return false
        }
        val busy = updateQueues[account]?.isNotEmpty() == true
        if (!busy && !hasUpdateInterceptors) {
            if (hasUpdateListeners) fanOut(unpackDifference(newMessages, otherUpdates), account)
            return false
        }
        val units = if (hasUpdateInterceptors) {
            interceptableUnits(unpackDifference(newMessages, otherUpdates), snapshots = false)
        } else {
            emptyList()
        }
        if (!busy && units.isEmpty()) {
            if (hasUpdateListeners) fanOut(unpackDifference(newMessages, otherUpdates), account)
            return false
        }
        return enqueue(UpdateBatch(UpdateDelivery.Difference(newMessages, otherUpdates, apply), account, units))
    }

    private fun unpackDifference(
        newMessages: List<TLRPC.Message>?,
        otherUpdates: List<TLRPC.Update>?,
    ): List<UnpackedUpdate> {
        val unpacked = ArrayList<UnpackedUpdate>()
        otherUpdates?.forEach { unpacked.add(UnpackedUpdate(it, it)) }
        newMessages?.forEach { message ->
            // stock skips these too: a hole the server is reporting, not a message
            if (message is TLRPC.TL_messageEmpty) return@forEach
            unpacked.add(UnpackedUpdate(message, wrapDifferenceMessage(message)))
        }
        return unpacked
    }

    private fun fanOut(batch: List<UnpackedUpdate>, account: Int) {
        if (batch.isEmpty()) return
        Utilities.stageQueue.postRunnable {
            EngineDispatch.scheduler.postRunnable {
                for (unpacked in batch) {
                    // a dropped update is still in the batch the app was handed, marked rather than removed, and never happened for observers either
                    if (unpacked.update in droppedUpdates) continue
                    if (!rememberDispatch(unpacked.arrival)) continue
                    dispatchUpdate(unpacked.update, account)
                }
            }
        }
    }

    /** the sender decides the shape, as on the live path; pts is 0 - a difference carries one state for the whole batch */
    private fun wrapDifferenceMessage(message: TLRPC.Message): TLObject =
        if (message.peer_id is TLRPC.TL_peerChannel) {
            TL_update.TL_updateNewChannelMessage().apply { this.message = message }
        } else {
            TL_update.TL_updateNewMessage().apply { this.message = message }
        }

    /**
     * paired with the object stock would re-feed it as. The short forms are their own update (each
     * pass through [normalizeShortMessage] mints a different one, which would defeat [arrival]), and
     * a difference's `new_messages` are keyed on the message for the same reason.
     */
    private data class UnpackedUpdate(val arrival: TLObject, val update: TLObject)

    /** the one reading of a batch's shape, so a new `Updates` subclass cannot be taught to the interception pass and not the fan-out */
    private fun unpackUpdates(updates: TLRPC.Updates, account: Int): List<UnpackedUpdate> = when (updates) {
        is TLRPC.TL_updateShort ->
            listOfNotNull(updates.update?.let { UnpackedUpdate(it, it) })
        is TLRPC.TL_updates ->
            updates.updates?.map { UnpackedUpdate(it, it) } ?: emptyList()
        is TLRPC.TL_updatesCombined ->
            updates.updates?.map { UnpackedUpdate(it, it) } ?: emptyList()
        is TLRPC.TL_updateShortMessage,
        is TLRPC.TL_updateShortChatMessage ->
            listOf(UnpackedUpdate(updates, normalizeShortMessage(updates, account)))
        else -> emptyList()
    }

    /**
     * stock re-feeds a parked batch once its pts lands, and the wrapper holds the very `Update`
     * instances the first pass delivered. The hook's own `fromQueue` flag cannot tell them apart,
     * since a wrapper is also how sub-updates the first pass could not apply come back.
     *
     * Bounded rather than complete: stock waits ~1.5 s on a pts hole before giving the queue up.
     */
    private fun rememberDispatch(arrival: TLObject): Boolean = dispatchedUpdates.add(arrival)

    /**
     * the compressed short forms carry no `Update`, so the one stock's own branch would have
     * applied is rebuilt from stock's own builder. `updateShortSentMessage` is not delivered at all
     * - a send ack with no message body, and the sender already holds the rpc response.
     */
    private fun normalizeShortMessage(updates: TLRPC.Updates, account: Int): TL_update.TL_updateNewMessage =
        TL_update.TL_updateNewMessage().apply {
            message = MessagesController.getInstance(account).inu_buildShortMessage(updates)
            pts = updates.pts
            pts_count = updates.pts_count
        }

    /** skipped before anything is minted: the type list is required precisely so filtering costs a map lookup rather than a handle and a bridge crossing per update */
    private fun dispatchUpdate(update: TLObject, account: Int) {
        val tlName = TlNames.classNameToTlName(update.javaClass)
        val listening = updateListenersByType[tlName] ?: return
        // the one rule `unsafe.disableApiFiltering` does not lift, same as `PeerSpecs.dialogIdOf` refusing an encrypted dialog id
        if (isSecretChatUpdate(update)) return
        val serviceNotification = update is TL_update.TL_updateServiceNotification
        for (listener in listening) {
            val session = listener.session
            if (!session.canDispatch()) continue
            val engine = session.engine
            val tl = session.tl
            // carries a login code with no peer to redact against. per-plugin rather than in the unpack loop, so the bypass grant lifts it like the other three
            if (serviceNotification && !session.permissions.has("unsafe.disableApiFiltering")) continue
            // over the scopes that actually authorized this plugin for this constructor - a demuxed registration holds its event's scope, and the two never imply each other
            if (listener.grantScopes.none { session.permissions.allows("onUpdate", it, ScopeMatch.EXACT) }) continue
            val handle = tl.mintForPlugin(update, readOnly = true)
            engine.dispatchUpdate(tlName, account, PluginWire.encodeHandle(vector = false, id = handle, readOnly = true))
        }
    }

    /** none of the four has a `DialogId` a plugin could have named in the first place */
    private fun isSecretChatUpdate(update: TLObject): Boolean =
        update is TL_update.TL_updateNewEncryptedMessage ||
            update is TL_update.TL_updateEncryption ||
            update is TL_update.TL_updateEncryptedChatTyping ||
            update is TL_update.TL_updateEncryptedMessagesRead
}
