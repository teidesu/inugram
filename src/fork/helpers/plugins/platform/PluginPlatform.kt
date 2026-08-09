package desu.inugram.helpers.plugins.platform

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import desu.inugram.helpers.plugins.PlatformListener
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.ui.LaunchActivity

object PluginPlatform {
    private const val TAG = "InuPluginPlatform"

    fun listenerFor(): PlatformListener = object : PlatformListener {
        override fun openUrl(url: String) = PluginPlatform.openUrl(url)

        override fun clipboardRead(): String = readClipboard()

        override fun clipboardWrite(text: String) {
            AndroidUtilities.addToClipboard(text)
        }
    }

    /**
     * Deliberately **not** `Browser.openUrl`, which appends the account's `autologin_token` to any
     * url whose host the server put in `autologinDomains` - precisely what the takeover filter
     * strips out of `config`. Routing a plugin's url through it would hand back what the filter took
     * away, to a host the plugin chose.
     *
     * With no ui this is a no-op: android refuses a background activity start.
     */
    private fun openUrl(url: String) {
        AndroidUtilities.runOnUIThread {
            val context = LaunchActivity.instance ?: ApplicationLoader.applicationContext ?: return@runOnUIThread
            try {
                val intent = Intent(Intent.ACTION_VIEW, url.toUri())
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
