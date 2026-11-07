package desu.inugram.ui.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.URLSpan
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.InuConfig
import desu.inugram.core.plugins.GrantCatalog
import desu.inugram.core.plugins.GrantTier
import desu.inugram.core.plugins.ObfuscationDetector
import desu.inugram.core.plugins.PluginAsset
import desu.inugram.core.plugins.PluginAssetsParser
import desu.inugram.core.plugins.PluginManifest
import desu.inugram.core.plugins.PluginPermissions
import desu.inugram.core.plugins.SourceObfuscation
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginFailure
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.ui.PluginManifestIcons
import desu.inugram.helpers.plugins.ui.PluginUi
import desu.inugram.helpers.theme.M3SectionsHelper
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.ColoredImageSpan
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.IconBackgroundColors
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.LinkSpanDrawable
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.URLSpanReplacement
import org.telegram.ui.SettingsActivity

class PluginInfoActivity(private val plugin: Plugin) : SettingsPageActivity() {
    private var header: PluginInfoHeaderView? = null
    private var obfuscationBanner: WarningBanner? = null
    private val grantRows = HashMap<Int, GrantRowView>()
    private var analyzedSource: String? = null
    private var obfuscation: SourceObfuscation? = null
    private var assets: List<PluginAsset> = emptyList()
    private var memoryUsage: String? = null

    override fun getTitle(): CharSequence = plugin.manifest.name

    private val onPluginChanged: () -> Unit = {
        simpleActionBar?.setTitle(plugin.manifest.name)
        listView?.adapter?.update(true)
    }

    private val pollMemoryUsage = object : Runnable {
        override fun run() {
            val session = plugin.session
            if (session != null && InuConfig.PLUGINS_DEV_MODE.value) {
                EngineDispatch.onEngine(session) {
                    val usage = session.engine.memoryUsage()?.let(::JSONObject) ?: return@onEngine
                    val text = AndroidUtilities.formatFileSize(usage.getLong("heapBytes")) + " / " +
                        AndroidUtilities.formatFileSize(usage.getLong("heapLimitBytes"))
                    AndroidUtilities.runOnUIThread {
                        if (memoryUsage == text) return@runOnUIThread
                        memoryUsage = text
                        listView?.adapter?.update(true)
                    }
                }
            }
            AndroidUtilities.runOnUIThread(this, MEMORY_POLL_MS)
        }
    }

    override fun onResume() {
        super.onResume()
        PluginManager.addOnChangedListener(onPluginChanged)
        onPluginChanged()
        pollMemoryUsage.run()
    }

    override fun onPause() {
        super.onPause()
        PluginManager.removeOnChangedListener(onPluginChanged)
        AndroidUtilities.cancelRunOnUIThread(pollMemoryUsage)
    }

    override fun createView(context: Context): View {
        header = null
        obfuscationBanner = null
        grantRows.clear()
        return super.createView(context)
    }

