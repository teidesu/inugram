package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.menu.MenuOrderConfig
import desu.inugram.helpers.menu.MenuOrderEntry
import desu.inugram.helpers.menu.MenuOrderItem
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCell
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.Switch
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

abstract class MenuOrderActivity<I : MenuOrderItem> : SettingsPageActivity() {
    protected var entries = config.value.toMutableList()
    protected val rows = HashMap<I, MenuOrderRow>()
    private val reorderHandlers = HashMap<Int, (List<UItem>) -> Unit>()

    protected abstract val config: MenuOrderConfig<I>
    protected abstract val infoStringRes: Int
    protected abstract val headerStringRes: Int
    protected abstract val resetStringRes: Int

    data class SubCell(val label: CharSequence, val value: CharSequence, val onClick: (MenuOrderRow) -> Unit)

    /** override to attach a long-tap sub-cell to a row (e.g. Reply / Forward long-tap pickers) */
    protected open fun subCell(item: I): SubCell? = null

    /**
     * default flips `enabled`, persists, refreshes the row switch. Override for capacity gating
     * or post-toggle invalidation; call `super` to perform the default flip.
     */
    protected open fun onRowToggle(entry: MenuOrderEntry<I>, row: MenuOrderRow?) {
        val idx = entries.indexOfFirst { it.item == entry.item }
        if (idx < 0) return
        entries[idx] = entries[idx].copy(enabled = !entries[idx].enabled)
        config.value = entries
        row?.setChecked(entries[idx].enabled)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun createView(context: Context): View {
        val view = super.createView(context)
        listView.inu_longPressDragEnabled = false
        listView.listenReorder { id, items -> reorderHandlers[id]?.invoke(items) }
        listView.allowReorder(true)
        return view
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        fillMainSection(items, adapter)
        fillResetSection(items, adapter)
    }

    protected fun fillMainSection(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asShadow(LocaleController.getString(infoStringRes)))
        items.add(UItem.asHeader(LocaleController.getString(headerStringRes)))
        openReorderSection(adapter, toBottom = false)
        for (entry in entries.filter { !it.bottom }) {
            items.add(buildRow(entry))
        }
        adapter.reorderSectionEnd()
    }

    protected fun fillResetSection(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asShadow(SHADOW_END, null))
        items.add(
            UItem.asButton(
                BUTTON_RESET,
                R.drawable.msg_reset,
                LocaleController.getString(resetStringRes)
            )
        )
    }

    /** opens a reorder section AND registers its drag dispatch (so the base listener routes it) */
    protected fun openReorderSection(adapter: UniversalAdapter, toBottom: Boolean): Int {
        val id = adapter.reorderSectionStart()
        // adapter resets its section list on each rebuild, so id 0 marks a fresh fillItems pass
        if (id == 0) reorderHandlers.clear()
        reorderHandlers[id] = { applyReorder(it, toBottom) }
        return id
    }

    /** reorders `entries` to match the dragged UItems of a section */
    @Suppress("UNCHECKED_CAST")
    protected fun applyReorder(items: List<UItem>, toBottom: Boolean) {
        val byItem = entries.associateBy { it.item }
        val reordered = items.mapNotNull { byItem[it.`object` as? I] }
        if (reordered.size != entries.count { it.bottom == toBottom }) return
        val others = entries.filter { it.bottom != toBottom }
        entries = (if (toBottom) others + reordered else reordered + others).toMutableList()
        config.value = entries
    }

    @SuppressLint("ClickableViewAccessibility")
    protected fun buildRow(entry: MenuOrderEntry<I>, decorate: (MenuOrderRow) -> Unit = {}): UItem {
        val row = rows.getOrPut(entry.item) {
            val newRow = MenuOrderRow(context)
            newRow.bind(entry.item)
            newRow.setOnReorderTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    val holder = listView.findContainingViewHolder(newRow) ?: return@setOnReorderTouchListener false
                    listView.itemTouchHelper.startDrag(holder)
                }
                false
            }
            newRow
        }
        row.setSwitchVisible(true)
        row.setMoveBackButton(null)
        decorate(row)
        row.setChecked(entry.enabled)
        val sub = subCell(entry.item)
        val heightDp = if (sub != null) {
            row.setSubCell(sub.label, sub.value)
            row.mainHeightDp + row.subHeightDp
        } else {
            row.clearSubCell()
            row.mainHeightDp
        }
        val uitem = UItem.asCustom(row, heightDp)
        uitem.id = ITEM_BASE + entry.item.ordinal
        uitem.`object` = entry.item
        return uitem
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id == BUTTON_RESET) {
            config.resetToDefault()
            entries = config.default.toMutableList()
            listView.adapter.update(true)
            return
        }
        @Suppress("UNCHECKED_CAST")
        val key = item.`object` as? I ?: return
        val row = rows[key]
        if (row != null && row.isInSubCell(y)) {
            subCell(key)?.onClick?.invoke(row)
            return
        }
        val entry = entries.firstOrNull { it.item == key } ?: return
        onRowToggle(entry, row)
    }

    companion object {
        private val BUTTON_RESET = InuUtils.generateId()
        // distinct from any null-text shadow elsewhere in the page; DiffUtil aliases identical
        // shadows during structural changes and crashes the animated diff
        private val SHADOW_END = InuUtils.generateId()
        private const val ITEM_BASE = 10000
    }
}

