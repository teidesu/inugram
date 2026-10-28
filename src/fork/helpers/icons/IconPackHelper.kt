package desu.inugram.helpers.icons

import android.app.Dialog
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.ui.settings.IconPacksActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BulletinFactory
import java.io.File
import java.io.InputStream
import kotlin.concurrent.thread

object IconPackHelper {
    const val EXTENSION = ".icons"
    private const val TAG = "InuIconPacks"
    private const val ASSETS_DIR = "icon_packs"
    private const val MAX_PACK_BYTES = 64L shl 20
    private const val PREVIEW_COLUMNS = 6
    private const val PREVIEW_ROWS = 2
    private const val PREVIEW_CELL_DP = 44f
    private val PREVIEW_ICONS = listOf(
        "msg_settings", "msg_contacts", "msg_calls", "msg_saved", "msg_archive", "msg_pin",
        "msg_edit", "msg_copy", "msg_forward", "msg_delete", "input_smile", "input_attach",
        "input_mic", "msg_theme", "msg_download", "msg_link", "msg_fave", "msg_shareout",
    )
    private val ROW_PREVIEW_ICONS = listOf("msg_sticker", "msg_link", "msg_pin", "msg_gallery")

    class PackInfo(val id: String, val name: String, val author: String, val bundled: Boolean, val preview: List<Drawable>)

    private class Active(val key: String, val packs: List<IconPack>)

    @Volatile
    private var active: Active? = null

    private val packsDir get() = File(ApplicationLoader.applicationContext.filesDir, "icon_packs")
    private val bundledDir get() = File(packsDir, "bundled")

    private val bundledIds: List<String> by lazy {
        ApplicationLoader.applicationContext.assets.list(ASSETS_DIR).orEmpty()
            .filter { it.endsWith(EXTENSION) }
            .map { it.removeSuffix(EXTENSION) }
            .sorted()
    }

    fun getEnabledIds(): List<String> = parseIds(InuConfig.ICON_PACKS.value)

    private fun parseIds(value: String) = value.split(',').filter { it.isNotEmpty() }

    fun applyStack(order: List<String>, enabled: Set<String>) {
        InuConfig.prefs.edit(true) {
            InuConfig.ICON_PACKS.unsafeSet(order.filter { it in enabled }.joinToString(","), this)
            InuConfig.ICON_PACKS_ORDER.unsafeSet(order.joinToString(","), this)
        }
        invalidateActivePacks()
    }

    private fun moveToTop(id: String) {
        applyStack(listOf(id) + getPacks().map { it.id }.filter { it != id }, getEnabledIds().toSet())
    }

    @JvmStatic
    fun getActivePacks(): List<IconPack> {
        val key = InuConfig.ICON_PACKS.value
        if (key.isEmpty()) return emptyList()
        active?.let { if (it.key == key) return it.packs }
        synchronized(this) {
            active?.let { if (it.key == key) return it.packs }
            val packs = getEnabledIds().mapNotNull(::openPack)
            active?.packs?.forEach(IconPack::close)
            active = Active(key, packs)
            thread(name = "InuIconPackPrewarm", priority = Thread.MIN_PRIORITY) { packs.forEach(IconPack::prewarm) }
            return packs
        }
    }

    @JvmStatic
    fun getLottieJson(rawRes: Int): String? {
        val resources = ApplicationLoader.applicationContext.resources
        for (pack in getActivePacks()) {
            pack.loadLottieJson(resources, rawRes)?.let { return it }
        }
        return null
    }

    @JvmStatic
    fun shouldPlayLottie(rawRes: Int, vararg staticRes: Int): Boolean {
        val packs = getActivePacks()
        if (packs.isEmpty()) return true
        val resources = ApplicationLoader.applicationContext.resources
        val name = resources.getResourceEntryName(rawRes)
        if (packs.any { it.hasLottie(name) }) return true
        val staticNames = staticRes.map(resources::getResourceEntryName)
        return packs.none { pack -> staticNames.any { it in pack.iconNames } }
    }

    private fun invalidateActivePacks() = synchronized(this) {
        active?.packs?.forEach(IconPack::close)
        active = null
    }

    private fun openPack(id: String): IconPack? = try {
        val file = if (id in bundledIds) getBundledFile(id) else File(packsDir, id + EXTENSION)
        IconPack.open(file).also { if (it.id != id) throw IllegalStateException("pack id mismatch: ${it.id}") }
    } catch (e: Exception) {
        FileLog.e("$TAG: failed to open pack $id", e)
        null
    }

