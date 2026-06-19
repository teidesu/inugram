package desu.inugram

import android.content.Context
import android.content.Intent
import android.os.Build
import desu.inugram.helpers.CrashReporter
import desu.inugram.helpers.LoginHelper
import desu.inugram.helpers.UrlCleanerHelper
import desu.inugram.helpers.cloud.CloudSettingsHelper
import desu.inugram.helpers.font.FontConfig
import desu.inugram.helpers.font.FontHelper
import desu.inugram.helpers.maps.MapsHelper
import desu.inugram.helpers.security.PasscodeHelper
import desu.inugram.helpers.theme.MonetHelper
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
        UpdateHelper.clearPendingIfInstalled()
        ApkInstaller.dismissInstalledNotification()
        CloudSettingsHelper.attachAutoSyncListener()
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
    fun onDeepLink(activity: LaunchActivity, intent: Intent?): Boolean {
        return PasscodeHelper.tryHandleDeepLink(activity, intent)
            || SearchRegistry.tryHandleDeepLink(activity, intent)
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
    }

    @JvmStatic
    fun syncDoubleTapDelay() {
        val delay = InuConfig.DOUBLE_TAP_DELAY.value
        GestureDetectorFixDoubleTap.GestureDetectorCompatImplBase.DOUBLE_TAP_TIMEOUT = delay
        GestureDetector2.DOUBLE_TAP_TIMEOUT = delay
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
