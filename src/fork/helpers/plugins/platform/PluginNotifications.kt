package desu.inugram.helpers.plugins.platform

import desu.inugram.core.plugins.OwnerRegistry
import desu.inugram.helpers.plugins.UiObservation
import desu.inugram.helpers.plugins.SessionResource
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.NotificationListener
import desu.inugram.helpers.plugins.PluginSession
import java.lang.reflect.Modifier
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig

/**
 * These events are not TL, so no TL filtering; the handles give the same access as `unsafe.jvm`.
 *
 * Encoded inside the observer before posting: stock owns the argument array, and later observers may
 * mutate it. NotificationCenter holds observers strongly, and each retains its engine.
 */
object PluginNotifications : SessionResource {
    /** the names are stock's, so a rebase moves this with them */
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
        ids: IntArray,
    ) {
        val observation = UiObservation(ids, ::centres) { id, accountId, args -> deliver(this, id, accountId, args) }
    }

    private val live = OwnerRegistry<PluginSession, Registration>()

    const val ANY_ACCOUNT = -1

    private class Suppression(val token: Int, val account: Int)

    /**
     * a hold per token, so two plugins don't cancel each other and a torn-down plugin releases only its own.
     * Read by [desu.inugram.helpers.NotificationsHelper.shouldSuppressNotifications] before stock posts.
     */
    private val suppressors = OwnerRegistry<PluginSession, Suppression>()

    fun areNotificationsSuppressed(account: Int): Boolean =
        anySuppressed && suppressors.any { it.account == ANY_ACCOUNT || it.account == account }

    @Volatile private var anySuppressed = false

    private fun setSuppressed(session: PluginSession, token: Int, account: Int, on: Boolean) {
        if (on) {
            suppressors.add(session, Suppression(token, account))
        } else {
            suppressors.remove(session) { it.token == token }
        }
        anySuppressed = suppressors.any { true }
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
            // closed vocabulary: an unknown name is refused, since a plugin could not tell it from an event that never fires
            ids[index] = idsByName[events[index]]
                ?: return PluginWire.encodePluginError("invalid-argument", "no notification named '${events[index]}'")
        }
        val registration = Registration(session, callbackId, ids)
        live.add(session, registration)
        registration.observation.start()
        return null
    }

    /** ui thread only: [NotificationCenter.getInstance] and `getGlobalInstance` are `@UiThread` */
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
        live.remove(session) { it.callbackId == callbackId }?.observation?.stop()
    }

    override fun detach(session: PluginSession) {
        for (registration in live.take(session)) registration.observation.stop()
        suppressors.take(session)
        anySuppressed = suppressors.any { true }
    }

    /** a refused value loses only its argument, since a delegate reading `args[3]` must still find it there */
    private fun encodeArgs(bridge: PluginJvm.ValueBridge, args: Array<Any?>): Array<String> =
        Array(args.size) { index ->
            try {
                bridge.encode(args[index])
            } catch (e: Exception) {
                PluginWire.encodeNull()
            }
        }
}
