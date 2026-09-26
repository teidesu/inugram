package desu.inugram.helpers.media

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.ActionBarMenuSubItem
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.PhotoViewer
import org.telegram.ui.Stories.recorder.StoryEntry
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

object PhotoViewerHelper {
    private const val MENU_COPY_PHOTO = 100
    private const val MENU_COPY_FRAME = 101
    private const val MENU_FOOTER = 102
    private const val MENU_FOOTER_GAP = 103

    @JvmStatic
    fun ensureEditSourceSnapshot(entry: MediaController.MediaEditState) {
        if (entry.isVideo || entry.inu_editSourcePath?.let { File(it).isFile } == true) return
        val source = entry.path?.let(::File)?.takeIf { it.isFile } ?: return
        val snapshot = File(
            FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE),
            "${SharedConfig.getLastLocalId()}_inu_edit_source.${source.extension.ifEmpty { "jpg" }}",
        )
        try {
            if (AndroidUtilities.copyFile(source, snapshot)) {
                entry.inu_editSourcePath = snapshot.absolutePath
            } else {
                snapshot.delete()
            }
        } catch (e: Exception) {
            snapshot.delete()
            FileLog.e(e)
        }
    }

    @JvmStatic
    fun getEditSourcePath(entry: MediaController.MediaEditState): String =
        entry.filterPath?.takeIf { it.isNotEmpty() }
            ?: entry.inu_editSourcePath?.takeIf { File(it).isFile }
            ?: entry.path

    @JvmStatic
    fun clearHdrMode(viewer: PhotoViewer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        setHdrMode(viewer, false)
    }

    @JvmStatic
    fun updateHdrMode(viewer: PhotoViewer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        if (
            !InuConfig.HDR_IMAGES.value ||
            viewer.centerImageIsVideo ||
            viewer.centerImageIsLivePhoto ||
            viewer.parentActivity?.display?.isHdr != true
        ) {
            clearHdrMode(viewer)
            return
        }

        val bitmap = viewer.centerImage.bitmap
        val colorSpace = bitmap?.colorSpace
        val enabled = bitmap != null && !bitmap.isRecycled && (
            bitmap.hasGainmap() ||
                colorSpace == ColorSpace.get(ColorSpace.Named.BT2020_PQ) ||
                colorSpace == ColorSpace.get(ColorSpace.Named.BT2020_HLG) ||
                colorSpace?.name?.contains("PQ", ignoreCase = true) == true ||
                colorSpace?.name?.contains("2084", ignoreCase = true) == true ||
                colorSpace?.name?.contains("HLG", ignoreCase = true) == true
        )
        setHdrMode(viewer, enabled)
    }

    private fun setHdrMode(viewer: PhotoViewer, enabled: Boolean) {
        val params = viewer.windowLayoutParams ?: return
        if (enabled) {
            if (params.colorMode == ActivityInfo.COLOR_MODE_HDR) return
            params.setColorMode(ActivityInfo.COLOR_MODE_HDR)
        } else {
            if (params.colorMode != ActivityInfo.COLOR_MODE_HDR) return
            params.setColorMode(ActivityInfo.COLOR_MODE_DEFAULT)
        }

        val window = viewer.windowView ?: return
        if (window.parent == null) return
        val windowManager = viewer.parentActivity?.windowManager ?: return
        try {
            windowManager.updateViewLayout(window, params)
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    @JvmStatic
    fun gifAsVideo(msg: MessageObject?): Boolean = msg != null && msg.isNewGif && InuConfig.GIF_SEEKBAR.value

    @JvmStatic
    fun setFooter(menuItem: ActionBarMenuItem, msg: MessageObject?) {
        if (msg == null) return applyFooter(menuItem, null)
        val dc = msg.document?.dc_id?.takeIf { it != 0 }
            ?: (MessageObject.getMedia(msg) as? TLRPC.TL_messageMediaPhoto)?.photo?.dc_id?.takeIf { it != 0 }
            ?: return applyFooter(menuItem, null)
        val file = runCatching {
            FileLoader.getInstance(msg.currentAccount).getPathToMessage(msg.messageOwner)
        }.getOrNull()
        applyFooter(menuItem, dc, detectPlatform(file, isPfp = false))
    }

    @JvmStatic
    fun getAvatarSubtitle(location: ImageLocation?, dialogId: Long, account: Int): CharSequence? {
        if (location == null) return null
        val userFull = if (dialogId > 0) MessagesController.getInstance(account).getUserFull(dialogId) else null
        val photo = location.photo ?: findUserPhotoById(userFull, location.photoId) ?: return null
        val date = LocaleController.formatDateTime(photo.date.toLong(), true)
        val markRes = when (photo.id) {
            (userFull?.fallback_photo as? TLRPC.TL_photo)?.id -> R.string.InuPublicPhotoMark
            (userFull?.personal_photo as? TLRPC.TL_photo)?.id -> R.string.InuPersonalPhotoMark
            else -> return date
        }
        return "$date • ${LocaleController.getString(markRes)}"
    }

    private fun findUserPhotoById(userFull: TLRPC.UserFull?, photoId: Long): TLRPC.Photo? {
        if (userFull == null || photoId == 0L) return null
        return listOf(userFull.profile_photo, userFull.personal_photo, userFull.fallback_photo)
            .firstOrNull { it is TLRPC.TL_photo && it.id == photoId }
    }

    @JvmStatic
    fun setFooter(menuItem: ActionBarMenuItem, location: ImageLocation?, account: Int) {
        val dc = location?.dc_id?.takeIf { it != 0 } ?: return applyFooter(menuItem, null)
        val hasVideo = location.photo?.video_sizes?.isEmpty() == false
        val platform = if (hasVideo) null else {
            val file = runCatching {
                FileLoader.getInstance(account).getPathToAttach(
                    PhotoViewer.getFileLocation(location), PhotoViewer.getFileLocationExt(location), true,
                )
            }.getOrNull()
            detectPlatform(file, isPfp = true)
        }
        applyFooter(menuItem, dc, platform)
    }

    private fun applyFooter(menuItem: ActionBarMenuItem, dc: Int, platform: String?) =
        applyFooter(menuItem, if (platform != null) "DC $dc • $platform" else "DC $dc")

    private fun applyFooter(menuItem: ActionBarMenuItem, text: String?) {
        val gap = menuItem.getSubItem(MENU_FOOTER_GAP) ?: return
        val item = menuItem.getSubItem(MENU_FOOTER) as? ActionBarMenuSubItem ?: return
        val visible = text != null
        gap.visibility = if (visible) View.VISIBLE else View.GONE
        item.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) item.setTextAndIcon(text, 0)
    }

    // thanks https://github.com/kukuruzka165/materialgram/blob/64dd8f3c43b9f7c169473fe464eec8cc5b097ede/Telegram/SourceFiles/media/view/media_view_overlay_widget.cpp#L8097
    private val PHOTO_HEADERS: List<Pair<ByteArray, String>> = listOf(
        "FFD8FFE000104A46494600010100000100010000FFDB004300090607" to "iOS",
        "FFD8FFE000104A46494600010101004800480000FFE201D84943435F50524F46494C45" to "Android",
        "FFD8FFE000104A464946000101010078" to "Desktop Windows",
        "FFD8FFE000104A464946000101010060" to "Desktop Windows, 2",
        "FFD8FFE000104A46494600010101004800480000FFE201DB" to "Desktop Windows, 3",
        "FFD8FFE000104A46494600010101004800480000FFDB00" to "Desktop Linux, Unigram, Generic",
        "FFD8FFE000104A46494600010101004800480000FFE202284943435F50524F46494C450001010000021800000000" to "Android, old",
        "FFD8FFE000104A4649460001010101" to "Desktop macOS",
        "FFD8FFE000104A46494600010101009000900000FFE201DB" to "Desktop macOS, 2",
        "FFD8FFE000104A46494600010100000100010000FFDB004300090606" to "macOS",
        "FFD8FFE000104A46494600010100000100010000FFDB004300080606" to "macOS, 2",
        "FFD8FFE000104A46494600010101009000900000FFDB004300" to "macOS, 3",
        "FFD8FFE000104A46494600010101004800480000FFE201F04943435F50524F46494C45" to "Desktop Linux",
        "FFD8FFE000104A46494600010101004800480000FFE202284943435F50524F46494C45000101000002186170706C" to "iOS, Share menu",
        "FFD8FFE000104A46494600010101004800480000FFE202184943435F50524F46494C45" to "Android, 2",
        "FFD8FFE000104A46494600010101004800480000FFE202404943435F50524F46494C45" to "Android, 3",
        "FFD8FFE000104A46494600010100004800480000FFC000110801" to "iOS, 2",
    ).map { hexToBytes(it.first) to it.second }
    private val PHOTO_HEADER_MAX = PHOTO_HEADERS.maxOf { it.first.size }
    private val platformCache = ConcurrentHashMap<String, String>()
    private val PLATFORM_NONE = "\u0000"

    private fun hexToBytes(hex: String) = ByteArray(hex.length / 2) { i ->
        ((Character.digit(hex[2 * i], 16) shl 4) or Character.digit(hex[2 * i + 1], 16)).toByte()
    }

    private fun detectPlatform(file: File?, isPfp: Boolean): String? {
        if (file == null || !file.isFile) return null
        val key = file.absolutePath
        val cached = platformCache[key]
        val platform = if (cached != null) cached.takeIf { it !== PLATFORM_NONE } else {
            val match = readPlatformHeader(file)
            if (platformCache.size >= 5000) platformCache.clear()
            platformCache[key] = match ?: PLATFORM_NONE
            match
        }
        return if (isPfp && platform == "Desktop Linux, Unigram, Generic") "iOS, Generic" else platform
    }

    private fun readPlatformHeader(file: File): String? {
        val buf = ByteArray(PHOTO_HEADER_MAX)
        val read = runCatching { FileInputStream(file).use { it.read(buf) } }.getOrDefault(-1)
        if (read <= 0) return null
        return PHOTO_HEADERS.firstOrNull { (h, _) -> h.size <= read && h.indices.all { buf[it] == h[it] } }?.second
    }

    @JvmStatic
    fun addMenuItems(menuItem: ActionBarMenuItem) {
        menuItem.addSubItem(MENU_COPY_PHOTO, R.drawable.msg_copy, LocaleController.getString(R.string.InuCopyPhoto))
            .setColors(0xfffafafa.toInt(), 0xfffafafa.toInt())
        menuItem.addSubItem(MENU_COPY_FRAME, R.drawable.msg_copy, LocaleController.getString(R.string.InuCopyFrame))
            .setColors(0xfffafafa.toInt(), 0xfffafafa.toInt())
    }

    @JvmStatic
    fun addFooter(menuItem: ActionBarMenuItem) {
        menuItem.addColoredGap(MENU_FOOTER_GAP).setColor(0xff181818.toInt())
        menuItem.addSubItem(MENU_FOOTER, 0, "").also {
            it.setColors(0xfffafafa.toInt(), 0xfffafafa.toInt())
            it.textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            it.textView.includeFontPadding = false
            it.setPadding(AndroidUtilities.dp(12f), 0, AndroidUtilities.dp(12f), 0)
            it.setItemHeight(32)
            it.isClickable = false
            it.isFocusable = false
        }
        applyFooter(menuItem, null)
    }

    @JvmStatic
    fun resetMenuItems(menuItem: ActionBarMenuItem) {
        menuItem.hideSubItem(MENU_COPY_PHOTO)
        menuItem.hideSubItem(MENU_COPY_FRAME)
        applyFooter(menuItem, null)
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
                val bitmap = viewer.pipCreatePrimaryWindowViewBitmap() ?: viewer.centerImage.bitmap
                if (bitmap != null) copyBitmapToClipboard(bitmap, viewer.containerView)
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
        if (InuUtils.copyFileUriToClipboard(file)) {
            BulletinFactory.of(containerView, null)
                .createCopyBulletin(LocaleController.getString(bulletinRes))
                .show()
        }
    }

    // applyCurrentEditMode bakes the crop into entry.imagePath from centerImage's bitmap; when
    // that bitmap is unavailable the bake used to fail silently *after* makeCrop committed
    // entry.cropState, so the viewer kept rendering the crop while the un-cropped original got
    // sent (still photos are sent from imagePath alone — cropState is video-only at send time).
    // These decode the source from disk instead, like stock PhotoEntry.rebuildPhoto does.
    // (part of bugfix__photo-crop-not-applied-on-send)
    @JvmStatic
    fun loadEditSourceBitmap(entry: MediaController.MediaEditState, orientation: IntArray): Bitmap? {
        val path = getEditSourcePath(entry)
        if (path.isNullOrEmpty() || !File(path).exists()) return null
        val exif = AndroidUtilities.getImageOrientation(path)
        orientation[0] = exif.first
        orientation[1] = exif.second
        return StoryEntry.getScaledBitmap(
            { opts -> BitmapFactory.decodeFile(path, opts) },
            AndroidUtilities.getPhotoSize(true), AndroidUtilities.getPhotoSize(true), false, true,
        )
    }

    @JvmStatic
    fun rebakeCroppedBitmap(entry: MediaController.MediaEditState): Bitmap? {
        val cropState = entry.cropState ?: return null
        val orientation = IntArray(2)
        val bitmap = loadEditSourceBitmap(entry, orientation) ?: return null
        val cropped = PhotoViewer.createCroppedBitmap(bitmap, cropState, orientation, true)
        bitmap.recycle()
        return cropped
    }

    @JvmStatic
    fun renderHighQualityCrop(entry: MediaController.MediaEditState): Bitmap? {
        val cropState = entry.cropState ?: return null
        if (entry.paintPath != null) return null
        val path = getEditSourcePath(entry)
        if (path.isNullOrEmpty() || !File(path).exists()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val w0 = bounds.outWidth
        val h0 = bounds.outHeight
        val target = AndroidUtilities.getPhotoSize(true)
        if (w0 <= 0 || h0 <= 0 || maxOf(w0, h0) <= target) return null

        val exif = AndroidUtilities.getImageOrientation(path)
        val orientation = intArrayOf(exif.first, exif.second)

        val tr = (cropState.transformRotation + orientation[0]) % 360
        val swap = tr == 90 || tr == 270
        val rotW = if (swap) h0 else w0
        val rotH = if (swap) w0 else h0
        val cropOutLong = maxOf(rotW * cropState.cropPw, rotH * cropState.cropPh)
        if (cropOutLong <= 0f) return null

        var sample = 1
        while (cropOutLong / (sample * 2) >= target) sample *= 2
        val maxBytes = minOf(96L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4)
        while ((w0.toLong() / sample) * (h0.toLong() / sample) * 4L > maxBytes) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = try {
            BitmapFactory.decodeFile(path, opts)
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null
        return try {
            PhotoViewer.createCroppedBitmap(bitmap, cropState, orientation, true)
        } finally {
            bitmap.recycle()
        }
    }
}
