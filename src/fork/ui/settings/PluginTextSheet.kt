package desu.inugram.ui.settings

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.core.plugins.JsLiterals
import desu.inugram.core.plugins.PluginAssetsParser
import desu.inugram.helpers.plugins.PluginFailure
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BottomSheetWithRecyclerListView
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import org.telegram.utils.code.highlight.CodeHighlighting
import org.telegram.utils.code.highlight.PrismaTokenStyles

class PluginTextSheet private constructor(
    context: Context,
    private val sheetTitle: String,
    private val subtitle: String,
    private val copyText: String,
    private val displayText: String,
    highlightLanguage: String?,
    private val wrapLines: Boolean,
) : BottomSheetWithRecyclerListView(context, null, false, false, false, null) {

    init {
        fixNavigationBar()
        // the base constructor reads the title before our fields are assigned
        updateTitle()
    }

    override fun getTitle(): CharSequence? = sheetTitle

    private lateinit var adapter: Adapter

    override fun createAdapter(listView: RecyclerListView): RecyclerListView.SelectionAdapter = Adapter().also { adapter = it }

    private fun buildHeader(context: Context): View {
        val title = TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            typeface = AndroidUtilities.bold()
            text = sheetTitle
        }
        val meta = TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            text = subtitle
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
                AndroidUtilities.addToClipboard(copyText)
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

    private val chunks: List<IntRange> = run {
        val result = ArrayList<IntRange>()
        var chunkStart = 0
        var lines = 0
        var lineStart = 0
        while (true) {
            val newline = displayText.indexOf('\n', lineStart)
            val lineEnd = if (newline == -1) displayText.length else newline
            if (lineEnd - lineStart > MAX_LINE_LENGTH) {
                if (lineStart > chunkStart) result.add(chunkStart until lineStart - 1)
                var rowStart = lineStart
                while (rowStart < lineEnd) {
                    var rowEnd = minOf(rowStart + MAX_LINE_LENGTH, lineEnd)
                    if (rowEnd < lineEnd && displayText[rowEnd - 1].isHighSurrogate()) rowEnd--
                    result.add(rowStart until rowEnd)
                    rowStart = rowEnd
                }
                chunkStart = lineEnd + 1
                lines = 0
            } else if (++lines == CHUNK_LINES) {
                result.add(chunkStart until lineEnd)
                chunkStart = lineEnd + 1
                lines = 0
            }
            if (newline == -1) break
            lineStart = newline + 1
        }
        if (chunkStart < displayText.length || result.isEmpty()) result.add(chunkStart until displayText.length)
        result
    }
    private var highlightedChunks: List<CharSequence>? = null
    private var codeWidth = 0
    private var codeScrollX = 0

    init {
        if (highlightLanguage != null && displayText.length <= HIGHLIGHT_MAX_LENGTH) {
            CodeHighlighting.SERVICE.request(displayText, highlightLanguage) { result ->
                val ranges = result.tokens?.copyStyledRanges() ?: return@request
                Utilities.searchQueue.postRunnable {
                    var first = 0
                    val highlighted = chunks.map { range ->
                        val end = range.last + 1
                        val text = SpannableString(displayText.substring(range.first, end))
                        while (first < ranges.size && ranges[first + 1] <= range.first) first += 3
                        var i = first
                        while (i < ranges.size && ranges[i] < end) {
                            val from = maxOf(ranges[i], range.first) - range.first
                            val to = minOf(ranges[i + 1], end) - range.first
                            val flags = ranges[i + 2]
                            i += 3
                            if (from >= to) continue
                            val group = flags and PrismaTokenStyles.COLOR_MASK
                            if (group != 0) {
                                text.setSpan(CodeHighlighting.ColorSpan(group), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                            if (flags and (PrismaTokenStyles.BOLD or PrismaTokenStyles.ITALIC) != 0) {
                                text.setSpan(CodeHighlighting.SyntaxFontSpan(flags), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                        }
                        text
                    }
                    AndroidUtilities.runOnUIThread {
                        highlightedChunks = highlighted
                        adapter.notifyItemRangeChanged(1, chunks.size)
                    }
                }
            }
        }
    }

    private fun buildCode(context: Context): View {
        val code = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextIsSelectable(true)
        }
        if (wrapLines) return code
        code.setHorizontallyScrolling(true)
        if (codeWidth == 0) {
            codeWidth = chunks.maxOf { range ->
                displayText.subSequence(range.first, range.last + 1).lineSequence().maxOf { Layout.getDesiredWidth(it, code.paint) }
            }.toInt() + AndroidUtilities.dp(44f)
        }
        code.minWidth = codeWidth
        return object : HorizontalScrollView(context) {
            override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
                super.onScrollChanged(l, t, oldl, oldt)
                if (codeScrollX == l) return
                codeScrollX = l
                for (i in 0 until recyclerListView.childCount) {
                    val child = recyclerListView.getChildAt(i)
                    if (child is HorizontalScrollView && child !== this) child.scrollTo(l, 0)
                }
            }
        }.apply {
            isHorizontalScrollBarEnabled = false
            addView(code, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat()))
        }
    }

    private fun bindCode(view: View, index: Int) {
        val range = chunks[index]
        (if (view is HorizontalScrollView) view.getChildAt(0) as TextView else view as TextView).apply {
            setPadding(
                AndroidUtilities.dp(22f),
                if (index == 0) AndroidUtilities.dp(4f) else 0,
                AndroidUtilities.dp(22f),
                if (index == chunks.lastIndex) AndroidUtilities.dp(16f) else 0,
            )
            text = highlightedChunks?.get(index) ?: displayText.substring(range.first, range.last + 1)
        }
        view.scrollX = codeScrollX
    }

    private inner class Adapter : RecyclerListView.SelectionAdapter() {
        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = false

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = if (viewType == 0) buildHeader(parent.context) else buildCode(parent.context)
            view.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
            return RecyclerListView.Holder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (position > 0) bindCode(holder.itemView, position - 1)
        }

        override fun getItemViewType(position: Int): Int = if (position == 0) 0 else 1

        override fun getItemCount(): Int = 1 + chunks.size
    }

    companion object {
        private const val HIGHLIGHT_MAX_LENGTH = 200_000
        private const val CHUNK_LINES = 40
        private const val MAX_LINE_LENGTH = 1000

        fun createForSource(context: Context, pluginName: String, source: String) = PluginTextSheet(
            context,
            LocaleController.getString(R.string.InuPluginSourceTitle),
            "$pluginName · ${AndroidUtilities.formatFileSize(source.toByteArray().size.toLong())}",
            source,
            collapseSourceBlobs(source),
            "javascript",
            false,
        )

        fun createForFailure(context: Context, pluginName: String, failure: PluginFailure) = PluginTextSheet(
            context,
            LocaleController.getString(failure.at.labelRes ?: R.string.InuPluginsFailedAtLoad),
            pluginName,
            failure.detail,
            failure.detail,
            null,
            true,
        )

        private fun collapseSourceBlobs(source: String) = buildString {
            fun placeholder(size: Long) = "[…${AndroidUtilities.formatFileSize(size)}…]"
            val footer = PluginAssetsParser.findFooter(source)
            append(JsLiterals.collapseEncodedStrings(source.substring(0, footer), MAX_LINE_LENGTH) { placeholder(it.toLong()) })
            var last = footer
            val assets = runCatching { PluginAssetsParser.parse(source) }.getOrDefault(emptyList())
            for (asset in assets) {
                if (asset.start == asset.end) continue
                append(source, last, asset.start).append(placeholder(asset.size))
                last = asset.end
            }
            append(source, last, source.length)
        }
    }
}