    private fun analyzeSource() {
        val source = plugin.source
        if (analyzedSource === source) return
        obfuscation = ObfuscationDetector.detect(source.substring(0, PluginAssetsParser.findFooter(source)))
        assets = runCatching { PluginAssetsParser.parse(source) }.getOrDefault(emptyList())
        analyzedSource = source
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val headerView = header ?: PluginInfoHeaderView(context).also {
            header = it
            it.onAuthorClick = { username -> messagesController.openByUserName(username, this, 0) }
        }
        headerView.bind(plugin.manifest, plugin.failure)
        items.add(UItem.asCustom(HEADER, headerView))
        val description = plugin.manifest.description(LocaleController.getInstance().currentLocaleInfo?.langCode)
        items.add(UItem.asShadow(description?.let { formatPluginDescription(it) { username -> messagesController.openByUserName(username, this, 0) } }))

        items.add(
            UItem.asRippleCheck(TOGGLE_ENABLED, LocaleController.getString(R.string.InuPluginsEnabled))
                .setChecked(plugin.enabled)
        )
        items.add(UItem.asShadow(null))

        analyzeSource()
        obfuscation?.let { kind ->
            val banner = obfuscationBanner ?: WarningBanner(context).also { obfuscationBanner = it }
            banner.setObfuscation(kind)
            items.add(UItem.asCustomShadow(OBFUSCATION_BANNER, banner))
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuPluginsPermissions)))
        val grants = sortedGrants(plugin.manifest.grants)
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

        if (InuConfig.PLUGINS_DEV_MODE.value && plugin.session != null) {
            items.add(UItem.asButton(MEMORY_USAGE, LocaleController.getString(R.string.InuPluginsMemoryUsage), memoryUsage ?: "…"))
            items.add(UItem.asShadow(null))
        }

        if (plugin.settingsPageId != null) {
            items.add(UItem.asButton(BUTTON_SETTINGS, R.drawable.msg_settings, LocaleController.getString(R.string.Settings)))
        }
        if (assets.isNotEmpty()) {
            items.add(UItem.asButton(BUTTON_ASSETS, R.drawable.msg_media, LocaleController.getString(R.string.InuPluginAssets), PluginAssetsSheet.describeAssets(assets)))
        }
        items.add(UItem.asButton(BUTTON_SOURCE, R.drawable.inu_tabler_code, LocaleController.getString(R.string.InuPluginsViewSource)))
        items.add(UItem.asButton(BUTTON_SHARE, R.drawable.msg_share, LocaleController.getString(R.string.ShareFile)))
        // a reload only re-reads the file for a plugin that is going to run it
        if (plugin.enabled) {
            items.add(UItem.asButton(BUTTON_RELOAD, R.drawable.msg_reset, LocaleController.getString(R.string.InuPluginsReload)))
        }
        items.add(UItem.asButton(BUTTON_REMOVE, R.drawable.msg_delete, LocaleController.getString(R.string.InuPluginsRemove)).red())
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_ENABLED -> {
                PluginManager.setEnabled(plugin, !plugin.enabled)
                (view as? TextCheckCell)?.isChecked = plugin.enabled
            }
            BUTTON_SETTINGS -> PluginUi.openRegisteredSettings(plugin)
            BUTTON_ASSETS -> PluginAssetsSheet(context, plugin.source, assets).show()
            BUTTON_SOURCE -> showDialog(PluginTextSheet.createForSource(context, plugin.manifest.name, plugin.source))
            BUTTON_SHARE -> sharePlugin(this, plugin)
            BUTTON_RELOAD -> PluginManager.reload(plugin)
            BUTTON_REMOVE -> confirmRemovePlugins(
                this,
                listOf(plugin),
                LocaleController.formatString(R.string.InuPluginsRemoveTitle, plugin.manifest.name),
                LocaleController.getString(R.string.InuPluginsRemoveConfirm),
                ::finishFragment,
            )
        }
    }

    companion object {
        private val HEADER = InuUtils.generateId()
        private val TOGGLE_ENABLED = InuUtils.generateId()
        private val OBFUSCATION_BANNER = InuUtils.generateId()
        private val BUTTON_SETTINGS = InuUtils.generateId()
        private val BUTTON_ASSETS = InuUtils.generateId()
        private val BUTTON_SOURCE = InuUtils.generateId()
        private val BUTTON_SHARE = InuUtils.generateId()
        private val BUTTON_RELOAD = InuUtils.generateId()
        private val BUTTON_REMOVE = InuUtils.generateId()
        private val MEMORY_USAGE = InuUtils.generateId()
        private const val GRANT_BASE = 20000
        private const val MEMORY_POLL_MS = 2000L
    }
}

/** gradient (top, bottom) of the tier's icon badge; the top color doubles as its flat accent */
internal fun tierColors(tier: GrantTier?): Pair<Int, Int> = when (tier) {
    GrantTier.DANGEROUS -> IconBackgroundColors.RED.top to IconBackgroundColors.RED.bottom
    GrantTier.CAUTION -> 0xFFF38B31.toInt() to 0xFFE26314.toInt()
    GrantTier.NEUTRAL -> IconBackgroundColors.BLUE.top to IconBackgroundColors.BLUE.bottom
    null -> 0xFFB6BEC8.toInt() to 0xFF98A2AD.toInt()
}

