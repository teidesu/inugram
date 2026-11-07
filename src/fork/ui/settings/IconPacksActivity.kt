package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.icons.IconPackHelper
import desu.inugram.helpers.theme.M3SectionsHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.LinkSpanDrawable
import org.telegram.ui.Components.Switch
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Stories.recorder.ButtonWithCounterView
import kotlin.math.roundToInt

class IconPacksActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuIconReplacement)

    private var packs = IconPackHelper.getPacks().associateBy { it.id }
    private val draftOrder = ArrayList(packs.keys)
    private val draftEnabled = HashSet(IconPackHelper.getEnabledIds())
    private val rows = HashMap<String, IconPackRow>()
    private val telegramRow by lazy { IconPackRow(context) }
    private val telegramPreview by lazy { IconPackHelper.loadStockRowPreview() }
    private var reorderSectionId = -1

    override fun createView(context: Context): View {
        val view = super.createView(context)
        attachStickyButton(view, ButtonWithCounterView(context, true, resourceProvider).setRound().apply {
            setText(LocaleController.getString(R.string.InuIconPacksApply), false)
            setOnClickListener { apply() }
        })
        simpleActionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1 && onBackPressed(true)) finishFragment()
            }
        })
        listView.allowReorder(true)
        listView.setReorderLongPressEnabled(false)
        listView.listenReorder { id, items ->
            if (id != reorderSectionId) return@listenReorder
            val order = items.mapNotNull { (it.`object` as? IconPackHelper.PackInfo)?.id }
            if (order.size != draftOrder.size) return@listenReorder
            draftOrder.clear()
            draftOrder.addAll(order)
            listView.adapter.update(true)
        }
        return view
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val classic = !M3SectionsHelper.isEnabled()
        val handle = if (draftOrder.size > 1) View.VISIBLE else View.GONE

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuIconPacksInstalled)))
        reorderSectionId = adapter.reorderSectionStart()
        for (id in draftOrder) {
            val pack = packs[id] ?: continue
            items.add(buildRow(pack, handle, divider = classic))
        }
        adapter.reorderSectionEnd()
        telegramRow.bind(
            LocaleController.getString(R.string.InuIconPacksTelegram),
            LocaleController.getString(R.string.InuIconPacksTelegramInfo),
            telegramPreview,
            checked = true,
            handle = if (handle == View.VISIBLE) View.INVISIBLE else View.GONE,
            divider = false,
            locked = true,
        )
        items.add(UItem.asCustom(telegramRow, IconPackRow.HEIGHT_DP))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuIconPacksOrderInfo)))

        items.add(UItem.asButton(BUTTON_LOAD, R.drawable.msg_download, LocaleController.getString(R.string.InuIconPackLoadFromFile)))
        items.add(UItem.asShadow(null))
    }

    private fun buildRow(pack: IconPackHelper.PackInfo, handle: Int, divider: Boolean): UItem {
        val row = rows.getOrPut(pack.id) {
            IconPackRow(context).also { row ->
                row.setOnReorderTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        val holder = listView.findContainingViewHolder(row) ?: return@setOnReorderTouchListener false
                        listView.itemTouchHelper.startDrag(holder)
                    }
                    false
                }
            }
        }
        val title = if (pack.bundled) {
            appendTag(pack.name, LocaleController.getString(R.string.InuIconPackBundled), getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader))
        } else {
            pack.name
        }
        val author = InuUtils.linkUsernames(pack.author) { messagesController.openByUserName(it, this, 0) }
        row.bind(title, author, pack.preview, pack.id in draftEnabled, handle, divider)
        row.setOnClickListener {
            if (!draftEnabled.remove(pack.id)) draftEnabled.add(pack.id)
            listView.adapter.update(true)
        }
        if (pack.bundled) {
            row.setOnLongClickListener(null)
        } else {
            row.setOnLongClickListener {
                IconPackHelper.confirmDelete(this, pack, ::onDeleted)
                true
            }
        }
        return UItem.asCustom(row, IconPackRow.HEIGHT_DP).apply {
            id = ITEM_BASE + (pack.id.hashCode() and 0xffff)
            `object` = pack
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id == BUTTON_LOAD) launchLoad()
    }

    private fun hasChanges(): Boolean {
        val savedEnabled = IconPackHelper.getEnabledIds().filterTo(HashSet()) { it in packs }
        return draftOrder != packs.keys.toList() || draftEnabled.filterTo(HashSet()) { it in packs } != savedEnabled
    }

    override fun onBackPressed(invoked: Boolean): Boolean {
        if (!hasChanges()) return super.onBackPressed(invoked)
        if (invoked) {
            val dialog = AlertDialog.Builder(parentActivity ?: return false, resourceProvider)
                .setTitle(LocaleController.getString(R.string.FilterDiscardTitle))
                .setMessage(LocaleController.getString(R.string.InuIconPacksUnsavedAlert))
                .setPositiveButton(LocaleController.getString(R.string.ApplyTheme)) { _, _ -> apply() }
                .setNegativeButton(LocaleController.getString(R.string.PassportDiscard)) { _, _ -> finishFragment() }
                .create()
            showDialog(dialog)
        }
        return false
    }

    override fun canBeginSlide() = onBackPressed(true)

    private fun apply() {
        IconPackHelper.applyStack(draftOrder, draftEnabled)
        val activity = parentActivity ?: return
        InuUtils.restartApp(activity)
    }

    private fun launchLoad() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        try {
            startActivityForResult(intent, REQ_LOAD)
        } catch (e: Exception) {
            BulletinFactory.of(this).createErrorBulletin(e.message ?: "").show()
        }
    }

    override fun onActivityResultFragment(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_LOAD || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        IconPackHelper.startImportFromUri(this, uri, ::reload)
    }

    private fun onDeleted(wasActive: Boolean) {
        reload()
        if (wasActive) showRestartBulletin()
    }

    // keeps unapplied edits; new packs come in on top, disabled
    private fun reload() {
        packs = IconPackHelper.getPacks().associateBy { it.id }
        val added = packs.keys.filter { it !in draftOrder }
        draftOrder.retainAll(packs.keys)
        draftOrder.addAll(0, added)
        draftEnabled.retainAll(packs.keys)
        listView.adapter.update(true)
    }

    companion object {
        private val BUTTON_LOAD = InuUtils.generateId()
        private const val ITEM_BASE = 30000
        private const val REQ_LOAD = 31012
    }
}

