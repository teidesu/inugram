package desu.inugram.helpers

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaController
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.BasePermissionsActivity
import org.telegram.ui.LaunchActivity
import org.telegram.ui.Components.BulletinFactory

object StickerDownloadHelper {
    const val MENU_OPTION_SAVE_TO_DOWNLOADS = 520

    // stock ContentPreviewViewer action codes for the removal options of the sticker/emoji preview menus
    private val REMOVAL_ACTIONS = setOf(4, 5, 8)

    @JvmStatic
    fun addPreviewMenuItems(
        document: TLRPC.Document?,
        items: MutableList<CharSequence>,
        icons: MutableList<Int>,
        actions: MutableList<Int>,
    ) {
        if (document == null) return
        val index = actions.indexOfFirst { it in REMOVAL_ACTIONS }.takeIf { it >= 0 } ?: items.size
        items.add(index, LocaleController.getString(R.string.SaveToDownloads))
        icons.add(index, R.drawable.msg_download)
        actions.add(index, MENU_OPTION_SAVE_TO_DOWNLOADS)
    }

    @JvmStatic
    fun processPreviewMenuOption(
        activity: Activity?,
        accountId: Int,
        document: TLRPC.Document?,
        resourcesProvider: Theme.ResourcesProvider?,
        action: Int,
    ): Boolean {
        if (action != MENU_OPTION_SAVE_TO_DOWNLOADS) return false
        if (activity != null && document != null) {
            saveStickerToDownloads(activity, accountId, document, null, resourcesProvider)
        }
        return true
    }

    @JvmStatic
    fun saveStickerToDownloads(
        activity: Activity,
        accountId: Int,
        document: TLRPC.Document,
        parentObject: Any?,
        resourcesProvider: Theme.ResourcesProvider?,
    ) {
        if (!ensureStoragePermission(activity)) return
        resolveFileName(accountId, document) { name ->
            withDownloadedFile(accountId, document, parentObject) { file ->
                MediaController.saveFile(file.absolutePath, activity, 2, name, document.mime_type) {
                    createBulletinFactory(resourcesProvider)
                        ?.createDownloadBulletin(BulletinFactory.FileType.UNKNOWN, 1, resourcesProvider)
                        ?.show()
                }
            }
        }
    }

    private fun createBulletinFactory(resourcesProvider: Theme.ResourcesProvider?): BulletinFactory? {
        val fragment = LaunchActivity.getLastFragment() ?: return null
        val sheetContainer = (fragment.visibleDialog as? BottomSheet)?.container
        if (sheetContainer != null) return BulletinFactory.of(sheetContainer, resourcesProvider)
        return BulletinFactory.of(fragment)
    }

    @JvmStatic
    fun ensureStoragePermission(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT >= 23 &&
            (Build.VERSION.SDK_INT <= 28 || BuildVars.NO_SCOPED_STORAGE) &&
            activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                BasePermissionsActivity.REQUEST_CODE_EXTERNAL_STORAGE,
            )
            return false
        }
        return true
    }

    private fun resolveFileName(accountId: Int, document: TLRPC.Document, callback: (String) -> Unit) {
        val inputSet = MessageObject.getInputStickerSet(document)
        if (inputSet == null) {
            callback(buildFileName(null, document))
            return
        }
        val controller = MediaDataController.getInstance(accountId)
        val cached = controller.getStickerSet(inputSet, true)
        if (cached != null) {
            callback(buildFileName(cached, document))
            return
        }
        controller.getStickerSet(inputSet, null, false) { set -> callback(buildFileName(set, document)) }
    }

    private fun buildFileName(stickerSet: TLRPC.TL_messages_stickerSet?, document: TLRPC.Document): String {
        val extension = getFileExtension(document)
        val slug = stickerSet?.set?.short_name?.takeIf { it.isNotEmpty() }
        val index = stickerSet?.documents?.indexOfFirst { it.id == document.id } ?: -1
        if (slug == null || index < 0) return "sticker_${document.id}.$extension"
        val width = stickerSet.documents.size.toString().length
        val position = (index + 1).toString().padStart(width, '0')
        return "${slug}_${position}_${document.id}.$extension"
    }

    private fun getFileExtension(document: TLRPC.Document): String = when (document.mime_type) {
        "application/x-tgsticker" -> "tgs"
        "video/webm" -> "webm"
        "image/webp" -> "webp"
        else -> FileLoader.getDocumentFileName(document).substringAfterLast('.', "").ifEmpty { "dat" }
    }

    private fun withDownloadedFile(
        accountId: Int,
        document: TLRPC.Document,
        parentObject: Any?,
        callback: (File) -> Unit,
    ) {
        val loader = FileLoader.getInstance(accountId)
        val existing = findExistingFile(loader, document)
        if (existing != null) {
            callback(existing)
            return
        }
        val attachName = FileLoader.getAttachFileName(document)
        val center = NotificationCenter.getInstance(accountId)
        val observer = object : NotificationCenter.NotificationCenterDelegate {
            override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
                if (args.getOrNull(0) as? String != attachName) return
                center.removeObserver(this, NotificationCenter.fileLoaded)
                center.removeObserver(this, NotificationCenter.fileLoadFailed)
                if (id != NotificationCenter.fileLoaded) return
                findExistingFile(loader, document)?.let(callback)
            }
        }
        center.addObserver(observer, NotificationCenter.fileLoaded)
        center.addObserver(observer, NotificationCenter.fileLoadFailed)
        loader.loadFile(document, parentObject, FileLoader.PRIORITY_HIGH, 1)
    }

    private fun findExistingFile(loader: FileLoader, document: TLRPC.Document): File? =
        sequenceOf(false, true)
            .mapNotNull { loader.getPathToAttach(document, it) }
            .firstOrNull { it.exists() }
}
