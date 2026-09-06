package desu.inugram.helpers.plugins

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.SystemClock
import android.util.Log
import desu.inugram.InuConfig
import desu.inugram.core.plugins.BootCohort
import desu.inugram.core.plugins.GrantValidator
import desu.inugram.core.plugins.PluginInstalls
import desu.inugram.core.plugins.PluginManifest
import desu.inugram.core.plugins.PluginManifestParser
import desu.inugram.core.plugins.TlCtorIds
import desu.inugram.helpers.plugins.PluginManager.fail
import desu.inugram.helpers.plugins.PluginManager.init
import desu.inugram.helpers.plugins.PluginManager.onAppInteractive
import desu.inugram.helpers.plugins.PluginManager.plugins
import desu.inugram.helpers.plugins.PluginManager.reload
import desu.inugram.helpers.plugins.PluginManager.start
import desu.inugram.helpers.plugins.PluginManager.stop
import desu.inugram.helpers.plugins.api.EngineBindings
import desu.inugram.helpers.plugins.api.PluginKv
import desu.inugram.helpers.plugins.io.PluginBlobs
import desu.inugram.helpers.plugins.io.PluginFetch
import desu.inugram.helpers.plugins.io.PluginFs
import desu.inugram.helpers.plugins.platform.PluginJvm
import desu.inugram.helpers.plugins.platform.PluginNotifications
import desu.inugram.helpers.plugins.platform.PluginPlatform
import desu.inugram.helpers.plugins.platform.PluginXposed
import desu.inugram.helpers.plugins.telegram.PluginAccounts
import desu.inugram.helpers.plugins.telegram.PluginMedia
import desu.inugram.helpers.plugins.telegram.PluginRpc
import desu.inugram.helpers.plugins.telegram.PluginUpdates
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlHandles
import desu.inugram.helpers.plugins.ui.PluginActions
import desu.inugram.helpers.plugins.ui.PluginAppVisibility
import desu.inugram.helpers.plugins.ui.PluginCanvas
import desu.inugram.helpers.plugins.ui.PluginUi
import desu.inugram.helpers.update.UpdateHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildVars
import org.telegram.messenger.LocaleController
import org.telegram.messenger.LocaleController.formatString
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLRPC
import org.telegram.ui.LaunchActivity
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Owns the running plugins: which of them are loaded, when each one starts and stops, and what
 * happens when one throws. The installed set on disk is [PluginStore]'s.
 *
 * Every engine op is funnelled through [Utilities.globalQueue] so each engine keeps its
 * same-thread invariant; structural list/flag mutations happen on the UI thread.
 */
object PluginManager {
    const val SAFE_MODE_ACTION = "desu.inugram.action.SAFE_MODE"
    private const val SAFE_MODE_SHORTCUT_ID = "inu_safe_mode"
    private const val TAG = "InuPlugin"
    const val PLUGIN_API_VERSION = 1
    private const val PLATFORM = "android"

    private const val LOG_BUDGET = 200
    private const val LOG_WINDOW_MS = 10_000L

    @Suppress("DEPRECATION")
    private val appBuild by lazy { UpdateHelper.packageInfo?.versionCode?.toString() ?: "0" }

    private val plugins = mutableListOf<Plugin>()

    // structural mutations happen on the UI thread while globalQueue reads the order to sort
    // interceptor chains, so readers get an immutable snapshot rather than the live list
    @Volatile
    private var snapshot: List<Plugin> = emptyList()

    private val guard = BootGuard()

    val safeMode: Boolean get() = guard.safeMode

    /** why, for the plugins page: a session that ran nothing has to say so somewhere */
    val safeModeReason: BootGuard.Reason? get() = guard.reason

    private var booted = false
    private var lateLoaded = false
    private var lateInited = false

    // a set, not a slot: the plugins page and a plugin's info page can be mounted at once, and the
    // fragment being revealed resumes before the one it replaced pauses
    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()

    fun init(context: Context) {
        PluginAppVisibility.watch(context)
        PluginAccounts.watch()
        PluginBlobs.scheduleSweep()
        plugins.addAll(PluginStore.load())
        PluginStore.persist(plugins)
        republishOrder()
        PluginDevServer.sync(context)
    }

    fun isEngineEnabled(): Boolean = InuConfig.PLUGINS_ENABLED.value

    fun plugins(): List<Plugin> = snapshot

