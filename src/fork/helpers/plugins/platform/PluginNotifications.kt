package desu.inugram.helpers.plugins.platform

import desu.inugram.core.plugins.OwnerRegistry
import desu.inugram.helpers.plugins.SessionResource
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.NotificationListener
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.QuickJs
import java.lang.reflect.Modifier
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities

/**
 * `inu.android.addNotificationCenterDelegate` (rust: `notifications.rs`): the app's own internal
 * event bus, behind `unsafe.notificationCenter` and `unsafe.jvm`.
 *
 * **A payload crosses the way any other java value does**, through [PluginJvm.ValueBridge]: a
 * scalar as itself, anything else as a `JavaObject` handle out of the same table `inu.jvm` and
 * `inu.xposed` mint into. The events are not TL, so there is no chokepoint to filter at and a
 * handle reaches exactly as far as `unsafe.jvm` already does - which is why this requires that
 * grant rather than standing on its own.
 *
 * **The encoding happens inside the observer**, not after the queue hop: the array belongs to stock
 * and observers downstream rewrite it (`didReceiveNewMessages` hands over a mutable message list),
 * so a handle minted after the hop would name whatever that list had become.
 *
 * **A payload the engine never takes is released here.** Minting happens before the hop and the hop
 * can drop the work, so the handles a dropped dispatch left behind are this object's to free.
 *
 * **Every registration is torn down at [detach].** [NotificationCenter] holds its observers
 * strongly and one of these closes over the [QuickJs] it dispatches into, so one left behind keeps
 * an unloaded plugin's engine alive for the life of the process.
 */
object PluginNotifications : SessionResource {
    /** read off the class rather than generated: the names are stock's own, so a rebase moves this with them and there is no table to regenerate */
    private val idsByName: Map<String, Int> by lazy {
        val out = HashMap<String, Int>()
        for (field in NotificationCenter::class.java.declaredFields) {
            val modifiers = field.modifiers
            if (!Modifier.isStatic(modifiers) || !Modifier.isPublic(modifiers) || !Modifier.isFinal(modifiers)) continue
            if (field.type != Int::class.javaPrimitiveType) continue
            out[field.name] = field.getInt(null)
        }
        out
    }

    private val namesById: Map<Int, String> by lazy { idsByName.entries.associate { (name, id) -> id to name } }

    private class Registration(
        val session: PluginSession,
        val callbackId: Int,
        val ids: IntArray,
    ) {
        var observer: NotificationCenter.NotificationCenterDelegate? = null
    }

    private val live = OwnerRegistry<PluginSession, Registration>()

    /** what `inu.notifications.suppress` holds, belonging to no one account; keep in sync with rust */
    const val ANY_ACCOUNT = -1

    private class Suppression(val token: Int, val account: Int)

    /**
     * `inu.notifications.suppress` and `Account.suppressNotifications`. A hold per token rather
     * than a flag, so two plugins asking at once do not cancel each other and a plugin that is
     * torn down without disposing releases only its own. Read by
     * [desu.inugram.helpers.NotificationsHelper.shouldSuppressNotifications], which stock consults
     * before posting, so a suppressed account dismisses rather than posting.
     */
    private val suppressors = OwnerRegistry<PluginSession, Suppression>()

    @JvmStatic
    fun areNotificationsSuppressed(account: Int): Boolean =
        suppressors.any { it.account == ANY_ACCOUNT || it.account == account }

    private fun setSuppressed(session: PluginSession, token: Int, account: Int, on: Boolean) {
        if (on) {
            suppressors.add(session, Suppression(token, account))
        } else {
            suppressors.remove(session) { it.token == token }
        }
    }

    fun listenerFor(session: PluginSession): NotificationListener =
        object : NotificationListener {
            override fun register(callbackId: Int, events: Array<String>): String? =
                startObserving(session, callbackId, events)

            override fun unregister(callbackId: Int) = stopObserving(session, callbackId)

            override fun suppress(token: Int, account: Int, on: Boolean) = setSuppressed(session, token, account, on)
        }

    private fun startObserving(session: PluginSession, callbackId: Int, events: Array<String>): String? {
        val ids = IntArray(events.size)
        for (index in events.indices) {
            // a closed vocabulary: a name this app does not have is refused rather than silently never firing, which a plugin could not tell from an event that never happened
            ids[index] = idsByName[events[index]]
                ?: return PluginWire.encodePluginError("invalid-argument", "no notification named '${events[index]}'")
        }
        val registration = Registration(session, callbackId, ids)
        live.add(session, registration)
        AndroidUtilities.runOnUIThread {
            val observer = NotificationCenter.NotificationCenterDelegate { id, accountId, args ->
                deliver(registration, id, accountId, args)
            }
            registration.observer = observer
            for (centre in centres()) {
                for (id in ids) centre.addObserver(observer, id)
            }
        }
        return null
    }

    /** only ever called on the ui thread: [NotificationCenter.getInstance] and `getGlobalInstance` are both `@UiThread`, as is observing */
    private fun centres(): List<NotificationCenter> {
        val out = ArrayList<NotificationCenter>(UserConfig.MAX_ACCOUNT_COUNT + 1)
        out.add(NotificationCenter.getGlobalInstance())
        for (account in 0 until UserConfig.MAX_ACCOUNT_COUNT) out.add(NotificationCenter.getInstance(account))
        return out
    }

    private fun deliver(registration: Registration, id: Int, accountId: Int, args: Array<Any?>) {
        val name = namesById[id] ?: return
        val session = registration.session
        val bridge = PluginJvm.bridgeFor(session.engine) ?: return
        val wires = encodeArgs(bridge, args)
        val engine = session.engine
        EngineDispatch.onEngine(session, onDropped = { PluginJvm.releaseUntaken(engine, wires.asList()) }) {
            engine.dispatchNotification(registration.callbackId, name, accountId, wires)
        }
    }

    private fun stopObserving(session: PluginSession, callbackId: Int) {
        val registration = live.remove(session) { it.callbackId == callbackId } ?: return
        removeObserver(registration)
    }

    /** the remove takes the same ui hop the add did, which is the only thing that orders it behind one: `runOnUIThread` always posts */
    private fun removeObserver(registration: Registration) {
        AndroidUtilities.runOnUIThread {
            val observer = registration.observer ?: return@runOnUIThread
            registration.observer = null
            for (centre in centres()) {
                for (id in registration.ids) centre.removeObserver(observer, id)
            }
        }
    }

    override fun detach(session: PluginSession) {
        for (registration in live.take(session)) removeObserver(registration)
        suppressors.take(session)
    }

    /**
     * One wire per argument, minted here on the observer's thread. A value the bridge refuses -
     * the engine's own bridge package, or one past the value limit - is the one argument lost
     * rather than the whole event, since a delegate reading `args[3]` must still find it there.
     */
    private fun encodeArgs(bridge: PluginJvm.ValueBridge, args: Array<Any?>): Array<String> =
        Array(args.size) { index ->
            try {
                bridge.encode(args[index])
            } catch (e: Exception) {
                PluginWire.encodeNull()
            }
        }
}
