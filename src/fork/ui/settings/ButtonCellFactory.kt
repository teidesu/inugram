package desu.inugram.ui.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import org.telegram.messenger.Emoji
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
open class ButtonCellFactory : UItem.UItemFactory<TextCell>() {
    companion object {
        init {
            setup(ButtonCellFactory())
        }

        /**
         * [icon] is an `icons.rs` spec, resolved at bind time so it follows theme + icon pack.
         *
         * [formatting] stands in for the spans the texts carry: `TextUtils.equals` compares
         * characters only and reports two CharSequences equal however differently they are spanned,
         * so entities that changed under unchanged text would otherwise never reach [contentsEquals].
         */
        fun of(
            id: Int,
            text: CharSequence,
            value: CharSequence?,
            subtitle: CharSequence?,
            icon: ButtonIcon?,
            danger: Boolean,
            formatting: String?,
        ): UItem =
            UItem.ofFactory(ButtonCellFactory::class.java).apply {
                this.id = id
                this.text = text
                this.textValue = value
                this.subtext = subtitle
                this.`object` = icon
                this.object2 = formatting
                this.red = danger
            }
    }

    protected open val needsCheckBox = false

    override fun createView(
        context: Context,
        listView: RecyclerListView?,
        currentAccount: Int,
        classGuid: Int,
        resourcesProvider: Theme.ResourcesProvider?,
    ): TextCell = TextCell(context, 23, false, needsCheckBox, resourcesProvider)

    override fun bindView(
        view: View,
        item: UItem,
        divider: Boolean,
        adapter: UniversalAdapter?,
        listView: UniversalRecyclerView?,
    ) {
        val cell = view as TextCell
        val sameRow = cell.tag == item.id
        // fix oversized emojis
        resizeEmoji(item.text, cell.textView.paint)
        resizeEmoji(item.subtext, cell.subtitleView.paint)
        resizeEmoji(item.textValue, cell.valueTextView.paint)
        // resolved here rather than in the model: this is the ui thread and this cell's own
        // context, so the drawable follows the current theme and icon pack without the model
        // knowing either existed. the icon setter drops the cell's colour filter, which the
        // setColors below puts back - that is what tints it to the row
        val icon = item.`object` as? ButtonIcon
        if (icon == null) {
            PluginIcons.clearIcon(cell.imageView)
            cell.setTextAndValue(item.text, item.textValue, sameRow, divider)
        } else {
            cell.setTextAndValueAndIcon(item.text, item.textValue, ColorDrawable(Color.TRANSPARENT), divider)
            if (!PluginIcons.setIcon(cell.imageView, icon.spec, icon.engine)) {
                cell.setTextAndValue(item.text, item.textValue, sameRow, divider)
            }
        }
        // fix transparent links
        val linkColor = Theme.getColor(Theme.key_windowBackgroundWhiteLinkText)
        cell.textView.setLinkTextColor(linkColor)
        cell.subtitleView.setLinkTextColor(linkColor)
        cell.valueTextView.paint.linkColor = linkColor
        cell.setSubtitle(item.subtext)
        if (item.red) {
            cell.setColors(Theme.key_text_RedBold, Theme.key_text_RedRegular)
        } else {
            cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText)
        }
        cell.checkBox?.let {
            it.visibility = View.VISIBLE
            it.setChecked(item.checked, sameRow)
        }
        cell.setEnabled(item.enabled, sameRow)
        cell.tag = item.id
    }

    private fun resizeEmoji(text: CharSequence?, paint: TextPaint) {
        if (text !is Spanned) return
        val metrics = paint.fontMetricsInt
        for (span in text.getSpans(0, text.length, Emoji.EmojiSpan::class.java)) {
            span.replaceFontMetrics(metrics)
        }
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
            a.object2 == b.object2 &&
            a.red == b.red &&
            a.checked == b.checked
    }
}

/** [ButtonCellFactory] with stock's switch on the right, so a check row lines up with a button one */
class CheckCellFactory : ButtonCellFactory() {
    companion object {
        init {
            setup(CheckCellFactory())
        }

        fun of(id: Int, text: CharSequence, subtitle: CharSequence?, icon: ButtonIcon?, checked: Boolean, formatting: String?): UItem =
            UItem.ofFactory(CheckCellFactory::class.java).apply {
                this.id = id
                this.text = text
                this.subtext = subtitle
                this.`object` = icon
                this.object2 = formatting
                this.checked = checked
            }
    }

    override val needsCheckBox = true
}
