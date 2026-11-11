package desu.inugram.helpers

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AppStartReceiver
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.NotificationsService
import org.telegram.messenger.PushListenerController
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.tl.TL_account
import org.telegram.ui.DialogsActivity
import org.telegram.ui.LaunchActivity

// Stock's keep-alive NotificationsService is a plain started service. Without the battery exemption the system stops
// it about a minute after the app leaves the foreground; with it the process still becomes cached and gets frozen.
// Either way the push connection goes silent until the app is opened. In the foreground the service keeps the
// process out of the cached state, and its network and wakelocks working in Doze.
object PersistentConnectionHelper {
    private const val TAG = "InuPersistentConnection"
    private const val CHANNEL_ID = "inu_persistent_connection"
    private const val NOTIFICATION_ID = 0x1e7a
    private const val PUSH_WAKELOCK_TIMEOUT_MS = 60_000L
    private const val BATTERY_REQUEST_DELAY_MS = 1500L
    private const val HEARTBEAT_ACTION = "desu.inugram.PUSH_HEARTBEAT"
    // the server keeps a push connection for 7 minutes after a ping, but xray and similar proxies silently drop a
    // connection idle for 5. Counted from the pong, so a round 5 minutes would always come too late
    private const val HEARTBEAT_INTERVAL_MS = 270_000L
    // outlasts INU_PUSH_PROBE_TIMEOUT in tgnet (5 s); the most awake time an unanswered ping costs
    private const val PING_WAKELOCK_TIMEOUT_MS = 7_000L

    private val lock = Any()
    private var pushWakeLock: PowerManager.WakeLock? = null
    private var pingWakeLock: PowerManager.WakeLock? = null
    private val pingsPending = HashSet<Int>()
    private val lastPong = LongArray(UserConfig.MAX_ACCOUNT_COUNT)
    private var inForeground = false
    private var batteryRequestPending = false
    @Volatile private var unregisterPending = false

    // stock's own check before it registers for FCM; Play services in the middle of an update still count
    private val pushProviderFound by lazy { fcm.hasServices() || playServicesUpdating() }

    private val fcm: PushListenerController.IPushListenerServiceProvider =
        PushListenerController.GooglePushListenerServiceProvider.INSTANCE

    /** Stock's FCM provider for `onCreatePushProvider`, hidden while the mode is on: see [dropFcmToken]. */
    @JvmStatic
    val pushProvider: PushListenerController.IPushListenerServiceProvider =
        object : PushListenerController.IPushListenerServiceProvider by fcm {
            override fun hasServices(): Boolean = !enabled && fcm.hasServices()
        }