    /**
     * [plugins] as a lookup, which is what every chain-order publish sorts by. Identity, because
     * `Plugin` has no `equals()` and a reload replaces the instance.
     */
    fun orderIndex(): IdentityHashMap<Plugin, Int> {
        val order = IdentityHashMap<Plugin, Int>()
        snapshot.forEachIndexed { index, plugin -> order[plugin] = index }
        return order
    }

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
        if (booted || ApplicationLoader.applicationContext == null) return
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
        PluginStore.persist(plugins)
        notifyChanged()
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

    /** the installed plugin [manifest] would replace, or null when it is a plugin of its own */
    fun findUpdateTarget(manifest: PluginManifest): Plugin? {
        val identity = manifest.identity ?: return null
        // the snapshot, because this is answered off the ui thread that mutates the list
        return plugins().firstOrNull { it.manifest.identity == identity }
    }

    /**
     * copies raw plugin source into the plugins dir, registers and (if applicable) runs it.
     *
     * a *new* install with an empty store, unless the source claims the identity of a record that
     * did not load this boot - that one is nothing the user can see or remove, so its id is reused
     * rather than stranded. A plugin that is merely installed and broken is [update]'s, not this.
     */
    fun import(suggestedName: String, source: String, enabled: Boolean = true, dev: Boolean = false): ImportResult {
        val manifest = PluginManifestParser.parseOrNull(source)
            ?: return ImportResult.Refused(getString(R.string.InuPluginsErrorNoManifest))
        badGrants(manifest)?.let { return ImportResult.Refused(it) }
        val reclaimed = manifest.identity?.let { PluginStore.findUnloaded(it) }
        val target = if (reclaimed != null) File(PluginStore.dir, reclaimed.file) else PluginStore.fileFor(suggestedName)
        if (!PluginStore.writeSource(target, source)) {
            return ImportResult.Refused(getString(R.string.InuPluginsErrorWrite))
        }
        reclaimed?.let { PluginStore.dropUnloaded(it) }
        val plugin = Plugin(reclaimed?.id ?: PluginInstalls.mintId(), target, source, manifest)
            .apply {
                this.enabled = enabled
                this.dev = dev
            }
        val reversible = reclaimed == null
        plugins.add(plugin)
        PluginStore.persist(plugins)
        republishOrder()
        notifyChanged()
        if (plugin.enabled && isEngineEnabled() && !safeMode) run(plugin)
        return ImportResult.Installed(plugin, reversible)
    }

    /**
     * replaces an installed plugin's source in place: same install id, so the same `kv` and `fs`
     * stores, the same place in the chain order and the same enabled bit. Returns why nothing was
     * written, or null once the plugin is running the new source.
     *
     * The vetting is [reload]'s, which re-reads the file this just wrote - deliberately, so an
     * update goes live through the one path that also has to survive a plugin failing to load.
     */
    fun update(plugin: Plugin, source: String, dev: Boolean = false): String? {
        val manifest = PluginManifestParser.parseOrNull(source)
            ?: return getString(R.string.InuPluginsErrorNoManifest)
        incompatibility(manifest)?.let { return it }
        if (!PluginStore.writeSource(plugin.file, source)) return getString(R.string.InuPluginsErrorWrite)
        plugin.dev = dev
        reload(plugin)
        PluginStore.persist(plugins)
        return null
    }

    /** what was installed, so the caller can offer to undo it, or why nothing was */
    sealed interface ImportResult {
        /**
         * [reversible] is false when this install took over the id of a record that did not load:
         * [remove] would then wipe `kv`/`fs` that belong to what held the id before, so there is
         * nothing to offer an undo of - the install is not its own inverse.
         */
        class Installed(val plugin: Plugin, val reversible: Boolean) : ImportResult
        class Refused(val reason: String) : ImportResult
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
        PluginStore.persist(plugins)
        republishOrder()
        notifyChanged()
    }

    fun setOrder(ordered: List<Plugin>) {
        if (ordered.size != plugins.size || !plugins.containsAll(ordered)) return
        plugins.clear()
        plugins.addAll(ordered)
        PluginStore.persist(plugins)
        republishOrder()
    }

    /**
     * republishes the snapshot readers sort by, and the interceptor chains derived from it. chain
     * order is the plugin-list order per `common.d.ts`, so every structural change has to reach
     * both chains or dragging a plugin would not move it until the process restarts.
     */
    private fun republishOrder() {
        snapshot = plugins.toList()
        PluginRpc.refreshChainOrder()
        PluginUpdates.refreshOrder()
    }

