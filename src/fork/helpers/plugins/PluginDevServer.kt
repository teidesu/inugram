package desu.inugram.helpers.plugins

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import desu.inugram.InuConfig
import desu.inugram.core.plugins.PluginManifestParser
import desu.inugram.helpers.plugins.ui.PluginAppVisibility
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.BulletinFactory

/**
 * Installs or reloads source pushed by `adb` into [DIR] under the app's external files directory,
 * then announced through [ACTION]. Used by `inu dev` (`sdk/cli`).
 *
 * Skips trust and permission review, with no undo. The receiver exists only after the user
 * enables [InuConfig.PLUGINS_DEV_MODE] through its warning sheet. It requires the sender's
 * `android.permission.DUMP`, held by `adb shell` and unavailable to ordinary installed apps.
 *
 * Reads source only from the drop directory, never broadcast extras. A broadcast must name
 * a file already placed there by something with write access.
 */
object PluginDevServer {
    const val ACTION = "desu.inugram.plugins.DEV"
    const val DIR = "plugin-dev"

    private const val SENDER_PERMISSION = "android.permission.DUMP"

    private val safeName = Regex("[A-Za-z0-9._-]+")

    private var registered = false

    /** brings the receiver up or down to match the toggle; safe to call repeatedly */
    fun sync(context: Context) {
        val app = context.applicationContext
        if (InuConfig.PLUGINS_DEV_MODE.value) register(app) else unregister(app)
    }

    fun dropDir(context: Context): File? = context.applicationContext.getExternalFilesDir(DIR)

    private fun register(context: Context) {
        if (registered) return
        try {
            val filter = IntentFilter(ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                context.registerReceiver(receiver, filter, SENDER_PERMISSION, null, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter, SENDER_PERMISSION, null)
            }
            registered = true
            PluginLog.HOST.d("dev", "listening on $ACTION, drop dir ${dropDir(context)}")
        } catch (e: Exception) {
            PluginLog.HOST.e("dev", "could not register", e)
        }
    }

