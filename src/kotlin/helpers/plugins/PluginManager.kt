package desu.inugram.helpers.plugins

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import desu.inugram.InuConfig
import desu.inugram.core.plugins.BootCohort
import desu.inugram.core.plugins.BootGuard
import desu.inugram.core.plugins.GrantValidator
import desu.inugram.core.plugins.PluginInstall
import desu.inugram.core.plugins.PluginInstalls
import desu.inugram.core.plugins.PluginManifest
import desu.inugram.core.plugins.PluginManifestParser
import desu.inugram.core.plugins.ScopeMatch
import desu.inugram.core.plugins.TlCtorIds
import desu.inugram.helpers.plugins.api.PluginApi
import desu.inugram.helpers.plugins.api.PluginKv
import desu.inugram.helpers.plugins.io.PluginBlobs
import desu.inugram.helpers.plugins.io.PluginFetch
import desu.inugram.helpers.plugins.io.PluginFs
import desu.inugram.helpers.plugins.platform.PluginJvm
import desu.inugram.helpers.plugins.platform.PluginNotifications
import desu.inugram.helpers.plugins.platform.PluginXposed
import desu.inugram.helpers.plugins.tg.PluginDeserialize
import desu.inugram.helpers.plugins.tg.PluginMedia
import desu.inugram.helpers.plugins.tg.PluginRpc
import desu.inugram.helpers.plugins.ui.PluginActions
import desu.inugram.helpers.plugins.ui.PluginCanvas
import desu.inugram.helpers.plugins.ui.PluginUi
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.BuildVars
import org.telegram.messenger.LocaleController
import org.telegram.messenger.LocaleController.formatString
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLRPC
import org.telegram.ui.LaunchActivity

/**
 * Owns the plugin set: discovery, persistence (order + enabled), and the QuickJs engines.
 *
 * Every engine op is funnelled through [Utilities.globalQueue] so each engine keeps its
 * same-thread invariant; structural list/flag mutations happen on the UI thread.
 */
object PluginManager {
    const val SAFE_MODE_ACTION = "desu.inugram.action.SAFE_MODE"
    private const val SAFE_MODE_SHORTCUT_ID = "inu_safe_mode"
    private const val TAG = "InuPlugin"
    private const val PLUGIN_API_VERSION = 1
    private const val PLATFORM = "android"
    private const val BUNDLED_DIR = "inu_plugins"

    private const val BUNDLED_STAMP_KEY = "plugins_bundled_apk"

    // console.* is one JNI upcall and one logcat line per call, and a plugin logging in a loop never
    // throws, so nothing in the failure policy stops it flooding on its own
    private const val LOG_BUDGET = 200
    private const val LOG_WINDOW_MS = 10_000L

    private lateinit var appContext: Context
    private val pluginsDir by lazy { PluginFs.storeDir() }
    private val packageInfo by lazy {
        try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private val appBuild by lazy { packageInfo?.versionCode?.toString() ?: "0" }

    private val plugins = mutableListOf<Plugin>()

    /** installs whose file is present but failed to load; kept so [persist] doesn't drop their ids */
    private var unloaded: List<PluginInstall> = emptyList()

    // structural mutations happen on the UI thread while globalQueue reads the order to sort
    // interceptor chains, so readers get an immutable snapshot rather than the live list
    @Volatile private var snapshot: List<Plugin> = emptyList()

    private val guard = BootGuard(object : BootGuard.Store {
        override fun read(key: String): Boolean = InuConfig.prefs.getBoolean(key, false)
        override fun write(key: String, value: Boolean) {
            InuConfig.prefs.edit(commit = true) { putBoolean(key, value) }
        }
    })

    val safeMode: Boolean get() = guard.safeMode

    /** why, for the plugins page: a session that ran nothing has to say so somewhere */
    val safeModeReason: BootGuard.Reason? get() = guard.reason

    private var booted = false
    private var lateLoaded = false
    private var lateInited = false

    var onChanged: (() -> Unit)? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        // before anything can run: it has to see the first activity start, and a plugin attaching
        // needs to know whether there is a foreground to attach into
        PluginApi.watchVisibility(appContext)
        PluginBlobs.scheduleSweep()
        copyBundledPlugins()
        scan()
    }