    /**
     * why this app can't run a plugin declaring [manifest], or null if it can. Also the install
     * flow's gate, which is why it is stated over a manifest rather than over an installed plugin.
     *
     * `@plugin-api` reads like minSdkVersion:
     * a level is never broken once shipped, so only a plugin asking for a level above ours is
     * refused - and refusing here, with a reason the user can read, beats failing later at whatever
     * call site happens to touch the missing api first.
     */
    fun incompatibility(manifest: PluginManifest): String? {
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
        if (!plugin.enabled || !isEngineEnabled() || safeMode) return
        incompatibility(plugin.manifest)?.let {
            fail(plugin, PluginFailure.Site.REFUSED, it)
            return
        }
        val engine = QuickJs()
        val budget = LogBudget()
        val timers = TimerThrottle(plugin, engine)::schedule
        val core = object : CoreListener {
            override fun onConsole(level: Int, message: String) {
                if (level == QuickJs.LEVEL_FAULT) fail(plugin, PluginFailure.Site.RUNTIME, message, engine)
                else logConsole(plugin, budget, level, message)
            }

            override fun onTimerSchedule(delayMs: Long) = timers(delayMs)
        }
        // built whole and handed over once: rust caches its method ids off `PluginBridge` at
        // `start`, and every ordering constraint among the installs after it is in `EngineBindings`
        val jvm = EngineBindings.jvmListenerFor(plugin, engine)
        val tl = TlHandles.attach(plugin, TlFilter.policyFor(plugin.permissions))
        val bridge = PluginBridge(
            core = core,
            rpc = PluginRpc.listenerFor(plugin, engine, tl),
            updates = PluginUpdates.listenerFor(plugin),
            tl = tl,
            storage = PluginKv.listenerFor(plugin),
            account = PluginAccounts.listenerFor(plugin, engine),
            ui = PluginUi.listenerFor(plugin, engine),
            platform = PluginPlatform.listenerFor(),
            fetch = PluginFetch.listenerFor(plugin, engine),
            canvas = PluginCanvas.listenerFor(plugin, engine),
            notifications = PluginNotifications.listenerFor(plugin, engine),
            jvm = jvm,
            xposed = PluginXposed.listenerFor(plugin, engine, jvm),
        )
        plugin.engine = engine
        try {
            // this is what runs the JNI bridge's own wiring, which throws when a descriptor does
            // not resolve - and an exception escaping here would take globalQueue, and the app, down
            EngineBindings.start(plugin, engine, bridge)
            engine.installInfo(
                appVersion = BuildVars.BUILD_VERSION_STRING,
                appBuild = appBuild,
                apiVersion = PLUGIN_API_VERSION,
                layer = TLRPC.LAYER,
                language = LocaleController.getInstance().currentLocaleInfo?.langCode ?: "",
                header = plugin.manifest.raw,
            )
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
        // the plugin's handle table spans both, and is released last of the three: the abandons
        // each of them runs reject inside this plugin, and a continuation touching its own request
        // view must not find every field expired
        TlHandles.beginDetach(plugin)
        PluginRpc.detach(plugin)
        PluginUpdates.detach(plugin)
        TlHandles.endDetach(plugin)
        PluginMedia.detach(plugin)
        PluginUi.detach(engine)
        PluginActions.detach(engine)
        PluginNotifications.detach(engine)
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
        notifyChanged()
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
                PluginStore.persist(plugins)
                stop(plugin)
            }
            notifyChanged()
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

    fun addOnChangedListener(listener: () -> Unit) {
        changeListeners.addIfAbsent(listener)
    }

    fun removeOnChangedListener(listener: () -> Unit) {
        changeListeners.remove(listener)
    }

    /** re-reads plugin state anywhere it is on screen; anything mutating a [Plugin] field owes it a call */
    fun notifyChanged() {
        AndroidUtilities.runOnUIThread { for (listener in changeListeners) listener() }
    }

    private fun registerSafeModeShortcut() {
        try {
            val manager = ApplicationLoader.applicationContext.getSystemService(ShortcutManager::class.java) ?: return
            val intent = Intent(ApplicationLoader.applicationContext, LaunchActivity::class.java).setAction(SAFE_MODE_ACTION)
            val shortcut = ShortcutInfo.Builder(ApplicationLoader.applicationContext, SAFE_MODE_SHORTCUT_ID)
                .setShortLabel(getString(R.string.InuPluginsSafeMode))
                .setLongLabel(getString(R.string.InuPluginsSafeModeShortcut))
                .setIcon(Icon.createWithResource(ApplicationLoader.applicationContext, R.drawable.msg_settings))
                .setIntent(intent)
                .build()
            manager.addDynamicShortcuts(listOf(shortcut))
        } catch (e: Exception) {
            Log.e(TAG, "safe-mode shortcut failed", e)
        }
    }
}
