package desu.inugram

import android.content.Context
import android.content.Intent
import android.os.Build
import desu.inugram.helpers.CrashReporter
import desu.inugram.helpers.PersistentConnectionHelper
import desu.inugram.helpers.LoginHelper
import desu.inugram.helpers.ProxyVpnHelper
import desu.inugram.helpers.ShortcutHelper
import desu.inugram.helpers.SplashScreenHelper
import desu.inugram.helpers.UrlCleanerHelper
import desu.inugram.helpers.cloud.CloudSettingsHelper
import desu.inugram.helpers.dialogs.DrawerHelper
import desu.inugram.helpers.dialogs.LinkedChatsHelper
import desu.inugram.helpers.font.FontHelper
import desu.inugram.helpers.maps.MapsHelper
import desu.inugram.helpers.media.MediaSendDebugHelper
// #if PLUGINS
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.platform.PluginNotifications
import desu.inugram.ui.settings.PluginReposActivity
// #endif
import desu.inugram.helpers.security.PasscodeHelper
import desu.inugram.helpers.theme.MonetHelper
import desu.inugram.helpers.theme.NonIslandHelper
import desu.inugram.helpers.update.ApkInstaller
import desu.inugram.helpers.update.UpdateHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLObject
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.AnimatedEmojiDrawable
import org.telegram.ui.Components.AnimatedFloat
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ChatActivityEnterView
import org.telegram.ui.Components.GestureDetector2
import org.telegram.ui.Components.GestureDetectorFixDoubleTap
import org.telegram.ui.LaunchActivity
import org.telegram.ui.LauncherIconController


object InuHooks {
    @JvmStatic
    fun preInit(context: Context) {
        CrashReporter.install()
        InuConfig.load(context)
    }

    /**
     * Runs once `ApplicationLoader` has armed its main-thread handler: until it does,
     * [AndroidUtilities.runOnUIThread] drops what it is given instead of posting it.
     */
    @JvmStatic
    fun init(context: Context) {
        FontHelper.init(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            FontHelper.installGlobal()
        }
        syncDoubleTapDelay()
        syncAnimationSpeed()
        syncChatInputRowHeight()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MonetHelper.registerOverlayChangeReceiver(context)
            MonetHelper.registerThemeReloadReceiver(context)
        }
        UpdateHelper.clearPendingIfInstalled()
        PersistentConnectionHelper.onProcessStart(context)
        ApkInstaller.dismissInstalledNotification()
        CloudSettingsHelper.attachAutoSyncListener()
        ProxyVpnHelper.init(context)
        // #if PLUGINS
        PluginManager.init(context)
        // #endif
        Utilities.globalQueue.postRunnable { UrlCleanerHelper.preload() }
        if (BuildConfig.INU_BUILD_TYPE == "debug") SearchRegistry.verifySlugs()
    }

    @JvmStatic
    fun onAppBoot() {
        PersistentConnectionHelper.onAppBoot()
        // #if PLUGINS
        PluginManager.onAppBoot()
        // #endif
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
        if (message.messageOwner != null) UpdateHelper.onNewMessage(message.messageOwner, account)
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
    fun handleIntent(activity: LaunchActivity, intent: Intent?, restore: Boolean): Boolean {
        return PasscodeHelper.tryHandleDeepLink(activity, intent)
            || SearchRegistry.tryHandleDeepLink(activity, intent)
            // #if PLUGINS
            || PluginReposActivity.tryHandleDeepLink(activity, intent)
            || PluginNotifications.tryHandleClick(intent, restore)
            // #endif
            || tryHandleFunDeepLink(activity, intent)
            || ShortcutHelper.handleAction(activity, intent)
    }

    private fun tryHandleFunDeepLink(activity: LaunchActivity, intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != "tg") return false
        val host = uri.host ?: uri.schemeSpecificPart?.removePrefix("//")?.substringBefore('/')
        val (emojiId, text) = when (host) {
            "nya" -> 6323275213672810256L to "meow~"
            "woof" -> 5776133475794950313L to "woof :3"
            else -> return false
        }
        val account = UserConfig.selectedAccount
        if (!UserConfig.getInstance(account).isClientActivated) return true
        AnimatedEmojiDrawable.getDocumentFetcher(account).fetchDocument(emojiId) { document ->
            val fragment = activity.actionBarLayout?.lastFragment ?: return@fetchDocument
            BulletinFactory.of(fragment).createEmojiBulletin(document, text).show()
        }
        return true
    }

    @JvmStatic
    fun onAuthSuccess(account: Int) {
        PasscodeHelper.removeForAccount(account)
    }

    @JvmStatic
    fun onResume(launchActivity: LaunchActivity) {
        SplashScreenHelper.sync(launchActivity)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MonetHelper.refreshMonetThemeIfChanged()
        }
        CrashReporter.maybeShowReportSheet(launchActivity)
        UpdateHelper.maybeShowWhatsNew(launchActivity)
        PersistentConnectionHelper.onResume(launchActivity)
        ProxyVpnHelper.reconcile()
        DrawerHelper.refreshUpdateState()
        MediaSendDebugHelper.startWatchingCache()
        // #if PLUGINS
        PluginManager.onAppInteractive()
        // #endif
    }

    @JvmStatic
    fun onFragmentResume(fragment: BaseFragment) {
        LinkedChatsHelper.onFragmentResume(fragment)
    }

    @JvmStatic
    fun onFragmentPause(fragment: BaseFragment) {
        LinkedChatsHelper.onFragmentPause(fragment)
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
