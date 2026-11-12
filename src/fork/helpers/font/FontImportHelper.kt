package desu.inugram.helpers.font

import desu.inugram.helpers.chat.ChatHelper
import desu.inugram.ui.settings.fonts.FontInstallSheet
import desu.inugram.ui.settings.fonts.FontsSettingsActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
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

    fun startImportFromFile(
        fragment: BaseFragment,
        file: File,
        displayName: String,
        mimeType: String?,
        album: List<MessageObject> = emptyList(),
    ) {
        FileLog.d("$TAG: startImportFromFile: path=${file.absolutePath} exists=${file.exists()} size=${file.length()} name=$displayName album=${album.size}")
        if (album.isEmpty()) {
            showInstallSheet(fragment, file, displayName, mimeType, emptyList())
            return
        }
        downloadAlbum(fragment, album) { albumFiles ->
            showInstallSheet(fragment, file, displayName, mimeType, albumFiles.orEmpty())
        }
    }

    private fun downloadAlbum(fragment: BaseFragment, album: List<MessageObject>, onDone: (List<File>?) -> Unit) {
        val account = fragment.currentAccount
        val collectFiles = {
            album.map { ChatHelper.getMessageFile(account, it) }.takeIf { files -> files.all { it != null } }?.filterNotNull()
        }
        val pending = album.filter { ChatHelper.getMessageFile(account, it) == null }
        val activity = fragment.parentActivity
        if (pending.isEmpty() || activity == null) {
            onDone(collectFiles())
            return
        }
        val names = pending.mapTo(HashSet()) { FileLoader.getAttachFileName(it.document) }
        val center = NotificationCenter.getInstance(account)
        val progress = AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER)
        val observer = object : NotificationCenter.NotificationCenterDelegate {
            fun detach() {
                center.removeObserver(this, NotificationCenter.fileLoaded)
                center.removeObserver(this, NotificationCenter.fileLoadFailed)
            }

            override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
                if (!names.remove(args.getOrNull(0) as? String) || names.isNotEmpty()) return
                detach()
                progress.dismiss()
                onDone(collectFiles())
            }
        }
        progress.setOnCancelListener { observer.detach() }
        center.addObserver(observer, NotificationCenter.fileLoaded)
        center.addObserver(observer, NotificationCenter.fileLoadFailed)
        progress.showDelayed(300)
        for (msg in pending) {
            FileLoader.getInstance(account).loadFile(msg.document, msg, FileLoader.PRIORITY_HIGH, 0)
        }
    }

    private fun showInstallSheet(
        fragment: BaseFragment,
        file: File,
        displayName: String,
        mimeType: String?,
        albumFiles: List<File>,
    ) {
        FontLibrary.importQueue.postRunnable {
            val info = FontLibrary.inspectFont(file)
            FileLog.d("$TAG: showInstallSheet: inspect result: family=${info?.family} faces=${info?.faceCount}")
            val loadable = info != null && FontLibrary.isLoadableBySystem(file)
            val family = info?.family?.takeIf { loadable }
            val albumInfos = albumFiles.filter { it != file }.map { other ->
                FontLibrary.inspectFont(other)?.takeIf {
                    it.family.equals(family, ignoreCase = true) && FontLibrary.isLoadableBySystem(other)
                }
            }
            val isFamilyAlbum = family != null && albumInfos.isNotEmpty() && albumInfos.all { it != null }
            val files = if (isFamilyAlbum) albumFiles else listOf(file)
            val sheetInfo = if (isFamilyAlbum) {
                FontLibrary.FontInfo(family, info!!.faces + albumInfos.flatMap { it!!.faces })
            } else info
            AndroidUtilities.runOnUIThread {
                val ctx = fragment.context ?: fragment.parentActivity ?: run {
                    FileLog.d("$TAG: showInstallSheet: fragment has no context, dropping")
                    return@runOnUIThread
                }
                if (sheetInfo == null || !loadable) {
                    BulletinFactory.of(fragment).createErrorBulletin(
                        LocaleController.getString(
                            if (sheetInfo == null) R.string.InuFontImportFailed else R.string.InuFontUnsupported
                        )
                    ).show()
                    return@runOnUIThread
                }
                FontInstallSheet(
                    ctx,
                    file,
                    sheetInfo,
                    displayName,
                    onInstall = { installFonts(fragment, files) },
                    onOpen = { openExternally(fragment, file, displayName, mimeType) },
                ).show()
            }
        }
    }

    private fun installFonts(fragment: BaseFragment, files: List<File>) {
        FileLog.d("$TAG: installFonts: ${files.joinToString { "${it.absolutePath} (${it.length()})" }}")
        FontLibrary.importQueue.postRunnable {
            val result = FontLibrary.importFromFiles(files)
            FileLog.d("$TAG: installFonts: added=${result.addedFaces} rejected=${result.rejectedBySystem}")
            AndroidUtilities.runOnUIThread {
                if (result.addedFaces > 0) {
                    FontLibrary.invalidateEditorRoster()
                    BulletinFactory.of(fragment).createSimpleBulletin(
                        R.raw.contact_check,
                        LocaleController.getString(R.string.InuFontInstalled),
                        LocaleController.getString(R.string.ViewAction),
                    ) { fragment.presentFragment(FontsSettingsActivity()) }.show()
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

    private fun openExternally(fragment: BaseFragment, file: File, displayName: String, mimeType: String?) {
        val activity = fragment.parentActivity ?: return
        if (!AndroidUtilities.openForView(file, displayName, mimeType, activity, fragment.resourceProvider, false)) {
            BulletinFactory.of(fragment).createErrorBulletin(
                LocaleController.getString(R.string.InuFontOpenInFailed)
            ).show()
        }
    }
}
