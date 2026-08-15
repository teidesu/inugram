package desu.inugram.ui.settings

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.helpers.plugins.Plugin
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.CodeHighlighting
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BottomSheetWithRecyclerListView
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView

class PluginSourceSheet(context: Context, private val plugin: Plugin) :
    BottomSheetWithRecyclerListView(context, null, false, false, false, null) {

    init {
        fixNavigationBar()
    }

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuPluginSourceTitle)

    override fun createAdapter(listView: RecyclerListView): RecyclerListView.SelectionAdapter = Adapter()

    private fun buildHeader(context: Context): View {
        val source = plugin.source
        val title = TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            typeface = AndroidUtilities.bold()
            text = LocaleController.getString(R.string.InuPluginSourceTitle)
        }
        val meta = TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            text = listOf(
                plugin.manifest.name,
                AndroidUtilities.formatFileSize(source.toByteArray().size.toLong()),
            ).joinToString(" · ")
        }
        val titleBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(meta, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f))
        }
        val copyButton = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.msg_copy)
            setColorFilter(PorterDuffColorFilter(Theme.getColor(Theme.key_dialogTextGray3), PorterDuff.Mode.SRC_IN))
            background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP)
            contentDescription = LocaleController.getString(R.string.Copy)
            setOnClickListener {
                AndroidUtilities.addToClipboard(source)
                BulletinFactory.of(container, resourcesProvider)
                    .createCopyBulletin(LocaleController.getString(R.string.TextCopied))
                    .show()
            }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(AndroidUtilities.dp(22f), AndroidUtilities.dp(18f), AndroidUtilities.dp(16f), AndroidUtilities.dp(10f))
            addView(titleBlock, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))
            addView(copyButton, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 12, 0, 0, 0))
        }
    }

    private fun buildCode(context: Context): View {
        val source = plugin.source
        val code = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextIsSelectable(true)
            setHorizontallyScrolling(true)
            setPadding(AndroidUtilities.dp(22f), AndroidUtilities.dp(4f), AndroidUtilities.dp(22f), AndroidUtilities.dp(16f))
            text = source
        }
        if (source.length <= HIGHLIGHT_MAX_LENGTH) {
            CodeHighlighting.highlightEditable(source, "javascript") { highlighted ->
                code.text = highlighted
            }
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(code, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat()))
        }
    }

    private inner class Adapter : RecyclerListView.SelectionAdapter() {
        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = false

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = if (viewType == 0) buildHeader(parent.context) else buildCode(parent.context)
            view.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
            return RecyclerListView.Holder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}

        override fun getItemViewType(position: Int): Int = position

        override fun getItemCount(): Int = 2
    }

    companion object {
        private const val HIGHLIGHT_MAX_LENGTH = 200_000
    }
}
