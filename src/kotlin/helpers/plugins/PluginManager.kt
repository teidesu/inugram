package desu.inugram.helpers.plugins

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import desu.inugram.InuConfig
import desu.inugram.core.plugins.PluginManifestParser
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.BuildVars
import org.telegram.messenger.LocaleController
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLRPC
import org.telegram.ui.LaunchActivity
import java.io.File

/**
 * Owns the plugin set: discovery, persistence (order + enabled), and the QuickJs engines.
 *
 * v0 only runs the script (exposing `console.*`). All engine ops are funnelled through
 * [Utilities.globalQueue] (single-threaded) so every engine keeps its same-thread invariant.
 * Structural list/flag mutations happen on the UI thread.
 */
object PluginManager {
    const val SAFE_MODE_ACTION = "desu.inugram.action.SAFE_MODE"
    private const val SAFE_MODE_SHORTCUT_ID = "inu_safe_mode"
    private const val TAG = "InuPlugin"
    private const val PLUGIN_API_VERSION = 1
    private const val BUNDLED_DIR = "inu_plugins"

    // set (commit) before plugins run at boot, cleared once boot is stable; still-set on the next
    // boot means the previous one bricked before reaching interactive UI -> skip plugins that boot
    private const val CRASH_GUARD_KEY = "plugins_boot_guard"

    // one-shot flag set by the safe-mode shortcut; honoured (and cleared) on the next boot
    private const val FORCE_SAFE_KEY = "plugins_force_safe"

    private lateinit var appContext: Context
    private val pluginsDir by lazy { File(appContext.filesDir, "inugram_plugins").apply { mkdirs() } }
    private val appBuild by lazy {
        try {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionCode.toString()
        } catch (_: Exception) {
            "0"
        }
    }

    private val plugins = mutableListOf<Plugin>()

    /** when true no plugin runs this process (crash guard tripped or user-forced) */
    var safeMode = false
        private set
    private var started = false
    private var lateInited = false
    private var bootMarkedStable = false