    private fun unregister(context: Context) {
        if (!registered) return
        registered = false
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            PluginLog.HOST.e("dev", "could not unregister", e)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION) return
            // reads and parses a file on the main thread, unlike every other install path: the reply
            // below is what the script prints, and an ordered broadcast's result has to be set
            // before onReceive returns. dev-only, and the file is the one being edited
            val reply = try {
                handle(context, intent)
            } catch (e: Exception) {
                PluginLog.HOST.e("dev", "command failed", e)
                fail(e.message ?: e.toString())
            }
            if (isOrderedBroadcast) resultData = reply.toString() else PluginLog.HOST.d("dev", reply.toString())
        }
    }

    private fun handle(context: Context, intent: Intent): JSONObject {
        return when (val cmd = intent.getStringExtra("cmd") ?: "ping") {
            "ping" -> JSONObject()
                .put("ok", true)
                .put("pluginApi", PluginManager.PLUGIN_API_VERSION)
                .put("engineEnabled", PluginManager.isEngineEnabled())
                .put("safeMode", PluginManager.safeMode)
                .put("dropDir", dropDir(context)?.absolutePath ?: "")

            "list" -> JSONObject().put("ok", true).put("plugins", listPlugins())
            "install" -> install(context, intent.getStringExtra("file"))
            "remove" -> remove(context, intent.getStringExtra("file"))
            else -> fail("unknown command: $cmd")
        }
    }

    private fun listPlugins(): JSONArray {
        val arr = JSONArray()
        for (plugin in PluginManager.plugins()) {
            arr.put(describe(plugin))
        }
        return arr
    }

    private fun describe(plugin: Plugin): JSONObject = JSONObject()
        .put("id", plugin.id)
        .put("name", plugin.manifest.name)
        .putOpt("pluginId", plugin.manifest.id)
        .put("file", plugin.file.name)
        .put("enabled", plugin.enabled)
        .put("running", plugin.running)
        .put("dev", plugin.dev)
        .putOpt("failure", plugin.failure?.describe())

    /** [name] null installs every source in the drop dir, in name order */
    private fun install(context: Context, name: String?): JSONObject {
        val results = resolve(context, name).map { installOne(it) }
        if (results.isEmpty()) return fail("nothing to install in ${dropDir(context)}")
        announce(results.filter { it.optBoolean("ok") })
        val arr = JSONArray()
        for (result in results) arr.put(result)
        return JSONObject().put("ok", results.all { it.optBoolean("ok") }).put("results", arr)
    }

    /**
     * Shows a bulletin for a dev install, which has no review sheet or undo bulletin.
     * Skip it in the background to avoid showing it on an unrelated screen later.
     * Already runs on the main thread as part of the receiver.
     */
    private fun announce(installed: List<JSONObject>) {
        if (installed.isEmpty() || !PluginAppVisibility.isForeground) return
        val text = if (installed.size == 1) {
            val one = installed[0]
            val name = one.optJSONObject("plugin")?.optString("name").orEmpty()
            val res = if (one.optString("action") == "installed") R.string.InuPluginInstalled else R.string.InuPluginUpdated
            LocaleController.formatString(res, name)
        } else {
            LocaleController.formatPluralString("InuPluginsDevPushed", installed.size)
        }
        BulletinFactory.global().createSimpleBulletin(R.raw.info, text).show()
    }

    private fun installOne(file: File): JSONObject {
        val source = try {
            file.readText()
        } catch (e: Exception) {
            return failFile(file, "${e.message ?: e}")
        }
        val manifest = PluginManifestParser.parseOrNull(source)
            ?: return failFile(file, "no valid manifest header")
        PluginManager.incompatibility(manifest)?.let { return failFile(file, it) }

        val target = PluginManager.findUpdateTarget(manifest)
        if (target != null) {
            PluginManager.update(target, source, dev = true)?.let { return failFile(file, it) }
            return installed("updated", file, target)
        }
        return when (val result = PluginManager.import(file.name, source, enabled = true, dev = true)) {
            is PluginManager.ImportResult.Refused -> failFile(file, result.reason)
            is PluginManager.ImportResult.Installed -> installed("installed", file, result.plugin)
        }
    }

    private fun failFile(file: File, reason: String): JSONObject = fail("${file.name}: $reason")

    private fun installed(action: String, file: File, plugin: Plugin): JSONObject = JSONObject()
        .put("ok", true)
        .put("action", action)
        .put("file", file.name)
        .put("plugin", describe(plugin))

    /**
     * uninstalls what the dropped [name] identifies. Resolved through the file's own plugin id, the
     * same way an install of it would land - the install's name on disk is [PluginStore]'s to
     * choose and need not be the pushed one, so that is only the fallback.
     */
    private fun remove(context: Context, name: String?): JSONObject {
        if (name == null) return fail("remove needs --es file <name>")
        val file = resolve(context, name).firstOrNull()
        val byIdentity = file
            ?.let { runCatching { it.readText() }.getOrNull() }
            ?.let { PluginManifestParser.parseOrNull(it) }
            ?.let { PluginManager.findUpdateTarget(it) }
        val target = byIdentity ?: PluginManager.plugins().firstOrNull { it.file.name == name }
            ?: return fail("not installed: $name")
        val described = describe(target)
        PluginManager.remove(target)
        return JSONObject().put("ok", true).put("action", "removed").put("plugin", described)
    }

    /** the drop-dir files [name] means, refusing anything that isn't a plain name inside it */
    private fun resolve(context: Context, name: String?): List<File> {
        val dir = dropDir(context) ?: return emptyList()
        if (name == null) {
            return dir.listFiles { f -> f.isFile && f.name.endsWith(".js") }?.sortedBy { it.name } ?: emptyList()
        }
        if (name == "." || name == ".." || !safeName.matches(name)) return emptyList()
        return listOf(File(dir, name)).filter { it.isFile }
    }

    private fun fail(error: String): JSONObject = JSONObject().put("ok", false).put("error", error)
}