    /**
     * debug-only. Skipped unless the apk changed, because [init] runs from
     * `ApplicationLoader.onCreate` - the head of the notification path for a process a push woke,
     * and unpacking the set is a read and a write each. The assets live *in* the apk, so a reinstall
     * (what dev iteration is) bumps `lastUpdateTime`.
     */
    private fun copyBundledPlugins() {
        if (!BuildConfig.DEBUG) return
        val stamp = packageInfo?.let { "${appBuild}:${it.lastUpdateTime}" }
        if (stamp != null && InuConfig.prefs.getString(BUNDLED_STAMP_KEY, null) == stamp) return
        val names = try {
            appContext.assets.list(BUNDLED_DIR)
        } catch (e: Exception) {
            Log.e(TAG, "list bundled plugins failed", e)
            null
        } ?: return
        var complete = true
        for (name in names) {
            if (!name.endsWith(".js")) continue
            try {
                val text = appContext.assets.open("$BUNDLED_DIR/$name").use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                File(pluginsDir, name).writeText(text)
            } catch (e: Exception) {
                complete = false
                Log.e(TAG, "copy bundled plugin failed: $name", e)
            }
        }
        if (stamp != null && complete) InuConfig.prefs.edit { putString(BUNDLED_STAMP_KEY, stamp) }
    }

    fun isEngineEnabled(): Boolean = InuConfig.PLUGINS_ENABLED.value

    fun plugins(): List<Plugin> = snapshot

    /**
     * runs at the end of `ApplicationLoader.postInitApplication`, stock's own "the app is really
     * starting" gate and the earliest point the engine can run at all. Every entry point goes
     * through it, so a process a push woke boots plugins like one the launcher did - that process
     * hands a decrypted `TL_updates` to `processUpdates` with no activity ever created.
     *
     * **It blocks**, or `processUpdates` races the registrations. So only [BootCohort] loads here,
     * the rest waits for [onAppInteractive], and past [BootCohort.EARLY_BUDGET_MILLIS] the app
     * stops waiting.
     */
    fun onAppBoot() {
        if (booted || !::appContext.isInitialized) return
        booted = true
        if (!isEngineEnabled()) return
        val loaded = CountDownLatch(1)
        Utilities.globalQueue.postRunnable {
            try {
                runPass { it.enabled && BootCohort.bootsEarly(it.permissions) }
            } finally {
                loaded.countDown()
            }
        }
        Utilities.globalQueue.postRunnable { TlCtorIds.allNames }
        loaded.await(BootCohort.EARLY_BUDGET_MILLIS, TimeUnit.MILLISECONDS)
    }

    /** a late, stable lifecycle point: the safe-mode shortcut needs LocaleController, so it cannot be registered at [init] */
    fun onAppInteractive() {
        if (!lateInited) {
            lateInited = true
            registerSafeModeShortcut()
        }
        if (lateLoaded) return
        lateLoaded = true
        if (!isEngineEnabled()) return
        Utilities.globalQueue.postRunnable { runPass { it.enabled } }
    }

    /** the guard is armed around each plugin's own code and nothing else; [start] no-ops on a running plugin, so the late pass is the early one's remainder */
    private fun runPass(wanted: (Plugin) -> Boolean) {
        if (!guard.startPass()) {
            Log.w(TAG, "safe mode (${guard.reason}); skipping plugins")
            return
        }
        for (plugin in plugins()) {
            // [start] would skip a running plugin anyway, but arming is a durable commit
            if (!wanted(plugin) || plugin.running) continue
            guard.guardPlugin { start(plugin) }
        }
    }

    /**
     * It closes rather than restarts: android refuses a background activity start, and this process
     * is gone before any alarm scheduled here could fire. The shortcut's label says so.
     */
    fun requestSafeMode() {
        guard.armForcedSafeMode()
        Runtime.getRuntime().exit(0)
    }

    fun toggleEngine(): Boolean {
        val enabled = InuConfig.PLUGINS_ENABLED.toggle()
        if (enabled && !safeMode) {
            for (plugin in plugins) if (plugin.enabled) {
                plugin.failure = null
                run(plugin)
            }
        } else {
            for (plugin in plugins) stop(plugin)
        }
        return enabled
    }

    /** switching a plugin back on is the only retry there is, so it also clears what it failed with */
    fun setEnabled(plugin: Plugin, enabled: Boolean) {
        plugin.enabled = enabled
        if (enabled) plugin.failure = null
        persist()
        if (enabled) {
            if (isEngineEnabled() && !safeMode) run(plugin)
        } else {
            stop(plugin)
        }
    }