/** worst tier among the grants we recognize, or null when nothing rises above neutral */
internal fun highestGrantTier(manifest: PluginManifest): GrantTier? =
    mergeGrants(manifest.grants)
        .filterKeys { GrantCatalog.findEntry(it) != null }
        .map { (name, scopes) -> GrantCatalog.resolveTier(name, scopes) }
        .maxOrNull()
        ?.takeIf { it != GrantTier.NEUTRAL }

/**
 * What every permission list shows: worst tier first, so the red rows are the ones a user reads
 * before deciding. A stable sort, so within a tier the manifest's own order survives.
 */
internal fun sortedGrants(tokens: List<String>): List<Pair<String, List<String>?>> =
    mergeGrants(tokens)
        .map { (name, scopes) -> name to scopes }
        .sortedByDescending { (name, scopes) -> GrantCatalog.resolveTier(name, scopes) }

/**
 * what [asked] wants that [baseline] does not cover: a grant whose name [baseline] lacks, or one
 * that widened - to more scopes, or from a scoped grant to an unscoped one. Same tier order as
 * [sortedGrants].
 *
 * The sheet confirming an update reads it both ways round: baseline first for what the new source
 * newly asks for, asked first for what it gave up.
 */
internal fun findGrantsBeyond(baseline: List<String>, asked: List<String>): List<Pair<String, List<String>?>> {
    val had = mergeGrants(baseline)
    return mergeGrants(asked).mapNotNull { (name, scopes) ->
        if (name !in had) return@mapNotNull name to scopes
        val hadScopes = had[name] ?: return@mapNotNull null
        if (scopes == null) return@mapNotNull name to null
        val added = scopes.filter { it !in hadScopes }
        if (added.isEmpty()) null else name to added
    }.sortedByDescending { (name, scopes) -> GrantCatalog.resolveTier(name, scopes) }
}

/**
 * what an update leaves exactly as it was - held before and still asked for, so neither
 * [findGrantsBeyond] direction lists it. A grant that only widened appears here too, narrowed to
 * the part that was already granted, because that part is not what the update is asking about.
 */
internal fun findGrantsKept(previous: List<String>, current: List<String>): List<Pair<String, List<String>?>> {
    val had = mergeGrants(previous)
    return mergeGrants(current).mapNotNull { (name, scopes) ->
        if (name !in had) return@mapNotNull null
        val hadScopes = had[name]
        // either side unscoped means the other side's scopes are all granted and all still asked for
        if (hadScopes == null) return@mapNotNull name to scopes
        if (scopes == null) return@mapNotNull name to hadScopes
        val kept = scopes.filter { it in hadScopes }
        if (kept.isEmpty()) null else name to kept
    }.sortedByDescending { (name, scopes) -> GrantCatalog.resolveTier(name, scopes) }
}

/** merged by grant name in first-appearance order; `null` scopes = unscoped (full access) */
internal fun mergeGrants(tokens: List<String>): LinkedHashMap<String, List<String>?> {
    val merged = LinkedHashMap<String, List<String>?>()
    for (grant in tokens.mapNotNull { PluginPermissions.parseGrant(it) }) {
        if (grant.name !in merged) {
            merged[grant.name] = grant.scopes.takeIf { it.isNotEmpty() }
        } else {
            val existing = merged[grant.name] ?: continue
            merged[grant.name] = if (grant.scopes.isEmpty()) null else existing + grant.scopes.filter { it !in existing }
        }
    }
    return merged
}

private val ACCOUNT_READ_SCOPE_LABELS = mapOf(
    "self" to R.string.InuPluginScopeReadSelf,
    "peers" to R.string.InuPluginScopeReadPeers,
    "messages" to R.string.InuPluginScopeReadMessages,
    "dialogs" to R.string.InuPluginScopeReadDialogs,
    "history" to R.string.InuPluginScopeReadHistory,
    "draft" to R.string.InuPluginScopeReadDraft,
)

private val ACCOUNT_WRITE_SCOPE_LABELS = mapOf(
    "send" to R.string.InuPluginScopeWriteSend,
    "edit" to R.string.InuPluginScopeWriteEdit,
    "delete" to R.string.InuPluginScopeWriteDelete,
    "forward" to R.string.InuPluginScopeWriteForward,
    "react" to R.string.InuPluginScopeWriteReact,
    "read" to R.string.InuPluginScopeWriteRead,
    "typing" to R.string.InuPluginScopeWriteTyping,
    "draft" to R.string.InuPluginScopeWriteDraft,
)

