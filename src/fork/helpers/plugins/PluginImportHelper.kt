package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.ObfuscationDetector
import desu.inugram.core.plugins.PluginManifestParser
import desu.inugram.ui.settings.PluginInstallSheet
import desu.inugram.ui.settings.PluginTrustSheet
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController.formatString
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory
import java.io.File

/**
 * The one way a plugin gets installed from outside the plugins page: parses and vets the source,
 * refuses what this app cannot run with a reason the user can read, and otherwise asks first.
 *
 * Parsing and the obfuscation scan both walk the whole file, so both happen off the ui thread.
 */
object PluginImportHelper {
    private const val SUFFIX = ".inu.js"

    fun isPluginFileName(name: String): Boolean = name.lowercase().endsWith(SUFFIX)

    fun startImportFromFile(fragment: BaseFragment, file: File, displayName: String) {
        Utilities.globalQueue.postRunnable {
            val source = runCatching { file.readText() }.getOrNull()
            if (source == null) {
                AndroidUtilities.runOnUIThread { showError(fragment, getString(R.string.InuPluginsErrorRead)) }
                return@postRunnable
            }
            present(fragment, displayName, source)
        }
    }

    /** [fileName] only suggests the name on disk; identity is minted at install */
    fun startImport(fragment: BaseFragment, fileName: String, source: String) {
        Utilities.globalQueue.postRunnable { present(fragment, fileName, source) }
    }

    /** globalQueue only */
    private fun present(fragment: BaseFragment, fileName: String, source: String) {
        val manifest = PluginManifestParser.parseOrNull(source)
        if (manifest == null) {
            AndroidUtilities.runOnUIThread { showError(fragment, getString(R.string.InuPluginsErrorNoManifest)) }
            return
        }
        val incompatibility = PluginManager.incompatibility(manifest)
        if (incompatibility != null) {
            AndroidUtilities.runOnUIThread { showError(fragment, incompatibility) }
            return
        }
        val obfuscation = ObfuscationDetector.detect(source)
        AndroidUtilities.runOnUIThread {
            val context = fragment.context ?: fragment.parentActivity ?: return@runOnUIThread
            // the per-plugin sheet says what this one asked for; what any plugin could do is read
            // and accepted once, before the first install
            PluginTrustSheet.requireConsent(context, fragment.resourceProvider) {
                fragment.showDialog(
                    PluginInstallSheet(context, fragment, manifest, source, obfuscation) { enable ->
                        when (val result = PluginManager.import(fileName, source, enable)) {
                            is PluginManager.ImportResult.Refused -> showError(fragment, result.reason)
                            is PluginManager.ImportResult.Installed -> BulletinFactory.of(fragment)
                                .createUndoBulletin(
                                    formatString(R.string.InuPluginInstalled, manifest.name),
                                    { PluginManager.remove(result.plugin) },
                                    {},
                                )
                                .show()
                        }
                    }
                )
            }
        }
    }

    private fun showError(fragment: BaseFragment, message: String) {
        BulletinFactory.of(fragment).createErrorBulletin(message).show()
    }
}