    fun reload(plugin: Plugin) {
        stop(plugin)
        plugin.failure = null
        val source = try {
            plugin.file.readText()
        } catch (e: Exception) {
            fail(plugin, PluginFailure.Site.REFUSED, e.message ?: e.toString())
            return
        }
        val manifest = PluginManifestParser.parseOrNull(source)
        if (manifest == null) {
            fail(plugin, PluginFailure.Site.REFUSED, getString(R.string.InuPluginsErrorNoManifest))
            return
        }
        badGrants(manifest)?.let {
            fail(plugin, PluginFailure.Site.REFUSED, it)
            return
        }
        plugin.source = source
        plugin.manifest = manifest
        notifyChanged()
        if (plugin.enabled && isEngineEnabled() && !safeMode) run(plugin)
    }

    /**
     * copies raw plugin source into the plugins dir, registers and (if applicable) runs it.
     *
     * always a *new* install, with its own identity and an empty store: nothing in the file is
     * matched against the installed set, so updating in place is what [reload] is for.
     */
    fun import(suggestedName: String, source: String): String? {
        val manifest = PluginManifestParser.parseOrNull(source)
            ?: return getString(R.string.InuPluginsErrorNoManifest)
        badGrants(manifest)?.let { return it }
        val target = uniqueFile(suggestedName)
        target.writeText(source)
        val plugin = Plugin(PluginInstalls.mintId(), target, source, manifest)
        plugins.add(plugin)
        persist()
        republishOrder()
        notifyChanged()
        if (plugin.enabled && isEngineEnabled() && !safeMode) run(plugin)
        return null
    }

    fun remove(plugin: Plugin) {
        stop(plugin)
        // same queue as stop()'s runnable, so the wipe is ordered after the engine is gone
        Utilities.globalQueue.postRunnable {
            PluginKv.wipe(plugin.id)
            // stop() wiped these already if it was running; this covers the one that never was
            PluginBlobs.wipe(plugin.id)
            PluginFetch.wipe(plugin.id)
            PluginCanvas.wipe(plugin.id)
            // the one plugin-owned tree meant to outlive its engine, so uninstall is the only thing
            // that ever clears it
            PluginFs.wipe(plugin.id)
            // a class cannot be unloaded, so staged dex outlives the engine that loaded it too
            PluginJvm.wipe(plugin.id)
        }
        plugin.file.delete()
        plugins.remove(plugin)
        persist()
        republishOrder()
        notifyChanged()
    }

    fun setOrder(ordered: List<Plugin>) {
        if (ordered.size != plugins.size || !plugins.containsAll(ordered)) return
        plugins.clear()
        plugins.addAll(ordered)
        persist()
        republishOrder()
    }

    /**
     * republishes the snapshot readers sort by, and the interceptor chains derived from it. chain
     * order is the plugin-list order per `common.d.ts`, so every structural change has to reach
     * [PluginRpc] or dragging a plugin would not move it until the process restarts.
     */
    private fun republishOrder() {
        snapshot = plugins.toList()
        PluginRpc.refreshChainOrder()
    }

    /**
     * why this app can't run [plugin], or null if it can. `@plugin-api` reads like minSdkVersion:
     * a level is never broken once shipped, so only a plugin asking for a level above ours is
     * refused - and refusing here, with a reason the user can read, beats failing later at whatever
     * call site happens to touch the missing api first.
     */
    private fun incompatibility(plugin: Plugin): String? {
        val manifest = plugin.manifest
        val wanted = manifest.pluginApi
        if (wanted != null && wanted > PLUGIN_API_VERSION) {
            return formatString(R.string.InuPluginsErrorApiVersion, wanted, PLUGIN_API_VERSION)
        }
        val platform = manifest.platform
        if (platform != null && !platform.equals(PLATFORM, ignoreCase = true)) {
            return formatString(R.string.InuPluginsErrorPlatform, platform)
        }
        return badGrants(manifest)
    }

    /**
     * a grant naming a scope no vocabulary recognises is always a typo, and narrowing it to nothing
     * would hide that - so it refuses the plugin instead. unknown grant *names* stay ignored.
     */
    private fun badGrants(manifest: PluginManifest): String? {
        val problems = GrantValidator.validateGrants(manifest.grants)
        if (problems.isEmpty()) return null
        return formatString(R.string.InuPluginsErrorBadGrant, problems.joinToString("; "))
    }

    private fun run(plugin: Plugin) {
        Utilities.globalQueue.postRunnable { start(plugin) }
    }

