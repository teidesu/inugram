package desu.inugram.ui.settings

import android.content.Context
import android.text.TextUtils
import android.view.View
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.ui.PluginIcons
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCell
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalRecyclerView

class ButtonIcon(val spec: String?, val engine: QuickJs)

/**
 * stock VIEW_TYPE_TEXT puts textValue into the differ's *identity* (itemEquals), so a value
 * change becomes remove+insert with a full-row crossfade. This factory keeps identity = id
 * only, so value/subtitle changes rebind the same holder in place, with the value animating
 * via TextCell's AnimatedTextView.
 */
class ButtonCellFactory : UItem.UItemFactory<TextCell>() {
    companion object {
        init {
            setup(ButtonCellFactory())
        }

        /** [icon] is an `icons.rs` spec, resolved at bind time so it follows theme + icon pack */
        fun of(id: Int, text: String, value: String?, subtitle: String?, icon: ButtonIcon?, danger: Boolean): UItem =
            UItem.ofFactory(ButtonCellFactory::class.java).apply {
                this.id = id
                this.text = text
                this.textValue = value
                this.subtext = subtitle
                this.`object` = icon
                this.red = danger
            }
    }

    override fun createView(
        context: Context,
        listView: RecyclerListView?,
        currentAccount: Int,
        classGuid: Int,
        resourcesProvider: Theme.ResourcesProvider?,
    ): TextCell = TextCell(context, resourcesProvider)

    override fun bindView(
        view: View,
        item: UItem,
        divider: Boolean,
        adapter: UniversalAdapter?,
        listView: UniversalRecyclerView?,
    ) {
        val cell = view as TextCell
        val sameRow = cell.tag == item.id
        // resolved here rather than in the model: this is the ui thread and this cell's own
        // context, so the drawable follows the current theme and icon pack without the model
        // knowing either existed. the icon setter drops the cell's colour filter, which the
        // setColors below puts back - that is what tints it to the row
        val icon = (item.`object` as? ButtonIcon)?.let { PluginIcons.resolveDrawable(cell.context, it.spec, it.engine) }
        if (icon == null) {
            cell.setTextAndValue(item.text, item.textValue, sameRow, divider)
        } else {
            cell.setTextAndValueAndIcon(item.text, item.textValue, icon, divider)
        }
        cell.setSubtitle(item.subtext)
        if (item.red) {
            cell.setColors(Theme.key_text_RedBold, Theme.key_text_RedRegular)
        } else {
            cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText)
        }
        cell.setEnabled(item.enabled, sameRow)
        cell.tag = item.id
    }

    override fun equals(a: UItem, b: UItem): Boolean = a.id == b.id

    override fun contentsEquals(a: UItem, b: UItem): Boolean {
        val left = a.`object` as? ButtonIcon
        val right = b.`object` as? ButtonIcon
        return TextUtils.equals(a.text, b.text) &&
            TextUtils.equals(a.textValue, b.textValue) &&
            TextUtils.equals(a.subtext, b.subtext) &&
            left?.spec == right?.spec &&
            left?.engine === right?.engine &&
            a.red == b.red
    }
}