    /** settings page subscribes to refresh its list after async load/run results */
    var onChanged: (() -> Unit)? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        copyBundledPlugins()
        scan()
        maybeStart()
    }

    /** debug-only: copy bundled assets/inu_plugins/ *.js into the install dir (overwrite for dev) */
    private fun copyBundledPlugins() {
        if (!BuildConfig.DEBUG) return
        val names = try {
            appContext.assets.list(BUNDLED_DIR)
        } catch (e: Exception) {
            Log.e(TAG, "list bundled plugins failed", e)
            null
        } ?: return
        for (name in names) {
            if (!name.endsWith(".js")) continue
            try {
                val text = appContext.assets.open("$BUNDLED_DIR/$name").use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                File(pluginsDir, name).writeText(text)
            } catch (e: Exception) {
                Log.e(TAG, "copy bundled plugin failed: $name", e)
            }
        }
    }

    fun isEngineEnabled(): Boolean = InuConfig.PLUGINS_ENABLED.value

    fun plugins(): List<Plugin> = plugins.toList()

    // -- lifecycle --

    /**
     * runs from [init] at ApplicationLoader.onCreate — as early as possible so interceptors are
     * registered before the app fires its boot RPCs. safe mode is a persisted crash guard rather
     * than an intent (the launch intent isn't available this early): the flag is committed before
     * plugins run and cleared once boot reaches interactive UI ([onAppInteractive]); if it's still
     * set on the next boot the previous one bricked, so plugins are skipped.
     */
    private fun maybeStart() {
        if (started) return
        started = true
        if (!isEngineEnabled()) return
        val prefs = InuConfig.prefs
        val forced = prefs.getBoolean(FORCE_SAFE_KEY, false)
        val crashed = prefs.getBoolean(CRASH_GUARD_KEY, false)
        if (forced || crashed) {
            safeMode = true
            prefs.edit(commit = true) {
                putBoolean(FORCE_SAFE_KEY, false)
                putBoolean(CRASH_GUARD_KEY, false)
            }
            Log.w(TAG, "safe mode: ${if (forced) "user-requested" else "previous boot bricked"}; skipping plugins")
            return
        }
        prefs.edit(commit = true) { putBoolean(CRASH_GUARD_KEY, true) }
        for (plugin in plugins) if (plugin.enabled) run(plugin)
    }

    /**
     * called from a late, stable lifecycle point (LaunchActivity resume). boot survived the risky
     * early-RPC window, so clear the crash guard; also registers the safe-mode shortcut here since
     * it needs LocaleController, which is unavailable at [init] time.
     */
    fun onAppInteractive() {
        if (!lateInited) {
            lateInited = true
            registerSafeModeShortcut()
        }
        if (!bootMarkedStable && started && !safeMode) {
            bootMarkedStable = true
            InuConfig.prefs.edit(commit = true) { putBoolean(CRASH_GUARD_KEY, false) }
        }
    }

    /** user tapped the safe-mode shortcut: persist a one-shot force flag and restart into safe mode */
    fun requestSafeModeRestart() {
        InuConfig.prefs.edit(commit = true) { putBoolean(FORCE_SAFE_KEY, true) }
        restartProcess()
    }

    private fun restartProcess() {
        val intent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (intent != null) {
            val pending = PendingIntent.getActivity(
                appContext, 0, intent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val alarm = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarm?.set(AlarmManager.RTC, System.currentTimeMillis() + 100, pending)
        }
        Runtime.getRuntime().exit(0)
    }

    fun toggleEngine(): Boolean {
        val enabled = InuConfig.PLUGINS_ENABLED.toggle()
        if (enabled && !safeMode) {
            for (plugin in plugins) if (plugin.enabled) run(plugin)
        } else {
            for (plugin in plugins) stop(plugin)
        }
        return enabled
    }

    // -- per-plugin actions --

    fun setEnabled(plugin: Plugin, enabled: Boolean) {
        plugin.enabled = enabled
        persist()
        if (enabled) {
            if (isEngineEnabled() && !safeMode) run(plugin)
        } else {
            stop(plugin)
        }
    }

    fun reload(plugin: Plugin) {
        stop(plugin)
        val source = try {
            plugin.file.readText()
        } catch (e: Exception) {
            plugin.error = e.message ?: e.toString()
            notifyChanged()
            return
        }
        val manifest = PluginManifestParser.parseOrNull(source)
        if (manifest == null) {
            plugin.error = "invalid manifest"
            notifyChanged()
            return
        }
        plugin.source = source
        plugin.manifest = manifest
        plugin.error = null
        notifyChanged()
        if (plugin.enabled && isEngineEnabled() && !safeMode) run(plugin)
    }

    /** copies raw plugin source into the plugins dir, registers and (if applicable) runs it */
    fun import(suggestedName: String, source: String): String? {
        val manifest = PluginManifestParser.parseOrNull(source)
            ?: return getString(R.string.InuPluginsErrorNoManifest)
        val target = uniqueFile(suggestedName)
        target.writeText(source)
        val existing = plugins.firstOrNull { it.manifest.id == manifest.id }
        if (existing != null) {
            // replace in place, keep position
            stop(existing)
            existing.file.takeIf { it != target }?.delete()
            val idx = plugins.indexOf(existing)
            plugins[idx] = Plugin(target, source, manifest).apply { enabled = existing.enabled }
        } else {
            plugins.add(Plugin(target, source, manifest))
        }
        persist()
        notifyChanged()
        plugins.firstOrNull { it.file == target }?.let {
            if (it.enabled && isEngineEnabled() && !safeMode) run(it)
        }
        return null
    }

    fun remove(plugin: Plugin) {
        stop(plugin)
        // same queue as stop()'s runnable, so the wipe is ordered after the engine is gone
        Utilities.globalQueue.postRunnable { PluginKv.wipe(plugin.id) }
        plugin.file.delete()
        plugins.remove(plugin)
        persist()
        notifyChanged()
    }

    fun setOrder(ordered: List<Plugin>) {
        if (ordered.size != plugins.size || !plugins.containsAll(ordered)) return
        plugins.clear()
        plugins.addAll(ordered)
        persist()
    }

    // -- engine ops (globalQueue) --

    private fun run(plugin: Plugin) {
        Utilities.globalQueue.postRunnable {
            if (plugin.engine != null) return@postRunnable
            val engine = QuickJs()
            engine.consoleListener = { level, msg -> logConsole(plugin, level, msg) }
            plugin.engine = engine
            try {
                engine.installInfo(
                    appVersion = BuildVars.BUILD_VERSION_STRING,
                    appBuild = appBuild,
                    apiVersion = PLUGIN_API_VERSION,
                    layer = TLRPC.LAYER,
                    language = LocaleController.getInstance().currentLocaleInfo?.langCode ?: "",
                    header = buildHeader(plugin),
                )
                PluginApi.attach(plugin, engine)
                PluginRpc.attach(plugin, engine)
                engine.evaluate(plugin.source, plugin.manifest.name)
                setError(plugin, null)
            } catch (e: Throwable) {
                PluginRpc.detach(plugin)
                engine.close()
                plugin.engine = null
                plugin.settingsPageId = null
                Log.e(TAG, "[${plugin.manifest.name}] failed", e)
                setError(plugin, e.message ?: e.toString())
            }
        }
    }

    private fun stop(plugin: Plugin) {
        Utilities.globalQueue.postRunnable {
            val engine = plugin.engine ?: return@postRunnable
            try {
                engine.notifyUnload()
            } catch (e: Throwable) {
                Log.e(TAG, "[${plugin.manifest.name}] onUnload failed", e)
            }
            PluginRpc.detach(plugin)
            engine.close()
            plugin.engine = null
            plugin.settingsPageId = null
        }
    }

    /** the metadata header flattened to a string record, backing `inu.info().header` */
    private fun buildHeader(plugin: Plugin): Map<String, String> =
        plugin.manifest.raw.mapValues { (_, values) -> values.joinToString("\n") }

    private fun logConsole(plugin: Plugin, level: Int, message: String) {
        val tag = "$TAG/${plugin.manifest.name}"
        when (level) {
            2 -> Log.w(tag, message)
            3 -> Log.e(tag, message)
            else -> Log.d(tag, message)
        }
    }

    // -- discovery + persistence --

    private fun scan() {
        val files = pluginsDir.listFiles { f -> f.isFile && f.name.endsWith(".js") }?.toList().orEmpty()
        for (file in files) {
            if (plugins.any { it.file == file }) continue
            val source = try {
                file.readText()
            } catch (e: Exception) {
                Log.e(TAG, "read failed: ${file.name}", e)
                continue
            }
            val manifest = PluginManifestParser.parseOrNull(source)
            if (manifest == null) {
                Log.w(TAG, "no valid manifest: ${file.name}")
                continue
            }
            plugins.add(Plugin(file, source, manifest))
        }
        applyPersistedState()
    }

    /** restores order (array order) + enabled from PLUGINS_STATE; new plugins go to the end, enabled */
    private fun applyPersistedState() {
        val raw = InuConfig.PLUGINS_STATE.value
        if (raw.isBlank()) return
        val order = LinkedHashMap<String, Boolean>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                order[o.getString("id")] = o.optBoolean("enabled", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "bad plugins state", e)
            return
        }
        for (p in plugins) order[p.id]?.let { p.enabled = it }
        plugins.sortBy { order.keys.indexOf(it.id).let { idx -> if (idx < 0) Int.MAX_VALUE else idx } }
    }

    private fun persist() {
        val arr = JSONArray()
        for (p in plugins) {
            arr.put(JSONObject().put("id", p.id).put("enabled", p.enabled))
        }
        InuConfig.PLUGINS_STATE.value = arr.toString()
    }

    private fun uniqueFile(suggestedName: String): File {
        val safe = suggestedName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .removeSuffix(".js").ifBlank { "plugin" }
        var candidate = File(pluginsDir, "$safe.js")
        var i = 1
        while (candidate.exists()) {
            candidate = File(pluginsDir, "$safe-$i.js")
            i++
        }
        return candidate
    }

    private fun setError(plugin: Plugin, error: String?) {
        AndroidUtilities.runOnUIThread {
            plugin.error = error
            onChanged?.invoke()
        }
    }

    private fun notifyChanged() {
        AndroidUtilities.runOnUIThread { onChanged?.invoke() }
    }

    private fun registerSafeModeShortcut() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        try {
            val manager = appContext.getSystemService(ShortcutManager::class.java) ?: return
            val intent = Intent(appContext, LaunchActivity::class.java).setAction(SAFE_MODE_ACTION)
            val shortcut = ShortcutInfo.Builder(appContext, SAFE_MODE_SHORTCUT_ID)
                .setShortLabel(getString(R.string.InuPluginsSafeMode))
                .setLongLabel(getString(R.string.InuPluginsSafeMode))
                .setIcon(Icon.createWithResource(appContext, R.drawable.msg_settings))
                .setIntent(intent)
                .build()
            manager.addDynamicShortcuts(listOf(shortcut))
        } catch (e: Exception) {
            Log.e(TAG, "safe-mode shortcut failed", e)
        }
    }
}