private val UPDATE_SCOPE_LABELS = mapOf(
    "new_message" to R.string.InuPluginScopeUpdNewMessage,
    "edit_message" to R.string.InuPluginScopeUpdEditMessage,
    "delete_message" to R.string.InuPluginScopeUpdDeleteMessage,
)

private fun grantSubtitle(
    name: String,
    scopes: List<String>?,
    expanded: Boolean,
    onExpand: () -> Unit,
): CharSequence? {
    var moreText: String? = null
    fun formatScopes(values: List<String>, labels: Map<String, Int> = emptyMap()): String {
        val shown = if (expanded) values else values.take(COLLAPSED_SCOPE_COUNT)
        val text = shown.joinToString(", ") { scope -> labels[scope]?.let(LocaleController::getString) ?: scope }
        val remaining = values.size - shown.size
        if (remaining == 0) return text
        moreText = LocaleController.formatPluralString("InuPluginScopesMore", remaining).replace(' ', '\u00a0')
        return "$text, $moreText"
    }

    val text = when (name) {
        "account.read" -> LocaleController.formatString(
            R.string.InuPluginScopeAccountRead,
            formatScopes(scopes ?: ACCOUNT_READ_SCOPE_LABELS.keys.toList(), ACCOUNT_READ_SCOPE_LABELS),
        )

        "account.write" -> formatScopes(scopes ?: ACCOUNT_WRITE_SCOPE_LABELS.keys.toList(), ACCOUNT_WRITE_SCOPE_LABELS)
        "fetch" -> scopes?.let { LocaleController.formatString(R.string.InuPluginScopeFetch, formatScopes(it)) }
            ?: LocaleController.getString(R.string.InuPluginScopeFetchAny)

        "invokeRpc" -> LocaleController.formatString(
            R.string.InuPluginScopeInvokeRpc,
            scopes?.let { formatScopes(it) } ?: LocaleController.getString(R.string.InuPluginScopeAnyMethod),
        )

        "interceptRpc" -> LocaleController.formatString(
            R.string.InuPluginScopeInterceptRpc,
            scopes?.let { formatScopes(it) } ?: LocaleController.getString(R.string.InuPluginScopeAnyMethod),
        )

        "onUpdate" -> LocaleController.formatString(
            R.string.InuPluginScopeOnUpdate,
            scopes?.let { formatScopes(it, UPDATE_SCOPE_LABELS) } ?: LocaleController.getString(R.string.InuPluginScopeAllUpdates),
        )

        "interceptUpdate" -> LocaleController.formatString(
            R.string.InuPluginScopeInterceptUpdate,
            scopes?.let { formatScopes(it, UPDATE_SCOPE_LABELS) } ?: LocaleController.getString(R.string.InuPluginScopeAllUpdates),
        )

        else -> GRANT_INFO[name]?.let(LocaleController::getString)
    } ?: return null
    val link = moreText ?: return text
    val start = text.lastIndexOf(link)
    if (start < 0) return text
    return SpannableStringBuilder(text).apply {
        setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) = onExpand()

                override fun updateDrawState(ds: TextPaint) {
                    ds.color = ds.linkColor
                }
            },
            start,
            start + link.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
}

private const val COLLAPSED_SCOPE_COUNT = 3

