package desu.inugram.ui.settings

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.core.plugins.PluginManifest
import desu.inugram.core.plugins.SourceObfuscation
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCell
import org.telegram.ui.Components.BottomSheetWithRecyclerListView
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

/**
 * The confirmation shown before a plugin is written to disk: what it is, what it may do once it
 * runs, and how to read its code first. Deliberately the same rows as [PluginInfoActivity] - a user
 * who installs one and inspects it later should be looking at the same page.
 *
 * Installing is gated on having *seen* the permissions: while the list still has something below
 * the fold, the confirm button scrolls instead of installing.
 */
class PluginInstallSheet(
    context: Context,
    fragment: BaseFragment,
    manifest: PluginManifest,
    source: String,
    obfuscation: SourceObfuscation?,
    onInstall: (enable: Boolean) -> Unit,
) : BottomSheetWithRecyclerListView(
    context, fragment, false, true, false, false, false, ActionBarType.SLIDING, null,
) {
    private class Content(
        val manifest: PluginManifest,
        val source: String,
        val obfuscation: SourceObfuscation?,
        val fragment: BaseFragment,
    )

    /**
     * everything the rows read, in one nullable field: [UniversalAdapter] fills from its own
     * constructor, which runs from `super()` - before any field of this class is assigned. That
     * first pass builds nothing, and `init` fills again once there is something to build.
     */
    private val content: Content? = Content(manifest, source, obfuscation, fragment)

    private lateinit var adapter: UniversalAdapter
    private var header: PluginInfoHeaderView? = null
    private var obfuscationBanner: WarningBanner? = null
    private var enableRow: TextCell? = null
    private val grantRows = HashMap<Int, GrantRowView>()
    private var enableNow = true

    private val buttons: ButtonsView
    private var seenEnd = false

    init {
        ignoreTouchActionBar = false
        headerMoveTop = dp(12f)
        actionBar.setTitle(getTitle())
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))

        recyclerListView.setPadding(backgroundPaddingLeft, 0, backgroundPaddingLeft, dp(76f) + AndroidUtilities.navigationBarHeight)
        recyclerListView.setSections()
        recyclerListView.clipToPadding = false
        recyclerListView.setOnItemClickListener { _, position -> onItemClick(position - 1) }
        recyclerListView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = checkScrolledToEnd()
        })

        val cancel = createSheetButton(
            context,
            LocaleController.getString(R.string.Cancel),
            background = Theme.createSimpleSelectorRoundRectDrawable(
                dp(24f), 0, Theme.getColor(Theme.key_dialogButtonSelector),
            ),
            textColor = Theme.getColor(Theme.key_dialogTextBlack),
            bold = false,
        ) {
            dismiss()
        }
        val install = createSheetButton(
            context,
            LocaleController.getString(R.string.InuPluginInstall),
            background = Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 24f),
            textColor = Theme.getColor(Theme.key_featuredStickers_buttonText),
            bold = true,
        ) {
            if (!seenEnd) {
                scrollAhead()
                return@createSheetButton
            }
            dismiss()
            onInstall(enableNow)
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cancel, LinearLayout.LayoutParams(0, LayoutHelper.MATCH_PARENT, 1f).apply { marginEnd = dp(8f) })
            addView(install, LinearLayout.LayoutParams(0, LayoutHelper.MATCH_PARENT, 1f))
        }
        buttons = ButtonsView(context)
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
        recyclerListView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> checkScrolledToEnd() }

        adapter.update(false)
    }

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuPluginInstallTitle)

    override fun createAdapter(listView: RecyclerListView): RecyclerListView.SelectionAdapter {
        adapter = UniversalAdapter(recyclerListView, context, currentAccount, 0, false, this::fillItems, resourcesProvider)
        adapter.setApplyBackground(false)
        return adapter
    }

    /**
     * The strip is opaque while it covers content and fades out once there is nothing left under
     * it - the cancel button has no fill of its own, so over a scrolling list it would otherwise be
     * a label floating on top of rows.
     */
    private inner class ButtonsView(context: Context) : FrameLayout(context) {
        private val paint = Paint()
        // inside the view rather than above it: the sheet clips its children, so the band the
        // shadow falls on has to be part of what this one measures
        private val shadowHeight = dp(3f)
        private val shadowPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, shadowHeight.toFloat(),
                0x00000000, 0x12000000, Shader.TileMode.CLAMP,
            )
        }
        private var progress = 1f
        private var covering = true
        private var animator: ValueAnimator? = null

        init {
            setWillNotDraw(false)
            setPadding(0, dp(12f) + shadowHeight, 0, 0)
        }

        fun setCovering(value: Boolean) {
            if (covering == value) return
            covering = value
            animator?.cancel()
            animator = ValueAnimator.ofFloat(progress, if (value) 1f else 0f).apply {
                duration = 180
                interpolator = CubicBezierInterpolator.DEFAULT
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (progress <= 0f) return
            val alpha = (255 * progress).toInt()
            shadowPaint.alpha = alpha
            canvas.drawRect(0f, 0f, width.toFloat(), shadowHeight.toFloat(), shadowPaint)
            paint.color = Theme.getColor(Theme.key_windowBackgroundWhite)
            paint.alpha = alpha
            canvas.drawRect(0f, shadowHeight.toFloat(), width.toFloat(), height.toFloat(), paint)
        }
    }

    /** one viewport short of a full page, so the row that was under the strip stays in sight */
    private fun scrollAhead() {
        recyclerListView.smoothScrollBy(0, (recyclerListView.height * 0.7f).toInt())
    }

    private fun checkScrolledToEnd() {
        val atEnd = !recyclerListView.canScrollVertically(1)
        if (atEnd) seenEnd = true
        buttons.setCovering(!atEnd)
    }

    private fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val content = this.content ?: return
        val manifest = content.manifest

        items.add(UItem.asSpace(SPACE_TOP, dp(10f)))
        val headerView = header ?: PluginInfoHeaderView(context).also {
            header = it
            it.onAuthorClick = { username ->
                dismiss()
                MessagesController.getInstance(currentAccount).openByUserName(username, content.fragment, 0)
            }
        }
        headerView.bind(manifest, null)
        items.add(UItem.asCustom(HEADER, headerView))
        items.add(UItem.asShadow(manifest.description(LocaleController.getInstance().currentLocaleInfo?.langCode)))

        content.obfuscation?.let { kind ->
            val banner = obfuscationBanner ?: WarningBanner(context).also { obfuscationBanner = it }
            when (kind) {
                SourceObfuscation.OBFUSCATED -> {
                    banner.setTitle(LocaleController.getString(R.string.InuPluginObfuscatedTitle))
                    banner.setText(LocaleController.getString(R.string.InuPluginObfuscatedInfo))
                }

                SourceObfuscation.MINIFIED -> {
                    banner.setTitle(LocaleController.getString(R.string.InuPluginMinifiedTitle))
                    banner.setText(LocaleController.getString(R.string.InuPluginMinifiedInfo))
                }
            }
            items.add(UItem.asCustomShadow(OBFUSCATION_BANNER, banner))
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuPluginsPermissions)))
        val grants = sortedGrants(manifest.grants)
        if (grants.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuPluginsPermissionsNone)))
        } else {
            grants.forEachIndexed { i, (name, scopes) ->
                val row = grantRows.getOrPut(i) { GrantRowView(context) }
                row.bind(name, scopes, divider = i != grants.lastIndex)
                items.add(UItem.asCustom(GRANT_BASE + i, row))
            }
            items.add(UItem.asShadow(null))
        }

        val enable = enableRow ?: TextCell(context, 23, false, true, resourcesProvider).also { cell ->
            enableRow = cell
            cell.background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL)
            cell.setOnClickListener {
                enableNow = !enableNow
                cell.setChecked(enableNow)
            }
        }
        enable.setTextAndCheckAndIcon(
            LocaleController.getString(R.string.InuPluginInstallEnable),
            enableNow,
            R.drawable.msg2_animations,
            false,
        )
        items.add(UItem.asCustom(TOGGLE_ENABLE, enable))
        items.add(UItem.asButton(BUTTON_SOURCE, R.drawable.inu_tabler_code, LocaleController.getString(R.string.InuPluginsViewSource)))
        items.add(UItem.asShadow(null))
    }

    private fun onItemClick(position: Int) {
        val content = this.content ?: return
        val item = adapter.getItem(position) ?: return
        if (item.id == BUTTON_SOURCE) PluginSourceSheet(context, content.manifest.name, content.source).show()
    }

    companion object {
        private val HEADER = InuUtils.generateId()
        private val OBFUSCATION_BANNER = InuUtils.generateId()
        private val SPACE_TOP = InuUtils.generateId()
        private val TOGGLE_ENABLE = InuUtils.generateId()
        private val BUTTON_SOURCE = InuUtils.generateId()
        private const val GRANT_BASE = 20000
    }
}
