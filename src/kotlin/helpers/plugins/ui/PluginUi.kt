package desu.inugram.helpers.plugins.ui

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.widget.LinearLayout
import desu.inugram.core.plugins.TlWire
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginDispatch
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.platform.PluginJvm
import desu.inugram.ui.settings.PluginSettingsActivity
import desu.inugram.ui.settings.RadioDialogBuilder
import desu.inugram.ui.showInputDialog
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.CheckBoxCell
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.LaunchActivity

/**
 * Kotlin side of the settings-page ui bridge (rust: `ui.rs`): presents [PluginSettingsActivity]
 * pages, routes `page.invalidate()` to open pages, anchors `UIAnchor.openMenu` popups to the row
 * the anchor names, and shows the `inu.ui.prompt`/`inu.ui.chooser` modals.
 *
 * Threading: upcalls arrive on [Utilities.globalQueue]; anything view-touching hops to the UI
 * thread and settles back on globalQueue with the usual engine-identity check.
 */
object PluginUi {
    private const val TAG = "InuPluginUi"

    // UI-thread state: open page views, keyed per engine so page ids can't cross plugins
    private class PageKey(val engine: QuickJs, val pageId: Long) {
        override fun equals(other: Any?): Boolean =
            other is PageKey && other.engine === engine && other.pageId == pageId
        override fun hashCode(): Int = System.identityHashCode(engine) * 31 + pageId.hashCode()
    }
    private val openPages = HashMap<PageKey, MutableList<PluginSettingsActivity>>()

    fun onPageOpened(activity: PluginSettingsActivity) {
        openPages.getOrPut(PageKey(activity.engine, activity.pageId)) { mutableListOf() }.add(activity)
    }

    fun onPageClosed(activity: PluginSettingsActivity) {
        val key = PageKey(activity.engine, activity.pageId)
        val list = openPages[key] ?: return
        list.remove(activity)
        if (list.isNotEmpty()) return
        openPages.remove(key)
        PluginDispatch.onEngine(activity.plugin, activity.engine) {
            activity.engine.uiPageClosed(activity.pageId)
        }
    }

    /**
     * the engine is closed right after this, and a frozen page whose rows do nothing is worse than
     * no page. The key is dropped before the fragment is, so the teardown that follows does not
     * try to tell the (by then closed) engine that its page closed.
     */
    fun detach(engine: QuickJs) {
        AndroidUtilities.runOnUIThread {
            val mine = openPages.filterKeys { it.engine === engine }
            for ((key, list) in mine) {
                openPages.remove(key)
                // not finishFragment(), which closes whatever is on top: a plugin page can be buried under one the user opened from it
                for (activity in list.toList()) activity.removeSelfFromStack()
            }
        }
    }

    fun openPage(plugin: Plugin, engine: QuickJs, pageId: Long): String? {
        AndroidUtilities.runOnUIThread {
            val fragment = LaunchActivity.getSafeLastFragment() ?: return@runOnUIThread
            fragment.presentFragment(PluginSettingsActivity(plugin, engine, pageId))
        }
        return null
    }

    /** the handle is resolved before the ui-thread hop, so naming something that is not a `BaseFragment` throws where the plugin can catch it */
    fun openFragment(engine: QuickJs, handle: Long): String? {
        val fragment = PluginJvm.objectAt(engine, handle)
            ?: return TlWire.encodePluginError("handle-expired", "openPage: that java object is gone")
        if (fragment !is BaseFragment) {
            return TlWire.encodePluginError(
                "invalid-argument",
                "openPage: expected a BaseFragment, got ${fragment.javaClass.name}",
            )
        }
        AndroidUtilities.runOnUIThread {
            LaunchActivity.getSafeLastFragment()?.presentFragment(fragment)
        }
        return null
    }

    fun registerSettings(plugin: Plugin, pageId: Long) {
        plugin.settingsPageId = pageId
    }

    /** guarded by the page id: disposing a page the plugin has already replaced must not clear it */
    fun unregisterSettings(plugin: Plugin, pageId: Long) {
        if (plugin.settingsPageId == pageId) plugin.settingsPageId = null
    }

