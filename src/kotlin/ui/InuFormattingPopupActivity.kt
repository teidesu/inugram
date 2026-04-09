package desu.inugram.ui

import android.content.Context
import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.FormattingPopupConfig
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class InuFormattingPopupActivity : InuSettingsPageActivity() {
    private var entries = InuConfig.FORMATTING_POPUP_ITEMS.value.toMutableList()
    private var reorderSectionId = -1

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuFormattingPopup)

    override fun createView(context: Context): View {
        val view = super.createView(context)
        listView.listenReorder { id, items ->
            if (id != reorderSectionId) return@listenReorder
            val byItem = entries.associateBy { it.item }
            val newOrder = ArrayList<FormattingPopupConfig.Entry>(items.size)
            for (i in items) {
                val key = i.`object` as? FormattingPopupConfig.Item ?: continue
                val existing = byItem[key] ?: continue
                newOrder.add(existing)
            }
            if (newOrder.size == entries.size) {
                entries = newOrder
                InuConfig.FORMATTING_POPUP_ITEMS.value = entries
            }
        }
        listView.allowReorder(true)
        return view
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            UItem.asRippleCheck(TOGGLE_ENABLED, LocaleController.getString(R.string.Enable))
                .setChecked(InuConfig.FORMATTING_POPUP.value)
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuFormattingPopupItemsInfo)))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuFormattingPopupItems)))
        reorderSectionId = adapter.reorderSectionStart()
        for (entry in entries) {
            val u = UItem.asCheck(ITEM_BASE + entry.item.ordinal, LocaleController.getString(entry.item.labelRes))
                .setChecked(entry.enabled)
            u.`object` = entry.item
            items.add(u)
        }
        adapter.reorderSectionEnd()
        items.add(UItem.asShadow(null))

        items.add(
            UItem.asButton(
                BUTTON_RESET,
                R.drawable.msg_reset,
                LocaleController.getString(R.string.InuFormattingPopupReset)
            )
        )
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_ENABLED -> {
                val new = InuConfig.FORMATTING_POPUP.toggle()
                (view as? TextCheckCell)?.setChecked(new)
                (view as? TextCheckCell)?.setBackgroundColorAnimated(
                    new,
                    Theme.getColor(if (new) Theme.key_windowBackgroundChecked else Theme.key_windowBackgroundUnchecked)
                )
            }

            BUTTON_RESET -> {
                InuConfig.FORMATTING_POPUP_ITEMS.resetToDefault()
                entries = FormattingPopupConfig.DEFAULT.toMutableList()
                listView.adapter.update(true)
            }

            else -> {
                val key = item.`object` as? FormattingPopupConfig.Item ?: return
                val idx = entries.indexOfFirst { it.item == key }
                if (idx < 0) return
                entries[idx] = entries[idx].copy(enabled = !entries[idx].enabled)
                InuConfig.FORMATTING_POPUP_ITEMS.value = entries
                listView.adapter.update(true)
            }
        }
    }

    companion object {
        private val TOGGLE_ENABLED = InuUtils.generateId()
        private val BUTTON_RESET = InuUtils.generateId()
        private const val ITEM_BASE = 10000
    }
}
