package desu.inugram.helpers.plugins.platform

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import desu.inugram.helpers.plugins.PluginLog
import desu.inugram.helpers.plugins.PlatformListener
import org.telegram.messenger.browser.Browser
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.ui.LaunchActivity

object PluginPlatform {
    private const val FORMAT_DATE = 0
    private const val FORMAT_TIME = 1
    private const val FORMAT_DATE_TIME = 2
    private const val FORMAT_RELATIVE_DATE = 3
    private const val FORMAT_NUMBER = 4
    private const val FORMAT_COMPACT_NUMBER = 5
    private const val FORMAT_FILE_SIZE = 6
    private const val FORMAT_DURATION = 7

    fun listenerFor(log: PluginLog): PlatformListener = object : PlatformListener {
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

        override fun openUrl(url: String) = PluginPlatform.openUrl(url, log)

        override fun clipboardRead(): String = readClipboard(log)

        override fun clipboardWrite(text: String) {
            AndroidUtilities.addToClipboard(text)
        }
    }

    /**
     * not `Browser.openUrl`, which adds `autologin_token` for `autologinDomains` and so would bypass the
     * takeover filter hiding those domains. No-op without ui: android blocks background activity starts.
     */
    private fun openUrl(url: String, log: PluginLog) {
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
                log.e("platform", "openUrl failed", e)
            }
        }
    }

    /**
     * `getPrimaryClip` is a Binder call with no view access. Not `coerceToText`: it could dereference a
     * `content://` uri with the app's permissions. Uri-only clips return "".
     */
    private fun readClipboard(log: PluginLog): String = try {
        val manager = ApplicationLoader.applicationContext
            ?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ""
        val clip = manager.primaryClip ?: return ""
        (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).text }
            ?.toString()
            .orEmpty()
    } catch (e: Exception) {
        log.e("platform", "clipboard read failed", e)
        ""
    }
}