    fun invalidate(engine: QuickJs, pageId: Long) {
        AndroidUtilities.runOnUIThread {
            openPages[PageKey(engine, pageId)]?.lastOrNull()?.requestRender()
        }
    }

    /**
     * the anchor names a row, not a view: by the time a plugin opens a menu the page may have
     * re-rendered and the row's view been recycled onto another row. So a row no longer on screen
     * leaves the menu unopened and settled as dismissed - the same answer as tapping outside.
     */
    fun openMenu(plugin: Plugin, engine: QuickJs, menuId: Long, pageId: Long, anchorKey: String, itemsJson: String): String? {
        val items = try {
            parseMenuItems(itemsJson)
        } catch (e: Exception) {
            return "openMenu: ${e.message}"
        }
        AndroidUtilities.runOnUIThread {
            fun settle(slot: Int) {
                PluginDispatch.onEngine(plugin, engine) { engine.uiMenuClick(menuId, slot) }
            }
            val activity = openPages[PageKey(engine, pageId)]?.lastOrNull()
            val anchorView = activity?.anchorViewFor(anchorKey)
            if (activity == null || anchorView == null || activity.parentActivity == null || !anchorView.isAttachedToWindow) {
                settle(-1)
                return@runOnUIThread
            }
            var clicked = false
            val opts = ItemOptions.makeOptions(activity, anchorView)
            // any item specifying `checked` (even false) makes the menu radio-style, so every row goes through addChecked to align on the checkmark column
            val radioStyle = items.any { it.checked != null }
            items.forEachIndexed { index, item ->
                val onClick = Runnable {
                    clicked = true
                    settle(index)
                    activity.requestRender()
                }
                if (radioStyle) {
                    opts.addChecked(item.checked == true, item.text, onClick)
                } else {
                    opts.add(0, item.text, item.danger, onClick)
                }
            }
            opts.setOnDismiss {
                if (!clicked) settle(-1)
            }
            opts.show()
        }
        return null
    }

    private class MenuItem(val text: String, val checked: Boolean?, val danger: Boolean)

