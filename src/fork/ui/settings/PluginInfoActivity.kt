package desu.inugram.ui.settings

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
import desu.inugram.core.plugins.GrantCatalog
import desu.inugram.core.plugins.GrantTier
import desu.inugram.core.plugins.ObfuscationDetector
import desu.inugram.core.plugins.PluginManifest
import desu.inugram.core.plugins.PluginPermissions
import desu.inugram.core.plugins.SourceObfuscation
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginFailure
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.ui.PluginManifestIcons
import desu.inugram.helpers.plugins.ui.PluginUi
import desu.inugram.helpers.theme.M3SectionsHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessageObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.ColoredImageSpan
import org.telegram.ui.Components.IconBackgroundColors
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.LinkSpanDrawable
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.SettingsActivity

class PluginInfoActivity(private val plugin: Plugin) : SettingsPageActivity() {
    private var header: PluginInfoHeaderView? = null
    private var obfuscationBanner: WarningBanner? = null
    private val grantRows = HashMap<Int, GrantRowView>()
    private var analyzedSource: String? = null
    private var obfuscation: SourceObfuscation? = null

    override fun getTitle(): CharSequence = plugin.manifest.name

    private val onPluginChanged: () -> Unit = {
        actionBar?.setTitle(plugin.manifest.name)
        listView?.adapter?.update(true)
    }

    override fun onResume() {
        super.onResume()
        PluginManager.addOnChangedListener(onPluginChanged)
    }

    override fun onPause() {
        super.onPause()
        PluginManager.removeOnChangedListener(onPluginChanged)
    }

    override fun createView(context: Context): View {
        header = null
        obfuscationBanner = null
        grantRows.clear()
        return super.createView(context)
    }