internal class GrantRowView(context: Context) : LinearLayout(context) {
    private val iconBackground = SettingsActivity.SettingCell.Background()
    private val icon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    private val iconLayout = FrameLayout(context).apply {
        background = iconBackground
        addView(icon, LayoutHelper.createFrame(20, 20, Gravity.CENTER))
    }
    private val title = TextView(context).apply {
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        textSize = 16f
    }
    private val subtitle = LinkSpanDrawable.LinksTextView(context).apply {
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText))
        textSize = 13f
    }
    private val textBlock = LinearLayout(context).apply { orientation = VERTICAL }
    private var needDivider = false
    private var expandedGrant: String? = null
    private var expandAnimator: ValueAnimator? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        setPadding(AndroidUtilities.dp(22f), AndroidUtilities.dp(8f), AndroidUtilities.dp(22f), AndroidUtilities.dp(8f))
        addView(iconLayout, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL, 0f, 0f, 18f, 0f))
        textBlock.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        textBlock.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f))
        addView(textBlock, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!needDivider) return
        // starts where the text column does, past the icon tile
        drawRowDivider(canvas, DIVIDER_INSET_DP)
    }

    /**
     * [divider] only asks for one: md3 cards separate rows by shape instead.
     *
     * [dropped] is a permission the plugin is *giving up*, which is why it also drops the danger
     * styling: a red "can take over your account" warning next to something being taken away reads
     * as the opposite of what happened. The content dims, not the view, so the row keeps its fill.
     */
    fun bind(name: String, scopes: List<String>?, divider: Boolean, dropped: Boolean = false) {
        expandAnimator?.cancel()
        if (expandedGrant != null && expandedGrant != name) expandedGrant = null
        val needed = divider && !M3SectionsHelper.isEnabled()
        if (needDivider != needed) {
            needDivider = needed
            invalidate()
        }
        val known = KNOWN_GRANTS[name]
        val tier = if (known == null) null else GrantCatalog.resolveTier(name, scopes)
        icon.setImageResource(known?.iconRes ?: R.drawable.msg_help)
        icon.setColorFilter(PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN))
        val (top, bottom) = tierColors(tier)
        iconBackground.setColor(top, bottom)
        iconBackground.setDrawBorder(Theme.isCurrentThemeDark())
        M3SectionsHelper.applySettingCellIcon(iconLayout, icon, top, bottom, iconBackground)
        val subtitleText = if (known == null) {
            title.text = name
            LocaleController.getString(R.string.InuPluginGrantUnknown)
        } else {
            title.text = LocaleController.getString(known.titleRes)
            grantSubtitle(name, scopes, expandedGrant == name) {
                expand(name, scopes, divider, dropped)
            }
        }
        val dangerous = tier == GrantTier.DANGEROUS && !dropped
        subtitle.setTextColor(
            if (dangerous) top else Theme.getColor(Theme.key_windowBackgroundWhiteGrayText),
        )
        subtitle.text = if (dangerous && subtitleText != null) warn(subtitleText, top) else subtitleText
        subtitle.visibility = if (subtitleText == null) GONE else VISIBLE
        val contentAlpha = if (dropped) 0.5f else 1f
        iconLayout.alpha = contentAlpha
        textBlock.alpha = contentAlpha
    }

    private fun expand(name: String, scopes: List<String>?, divider: Boolean, dropped: Boolean) {
        val fromHeight = height
        expandedGrant = name
        bind(name, scopes, divider, dropped)
        if (fromHeight <= 0 || width <= 0) return
        measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val toHeight = measuredHeight
        if (toHeight <= fromHeight) return
        val params = layoutParams ?: return
        params.height = fromHeight
        requestLayout()
        val animator = ValueAnimator.ofInt(fromHeight, toHeight).apply {
            duration = 200
            interpolator = CubicBezierInterpolator.DEFAULT
            addUpdateListener {
                params.height = it.animatedValue as Int
                requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    params.height = LayoutHelper.MATCH_PARENT
                    requestLayout()
                    expandAnimator = null
                }
            })
        }
        expandAnimator = animator
        animator.start()
    }

    /** the explanation, marked with the same triangle the plugin's own row carries */
    private fun warn(text: CharSequence, color: Int): CharSequence {
        val sb = SpannableStringBuilder("\u200b  ")
        sb.setSpan(
            ColoredImageSpan(R.drawable.inu_tabler_alert_triangle_filled, ColoredImageSpan.ALIGN_CENTER).apply {
                setSize(AndroidUtilities.dp(13f))
                setOverrideColor(color)
            },
            0,
            1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return sb.append(text)
    }

    companion object {
        private const val DIVIDER_INSET_DP = 68f
    }
}

