package desu.inugram.helpers.plugins

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.SystemClock
import desu.inugram.InuConfig
import desu.inugram.core.plugins.FsQuota
import desu.inugram.core.plugins.GrantCatalog
import desu.inugram.core.plugins.GrantValidator
import desu.inugram.core.plugins.PluginInstalls
import desu.inugram.core.plugins.PluginManifest
import desu.inugram.core.plugins.PluginManifestParser
import desu.inugram.core.plugins.PluginPermissions
import desu.inugram.core.plugins.TlTables
import desu.inugram.helpers.plugins.io.PluginBlobs
import desu.inugram.helpers.plugins.io.PluginFetch
import desu.inugram.helpers.plugins.io.PluginFs
import desu.inugram.helpers.plugins.io.PluginLocalStorage
import desu.inugram.helpers.plugins.io.PluginPaths
import desu.inugram.helpers.plugins.io.PluginTransfers
import desu.inugram.helpers.plugins.platform.PluginJvm
import desu.inugram.helpers.plugins.platform.PluginNotifications
import desu.inugram.helpers.plugins.platform.PluginPlatform
import desu.inugram.helpers.plugins.platform.PluginXposed
import desu.inugram.helpers.plugins.telegram.PluginAccounts
import desu.inugram.helpers.plugins.telegram.PluginMedia
import desu.inugram.helpers.plugins.telegram.PluginOptimisticSend
import desu.inugram.helpers.plugins.telegram.PluginRpc
import desu.inugram.helpers.plugins.telegram.PluginUpdates
import desu.inugram.helpers.plugins.tl.TlReflect
import desu.inugram.helpers.plugins.ui.PluginActions
import desu.inugram.helpers.plugins.ui.PluginAppVisibility
import desu.inugram.helpers.plugins.ui.PluginCanvas
import desu.inugram.helpers.plugins.ui.PluginFilePicker
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
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.LaunchActivity
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Engine ops run on [EngineDispatch.scheduler]; structural list/flag mutations on the ui thread. */
object PluginManager {
    const val SAFE_MODE_ACTION = "desu.inugram.action.SAFE_MODE"
    private const val SAFE_MODE_SHORTCUT_ID = "inu_safe_mode"
    const val PLUGIN_API_VERSION = GrantCatalog.PLUGIN_API
    private const val PLATFORM = "android"

    private const val LOG_BUDGET = 200
    private const val LOG_WINDOW_MS = 10_000L

    @Suppress("DEPRECATION")
    private val appBuild by lazy { UpdateHelper.packageInfo?.versionCode?.toString() ?: "0" }

    private val plugins = mutableListOf<Plugin>()

    // the engine queue reads the order to sort interceptor chains while the ui thread mutates it
    @Volatile
    private var snapshot: List<Plugin> = emptyList()

    private val guard = BootGuard()

    val safeMode: Boolean get() = guard.safeMode

    val safeModeReason: BootGuard.Reason? get() = guard.reason

    private var booted = false
    private var lateLoaded = false
    private var lateInited = false
    private var loaded = false
    private var hosting = false

    /** read first by every stock hook, so a disabled engine costs one volatile read */
    @Volatile
    var anyRunning = false
        private set

    fun refreshAnyRunning() {
        anyRunning = snapshot.any { it.session != null }
    }

    // the plugins page and an info page can be mounted at once, and the revealed fragment resumes before the other pauses
    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()

    fun init(context: Context) {
        if (!isEngineEnabled()) return
        startHosting(context)
    }

    /** with the engine off, registers no observers and does not read the plugin directory */
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