    private fun detectObfuscation(): SourceObfuscation? {
        val source = plugin.source
        if (analyzedSource !== source) {
            obfuscation = ObfuscationDetector.detect(source)
            analyzedSource = source
        }
        return obfuscation
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val headerView = header ?: PluginInfoHeaderView(context).also {
            header = it
            it.onAuthorClick = { username -> messagesController.openByUserName(username, this, 0) }
        }
        headerView.bind(plugin.manifest, plugin.failure)
        items.add(UItem.asCustom(HEADER, headerView))
        val description = plugin.manifest.description(LocaleController.getInstance().currentLocaleInfo?.langCode)
        items.add(UItem.asShadow(description))

        items.add(
            UItem.asRippleCheck(TOGGLE_ENABLED, LocaleController.getString(R.string.InuPluginsEnabled))
                .setChecked(plugin.enabled)
        )
        items.add(UItem.asShadow(null))

        detectObfuscation()?.let { kind ->
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

        if (plugin.settingsPageId != null) {
            items.add(UItem.asButton(BUTTON_SETTINGS, R.drawable.msg_settings, LocaleController.getString(R.string.Settings)))
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
            BUTTON_SOURCE -> showDialog(PluginSourceSheet(context, plugin.manifest.name, plugin.source))
            BUTTON_SHARE -> sharePlugin(this, plugin)
            BUTTON_RELOAD -> PluginManager.reload(plugin)
            BUTTON_REMOVE -> {
                PluginManager.remove(plugin)
                finishFragment()
            }
        }
    }

    companion object {
        private val HEADER = InuUtils.generateId()
        private val TOGGLE_ENABLED = InuUtils.generateId()
        private val OBFUSCATION_BANNER = InuUtils.generateId()
        private val BUTTON_SETTINGS = InuUtils.generateId()
        private val BUTTON_SOURCE = InuUtils.generateId()
        private val BUTTON_SHARE = InuUtils.generateId()
        private val BUTTON_RELOAD = InuUtils.generateId()
        private val BUTTON_REMOVE = InuUtils.generateId()
        private const val GRANT_BASE = 20000
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
        .filterKeys { GrantCatalog.entryOf(it) != null }
        .map { (name, scopes) -> GrantCatalog.tierOf(name, scopes) }
        .maxOrNull()
        ?.takeIf { it != GrantTier.NEUTRAL }

private class GrantPresentation(val titleRes: Int, val iconRes: Int)

/**
 * What every permission list shows: worst tier first, so the red rows are the ones a user reads
 * before deciding. A stable sort, so within a tier the manifest's own order survives.
 */
internal fun sortedGrants(tokens: List<String>): List<Pair<String, List<String>?>> =
    mergeGrants(tokens)
        .map { (name, scopes) -> name to scopes }
        .sortedByDescending { (name, scopes) -> GrantCatalog.tierOf(name, scopes) }

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
    }.sortedByDescending { (name, scopes) -> GrantCatalog.tierOf(name, scopes) }
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
    }.sortedByDescending { (name, scopes) -> GrantCatalog.tierOf(name, scopes) }
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

private fun labeledScopes(scopes: List<String>?, labels: Map<String, Int>): String =
    (scopes ?: labels.keys.toList()).joinToString(", ") { scope -> labels[scope]?.let(LocaleController::getString) ?: scope }

private fun grantSubtitle(name: String, scopes: List<String>?): String? = when (name) {
    "account.read" -> LocaleController.formatString(
        R.string.InuPluginScopeAccountRead,
        labeledScopes(scopes, ACCOUNT_READ_SCOPE_LABELS),
    )

    "account.write" -> labeledScopes(scopes, ACCOUNT_WRITE_SCOPE_LABELS)
    "fetch" -> scopes?.let { LocaleController.formatString(R.string.InuPluginScopeFetch, it.joinToString(", ")) }
        ?: LocaleController.getString(R.string.InuPluginScopeFetchAny)

    "invokeRpc" -> LocaleController.formatString(
        R.string.InuPluginScopeInvokeRpc,
        scopes?.joinToString(", ") ?: LocaleController.getString(R.string.InuPluginScopeAnyMethod),
    )

    "interceptRpc" -> LocaleController.formatString(
        R.string.InuPluginScopeInterceptRpc,
        scopes?.joinToString(", ") ?: LocaleController.getString(R.string.InuPluginScopeAnyMethod),
    )

    "onUpdate" -> LocaleController.formatString(
        R.string.InuPluginScopeOnUpdate,
        scopes?.let { labeledScopes(it, UPDATE_SCOPE_LABELS) } ?: LocaleController.getString(R.string.InuPluginScopeAllUpdates),
    )

    "interceptUpdate" -> LocaleController.formatString(
        R.string.InuPluginScopeInterceptUpdate,
        scopes?.let { labeledScopes(it, UPDATE_SCOPE_LABELS) } ?: LocaleController.getString(R.string.InuPluginScopeAllUpdates),
    )


    "notifications.suppress" -> LocaleController.getString(R.string.InuPluginGrantNotificationsSuppressInfo)
    "takeout" -> LocaleController.getString(R.string.InuPluginGrantTakeoutInfo)
    "unsafe.fs" -> LocaleController.getString(R.string.InuPluginGrantUnsafeFsInfo)
    "unsafe.invokeRaw" -> LocaleController.getString(R.string.InuPluginGrantUnsafeInvokeRawInfo)
    "unsafe.jvm" -> LocaleController.getString(R.string.InuPluginGrantUnsafeJvmInfo)

    "unsafe.xposed" -> LocaleController.getString(R.string.InuPluginGrantUnsafeXposedInfo)
    "unsafe.notificationCenter" -> LocaleController.getString(R.string.InuPluginGrantUnsafeNotificationCenterInfo)
    "unsafe.disableApiFiltering" -> LocaleController.getString(R.string.InuPluginGrantUnsafeDisableApiFilteringInfo)

    else -> null
}

private val KNOWN_GRANTS = mapOf(
    "kv" to GrantPresentation(R.string.InuPluginGrantKv, R.drawable.msg_customize),
    "fs" to GrantPresentation(R.string.InuPluginGrantFs, R.drawable.files_storage),
    "clipboard.write" to GrantPresentation(R.string.InuPluginGrantClipboardWrite, R.drawable.msg_copy),
    "openUrl" to GrantPresentation(R.string.InuPluginGrantOpenUrl, R.drawable.msg_link),
    "onAppVisibilityChange" to GrantPresentation(R.string.InuPluginGrantAppVisibility, R.drawable.menu_hide_gift),
    "clipboard.read" to GrantPresentation(R.string.InuPluginGrantClipboardRead, R.drawable.msg_copy),
    "notifications.suppress" to GrantPresentation(R.string.InuPluginGrantNotificationsSuppress, R.drawable.msg_mute),
    "fetch" to GrantPresentation(R.string.InuPluginGrantFetch, R.drawable.msg_language),
    "account.read" to GrantPresentation(R.string.InuPluginGrantAccountRead, R.drawable.msg_contacts),
    "account.write" to GrantPresentation(R.string.InuPluginGrantAccountWrite, R.drawable.msg_send),
    "onUpdate" to GrantPresentation(R.string.InuPluginGrantOnUpdate, R.drawable.msg_message),
    "interceptUpdate" to GrantPresentation(R.string.InuPluginGrantInterceptUpdate, R.drawable.msg_download),
    "interceptSendMessage" to GrantPresentation(R.string.InuPluginGrantInterceptSend, R.drawable.msg_edit),
    "interceptRpc" to GrantPresentation(R.string.InuPluginGrantInterceptRpc, R.drawable.msg_log),
    "invokeRpc" to GrantPresentation(R.string.InuPluginGrantInvokeRpc, R.drawable.msg_bot),
    "takeout" to GrantPresentation(R.string.InuPluginGrantTakeout, R.drawable.msg_download),
    "unsafe.fs" to GrantPresentation(R.string.InuPluginGrantUnsafeFs, R.drawable.files_storage),
    "unsafe.invokeRaw" to GrantPresentation(R.string.InuPluginGrantUnsafeInvokeRaw, R.drawable.msg_bot),
    "unsafe.jvm" to GrantPresentation(R.string.InuPluginGrantUnsafeJvm, R.drawable.inu_tabler_code),
    "unsafe.xposed" to GrantPresentation(R.string.InuPluginGrantUnsafeXposed, R.drawable.msg_replace),
    "unsafe.notificationCenter" to GrantPresentation(R.string.InuPluginGrantUnsafeNotificationCenter, R.drawable.msg_notifications),
    "unsafe.disableApiFiltering" to GrantPresentation(R.string.InuPluginGrantUnsafeDisableApiFiltering, R.drawable.msg_block),
)

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
    private val subtitle = TextView(context).apply {
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        textSize = 13f
    }
    private val textBlock = LinearLayout(context).apply { orientation = VERTICAL }
    private var needDivider = false

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
        val needed = divider && !M3SectionsHelper.isEnabled()
        if (needDivider != needed) {
            needDivider = needed
            invalidate()
        }
        val known = KNOWN_GRANTS[name]
        val tier = if (known == null) null else GrantCatalog.tierOf(name, scopes)
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
            grantSubtitle(name, scopes)
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

    /** the explanation, marked with the same triangle the plugin's own row carries */
    private fun warn(text: String, color: Int): CharSequence {
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
            metaText.append(linkPluginUsernames(formatted) { username -> onAuthorClick?.invoke(username) })
        }

        meta.text = metaText
        meta.visibility = if (metaText.isEmpty()) GONE else VISIBLE
        failureView.text = failure?.describe()
        failureView.visibility = if (failure == null) GONE else VISIBLE
    }
}

internal fun linkPluginUsernames(text: String, onClick: (String) -> Unit): CharSequence {
    val result = SpannableStringBuilder(text)
    MessageObject.addUrlsByPattern(false, result, false, 0, 0, false)
    for (span in result.getSpans(0, result.length, URLSpan::class.java)) {
        val start = result.getSpanStart(span)
        val end = result.getSpanEnd(span)
        result.removeSpan(span)
        if (!span.url.startsWith("@")) continue
        result.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) {
                    onClick(span.url.substring(1))
                }

                override fun updateDrawState(ds: TextPaint) {
                    ds.color = ds.linkColor
                }
            },
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
    return result
}
