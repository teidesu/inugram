package desu.inugram

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import desu.inugram.helpers.CloudSettingsHelper
import desu.inugram.helpers.LoginHelper
import desu.inugram.helpers.MainTabsHelper
import desu.inugram.helpers.MapsHelper
import desu.inugram.helpers.MonetHelper
import desu.inugram.helpers.UpdateHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLObject
import org.telegram.ui.Components.AnimatedFloat
import org.telegram.ui.Components.GestureDetector2
import org.telegram.ui.Components.GestureDetectorFixDoubleTap
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.ContactsActivity
import org.telegram.ui.DialogsActivity
import org.telegram.ui.LaunchActivity
import org.telegram.ui.LauncherIconController
import org.telegram.ui.ProfileActivity
import org.telegram.ui.SettingsActivity
import java.util.Hashtable


object InuHooks {
    fun init(context: Context) {
        InuConfig.load(context)
        syncDoubleTapDelay()
        syncAnimationSpeed()
        UpdateHelper.clearPendingIfInstalled()
        CloudSettingsHelper.attachAutoSyncListener()
    }

    @JvmStatic
    fun onMessagesControllerCreated(messagesController: MessagesController) {
        MapsHelper.syncMapProvider(messagesController)
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
        UpdateHelper.onUpdate(update, account)
    }

    @JvmStatic
    fun onResume(launchActivity: LaunchActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MonetHelper.refreshMonetThemeIfChanged()
        }
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

    @JvmStatic
    fun onGetTypeface(cache: Hashtable<String, Typeface>, assetPath: String): Typeface? {
        if (!InuConfig.USE_SYSTEM_FONT.value) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val key = "inu:$assetPath"
        return cache.getOrElse(key) {
            when (assetPath) {
                AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM -> Typeface.create(null, 500, false)
                AndroidUtilities.TYPEFACE_ROBOTO_EXTRA_BOLD -> Typeface.create(null, 800, false)
                AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM_ITALIC -> Typeface.create(null, 500, true)
                "fonts/ritalic.ttf" -> Typeface.create(null, 400, true)
                "fonts/rcondensedbold.ttf" -> Typeface.create(null, 700, false)
                else -> null
            }?.let { cache.put(key, it) }
        }
    }

    @JvmStatic
    fun isLocalOnlyString(key: String?): Boolean {
        if (key == null) return false
        return key.startsWith("Inu") ||
            key == "AppName" ||
            key == "AppNameBeta" ||
            key == "AppUpdate" ||
            key == "AppUpdateBeta"
    }

    @JvmStatic
    fun addDialogsActivityOptions(instance: DialogsActivity, io: ItemOptions): Unit {
        val bottomTabsHidden = MainTabsHelper.isHidden;

        if (bottomTabsHidden) {
            io.add(R.drawable.left_status_profile, getString(R.string.MyProfile)) {
                val args = Bundle()
                args.putLong("user_id", UserConfig.getInstance(instance.currentAccount).getClientUserId())
                args.putBoolean("my_profile", true)
                instance.presentFragment(ProfileActivity(args))
            }
        }

        if (bottomTabsHidden || MainTabsHelper.isContactsTabHidden) {
            io.add(R.drawable.msg_contacts, getString(R.string.Contacts)) {
                val args = Bundle()
                args.putBoolean("needPhonebook", true)
                instance.presentFragment(ContactsActivity(args))
            }
        }

        if (bottomTabsHidden) {
            io.add(R.drawable.msg_settings_old, getString(R.string.Settings)) {
                instance.presentFragment(SettingsActivity())
            }
        }
    }
}
