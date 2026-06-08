package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.menu.MenuOrderEntry
import desu.inugram.helpers.menu.MessageMenuConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class MessageMenuOrderActivity : MenuOrderActivity<MessageMenuConfig.Item>() {
    override val config get() = InuConfig.MESSAGE_MENU_ITEMS
    override val infoStringRes = R.string.InuMessageMenuOrderInfo
    override val headerStringRes = R.string.InuMessageMenuItems
    override val resetStringRes = R.string.InuMessageMenuReset

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuMessageMenuOrder)

    private fun bottomEnabled() = InuConfig.MESSAGE_MENU_BOTTOM_ROW.value

    // custom items parked in the bottom can't be hidden (only moved back); slots and main items can
    private fun canToggle(entry: MenuOrderEntry<MessageMenuConfig.Item>): Boolean =
        !entry.bottom || entry.item.isSlot

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        fillMainSection(items, adapter)

        items.add(UItem.asShadow(SHADOW_MID, LocaleController.getString(R.string.InuMessageMenuOrderInfoBottom)))
        items.add(UItem.asHeader(HEADER_BOTTOM, bottomGroupLabel()))
        items.add(
            mkTwoLineCheckItem(
                MASTER_TOGGLE_ID,
                R.string.InuMessageMenuBottomRow,
                R.string.InuMessageMenuBottomRowInfo,
                bottomEnabled(),
            )
        )
        if (bottomEnabled()) {
            openReorderSection(adapter, toBottom = true)
            for (entry in entries.filter { it.bottom }) {
                items.add(buildRow(entry) { row ->
                    if (canToggle(entry)) return@buildRow
                    row.setSwitchVisible(false)
                    row.setMoveBackButton { moveItem(entry.item, false) }
                })
            }
            adapter.reorderSectionEnd()
        }

        fillResetSection(items, adapter)
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id == MASTER_TOGGLE_ID) {
            setBottomRowEnabled(!bottomEnabled())
            return
        }
        super.onClick(item, view, position, x, y)
    }

    override fun onRowToggle(entry: MenuOrderEntry<MessageMenuConfig.Item>, row: MenuOrderRow?) {
        if (!canToggle(entry)) return
        // bottom row is capped — block re-enabling a slot when there's no room
        if (!entry.enabled && entry.bottom && entries.count { it.bottom && it.enabled } >= MAX_BOTTOM) {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.InuMenuBottomRowFull)).show()
            return
        }
        super.onRowToggle(entry, row)
        // toggling a bottom slot changes the header's enabled count
        if (entry.bottom) listView.adapter.update(true)
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean {
        @Suppress("UNCHECKED_CAST")
        val key = item.`object` as? MessageMenuConfig.Item
            ?: return super.onLongClick(item, view, position, x, y)
        val entry = entries.firstOrNull { it.item == key } ?: return false

        // bottom items have a dedicated "move back" button (customs) or are pinned (slots) — no long-tap menu
        if (entry.bottom) return false

        // translate's behavior is wired through the vertical cell's click listener (lang detection),
        // so it can't run as a plain bottom-row button (todo maybe some crutch)
        if (!bottomEnabled() || key == MessageMenuConfig.Item.TRANSLATE) return false
        if (entries.count { it.bottom && it.enabled } >= MAX_BOTTOM) {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.InuMenuBottomRowFull)).show()
            return true
        }
        ItemOptions.makeOptions(this, view)
            .add(R.drawable.msg_go_down, LocaleController.getString(R.string.InuMenuMoveToBottomRow)) { moveItem(key, true) }
            .show()
        return true
    }

    override fun subCell(item: MessageMenuConfig.Item): SubCell? {
        val cfg = LONG_TAP_CONFIGS[item] ?: return null
        val labelRes = cfg.options.firstOrNull { it.first == cfg.getter() }?.second
            ?: cfg.options.first().second
        return SubCell(
            label = LocaleController.getString(R.string.InuLongTapAction),
            value = LocaleController.getString(labelRes),
            onClick = { row -> showLongTapPicker(cfg, row) },
        )
    }

    private fun showLongTapPicker(cfg: LongTapConfig, row: MenuOrderRow) {
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

    private fun setBottomRowEnabled(enabled: Boolean) {
        InuConfig.MESSAGE_MENU_BOTTOM_ROW.value = enabled
        if (!enabled) {
            // hidden bottom row can't host customs; send them back to main, preserving relative order
            entries = entries
                .map { if (it.bottom && !it.item.isSlot) it.copy(bottom = false) else it }
                .sortedBy { it.bottom }
                .toMutableList()
            config.value = entries
        }
        listView.adapter.update(true)
    }

    // moved item lands at the end of the target group; the two groups stay contiguous (main, bottom)
    private fun moveItem(key: MessageMenuConfig.Item, toBottom: Boolean) {
        val idx = entries.indexOfFirst { it.item == key }
        if (idx < 0) return
        // customs in the bottom row have no hide switch — force enabled to avoid stranding a hidden one
        val moved = entries[idx].copy(bottom = toBottom, enabled = true)
        val (main, bottom) = entries.filter { it.item != key }.partition { !it.bottom }
        entries = (if (toBottom) main + bottom + moved else main + moved + bottom).toMutableList()
        config.value = entries
        listView.adapter.update(true)
    }

    private fun bottomGroupLabel(): CharSequence {
        val title = LocaleController.getString(R.string.InuMessageMenuBottomGroup)
        if (!bottomEnabled()) return title
        val count = entries.count { it.bottom && it.enabled }
        return "$title · $count/$MAX_BOTTOM"
    }

    companion object {
        private val MASTER_TOGGLE_ID = InuUtils.generateId()
        // distinct from base's SHADOW_END so DiffUtil doesn't alias them on structural change
        private val SHADOW_MID = InuUtils.generateId()
        private val HEADER_BOTTOM = InuUtils.generateId()
        private const val MAX_BOTTOM = 4

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
