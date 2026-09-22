package desu.inugram.helpers.plugins.telegram

import desu.inugram.helpers.dialogs.AccountOrderHelper
import desu.inugram.helpers.plugins.EngineDispatch

import desu.inugram.helpers.plugins.AccountListener
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.ReadsListener
import desu.inugram.helpers.plugins.WritesListener
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities

object PluginAccounts {
    private var watching = false
    private var lastAccounts: String? = null

    fun listenerFor(session: PluginSession): AccountListener {
        val reads = PluginReads.listenerFor(session)
        val writes = PluginWrites.listenerFor(session)
        return object : AccountListener, ReadsListener by reads, WritesListener by writes {
            override fun accounts(): String = accountsJson()
        }
    }

    fun watch() {
        if (watching) return
        watching = true
        AndroidUtilities.runOnUIThread {
            lastAccounts = accountsJson()
            val observer = NotificationCenter.NotificationCenterDelegate { _, _, _ -> notifyIfChanged() }
            NotificationCenter.getGlobalInstance().addObserver(observer, NotificationCenter.activeAccountChanged)
            for (id in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
                val center = NotificationCenter.getInstance(id)
                center.addObserver(observer, NotificationCenter.mainUserInfoChanged)
                center.addObserver(observer, NotificationCenter.appDidLogout)
            }
        }
    }

    /** the account order is not a stock notification, so its only writer reports it here */
    fun onOrderChanged() {
        AndroidUtilities.runOnUIThread {
            if (lastAccounts != null) notifyIfChanged()
        }
    }

    /** runs on the ui thread, which owns [lastAccounts] */
    private fun notifyIfChanged() {
        // a plugin started later reads the live snapshot at install, so there is nothing to
        // keep up to date while none is running
        if (!PluginManager.anyRunning) return
        val current = accountsJson()
        if (current == lastAccounts) return
        lastAccounts = current
        EngineDispatch.scheduler.postRunnable {
            for (plugin in PluginManager.plugins()) plugin.engine?.notifyAccountsChanged()
        }
    }

    private fun accountsJson(): String {
        val ids = (0 until UserConfig.MAX_ACCOUNT_COUNT).filterTo(mutableListOf()) { UserConfig.isValidAccount(it) }
        AccountOrderHelper.sort(ids)
        val arr = JSONArray()
        for (id in ids) {
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
}
