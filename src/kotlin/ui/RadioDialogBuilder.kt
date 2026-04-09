package desu.inugram.ui

import android.content.Context
import android.content.DialogInterface
import android.widget.LinearLayout
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.RadioColorCell

class RadioDialogBuilder(
    context: Context,
    private val resourcesProvider: Theme.ResourcesProvider? = null,
) {
    private val builder = AlertDialog.Builder(context, resourcesProvider)
    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    private var items: List<Item> = emptyList()
    private var selectedIndex = -1
    private var onClick: ((DialogInterface, Int) -> Unit)? = null
    private var built = false

    data class Item(
        val text: CharSequence,
        val value: CharSequence? = null,
    )

    fun setTitle(title: CharSequence): RadioDialogBuilder = apply {
        builder.setTitle(title)
    }

    fun setMessage(message: CharSequence): RadioDialogBuilder = apply {
        builder.setMessage(message)
    }

    fun setSubtitle(message: CharSequence): RadioDialogBuilder = apply {
        builder.setSubtitle(message)
    }

    fun setPositiveButton(text: CharSequence, listener: AlertDialog.OnButtonClickListener?): RadioDialogBuilder =
        apply {
            builder.setPositiveButton(text, listener)
        }

    fun setNegativeButton(text: CharSequence, listener: AlertDialog.OnButtonClickListener?): RadioDialogBuilder =
        apply {
            builder.setNegativeButton(text, listener)
        }

    fun setNeutralButton(text: CharSequence, listener: AlertDialog.OnButtonClickListener?): RadioDialogBuilder = apply {
        builder.setNeutralButton(text, listener)
    }

    fun setOnDismissListener(listener: DialogInterface.OnDismissListener): RadioDialogBuilder = apply {
        builder.setOnDismissListener(listener)
    }

    fun setItems(
        items: Array<CharSequence>,
        selectedIndex: Int,
        listener: (DialogInterface, Int) -> Unit
    ): RadioDialogBuilder =
        setItems(items.map { Item(it) }, selectedIndex, listener)

    fun setItems(items: List<Item>, selectedIndex: Int, listener: (DialogInterface, Int) -> Unit): RadioDialogBuilder =
        apply {
            this.items = items
            this.selectedIndex = selectedIndex
            this.onClick = listener
            built = false
        }

    fun create(): AlertDialog {
        if (!built) {
            buildItems()
            builder.setView(container)
            built = true
        }
        return builder.create()
    }

    fun show(): AlertDialog {
        val dialog = create()
        dialog.show()
        return dialog
    }

    private fun buildItems() {
        container.removeAllViews()
        val dismiss = builder.dismissRunnable
        items.forEachIndexed { index, item ->
            val cell = RadioColorCell(builder.context, resourcesProvider)
            cell.setPadding(AndroidUtilities.dp(4f), 0, AndroidUtilities.dp(4f), 0)
            cell.tag = index
            cell.setCheckColor(
                Theme.getColor(Theme.key_radioBackground, resourcesProvider),
                Theme.getColor(Theme.key_dialogRadioBackgroundChecked, resourcesProvider),
            )
            if (item.value == null) {
                cell.setTextAndValue(item.text, selectedIndex == index)
            } else {
                cell.setTextAndText2AndValue(item.text, item.value, selectedIndex == index)
            }
            cell.background = Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_listSelector, resourcesProvider),
                Theme.RIPPLE_MASK_ALL
            )
            cell.setOnClickListener {
                val dialog = create()
                dismiss.run()
                onClick?.invoke(dialog, index)
            }
            container.addView(cell)
        }
    }
}
