package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.ui.PluginFilePicker
import java.util.concurrent.atomic.AtomicBoolean
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.SystemClock
import android.util.Log
import desu.inugram.InuConfig
import desu.inugram.core.plugins.BootCohort
import desu.inugram.core.plugins.GrantCatalog
import desu.inugram.core.plugins.GrantValidator
import desu.inugram.core.plugins.PluginInstalls
import desu.inugram.core.plugins.PluginManifest
import desu.inugram.core.plugins.PluginManifestParser
import desu.inugram.core.plugins.TlTables
import desu.inugram.helpers.plugins.PluginManager.fail
import desu.inugram.helpers.plugins.PluginManager.init
import desu.inugram.helpers.plugins.PluginManager.onAppInteractive
import desu.inugram.helpers.plugins.PluginManager.plugins
import desu.inugram.helpers.plugins.PluginManager.reload
import desu.inugram.helpers.plugins.PluginManager.start
import desu.inugram.helpers.plugins.PluginManager.stop
import desu.inugram.helpers.plugins.api.EngineBindings
import desu.inugram.helpers.plugins.api.PluginLocalStorage
import desu.inugram.helpers.plugins.io.PluginBlobs
import desu.inugram.helpers.plugins.io.PluginTransfers
import desu.inugram.helpers.plugins.io.PluginFetch
import desu.inugram.helpers.plugins.io.PluginFs
import desu.inugram.helpers.plugins.platform.PluginJvm
import desu.inugram.helpers.plugins.platform.PluginNotifications
import desu.inugram.helpers.plugins.platform.PluginPlatform
import desu.inugram.helpers.plugins.platform.PluginXposed
import desu.inugram.helpers.plugins.telegram.PluginAccounts
import desu.inugram.helpers.plugins.telegram.PluginMedia
import desu.inugram.helpers.plugins.telegram.PluginOptimisticSend
import desu.inugram.helpers.plugins.telegram.PluginRpc
import desu.inugram.helpers.plugins.telegram.PluginUpdates
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlHandles
import desu.inugram.helpers.plugins.tl.TlReflect
import desu.inugram.helpers.plugins.ui.PluginActions
import desu.inugram.helpers.plugins.ui.PluginAppVisibility
import desu.inugram.helpers.plugins.ui.PluginCanvas
import desu.inugram.helpers.plugins.ui.PluginUi
import desu.inugram.helpers.update.UpdateHelper
import desu.inugram.ui.settings.PluginInfoActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildVars
import org.telegram.messenger.LocaleController
import org.telegram.messenger.LocaleController.formatString
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.BulletinFactory
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
 * Every engine op is funnelled through [EngineDispatch.scheduler] so each engine keeps its
 * same-thread invariant; structural list/flag mutations happen on the UI thread.
 */
object PluginManager {
    const val SAFE_MODE_ACTION = "desu.inugram.action.SAFE_MODE"
    private const val SAFE_MODE_SHORTCUT_ID = "inu_safe_mode"
    private const val TAG = "InuPlugin"
    const val PLUGIN_API_VERSION = GrantCatalog.PLUGIN_API
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
    private var loaded = false
    private var hosting = false

    /**
     * whether any plugin is actually running. Every hook stock calls into reads this first, so an
     * engine that is off - the default - costs the app a volatile read and nothing else.
     */
    @Volatile
    var anyRunning = false
        private set

    /** [snapshot], not [plugins]: the published list is what every reader of the flag sees */
    fun refreshAnyRunning() {
        anyRunning = snapshot.any { it.session != null }
    }

    // a set, not a slot: the plugins page and a plugin's info page can be mounted at once, and the
    // fragment being revealed resumes before the one it replaced pauses
    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()

    fun init(context: Context) {
        if (!isEngineEnabled()) return
        startHosting(context)
    }

    /**
     * Starts plugin observers, file cleanup, and store loading when the engine is enabled.
     * With the engine off, registers no observers and does not read the plugin directory.
     */
    private fun startHosting(context: Context) {
        if (hosting) return
        hosting = true
        PluginAppVisibility.watch(context)
        PluginAccounts.watch()
        PluginBlobs.scheduleSweep()
        PluginTransfers.scheduleSweep()
        ensureLoaded()
        PluginDevServer.sync(context)
    }