@SuppressLint("ViewConstructor")
private class IconPackRow(context: Context) : LinearLayout(context) {
    private val handle = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER
        setImageResource(R.drawable.list_reorder)
        colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_stickers_menu), PorterDuff.Mode.MULTIPLY)
        contentDescription = LocaleController.getString(R.string.FilterReorder)
        isClickable = true
    }
    private val title = TextView(context).apply {
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        textSize = 16f
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
    }
    private val subtitle = LinkSpanDrawable.LinksTextView(context).apply {
        setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText))
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        textSize = 13f
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
    }
    private val preview = IconPreview(context)
    private val switch = Switch(context).apply {
        setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite)
        isClickable = false
    }
    private val textBlock = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f))
    }
    private var needDivider = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Theme.createSelectorWithBackgroundDrawable(
            Theme.getColor(Theme.key_windowBackgroundWhite),
            Theme.getColor(Theme.key_listSelector),
        )
        val rtl = LocaleController.isRTL
        addView(handle, LayoutHelper.createLinear(40, 48, Gravity.CENTER_VERTICAL, if (rtl) 0 else 8, 0, if (rtl) 8 else 0, 0))
        addView(preview, LayoutHelper.createLinear(PREVIEW_DP, PREVIEW_DP, Gravity.CENTER_VERTICAL))
        addView(textBlock, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, if (rtl) 0 else TEXT_GAP_DP, 0, if (rtl) TEXT_GAP_DP else 0, 0))
        addView(switch, LayoutHelper.createLinear(37, 24, Gravity.CENTER_VERTICAL, if (rtl) 22 else 12, 0, if (rtl) 12 else 22, 0))
    }

    fun bind(title: CharSequence, subtitle: CharSequence, icons: List<Drawable>, checked: Boolean, handle: Int, divider: Boolean, locked: Boolean = false) {
        this.title.text = title
        this.subtitle.text = subtitle
        this.subtitle.visibility = if (subtitle.isEmpty()) GONE else VISIBLE
        this.handle.visibility = handle
        preview.icons = icons
        switch.alpha = if (locked) 0.5f else 1f
        isClickable = !locked
        val previewStart = AndroidUtilities.dp(if (handle != GONE) PREVIEW_INSET_WITH_HANDLE_DP else PREVIEW_INSET_DP)
        (preview.layoutParams as LayoutParams).apply {
            if (LocaleController.isRTL) rightMargin = previewStart else leftMargin = previewStart
        }
        preview.requestLayout()
        if (switch.isChecked != checked) switch.setChecked(checked, isAttachedToWindow)
        if (needDivider != divider) {
            needDivider = divider
            invalidate()
        }
    }

    fun setOnReorderTouchListener(listener: OnTouchListener) {
        handle.setOnTouchListener(listener)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (needDivider) drawRowDivider(canvas, (if (handle.visibility != GONE) TEXT_INSET_WITH_HANDLE_DP else PREVIEW_INSET_DP) + PREVIEW_DP + TEXT_GAP_DP)
    }

    companion object {
        const val HEIGHT_DP = 64
        private const val PREVIEW_DP = 41
        private const val TEXT_GAP_DP = 14
        private const val PREVIEW_INSET_DP = 21f
        private const val PREVIEW_INSET_WITH_HANDLE_DP = 8f
        private const val TEXT_INSET_WITH_HANDLE_DP = 56f
    }
}

private class IconPreview(context: Context) : View(context) {
    private val filter = PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY)

    var icons: List<Drawable> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val cell = width / 2f
        val box = AndroidUtilities.dp(ICON_DP)
        icons.forEachIndexed { i, icon ->
            val aspect = if (icon.intrinsicWidth > 0 && icon.intrinsicHeight > 0) icon.intrinsicWidth.toFloat() / icon.intrinsicHeight else 1f
            val w = if (aspect >= 1f) box else (box * aspect).roundToInt()
            val h = if (aspect >= 1f) (box / aspect).roundToInt() else box
            val x = ((i % 2) * cell + (cell - w) / 2).roundToInt()
            val y = ((i / 2) * cell + (cell - h) / 2).roundToInt()
            icon.setBounds(x, y, x + w, y + h)
            icon.colorFilter = filter
            icon.draw(canvas)
        }
    }

    companion object {
        private const val ICON_DP = 18f
    }
}
