package desu.inugram.helpers

import android.content.Context
import android.os.SystemClock
import desu.inugram.InuConfig
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLog
import org.telegram.messenger.PushListenerController
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.unifiedpush.android.connector.UnifiedPush
import java.net.URLEncoder

object UnifiedPushHelper {
    @JvmField var lastReceivedNotification: Long = 0L
    @JvmField var numOfReceivedNotifications: Long = 0L

    @JvmStatic
    fun isEnabled(): Boolean = InuConfig.UNIFIED_PUSH_ENABLED.value

    @JvmStatic
    fun getGateway(): String {
        val gw = InuConfig.UNIFIED_PUSH_GATEWAY.value
        return if (gw.isBlank()) "https://p2p.belloworld.it/" else gw
    }

    @JvmStatic
    fun hasDistributors(context: Context): Boolean {
        return UnifiedPush.getDistributors(context).isNotEmpty()
    }

    @JvmStatic
    fun getDistributors(context: Context): List<String> {
        return UnifiedPush.getDistributors(context)
    }

    @JvmStatic
    fun getActiveDistributor(context: Context): String? {
        return try {
            UnifiedPush.getAckDistributor(context)
        } catch (_: Throwable) {
            null
        }
    }

    @JvmStatic
    fun register(context: Context) {
        Utilities.globalQueue.postRunnable {
            try {
                SharedConfig.pushStringGetTimeStart = SystemClock.elapsedRealtime()
                SharedConfig.saveConfig()
                if (UnifiedPush.getAckDistributor(context) == null) {
                    val distributors = UnifiedPush.getDistributors(context)
                    if (distributors.isNotEmpty()) {
                        UnifiedPush.saveDistributor(context, distributors[0])
                    }
                }
                UnifiedPush.register(context, "default", "Telegram Simple Push")
            } catch (e: Throwable) {
                FileLog.e(e)
            }
        }
    }

    @JvmStatic
    fun unregister(context: Context) {
        try {
            UnifiedPush.unregisterApp(context, "default")
        } catch (_: Throwable) {}
    }

    @JvmStatic
    fun onNewEndpoint(endpoint: String, context: Context) {
        Utilities.globalQueue.postRunnable {
            SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime()
            val distributor = try { UnifiedPush.getAckDistributor(context) } catch (_: Throwable) { null }
            val url = if (distributor?.contains("ntfy") == true) {
                endpoint
            } else {
                try {
                    getGateway() + URLEncoder.encode(endpoint, "UTF-8")
                } catch (_: Exception) {
                    endpoint
                }
            }
            PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_SIMPLE, url)
        }
    }

    @JvmStatic
    fun onPushReceived() {
        lastReceivedNotification = SystemClock.elapsedRealtime()
        numOfReceivedNotifications++
        val latch = java.util.concurrent.CountDownLatch(1)
        org.telegram.messenger.AndroidUtilities.runOnUIThread {
            if (BuildVars.LOGS_ENABLED) FileLog.d("UP PRE INIT APP")
            ApplicationLoader.postInitApplication()
            if (BuildVars.LOGS_ENABLED) FileLog.d("UP POST INIT APP")
            Utilities.stageQueue.postRunnable {
                if (BuildVars.LOGS_ENABLED) FileLog.d("UP START PROCESSING")
                for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
                    if (UserConfig.getInstance(a).isClientActivated) {
                        ConnectionsManager.onInternalPushReceived(a)
                        ConnectionsManager.getInstance(a).resumeNetworkMaybe()
                    }
                }
                latch.countDown()
            }
        }
        Utilities.globalQueue.postRunnable {
            try { latch.await() } catch (_: Throwable) {}
        }
    }

    @JvmStatic
    fun onRegistrationFailed() {
        SharedConfig.pushStringStatus = "__UNIFIEDPUSH_FAILED__"
        Utilities.globalQueue.postRunnable {
            SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime()
            PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_SIMPLE, null)
        }
    }

    @JvmStatic
    fun onUnregistered() {
        SharedConfig.pushStringStatus = "__UNIFIEDPUSH_FAILED__"
        Utilities.globalQueue.postRunnable {
            SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime()
            PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_SIMPLE, null)
        }
    }

    @JvmField
    val PROVIDER = object : PushListenerController.IPushListenerServiceProvider {
        override fun hasServices(): Boolean = hasDistributors(ApplicationLoader.applicationContext)
        override fun getLogTitle(): String = "UnifiedPush"
        override fun getPushType(): Int = PushListenerController.PUSH_TYPE_SIMPLE
        override fun onRequestPushToken() {
            if (!isEnabled()) {
                unregister(ApplicationLoader.applicationContext)
                return
            }
            val currentPushString = SharedConfig.pushString
            if (!currentPushString.isNullOrEmpty()) {
                if (BuildVars.DEBUG_PRIVATE_VERSION && BuildVars.LOGS_ENABLED) {
                    FileLog.d("UnifiedPush endpoint = $currentPushString")
                }
            } else {
                if (BuildVars.LOGS_ENABLED) FileLog.d("No UnifiedPush string found")
            }
            register(ApplicationLoader.applicationContext)
        }
    }
}