    /**
     * Loads installed plugins from disk once. The plugins page also needs this with the engine
     * disabled, before [startHosting] runs.
     */
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        plugins.addAll(PluginStore.load())
        PluginStore.persist(plugins)
        republishOrder()
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
     * Runs at the end of `ApplicationLoader.postInitApplication`, the earliest safe startup point.
     * Every app entry point uses it, including push wakeups that deliver decrypted `TL_updates`
     * to `processUpdates` without creating an activity.
     *
     * Blocks until [BootCohort] loads or [BootCohort.EARLY_BUDGET_MILLIS] expires, so update
     * processing waits for registrations. Other plugins load in [onAppInteractive].
     */
    fun onAppBoot() {
        if (booted || ApplicationLoader.applicationContext == null) return
        booted = true
        if (!isEngineEnabled()) return
        val loaded = CountDownLatch(1)
        EngineDispatch.scheduler.postRunnable {
            try {
                runPass { it.enabled && BootCohort.bootsEarly(it.permissions) }
            } finally {
                loaded.countDown()
            }
        }
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
        EngineDispatch.scheduler.postRunnable { runPass { it.enabled } }
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
        if (enabled) ApplicationLoader.applicationContext?.let { startHosting(it) }
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
            if (mayRun(plugin)) run(plugin)
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
        if (mayRun(plugin)) run(plugin)
    }

    /** the installed plugin [manifest] would replace, or null when it is a plugin of its own */
    fun findUpdateTarget(manifest: PluginManifest): Plugin? {
        val pluginId = manifest.id ?: return null
        // the snapshot, because this is answered off the ui thread that mutates the list
        return plugins().firstOrNull { it.manifest.id == pluginId }
    }

    /**
     * Copies plugin source into the plugins directory, registers it, and starts it if enabled.
     *
     * Creates a new install with empty storage, unless the plugin ID matches a record that failed
     * to load this boot. Reuse that record's install ID to preserve otherwise inaccessible storage.
     * Use [update] for plugins already present in the installed list, including broken ones.
     */
    fun import(suggestedName: String, source: String, enabled: Boolean = true, dev: Boolean = false): ImportResult {
        val manifest = PluginManifestParser.parseOrNull(source)
            ?: return ImportResult.Refused(getString(R.string.InuPluginsErrorNoManifest))
        badGrants(manifest)?.let { return ImportResult.Refused(it) }
        val reclaimed = manifest.id?.let { PluginStore.findUnloaded(it) }
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
        if (mayRun(plugin)) run(plugin)
        return ImportResult.Installed(plugin, reversible)
    }

    /**
     * Replaces installed source while preserving the install ID, `localStorage`/`fs` stores, order,
     * and enabled state. Returns an error reason, or null after loading the new source.
     *
     * [reload] re-reads and validates the written file, using the same path as ordinary reloads.
     */
    fun update(plugin: Plugin, source: String, dev: Boolean = false): String? {
        val manifest = PluginManifestParser.parseOrNull(source)
            ?: return getString(R.string.InuPluginsErrorNoManifest)
        incompatibility(manifest)?.let { return it }
        if (!PluginStore.writeSource(plugin.file, source)) return getString(R.string.InuPluginsErrorWrite)
        plugin.dev = dev
        if (dev && !plugin.enabled && plugin.failure?.disables == true) plugin.enabled = true
        reload(plugin)
        PluginStore.persist(plugins)
        return null
    }

    /** what was installed, so the caller can offer to undo it, or why nothing was */
    sealed interface ImportResult {
        /**
         * [reversible] is false when this install took over the id of a record that did not load:
         * [remove] would then wipe `localStorage`/`fs` that belong to what held the id before, so there is
         * nothing to offer an undo of - the install is not its own inverse.
         */
        class Installed(val plugin: Plugin, val reversible: Boolean) : ImportResult
        class Refused(val reason: String) : ImportResult
    }

