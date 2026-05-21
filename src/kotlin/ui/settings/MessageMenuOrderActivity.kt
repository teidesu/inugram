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
import android.widget.TextView
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.MessageMenuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCell
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.Switch
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class MessageMenuOrderActivity : SettingsPageActivity() {
    private var entries = InuConfig.MESSAGE_MENU_ITEMS.value.toMutableList()
    private var reorderSectionId = -1
    private val rows = HashMap<MessageMenuConfig.Item, MessageMenuOrderRow>()

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuMessageMenuOrder)

    override fun createView(context: Context): View {
        val view = super.createView(context)
        listView.inu_longPressDragEnabled = false
        listView.listenReorder { id, items ->
            if (id != reorderSectionId) return@listenReorder
            val byItem = entries.associateBy { it.item }
            val newOrder = ArrayList<MessageMenuConfig.Entry>(items.size)
            for (i in items) {
                val key = i.`object` as? MessageMenuConfig.Item ?: continue
                val existing = byItem[key] ?: continue
                newOrder.add(existing)
            }
            if (newOrder.size == entries.size) {
                entries = newOrder
                InuConfig.MESSAGE_MENU_ITEMS.value = entries
            }
        }
        listView.allowReorder(true)
        return view
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuMessageMenuOrderInfo)))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuMessageMenuItems)))
        reorderSectionId = adapter.reorderSectionStart()
        for (entry in entries) {
            val row = rows.getOrPut(entry.item) {
                val r = MessageMenuOrderRow(context)
                r.bind(entry.item)
                r.setOnReorderTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        val holder = listView.findContainingViewHolder(r) ?: return@setOnReorderTouchListener false
                        listView.itemTouchHelper.startDrag(holder)
                    }
                    false
                }
                r
            }
            row.setChecked(entry.enabled)
            val subValue = subCellValue(entry.item)
            val heightDp = if (subValue != null) {
                row.setSubCell(LocaleController.getString(R.string.InuLongTapAction), subValue)
                row.mainHeightDp + row.subHeightDp
            } else {
                row.clearSubCell()
                row.mainHeightDp
            }
            val u = UItem.asCustom(row, heightDp)
            u.id = ITEM_BASE + entry.item.ordinal
            u.`object` = entry.item
            items.add(u)
        }
        adapter.reorderSectionEnd()
        items.add(UItem.asShadow(null))

        items.add(
            UItem.asButton(
                BUTTON_RESET,
                R.drawable.msg_reset,
                LocaleController.getString(R.string.InuMessageMenuReset)
            )
        )
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_RESET -> {
                InuConfig.MESSAGE_MENU_ITEMS.resetToDefault()
                entries = MessageMenuConfig.DEFAULT.toMutableList()
                listView.adapter.update(true)
            }

            else -> {
                val key = item.`object` as? MessageMenuConfig.Item ?: return
                val row = rows[key]
                if (row != null && row.isInSubCell(y)) {
                    showLongTapPicker(key, row)
                    return
                }
                val idx = entries.indexOfFirst { it.item == key }
                if (idx < 0) return
                entries[idx] = entries[idx].copy(enabled = !entries[idx].enabled)
                InuConfig.MESSAGE_MENU_ITEMS.value = entries
                row?.setChecked(entries[idx].enabled)
            }
        }
    }

    private fun subCellValue(item: MessageMenuConfig.Item): CharSequence? {
        val cfg = LONG_TAP_CONFIGS[item] ?: return null
        val labelRes = cfg.options.firstOrNull { it.first == cfg.getter() }?.second
            ?: cfg.options.first().second
        return LocaleController.getString(labelRes)
    }

    private fun showLongTapPicker(item: MessageMenuConfig.Item, row: MessageMenuOrderRow) {
        val cfg = LONG_TAP_CONFIGS[item] ?: return
        val current = cfg.options.indexOfFirst { it.first == cfg.getter() }.coerceAtLeast(0)
        val anchor = row.getSubAnchor() ?: row
        RadioItemOptions.show(
            this, anchor,
            cfg.options.map { LocaleController.getString(it.second) },
            current,
        ) { which ->
            val (value, labelRes) = cfg.options.getOrNull(which) ?: return@show
            cfg.setter(value)
            row.setSubCell(
                LocaleController.getString(R.string.InuLongTapAction),
                LocaleController.getString(labelRes)
            )
        }
    }

    companion object {
        private val BUTTON_RESET = InuUtils.generateId()
        private const val ITEM_BASE = 10000

        private class LongTapConfig(
            val options: List<Pair<Int, Int>>,
            val getter: () -> Int,
            val setter: (Int) -> Unit,
        )

        private val LONG_TAP_CONFIGS: Map<MessageMenuConfig.Item, LongTapConfig> = mapOf(
            MessageMenuConfig.Item.FORWARD to LongTapConfig(
                listOf(
                    InuConfig.ForwardLongTapItem.OFF to R.string.InuForwardLongTapOff,
                    InuConfig.ForwardLongTapItem.CHOOSE_MODE to R.string.InuLongTapChooseMode,
                    InuConfig.ForwardLongTapItem.WITHOUT_AUTHOR to R.string.InuForwardWithoutAuthor,
                    InuConfig.ForwardLongTapItem.WITHOUT_CAPTION to R.string.InuForwardWithoutCaption,
                ),
                { InuConfig.FORWARD_LONG_TAP_ACTION.value },
                { InuConfig.FORWARD_LONG_TAP_ACTION.value = it },
            ),
            MessageMenuConfig.Item.REPLY to LongTapConfig(
                listOf(
                    InuConfig.ReplyLongTapItem.OFF to R.string.InuForwardLongTapOff,
                    InuConfig.ReplyLongTapItem.CHOOSE_MODE to R.string.InuLongTapChooseMode,
                    InuConfig.ReplyLongTapItem.REPLY_IN to R.string.InuReplyIn,
                    InuConfig.ReplyLongTapItem.REPLY_IN_DMS to R.string.InuReplyInDms,
                ),
                { InuConfig.REPLY_LONG_TAP_ACTION.value },
                { InuConfig.REPLY_LONG_TAP_ACTION.value = it },
            ),
        )
    }
}

@SuppressLint("ViewConstructor")
class MessageMenuOrderRow(context: Context) : android.widget.LinearLayout(context) {
    private val handle: ImageView
    private val icon: ImageView
    private val text: TextView
    private val switch: Switch
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
    }

    fun bind(item: MessageMenuConfig.Item) {
        icon.setImageResource(item.iconRes)
        text.text = LocaleController.getString(item.labelRes)
    }

    fun setChecked(checked: Boolean) {
        switch.setChecked(checked, isAttachedToWindow)
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