    /** globalQueue only */
    private fun start(plugin: Plugin) {
        if (plugin.engine != null) return
        // re-read here rather than trusting the call site: a fault raised while the previous
        // engine was tearing down switches the plugin off after this runnable was queued
        if (!plugin.enabled || !isEngineEnabled() || safeMode) return
        incompatibility(plugin)?.let {
            fail(plugin, PluginFailure.Site.REFUSED, it)
            return
        }
        // creating one runs the JNI bridge's own wiring, which throws when a descriptor does not
        // resolve - and an exception escaping here would take the queue's thread, and the app, down
        val engine = try {
            QuickJs()
        } catch (e: Throwable) {
            fail(plugin, PluginFailure.Site.LOAD, e.message ?: e.toString())
            return
        }
        val budget = LogBudget()
        engine.consoleListener = { level, msg ->
            if (level == QuickJs.LEVEL_FAULT) fail(plugin, PluginFailure.Site.RUNTIME, msg, engine)
            else logConsole(plugin, budget, level, msg)
        }
        val permissions = plugin.permissions
        engine.grantChecker = { name, target, mode ->
            val match = ScopeMatch.entries.getOrNull(mode)
            if (target == null) permissions.has(name) else match != null && permissions.allows(name, target, match)
        }
        plugin.engine = engine
        try {
            engine.installInfo(
                appVersion = BuildVars.BUILD_VERSION_STRING,
                appBuild = appBuild,
                apiVersion = PLUGIN_API_VERSION,
                layer = TLRPC.LAYER,
                language = LocaleController.getInstance().currentLocaleInfo?.langCode ?: "",
                header = plugin.manifest.raw,
            )
            PluginApi.attach(plugin, engine)
            PluginRpc.attach(plugin, engine)
            engine.evaluate(plugin.source, plugin.manifest.name)
            notifyChanged()
        } catch (e: Throwable) {
            teardown(plugin, engine) {
                // before the field is cleared, which is what [fail] reads to decide the fault is
                // still this plugin's
                fail(plugin, PluginFailure.Site.LOAD, e.message ?: e.toString(), engine)
            }
        }
    }

    /**
     * drops every subsystem this engine reached, closes it, and reclaims what it left on disk.
     *
     * The order is load-bearing twice over and both loads are why this is one function rather than
     * a sequence written out at each of the two sites that need it: `inu.xposed` reads `inu.jvm`'s
     * handle table while taking its hooks down, and the three `wipe`s can only run after `close()`,
     * which is when rust lets go of the descriptors it holds per spill file. [beforeClear] runs
     * while `plugin.engine` still points at [engine], which is what [fail] reads.
     */
    private fun teardown(plugin: Plugin, engine: QuickJs, beforeClear: () -> Unit = {}) {
        PluginRpc.detach(plugin)
        PluginMedia.detach(plugin)
        PluginUi.detach(engine)
        PluginActions.detach(engine)
        PluginNotifications.detach(engine)
        PluginDeserialize.detach(engine)
        PluginCanvas.detach(engine)
        PluginXposed.detach(engine)
        PluginJvm.detach(engine)
        engine.close()
        PluginBlobs.wipe(plugin.id)
        PluginFetch.wipe(plugin.id)
        PluginCanvas.wipe(plugin.id)
        beforeClear()
        plugin.engine = null
        plugin.settingsPageId = null
    }

    private fun stop(plugin: Plugin) {
        Utilities.globalQueue.postRunnable {
            val engine = plugin.engine ?: return@postRunnable
            try {
                engine.notifyUnload()
            } catch (e: Throwable) {
                fail(plugin, PluginFailure.Site.UNLOAD, e.message ?: e.toString(), engine)
            }
            teardown(plugin, engine)
        }
    }

    /**
     * the whole failure policy: a plugin whose own code threw records where it threw and is switched
     * off, and switching it back on is the retry.
     *
     * both hops are load-bearing. a fault arrives from inside a JNI upcall, with the engine's
     * `RefCell` already borrowed, so nothing here may re-enter it; and [stop] only ever reaches the
     * engine through [Utilities.globalQueue], which is also where every in-flight dispatch of that
     * plugin runs, so the two can't interleave inside one engine.
     *
     * The verdict is taken on the queue that owns [Plugin.engine] rather than in the post: a
     * reload's `onUnload` throwing is reported from the teardown, which is a `globalQueue` runnable,
     * while this settles on the ui thread - so by the time it landed the successor would already be
     * running and be switched off over an engine that no longer exists.
     */
    private fun fail(plugin: Plugin, at: PluginFailure.Site, detail: String, engine: QuickJs? = null) {
        if (engine != null && plugin.engine !== engine) return
        Log.e(TAG, "[${plugin.manifest.name}] $at: $detail")
        val failure = PluginFailure(at, detail)
        AndroidUtilities.runOnUIThread {
            plugin.failure = failure
            if (failure.disables && plugin.enabled) {
                plugin.enabled = false
                persist()
                stop(plugin)
            }
            onChanged?.invoke()
        }
    }

