package desu.inugram.ui.settings

import desu.inugram.helpers.SharePicker
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginStore
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory

private const val PLUGIN_MIME = "text/javascript"

/** [plugin]'s source written out to the cache and handed to stock's share sheet */
internal fun sharePlugin(fragment: BaseFragment, plugin: Plugin) {
    Utilities.globalQueue.postRunnable {
        val exported = runCatching { PluginStore.exportTo(AndroidUtilities.getCacheDir(), plugin) }
        AndroidUtilities.runOnUIThread {
            exported.fold(
                onSuccess = { SharePicker.showShareSheet(fragment, it, PLUGIN_MIME) },
                onFailure = {
                    BulletinFactory.of(fragment)
                        .createErrorBulletin(it.message ?: it.javaClass.simpleName)
                        .show()
                },
            )
        }
    }
}
