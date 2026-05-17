package desu.inugram

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import desu.inugram.helpers.CloudSettingsHelper
import desu.inugram.helpers.FontHelper
import desu.inugram.helpers.LoginHelper
import desu.inugram.helpers.MainTabsHelper
import desu.inugram.helpers.MapsHelper
import desu.inugram.helpers.MonetHelper
import desu.inugram.helpers.PasscodeHelper
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
        FontHelper.init(context)
        if (InuConfig.FONT_MODE.value == 2 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            FontHelper.installAsDefault()
        }
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
    fun onDeepLink(activity: LaunchActivity, intent: Intent?): Boolean {
        return PasscodeHelper.tryHandleDeepLink(activity, intent)
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
    fun applyDefaultFont(paint: android.text.TextPaint?) {
        if (paint == null || paint.typeface != null) return
        if (InuConfig.FONT_MODE.value != 2) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        FontHelper.resolve(400, false)?.let { paint.typeface = it }
    }

    @JvmStatic
    fun applyDefaultFont(view: android.widget.TextView?) {
        if (view == null) return
        if (InuConfig.FONT_MODE.value != 2) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        FontHelper.resolve(400, false)?.let { view.typeface = it }
    }

    /**
     * Stock creates many `chat_*Paint`/`dialogs_*Paint`/`profile_*Paint` `TextPaint`s in
     * [org.telegram.ui.ActionBar.Theme] without an explicit typeface. Sweep them after creation
     * so message bubble text, dialog cell previews, profile bio, etc. pick up the custom font.
     */
    @JvmStatic
    fun onThemePaintsCreated() {
        if (InuConfig.FONT_MODE.value != 2) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val tf = FontHelper.resolve(400, false) ?: return
        try {
            for (field in org.telegram.ui.ActionBar.Theme::class.java.declaredFields) {
                val name = field.name
                if (!name.endsWith("Paint")) continue
                if (!(name.startsWith("chat_") || name.startsWith("dialogs_") || name.startsWith("profile_"))) continue
                val value = field.get(null) ?: continue
                when (value) {
                    is android.text.TextPaint -> if (value.typeface == null) value.typeface = tf
                    is Array<*> -> for (item in value) {
                        if (item is android.text.TextPaint && item.typeface == null) item.typeface = tf
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun onGetTypeface(cache: Hashtable<String, Typeface>, assetPath: String): Typeface? {
        val mode = InuConfig.FONT_MODE.value
        if (mode == 0) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val key = "inu:m$mode:$assetPath"
        cache[key]?.let { return it }
        val (weight, italic) = when (assetPath) {
            AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM -> 500 to false
            AndroidUtilities.TYPEFACE_ROBOTO_EXTRA_BOLD -> 800 to false
            AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM_ITALIC -> 500 to true
            "fonts/ritalic.ttf" -> 400 to true
            "fonts/rcondensedbold.ttf" -> 700 to false
            else -> return null
        }
        val tf = when (mode) {
            1 -> Typeface.create(null as Typeface?, weight, italic)
            2 -> FontHelper.resolve(weight, italic)
            else -> null
        } ?: return null
        cache[key] = tf
        return tf
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
