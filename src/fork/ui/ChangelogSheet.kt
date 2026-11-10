package desu.inugram.ui

import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.ui.settings.SheetButtonsView
import desu.inugram.ui.settings.createSheetButton
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BottomSheetWithRecyclerListView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

open class ChangelogSheet(
    context: Context,
    title: CharSequence,
    rows: List<View>,
    secondaryText: CharSequence,
    onSecondary: () -> Unit,
    primaryText: CharSequence,
    onPrimary: () -> Unit,
) : BottomSheetWithRecyclerListView(
    context, null, false, false, false, false, ActionBarType.SLIDING, null,
) {
    private lateinit var adapter: UniversalAdapter

    // nullable: super() reads both before they are assigned
    private val title: CharSequence? = title
    private val rows: List<View>? = listOf(TextView(context).apply {
        typeface = AndroidUtilities.bold()
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
        setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        text = title
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(23f), dp(16f), dp(23f), 0)
    }) + rows

    init {
        setCanceledOnTouchOutside(false)
        ignoreTouchActionBar = false
        // the collapsed sheet has no title row: the content carries its own
        headerMoveTop = headerHeight + headerPaddingTop + headerPaddingBottom

        recyclerListView.setPadding(backgroundPaddingLeft, 0, backgroundPaddingLeft, dp(76f))
        recyclerListView.clipToPadding = false

        val secondaryButton = createSheetButton(
            context,
            secondaryText,
            background = Theme.createSimpleSelectorRoundRectDrawable(dp(24f), 0, Theme.getColor(Theme.key_dialogButtonSelector)),
            textColor = Theme.getColor(Theme.key_dialogTextBlack),
            bold = false,
        ) {
            onSecondary()
            dismiss()
        }
        val primaryButton = createSheetButton(
            context,
            primaryText,
            background = Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 24f),
            textColor = Theme.getColor(Theme.key_featuredStickers_buttonText),
            bold = true,
        ) {
            onPrimary()
            dismiss()
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(secondaryButton, LinearLayout.LayoutParams(0, LayoutHelper.MATCH_PARENT, 1f).apply { marginEnd = dp(8f) })
            addView(primaryButton, LinearLayout.LayoutParams(0, LayoutHelper.MATCH_PARENT, 1f))
        }
        val buttons = SheetButtonsView(context, fadeBackground = false)
        buttons.addView(
            row,
            LayoutHelper.createFrameMarginPx(
                LayoutHelper.MATCH_PARENT, 48f, Gravity.BOTTOM,
                dp(12f) + backgroundPaddingLeft, 0,
                dp(12f) + backgroundPaddingLeft,
                dp(12f),
            ),
        )
        containerView.addView(
            buttons,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM),
        )
        val updateCovering = { buttons.setCovering(recyclerListView.canScrollVertically(1)) }
        recyclerListView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updateCovering()
        })
        recyclerListView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateCovering() }

        updateTitle()
        adapter.update(false)
    }

    override fun onPreMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onPreMeasure(widthMeasureSpec, heightMeasureSpec)
        containerView.minimumHeight = View.MeasureSpec.getSize(heightMeasureSpec)
    }

    override fun onActionBarAlpha(alpha: Float) {
        actionBar.titleTextView.alpha = alpha
    }

    override fun getTitle(): CharSequence = title ?: ""

    override fun createAdapter(listView: RecyclerListView): RecyclerListView.SelectionAdapter {
        adapter = UniversalAdapter(recyclerListView, context, currentAccount, 0, false, { items, _ ->
            rows?.forEach { items.add(UItem.asCustom(it)) }
        }, resourcesProvider)
        adapter.setApplyBackground(false)
        return adapter
    }
}
