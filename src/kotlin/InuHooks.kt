package desu.inugram

import android.content.Context
import android.content.Intent
import android.os.Build
import desu.inugram.helpers.CrashReporter
import desu.inugram.helpers.LoginHelper
import desu.inugram.helpers.ProxyVpnHelper
import desu.inugram.helpers.ShortcutHelper
import desu.inugram.helpers.UrlCleanerHelper
import desu.inugram.helpers.cloud.CloudSettingsHelper
import desu.inugram.helpers.font.FontHelper
import desu.inugram.helpers.maps.MapsHelper
import desu.inugram.helpers.security.PasscodeHelper
import desu.inugram.helpers.theme.MonetHelper
import desu.inugram.helpers.theme.NonIslandHelper
import desu.inugram.helpers.update.ApkInstaller
import desu.inugram.helpers.update.UpdateHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLObject
import org.telegram.ui.Components.AnimatedFloat
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ChatActivityEnterView
import org.telegram.ui.Components.GestureDetector2
import org.telegram.ui.Components.GestureDetectorFixDoubleTap
import org.telegram.ui.LaunchActivity
import org.telegram.ui.LauncherIconController


object InuHooks {
    @JvmStatic
    fun init(context: Context) {
        CrashReporter.install()
        InuConfig.load(context)
        FontHelper.init(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            FontHelper.installGlobal()
        }
        syncDoubleTapDelay()
        syncAnimationSpeed()
        syncChatInputRowHeight()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MonetHelper.registerOverlayChangeReceiver(context)
        }
        UpdateHelper.clearPendingIfInstalled()
        ApkInstaller.dismissInstalledNotification()
        CloudSettingsHelper.attachAutoSyncListener()
        ProxyVpnHelper.init(context)
        Utilities.globalQueue.postRunnable { UrlCleanerHelper.preload() }
    }

    @JvmStatic
    fun onMessagesControllerCreated(messagesController: MessagesController, account: Int) {
        MapsHelper.syncMapProvider(messagesController)
        AndroidUtilities.runOnUIThread {
            NotificationCenter.getInstance(account).addObserver(
                NotificationCenter.NotificationCenterDelegate { id, acc, args ->
                    if (id != NotificationCenter.didReceiveNewMessages) return@NotificationCenterDelegate
                    @Suppress("UNCHECKED_CAST")
                    val messages = args[1] as? ArrayList<MessageObject> ?: return@NotificationCenterDelegate
                    for (msg in messages) onNewMessage(msg, acc)
                },
                NotificationCenter.didReceiveNewMessages,
            )
        }
    }

    fun onNewMessage(message: MessageObject, account: Int) {
        if (message.messageOwner != null) UpdateHelper.onNewMessage(message.messageOwner)
    }

    @JvmStatic
    fun syncAnimationSpeed() {
        try {
            Class.forName("android.animation.ValueAnimator")
                .getMethod("setDurationScale", Float::class.javaPrimitiveType)
                .invoke(null, 1f / InuConfig.ANIMATION_SPEED.value)
        } catch (_: Throwable) {
        }
        AnimatedFloat.inu_multiplier = InuConfig.ANIMATION_SPEED.value
    }

    @JvmStatic
    fun onUpdate(update: TLObject?, account: Int) {
        LoginHelper.onUpdate(update, account)
    }

    @JvmStatic
    fun handleIntent(activity: LaunchActivity, intent: Intent?): Boolean {
        return PasscodeHelper.tryHandleDeepLink(activity, intent)
            || SearchRegistry.tryHandleDeepLink(activity, intent)
            || tryHandleFunDeepLink(activity, intent)
            || ShortcutHelper.handleAction(activity, intent)
    }

    private fun tryHandleFunDeepLink(activity: LaunchActivity, intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != "tg") return false
        val host = uri.host ?: uri.schemeSpecificPart?.removePrefix("//")?.substringBefore('/')
        val (icon, text) = when (host) {
            "nya" -> R.raw.msg_emoji_cat to "meow~"
            "woof" -> R.raw.msg_emoji_activities to "woof :3"
            else -> return false
        }
        val fragment = activity.actionBarLayout?.lastFragment ?: return false
        BulletinFactory.of(fragment).createSimpleBulletin(icon, text).show()
        return true
    }

    @JvmStatic
    fun onAuthSuccess(account: Int) {
        PasscodeHelper.removeForAccount(account)
    }

    @JvmStatic
    fun onResume(launchActivity: LaunchActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MonetHelper.refreshMonetThemeIfChanged()
        }
        CrashReporter.maybeShowReportSheet(launchActivity)
        ProxyVpnHelper.reconcile()
    }

    @JvmStatic
    fun syncDoubleTapDelay() {
        val delay = InuConfig.DOUBLE_TAP_DELAY.value
        GestureDetectorFixDoubleTap.GestureDetectorCompatImplBase.DOUBLE_TAP_TIMEOUT = delay
        GestureDetector2.DOUBLE_TAP_TIMEOUT = delay
    }

    @JvmStatic
    fun syncChatInputRowHeight() {
        val height = NonIslandHelper.chatInputRowHeight()
        val delta = (height - 44) / 2
        ChatActivityEnterView.DEFAULT_HEIGHT = height
        ChatActivityEnterView.inu_FIELD_PADDING_TOP = 9 + delta
        ChatActivityEnterView.inu_FIELD_PADDING_BOTTOM = 10 + delta
        ChatActivityEnterView.inu_ICON_PADDING = 7.5f + delta
    }

    @JvmStatic
    fun getCurrentAppIconLicense(): CharSequence {
        val current = LauncherIconController.LauncherIcon.entries
            .firstOrNull { LauncherIconController.isEnabled(it) }
        val resId = when (current) {
            LauncherIconController.LauncherIcon.DEFAULT -> R.string.InuAppIconLicenseInugram
            else -> R.string.InuAppIconLicenseTelegram
        }
        return AndroidUtilities.replaceTags(getString(resId))
    }
}
