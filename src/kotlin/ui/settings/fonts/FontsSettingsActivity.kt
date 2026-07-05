package desu.inugram.ui.settings.fonts

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.font.FontConfig
import desu.inugram.helpers.font.FontConfig.FontMode
import desu.inugram.helpers.font.FontHelper
import desu.inugram.helpers.font.FontId
import desu.inugram.helpers.font.FontLibrary
import desu.inugram.ui.settings.SettingsPageActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

/**
 * Manages the font roster: built-in editor fonts (pinned, reorderable, not deletable) plus
 * user-imported families (add / remove / reorder). The list order drives the media-editor text
 * tool; the separate "App font" row picks the whole-app UI font (Default / System / a family).
 */
class FontsSettingsActivity : SettingsPageActivity(), NotificationCenter.NotificationCenterDelegate {
    private var reorderSectionId = -1
    private val rows = HashMap<String, FontRow>()

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuFonts)

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id == NotificationCenter.customTypefacesLoaded) listView?.adapter?.update(true)
    }

    override fun onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.customTypefacesLoaded)
        super.onFragmentDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun createView(context: Context): View {
        val view = super.createView(context)
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.customTypefacesLoaded)
        listView.inu_longPressDragEnabled = false
        listView.listenReorder { id, items ->
            if (id != reorderSectionId) return@listenReorder
            val newOrder = items.mapNotNull { it.`object` as? String }
            if (newOrder.size == FontLibrary.getCachedRoster().size) {
                FontLibrary.setRoster(newOrder.map { FontId.parse(it) })
                FontLibrary.invalidateEditorRoster()
            }
        }
        listView.allowReorder(true)
        return view
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            UItem.asButton(
                BUTTON_APP_FONT, LocaleController.getString(R.string.InuAppFont), when (val mode = FontConfig.FONT.value) {
                    FontMode.System -> LocaleController.getString(R.string.InuFontSystem)
                    FontMode.Bundled -> LocaleController.getString(R.string.InuFontDefault)
                    is FontMode.Custom -> FontLibrary.getFontName(mode.fontId)
                }
            )
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAppFontInfo)))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAvailableFonts)))
        // device system fonts are only enumerable on API Q+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            items.add(
                mkTwoLineCheckItem(
                    BUTTON_INCLUDE_SYSTEM,
                    R.string.InuFontIncludeSystem,
                    R.string.InuFontIncludeSystemInfo,
                    FontConfig.FONT_INCLUDE_SYSTEM.value,
                )
            )
        }

        reorderSectionId = adapter.reorderSectionStart()
        for (font in FontLibrary.getCachedRoster()) {
            val token = font.token()
            val row = rows.getOrPut(token) {
                FontRow(context).also { r ->
                    r.setOnReorderTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            val holder = listView.findContainingViewHolder(r)
                                ?: return@setOnReorderTouchListener false
                            listView.itemTouchHelper.startDrag(holder)
                        }
                        false
                    }
                }
            }
            row.bind(font, FontLibrary.isHidden(font))
            items.add(UItem.asCustom(row, row.heightDp).apply {
                id = token.hashCode()
                `object` = token
            })
        }
        adapter.reorderSectionEnd()
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuFontsInfo)))

        items.add(UItem.asButton(BUTTON_ADD, R.drawable.msg_add, LocaleController.getString(R.string.InuFontAdd)))
        items.add(UItem.asButton(BUTTON_RESET, R.drawable.msg_reset, LocaleController.getString(R.string.InuFontResetOrder)))
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_APP_FONT -> presentFragment(FontStackActivity())
            BUTTON_ADD -> launchFontPicker()
            BUTTON_RESET -> {
                FontLibrary.resetOrder()
                FontLibrary.invalidateEditorRoster()
                listView.adapter.update(true)
            }

            BUTTON_INCLUDE_SYSTEM -> {
                if (FontConfig.FONT_INCLUDE_SYSTEM.toggle()) FontLibrary.ensureSystemFontsLoaded()
                FontLibrary.invalidateEditorRoster()
                listView.adapter.update(true)
            }

            else -> {
                val token = item.`object` as? String ?: return
                val font = FontId.parse(token)
                when {
                    // tap on the eye toggles visibility; elsewhere opens the font menu.
                    // the row is wrapped in a FrameLayout, so look it up rather than casting `view`.
                    rows[token]?.isInEye(x) == true -> setHidden(font, !FontLibrary.isHidden(font))
                    else -> showFontMenu(font, view)
                }
            }
        }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean {
        if (item.id != BUTTON_APP_FONT && item.id != BUTTON_ADD && item.id != BUTTON_RESET) {
            val token = item.`object` as? String
            if (token != null) {
                showFontMenu(FontId.parse(token), view)
                return true
            }
        }

        return super.onLongClick(item, view, position, x, y)
    }

    private fun showFontMenu(font: FontId, anchor: View) {
        // for imported/system families, list individual faces as disabled rows (in their own face) on top
        val faces = FontLibrary.getFontFaces(font)
        // built-ins can't be app font or removed and have no faces to list → no menu
        if (faces.isEmpty() && font !is FontId.Family) return

        val opts = ItemOptions.makeOptions(this, anchor)
        if (faces.isNotEmpty()) {
            val muted = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText)
            for ((label, tf) in faces) {
                val sub = opts.add()
                sub.setText(label)
                sub.setColors(muted, muted)
                sub.isClickable = false
                tf?.let { sub.textView.typeface = it }
            }
            opts.addGap()
        }

        // only imported families can be the app font / removed
        if (font is FontId.Family) {
            opts.add(R.drawable.msg_text_outlined, LocaleController.getString(R.string.InuFontSetAsApp)) {
                // keep any existing fallback stack; only the primary changes
                FontConfig.FONT.value = FontMode.Custom(font, FontHelper.getActiveFallbackIds())
                listView.adapter.update(true)
                showRestartBulletin()
            }
            opts.add(R.drawable.msg_delete, LocaleController.getString(R.string.InuFontRemove), true) {
                removeFont(font)
            }
        }
        opts.show()
    }

    private fun setHidden(font: FontId, hidden: Boolean) {
        FontLibrary.setHidden(font, hidden)
        // hiding the active app font (family or built-in) reverts it to the default — otherwise the
        // editor would hide it while the app keeps rendering everything in it.
        if (hidden && FontHelper.isActiveCustomFont(font)) {
            FontHelper.resetAppFont()
            showRestartBulletin()
        }
        FontLibrary.invalidateEditorRoster()
        listView.adapter.update(true)
    }

    private fun removeFont(font: FontId.Family) {
        val wasActive = FontHelper.isActiveCustomFont(font)
        FontLibrary.removeFamily(font.id)
        rows.remove(font.token())
        if (wasActive) {
            FontHelper.resetAppFont()
            showRestartBulletin()
        }
        FontLibrary.invalidateEditorRoster()
        listView.adapter.update(true)
    }

    private fun launchFontPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "font/ttf", "font/otf", "font/collection", "font/sfnt",
                        "application/font-sfnt", "application/x-font-ttf",
                        "application/x-font-opentype", "application/octet-stream",
                    )
                )
            }
            startActivityForResult(intent, REQ_PICK_FONT)
        } catch (e: Exception) {
            FileLog.e(e)
            BulletinFactory.of(this).createErrorBulletin(e.message ?: "").show()
        }
    }

    override fun onActivityResultFragment(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_PICK_FONT) return
        if (resultCode != Activity.RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } else {
            data.data?.let(uris::add)
        }
        if (uris.isEmpty()) return
        val ctx = parentActivity ?: context ?: return
        FileLog.d("InuFonts: onActivityResultFragment: picked ${uris.size} uris")
        Utilities.globalQueue.postRunnable {
            val result = FontLibrary.importFromUris(ctx, uris)
            AndroidUtilities.runOnUIThread {
                if (result.addedFaces > 0) {
                    FontLibrary.invalidateEditorRoster()
                    listView.adapter.update(true)
                    if (result.rejectedBySystem > 0) {
                        BulletinFactory.of(this).createErrorBulletin(
                            LocaleController.getString(R.string.InuFontUnsupported)
                        ).show()
                    }
                } else {
                    BulletinFactory.of(this).createErrorBulletin(
                        LocaleController.getString(
                            if (result.rejectedBySystem > 0) R.string.InuFontUnsupported else R.string.InuFontImportFailed
                        )
                    ).show()
                }
            }
        }
    }

    companion object {
        private val BUTTON_APP_FONT = InuUtils.generateId()
        private val BUTTON_INCLUDE_SYSTEM = InuUtils.generateId()
        private val BUTTON_ADD = InuUtils.generateId()
        private val BUTTON_RESET = InuUtils.generateId()
        private const val REQ_PICK_FONT = 31010

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "fonts",
            titleRes = R.string.InuFonts,
            iconRes = R.drawable.msg_text_outlined,
            factory = ::FontsSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("app-font", R.string.InuAppFont, BUTTON_APP_FONT),
                SearchRegistry.Entry("include-system-fonts", R.string.InuFontIncludeSystem, BUTTON_INCLUDE_SYSTEM),
                SearchRegistry.Entry("add-font", R.string.InuFontAdd, BUTTON_ADD),
            ),
        )
    }


    @SuppressLint("ViewConstructor")
    class FontRow(context: Context) : FrameLayout(context) {
        val heightDp = 58
        private val handle: ImageView
        private val text: TextView
        private val tagView: TextView
        private val eye: ImageView

        init {
            val v = buildFontRow(this)
            handle = v.handle
            text = v.text
            tagView = v.tag
            eye = v.trailing
        }

        fun bind(font: FontId, hidden: Boolean) {
            text.text = FontLibrary.getFontName(font)
            text.typeface = FontLibrary.getTypefaceFor(font) ?: Typeface.DEFAULT
            text.alpha = if (hidden) 0.4f else 1f

            FontSourceTag.bind(tagView, font, if (hidden) 0.4f else 1f)

            eye.setImageResource(if (hidden) R.drawable.menu_hide_gift else R.drawable.msg_message)
            eye.alpha = if (hidden) 0.4f else 1f
            eye.contentDescription =
                LocaleController.getString(if (hidden) R.string.InuFontShow else R.string.InuFontHide)
        }

        @SuppressLint("ClickableViewAccessibility")
        fun setOnReorderTouchListener(listener: OnTouchListener) {
            handle.setOnTouchListener(listener)
        }

        /** True if [x] (relative to the row) falls within the eye toggle's bounds. */
        fun isInEye(x: Float): Boolean = x >= eye.left && x <= eye.right
    }

}