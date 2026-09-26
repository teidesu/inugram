package desu.inugram.helpers.plugins.telegram

import desu.inugram.core.plugins.BoundedIdentitySet
import desu.inugram.core.plugins.DispatchDeadline
import desu.inugram.core.plugins.PluginWire
import desu.inugram.core.plugins.ScopeMatch
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.PluginLog
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.SessionResource
import desu.inugram.helpers.plugins.UpdatesListener
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlHandles
import desu.inugram.helpers.plugins.tl.TlJson
import desu.inugram.helpers.plugins.tl.TlNames
import desu.inugram.core.plugins.TlTables
import java.util.Collections
import java.util.IdentityHashMap
import org.telegram.messenger.MessagesController
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_update

/**
 * Delivery back to stock runs on [Utilities.stageQueue]: `processUpdates` mutates pts/seq without locks.
 *
 * Three arrival forms share dedup, filtering and disposer rules: live batches ([onUpdates]),
 * difference catch-up ([onDifference]), and compressed short messages, which get a synthetic update
 * from [normalizeShortMessage]. Route new arrival paths through these instead of adding dispatch sites.
 */
object PluginUpdates : SessionResource {

    /** the app's whole arriving batch is parked behind this */
    private const val UPDATE_BUDGET_MS = 2_000L
    private const val DISPATCH_MEMORY = 2048

    @Volatile private var hasUpdateListeners = false
    @Volatile private var hasUpdateInterceptors = false

    @Volatile private var updateRegs: List<UpdateReg> = emptyList()
    @Volatile private var updateListenersByType: Map<String, List<UpdateListener>> = emptyMap()
    @Volatile private var updateInterceptRegs: List<UpdateInterceptor> = emptyList()
    @Volatile private var updateInterceptorsByType: Map<String, List<UpdateInterceptor>> = emptyMap()

    // rust keeps interceptRpc and interceptUpdate dispatch ids in separate tables
    private var nextDispatchId = 1L
    private val pendingUpdateDispatches = HashMap<Long, UpdateBatch>()

    // no TLRPC class overrides hashCode
    private val dispatchedUpdates = BoundedIdentitySet<TLObject>(DISPATCH_MEMORY)
    private val updateQueues = HashMap<Int, ArrayDeque<UpdateBatch>>()
    private val takenOver = Collections.newSetFromMap(IdentityHashMap<TLRPC.Updates, Boolean>())
    private val takenOverDifferences = Collections.newSetFromMap(IdentityHashMap<Runnable, Boolean>())
    // stock re-feeds a parked batch around the same objects a first pass ran over
    private val interceptedUpdates = BoundedIdentitySet<TLObject>(DISPATCH_MEMORY)
    // a re-fed batch is not re-intercepted, so a verdict cleared after the hand-back would be lost
    private val droppedUpdates = BoundedIdentitySet<TLObject>(DISPATCH_MEMORY)

    @Volatile private var anyDropped = false

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

    /** delivered, never failed: nothing re-requests a dropped update, so a drop on unload loses messages */
    override fun detach(session: PluginSession) {
        publishUpdateRegs(updateRegs.filter { it.session !== session })
        publishUpdateInterceptors(updateInterceptRegs.filter { it.session !== session })
        for (dispatchId in pendingUpdateDispatches.filterValues { it.stageSession === session }.keys.toList()) {
            val batch = pendingUpdateDispatches.remove(dispatchId) ?: continue
            batch.stageSession?.engine?.abandonUpdateDispatch(dispatchId, PluginRpc.ABANDONED_WIRE)
            advanceBatch(batch)
        }
    }

    /** `common.d.ts` keeps constructor and demuxed-event vocabularies apart, so [grantScope] is not derivable */
    private class UpdateReg(
        val session: PluginSession,
        val callbackId: Int,
        val types: Set<String>,
        val grantScope: String?,
    )

    private class UpdateListener(val session: PluginSession) {
        val grantScopes = HashSet<String>()
    }

    private class UpdateInterceptor(val session: PluginSession, val callbackId: Int, val types: Set<String>)

