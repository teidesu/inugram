package desu.inugram.ui.settings

import android.content.Context
import desu.inugram.InuConfig
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme

/**
 * The consent taken once, before the first plugin is ever installed: the per-plugin sheet lists the
 * permissions that one declared, which is what the sandbox holds it to - but not what granting them
 * amounts to. Answered once and remembered, so it does not turn into a dialog people dismiss
 * without reading.
 */
object PluginTrustSheet {
    /** runs [onAccept] once the warning has been accepted - immediately, if it already was */
    fun requireConsent(context: Context, resourcesProvider: Theme.ResourcesProvider?, onAccept: () -> Unit) {
        if (InuConfig.PLUGINS_TRUSTED.value) {
            onAccept()
            return
        }
        PluginConsentSheet.show(
            context,
            resourcesProvider,
            titleRes = R.string.InuPluginTrustTitle,
            infoRes = R.string.InuPluginTrustInfo,
            features = listOf(
                PluginConsentSheet.Feature(R.drawable.msg_permissions, R.string.InuPluginTrust1Title, R.string.InuPluginTrust1Text),
                PluginConsentSheet.Feature(R.drawable.files_storage, R.string.InuPluginTrust2Title, R.string.InuPluginTrust2Text),
                PluginConsentSheet.Feature(R.drawable.inu_tabler_code, R.string.InuPluginTrust3Title, R.string.InuPluginTrust3Text),
            ),
            warningRes = R.string.InuPluginTrustWarning,
            acceptRes = R.string.InuPluginTrustAccept,
        ) {
            InuConfig.PLUGINS_TRUSTED.value = true
            onAccept()
        }
    }
}