    private fun logConsole(plugin: Plugin, budget: LogBudget, level: Int, message: String) {
        val tag = "$TAG/${plugin.manifest.name}"
        when (budget.charge(SystemClock.uptimeMillis())) {
            LogBudget.Verdict.DROP -> return
            LogBudget.Verdict.LAST -> {
                Log.w(tag, "spent its log budget ($LOG_BUDGET per ${LOG_WINDOW_MS / 1000}s); muting the rest")
                return
            }
            LogBudget.Verdict.PASS -> Unit
        }
        when (level) {
            2 -> Log.w(tag, message)
            3 -> Log.e(tag, message)
            else -> Log.d(tag, message)
        }
    }

    /** per-engine, so it dies with the engine rather than needing an entry to evict */
    private class LogBudget {
        private var windowStart = 0L
        private var used = 0

        fun charge(now: Long): Verdict {
            if (now - windowStart >= LOG_WINDOW_MS) {
                windowStart = now
                used = 0
            }
            used++
            return when {
                used < LOG_BUDGET -> Verdict.PASS
                used == LOG_BUDGET -> Verdict.LAST
                else -> Verdict.DROP
            }
        }

        enum class Verdict { PASS, LAST, DROP }
    }

    /**
     * a file in the plugins dir is one install; the persisted state carries its identity, its place
     * in the order and its enabled bit. a file nobody has a record for is a new install and is
     * assigned a fresh id here, so the id has to be written back before anything can use it.
     */
    private fun scan() {
        // null is "could not list", which is not "there are no plugins": reconciling against an empty
        // set drops every record, and [persist] would then write that back, losing the ids the kv
        // stores are keyed on. leave the persisted state alone and run no plugins this boot
        val files = pluginsDir.listFiles { f -> f.isFile && f.name.endsWith(".js") }
        if (files == null) {
            Log.e(TAG, "could not list $pluginsDir; keeping the persisted installs and skipping plugins")
            return
        }
        val installs = PluginInstalls.reconcile(readPersistedInstalls(), files.map { it.name }.sorted())
        for (install in installs) {
            val file = File(pluginsDir, install.file)
            if (plugins.any { it.file == file }) continue
            val source = try {
                file.readText()
            } catch (e: Exception) {
                Log.e(TAG, "read failed: ${install.file}", e)
                continue
            }
            val manifest = PluginManifestParser.parseOrNull(source)
            if (manifest == null) {
                Log.w(TAG, "no valid manifest: ${install.file}")
                continue
            }
            plugins.add(Plugin(install.id, file, source, manifest).apply { enabled = install.enabled })
        }
        // a file we could not load this boot keeps its record, or fixing it later would land it on a
        // fresh id and an empty store
        unloaded = installs.filter { install -> plugins.none { it.file.name == install.file } }
        persist()
        republishOrder()
    }

    private fun readPersistedInstalls(): List<PluginInstall> {
        val raw = InuConfig.PLUGINS_STATE.value
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val file = o.optString("file")
                if (file.isEmpty()) null
                else PluginInstall(o.optString("id"), file, o.optBoolean("enabled", true))
            }
        } catch (e: Exception) {
            Log.e(TAG, "bad plugins state", e)
            emptyList()
        }
    }

    private fun persist() {
        val arr = JSONArray()
        for (p in plugins) {
            arr.put(JSONObject().put("id", p.id).put("file", p.file.name).put("enabled", p.enabled))
        }
        for (install in unloaded) {
            arr.put(JSONObject().put("id", install.id).put("file", install.file).put("enabled", install.enabled))
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
                .setLongLabel(getString(R.string.InuPluginsSafeModeShortcut))
                .setIcon(Icon.createWithResource(appContext, R.drawable.msg_settings))
                .setIntent(intent)
                .build()
            manager.addDynamicShortcuts(listOf(shortcut))
        } catch (e: Exception) {
            Log.e(TAG, "safe-mode shortcut failed", e)
        }
    }
}