    private fun parseMenuItems(json: String): List<MenuItem> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            MenuItem(
                o.getString("text"),
                if (o.has("checked")) o.getBoolean("checked") else null,
                o.optBoolean("danger"),
            )
        }
    }

    fun prompt(plugin: Plugin, engine: QuickJs, requestId: Long, optionsJson: String): String? {
        val options = try {
            JSONObject(optionsJson)
        } catch (e: Exception) {
            return "prompt: ${e.message}"
        }
        AndroidUtilities.runOnUIThread {
            var settled = false
            fun settle(text: String?) {
                if (settled) return
                settled = true
                PluginDispatch.onEngine(plugin, engine) { engine.resolvePrompt(requestId, text) }
            }

            val fragment = LaunchActivity.getSafeLastFragment()
            if (fragment == null) {
                settle(null)
                return@runOnUIThread
            }
            val dialog = showInputDialog(
                fragment,
                title = options.optString("title"),
                hint = options.optString("hint").takeIf { it.isNotEmpty() },
                initialText = options.optString("value").takeIf { it.isNotEmpty() },
                selectAll = options.optBoolean("selectAll"),
            ) { text ->
                settle(text)
                true
            }
            if (dialog == null) {
                settle(null)
            } else {
                dialog.setOnDismissListener { settle(null) }
            }
        }
        return null
    }

    /**
     * one dialog for both modes, the engine having normalized `selected` into a list. Exactly one
     * settle either way - the engine drops a second, but the promise must never be left hanging.
     */
    fun chooser(plugin: Plugin, engine: QuickJs, requestId: Long, optionsJson: String): String? {
        val options = try {
            JSONObject(optionsJson)
        } catch (e: Exception) {
            return "chooser: ${e.message}"
        }
        val items = try {
            parseChooserItems(options.getJSONArray("items"))
        } catch (e: Exception) {
            return "chooser: ${e.message}"
        }
        val multiple = options.optBoolean("multiple")
        val selected = options.optJSONArray("selected")
        val picked = (0 until (selected?.length() ?: 0)).mapTo(HashSet()) { selected!!.getInt(it) }

        AndroidUtilities.runOnUIThread {
            var settled = false
            fun settle(result: String?) {
                if (settled) return
                settled = true
                PluginDispatch.onEngine(plugin, engine) { engine.resolveChooser(requestId, result) }
            }

            val activity = LaunchActivity.instance
            if (activity == null || activity.isFinishing) {
                settle(null)
                return@runOnUIThread
            }
            try {
                val fragment = LaunchActivity.getSafeLastFragment()
                val theme = fragment?.resourceProvider
                val title = options.optString("title").takeIf { it.isNotEmpty() }
                val dialog = if (multiple) {
                    buildMultiChooser(activity, theme, title, items, picked, ::settle)
                } else {
                    buildSingleChooser(activity, theme, title, items, picked.firstOrNull(), ::settle)
                }
                dialog.setOnDismissListener { settle(null) }
                if (fragment?.showDialog(dialog) == null) dialog.show()
            } catch (e: Exception) {
                Log.e(TAG, "chooser failed", e)
                settle(null)
            }
        }
        return null
    }

    private class ChooserItem(val text: String, val subtitle: String?, val danger: Boolean)

    private fun parseChooserItems(arr: JSONArray): List<ChooserItem> =
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ChooserItem(
                o.getString("text"),
                o.optString("subtitle").takeIf { it.isNotEmpty() },
                o.optBoolean("danger"),
            )
        }

    /** danger is a red text colour rather than a cell flag: no stock list cell exposes one */
    private fun chooserLabel(item: ChooserItem, theme: Theme.ResourcesProvider?): CharSequence {
        if (!item.danger) return item.text
        val text = SpannableString(item.text)
        text.setSpan(
            ForegroundColorSpan(Theme.getColor(Theme.key_text_RedRegular, theme)),
            0,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return text
    }

    private fun buildSingleChooser(
        context: Context,
        theme: Theme.ResourcesProvider?,
        title: String?,
        items: List<ChooserItem>,
        selected: Int?,
        settle: (String?) -> Unit,
    ): AlertDialog {
        val builder = RadioDialogBuilder(context, theme)
        if (title != null) builder.setTitle(title)
        builder.setItems(
            items.map { RadioDialogBuilder.Item(chooserLabel(it, theme), it.subtitle) },
            selected ?: -1,
        ) { _, index -> settle(index.toString()) }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null)
        return builder.create()
    }

    private fun buildMultiChooser(
        context: Context,
        theme: Theme.ResourcesProvider?,
        title: String?,
        items: List<ChooserItem>,
        selected: Set<Int>,
        settle: (String?) -> Unit,
    ): AlertDialog {
        val ticked = selected.toMutableSet()
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { index, item ->
            val cell = CheckBoxCell(context, CheckBoxCell.TYPE_CHECK_BOX_DEFAULT, 21, theme)
            cell.setText(chooserLabel(item, theme), item.subtitle.orEmpty(), index in ticked, false)
            cell.background = Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_listSelector, theme),
                Theme.RIPPLE_MASK_ALL,
            )
            cell.setOnClickListener {
                val now = !cell.isChecked
                cell.setChecked(now, true)
                if (now) ticked.add(index) else ticked.remove(index)
            }
            container.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50))
        }
        val builder = AlertDialog.Builder(context, theme)
        if (title != null) builder.setTitle(title)
        builder.setView(container)
        builder.setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ ->
            settle(ticked.sorted().joinToString(","))
        }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null)
        return builder.create()
    }

    fun openRegisteredSettings(plugin: Plugin) {
        Utilities.globalQueue.postRunnable {
            val engine = plugin.engine ?: return@postRunnable
            val pageId = plugin.settingsPageId ?: return@postRunnable
            val err = openPage(plugin, engine, pageId)
            if (err != null) Log.e(TAG, "openRegisteredSettings: $err")
        }
    }
}