    fun remove(plugin: Plugin) {
        plugin.enabled = false
        // same queue as stop()'s completion, so the wipe is ordered after the engine is gone
        stop(plugin) {
            PluginLocalStorage.wipe(plugin.id)
            // stop() wiped these already if it was running; this covers the one that never was
            wipeSessionScratch(plugin.id)
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

    private val stopping = java.util.IdentityHashMap<Plugin, MutableList<() -> Unit>>()

    private fun run(plugin: Plugin) {
        EngineDispatch.scheduler.postRunnable {
            val pending = stopping[plugin]
            if (pending != null) pending.add { start(plugin) }
            else start(plugin)
        }
    }

    private var warmed = false

    /** plugin queue only */
    private fun warmTlTables() {
        if (warmed) return
        warmed = true
        EngineDispatch.scheduler.postRunnable {
            TlTables.allNames
            TlReflect.prewarm()
        }
    }

    /** plugin queue only */
    private fun start(plugin: Plugin) {
        if (plugin.engine != null) return
        if (!mayRun(plugin)) return
        warmTlTables()
        incompatibility(plugin.manifest)?.let {
            fail(plugin, PluginFailure.Site.REFUSED, it)
            return
        }
        val session = PluginSession(plugin, QuickJs())
        plugin.session = session
        refreshAnyRunning()
        val budget = LogBudget()
        val timers = TimerThrottle(session)::schedule
        val onHost = EngineDispatch.createHostDispatcher(session::isCurrent)
        val core = object : CoreListener {
            private val faultReported = AtomicBoolean()

            override fun onConsole(level: Int, message: String) {
                logConsole(session, budget, level, message)
                if (level == QuickJs.LEVEL_FAULT && faultReported.compareAndSet(false, true)) {
                    EngineDispatch.onEngine(session) { fail(session, PluginFailure.Site.RUNTIME, message) }
                }
            }

            override fun onTimerSchedule(delayMs: Long) = onHost { timers(delayMs) }
        }
        // built whole and handed over once: rust caches its method ids off `PluginBridge` at
        // `start`, and every ordering constraint among the installs after it is in `EngineBindings`
        val jvm = EngineBindings.jvmListenerFor(session)
        val tl = session.tl
        val bridge = PluginBridge(
            core = core,
            rpc = PluginRpc.listenerFor(session),
            updates = PluginUpdates.listenerFor(session),
            tl = tl,
            account = PluginAccounts.listenerFor(session),
            ui = PluginUi.listenerFor(session),
            platform = PluginPlatform.listenerFor(),
            fetch = PluginFetch.listenerFor(session),
            canvas = PluginCanvas.listenerFor(session),
            notifications = PluginNotifications.listenerFor(session),
            jvm = jvm,
            xposed = PluginXposed.listenerFor(session, jvm),
        )
        try {
            // this is what runs the JNI bridge's own wiring, which throws when a descriptor does
            // not resolve - and an exception escaping here would take globalQueue, and the app, down
            EngineBindings.start(session, bridge)
            session.engine.installInfo(
                appVersion = BuildVars.BUILD_VERSION_STRING,
                appBuild = appBuild,
                apiVersion = PLUGIN_API_VERSION,
                layer = TLRPC.LAYER,
                language = LocaleController.getInstance().currentLocaleInfo?.langCode ?: "",
                header = session.manifest.raw,
            )
            session.engine.evaluate(session.source, session.manifest.name)
            notifyChanged()
        } catch (e: Throwable) {
            teardown(session) {
                // before the field is cleared, which is what [fail] reads to decide the fault is
                // still this plugin's
                fail(session, PluginFailure.Site.LOAD, e.message ?: e.toString())
            }
        }
    }

    /** whether a plugin is allowed to be running right now, which every start path asks in the same terms */
    private fun mayRun(plugin: Plugin): Boolean = plugin.enabled && isEngineEnabled() && !safeMode

    private fun failUnload(session: PluginSession, e: Throwable) =
        fail(session, PluginFailure.Site.UNLOAD, e.message ?: e.toString())

    /** exactly the trees an engine owns: the durable ones ([PluginFs], [PluginJvm], [PluginLocalStorage]) are uninstall's alone */
    private fun wipeSessionScratch(installId: String) {
        PluginBlobs.wipe(installId)
        PluginTransfers.wipe(installId)
        PluginFetch.wipe(installId)
        PluginCanvas.wipe(installId)
    }

    private val CHAIN_OWNERS: List<SessionResource> = listOf(PluginRpc, PluginUpdates)

    private val SESSION_RESOURCES: List<SessionResource> = listOf(
        PluginMedia,
        PluginOptimisticSend,
        PluginUi,
        PluginFilePicker,
        PluginFetch,
        PluginActions,
        PluginNotifications,
        PluginCanvas,
        PluginXposed,
        PluginJvm,
    )

    /**
     * Detaches subsystems, closes the engine, and removes temporary files.
     *
     * Order matters: Xposed needs the JVM handle table while removing hooks. File cleanup must
     * wait for `close()`, which releases Rust's spill-file descriptors. [beforeClear] runs while
     * `plugin.engine` still points to [engine], so [fail] can check its identity.
     */
    private fun teardown(session: PluginSession, beforeClear: () -> Unit = {}) {
        session.engine.stopCallbacks()
        // the plugin's handle table spans both, and is released last of the three: the abandons
        // each of them runs reject inside this plugin, and a continuation touching its own request
        // view must not find every field expired
        session.stopDispatching()
        for (owner in CHAIN_OWNERS) owner.detach(session)
        session.tl.releaseAll()
        for (resource in SESSION_RESOURCES) resource.detach(session)
        session.engine.close()
        wipeSessionScratch(session.plugin.id)
        beforeClear()
        session.plugin.session = null
        session.settingsPageId = null
        refreshAnyRunning()
        notifyChanged()
    }

    private fun stop(plugin: Plugin, after: () -> Unit = {}) {
        EngineDispatch.scheduler.postRunnable {
            stopping[plugin]?.let { it.add(after); return@postRunnable }
            val session = plugin.session
            if (session == null) { after(); return@postRunnable }
            stopping[plugin] = arrayListOf(after)
            fun finish() {
                try { teardown(session) }
                finally { stopping.remove(plugin)?.forEach { it() } }
            }
            try {
                session.stopDispatching()
                session.engine.stopCallbacks()
                session.engine.notifyUnload()
            } catch (e: Throwable) {
                failUnload(session, e)
                finish()
                return@postRunnable
            }
            val poll = object : Runnable {
                override fun run() {
                    try {
                        if (!session.engine.pollUnload()) {
                            EngineDispatch.scheduler.postRunnable(this, 16)
                            return
                        }
                    } catch (e: Throwable) {
                        failUnload(session, e)
                    }
                    finish()
                }
            }
            poll.run()
        }
    }

    /**
     * Records the failure and disables the plugin. Re-enabling it retries startup.
     *
     * Faults arrive during JNI upcalls while the engine's `RefCell` is borrowed, so handling must
     * be posted to avoid reentry. [stop] uses [EngineDispatch.scheduler], serializing teardown
     * with the plugin's in-flight dispatches.
     *
     * Check engine identity on the queue that owns [Plugin.engine], before posting to the UI.
     * Otherwise, an `onUnload` failure during reload could disable the replacement engine.
     */
    private fun fail(session: PluginSession, at: PluginFailure.Site, detail: String) {
        if (session.isCurrent()) fail(session.plugin, at, detail)
    }

    private fun fail(plugin: Plugin, at: PluginFailure.Site, detail: String) {
        Log.e(TAG, "[${plugin.manifest.name}] $at: $detail")
        val failure = PluginFailure(at, detail)
        AndroidUtilities.runOnUIThread {
            plugin.failure = failure
            if (failure.disables && plugin.enabled) {
                plugin.enabled = false
                PluginStore.persist(plugins)
                stop(plugin)
                announceCrash(plugin, failure)
            }
            notifyChanged()
        }
    }

    private fun announceCrash(plugin: Plugin, failure: PluginFailure) {
        if (!PluginAppVisibility.isForeground) return
        BulletinFactory.global().createSimpleBulletin(
            R.raw.error,
            formatString(R.string.InuPluginCrashed, plugin.manifest.name),
            failure.describe(),
            getString(R.string.ViewAction),
        ) {
            if (plugin in plugins) LaunchActivity.getSafeLastFragment()?.presentFragment(PluginInfoActivity(plugin))
        }.show()
    }

    private fun logConsole(session: PluginSession, budget: LogBudget, level: Int, message: String) {
        val tag = "$TAG/${session.manifest.name}"
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
            3, QuickJs.LEVEL_FAULT -> Log.e(tag, message)
            else -> Log.d(tag, message)
        }
    }

    /** per-engine, so it dies with the engine rather than needing an entry to evict */
    private class LogBudget {
        private var windowStart = 0L
        private var used = 0

        @Synchronized
        fun charge(now: Long): Verdict {
            if (now - windowStart >= LOG_WINDOW_MS) {
                windowStart = now
                used = 0
            }
            if (used < LOG_BUDGET + 1) used++
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
