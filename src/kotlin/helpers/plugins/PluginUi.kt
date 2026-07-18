package desu.inugram.helpers.plugins

import android.util.Log
import android.view.View
import desu.inugram.ui.settings.PluginSettingsActivity
import desu.inugram.ui.showInputDialog
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.Utilities
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.LaunchActivity

/**
 * Kotlin side of the settings-page ui bridge (rust: `ui.rs`): presents [PluginSettingsActivity]
 * pages, routes `page.invalidate()` to open pages, anchors `inu.ui.openMenu` popups to the row
 * whose event is currently dispatching, and shows `inu.ui.prompt` input dialogs.
 *
 * Threading: upcalls arrive on [Utilities.globalQueue]; anything view-touching hops to the UI
 * thread and settles back on globalQueue with the usual engine-identity check.
 */
object PluginUi {
    private const val TAG = "InuPluginUi"

    /**
     * the row behind the ui event currently being dispatched into JS. set (and cleared) around
     * `engine.uiEvent(...)` by [PluginSettingsActivity] on globalQueue; `inu.ui.openMenu` runs
     * synchronously inside that dispatch, so a non-null anchor here is exactly "a menu may open"
     */
    class EventAnchor(val activity: PluginSettingsActivity, val view: View?)
    var eventAnchor: EventAnchor? = null

    // UI-thread state: open page views, keyed per engine so page ids can't cross plugins
    private class PageKey(val engine: QuickJs, val pageId: Long) {
        override fun equals(other: Any?): Boolean =
            other is PageKey && other.engine === engine && other.pageId == pageId
        override fun hashCode(): Int = System.identityHashCode(engine) * 31 + pageId.hashCode()
    }
    private val openPages = HashMap<PageKey, MutableList<PluginSettingsActivity>>()

    // -- page lifecycle (UI thread) --

    fun onPageOpened(activity: PluginSettingsActivity) {
        openPages.getOrPut(PageKey(activity.engine, activity.pageId)) { mutableListOf() }.add(activity)
    }

    fun onPageClosed(activity: PluginSettingsActivity) {
        val key = PageKey(activity.engine, activity.pageId)
        val list = openPages[key] ?: return
        list.remove(activity)
        if (list.isNotEmpty()) return
        openPages.remove(key)
        Utilities.globalQueue.postRunnable {
            if (activity.plugin.engine === activity.engine) activity.engine.uiPageClosed(activity.pageId)
        }
    }

    // -- upcalls (globalQueue) --

    fun openPage(plugin: Plugin, engine: QuickJs, pageId: Long): String? {
        AndroidUtilities.runOnUIThread {
            val fragment = LaunchActivity.getSafeLastFragment() ?: return@runOnUIThread
            fragment.presentFragment(PluginSettingsActivity(plugin, engine, pageId))
        }
        return null
    }

    fun registerSettings(plugin: Plugin, pageId: Long) {
        plugin.settingsPageId = pageId
    }

    fun invalidate(engine: QuickJs, pageId: Long) {
        AndroidUtilities.runOnUIThread {
            openPages[PageKey(engine, pageId)]?.lastOrNull()?.requestRender()
        }
    }

    fun openMenu(plugin: Plugin, engine: QuickJs, menuId: Long, itemsJson: String): String? {
        val anchor = eventAnchor
            ?: return "openMenu: no active item event"
        val anchorView = anchor.view
            ?: return "openMenu: the current event has no anchor row"
        val items = try {
            parseMenuItems(itemsJson)
        } catch (e: Exception) {
            return "openMenu: ${e.message}"
        }
        val activity = anchor.activity
        AndroidUtilities.runOnUIThread {
            fun settle(slot: Int) {
                Utilities.globalQueue.postRunnable {
                    if (plugin.engine === engine) engine.uiMenuClick(menuId, slot)
                }
            }
            if (activity.parentActivity == null || !anchorView.isAttachedToWindow) {
                settle(-1)
                return@runOnUIThread
            }
            var clicked = false
            val opts = ItemOptions.makeOptions(activity, anchorView)
            // any item specifying `checked` (even false) makes the menu radio-style: every row
            // goes through addChecked so texts align on the checkmark column (cf. RadioItemOptions)
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
                Utilities.globalQueue.postRunnable {
                    if (plugin.engine === engine) engine.resolvePrompt(requestId, text)
                }
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

    /** entry point for the settings button in the plugins list */
    fun openRegisteredSettings(plugin: Plugin) {
        Utilities.globalQueue.postRunnable {
            val engine = plugin.engine ?: return@postRunnable
            val pageId = plugin.settingsPageId ?: return@postRunnable
            val err = openPage(plugin, engine, pageId)
            if (err != null) Log.e(TAG, "openRegisteredSettings: $err")
        }
    }
}
