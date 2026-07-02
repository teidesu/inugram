package desu.inugram.helpers.security

import android.app.Activity
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

    fun isParanoia(): Boolean =
        paranoiaCache ?: prefs.getBoolean("paranoia", false).also { paranoiaCache = it }

    @JvmStatic
    fun isHidden(account: Int, dialogId: Long): Boolean {
        if (!isParanoia()) return false
        if (DialogObject.isEncryptedDialog(dialogId)) return true
        // service notifications (login codes, etc.) — never hide, would lock user out
        if (dialogId == 777000L) return false
        val selected = getHidden(account).contains(dialogId)
        return if (whitelist) !selected else selected
    }

    fun getHidden(account: Int): Set<Long> {
        val cache = hiddenCache ?: loadAll().also { hiddenCache = it }
        return cache[account] ?: emptySet()
    }

    private fun loadAll(): Map<Int, Set<Long>> {
        val map = HashMap<Int, Set<Long>>()
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            val stored = prefs.getStringSet("hiddenChats$a", null) ?: continue
            map[a] = stored.mapNotNull { it.toLongOrNull() }.toHashSet()
        }
        return map
    }

    fun setHidden(account: Int, ids: Collection<Long>) {
        prefs.edit { putStringSet("hiddenChats$account", ids.map(Long::toString).toHashSet()) }
        hiddenCache = null
        reconcileLauncherShortcut()
    }

    var whitelist: Boolean
        get() = prefs.getBoolean("whitelist", false)
        set(value) = prefs.edit { putBoolean("whitelist", value) }

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

    var hideFolders: Boolean
        get() = prefs.getBoolean("hideFolders", false)
        set(value) = prefs.edit { putBoolean("hideFolders", value) }

    // opt-in: collapse the folder tab strip and "Add to folder" submenu while armed.
    @JvmStatic
    fun shouldHideFolders(): Boolean = isParanoia() && hideFolders

    var disguiseIcon: Boolean
        get() = prefs.getBoolean("disguiseIcon", false)
        set(value) = prefs.edit { putBoolean("disguiseIcon", value) }

    // opt-in: expose a launcher long-press shortcut to enter paranoia mode.
    var launcherShortcut: Boolean
        get() = prefs.getBoolean("launcherShortcut", false)
        set(value) = prefs.edit { putBoolean("launcherShortcut", value) }

    // same precondition as entering via the settings button: an exit code and at least one picked chat.
    fun canUseLauncherShortcut(account: Int = UserConfig.selectedAccount): Boolean =
        hasExitCode() && getHidden(account).isNotEmpty()

    // shown only when armed-able and not already armed (while armed it must vanish).
    @JvmStatic
    fun shouldShowLauncherShortcut(): Boolean =
        launcherShortcut && !isParanoia() && canUseLauncherShortcut()

    // force the toggle off once its preconditions no longer hold (e.g. hidden set cleared).
    private fun reconcileLauncherShortcut() {
        if (launcherShortcut && !canUseLauncherShortcut()) launcherShortcut = false
    }

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
        reconcileLauncherShortcut()
    }

    // strips hidden peers from frequent-contacts hints (search "People" row + app shortcuts).
    @JvmStatic
    fun filterTopPeers(account: Int, peers: MutableList<TLRPC.TL_topPeer>) {
        if (!isParanoia()) return
        peers.removeAll { isHidden(account, DialogObject.getPeerDialogId(it.peer)) }
    }

    @JvmStatic
    fun filterContacts(account: Int, list: MutableList<TLRPC.TL_contact>) {
        if (!isParanoia()) return
        list.removeAll { isHidden(account, it.user_id) }
    }

    @JvmStatic
    fun anyHidden(account: Int, ids: Collection<Long>): Boolean {
        if (!isParanoia()) return false
        return ids.any { isHidden(account, it) }
    }

    @JvmStatic
    fun matchesExitCode(query: String?): Boolean {
        if (!isParanoia() || query.isNullOrBlank()) return false
        return SecretHash.verify(prefs, "exitHash", "exitSalt", query.trim())
    }

    fun enableParanoia(fragment: BaseFragment) {
        fragment.parentActivity?.let { enableParanoia(it) }
    }

    fun disableParanoia(fragment: BaseFragment) {
        fragment.parentActivity?.let { disableParanoia(it) }
    }

    fun enableParanoia(activity: Activity) = setParanoia(activity, true)

    fun disableParanoia(activity: Activity) = setParanoia(activity, false)

    private fun setParanoia(activity: Activity, value: Boolean) {
        if (value) {
            if (disguiseIcon) enableDisguise()
        } else {
            disableDisguise()
        }
        // need commit synchronously
        prefs.edit(commit = true) { putBoolean("paranoia", value) }
        paranoiaCache = value
        InuUtils.restartApp(activity)
    }
}
