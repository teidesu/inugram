package desu.inugram.ui

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.CheckBoxCell
import org.telegram.ui.Cells.TextCheckCell2
import org.telegram.ui.Components.UItem

class ExpandableBoolGroup(
    val title: CharSequence,
    val options: List<Option>,
) {
    class Option(val labelRes: Int, val config: InuConfig.BoolItem) {
        val id: Int = InuUtils.generateId()
    }

    private val sectionId = InuUtils.generateId()
    var expanded = false

    fun addTo(items: ArrayList<UItem>, onChange: (options: List<Option>) -> Unit) {
        val checkedCount = options.count { it.config.value }
        val total = options.size
        items.add(
            UItem.asExpandableSwitch(sectionId, title, "$checkedCount/$total")
                .setChecked(checkedCount == total)
                .setCollapsed(!expanded)
                .setClickCallback {
                    val enable = checkedCount != total
                    val changed = arrayListOf<Option>()
                    for (opt in options) {
                        if (opt.config.value != enable) {
                            changed.add(opt)
                            opt.config.value = enable
                        }
                    }
                    onChange(changed)
                }
                .onBind(Utilities.Callback { view ->
                    val cell = view as? TextCheckCell2 ?: return@Callback
                    cell.checkBox.also {
                        it.setDrawIconType(0)
                        it.setColors(
                            Theme.key_switchTrack,
                            Theme.key_switchTrackChecked,
                            Theme.key_windowBackgroundWhite,
                            Theme.key_windowBackgroundWhite,
                        )
                    }
                })
        )
        if (expanded) {
            for (opt in options) {
                items.add(
                    UItem.asRoundCheckbox(opt.id, LocaleController.getString(opt.labelRes))
                        .setChecked(opt.config.value)
                        .setPad(1)
                )
            }
        }
    }

    fun handleClick(item: UItem, view: View, onChange: (option: Option?) -> Unit): Boolean {
        if (item.id == sectionId) {
            expanded = !expanded
            onChange(null)
            return true
        }
        val opt = options.firstOrNull { it.id == item.id } ?: return false
        val new = opt.config.toggle()
        (view as? CheckBoxCell)?.setChecked(new, true)
        onChange(opt)
        return true
    }
}
