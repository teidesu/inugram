package desu.inugram.ui.settings

import android.view.View
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Cells.TextCell
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.RecyclerListView

object RadioItemOptions {
    fun show(
        fragment: BaseFragment,
        anchor: View,
        items: List<CharSequence>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
    ) {
        val options = ItemOptions.makeOptions(fragment, anchor)
        (anchor.parent as? RecyclerListView)?.getClipBackground(anchor)?.let(options::setScrimViewBackground)
        items.forEachIndexed { index, text ->
            options.addChecked(index == selectedIndex, text) {
                if (index == selectedIndex) return@addChecked
                (anchor as? TextCell)?.setValue(text, true)
                onSelect(index)
            }
        }
        options.show()
    }
}
