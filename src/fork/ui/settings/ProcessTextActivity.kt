package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.ProcessTextConfig
import desu.inugram.helpers.ProcessTextHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class ProcessTextActivity : SettingsPageActivity() {
    private var actions = ProcessTextHelper.getOrderedActions().toMutableList()
    private val rows = HashMap<String, MenuOrderRow>()
    private val ids = HashMap<String, Int>()
    private var reorderSectionId = -1

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuSelectionProcessText)

    override fun createView(context: Context): View {
        val view = super.createView(context)
        listView.setReorderLongPressEnabled(false)
        listView.listenReorder { id, items ->
            if (id != reorderSectionId) return@listenReorder
            val byKey = actions.associateBy { it.first.key }
            val reordered = items.mapNotNull { byKey[it.`object` as? String] }
            if (reordered.size != actions.size) return@listenReorder
            actions = reordered.toMutableList()
            save()
        }
        listView.allowReorder(true)
        return view
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            UItem.asRippleCheck(TOGGLE_ENABLED, LocaleController.getString(R.string.Enable))
                .setChecked(InuConfig.SELECTION_PROCESS_TEXT.value)
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuSelectionProcessTextInfo)))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuSelectionProcessTextItems)))
        reorderSectionId = adapter.reorderSectionStart()
        for ((action, enabled) in actions) items.add(buildRow(action, enabled))
        adapter.reorderSectionEnd()
        items.add(UItem.asShadow(null))

        items.add(
            UItem.asButton(
                BUTTON_RESET,
                R.drawable.msg_reset,
                LocaleController.getString(R.string.InuSelectionProcessTextReset)
            )
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildRow(action: ProcessTextHelper.Action, enabled: Boolean): UItem {
        val row = rows.getOrPut(action.key) {
            MenuOrderRow(context).apply {
                bind(action.label, action.loadIcon(), R.drawable.msg_search)
                setOnReorderTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        val holder = listView.findContainingViewHolder(this) ?: return@setOnReorderTouchListener false
                        listView.itemTouchHelper.startDrag(holder)
                    }
                    false
                }
            }
        }
        row.setChecked(enabled)
        return UItem.asCustom(row, row.mainHeightDp).also {
            it.id = ids.getOrPut(action.key) { InuUtils.generateId() }
            it.`object` = action.key
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_ENABLED -> {
                val new = InuConfig.SELECTION_PROCESS_TEXT.toggle()
                (view as? TextCheckCell)?.setChecked(new)
                (view as? TextCheckCell)?.setBackgroundColorAnimated(
                    new,
                    Theme.getColor(if (new) Theme.key_windowBackgroundChecked else Theme.key_windowBackgroundUnchecked)
                )
            }

            BUTTON_RESET -> {
                InuConfig.SELECTION_PROCESS_TEXT_ITEMS.value = null
                actions = ProcessTextHelper.getOrderedActions().toMutableList()
                listView.adapter.update(true)
            }

            else -> {
                val key = item.`object` as? String ?: return
                val idx = actions.indexOfFirst { it.first.key == key }
                if (idx < 0) return
                actions[idx] = actions[idx].first to !actions[idx].second
                rows[key]?.setChecked(actions[idx].second)
                save()
            }
        }
    }

    private fun save() {
        val visible = actions.map { ProcessTextConfig.Entry(it.first.key, it.second) }
        val keys = visible.mapTo(HashSet()) { it.key }
        val uninstalled = InuConfig.SELECTION_PROCESS_TEXT_ITEMS.value.orEmpty().filter { it.key !in keys }
        InuConfig.SELECTION_PROCESS_TEXT_ITEMS.value = visible + uninstalled
    }

    companion object {
        private val TOGGLE_ENABLED = InuUtils.generateId()
        private val BUTTON_RESET = InuUtils.generateId()
    }
}
