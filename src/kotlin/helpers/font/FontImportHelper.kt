package desu.inugram.helpers.font

import desu.inugram.ui.settings.fonts.FontInstallSheet
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory
import java.io.File

object FontImportHelper {
    private val EXTENSIONS = listOf(".ttf", ".otf", ".ttc")

    fun isFontFileName(name: String): Boolean {
        val lower = name.lowercase()
        return EXTENSIONS.any { lower.endsWith(it) }
    }

    fun startImportFromFile(fragment: BaseFragment, message: MessageObject, file: File, displayName: String) {
        Utilities.globalQueue.postRunnable {
            val info = FontLibrary.inspectFont(file)
            AndroidUtilities.runOnUIThread {
                val ctx = fragment.context ?: fragment.parentActivity ?: return@runOnUIThread
                if (info == null) {
                    BulletinFactory.of(fragment).createErrorBulletin(
                        LocaleController.getString(R.string.InuFontImportFailed)
                    ).show()
                    return@runOnUIThread
                }
                FontInstallSheet(
                    ctx,
                    file,
                    info,
                    displayName,
                    onInstall = { installFont(fragment, file) },
                    onOpen = { openExternally(fragment, message) },
                ).show()
            }
        }
    }

    private fun installFont(fragment: BaseFragment, file: File) {
        Utilities.globalQueue.postRunnable {
            val added = FontLibrary.importFromFile(file)
            AndroidUtilities.runOnUIThread {
                if (added > 0) {
                    FontLibrary.invalidateEditorRoster()
                    BulletinFactory.of(fragment).createSimpleBulletin(
                        R.raw.contact_check,
                        LocaleController.getString(R.string.InuFontInstalled)
                    ).show()
                } else {
                    BulletinFactory.of(fragment).createErrorBulletin(
                        LocaleController.getString(R.string.InuFontImportFailed)
                    ).show()
                }
            }
        }
    }

    private fun openExternally(fragment: BaseFragment, message: MessageObject) {
        val activity = fragment.parentActivity ?: return
        if (!AndroidUtilities.openForView(message, activity, fragment.resourceProvider, false)) {
            BulletinFactory.of(fragment).createErrorBulletin(
                LocaleController.getString(R.string.InuFontOpenInFailed)
            ).show()
        }
    }
}