@SuppressLint("ViewConstructor")
class MenuOrderRow(context: Context) : LinearLayout(context) {
    private val handle: ImageView
    private val icon: ImageView
    private val text: TextView
    private val switch: Switch
    private val moveBackButton: ImageView
    private val main: FrameLayout
    private var sub: TextCell? = null
    private var subWrapper: FrameLayout? = null

    val mainHeightDp = 50
    val subHeightDp = 50

    init {
        orientation = VERTICAL
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        val rtl = LocaleController.isRTL
        main = FrameLayout(context)
        addView(main, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, mainHeightDp))

        handle = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.list_reorder)
            colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_stickers_menu), PorterDuff.Mode.MULTIPLY)
            contentDescription = LocaleController.getString(R.string.FilterReorder)
            isClickable = true
        }
        main.addView(handle, LayoutHelper.createFrame(48, 48f, (if (rtl) Gravity.RIGHT else Gravity.LEFT) or Gravity.CENTER_VERTICAL, 4f, 0f, 4f, 0f))

        icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY)
        }
        main.addView(icon, LayoutHelper.createFrame(24, 24f, (if (rtl) Gravity.RIGHT else Gravity.LEFT) or Gravity.CENTER_VERTICAL, 60f, 0f, 60f, 0f))

        text = TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            textSize = 16f
            setSingleLine(true)
            gravity = (if (rtl) Gravity.RIGHT else Gravity.LEFT) or Gravity.CENTER_VERTICAL
        }
        main.addView(
            text,
            LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT.toFloat(),
                (if (rtl) Gravity.RIGHT else Gravity.LEFT) or Gravity.CENTER_VERTICAL,
                if (rtl) 70f else 96f,
                0f,
                if (rtl) 96f else 70f,
                0f
            )
        )

        switch = Switch(context).apply {
            setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite)
        }
        main.addView(
            switch,
            // 24dp tall so the MD3 switch track (~22dp) fits without clipping
            LayoutHelper.createFrame(37, 24f, (if (rtl) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL, 22f, 0f, 22f, 0f)
        )

        moveBackButton = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.msg_go_up)
            colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY)
            background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP)
            contentDescription = LocaleController.getString(R.string.InuMenuMoveFromBottomRow)
            visibility = View.GONE
        }
        main.addView(
            moveBackButton,
            LayoutHelper.createFrame(36, 36f, (if (rtl) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL, 16f, 0f, 16f, 0f)
        )
    }

    fun bind(item: MenuOrderItem) {
        icon.setImageResource(item.iconRes)
        text.text = LocaleController.getString(item.labelRes)
    }

    fun setChecked(checked: Boolean) {
        switch.setChecked(checked, isAttachedToWindow)
    }

    fun setSwitchVisible(visible: Boolean) {
        switch.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setMoveBackButton(onClick: (() -> Unit)?) {
        if (onClick == null) {
            moveBackButton.visibility = View.GONE
            moveBackButton.setOnClickListener(null)
            moveBackButton.isClickable = false
        } else {
            moveBackButton.visibility = View.VISIBLE
            moveBackButton.setOnClickListener { onClick() }
        }
    }

    fun setOnReorderTouchListener(listener: View.OnTouchListener) {
        handle.setOnTouchListener(listener)
    }

    fun setSubCell(label: CharSequence, value: CharSequence): TextCell {
        val cell = sub ?: TextCell(context).also {
            sub = it
            it.setPrioritizeTitleOverValue(true)
            val wrapper = FrameLayout(context).apply {
                setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
                setPadding(AndroidUtilities.dp(SUB_LEFT_OFFSET_DP.toFloat()), 0, 0, 0)
                addView(it, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
            }
            subWrapper = wrapper
            addView(wrapper, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, subHeightDp))
        }
        cell.setTextAndValue(label, value, false, false)
        return cell
    }

    fun clearSubCell() {
        subWrapper?.let { removeView(it) }
        subWrapper = null
        sub = null
    }

    fun getSubAnchor(): View? = subWrapper

    fun isInSubCell(y: Float): Boolean = sub != null && y >= AndroidUtilities.dp(mainHeightDp.toFloat())

    companion object {
        private const val SUB_LEFT_OFFSET_DP = 40
    }
}