    private fun playServicesUpdating(): Boolean = try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ApplicationLoader.applicationContext) ==
            ConnectionResult.SERVICE_UPDATING
    } catch (e: Exception) {
        false
    }

    val autoEnabled: Boolean
        get() = !pushProviderFound

    val enabled: Boolean
        @JvmStatic get() = when (InuConfig.PERSISTENT_CONNECTION_MODE.value) {
            InuConfig.PersistentConnectionModeItem.ON -> true
            InuConfig.PersistentConnectionModeItem.OFF -> false
            else -> autoEnabled
        }

    // specialUse has no runtime limit and, unlike dataSync, may still be started from BOOT_COMPLETED on Android 15+
    private val foregroundType: Int
        get() = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST

    /**
     * Stock `ApplicationLoader.startPushService`, before it reads its own toggles; true means the start was handled
     * here. Login clears the notification prefs and the forced toggles with them (`MessagesController.cleanup()`):
     * they are put back first, or stock would stop the service.
     */
    @JvmStatic
    fun startService(context: Context): Boolean {
        if (!enabled) return false
        if (!MessagesController.getGlobalNotificationsSettings().contains("pushService")) applyStockPush(true)
        try {
            context.startForegroundService(Intent(context, NotificationsService::class.java))
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException: a background start on Android 12+ without the battery
            // exemption. Stock startService() would be refused here as well, so there is nothing to fall back to.
            FileLog.e("$TAG: foreground start refused, exempt=${isIgnoringBatteryOptimizations(context)}", e)
        }
        return true
    }

    /**
     * `InuHooks.init`, just before stock's `startPushService`: stock's toggles may have been flipped since the last
     * run, and in Auto a push provider may have appeared or gone. Opens the first account's notification prefs by
     * file name, the same file `MessagesController` opens, so that no controller is constructed this early.
     */
    @JvmStatic
    fun onProcessStart(context: Context) {
        val prefs = context.getSharedPreferences("Notifications", Context.MODE_PRIVATE)
        if (enabled || InuConfig.PERSISTENT_CONNECTION_STOCK_PUSH_FORCED.value) syncStockPush(prefs, enabled)
        log { "process start: ${state(context)}" }
    }

    @JvmStatic
    fun onServiceCreate(service: Service) {
        if (enabled) {
            applyStockPush(true)
            synchronized(lock) { scheduleHeartbeat() }
        }
        enterForeground(service, "create", 0)
    }

    @JvmStatic
    fun onServiceStartCommand(service: Service, intent: Intent?, flags: Int) {
        // a null intent is the system restarting the sticky service after the process died
        enterForeground(service, if (intent == null) "restart" else "start", flags)
    }

    @JvmStatic
    fun onServiceDestroy(service: Service) {
        synchronized(lock) { inForeground = false }
    }

    private fun enterForeground(service: Service, reason: String, flags: Int) {
        if (!enabled) {
            // the toggle was turned off while the service was in the foreground: carry on as the stock plain service
            synchronized(lock) {
                if (!inForeground) return
                inForeground = false
            }
            ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
            log { "left the foreground: turned off" }
            return
        }
        try {
            // repeated on every start: a notification posted before notifications were allowed is dropped for good
            ServiceCompat.startForeground(service, NOTIFICATION_ID, buildNotification(service), foregroundType)
            synchronized(lock) {
                if (!inForeground) log { "entered the foreground on $reason, flags=$flags" }
                inForeground = true
            }
        } catch (e: Exception) {
            FileLog.e("$TAG: failed to enter the foreground on $reason", e)
            // a service started with startForegroundService() that never reaches the foreground crashes the process
            service.stopSelf()
        }
    }

    /** `AppStartReceiver`: true when stock should run `startPushService` for this intent, as it does on boot. */
    @JvmStatic
    fun onStartReceiver(intent: Intent): Boolean {
        if (HEARTBEAT_ACTION == intent.action) {
            onHeartbeat()
            return false
        }
        log { "start receiver: ${intent.action}" }
        // after an update the service is gone until the app is opened; MY_PACKAGE_REPLACED may start it from background
        return enabled && Intent.ACTION_MY_PACKAGE_REPLACED == intent.action
    }

    /** `KeepAliveJob.startJob`: the server signalled new updates on the push connection. */
    @JvmStatic
    fun onPushWorkStarted() {
        if (!enabled) return
        synchronized(lock) {
            // keep the CPU up while getDifference runs: stock's KeepAliveJob goes through JobScheduler,
            // which may not start before the kernel's short receive wakelock runs out
            try {
                val wakeLock = pushWakeLock ?: newWakeLock("inugram:push").also { pushWakeLock = it }
                wakeLock.acquire(PUSH_WAKELOCK_TIMEOUT_MS)
            } catch (e: Exception) {
                Log.d(TAG, "wakelock", e)
            }
        }
    }

    /** `KeepAliveJob.finishJob`: updates arrived. Called for every update batch, so it must stay cheap. */
    @JvmStatic
    fun onPushWorkFinished() {
        if (!enabled) return
        synchronized(lock) {
            try {
                pushWakeLock?.takeIf { it.isHeld }?.release()
            } catch (e: Exception) {
                Log.d(TAG, "wakelock", e)
            }
        }
    }

    private fun newWakeLock(tag: String): PowerManager.WakeLock =
        (ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
            .apply { setReferenceCounted(false) }

    /**
     * tgnet, on an account's network thread: a push ping was sent, or its answer arrived. The device is kept awake in
     * between: a sleeping phone is up for a fraction of a second, too short for the answer. Returns whether it is
     * kept awake, which lets tgnet ask a connection after a sleep instead of replacing it outright.
     */
    @JvmStatic
    fun onPushPing(account: Int, answered: Boolean): Boolean {
        try {
            if (!enabled) return false
            synchronized(lock) {
                val wakeLock = pingWakeLock ?: newWakeLock("inugram:ping").also { pingWakeLock = it }
                if (answered) {
                    lastPong[account] = SystemClock.elapsedRealtime()
                    scheduleHeartbeat()
                    pingsPending.remove(account)
                    if (pingsPending.isEmpty() && wakeLock.isHeld) wakeLock.release()
                } else {
                    // once the lock has timed out, an account that never answered must not hold it for the others
                    if (!wakeLock.isHeld) pingsPending.clear()
                    pingsPending.add(account)
                    wakeLock.acquire(PING_WAKELOCK_TIMEOUT_MS)
                }
            }
            return true
        } catch (e: Exception) {
            // nothing may be thrown into tgnet
            Log.d(TAG, "push ping", e)
            return false
        }
    }

    private fun heartbeatIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(HEARTBEAT_ACTION).setClass(context, AppStartReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * tgnet pings the push connection every 3 minutes, but only while the CPU runs: asleep, the connection goes
     * unpinged until the server or a proxy on the way drops it, and nothing tells the phone. The alarm sits where the
     * account pinged longest ago runs out of time, and every answered ping moves it away. Waking up is all it does:
     * tgnet's loop resumes with the device and pings by itself. Called with [lock] held.
     */
    private fun scheduleHeartbeat() {
        val context = ApplicationLoader.applicationContext
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val oldest = lastPong.filter { it != 0L }.minOrNull() ?: SystemClock.elapsedRealtime()
        val at = oldest + HEARTBEAT_INTERVAL_MS
        try {
            // exact, because the system may hold an inexact alarm back for minutes. An app exempt from battery
            // optimisation needs no permission for it; without the exemption Android 12+ throws
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, heartbeatIntent(context))
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, heartbeatIntent(context))
        }
    }

    /**
     * An account silent for the whole interval is forgotten until it answers again, or it would pin the alarm to the
     * past. The alarm itself is always set again, so an unreachable server gets one attempt per interval.
     */
    private fun onHeartbeat() {
        // left over from before the mode was turned off
        if (!enabled) return
        var silent = 0
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            for (a in lastPong.indices) {
                if (lastPong[a] != 0L && now - lastPong[a] >= HEARTBEAT_INTERVAL_MS) {
                    lastPong[a] = 0
                    silent++
                }
            }
            scheduleHeartbeat()
        }
        log { "heartbeat alarm, accounts due: $silent" }
    }

    private fun cancelHeartbeat() {
        synchronized(lock) {
            lastPong.fill(0)
            pingsPending.clear()
            val context = ApplicationLoader.applicationContext
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(heartbeatIntent(context))
        }
    }

    @JvmStatic
    fun onConfigChanged() {
        if (enabled || InuConfig.PERSISTENT_CONNECTION_STOCK_PUSH_FORCED.value) applyStockPush(enabled)
        log { "mode changed: ${state(ApplicationLoader.applicationContext)}" }
        ApplicationLoader.startPushService()
        if (enabled) {
            dropFcmToken()
            synchronized(lock) { scheduleHeartbeat() }
        } else {
            cancelHeartbeat()
            // the token is about to be registered again, nothing left to unregister
            InuConfig.PERSISTENT_CONNECTION_FCM_TOKEN_TO_DROP.value = ""
            // back to FCM: stock asks for a token only at app start. Asked even when a token is still around: a
            // registerDevice that was in flight when the mode turned on has put it back with registeredForPush set
            if (fcm.hasServices()) fcm.onRequestPushToken()
        }
        refreshHint()
    }

    /** `InuHooks.onAppBoot`: every account is loaded, and stock has just scheduled its push registration. */
    @JvmStatic
    fun onAppBoot() {
        if (enabled) dropFcmToken()
    }

    /**
     * Unregisters the FCM token stock registered before the mode turned on or Google Play services went away. With it
     * still on the server, notifications did not reach the connection for about half an hour in one report.
     */
    private fun dropFcmToken() {
        val token = SharedConfig.pushString
        if (!token.isNullOrEmpty() && SharedConfig.pushType == PushListenerController.PUSH_TYPE_FIREBASE) {
            // forgotten up front, or getDifference's registerForPush would bring it straight back
            SharedConfig.pushString = ""
            SharedConfig.pushStringStatus = "__NO_GOOGLE_PLAY_SERVICES__"
            SharedConfig.saveConfig()
            // tgnet keeps sending the old token as device_token in initConnection until told otherwise
            ConnectionsManager.setRegId(null, PushListenerController.PUSH_TYPE_FIREBASE, SharedConfig.pushStringStatus)
            InuConfig.PERSISTENT_CONNECTION_FCM_TOKEN_TO_DROP.value = token
            for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
                val config = UserConfig.getInstance(a)
                if (!config.isClientActivated) continue
                config.registeredForPush = false
                config.saveConfig(false)
            }
        }
        unregisterDroppedToken()
    }

    /**
     * One unregisterDevice for every account, as stock's registerDevice does with other_uids. The token stays in the
     * config until the server answers: tgnet queues the request while offline, but a process death loses it.
     */
    private fun unregisterDroppedToken() {
        val token = InuConfig.PERSISTENT_CONNECTION_FCM_TOKEN_TO_DROP.value
        if (token.isEmpty() || unregisterPending) return
        val accounts = (0 until UserConfig.MAX_ACCOUNT_COUNT).filter { UserConfig.getInstance(it).isClientActivated }
        if (accounts.isEmpty()) {
            // logging out took the registration with the auth key
            InuConfig.PERSISTENT_CONNECTION_FCM_TOKEN_TO_DROP.value = ""
            return
        }
        unregisterPending = true
        val req = TL_account.unregisterDevice().apply {
            token_type = PushListenerController.PUSH_TYPE_FIREBASE
            this.token = token
            for (a in accounts.drop(1)) other_uids.add(UserConfig.getInstance(a).getClientUserId())
        }
        ConnectionsManager.getInstance(accounts[0]).sendRequest(req) { _, error ->
            unregisterPending = false
            // -1000 is tgnet giving up locally (cleanup), not an answer; anything else means the server has spoken
            if (error == null || error.code != -1000) {
                InuConfig.PERSISTENT_CONNECTION_FCM_TOKEN_TO_DROP.value = ""
            }
            log { "FCM token unregistered${error?.let { ": ${it.code} ${it.text}" } ?: ""}" }
        }
    }

    // The foreground service is pointless without stock's keep-alive service and push connection, both off by default.
    // Stock reads both from the first account's notification prefs; off removes the keys and returns to the
    // server-provided defaults, as if the feature was never on.
    private fun applyStockPush(on: Boolean) {
        syncStockPush(MessagesController.getGlobalNotificationsSettings(), on)
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (UserConfig.getInstance(a).isClientActivated) {
                val cm = ConnectionsManager.getInstance(a)
                cm.setPushConnectionEnabled(cm.isPushConnectionEnabled)
            }
        }
    }

    private fun syncStockPush(prefs: SharedPreferences, on: Boolean) {
        InuConfig.PERSISTENT_CONNECTION_STOCK_PUSH_FORCED.value = on
        prefs.edit().apply {
            if (on) putBoolean("pushService", true).putBoolean("pushConnection", true)
            else remove("pushService").remove("pushConnection")
        }.apply()
    }

    @JvmStatic
    fun onResume(activity: LaunchActivity) {
        refreshHint()
        maybeRequestBatteryExemption(activity)
        // brings back a service the system could not start from background, and its notification
        if (enabled) ApplicationLoader.startPushService()
    }

    /**
     * Once per device: the system dialog that lets the app run in the background; without it the service can't come
     * back after an update or a kill. Waits until notifications are allowed (stock asks right after login) and the
     * chat list is on top, focused, with no dialog over it. A refusal leaves the chat-list card behind.
     */
    private fun maybeRequestBatteryExemption(activity: LaunchActivity) {
        if (!batteryRequestWanted() || batteryRequestPending) return
        batteryRequestPending = true
        AndroidUtilities.runOnUIThread({
            batteryRequestPending = false
            if (!batteryRequestWanted() || CrashReporter.isCrashed()) return@runOnUIThread
            val top = LaunchActivity.getLastFragment() ?: return@runOnUIThread
            val fragment = LaunchActivity.getLastFragmentIncludeMainTabs() ?: return@runOnUIThread
            if (fragment !is DialogsActivity || fragment.visibleDialog != null || top.visibleDialog != null) return@runOnUIThread
            if (activity.isFinishing || !activity.hasWindowFocus()) return@runOnUIThread
            requestBatteryExemptionOnce(activity)
        }, BATTERY_REQUEST_DELAY_MS)
    }

    private fun batteryRequestWanted(): Boolean {
        if (!enabled || InuConfig.PERSISTENT_CONNECTION_BATTERY_ASKED.value) return false
        val context = ApplicationLoader.applicationContext
        // a restricted app is the user's explicit choice: only the card speaks to it, never an unprompted settings page
        if (isBackgroundRestricted(context) || isIgnoringBatteryOptimizations(context)) return false
        return isClientReady(context)
    }

    /** The settings page: the mode was just switched on by hand, ask right away. */
    @JvmStatic
    fun requestBatteryExemptionOnce(context: Context) {
        if (!needsBatteryExemption(context)) return
        InuConfig.PERSISTENT_CONNECTION_BATTERY_ASKED.value = true
        log { "asking for the battery exemption" }
        requestBatteryExemption(context)
    }

    fun needsBatteryExemption(context: Context): Boolean =
        isBackgroundRestricted(context) || !isIgnoringBatteryOptimizations(context)

    private fun isClientReady(context: Context): Boolean =
        UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * The card at the top of the chat list, through stock's `ApplicationLoader.onSuggestionFill` slot. Shown once the
     * exemption was asked for and is still missing; stock draws no close button for an app-level suggestion, so it
     * stays until background activity is allowed or the mode is turned off. A tap asks again.
     */
    @JvmStatic
    fun fillHint(output: Array<CharSequence?>): Boolean {
        if (!hintVisible()) return false
        output[0] = LocaleController.getString(R.string.InuPersistentConnectionBatteryHint)
        output[1] = LocaleController.getString(R.string.InuPersistentConnectionBatteryHintInfo)
        return true
    }

    @JvmStatic
    fun onHintClick() {
        requestBatteryExemption(LaunchActivity.instance ?: return)
    }

    private fun refreshHint() {
        AndroidUtilities.runOnUIThread {
            NotificationCenter.getInstance(UserConfig.selectedAccount)
                .postNotificationName(NotificationCenter.newSuggestionsAvailable)
        }
    }

    private fun hintVisible(): Boolean {
        if (!enabled || !InuConfig.PERSISTENT_CONNECTION_BATTERY_ASKED.value) return false
        val context = ApplicationLoader.applicationContext
        return needsBatteryExemption(context) && isClientReady(context)
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

    fun isBackgroundRestricted(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted

    fun batteryStatus(context: Context): String = LocaleController.getString(
        when {
            isBackgroundRestricted(context) -> R.string.InuPersistentConnectionBatteryRestricted
            isIgnoringBatteryOptimizations(context) -> R.string.InuPersistentConnectionBatteryUnrestricted
            else -> R.string.InuPersistentConnectionBatteryOptimized
        }
    )

    /** The system dialog that adds the app to the battery allowlist; the app's settings page when it doesn't apply. */
    fun requestBatteryExemption(context: Context) {
        val dialog = !isBackgroundRestricted(context) && !isIgnoringBatteryOptimizations(context)
        val uri = Uri.fromParts("package", context.packageName, null)
        try {
            context.startActivity(Intent(if (dialog) Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS else Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
        } catch (e: ActivityNotFoundException) {
            // some ROMs ship no exemption dialog; the app's settings page is always there
            if (dialog) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
        }
    }

    // Rare events only, in the stock log so that they reach bug reports; nothing is evaluated while stock logs are off.
    private inline fun log(message: () -> String) {
        if (BuildVars.LOGS_ENABLED) FileLog.d("$TAG: ${message()}")
    }

    private fun state(context: Context): String =
        "mode=${InuConfig.PERSISTENT_CONNECTION_MODE.value} provider=$pushProviderFound enabled=$enabled " +
            "exempt=${isIgnoringBatteryOptimizations(context)} restricted=${isBackgroundRestricted(context)}"

    /** The system settings of the notification's own channel: turning it off there hides the notification for good. */
    fun openNotificationChannelSettings(context: Context) {
        ensureChannel(context)
        try {
            context.startActivity(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
            )
        } catch (e: Exception) {
            Log.d(TAG, "no channel settings", e)
        }
    }

    // Re-created every time on purpose: the system keeps the user's importance choice and only takes the name and
    // description, so an updated description reaches existing installs
    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                LocaleController.getString(R.string.InuPersistentConnectionChannel),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = LocaleController.getString(R.string.InuPersistentConnectionChannelInfo)
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(context: Context): Notification {
        ensureChannel(context)
        val settingsIntent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://settings/inu/persistent-connection-notification"), context, LaunchActivity::class.java)
        val contentIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, settingsIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(NotificationsHelper.smallIconRes())
            .setContentTitle(LocaleController.getString(R.string.InuPersistentConnection))
            .setContentText(LocaleController.getString(R.string.InuPersistentConnectionNotification))
            .setContentIntent(contentIntent)
            .setGroup(CHANNEL_ID)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            // Android 12+ holds a foreground service notification back for ~10 s so that short-lived services
            // don't flash one; this service never stops, and the delay only makes the toggle look broken
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
