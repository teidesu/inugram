package desu.inugram.helpers.security

import android.content.Context
import androidx.core.content.edit
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.DialogObject
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.LauncherIconController
import org.telegram.ui.LauncherIconController.LauncherIcon
import desu.inugram.helpers.InuUtils

// "Hidden chats" aka "Paranoia mode": a per-account set of dialogs that vanishes from every surface while
// paranoia mode is on. Secret (encrypted) chats are hidden unconditionally in that mode.
// State lives in its own prefs file (like PasscodeHelper) so it never lands in settings backups.
object ParanoiaHelper {
    private val prefs by lazy {
        ApplicationLoader.applicationContext.getSharedPreferences("inugram_hidden", Context.MODE_PRIVATE)
    }

    @Volatile
    private var paranoiaCache: Boolean? = null

    // immutable snapshots, swapped wholesale on mutation → lock-free reads from any thread.
    @Volatile
    private var hiddenCache: Map<Int, Set<Long>>? = null

    @Volatile
    private var visibleCache: Map<Int, Set<Long>>? = null

    @Volatile
    private var whitelistModeCache: Boolean? = null

    private const val MODE_BLACKLIST = 0
    private const val MODE_WHITELIST = 1

    var isWhitelistMode: Boolean
        get() = whitelistModeCache
            ?: (prefs.getInt("filteringMode", MODE_BLACKLIST) == MODE_WHITELIST).also { whitelistModeCache = it }
        set(value) {
            prefs.edit { putInt("filteringMode", if (value) MODE_WHITELIST else MODE_BLACKLIST) }
            whitelistModeCache = value
        }

    fun isParanoia(): Boolean =
        paranoiaCache ?: prefs.getBoolean("paranoia", false).also { paranoiaCache = it }

    @JvmStatic
    fun isHidden(account: Int, dialogId: Long): Boolean {
        if (!isParanoia()) return false
        if (DialogObject.isEncryptedDialog(dialogId)) return true
        return if (isWhitelistMode) {
            !getVisible(account).contains(dialogId)
        } else {
            getHidden(account).contains(dialogId)
        }
    }

    fun getHidden(account: Int): Set<Long> {
        val cache = hiddenCache ?: loadAll("hiddenChats").also { hiddenCache = it }
        return cache[account] ?: emptySet()
    }

    fun setHidden(account: Int, ids: Collection<Long>) {
        prefs.edit { putStringSet("hiddenChats$account", ids.map(Long::toString).toHashSet()) }
        hiddenCache = null
    }

    fun getVisible(account: Int): Set<Long> {
        val cache = visibleCache ?: loadAll("visibleChats").also { visibleCache = it }
        return cache[account] ?: emptySet()
    }

    fun setVisible(account: Int, ids: Collection<Long>) {
        prefs.edit { putStringSet("visibleChats$account", ids.map(Long::toString).toHashSet()) }
        visibleCache = null
    }

    fun getActiveList(account: Int): Set<Long> =
        if (isWhitelistMode) getVisible(account) else getHidden(account)

    fun setActiveList(account: Int, ids: Collection<Long>) {
        if (isWhitelistMode) setVisible(account, ids) else setHidden(account, ids)
    }

    private fun loadAll(prefix: String): Map<Int, Set<Long>> {
        val map = HashMap<Int, Set<Long>>()
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            val stored = prefs.getStringSet("$prefix$a", null) ?: continue
            map[a] = stored.mapNotNull { it.toLongOrNull() }.toHashSet()
        }
        return map
    }

    var hideSettings: Boolean
        get() = prefs.getBoolean("hideSettings", false)
        set(value) = prefs.edit { putBoolean("hideSettings", value) }

    // opt-in: drop the Inugram entry from stock Settings while armed
    @JvmStatic
    fun shouldHideSettings(): Boolean = isParanoia() && hideSettings

    var disableNotifications: Boolean
        get() = prefs.getBoolean("disableNotifications", false)
        set(value) = prefs.edit { putBoolean("disableNotifications", value) }

    // opt-in: silence all notifications while armed.
    @JvmStatic
    fun shouldSuppressNotifications(): Boolean = isParanoia() && disableNotifications

    var hideOtherAccounts: Boolean
        get() = prefs.getBoolean("hideOtherAccounts", false)
        set(value) = prefs.edit { putBoolean("hideOtherAccounts", value) }

    // opt-in: while armed, hide every account except the active one from switchers.
    @JvmStatic
    fun hidesOtherAccounts(): Boolean = isParanoia() && hideOtherAccounts

    var disguiseIcon: Boolean
        get() = prefs.getBoolean("disguiseIcon", false)
        set(value) = prefs.edit { putBoolean("disguiseIcon", value) }

    @Volatile
    private var disguisedCache: Boolean? = null

    // opt-in: while armed, masquerade as stock Telegram (icon + launcher name + in-app branding).
    // constant per process (toggling restarts the app), so cache it for animation hot-path callers.
    @JvmStatic
    fun isDisguised(): Boolean = disguisedCache ?: (isParanoia() && disguiseIcon).also { disguisedCache = it }

    @JvmStatic
    fun filterLauncherIcons(icons: MutableList<LauncherIcon>) {
        if (isDisguised()) {
            icons.remove(LauncherIcon.DEFAULT)
            icons.remove(LauncherIcon.STOCK)
            val disguiseIdx = icons.indexOf(LauncherIcon.DISGUISE)
            if (disguiseIdx != -1) {
                icons.removeAt(disguiseIdx)
                icons.add(0, LauncherIcon.DISGUISE)
            }
        } else {
            icons.remove(LauncherIcon.DISGUISE)
        }
    }

    private fun enableDisguise() {
        val current = LauncherIcon.values().firstOrNull { LauncherIconController.isEnabled(it) } ?: LauncherIcon.DEFAULT
        prefs.edit { putString("savedIcon", current.name) }
        LauncherIconController.setIcon(LauncherIcon.DISGUISE)
    }

    private fun disableDisguise() {
        val saved = prefs.getString("savedIcon", null) ?: return
        prefs.edit { remove("savedIcon") }
        val icon = runCatching { LauncherIcon.valueOf(saved) }.getOrNull() ?: LauncherIcon.DEFAULT
        LauncherIconController.setIcon(icon)
    }

    fun hasExitCode(): Boolean = prefs.contains("exitHash")

    fun setExitCode(code: String) {
        SecretHash.store(prefs, "exitHash", "exitSalt", code.trim())
    }

    // strips hidden peers from frequent-contacts hints (search "People" row + app shortcuts).
    @JvmStatic
    fun filterTopPeers(account: Int, peers: MutableList<TLRPC.TL_topPeer>) {
        if (!isParanoia()) return
        peers.removeAll { isHidden(account, DialogObject.getPeerDialogId(it.peer)) }
    }

    @JvmStatic
    fun matchesExitCode(query: String?): Boolean {
        if (!isParanoia() || query.isNullOrBlank()) return false
        return SecretHash.verify(prefs, "exitHash", "exitSalt", query.trim())
    }

    fun enableParanoia(fragment: BaseFragment) = setParanoia(fragment, true)

    fun disableParanoia(fragment: BaseFragment) = setParanoia(fragment, false)

    private fun setParanoia(fragment: BaseFragment, value: Boolean) {
        if (value) {
            if (disguiseIcon) enableDisguise()
        } else {
            disableDisguise()
        }
        // need commit synchronously
        prefs.edit(commit = true) { putBoolean("paranoia", value) }
        paranoiaCache = value
        fragment.parentActivity?.let { InuUtils.restartApp(it) }
    }
}
