package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import desu.inugram.InuConfig
import desu.inugram.helpers.plugins.BootGuard
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.ui.PluginManifestIcons
import desu.inugram.helpers.plugins.ui.PluginUi
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.ActionBarMenuSubItem
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.Switch
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import kotlin.math.ceil

class PluginsActivity : SettingsPageActivity() {
    private val rows = HashMap<String, PluginRow>()
    private var safeModeBanner: WarningBanner? = null
    private var reorderSectionId = -1
    private var compactItem: ActionBarMenuSubItem? = null

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuPlugins)

    override fun onResume() {
        super.onResume()
        PluginManager.onChanged = { listView?.adapter?.update(true) }
    }

    override fun onPause() {
        super.onPause()
        PluginManager.onChanged = null
    }

    override fun createView(context: Context): View {
        safeModeBanner = null
        val view = super.createView(context)
        listView.allowReorder(true)
        listView.setReorderLongPressEnabled(false)
        listView.listenReorder { id, items -> if (id == reorderSectionId) applyReorder(items) }

        val otherItem = actionBar.createMenu().addItem(0, R.drawable.ic_ab_other)
        otherItem.contentDescription = LocaleController.getString(R.string.AccDescrMoreOptions)
        compactItem = otherItem.addSubItem(
            MENU_COMPACT,
            0,
            LocaleController.getString(R.string.InuPluginsCompactView),
            true,
        ).apply {
            setChecked(InuConfig.PLUGINS_COMPACT_LIST.value)
        }
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                when (id) {
                    -1 -> finishFragment()
                    MENU_COMPACT -> toggleCompactList()
                }
            }
        })
        return view
    }

    private fun toggleCompactList() {
        InuConfig.PLUGINS_COMPACT_LIST.toggle()
        compactItem?.setChecked(InuConfig.PLUGINS_COMPACT_LIST.value)
        rows.clear()
        listView.adapter.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        // the rows below still read as enabled, because they are: safe mode is about this session
        // and nothing else, and a session that ran nothing has to say so somewhere
        safeModeNotice()?.let { notice ->
            val banner = safeModeBanner ?: WarningBanner(context, LocaleController.getString(R.string.InuRestartNow), {
                parentActivity?.let { InuUtils.restartApp(it) }
            }).also {
                it.setTitle(LocaleController.getString(R.string.InuPluginsSafeMode))
                safeModeBanner = it
            }
            banner.setText(notice)
            items.add(UItem.asCustomShadow(SAFE_MODE_BANNER, banner))
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuPluginsInfo)))
        items.add(
            mkTwoLineCheckItem(
                ENGINE_TOGGLE,
                R.string.InuPluginsEnableEngine,
                R.string.InuPluginsEnableEngineInfo,
                PluginManager.isEngineEnabled(),
            )
        )

        val plugins = PluginManager.plugins()
        if (plugins.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuPluginsEmpty)))
        } else {
            items.add(UItem.asHeader(LocaleController.getString(R.string.InuPluginsInstalled)))
            reorderSectionId = adapter.reorderSectionStart()
            for (plugin in plugins) items.add(buildRow(plugin))
            adapter.reorderSectionEnd()
        }

        items.add(UItem.asShadow(null))
        items.add(
            UItem.asButton(
                BUTTON_LOAD,
                R.drawable.msg_download,
                LocaleController.getString(R.string.InuPluginsLoadFromDisk),
            )
        )
        items.add(UItem.asShadow(null))
    }

    private fun safeModeNotice(): CharSequence? = when (PluginManager.safeModeReason) {
        BootGuard.Reason.FORCED -> LocaleController.getString(R.string.InuPluginsSafeModeForced)
        BootGuard.Reason.CRASHED -> LocaleController.getString(R.string.InuPluginsSafeModeCrashed)
        null -> null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildRow(plugin: Plugin): UItem {
        val compact = InuConfig.PLUGINS_COMPACT_LIST.value
        var row = rows[plugin.id]
        if (row == null || row.compact != compact) {
            row = PluginRow(context, compact).also { newRow ->
                newRow.setOnReorderTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        val holder = listView.findContainingViewHolder(newRow) ?: return@setOnReorderTouchListener false
                        listView.itemTouchHelper.startDrag(holder)
                    }
                    false
                }
            }
            rows[plugin.id] = row
        }
        row.bind(
            plugin,
            onOpen = { presentFragment(PluginInfoActivity(plugin)) },
            onMenu = { anchor -> showPluginOptions(plugin, anchor) },
        ) { enabled ->
            PluginManager.setEnabled(plugin, enabled)
        }
        row.bindSettings(if (plugin.settingsPageId != null) ({ PluginUi.openRegisteredSettings(plugin) }) else null)
        row.bindActions(
            onReload = { PluginManager.reload(plugin) },
            onRemove = { removePlugin(plugin) },
        )
        val uitem = if (compact) UItem.asCustom(row, PluginRow.HEIGHT_DP) else UItem.asCustom(row)
        uitem.id = ITEM_BASE + (plugin.id.hashCode() and 0xffff)
        uitem.`object` = plugin
        return uitem
    }

    private fun applyReorder(items: List<UItem>) {
        val ordered = items.mapNotNull { it.`object` as? Plugin }
        if (ordered.size != PluginManager.plugins().size) return
        PluginManager.setOrder(ordered)
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            ENGINE_TOGGLE -> {
                PluginManager.toggleEngine()
                listView.adapter.update(true)
            }
            BUTTON_LOAD -> launchLoad()
            // plugin rows handle their own clicks (see PluginRow's background comment)
        }
    }

    private fun showPluginOptions(plugin: Plugin, anchor: View) {
        ItemOptions.makeOptions(this, anchor)
            .add(R.drawable.msg_reset, LocaleController.getString(R.string.InuPluginsReload)) {
                PluginManager.reload(plugin)
            }
            .add(R.drawable.msg_delete, LocaleController.getString(R.string.InuPluginsRemove), true) {
                removePlugin(plugin)
            }
            .show()
    }

    private fun removePlugin(plugin: Plugin) {
        PluginManager.remove(plugin)
        rows.remove(plugin.id)
        listView.adapter.update(true)
    }

    private fun launchLoad() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/javascript", "text/javascript", "text/plain", "*/*"))
        }
        try {
            startActivityForResult(intent, REQ_LOAD)
        } catch (e: Exception) {
            BulletinFactory.of(this).createErrorBulletin(e.message ?: "").show()
        }
    }

    override fun onActivityResultFragment(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_LOAD || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val ctx = context ?: parentActivity ?: return
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "plugin.js"
        Utilities.globalQueue.postRunnable {
            val source = try {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (_: Exception) {
                null
            }
            AndroidUtilities.runOnUIThread {
                if (source == null) {
                    BulletinFactory.of(this).createErrorBulletin(
                        LocaleController.getString(R.string.InuPluginsErrorRead)
                    ).show()
                    return@runOnUIThread
                }
                val err = PluginManager.import(name, source)
                if (err != null) {
                    BulletinFactory.of(this).createErrorBulletin(err).show()
                } else {
                    listView.adapter.update(true)
                }
            }
        }
    }

    companion object {
        private val ENGINE_TOGGLE = InuUtils.generateId()
        private val BUTTON_LOAD = InuUtils.generateId()
        private val SAFE_MODE_BANNER = InuUtils.generateId()
        private const val MENU_COMPACT = 1
        private const val ITEM_BASE = 20000
        private const val REQ_LOAD = 31010
    }
}

@SuppressLint("ViewConstructor")
class PluginRow(context: Context, val compact: Boolean) : LinearLayout(context) {
    private val handle: ImageView
    private val icon: BackupImageView
    private val placeholder = ResourcesCompat.getDrawable(resources, R.drawable.inu_tabler_code, null)?.mutate()?.apply {
        colorFilter = PorterDuffColorFilter(
            Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon),
            PorterDuff.Mode.SRC_IN,
        )
    }
    private val title: TextView
    private val warningDrawable = ResourcesCompat.getDrawable(resources, R.drawable.inu_tabler_alert_triangle_filled, null)
        ?.mutate()
        ?.apply {
            // bounds keep their height, so the offset only nudges the glyph off the text's optical center
            val size = AndroidUtilities.dp(WARNING_SIZE_DP)
            val offset = AndroidUtilities.dp(WARNING_OFFSET_DP)
            setBounds(0, offset, size, size + offset)
        }
    private val subtitle: TextView
    private val switch: Switch
    private val settingsButton: ImageView
    private var description: TextView? = null
    private var settingsAction: TextView? = null
    private var reloadAction: TextView? = null
    private var removeAction: TextView? = null
    private var onToggle: ((Boolean) -> Unit)? = null
    private var onOpen: (() -> Unit)? = null
    private var onMenu: ((View) -> Unit)? = null

    init {
        orientation = VERTICAL
        // the row consumes its own touches (making RecyclerListView's item-click skip it via
        // interceptedByChild) so taps on the switch/settings/handle children don't also open
        // the info page
        background = Theme.createSelectorWithBackgroundDrawable(
            Theme.getColor(Theme.key_windowBackgroundWhite),
            Theme.getColor(Theme.key_listSelector),
        )
        setOnClickListener { onOpen?.invoke() }
        setOnLongClickListener { onMenu?.invoke(this); true }
        val rtl = LocaleController.isRTL

        handle = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.list_reorder)
            colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_stickers_menu), PorterDuff.Mode.MULTIPLY)
            contentDescription = LocaleController.getString(R.string.FilterReorder)
            isClickable = true
        }
        icon = BackupImageView(context).apply {
            setRoundRadius(AndroidUtilities.dp(if (compact) 6f else 8f))
        }
        // an ellipsized line stops short of the width wrap_content took, which would leave the
        // warning drawable parked at the row's edge instead of right after the ellipsis
        title = object : TextView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) return
                val line = layout?.takeIf { it.lineCount == 1 } ?: return
                val fitted = ceil(line.getLineWidth(0)).toInt() + compoundPaddingLeft + compoundPaddingRight
                if (fitted < measuredWidth) setMeasuredDimension(fitted, measuredHeight)
            }
        }.apply {
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            textSize = 16f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            compoundDrawablePadding = AndroidUtilities.dp(5f)
            // keeps the warning off the switch once the name is long enough to ellipsize
            setPaddingRelative(0, 0, AndroidUtilities.dp(8f), 0)
        }
        subtitle = TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            textSize = 13f
            maxLines = if (compact) 2 else 1
            ellipsize = TextUtils.TruncateAt.END
        }
        settingsButton = ImageView(context).apply {
            setImageResource(R.drawable.msg_settings)
            setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon))
            background = Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_listSelector),
                Theme.RIPPLE_MASK_CIRCLE_20DP,
            )
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = LocaleController.getString(R.string.Settings)
            visibility = GONE
        }
        switch = Switch(context).apply {
            setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite)
            setOnClickListener {
                val next = !isChecked
                setChecked(next, true)
                onToggle?.invoke(next)
            }
        }

        val textBlock = LinearLayout(context).apply { orientation = VERTICAL }
        // wrap_content, so the warning drawable hugs the name instead of the row's right edge
        textBlock.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        textBlock.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f))

        if (compact) {
            minimumHeight = AndroidUtilities.dp(HEIGHT_DP.toFloat())
            val header = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(handle, LayoutHelper.createLinear(40, 48, Gravity.CENTER_VERTICAL, if (rtl) 0 else 8, 0, if (rtl) 8 else 0, 0))
            header.addView(icon, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL, if (rtl) 0 else 8, 0, if (rtl) 8 else 14, 0))
            header.addView(textBlock, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))
            header.addView(settingsButton, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, if (rtl) 0 else 4, 0))
            header.addView(switch, LayoutHelper.createLinear(37, 24, Gravity.CENTER_VERTICAL, if (rtl) 22 else 0, 0, if (rtl) 0 else 22, 0))
            addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, HEIGHT_DP))
        } else {
            // icon above the handle in one narrow gutter, so title, description and actions all
            // share a single text column instead of clearing both of them
            val gutter = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            gutter.addView(icon, LayoutHelper.createLinear(32, 32, Gravity.CENTER_HORIZONTAL, 0f, 4f, 0f, 0f))
            gutter.addView(handle, LayoutHelper.createLinear(40, 40, Gravity.CENTER_HORIZONTAL, 0f, 4f, 0f, 0f))

            val titleRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(textBlock, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))
            titleRow.addView(switch, LayoutHelper.createLinear(37, 24, Gravity.CENTER_VERTICAL, if (rtl) 22 else 0, 0, if (rtl) 0 else 22, 0))

            description = TextView(context).apply {
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
                textSize = 14f
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            }

            val actions = LinearLayout(context).apply { orientation = HORIZONTAL }
            settingsAction = mkAction(R.string.Settings, red = false).also {
                it.visibility = GONE
                actions.addView(it, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 4f, 0f, 4f, 0f))
            }
            reloadAction = mkAction(R.string.InuPluginsReload, red = false).also {
                actions.addView(it, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 4f, 0f, 4f, 0f))
            }
            removeAction = mkAction(R.string.InuPluginsRemove, red = true).also {
                actions.addView(it, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 4f, 0f, 4f, 0f))
            }

            val content = LinearLayout(context).apply { orientation = VERTICAL }
            content.addView(titleRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            content.addView(
                description,
                LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.NO_GRAVITY,
                    if (rtl) 22 else 0, 4, if (rtl) 0 else 22, 0,
                ),
            )
            content.addView(
                actions,
                LayoutHelper.createLinear(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    if (rtl) Gravity.LEFT else Gravity.RIGHT,
                    if (rtl) 8 else 0, 6, if (rtl) 0 else 8, 0,
                ),
            )

            val body = LinearLayout(context).apply { orientation = HORIZONTAL }
            body.addView(
                gutter,
                LayoutHelper.createLinear(
                    40, LayoutHelper.WRAP_CONTENT, Gravity.NO_GRAVITY,
                    if (rtl) 12 else 8, 0, if (rtl) 8 else 12, 0,
                ),
            )
            body.addView(content, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
            addView(
                body,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 10f, 0f, 10f),
            )
        }
    }

    private fun mkAction(textRes: Int, red: Boolean) = TextView(context).apply {
        val color = Theme.getColor(if (red) Theme.key_text_RedBold else Theme.key_windowBackgroundWhiteBlueText2)
        text = LocaleController.getString(textRes)
        textSize = 14f
        typeface = AndroidUtilities.bold()
        setTextColor(color)
        setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(7f), AndroidUtilities.dp(14f), AndroidUtilities.dp(7f))
        background = Theme.createRadSelectorDrawable(
            if (red) Theme.multAlpha(color, 0.12f) else Theme.getColor(Theme.key_listSelector),
            ACTION_RADIUS_DP,
            ACTION_RADIUS_DP,
        )
    }

    fun bind(plugin: Plugin, onOpen: () -> Unit, onMenu: (View) -> Unit, onToggle: (Boolean) -> Unit) {
        this.onToggle = onToggle
        this.onOpen = onOpen
        this.onMenu = onMenu
        PluginManifestIcons.bindIcon(icon, plugin.manifest.icon, placeholder)
        title.text = plugin.manifest.name
        val tier = highestGrantTier(plugin.manifest)
        val warning = warningDrawable?.takeIf { tier != null }?.apply {
            colorFilter = PorterDuffColorFilter(tierColors(tier).first, PorterDuff.Mode.SRC_IN)
        }
        title.setCompoundDrawablesRelative(null, null, warning, null)
        val failure = plugin.failure
        if (compact) {
            if (failure != null) {
                subtitle.setTextColor(Theme.getColor(Theme.key_text_RedRegular))
                subtitle.text = failure.describe()
            } else {
                subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
                subtitle.text = plugin.manifest.description
                    ?: plugin.manifest.author?.let { LocaleController.formatString(R.string.InuPluginsByAuthor, it) }
                    ?: ""
            }
        } else {
            val meta = buildMetaLine(plugin)
            subtitle.text = meta
            subtitle.visibility = if (meta.isEmpty()) GONE else VISIBLE
            val text = failure?.describe()
                ?: plugin.manifest.description(LocaleController.getInstance().currentLocaleInfo?.langCode)
            description?.let {
                it.setTextColor(Theme.getColor(if (failure != null) Theme.key_text_RedRegular else Theme.key_windowBackgroundWhiteGrayText))
                it.text = text ?: ""
                it.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
            }
        }
        switch.setChecked(plugin.enabled, false)
    }

    private fun buildMetaLine(plugin: Plugin): String {
        val parts = ArrayList<String>()
        plugin.manifest.version?.let { parts.add("v$it") }
        plugin.manifest.author?.let { parts.add(LocaleController.formatString(R.string.InuPluginsByAuthor, it)) }
        return parts.joinToString(" · ")
    }

    /** non-null == the plugin registered a settings page; shows the gear / settings action */
    fun bindSettings(onOpen: (() -> Unit)?) {
        if (compact) {
            settingsButton.visibility = if (onOpen != null) VISIBLE else GONE
            settingsButton.setOnClickListener { onOpen?.invoke() }
        } else {
            settingsAction?.visibility = if (onOpen != null) VISIBLE else GONE
            settingsAction?.setOnClickListener { onOpen?.invoke() }
        }
    }

    fun bindActions(onReload: () -> Unit, onRemove: () -> Unit) {
        reloadAction?.setOnClickListener { onReload() }
        removeAction?.setOnClickListener { onRemove() }
    }

    fun setOnReorderTouchListener(listener: OnTouchListener) {
        handle.setOnTouchListener(listener)
    }

    companion object {
        const val HEIGHT_DP = 64
        private const val ACTION_RADIUS_DP = 18
        private const val WARNING_SIZE_DP = 12.5f
        private const val WARNING_OFFSET_DP = 1.5f
    }
}
