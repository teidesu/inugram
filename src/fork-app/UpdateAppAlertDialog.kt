package desu.inugram

import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.helpers.update.UpdateHelper
import desu.inugram.ui.settings.SheetButtonsView
import desu.inugram.ui.settings.createSheetButton
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.DocumentObject
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.RichMessageLayout
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.BottomSheetWithRecyclerListView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class UpdateAppAlertDialog(
    context: Context,
    appUpdate: TLRPC.TL_help_appUpdate,
    accountNum: Int,
) : BottomSheetWithRecyclerListView(
    context, null, false, true, false, false, false, ActionBarType.SLIDING, null,
) {
    private lateinit var adapter: UniversalAdapter

    // nullable: UniversalAdapter fills from super(), before this is assigned
    private val rows: List<View>? = createRows(context, appUpdate, accountNum)

    init {
        setCanceledOnTouchOutside(false)
        ignoreTouchActionBar = false
        // the collapsed sheet has no title row: the content carries its own
        headerMoveTop = headerHeight + headerPaddingTop + headerPaddingBottom

        recyclerListView.setPadding(backgroundPaddingLeft, 0, backgroundPaddingLeft, dp(76f) + AndroidUtilities.navigationBarHeight)
        recyclerListView.clipToPadding = false

        val scheduleButton = createSheetButton(
            context,
            LocaleController.getString(R.string.AppUpdateRemindMeLater),
            background = Theme.createSimpleSelectorRoundRectDrawable(dp(24f), 0, Theme.getColor(Theme.key_dialogButtonSelector)),
            textColor = Theme.getColor(Theme.key_dialogTextBlack),
            bold = false,
        ) {
            UpdateHelper.clearPending()
            dismiss()
        }
        val doneButton = createSheetButton(
            context,
            LocaleController.getString(R.string.AppUpdateDownloadNow),
            background = Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 24f),
            textColor = Theme.getColor(Theme.key_featuredStickers_buttonText),
            bold = true,
        ) {
            UpdateHelper.startDownload(accountNum)
            dismiss()
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(scheduleButton, LinearLayout.LayoutParams(0, LayoutHelper.MATCH_PARENT, 1f).apply { marginEnd = dp(8f) })
            addView(doneButton, LinearLayout.LayoutParams(0, LayoutHelper.MATCH_PARENT, 1f))
        }
        val buttons = SheetButtonsView(context, fadeBackground = false)
        buttons.addView(
            row,
            LayoutHelper.createFrameMarginPx(
                LayoutHelper.MATCH_PARENT, 48f, Gravity.BOTTOM,
                dp(12f) + backgroundPaddingLeft, 0,
                dp(12f) + backgroundPaddingLeft,
                dp(12f) + AndroidUtilities.navigationBarHeight,
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

        adapter.update(false)
    }

    override fun onPreMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onPreMeasure(widthMeasureSpec, heightMeasureSpec)
        containerView.minimumHeight = View.MeasureSpec.getSize(heightMeasureSpec)
    }

    override fun onActionBarAlpha(alpha: Float) {
        actionBar.titleTextView.alpha = alpha
    }

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.AppUpdate)

    override fun createAdapter(listView: RecyclerListView): RecyclerListView.SelectionAdapter {
        adapter = UniversalAdapter(recyclerListView, context, currentAccount, 0, false, { items, _ ->
            rows?.forEach { items.add(UItem.asCustom(it)) }
        }, resourcesProvider)
        adapter.setApplyBackground(false)
        return adapter
    }

    private fun createRows(context: Context, appUpdate: TLRPC.TL_help_appUpdate, accountNum: Int): List<View> {
        val rows = ArrayList<View>()

        appUpdate.sticker?.let { sticker ->
            val imageView = BackupImageView(context)
            val docLocation = ImageLocation.getForDocument(sticker)
            val svgThumb = DocumentObject.getSvgThumb(sticker.thumbs, Theme.key_windowBackgroundGray, 1.0f)
            if (svgThumb != null) {
                imageView.setImage(docLocation, "250_250", svgThumb, 0, "update")
            } else {
                val thumb = FileLoader.getClosestPhotoSizeWithSize(sticker.thumbs, 90)
                imageView.setImage(docLocation, "250_250", ImageLocation.getForDocument(thumb, sticker), null, 0, "update")
            }
            rows.add(FrameLayout(context).apply {
                addView(imageView, LayoutHelper.createFrame(160, 160f, Gravity.CENTER_HORIZONTAL, 17f, 8f, 17f, 0f))
            })
        }

        rows.add(TextView(context).apply {
            typeface = AndroidUtilities.bold()
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            text = LocaleController.getString(R.string.AppUpdate)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(23f), dp(16f), dp(23f), 0)
        })

        rows.add(TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            text = LocaleController.formatString(
                R.string.AppUpdateVersionAndSize,
                appUpdate.version,
                AndroidUtilities.formatFileSize(appUpdate.document.size),
            )
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(23f), 0, dp(23f), dp(5f))
        })

        val changelog = UpdateHelper.getChangelog(appUpdate)
        rows.add(
            if (changelog != null && changelog.blocks.isNotEmpty()) {
                RichMessageLayout.PreviewView(context, accountNum, null).apply {
                    set(changelog)
                    setPadding(dp(23f), dp(15f), dp(23f), dp(8f))
                }
            } else {
                TextView(context).apply {
                    setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                    text = AndroidUtilities.replaceTags(LocaleController.getString(R.string.AppUpdateChangelogEmpty))
                    setPadding(dp(23f), dp(15f), dp(23f), dp(8f))
                }
            },
        )
        return rows
    }

    override fun onOpenAnimationEnd() {
        super.onOpenAnimationEnd()
        UpdateHelper.revealPendingUpdate()
    }
}
