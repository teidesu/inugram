package desu.inugram.helpers.plugins.platform

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import desu.inugram.core.plugins.OwnerRegistry
import desu.inugram.helpers.NotificationsHelper
import desu.inugram.helpers.security.ParanoiaHelper
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.ui.PluginIcons
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
            private val budget = PostBudget()

            override fun register(callbackId: Int, events: Array<String>): String? =
                startObserving(session, callbackId, events)

            override fun unregister(callbackId: Int) = stopObserving(session, callbackId)

            override fun suppress(token: Int, account: Int, on: Boolean) = setSuppressed(session, token, account, on)

            override fun notificationPost(token: Int, optionsJson: String): String? =
                post(session, budget, token, optionsJson)

            override fun notificationCancel(token: Int) = cancel(session, token)

            override fun notificationCancelById(id: String) = cancelById(session, id)

            override fun notificationListenClicks() = listenClicks(session)
        }

    /** a non-exported alias of LaunchActivity: only our own pending intents reach it, so a click cannot be forged */
    private const val CLICK_ALIAS = "desu.inugram.PluginNotificationClick"
    private const val EXTRA_INSTALL = "install"
    private const val EXTRA_ID = "id"
    private const val EXTRA_DATA = "data"
    private const val CHANNEL_GROUP = "inu_plugins"
    private const val CHANNEL_PREFIX = "inu_plugin_"
    private const val TAG_PREFIX = "inu_plugin:"

    /** the tag tells plugin notifications apart */
    private const val NOTIFICATION_ID = 1

    /** android drops an app's notifications past ~50 shown and ~5 posts a second, stock's included */
    private const val MAX_SHOWN = 8
    private const val POST_BURST = 5
    private const val POST_REFILL_MS = 1000L
    private const val MAX_PENDING_CLICKS = 16

    private class Posted(val token: Int, val tag: String)

    /** empty strings stand for a notification posted without an id or data */
    private class Click(val id: String, val dataJson: String)

    private val posted = OwnerRegistry<PluginSession, Posted>()

    private val clickListeners: MutableSet<PluginSession> = ConcurrentHashMap.newKeySet()

    /** by install id: clicks on a plugin that is not running or has not subscribed yet */
    private val pendingClicks = HashMap<String, ArrayDeque<Click>>()

    /** engine thread only */
    private class PostBudget {
        private var tokens = POST_BURST.toDouble()
        private var refilledAt = SystemClock.uptimeMillis()

        fun take(): Boolean {
            val now = SystemClock.uptimeMillis()
            tokens = minOf(POST_BURST.toDouble(), tokens + (now - refilledAt).toDouble() / POST_REFILL_MS)
            refilledAt = now
            if (tokens < 1) return false
            tokens--
            return true
        }
    }

    private fun getManager(): NotificationManager? =
        ApplicationLoader.applicationContext?.getSystemService(NotificationManager::class.java)

    private fun post(session: PluginSession, budget: PostBudget, token: Int, optionsJson: String): String? {
        val context = ApplicationLoader.applicationContext ?: return PluginWire.encodeError("internal: no context")
        val manager = getManager() ?: return PluginWire.encodeError("internal: no notification manager")
        if (!manager.areNotificationsEnabled()) {
            return PluginWire.encodePluginError("forbidden", "notifications.post: notifications are turned off for the app")
        }
        val options = JSONObject(optionsJson)
        val installId = session.plugin.id
        val tagPrefix = "$TAG_PREFIX$installId:"
        val id = options.optString("id")
        val tag = tagPrefix + id.ifEmpty { "#" + UUID.randomUUID() }
        val shown = manager.activeNotifications.count { it.tag != tag && it.tag?.startsWith(tagPrefix) == true }
        if (shown >= MAX_SHOWN) {
            return PluginWire.encodePluginError(
                "quota-exceeded",
                "notifications.post: at most $MAX_SHOWN notifications are shown at once",
                usage = shown + 1L,
                quota = MAX_SHOWN.toLong(),
            )
        }
        if (!budget.take()) {
            return PluginWire.encodePluginError(
                "quota-exceeded",
                "notifications.post: at most $POST_BURST posts at once, then one a second",
            )
        }
        posted.remove(session) { it.tag == tag }
        posted.add(session, Posted(token, tag))
        if (ParanoiaHelper.shouldSuppressNotifications()) return null

        manager.createNotificationChannelGroup(
            NotificationChannelGroup(CHANNEL_GROUP, LocaleController.getString(R.string.InuPlugins)),
        )
        val channelId = CHANNEL_PREFIX + installId
        manager.createNotificationChannel(
            NotificationChannel(channelId, session.manifest.name, NotificationManager.IMPORTANCE_DEFAULT)
                .apply { group = CHANNEL_GROUP },
        )

        val intent = Intent()
            .setClassName(context, CLICK_ALIAS)
            // pending intents differ by data, not extras: one per tag
            .setData(Uri.fromParts("inu-plugin", tag, null))
            .putExtra(EXTRA_INSTALL, installId)
            .putExtra(EXTRA_ID, id)
        if (options.has("data")) intent.putExtra(EXTRA_DATA, options.getString("data"))
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val text = options.optString("text").ifEmpty { null }
        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(options.getString("title"))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setSilent(options.optBoolean("silent"))
        if (text != null) builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        val icon = options.optString("icon").ifEmpty { null }?.let(PluginIcons::createNotificationIcon)
        if (icon != null) builder.setSmallIcon(icon) else builder.setSmallIcon(NotificationsHelper.smallIconRes())
        manager.notify(tag, NOTIFICATION_ID, builder.build())
        return null
    }

    private fun cancel(session: PluginSession, token: Int) {
        val entry = posted.remove(session) { it.token == token } ?: return
        getManager()?.cancel(entry.tag, NOTIFICATION_ID)
    }

    private fun cancelById(session: PluginSession, id: String) {
        val manager = getManager() ?: return
        val tagPrefix = "$TAG_PREFIX${session.plugin.id}:"
        if (id.isNotEmpty()) {
            manager.cancel(tagPrefix + id, NOTIFICATION_ID)
            return
        }
        for (shown in manager.activeNotifications) {
            if (shown.tag?.startsWith(tagPrefix) == true) manager.cancel(shown.tag, shown.id)
        }
    }

    private fun listenClicks(session: PluginSession) {
        clickListeners.add(session)
        val pending = synchronized(pendingClicks) { pendingClicks.remove(session.plugin.id) } ?: return
        // after the subscribing call returns, so a handler never runs inside `onClick`
        EngineDispatch.onEngine(session, onDropped = { for (click in pending) queueClick(session.plugin.id, click) }) {
            for (click in pending) session.engine.dispatchNotificationClick(click.id, click.dataJson)
        }
    }

    private fun queueClick(installId: String, click: Click) = synchronized(pendingClicks) {
        val queue = pendingClicks.getOrPut(installId) { ArrayDeque() }
        if (queue.size >= MAX_PENDING_CLICKS) queue.removeFirst()
        queue.addLast(click)
    }

    /**
     * From `LaunchActivity.handleIntent`. An activity recreated after process death or a config change gets
     * its launching intent again, as does a launch from recents: those must not tap twice.
     */
    fun tryHandleClick(intent: Intent?, restore: Boolean): Boolean {
        if (intent?.component?.className != CLICK_ALIAS) return false
        if (restore || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return true
        // stock stashes the intent and replays it once unlocked
        if (AndroidUtilities.needShowPasscode() || SharedConfig.isWaitingForPasscodeEnter) return false
        val installId = intent.getStringExtra(EXTRA_INSTALL) ?: return true
        val click = Click(intent.getStringExtra(EXTRA_ID).orEmpty(), intent.getStringExtra(EXTRA_DATA).orEmpty())
        if (!PluginManager.isEngineEnabled()) return true
        EngineDispatch.scheduler.postRunnable {
            val session = PluginManager.getDispatchTargets().firstOrNull { it.id == installId }?.session
            if (session != null && session.canDispatch() && session in clickListeners) {
                session.engine.dispatchNotificationClick(click.id, click.dataJson)
            } else {
                queueClick(installId, click)
            }
        }
        return true
    }

    /** deleting a channel removes its notifications */
    fun wipe(installId: String) {
        synchronized(pendingClicks) { pendingClicks.remove(installId) }
        getManager()?.deleteNotificationChannel(CHANNEL_PREFIX + installId)
    }

    fun sweepOrphans(live: Set<String>) {
        val manager = getManager() ?: return
        for (channel in manager.notificationChannels) {
            val installId = channel.id.removePrefix(CHANNEL_PREFIX)
            if (installId != channel.id && installId !in live) wipe(installId)
        }
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
        posted.take(session)
        clickListeners.remove(session)
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
