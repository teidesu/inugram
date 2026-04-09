package desu.inugram.helpers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.FrameLayout
import androidx.core.content.FileProvider
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.PhotoViewer
import java.io.File
import java.io.FileOutputStream

object PhotoViewerHelper {
    private const val MENU_COPY_PHOTO = 100
    private const val MENU_COPY_FRAME = 101

    @JvmStatic
    fun addMenuItems(menuItem: ActionBarMenuItem) {
        menuItem.addSubItem(MENU_COPY_PHOTO, R.drawable.msg_copy, LocaleController.getString(R.string.InuCopyPhoto)).setColors(0xfffafafa.toInt(), 0xfffafafa.toInt())
        menuItem.addSubItem(MENU_COPY_FRAME, R.drawable.msg_copy, LocaleController.getString(R.string.InuCopyFrame)).setColors(0xfffafafa.toInt(), 0xfffafafa.toInt())
    }

    @JvmStatic
    fun resetMenuItems(menuItem: ActionBarMenuItem) {
        menuItem.hideSubItem(MENU_COPY_PHOTO)
        menuItem.hideSubItem(MENU_COPY_FRAME)
    }

    @JvmStatic
    fun updateMenuItems(menuItem: ActionBarMenuItem, allowShare: Boolean, isVideo: Boolean, isGif: Boolean) {
        if (!allowShare) return
        if (isVideo || isGif) {
            menuItem.showSubItem(MENU_COPY_FRAME)
        } else {
            menuItem.showSubItem(MENU_COPY_PHOTO)
        }
    }

    @JvmStatic
    fun handleMenuClick(id: Int, viewer: PhotoViewer): Boolean {
        when (id) {
            MENU_COPY_PHOTO -> {
                val file = viewer.inu_getCurrentPhotoFile()
                if (file != null) {
                    copyFileUriToClipboard(file, viewer.containerView, R.string.InuPhotoCopied)
                } else {
                    viewer.showDownloadAlert()
                }
            }
            MENU_COPY_FRAME -> {
                var bitmap = viewer.pipCreatePrimaryWindowViewBitmap()
                if (bitmap == null) {
                    bitmap = viewer.centerImage.bitmap
                }
                if (bitmap != null) {
                    copyBitmapToClipboard(bitmap, viewer.containerView)
                }
            }
            else -> return false
        }
        return true
    }

    private fun copyBitmapToClipboard(bitmap: Bitmap, containerView: FrameLayout) {
        try {
            val cacheDir = File(AndroidUtilities.getCacheDir(), "inu_clipboard")
            cacheDir.mkdirs()
            for (old in cacheDir.listFiles() ?: emptyArray()) old.delete()
            val file = File(cacheDir, "frame_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            copyFileUriToClipboard(file, containerView, R.string.InuFrameCopied)
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    private fun copyFileUriToClipboard(file: File, containerView: FrameLayout, bulletinRes: Int) {
        try {
            val context = ApplicationLoader.applicationContext
            val uri = FileProvider.getUriForFile(context, ApplicationLoader.getApplicationId() + ".provider", file)
            val clip = ClipData.newUri(context.contentResolver, "photo", uri)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(clip)
            BulletinFactory.of(containerView, null)
                .createCopyBulletin(LocaleController.getString(bulletinRes))
                .show()
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }
}
