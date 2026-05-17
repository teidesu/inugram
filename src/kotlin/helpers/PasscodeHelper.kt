package desu.inugram.helpers

import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.core.content.edit
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessagesController
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.ui.LaunchActivity
import org.telegram.ui.PasscodeActivity
import java.nio.charset.StandardCharsets

object PasscodeHelper {
    const val PANIC_ACCOUNT = Int.MAX_VALUE

    private val prefs by lazy {
        ApplicationLoader.applicationContext.getSharedPreferences("inugram_passcode", Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun check(activity: Any?, passcode: String): Boolean {
        if (hasPasscodeForAccount(PANIC_ACCOUNT) && verify(PANIC_ACCOUNT, passcode)) {
            for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
                if (UserConfig.getInstance(a).isClientActivated && isAccountAllowPanic(a)) {
                    MessagesController.getInstance(a).performLogout(1)
                }
            }
            return false
        }
        if (activity is LaunchActivity) {
            for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
                if (!UserConfig.getInstance(a).isClientActivated) continue
                if (hasPasscodeForAccount(a) && verify(a, passcode)) {
                    activity.switchToAccount(a, true)
                    return true
                }
            }
        }
        return SharedConfig.checkPasscode(passcode)
    }

    private fun verify(account: Int, passcode: String): Boolean = try {
        val saltB64 = prefs.getString("passcodeSalt$account", "") ?: ""
        val salt = if (saltB64.isNotEmpty()) Base64.decode(saltB64, Base64.DEFAULT) else ByteArray(0)
        prefs.getString("passcodeHash$account", "") == hash(passcode, salt)
    } catch (e: Exception) {
        FileLog.e(e); false
    }

    private fun hash(passcode: String, salt: ByteArray): String {
        val pwd = passcode.toByteArray(StandardCharsets.UTF_8)
        val bytes = ByteArray(32 + pwd.size)
        System.arraycopy(salt, 0, bytes, 0, 16)
        System.arraycopy(pwd, 0, bytes, 16, pwd.size)
        System.arraycopy(salt, 0, bytes, pwd.size + 16, 16)
        return Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.size.toLong()))
    }

    @JvmStatic
    fun handleSetupSubmit(activity: PasscodeActivity, passcode: String): Boolean {
        if (activity.inu_account == -1) return false
        setForAccount(passcode, activity.inu_account)
        AndroidUtilities.hideKeyboard(activity.fragmentView)
        activity.finishFragment()
        return true
    }

    @JvmStatic
    fun setForAccount(passcode: String, account: Int) {
        try {
            val salt = ByteArray(16).also { Utilities.random.nextBytes(it) }
            prefs.edit {
                putString("passcodeHash$account", hash(passcode, salt))
                putString("passcodeSalt$account", Base64.encodeToString(salt, Base64.DEFAULT))
            }
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    @JvmStatic
    fun clearAll() {
        prefs.edit { clear() }
    }

    @JvmStatic
    fun removeForAccount(account: Int) {
        prefs.edit {
            remove("passcodeHash$account")
            remove("passcodeSalt$account")
            remove("hide$account")
            remove("allowPanic$account")
        }
    }

    @JvmStatic
    fun hasPasscodeForAccount(account: Int): Boolean =
        prefs.contains("passcodeHash$account") && prefs.contains("passcodeSalt$account")

    @JvmStatic
    fun isAccountHidden(account: Int): Boolean =
        hasPasscodeForAccount(account) && prefs.getBoolean("hide$account", false)

    fun setAccountHidden(account: Int, hide: Boolean) {
        prefs.edit { putBoolean("hide$account", hide) }
    }

    fun isAccountAllowPanic(account: Int): Boolean = prefs.getBoolean("allowPanic$account", true)

    fun setAccountAllowPanic(account: Int, allow: Boolean) {
        prefs.edit { putBoolean("allowPanic$account", allow) }
    }

    fun hasPanicCode(): Boolean = hasPasscodeForAccount(PANIC_ACCOUNT)

    fun isSettingsHidden(): Boolean = prefs.getBoolean("hideSettings", false)

    fun setSettingsHidden(hide: Boolean) {
        prefs.edit { putBoolean("hideSettings", hide) }
    }

    fun getSettingsKey(): String {
        prefs.getString("settingsHash", null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val bytes = ByteArray(8).also { Utilities.random.nextBytes(it) }
        val hash = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        prefs.edit { putString("settingsHash", hash) }
        return hash
    }

    @JvmStatic
    fun tryHandleDeepLink(activity: LaunchActivity, intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (AndroidUtilities.needShowPasscode(true) || SharedConfig.isWaitingForPasscodeEnter) return false
        val (host, key) = when (uri.scheme) {
            "tg" -> (uri.host ?: return false) to (uri.pathSegments.firstOrNull() ?: uri.getQueryParameter("key"))
            "http", "https" -> {
                if (uri.host != "t.me" && uri.host != "telegram.me") return false
                val segs = uri.pathSegments
                if (segs.size < 2) return false
                segs[0] to segs[1]
            }

            else -> return false
        }
        if (host != "inusettings" || key.isNullOrEmpty() || key != getSettingsKey()) return false
        activity.actionBarLayout.presentFragment(desu.inugram.ui.InuPasscodeSettingsActivity())
        return true
    }
}
