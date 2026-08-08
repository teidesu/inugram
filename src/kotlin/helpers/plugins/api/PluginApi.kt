package desu.inugram.helpers.plugins.api

import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.ApiListener
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.ui.PluginActions
import desu.inugram.helpers.plugins.ui.PluginIcons
import desu.inugram.helpers.plugins.ui.PluginScreens
import desu.inugram.helpers.plugins.ui.PluginUi
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.ui.LaunchActivity

/**
 * Wires `inu.kv`/`inu.ui.*`/`inu.account*` (rust: `api.rs`, `account.rs`) into [PluginKv], the app
 * UI and [UserConfig]. Upcalls arrive on [Utilities.globalQueue]; anything touching views hops to
 * the UI thread and settles back on it.
 */
object PluginApi {
    private const val TAG = "InuPluginApi"

    fun listenerFor(plugin: Plugin, engine: QuickJs): ApiListener {
        watchAccounts()
        return object : ApiListener {
            override fun kv(op: Int, key: String, value: String): String {
                // the engine's grant check already ran in native; this is belt-and-braces
                if (!plugin.permissions.has("kv")) {
                    return PluginWire.encodeNotGranted("kv")
                }
                return PluginKv.handleOp(plugin.id, op, key, value)
            }

            override fun accounts(): String = accountsJson()

            override fun uiToast(text: String) {
                AndroidUtilities.runOnUIThread {
                    Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_SHORT).show()
                }
            }

            override fun uiDialog(requestId: Long, optionsJson: String): String? =
                PluginUi.dialog(plugin, engine, requestId, optionsJson)

            override fun uiPrompt(requestId: Long, optionsJson: String): String? =
                PluginUi.prompt(plugin, engine, requestId, optionsJson)

            override fun uiChooser(requestId: Long, optionsJson: String): String? =
                PluginUi.chooser(plugin, engine, requestId, optionsJson)

            override fun uiCurrentScreen(): String = PluginScreens.currentScreenWire()

            override fun openUrl(url: String) = PluginApi.openUrl(url)

            override fun clipboardRead(): String = readClipboard()

            override fun clipboardWrite(text: String) {
                AndroidUtilities.addToClipboard(text)
            }

            override fun uiOpenPage(pageId: Long): String? =
                PluginUi.openPage(plugin, engine, pageId)

            override fun uiOpenFragment(handle: Long): String? =
                PluginUi.openFragment(engine, handle)

            override fun uiRegisterSettings(pageId: Long) =
                PluginUi.registerSettings(plugin, pageId)

            override fun uiUnregisterSettings(pageId: Long) =
                PluginUi.unregisterSettings(plugin, pageId)

            override fun uiInvalidate(pageId: Long) =
                PluginUi.invalidate(engine, pageId)

            override fun uiOpenMenu(menuId: Long, pageId: Long, anchorKey: String, itemsJson: String): String? =
                PluginUi.openMenu(plugin, engine, menuId, pageId, anchorKey, itemsJson)

            override fun iconResolves(kind: Int, value: String): Boolean =
                PluginIcons.iconResolves(kind, value)

            override fun actionRegister(kind: Int, token: Int, id: String): String? =
                PluginActions.register(plugin, engine, kind, token, id)

            override fun actionUnregister(kind: Int, token: Int) =
                PluginActions.unregister(engine, kind, token)

            override fun actionEditor(op: Int, surface: Long, payloadJson: String): String? =
                PluginActions.editorOp(op, surface, payloadJson)
        }
    }

    /**
     * Deliberately **not** `Browser.openUrl`, which appends the account's `autologin_token` to any
     * url whose host the server put in `autologinDomains` - precisely what the takeover filter
     * strips out of `config`. Routing a plugin's url through it would hand back what the filter took
     * away, to a host the plugin chose.
     *
     * With no ui this is a no-op: android refuses a background activity start.
     */
    private fun openUrl(url: String) {
        AndroidUtilities.runOnUIThread {
            val context = LaunchActivity.instance ?: ApplicationLoader.applicationContext ?: return@runOnUIThread
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "openUrl failed", e)
            }
        }
    }

    /**
     * answers synchronously off globalQueue: `getPrimaryClip` is a binder call and touches no view.
     *
     * **The clip's own text, never `coerceToText`**, which dereferences a `content://` uri through
     * *this app's* permissions and would turn "read what the user copied" into "read any provider
     * the app can reach". A clip carrying only a uri is therefore "".
     */
    private fun readClipboard(): String {
        return try {
            val manager = ApplicationLoader.applicationContext
                ?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return ""
            val clip = manager.primaryClip ?: return ""
            (0 until clip.itemCount)
                .asSequence()
                .mapNotNull { clip.getItemAt(it).text }
                .firstOrNull()
                ?.toString()
                .orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "clipboard read failed", e)
            ""
        }
    }

    // an activity restarting across a configuration change stops before its replacement starts, so the count blips through zero
    private const val BACKGROUND_DEBOUNCE_MS = 700L

    // UI-thread state read from globalQueue, hence @Volatile. false until an activity says otherwise: a process a push woke has no ui and never will
    @Volatile private var foreground = false

    /** `getCurrentScreen()` answers `null` while the app is backgrounded, and the stack alone cannot say that - going away does not change it */
    internal val isForeground: Boolean get() = foreground
    private var watchingVisibility = false
    private var startedActivities = 0
    private val enterBackground = Runnable { if (startedActivities == 0) publishVisibility(false) }

    /** call once from [PluginManager.init], before any activity exists, so the count never misses the first start. An app-wide signal needs no stock patch */
    fun watchVisibility(context: Context) {
        if (watchingVisibility) return
        watchingVisibility = true
        val app = context.applicationContext as? Application
        if (app == null) {
            publishVisibility(true)
            return
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                AndroidUtilities.cancelRunOnUIThread(enterBackground)
                publishVisibility(true)
            }

            override fun onActivityStopped(activity: Activity) {
                if (--startedActivities > 0) return
                startedActivities = 0
                AndroidUtilities.runOnUIThread(enterBackground, BACKGROUND_DEBOUNCE_MS)
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, out: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun publishVisibility(visible: Boolean) {
        if (foreground == visible) return
        foreground = visible
        Utilities.globalQueue.postRunnable {
            for (plugin in PluginManager.plugins()) plugin.engine?.appVisibilityChanged(visible)
        }
    }

    // the last list plugins were told about, so the noisy per-account notifications below only cost a comparison
    private var watching = false
    private var lastAccounts: String? = null

    private fun accountsJson(): String {
        val arr = JSONArray()
        for (id in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (!UserConfig.isValidAccount(id)) continue
            val config = UserConfig.getInstance(id)
            arr.put(
                JSONObject()
                    .put("id", id)
                    .put("userId", config.getClientUserId())
                    .put("isCurrent", id == UserConfig.selectedAccount)
                    .put("isPremium", config.isPremium())
            )
        }
        return arr.toString()
    }

    /**
     * subscribes to the three things that move the logged-in set. only `activeAccountChanged` is
     * about accounts as such; a login is announced as that account's `mainUserInfoChanged` (which
     * also fires for a rename or a premium purchase) and a logout as its `appDidLogout`, so what
     * actually gates the fan-out is the snapshot comparison.
     */
    private fun watchAccounts() {
        if (watching) return
        watching = true
        AndroidUtilities.runOnUIThread {
            lastAccounts = accountsJson()
            val observer = NotificationCenter.NotificationCenterDelegate { _, _, _ ->
                val current = accountsJson()
                if (current == lastAccounts) return@NotificationCenterDelegate
                lastAccounts = current
                Utilities.globalQueue.postRunnable {
                    for (plugin in PluginManager.plugins()) plugin.engine?.notifyAccountsChanged()
                }
            }
            NotificationCenter.getGlobalInstance().addObserver(observer, NotificationCenter.activeAccountChanged)
            for (id in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
                val center = NotificationCenter.getInstance(id)
                center.addObserver(observer, NotificationCenter.mainUserInfoChanged)
                center.addObserver(observer, NotificationCenter.appDidLogout)
            }
        }
    }
}