class PluginInfoHeaderView(context: Context) : LinearLayout(context) {
    private val icon = BackupImageView(context).apply {
        setRoundRadius(AndroidUtilities.dp(16f))
    }
    // one of our own glyphs is a flat silhouette, which at this size is a blob in whatever colour
    // the theme's icons take; it gets an accent tile to sit on instead, like a launcher icon
    private val badgeColor = Theme.getColor(Theme.key_featuredStickers_addButton)
    private val badge = Theme.createRoundRectDrawable(AndroidUtilities.dp(20f), badgeColor)
    private val glyphTint = Theme.getColor(Theme.key_featuredStickers_buttonText)
    private val iconContainer = FrameLayout(context)
    private val placeholder = PluginManifestIcons.createPlaceholder(context)
    private val name = TextView(context).apply {
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        textSize = 20f
        typeface = AndroidUtilities.bold()
        gravity = Gravity.CENTER
    }
    var onAuthorClick: ((String) -> Unit)? = null
    private val meta = LinkSpanDrawable.LinksTextView(context).apply {
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText))
        textSize = 14f
        gravity = Gravity.CENTER
    }
    private val failureView = TextView(context).apply {
        setTextColor(Theme.getColor(Theme.key_text_RedRegular))
        textSize = 14f
        gravity = Gravity.CENTER
    }

    init {
        isClickable = true
        orientation = VERTICAL
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        setPadding(0, 0, 0, AndroidUtilities.dp(20f))
        iconContainer.addView(icon, LayoutHelper.createFrame(72, 72, Gravity.CENTER))
        addView(iconContainer, LayoutHelper.createLinear(72, 72, Gravity.CENTER_HORIZONTAL, 0f, 20f, 0f, 0f))
        addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 20f, 12f, 20f, 0f))
        addView(meta, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 20f, 4f, 20f, 0f))
        addView(failureView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 20f, 8f, 20f, 0f))
    }

    /** [previousVersion] is the installed plugin's, when this header is confirming an update over it */
    fun bind(manifest: PluginManifest, failure: PluginFailure?, previousVersion: String? = null) {
        val glyph = PluginManifestIcons.bindIcon(icon, manifest.icon, placeholder, glyphTint)
        iconContainer.background = if (glyph) badge else null
        val iconSize = AndroidUtilities.dp(if (glyph) 40f else 72f)
        icon.layoutParams?.let { lp ->
            if (lp.width != iconSize) {
                lp.width = iconSize
                lp.height = iconSize
                icon.layoutParams = lp
            }
        }
        name.text = manifest.name
        val metaText = SpannableStringBuilder()
        // the arrow is driven by the installed version, not the new one: a file that dropped
        // @version still changed what is installed, and rendering nothing would read as "no version
        // info" rather than as the update it is
        if (previousVersion != null && previousVersion != manifest.version) {
            metaText.append("v$previousVersion \u2192 ${manifest.version?.let { "v$it" } ?: "?"}")
        } else {
            manifest.version?.let { metaText.append("v$it") }
        }
        manifest.author?.let { author ->
            if (metaText.isNotEmpty()) metaText.append(" · ")
            val formatted = LocaleController.formatString(R.string.InuPluginsByAuthor, author)
            metaText.append(InuUtils.linkUsernames(formatted) { username -> onAuthorClick?.invoke(username) })
        }

        meta.text = metaText
        meta.visibility = if (metaText.isEmpty()) GONE else VISIBLE
        failureView.text = failure?.describe()
        failureView.visibility = if (failure == null) GONE else VISIBLE
        failure?.let { failureView.setOnClickListener { _ -> PluginTextSheet.createForFailure(context, manifest.name, it).show() } }
    }
}

private val MARKDOWN_LINK = Regex("""\[(.+?)]\((.+?)\)""")

internal fun formatPluginDescription(text: String, onUsernameClick: (String) -> Unit): CharSequence {
    val withLinks = SpannableStringBuilder(text)
    for (match in MARKDOWN_LINK.findAll(text).toList().asReversed()) {
        val (label, url) = match.destructured
        val start = match.range.first
        withLinks.replace(start, match.range.last + 1, label)
        withLinks.setSpan(URLSpanReplacement(url), start, start + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    val message = arrayOf<CharSequence>(withLinks)
    val entities = MediaDataController.getInstance(UserConfig.selectedAccount).getEntities(message, true)
    val styled = SpannableStringBuilder(message[0].toString())
    MessageObject.addEntitiesToText(styled, entities, false, false, false, false)
    return InuUtils.linkUsernames(styled, onUsernameClick)
}
