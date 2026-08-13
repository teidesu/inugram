package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
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
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.Switch
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class PluginsActivity : SettingsPageActivity() {
    private val rows = HashMap<String, PluginRow>()
    private var reorderSectionId = -1

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
        val view = super.createView(context)
        listView.allowReorder(true)
        listView.listenReorder { id, items -> if (id == reorderSectionId) applyReorder(items) }
        return view
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuPluginsInfo)))
        items.add(
            mkTwoLineCheckItem(
                ENGINE_TOGGLE,
                R.string.InuPluginsEnableEngine,
                R.string.InuPluginsEnableEngineInfo,
                PluginManager.isEngineEnabled(),
            )
        )
        // the rows below still read as enabled, because they are: safe mode is about this session
        // and nothing else, and a session that ran nothing has to say so somewhere
        safeModeNotice()?.let { items.add(UItem.asShadow(it)) }

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

    private fun buildRow(plugin: Plugin): UItem {
        val row = rows.getOrPut(plugin.id) { PluginRow(context) }
        row.bind(plugin, onMenu = { anchor -> showPluginOptions(plugin, anchor) }) { enabled ->
            PluginManager.setEnabled(plugin, enabled)
        }
        row.bindSettings(if (plugin.settingsPageId != null) ({ PluginUi.openRegisteredSettings(plugin) }) else null)
        val uitem = UItem.asCustom(row, PluginRow.HEIGHT_DP)
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
                PluginManager.remove(plugin)
                rows.remove(plugin.id)
                listView.adapter.update(true)
            }
            .show()
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
        private const val ITEM_BASE = 20000
        private const val REQ_LOAD = 31010
    }
}

@SuppressLint("ViewConstructor")
class PluginRow(context: Context) : LinearLayout(context) {
    private val icon: BackupImageView
    private val placeholder = ResourcesCompat.getDrawable(resources, R.drawable.inu_tabler_code, null)?.mutate()?.apply {
        colorFilter = PorterDuffColorFilter(
            Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon),
            PorterDuff.Mode.SRC_IN,
        )
    }
    private val title: TextView
    private val subtitle: TextView
    private val switch: Switch
    private val settingsButton: ImageView
    private var onToggle: ((Boolean) -> Unit)? = null
    private var onMenu: ((View) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = AndroidUtilities.dp(HEIGHT_DP.toFloat())
        // the row consumes its own touches (making RecyclerListView's item-click skip it via
        // interceptedByChild) so taps on the switch/settings children don't also open the menu
        background = Theme.createSelectorWithBackgroundDrawable(
            Theme.getColor(Theme.key_windowBackgroundWhite),
            Theme.getColor(Theme.key_listSelector),
        )
        setOnClickListener { onMenu?.invoke(this) }
        val rtl = LocaleController.isRTL

        icon = BackupImageView(context).apply {
            setRoundRadius(AndroidUtilities.dp(6f))
        }
        addView(icon, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL, if (rtl) 0 else 18, 0, if (rtl) 18 else 14, 0))

        val textBlock = LinearLayout(context).apply { orientation = VERTICAL }
        title = TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            textSize = 16f
            setSingleLine(true)
        }
        subtitle = TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            textSize = 13f
            maxLines = 2
        }
        textBlock.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        textBlock.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f))
        addView(textBlock, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))

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
        addView(settingsButton, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, if (rtl) 0 else 4, 0))

        switch = Switch(context).apply {
            setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite)
            setOnClickListener {
                val next = !isChecked
                setChecked(next, true)
                onToggle?.invoke(next)
            }
        }
        addView(switch, LayoutHelper.createLinear(37, 24, Gravity.CENTER_VERTICAL, if (rtl) 22 else 0, 0, if (rtl) 0 else 22, 0))
    }

    fun bind(plugin: Plugin, onMenu: (View) -> Unit, onToggle: (Boolean) -> Unit) {
        this.onToggle = onToggle
        this.onMenu = onMenu
        PluginManifestIcons.bindIcon(icon, plugin.manifest.icon, placeholder)
        title.text = plugin.manifest.name
        val failure = plugin.failure
        if (failure != null) {
            subtitle.setTextColor(Theme.getColor(Theme.key_text_RedRegular))
            subtitle.text = failure.describe()
        } else {
            subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            subtitle.text = plugin.manifest.description
                ?: plugin.manifest.author?.let { LocaleController.formatString(R.string.InuPluginsByAuthor, it) }
                ?: ""
        }
        switch.setChecked(plugin.enabled, false)
    }

    /** non-null == the plugin registered a settings page; shows the gear */
    fun bindSettings(onOpen: (() -> Unit)?) {
        settingsButton.visibility = if (onOpen != null) VISIBLE else GONE
        settingsButton.setOnClickListener { onOpen?.invoke() }
    }

    companion object {
        const val HEIGHT_DP = 64
    }
}
