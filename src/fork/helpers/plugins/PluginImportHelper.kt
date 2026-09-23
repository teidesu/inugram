package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.ObfuscationDetector
import desu.inugram.core.plugins.PluginManifest
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
 * Installs plugins from outside the plugins page. Parses and validates source, explains
 * unsupported plugins, and asks for approval before installing.
 * Parsing and obfuscation scanning run off the UI thread because both read the full file.
 */
object PluginImportHelper {
    private const val SUFFIX = ".inu.js"

    fun isPluginFileName(name: String): Boolean = name.lowercase().endsWith(SUFFIX)

    fun startImportFromFile(fragment: BaseFragment, file: File) {
        EngineDispatch.scheduler.postRunnable {
            val source = runCatching { file.readText() }.getOrNull()
            if (source == null) {
                failOnUi(fragment, getString(R.string.InuPluginsErrorRead))
                return@postRunnable
            }
            present(fragment, source)
        }
    }

    fun startImport(fragment: BaseFragment, source: String) {
        EngineDispatch.scheduler.postRunnable { present(fragment, source) }
    }

    /** every bail on the plugin queue reports the same way: the dialog belongs to the ui thread */
    private fun failOnUi(fragment: BaseFragment, message: String) {
        AndroidUtilities.runOnUIThread { showError(fragment, message) }
    }

    /** plugin queue only */
    private fun present(fragment: BaseFragment, source: String) {
        val manifest = PluginManifestParser.parseOrNull(source)
        if (manifest == null) {
            failOnUi(fragment, getString(R.string.InuPluginsErrorNoManifest))
            return
        }
        val incompatibility = PluginManager.incompatibility(manifest)
        if (incompatibility != null) {
            failOnUi(fragment, incompatibility)
            return
        }
        val obfuscation = ObfuscationDetector.detect(source)
        val installed = PluginManager.findUpdateTarget(manifest)
        // the file, not `installed.source`: something that rewrote it behind the app leaves the
        // in-memory copy stale, and "already installed" about bytes we are not running is a lie
        if (installed != null && runCatching { installed.file.readText() }.getOrElse { installed.source } == source) {
            AndroidUtilities.runOnUIThread {
                BulletinFactory.of(fragment)
                    .createSimpleBulletin(
                        R.raw.info,
                        formatString(R.string.InuPluginAlreadyInstalled, manifest.name),
                    )
                    .show()
            }
            return
        }
        AndroidUtilities.runOnUIThread {
            val context = fragment.context ?: fragment.parentActivity ?: return@runOnUIThread
            // the per-plugin sheet says what this one asked for; what any plugin could do is read
            // and accepted once, before the first install
            PluginTrustSheet.requireConsent(context, fragment.resourceProvider) {
                fragment.showDialog(
                    PluginInstallSheet(context, fragment, manifest, source, obfuscation, installed?.manifest) { enable ->
                        // re-resolved rather than taken from the sheet: what it was built against is
                        // what to *show*, and the installed set is the ui thread's to answer for
                        val target = PluginManager.findUpdateTarget(manifest)
                        if (target != null) confirmUpdate(fragment, target, manifest, source)
                        else confirmInstall(fragment, manifest, source, enable)
                    }
                )
            }
        }
    }

    private fun confirmInstall(
        fragment: BaseFragment,
        manifest: PluginManifest,
        source: String,
        enable: Boolean,
    ) {
        when (val result = PluginManager.import(source, enable)) {
            is PluginManager.ImportResult.Refused -> showError(fragment, result.reason)
            is PluginManager.ImportResult.Installed -> {
                val text = formatString(R.string.InuPluginInstalled, manifest.name)
                val factory = BulletinFactory.of(fragment)
                if (result.reversible) {
                    factory.createUndoBulletin(text, { PluginManager.remove(result.plugin) }, {}).show()
                } else {
                    factory.createSimpleBulletin(R.raw.info, text).show()
                }
            }
        }
    }

    /** no undo: the source it replaced is gone, and the stores an undo would have to protect are untouched anyway */
    private fun confirmUpdate(
        fragment: BaseFragment,
        installed: Plugin,
        manifest: PluginManifest,
        source: String,
    ) {
        val error = PluginManager.update(installed, source)
        if (error != null) {
            showError(fragment, error)
            return
        }
        BulletinFactory.of(fragment)
            .createSimpleBulletin(R.raw.info, formatString(R.string.InuPluginUpdated, manifest.name))
            .show()
    }

    private fun showError(fragment: BaseFragment, message: String) {
        BulletinFactory.of(fragment).createErrorBulletin(message).show()
    }
}
