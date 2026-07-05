package desu.inugram.helpers.font

import desu.inugram.ui.settings.fonts.FontInstallSheet
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory
import java.io.File

object FontImportHelper {
    private const val TAG = "InuFonts"
    private val EXTENSIONS = listOf(".ttf", ".otf", ".ttc")

    fun isFontFileName(name: String): Boolean {
        val lower = name.lowercase()
        return EXTENSIONS.any { lower.endsWith(it) }
    }

    fun startImportFromFile(fragment: BaseFragment, message: MessageObject, file: File, displayName: String) {
        FileLog.d("$TAG: startImportFromFile: path=${file.absolutePath} exists=${file.exists()} size=${file.length()} name=$displayName")
        Utilities.globalQueue.postRunnable {
            val info = FontLibrary.inspectFont(file)
            FileLog.d("$TAG: startImportFromFile: inspect result: family=${info?.family} faces=${info?.faceCount}")
            val loadable = info != null && FontLibrary.isLoadableBySystem(file)
            AndroidUtilities.runOnUIThread {
                val ctx = fragment.context ?: fragment.parentActivity ?: run {
                    FileLog.d("$TAG: startImportFromFile: fragment has no context, dropping")
                    return@runOnUIThread
                }
                if (info == null || !loadable) {
                    BulletinFactory.of(fragment).createErrorBulletin(
                        LocaleController.getString(
                            if (info == null) R.string.InuFontImportFailed else R.string.InuFontUnsupported
                        )
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
        FileLog.d("$TAG: installFont: path=${file.absolutePath} exists=${file.exists()} size=${file.length()}")
        Utilities.globalQueue.postRunnable {
            val result = FontLibrary.importFromFile(file)
            FileLog.d("$TAG: installFont: added=${result.addedFaces} rejected=${result.rejectedBySystem}")
            AndroidUtilities.runOnUIThread {
                if (result.addedFaces > 0) {
                    FontLibrary.invalidateEditorRoster()
                    BulletinFactory.of(fragment).createSimpleBulletin(
                        R.raw.contact_check,
                        LocaleController.getString(R.string.InuFontInstalled)
                    ).show()
                } else {
                    BulletinFactory.of(fragment).createErrorBulletin(
                        LocaleController.getString(
                            if (result.rejectedBySystem > 0) R.string.InuFontUnsupported else R.string.InuFontImportFailed
                        )
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
