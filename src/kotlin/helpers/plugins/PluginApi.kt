package desu.inugram.helpers.plugins

import android.widget.Toast
import desu.inugram.core.plugins.TlWire
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.LaunchActivity

/**
 * Wires a plugin engine's `inu.kv`/`inu.ui.*` upcalls (rust: `api.rs`) into [PluginKv] and the app
 * UI. Upcalls arrive on [Utilities.globalQueue]; anything touching views hops to the UI thread and
 * settles back on globalQueue (same pattern as [PluginRpc.invokeRpc]).
 */
object PluginApi {
    /** call once the plugin's engine has been created, before evaluating its source */
    fun attach(plugin: Plugin, engine: QuickJs) {
        engine.apiListener = object : QuickJs.ApiListener {
            override fun kv(op: Int, key: String, value: String): String {
                // install-time gating already hides inu.kv without the grant; this is belt-and-braces
                if (!plugin.permissions.has("inu.kv")) return TlWire.encodeError("kv: not granted")
                return PluginKv.handleOp(plugin.id, op, key, value)
            }

            override fun uiToast(text: String) {
                AndroidUtilities.runOnUIThread {
                    Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_SHORT).show()
                }
            }

            override fun uiDialog(requestId: Long, optionsJson: String): String? =
                showDialog(plugin, engine, requestId, optionsJson)

            override fun uiPrompt(requestId: Long, optionsJson: String): String? =
                PluginUi.prompt(plugin, engine, requestId, optionsJson)

            override fun uiOpenPage(pageId: Long): String? =
                PluginUi.openPage(plugin, engine, pageId)

            override fun uiRegisterSettings(pageId: Long) =
                PluginUi.registerSettings(plugin, pageId)

            override fun uiInvalidate(pageId: Long) =
                PluginUi.invalidate(engine, pageId)

            override fun uiOpenMenu(menuId: Long, itemsJson: String): String? =
                PluginUi.openMenu(plugin, engine, menuId, itemsJson)
        }
        engine.installApi(allowKv = plugin.permissions.has("inu.kv"))
    }

    private fun showDialog(plugin: Plugin, engine: QuickJs, requestId: Long, optionsJson: String): String? {
        val options = try {
            JSONObject(optionsJson)
        } catch (e: Exception) {
            return "dialog: ${e.message}"
        }
        AndroidUtilities.runOnUIThread {
            var settled = false
            fun settle(result: String) {
                if (settled) return
                settled = true
                Utilities.globalQueue.postRunnable {
                    // identity check, not just null: after a reload plugin.engine is a *new* engine
                    // whose request ids restart - a stale settle must not hit it
                    if (plugin.engine === engine) engine.resolveDialog(requestId, result)
                }
            }

            val activity = LaunchActivity.instance
            if (activity == null || activity.isFinishing) {
                settle("dismissed")
                return@runOnUIThread
            }
            try {
                val builder = AlertDialog.Builder(activity)
                options.optString("title").takeIf { it.isNotEmpty() }?.let { builder.setTitle(it) }
                options.optString("message").takeIf { it.isNotEmpty() }?.let { builder.setMessage(it) }
                options.optString("positive").takeIf { it.isNotEmpty() }?.let {
                    builder.setPositiveButton(it) { _, _ -> settle("positive") }
                }
                options.optString("negative").takeIf { it.isNotEmpty() }?.let {
                    builder.setNegativeButton(it) { _, _ -> settle("negative") }
                }
                options.optString("neutral").takeIf { it.isNotEmpty() }?.let {
                    builder.setNeutralButton(it) { _, _ -> settle("neutral") }
                }
                val dialog = builder.create()
                // buttons settle first (their click listeners run before dismissal), so this only
                // catches back-press / outside-tap / activity teardown
                dialog.setOnDismissListener { settle("dismissed") }
                val fragment = LaunchActivity.getSafeLastFragment()
                // BaseFragment.showDialog returns null when it refuses to show (mid-transition
                // etc.) - without the fallback the promise would hang forever
                if (fragment?.showDialog(dialog) == null) dialog.show()
            } catch (e: Exception) {
                settle("dismissed")
            }
        }
        return null
    }
}
