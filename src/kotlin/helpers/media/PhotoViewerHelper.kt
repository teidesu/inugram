package desu.inugram.helpers.media

import android.annotation.SuppressLint
import android.graphics.Bitmap
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
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.ActionBarMenuSubItem
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.PhotoViewer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("StaticFieldLeak")
object PhotoViewerHelper {
    private const val MENU_COPY_PHOTO = 100
    private const val MENU_COPY_FRAME = 101
    private const val MENU_FOOTER = 102

    private var footerGap: View? = null
    private var footerItem: ActionBarMenuSubItem? = null

    @JvmStatic
    fun gifAsVideo(msg: MessageObject?): Boolean = msg != null && msg.isNewGif && InuConfig.GIF_SEEKBAR.value

    @JvmStatic
    fun setFooter(msg: MessageObject?) {
        if (msg == null) return applyFooter(null)
        val dc = msg.document?.dc_id?.takeIf { it != 0 }
            ?: (MessageObject.getMedia(msg) as? TLRPC.TL_messageMediaPhoto)?.photo?.dc_id?.takeIf { it != 0 }
            ?: return applyFooter(null)
        val file = runCatching {
            FileLoader.getInstance(msg.currentAccount).getPathToMessage(msg.messageOwner)
        }.getOrNull()
        applyFooter(dc, detectPlatform(file, isPfp = false))
    }

    @JvmStatic
    fun setFooter(location: ImageLocation?, account: Int) {
        val dc = location?.dc_id?.takeIf { it != 0 } ?: return applyFooter(null)
        val hasVideo = location.photo?.video_sizes?.isEmpty() == false
        val platform = if (hasVideo) null else {
            val file = runCatching {
                FileLoader.getInstance(account).getPathToAttach(
                    PhotoViewer.getFileLocation(location), PhotoViewer.getFileLocationExt(location), true,
                )
            }.getOrNull()
            detectPlatform(file, isPfp = true)
        }
        applyFooter(dc, platform)
    }

    private fun applyFooter(dc: Int, platform: String?) =
        applyFooter(if (platform != null) "DC $dc • $platform" else "DC $dc")

    private fun applyFooter(text: String?) {
        val gap = footerGap ?: return
        val item = footerItem ?: return
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
        footerGap = menuItem.addColoredGap().also { it.setColor(0xff181818.toInt()) }
        footerItem = menuItem.addSubItem(MENU_FOOTER, 0, "").also {
            it.setColors(0xfffafafa.toInt(), 0xfffafafa.toInt())
            it.textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            it.textView.includeFontPadding = false
            it.setPadding(AndroidUtilities.dp(12f), 0, AndroidUtilities.dp(12f), 0)
            it.setItemHeight(32)
            it.isClickable = false
            it.isFocusable = false
        }
        applyFooter(null)
    }

    @JvmStatic
    fun resetMenuItems(menuItem: ActionBarMenuItem) {
        menuItem.hideSubItem(MENU_COPY_PHOTO)
        menuItem.hideSubItem(MENU_COPY_FRAME)
        applyFooter(null)
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
}
