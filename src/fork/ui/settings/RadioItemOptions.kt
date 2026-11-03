package desu.inugram.ui.settings

import android.view.View
import org.telegram.ui.Cells.TextCell
import org.telegram.ui.Components.ItemOptions

object RadioItemOptions {
    fun show(
        fragment: SettingsPageActivity,
        anchor: View,
        items: List<CharSequence>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
    ) {
        val options = ItemOptions.makeOptions(fragment, anchor).setLongPressSelectionEnabled(false)
        items.forEachIndexed { index, text ->
            options.addChecked(index == selectedIndex, text) {
                if (index == selectedIndex) return@addChecked
                (anchor as? TextCell)?.setValue(text, true)
                onSelect(index)
                fragment.listView.adapter.updateWithoutNotify()
            }
        }
        options.show()
    }
}
