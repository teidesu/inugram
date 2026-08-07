package desu.inugram.helpers.plugins.platform

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginDispatch
import desu.inugram.helpers.plugins.QuickJs
import java.lang.reflect.Modifier
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities

/**
 * `inu.android.addNotificationCenterDelegate` (rust: `notifications.rs`): the app's own internal
 * event bus, behind `unsafe.notificationCenter`.
 *
 * **A payload crosses as scalars and nothing else.** The events carry arbitrary java objects, and a
 * class name or a `toString` in their place would be a shape nothing can act on and a lie about
 * what the event carries. They are not TL either, so there is no chokepoint to filter at - which is
 * why the grant sits in the unsafe tier.
 *
 * **The encoding happens inside the observer**, not after the queue hop: the array belongs to stock
 * and observers downstream rewrite it (`didReceiveNewMessages` hands over a mutable message list).
 *
 * **Every registration is torn down at [detach].** [NotificationCenter] holds its observers
 * strongly and one of these closes over the [QuickJs] it dispatches into, so one left behind keeps
 * an unloaded plugin's engine alive for the life of the process.
 */
object PluginNotifications {
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
        val plugin: Plugin,
        val engine: QuickJs,
        val callbackId: Int,
        val ids: IntArray,
    ) {
        var observer: NotificationCenter.NotificationCenterDelegate? = null
    }

    private val live = HashMap<QuickJs, MutableList<Registration>>()

    fun attach(plugin: Plugin, engine: QuickJs) {
        engine.notificationListener = object : QuickJs.NotificationListener {
            override fun register(callbackId: Int, events: Array<String>): String? =
                startObserving(plugin, engine, callbackId, events)

            override fun unregister(callbackId: Int) = stopObserving(engine, callbackId)
        }
    }

    private fun startObserving(plugin: Plugin, engine: QuickJs, callbackId: Int, events: Array<String>): String? {
        // the engine's own check_grant already ran in native; this is the second gate, on the side that owns the data
        if (!plugin.permissions.has("unsafe.notificationCenter")) {
            return PluginWire.encodeNotGranted("unsafe.notificationCenter")
        }
        val ids = IntArray(events.size)
        for (index in events.indices) {
            // a closed vocabulary: a name this app does not have is refused rather than silently never firing, which a plugin could not tell from an event that never happened
            ids[index] = idsByName[events[index]]
                ?: return PluginWire.encodePluginError("invalid-argument", "no notification named '${events[index]}'")
        }
        val registration = Registration(plugin, engine, callbackId, ids)
        synchronized(live) { live.getOrPut(engine) { ArrayList() }.add(registration) }
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
        val payload = encodeArgs(args)
        val engine = registration.engine
        PluginDispatch.onEngine(registration.plugin, engine) {
            engine.dispatchNotification(registration.callbackId, name, accountId, payload)
        }
    }

    private fun stopObserving(engine: QuickJs, callbackId: Int) {
        val registration = synchronized(live) {
            val mine = live[engine] ?: return
            val found = mine.firstOrNull { it.callbackId == callbackId } ?: return
            mine.remove(found)
            if (mine.isEmpty()) live.remove(engine)
            found
        }
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

    internal fun detach(engine: QuickJs) {
        val mine = synchronized(live) { live.remove(engine) } ?: return
        for (registration in mine) removeObserver(registration)
    }

    private fun encodeArgs(args: Array<Any?>): String {
        val json = JSONArray()
        for (arg in args) json.put(scalarOf(arg))
        return json.toString()
    }

    /** everything that is not a scalar is `null`: see the class doc for why that is a refusal rather than a gap */
    private fun scalarOf(value: Any?): Any = when (value) {
        is Boolean -> value
        is Byte -> value.toInt()
        is Short -> value.toInt()
        is Int -> value
        is Long -> value
        is Float -> finiteOrNull(value.toDouble())
        is Double -> finiteOrNull(value)
        is String -> value
        is Char -> value.toString()
        else -> JSONObject.NULL
    }

    /** org.json refuses to serialize a non-finite double, and a whole payload lost to one is worse */
    private fun finiteOrNull(value: Double): Any = if (value.isFinite()) value else JSONObject.NULL
}