    /** null [snapshot] means snapshotting failed and a rewrite is assumed */
    private class UpdateUnit(
        val arrival: TLObject,
        val update: TLObject,
        val tlName: String,
        val chain: List<UpdateInterceptor>,
        val synthesized: Boolean,
        val snapshot: String?,
    )

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

    /** one budget for the whole batch: a per-update budget would hold the stream for batch size times budget */
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

    /** unknown constructors are refused: one that silently never fires is an hour of debugging */
    private fun registerUpdates(session: PluginSession, callbackId: Int, types: Array<String>, scope: String): String? {
        for (type in types) {
            if (type !in TlTables.updateNames) {
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

    private fun registerInterceptUpdates(session: PluginSession, callbackId: Int, types: Array<String>): String? {
        for (type in types) {
            if (type !in TlTables.updateNames) {
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
        // the ring holds strong references to update graphs nothing will look up again
        if (!hasUpdateInterceptors) {
            interceptedUpdates.clear()
        }
    }

    /**
     * synchronous because the batch is only whole on entry: stock moves a sub-update with misaligned pts
     * into a fresh wrapper it parks. The fan-out hops through stageQueue so plugins read objects after
     * `processUpdateArray` backfilled them.
     */
    @JvmStatic
    fun onUpdates(controller: MessagesController, updates: TLRPC.Updates, account: Int, fromQueue: Boolean): Boolean {
        if (!PluginManager.anyRunning) return false
        // our own hand-back: observers see what the app is about to apply, so a drop is invisible to `onUpdate` too
        val handedBack = takenOver.remove(updates)
        return routeUpdates(account, handedBack, snapshots = true, { unpackUpdates(updates, account) }) {
            UpdateDelivery.Batch(controller, updates, fromQueue)
        }
    }

    /** `true` when the batch was queued and the caller must apply nothing */
    private fun routeUpdates(
        account: Int,
        handedBack: Boolean,
        snapshots: Boolean,
        unpack: () -> List<UnpackedUpdate>,
        delivery: () -> UpdateDelivery,
    ): Boolean {
        val busy = !handedBack && updateQueues[account]?.isNotEmpty() == true
        val units = if (!handedBack && hasUpdateInterceptors) interceptableUnits(unpack(), snapshots) else emptyList()
        if (!busy && units.isEmpty()) {
            if (hasUpdateListeners) fanOut(unpack(), account)
            return false
        }
        return enqueue(UpdateBatch(delivery(), account, units))
    }

    private fun enqueue(batch: UpdateBatch): Boolean {
        val queue = updateQueues.getOrPut(batch.account) { ArrayDeque() }
        queue.addLast(batch)
        // unclaimed batches queue too: the app applies updates in arrival order
        if (queue.size == 1) EngineDispatch.scheduler.postRunnable { runBatch(batch) }
        return true
    }

    /** snapshotted here, so a registration made mid-walk joins the next batch like every `Disposer` */
    private fun interceptableUnits(unpacked: List<UnpackedUpdate>, snapshots: Boolean): List<UpdateUnit> {
        val units = ArrayList<UpdateUnit>()
        for ((arrival, update) in unpacked) {
            val tlName = TlNames.classNameToTlName(update.javaClass)
            val chain = chainFor(update, tlName)
            if (chain.isEmpty()) continue
            // not [dispatchedUpdates]: sharing one would make the hand-back look already delivered
            if (!interceptedUpdates.add(arrival)) continue
            val synthesized = arrival !== update
            val snapshot = if (snapshots && synthesized) snapshotRawUpdate(update) else null
            units.add(UpdateUnit(arrival, update, tlName, chain, synthesized, snapshot))
        }
        return units
    }

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

    /** unfiltered on purpose: a hidden field would hide a rewrite reachable through a nested view */
    private val RAW_POLICY = TlFilter.Policy(takeover = false, drafts = true)

    private fun snapshotRawUpdate(update: TLObject): String? = try {
        TlJson.toJson(update, RAW_POLICY).toString()
    } catch (e: Exception) {
        PluginLog.HOST.w("updates", "cannot snapshot ${update.javaClass.simpleName}: $e")
        null
    }

    private fun runBatch(batch: UpdateBatch) {
        batch.scopeId = TlHandles.newScope()
        batch.deadline.resume()
        advanceBatch(batch)
    }

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
            engine.dispatchUpdateIntercept(
                interceptor.callbackId,
                dispatchId,
                unit.tlName,
                batch.account,
                tl.mintWireForScope(unit.update, batch.scopeId),
            )
            return
        }
        finishBatch(batch)
    }

    /** posted: this arrives inside the engine's JNI upcall, and the next stage may be the same engine */
    private fun onUpdateStageSettled(dispatchId: Long, deliver: Boolean) {
        EngineDispatch.scheduler.postRunnable {
            val batch = pendingUpdateDispatches.remove(dispatchId) ?: return@postRunnable
            batch.stageSession = null
            if (!deliver) {
                batch.dropped.add(batch.units[batch.index].update)
            }
            advanceBatch(batch)
        }
    }

    /** undecided updates are delivered, never dropped: nothing re-requests a drop, so a stall would lose messages */
    private fun expireBatch(batch: UpdateBatch) {
        if (batch.finished) return
        batch.expired = true
        val dispatchId = batch.dispatchId
        val stageSession = batch.stageSession
        if (dispatchId != 0L && pendingUpdateDispatches.remove(dispatchId) === batch) {
            stageSession?.engine?.abandonUpdateDispatch(dispatchId, PluginRpc.TIMEOUT_WIRE)
        }
        (stageSession?.log ?: PluginLog.HOST).w("updates", "an update batch ran past the ${UPDATE_BUDGET_MS}ms budget")
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
     * `processUpdates` reaches most of the app and a throw here is unrecoverable: the account's stream is
     * parked behind this entry. The queue advances whatever happens, and nothing escapes onto stageQueue.
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
                    // a difference unit wraps the object the app applies, so a rewrite already landed and a drop is a list removal
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
            PluginLog.HOST.e("updates", "handing an update batch back to the app failed", e)
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
     * A drop is a mark, not a removal. Stock applies a group's pts (`lastPts + pts_count == pts`, then
     * `setLastPtsValue`) around `processUpdateArray`, so a removed update leaves its pts unaccounted: the
     * next group misaligns, stock runs a catch-up, and the dropped message comes back.
     *
     * Short forms are the exception: stock applies them from their own fields. A rewritten or dropped one
     * is handed over as the `TL_updates` the server would have sent; unchanged ones keep stock's branch,
     * which prefetches the sender and does its own pts bookkeeping.
     */
    private fun deliverable(batch: UpdateBatch, delivery: UpdateDelivery.Batch): TLRPC.Updates? {
        val updates = delivery.updates
        val short = batch.units.firstOrNull { it.synthesized }
        if (short != null) {
            if (short.update in batch.dropped) {
                dropUpdate(short.update)
                return asUpdatesBatch(short.update, updates)
            }
            val untouched = short.snapshot != null && snapshotRawUpdate(short.update) == short.snapshot
            return if (untouched) updates else asUpdatesBatch(short.update, updates)
        }
        for (unit in batch.units) {
            if (unit.update in batch.dropped) dropUpdate(unit.update)
        }
        return updates
    }

    private fun dropUpdate(update: TLObject) {
        droppedUpdates.add(update)
        anyDropped = true
    }

    @JvmStatic
    fun isDropped(update: TLObject?): Boolean = anyDropped && update != null && update in droppedUpdates

    /**
     * `users`/`chats` stay empty: `processUpdateArray` resolves peers through the batch, then
     * `getUser`, then `getUserSync`, and a miss triggers `needGetDiff`, as on the path this replaces
     */
    private fun asUpdatesBatch(update: TLObject, original: TLRPC.Updates): TLRPC.Updates =
        TLRPC.TL_updates().apply {
            updates = arrayListOf(update as TLRPC.Update)
            date = original.date
        }

    /**
     * difference catch-up walks its payload itself instead of going through [onUpdates].
     * Claiming parks the caller's whole runnable, so [apply] is re-run after the walk; `true` means the
     * caller must return having applied nothing.
     * Must stay at the top of the difference runnable: `getDifference` appends decrypted secret-chat
     * messages to `new_messages` further down it.
     */
    @JvmStatic
    fun onDifference(
        newMessages: MutableList<TLRPC.Message>?,
        otherUpdates: MutableList<TLRPC.Update>?,
        account: Int,
        apply: Runnable,
    ): Boolean {
        if (!PluginManager.anyRunning) return false
        val handedBack = takenOverDifferences.remove(apply)
        return routeUpdates(account, handedBack, snapshots = false, { unpackDifference(newMessages, otherUpdates) }) {
            UpdateDelivery.Difference(newMessages, otherUpdates, apply)
        }
    }

    private fun unpackDifference(
        newMessages: List<TLRPC.Message>?,
        otherUpdates: List<TLRPC.Update>?,
    ): List<UnpackedUpdate> {
        val unpacked = ArrayList<UnpackedUpdate>()
        otherUpdates?.forEach { unpacked.add(UnpackedUpdate(it, it)) }
        newMessages?.forEach { message ->
            // stock skips these too
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
                    if (unpacked.update in droppedUpdates) continue
                    if (!dispatchedUpdates.add(unpacked.arrival)) continue
                    dispatchUpdate(unpacked.update, account)
                }
            }
        }
    }

    /** a difference carries one pts state for the whole batch */
    private fun wrapDifferenceMessage(message: TLRPC.Message): TLObject =
        if (message.peer_id is TLRPC.TL_peerChannel) {
            TL_update.TL_updateNewChannelMessage().apply { this.message = message }
        } else {
            TL_update.TL_updateNewMessage().apply { this.message = message }
        }

    /** each [normalizeShortMessage] pass mints a new update, so short forms and difference messages key on the arrival object */
    private data class UnpackedUpdate(val arrival: TLObject, val update: TLObject)

    private fun unpackUpdates(updates: TLRPC.Updates, account: Int): List<UnpackedUpdate> = when (updates) {
        is TLRPC.TL_updateShort ->
            listOfNotNull(updates.update?.let { UnpackedUpdate(it, it) })
        is TLRPC.TL_updates, is TLRPC.TL_updatesCombined ->
            updates.updates?.map { UnpackedUpdate(it, it) } ?: emptyList()
        is TLRPC.TL_updateShortMessage,
        is TLRPC.TL_updateShortChatMessage ->
            listOf(UnpackedUpdate(updates, normalizeShortMessage(updates, account)))
        else -> emptyList()
    }

    /** `updateShortSentMessage` is not delivered: a send ack whose sender already holds the rpc response */
    private fun normalizeShortMessage(updates: TLRPC.Updates, account: Int): TL_update.TL_updateNewMessage =
        TL_update.TL_updateNewMessage().apply {
            message = MessagesController.getInstance(account).inu_buildShortMessage(updates)
            pts = updates.pts
            pts_count = updates.pts_count
        }

    private fun dispatchUpdate(update: TLObject, account: Int) {
        val tlName = TlNames.classNameToTlName(update.javaClass)
        val listening = updateListenersByType[tlName] ?: return
        // not lifted by `unsafe.disableApiFiltering`, same as `PeerSpecs.resolveDialogId` refusing an encrypted dialog id
        if (isSecretChatUpdate(update)) return
        val serviceNotification = update is TL_update.TL_updateServiceNotification
        for (listener in listening) {
            val session = listener.session
            if (!session.canDispatch()) continue
            val engine = session.engine
            val tl = session.tl
            // carries a login code with no peer to redact against. per-plugin so the bypass grant lifts it
            if (serviceNotification && !session.permissions.has("unsafe.disableApiFiltering")) continue
            // a demuxed registration's scope and a constructor scope never imply each other
            if (listener.grantScopes.none { session.permissions.allows("onUpdate", it, ScopeMatch.EXACT) }) continue
            engine.dispatchUpdate(tlName, account, tl.mintWireForPlugin(update, readOnly = true))
        }
    }

    private fun isSecretChatUpdate(update: TLObject): Boolean =
        update is TL_update.TL_updateNewEncryptedMessage ||
            update is TL_update.TL_updateEncryption ||
            update is TL_update.TL_updateEncryptedChatTyping ||
            update is TL_update.TL_updateEncryptedMessagesRead
}
