package desu.inugram.helpers.plugins.platform

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import desu.inugram.helpers.plugins.PlatformListener
import org.telegram.messenger.browser.Browser
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.ui.LaunchActivity

object PluginPlatform {
    private const val TAG = "InuPluginPlatform"
    private const val FORMAT_DATE = 0
    private const val FORMAT_TIME = 1
    private const val FORMAT_DATE_TIME = 2
    private const val FORMAT_RELATIVE_DATE = 3
    private const val FORMAT_NUMBER = 4
    private const val FORMAT_COMPACT_NUMBER = 5
    private const val FORMAT_FILE_SIZE = 6
    private const val FORMAT_DURATION = 7

    fun listenerFor(): PlatformListener = object : PlatformListener {
        override fun format(op: Int, value: Long): String = when (op) {
            FORMAT_DATE -> LocaleController.formatDate(value)
            FORMAT_TIME -> LocaleController.getInstance().formatterDay.format(java.util.Date(value * 1000))
            FORMAT_DATE_TIME -> LocaleController.formatDateTime(value, true)
            FORMAT_RELATIVE_DATE -> LocaleController.formatDateChat(value, true)
            FORMAT_NUMBER -> LocaleController.formatNumber(value, ' ')
            FORMAT_COMPACT_NUMBER -> LocaleController.formatNumberWithMillion(value, ' ')
            FORMAT_FILE_SIZE -> AndroidUtilities.formatFileSize(value)
            FORMAT_DURATION -> LocaleController.formatShortDuration(value.toInt())
            else -> error("unknown format op $op")
        }

        override fun openUrl(url: String) = PluginPlatform.openUrl(url)

        override fun clipboardRead(): String = readClipboard()

        override fun clipboardWrite(text: String) {
            AndroidUtilities.addToClipboard(text)
        }
    }

    /**
     * External urls deliberately avoid `Browser.openUrl`, which appends the account's `autologin_token` to any
     * url whose host the server put in `autologinDomains` - precisely what the takeover filter
     * strips out of `config`. Routing a plugin's url through it would hand back what the filter took
     * away, to a host the plugin chose. Telegram links use narrower entry points that never add it.
     *
     * With no ui this is a no-op: android refuses a background activity start.
     */
    private fun openUrl(url: String) {
        AndroidUtilities.runOnUIThread {
            val context = LaunchActivity.instance ?: ApplicationLoader.applicationContext ?: return@runOnUIThread
            try {
                val uri = url.toUri()
                val host = uri.host?.trimEnd('.')?.lowercase()
                if (uri.scheme.equals("tg", ignoreCase = true) || host == "t.me") {
                    Browser.openAsInternalIntent(context, url)
                    return@runOnUIThread
                }
                if (host == "telegram.org" || host?.endsWith(".telegram.org") == true) {
                    Browser.openInTelegramBrowser(context, url, null)
                    return@runOnUIThread
                }
                val intent = Intent(Intent.ACTION_VIEW, uri)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "openUrl failed", e)
            }
        }
    }

    /**
     * answers synchronously off globalQueue: `getPrimaryClip` is a binder call and touches no view.
     *
     * **The clip's own text, never `coerceToText`**, which dereferences a `content://` uri through
     * *this app's* permissions and would turn "read what the user copied" into "read any provider
     * the app can reach". A clip carrying only a uri is therefore "".
     */
    private fun readClipboard(): String = try {
        val manager = ApplicationLoader.applicationContext
            ?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ""
        val clip = manager.primaryClip ?: return ""
        (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).text }
            ?.toString()
            .orEmpty()
    } catch (e: Exception) {
        Log.e(TAG, "clipboard read failed", e)
        ""
    }
}