    @Synchronized
    private fun getBundledFile(id: String): File {
        val context = ApplicationLoader.applicationContext
        val file = File(bundledDir, id + EXTENSION)
        val stamp = File(bundledDir, "$id.stamp")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (file.exists() && stamp.exists() && stamp.readText() == version) return file

        bundledDir.mkdirs()
        val tmp = File(bundledDir, "$id.tmp")
        context.assets.open("$ASSETS_DIR/$id$EXTENSION").use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (!tmp.renameTo(file)) throw IllegalStateException("failed to move $tmp")
        stamp.writeText(version)
        return file
    }

    fun getPacks(): List<PackInfo> {
        val bundled = bundledIds.mapNotNull { id -> readInfo(id, bundled = true) { getBundledFile(id) } }
        val custom = packsDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(EXTENSION) }
            .mapNotNull { file -> readInfo(file.name.removeSuffix(EXTENSION), bundled = false) { file } }
            .sortedBy { it.name.lowercase() }
        val order = parseIds(InuConfig.ICON_PACKS_ORDER.value).ifEmpty { getEnabledIds() }
        return (bundled + custom).sortedBy { order.indexOf(it.id).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }
    }

    private inline fun readInfo(id: String, bundled: Boolean, getFile: () -> File): PackInfo? = try {
        IconPack.open(getFile()).use { PackInfo(id, it.name, it.author, bundled, loadPreviewIcons(it, ROW_PREVIEW_ICONS, ROW_PREVIEW_ICONS.size)) }
    } catch (e: Exception) {
        FileLog.e("$TAG: failed to read pack $id", e)
        null
    }

    fun getActivePacksSummary(): String {
        val packs = getActivePacks()
        return when (packs.size) {
            0 -> LocaleController.getString(R.string.InuIconPacksTelegram)
            1 -> packs[0].name
            else -> "${packs.last().name} + ${packs.size - 1}"
        }
    }

    private fun deletePack(id: String): Boolean {
        if (id in bundledIds) return false
        val enabled = getEnabledIds()
        applyStack(getPacks().map { it.id }.filter { it != id }, enabled.toSet() - id)
        File(packsDir, id + EXTENSION).delete()
        return id in enabled
    }

    fun isIconPackFileName(name: String) = name.endsWith(EXTENSION, ignoreCase = true)

    private class ImportPreview(val info: PackInfo, val iconCount: Int, val icons: List<Drawable>)

    fun startImportFromUri(fragment: BaseFragment, uri: Uri, onInstalled: (() -> Unit)?) {
        Utilities.globalQueue.postRunnable {
            val context = ApplicationLoader.applicationContext
            val file = File(context.cacheDir, "icon_pack_import$EXTENSION")
            val copied = try {
                val input = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("no stream for $uri")
                input.use { copyLimited(it, file) }
                true
            } catch (e: Exception) {
                FileLog.e("$TAG: failed to read $uri", e)
                file.delete()
                false
            }
            if (copied) {
                startImportFromFile(fragment, file, onInstalled)
            } else {
                AndroidUtilities.runOnUIThread {
                    BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.InuIconPackInvalid)).show()
                }
            }
        }
    }

    private fun copyLimited(input: InputStream, target: File) {
        target.outputStream().use { output ->
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_PACK_BYTES) throw IllegalStateException("pack is too large")
                output.write(buffer, 0, read)
            }
        }
    }

    fun startImportFromFile(fragment: BaseFragment, file: File, onInstalled: (() -> Unit)? = null) {
        Utilities.globalQueue.postRunnable {
            val preview = try {
                IconPack.open(file).use { ImportPreview(PackInfo(it.id, it.name, it.author, false, emptyList()), it.iconCount, loadPreviewIcons(it, PREVIEW_ICONS, PREVIEW_COLUMNS * PREVIEW_ROWS)) }
            } catch (e: Exception) {
                FileLog.e("$TAG: invalid pack ${file.name}", e)
                null
            }
            AndroidUtilities.runOnUIThread {
                if (preview == null || preview.info.id in bundledIds) {
                    BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.InuIconPackInvalid)).show()
                    return@runOnUIThread
                }
                showInstallConfirm(fragment, file, preview, onInstalled)
            }
        }
    }

    private fun loadPreviewIcons(pack: IconPack, preferred: List<String>, count: Int): List<Drawable> {
        val context = ApplicationLoader.applicationContext
        val names = (preferred.filter { it in pack.iconNames } + pack.iconNames.sorted()).distinct()
        return names.asSequence()
            .map { context.resources.getIdentifier(it, "drawable", context.packageName) }
            .filter { it != 0 }
            .mapNotNull { pack.loadDrawable(context.resources, it, 0, null) }
            .take(count)
            .toList()
    }

    // app context resources give stock icons, activity ones are replaced by the active packs
    fun loadStockRowPreview(): List<Drawable> {
        val context = ApplicationLoader.applicationContext
        return ROW_PREVIEW_ICONS.mapNotNull { name ->
            val id = context.resources.getIdentifier(name, "drawable", context.packageName)
            if (id == 0) null else context.resources.getDrawable(id, null)?.mutate()
        }
    }

    private fun showInstallConfirm(fragment: BaseFragment, file: File, preview: ImportPreview, onInstalled: (() -> Unit)?) {
        val ctx = fragment.parentActivity ?: return
        val info = preview.info
        val details = LocaleController.formatPluralString("InuIconPackIcons", preview.iconCount)
        val builder = AlertDialog.Builder(ctx, fragment.resourceProvider)
            .setTitle(info.name)
            .setMessage(if (info.author.isEmpty()) details else InuUtils.linkUsernames(LocaleController.formatString(R.string.InuIconPackByAuthor, info.author, details)) {
                fragment.dismissCurrentDialog()
                fragment.messagesController.openByUserName(it, fragment, 0)
            })
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.InuIconPackInstall)) { _, _ -> install(fragment, file, info.id, onInstalled) }
        if (preview.icons.isNotEmpty()) builder.setView(createPreviewGrid(ctx, preview.icons, fragment.getThemedColor(Theme.key_dialogIcon)))
        fragment.showDialog(builder.create())
    }

    private fun createPreviewGrid(context: Context, icons: List<Drawable>, color: Int): View {
        val filter = PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY)
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(4f), AndroidUtilities.dp(16f), AndroidUtilities.dp(8f))
        }
        for (row in icons.chunked(PREVIEW_COLUMNS)) {
            val rowView = LinearLayout(context)
            for (i in 0 until PREVIEW_COLUMNS) {
                val icon = row.getOrNull(i)
                rowView.addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    if (icon != null) {
                        setImageDrawable(icon)
                        colorFilter = filter
                    }
                }, LinearLayout.LayoutParams(0, AndroidUtilities.dp(PREVIEW_CELL_DP), 1f))
            }
            grid.addView(rowView)
        }
        return grid
    }

    private fun install(fragment: BaseFragment, file: File, id: String, onInstalled: (() -> Unit)?) {
        Utilities.globalQueue.postRunnable {
            val isNew = !File(packsDir, id + EXTENSION).exists()
            val installed = try {
                packsDir.mkdirs()
                val tmp = File(packsDir, "$id.tmp")
                file.copyTo(tmp, overwrite = true)
                // validate the copy, the source may have changed since it was inspected
                val copiedId = try {
                    IconPack.open(tmp).use { it.id }
                } catch (e: Exception) {
                    tmp.delete()
                    throw e
                }
                if (copiedId != id || !tmp.renameTo(File(packsDir, id + EXTENSION))) {
                    tmp.delete()
                    throw IllegalStateException("failed to install $id")
                }
                true
            } catch (e: Exception) {
                FileLog.e("$TAG: install failed", e)
                false
            }
            AndroidUtilities.runOnUIThread {
                if (!installed) {
                    BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.InuIconPackInvalid)).show()
                    return@runOnUIThread
                }
                val replacedEnabled = !isNew && id in getEnabledIds()
                if (isNew) moveToTop(id) else invalidateActivePacks()
                val page = if (onInstalled != null) {
                    onInstalled()
                    fragment
                } else {
                    IconPacksActivity().also { fragment.presentFragment(it) }
                }
                if (replacedEnabled) {
                    BulletinFactory.of(page).createSimpleBulletin(
                        R.raw.chats_infotip,
                        LocaleController.getString(R.string.InuRestartRequired),
                        LocaleController.getString(R.string.InuRestartNow),
                    ) {
                        val activity = page.parentActivity ?: return@createSimpleBulletin
                        InuUtils.restartApp(activity)
                    }.show()
                } else {
                    BulletinFactory.of(page).createSimpleBulletin(
                        R.raw.contact_check,
                        LocaleController.getString(R.string.InuIconPackInstalled),
                    ).show()
                }
            }
        }
    }

    fun confirmDelete(fragment: BaseFragment, info: PackInfo, onDeleted: (wasActive: Boolean) -> Unit) {
        val ctx = fragment.parentActivity ?: return
        val dialog = AlertDialog.Builder(ctx, fragment.resourceProvider)
            .setTitle(LocaleController.getString(R.string.InuIconPackDelete))
            .setMessage(LocaleController.formatString(R.string.InuIconPackDeleteConfirm, info.name))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.Delete)) { _, _ ->
                onDeleted(deletePack(info.id))
            }
            .create()
        fragment.showDialog(dialog)
        (dialog.getButton(Dialog.BUTTON_POSITIVE) as? TextView)?.setTextColor(fragment.getThemedColor(Theme.key_text_RedBold))
    }
}