    /** the plugins page needs this with the engine disabled too */
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val stored = PluginStore.load()
        plugins.addAll(stored.orEmpty())
        PluginStore.persist(plugins)
        republishOrder()
        if (stored != null) sweepOrphans(PluginStore.installIds(plugins))
    }

    /** runs before any plugin can start, so no engine is creating a store meanwhile */
    private fun sweepOrphans(live: Set<String>) {
        PluginFs.sweepOrphans(live)
        PluginLocalStorage.sweepOrphans(live)
        PluginJvm.sweepOrphans(live)
        PluginPaths.emptyTrash()
        PluginActions.retainInstalls(live)
    }

    fun isEngineEnabled(): Boolean = InuConfig.PLUGINS_ENABLED.value

    fun plugins(): List<Plugin> = snapshot

    /** `Plugin` has no `equals()` and a reload replaces the instance */
    fun orderIndex(): IdentityHashMap<Plugin, Int> {
        val order = IdentityHashMap<Plugin, Int>()
        snapshot.forEachIndexed { index, plugin -> order[plugin] = index }
        return order
    }

    /**
     * Runs at the end of `ApplicationLoader.postInitApplication`, which every entry point reaches, including
     * push wakeups that feed `processUpdates` without an activity. Blocks up to
     * [BootCohort.EARLY_BUDGET_MILLIS] so update processing waits for registrations.
     */
    fun onAppBoot() {
        if (booted || ApplicationLoader.applicationContext == null) return
        booted = true
        if (!isEngineEnabled()) return
        val loaded = CountDownLatch(1)
        EngineDispatch.scheduler.postRunnable {
            try {
                runPass { it.enabled && BootCohort.bootsEarly(PluginPermissions.parse(it.manifest.grants)) }
            } finally {
                loaded.countDown()
            }
        }
        loaded.await(BootCohort.EARLY_BUDGET_MILLIS, TimeUnit.MILLISECONDS)
    }

    /** the safe-mode shortcut needs LocaleController, so it cannot be registered at [init] */
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

    private fun runPass(wanted: (Plugin) -> Boolean) {
        if (!guard.startPass()) {
            PluginLog.HOST.w("manager", "safe mode (${guard.reason}); skipping plugins")
            return
        }
        for (plugin in plugins()) {
            // arming is a durable write
            if (!wanted(plugin) || plugin.running) continue
            watchCrashes()
            guard.guardPlugin { start(plugin) }
        }
    }

    private var watchingCrashes = false

    /** plugin queue only */
    private fun watchCrashes() {
        if (watchingCrashes) return
        watchingCrashes = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                guard.recordCrash()
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
        EngineDispatch.scheduler.postRunnable({ guard.survivedWindow() }, BootGuard.CRASH_WINDOW_MILLIS)
    }

    /** closes rather than restarts: android refuses a background activity start, and no alarm set here would fire in time */
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

    fun findUpdateTarget(manifest: PluginManifest): Plugin? {
        val pluginId = manifest.id ?: return null
        // answered off the ui thread that mutates the list
        return plugins().firstOrNull { it.manifest.id == pluginId }
    }

    /**
     * Reuses the install id of a same-id record that failed to load this boot, to keep its otherwise
     * unreachable storage. Plugins already in the installed list go through [update].
     */
    fun import(source: String, enabled: Boolean = true, dev: Boolean = false): ImportResult {
        val manifest = PluginManifestParser.parseOrNull(source)
            ?: return ImportResult.Refused(getString(R.string.InuPluginsErrorNoManifest))
        badGrants(manifest)?.let { return ImportResult.Refused(it) }
        val reclaimed = manifest.id?.let { PluginStore.findUnloaded(it) }
        val id = reclaimed?.id ?: PluginInstalls.mintId()
        val target = File(PluginStore.dir, PluginInstalls.fileName(id))
        if (!PluginStore.writeSource(target, source)) {
            return ImportResult.Refused(getString(R.string.InuPluginsErrorWrite))
        }
        reclaimed?.let { PluginStore.dropUnloaded(it) }
        val plugin = Plugin(id, target, source, manifest)
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

    sealed interface ImportResult {
        /** false when this took over a failed record's id: [remove] would wipe storage that belonged to it */
        class Installed(val plugin: Plugin, val reversible: Boolean) : ImportResult
        class Refused(val reason: String) : ImportResult
    }

    fun remove(plugin: Plugin) {
        plugin.enabled = false
        // same queue as stop()'s completion, so the wipe is ordered after the engine is gone
        stop(plugin) {
            PluginLocalStorage.wipe(plugin.id)
            wipeSessionScratch(plugin.id)
            PluginFs.wipe(plugin.id)
            // a class cannot be unloaded, so staged dex outlives the engine
            PluginJvm.wipe(plugin.id)
        }
        plugin.file.delete()
        plugins.remove(plugin)
        PluginStore.persist(plugins)
        PluginActions.retainInstalls(PluginStore.installIds(plugins))
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

    /** chain order is plugin-list order per `common.d.ts` */
    private fun republishOrder() {
        snapshot = plugins.toList()
        PluginRpc.refreshChainOrder()
        PluginUpdates.refreshOrder()
    }

    /** also the install flow's gate. `@plugin-api` reads like minSdkVersion: a shipped level never breaks */
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

    /** an unknown scope is a typo that narrowing would hide. unknown grant names stay ignored */
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
        // rust caches its method ids off `PluginBridge` at `start`
        val jvm = PluginJvm.listenerFor(session)
        val tl = session.tl
        val bridge = PluginBridge(
            core = core,
            rpc = PluginRpc.listenerFor(session),
            updates = PluginUpdates.listenerFor(session),
            tl = tl,
            account = PluginAccounts.listenerFor(session),
            ui = PluginUi.listenerFor(session),
            platform = PluginPlatform.listenerFor(session.log),
            fetch = PluginFetch.listenerFor(session),
            canvas = PluginCanvas.listenerFor(session),
            notifications = PluginNotifications.listenerFor(session),
            jvm = jvm,
            xposed = PluginXposed.listenerFor(session, jvm),
        )
        try {
            // runs the JNI bridge wiring, which throws when a descriptor does not resolve
            startEngine(session, bridge)
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
                // before the field is cleared: [fail] reads it to decide the fault is still this plugin's
                fail(session, PluginFailure.Site.LOAD, e.message ?: e.toString())
            }
        }
    }

    private fun startEngine(session: PluginSession, bridge: PluginBridge) {
        val quota = FsQuota.forGrants(session.manifest.grants)
        session.engine.start(
            bridge,
            QuickJs.Config(
                spillDir = PluginBlobs.dirFor(session.plugin.id),
                transferDir = PluginTransfers.dirFor(session.plugin.id),
                fsDir = quota?.let { PluginFs.dirFor(session.plugin.id) } ?: "",
                fsQuotaBytes = quota ?: 0,
                fsUnscoped = session.permissions.has("unsafe.fs"),
                installFs = quota != null,
                androidDirs = PluginFs.androidDirs(),
                localStoragePath = PluginLocalStorage.pathFor(session.plugin.id),
                installJvm = bridge.jvm != null,
                installXposed = bridge.xposed != null,
                grants = session.permissions,
            ),
        )
        // no callback can hear this, the plugin's code not having run yet
        if (!PluginAppVisibility.isForeground) session.engine.appVisibilityChanged(PluginAppVisibility.MODE_BACKGROUND)
    }

    private fun mayRun(plugin: Plugin): Boolean = plugin.enabled && isEngineEnabled() && !safeMode

    private fun failUnload(session: PluginSession, e: Throwable) =
        fail(session, PluginFailure.Site.UNLOAD, e.message ?: e.toString())

    /** durable trees ([PluginFs], [PluginJvm], [PluginLocalStorage]) are uninstall's alone */
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
     * Xposed needs the JVM handle table while removing hooks. File cleanup waits for `close()`, which
     * releases rust's spill-file descriptors. [beforeClear] runs while `plugin.engine` is still [engine].
     */
    private fun teardown(session: PluginSession, beforeClear: () -> Unit = {}) {
        session.engine.stopCallbacks()
        // handle table released last: abandoned continuations may still read their own request view
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
     * Faults arrive during JNI upcalls while the engine's `RefCell` is borrowed, so handling is posted.
     * Engine identity is checked on the queue that owns [Plugin.engine]: an `onUnload` failure during
     * reload must not disable the replacement engine.
     */
    private fun fail(session: PluginSession, at: PluginFailure.Site, detail: String) {
        if (session.isCurrent()) fail(session.plugin, at, detail)
    }

    private fun fail(plugin: Plugin, at: PluginFailure.Site, detail: String) {
        PluginLog.of(plugin).e("manager", "$at: $detail")
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
        when (budget.charge(SystemClock.uptimeMillis())) {
            LogBudget.Verdict.DROP -> return
            LogBudget.Verdict.LAST -> {
                session.log.w("console", "spent its log budget ($LOG_BUDGET per ${LOG_WINDOW_MS / 1000}s); muting the rest")
                return
            }

            LogBudget.Verdict.PASS -> Unit
        }
        session.log.console(level, message)
    }

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

    /** anything mutating a [Plugin] field owes a call */
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
            PluginLog.HOST.e("manager", "safe-mode shortcut failed", e)
        }
    }
}
