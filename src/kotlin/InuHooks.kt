package desu.inugram

import android.content.Context
import android.os.Build
import android.os.Bundle
import desu.inugram.helpers.MainTabsHelper
import desu.inugram.helpers.MonetHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
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


object InuHooks {
    fun init(context: Context) {
        InuConfig.load(context)
        syncDoubleTapDelay()
        syncAnimationSpeed()
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
