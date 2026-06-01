package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.font.FontHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.Paint.PaintTypeface
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

/**
 * Manages the font roster: built-in editor fonts (pinned, reorderable, not deletable) plus
 * user-imported families (add / remove / reorder). The list order drives the media-editor text
 * tool; the separate "App font" row picks the whole-app UI font (Default / System / a family).
 */
class FontsSettingsActivity : SettingsPageActivity() {
    private var reorderSectionId = -1
    private val rows = HashMap<String, FontRow>()

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuFonts)

    @SuppressLint("ClickableViewAccessibility")
    override fun createView(context: Context): View {
        val view = super.createView(context)
        listView.inu_longPressDragEnabled = false
        listView.listenReorder { id, items ->
            if (id != reorderSectionId) return@listenReorder
            val newOrder = items.mapNotNull { it.`object` as? String }
            if (newOrder.size == FontHelper.roster().size) {
                FontHelper.setRoster(newOrder)
                PaintTypeface.inu_invalidate()
            }
        }
        listView.allowReorder(true)
        return view
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asButton(BUTTON_APP_FONT, LocaleController.getString(R.string.InuAppFont), appFontValue()))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAppFontInfo)))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuEditorFonts)))
        reorderSectionId = adapter.reorderSectionStart()
        for (token in FontHelper.roster()) {
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
            row.bind(token, FontHelper.isHidden(token))
            val u = UItem.asCustom(row, row.heightDp)
            u.id = token.hashCode()
            u.`object` = token
            items.add(u)
        }
        adapter.reorderSectionEnd()
        items.add(UItem.asShadow(null))

        items.add(UItem.asButton(BUTTON_ADD, R.drawable.msg_add, LocaleController.getString(R.string.InuFontAdd)))
        items.add(UItem.asButton(BUTTON_RESET, R.drawable.msg_reset, LocaleController.getString(R.string.InuFontResetOrder)))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuFontsInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_APP_FONT -> showAppFontPicker(view)
            BUTTON_ADD -> launchFontPicker()
            BUTTON_RESET -> {
                FontHelper.resetOrder()
                PaintTypeface.inu_invalidate()
                listView.adapter.update(true)
            }
            else -> {
                val token = item.`object` as? String ?: return
                when {
                    // tap on the eye toggles visibility; elsewhere opens the font menu.
                    // the row is wrapped in a FrameLayout, so look it up rather than casting `view`.
                    rows[token]?.isInEye(x) == true ->
                        setHidden(token, !FontHelper.isHidden(token))
                    else -> showFontMenu(token, view)
                }
            }
        }
    }

    private fun showFontMenu(token: String, anchor: View) {
        val isFamily = token.startsWith("font:")
        // app-font id is the family id for imported fonts, or the built-in key for default fonts
        val appFontId = if (isFamily) token.removePrefix("font:") else token
        val opts = ItemOptions.makeOptions(this, anchor)

        // for a family, list its individual faces as disabled rows (rendered in their own face) on top
        if (isFamily) {
            val faces = FontHelper.familyFaces(appFontId)
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
        }

        opts.add(R.drawable.msg_text_outlined, LocaleController.getString(R.string.InuFontSetAsApp)) {
            InuConfig.FONT_MODE.value = 2
            InuConfig.ACTIVE_FONT_ID.value = appFontId
            listView.adapter.update(true)
            showRestartBulletin()
        }
        if (isFamily) {
            opts.add(R.drawable.msg_delete, LocaleController.getString(R.string.InuFontRemove), true) {
                removeFont(token)
            }
        }
        opts.show()
    }

    private fun setHidden(token: String, hidden: Boolean) {
        FontHelper.setHidden(token, hidden)
        // hiding the active app font reverts it to the default
        if (hidden && token.startsWith("font:") && isActiveFamily(token.removePrefix("font:"))) {
            InuConfig.FONT_MODE.value = 0
            InuConfig.ACTIVE_FONT_ID.value = ""
            showRestartBulletin()
        }
        PaintTypeface.inu_invalidate()
        listView.adapter.update(true)
    }

    private fun removeFont(token: String) {
        val id = token.removePrefix("font:")
        val wasActive = isActiveFamily(id)
        FontHelper.removeFamily(id)
        rows.remove(token)
        if (wasActive) {
            InuConfig.FONT_MODE.value = 0
            InuConfig.ACTIVE_FONT_ID.value = ""
            showRestartBulletin()
        }
        PaintTypeface.inu_invalidate()
        listView.adapter.update(true)
    }

    private fun appFontValue(): CharSequence = when (InuConfig.FONT_MODE.value) {
        1 -> LocaleController.getString(R.string.InuFontSystem)
        2 -> fontLabel(
            FontHelper.activeFamilyName() ?: LocaleController.getString(R.string.InuFontUnnamed),
            FontHelper.activeFamilyTypeface(),
        )
        else -> LocaleController.getString(R.string.InuFontDefault)
    }

    /** Renders [name] in [tf] (a TypefaceSpan needs API P; falls back to plain text otherwise). */
    private fun fontLabel(name: CharSequence, tf: Typeface?): CharSequence {
        if (tf == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return name
        return SpannableString(name).apply {
            setSpan(TypefaceSpan(tf), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun isActiveFamily(id: String): Boolean {
        if (InuConfig.FONT_MODE.value != 2) return false
        val active = InuConfig.ACTIVE_FONT_ID.value
        if (active.isNotEmpty()) return active == id
        // legacy: empty id resolves to the first family
        return FontHelper.familyChoices().firstOrNull()?.first == id
    }

    private fun showAppFontPicker(anchor: View) {
        val choices = FontHelper.familyChoices()
        val labels = mutableListOf<CharSequence>(
            LocaleController.getString(R.string.InuFontDefault),
            LocaleController.getString(R.string.InuFontSystem),
        )
        choices.forEach { (id, name) -> labels.add(fontLabel(name, FontHelper.editorTypeface(id))) }
        val selected = when (InuConfig.FONT_MODE.value) {
            1 -> 1
            2 -> {
                val active = InuConfig.ACTIVE_FONT_ID.value.ifEmpty { choices.firstOrNull()?.first ?: "" }
                val idx = choices.indexOfFirst { it.first == active }
                if (idx >= 0) 2 + idx else -1 // -1 when a built-in font is the app font (not in this list)
            }
            else -> 0
        }
        RadioItemOptions.show(this, anchor, labels, selected) { which ->
            when (which) {
                0 -> {
                    InuConfig.FONT_MODE.value = 0
                    InuConfig.ACTIVE_FONT_ID.value = ""
                }
                1 -> {
                    InuConfig.FONT_MODE.value = 1
                    InuConfig.ACTIVE_FONT_ID.value = ""
                }
                else -> {
                    InuConfig.FONT_MODE.value = 2
                    InuConfig.ACTIVE_FONT_ID.value = choices[which - 2].first
                }
            }
            showRestartBulletin()
        }
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
        data.clipData?.let { cd ->
            for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
        } ?: data.data?.let { uris.add(it) }
        if (uris.isEmpty()) return
        val ctx = parentActivity ?: context ?: return
        Utilities.globalQueue.postRunnable {
            val added = FontHelper.importFromUris(ctx, uris)
            AndroidUtilities.runOnUIThread {
                if (added > 0) {
                    PaintTypeface.inu_invalidate()
                    listView.adapter.update(true)
                } else {
                    BulletinFactory.of(this).createErrorBulletin(
                        LocaleController.getString(R.string.InuFontImportFailed)
                    ).show()
                }
            }
        }
    }

    companion object {
        private val BUTTON_APP_FONT = InuUtils.generateId()
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
                SearchRegistry.Entry("add-font", R.string.InuFontAdd, BUTTON_ADD),
            ),
        )
    }
}

/**
 * A reorderable font roster row: drag handle + the font's name rendered in that font, with an
 * eye toggle (hide/show) on the trailing side. Removal of imported families is via tapping the row.
 */
@SuppressLint("ViewConstructor")
class FontRow(context: Context) : FrameLayout(context) {
    val heightDp = 50
    private val handle: ImageView
    private val text: TextView
    private val eye: ImageView

    init {
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        val rtl = LocaleController.isRTL
        val leadGravity = (if (rtl) Gravity.RIGHT else Gravity.LEFT) or Gravity.CENTER_VERTICAL
        val trailGravity = (if (rtl) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL

        handle = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.list_reorder)
            colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_stickers_menu), PorterDuff.Mode.MULTIPLY)
            contentDescription = LocaleController.getString(R.string.FilterReorder)
            isClickable = true
        }
        addView(handle, LayoutHelper.createFrame(48, 48f, leadGravity, 4f, 0f, 4f, 0f))

        // not independently clickable — the row's onClick hit-tests isInEye(x) to avoid firing the
        // item click (delete menu) at the same time
        eye = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY)
        }
        // mirror the drag handle's geometry so the eye sits the same distance from the trailing edge
        addView(eye, LayoutHelper.createFrame(48, 48f, trailGravity, 4f, 0f, 4f, 0f))

        text = TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            textSize = 16f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            gravity = leadGravity
        }
        addView(
            text,
            LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT.toFloat(),
                leadGravity,
                64f,
                0f,
                64f,
                0f,
            )
        )
    }

    fun bind(token: String, hidden: Boolean) {
        if (token.startsWith("font:")) {
            val id = token.removePrefix("font:")
            text.text = FontHelper.editorName(id)
            text.typeface = FontHelper.editorTypeface(id) ?: Typeface.DEFAULT
        } else {
            val pt = PaintTypeface.find(token)
            text.text = pt?.name ?: token
            text.typeface = pt?.typeface ?: Typeface.DEFAULT
        }
        text.alpha = if (hidden) 0.4f else 1f
        eye.setImageResource(if (hidden) R.drawable.inu_tabler_eye_off else R.drawable.inu_tabler_eye)
        eye.contentDescription =
            LocaleController.getString(if (hidden) R.string.InuFontShow else R.string.InuFontHide)
    }

    fun setOnReorderTouchListener(listener: View.OnTouchListener) {
        handle.setOnTouchListener(listener)
    }

    /** True if [x] (relative to the row) falls within the eye toggle's bounds. */
    fun isInEye(x: Float): Boolean = x >= eye.left && x <= eye.right
}
