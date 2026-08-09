package desu.inugram.helpers

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import desu.inugram.InuConfig
import desu.inugram.helpers.security.ParanoiaHelper
import desu.inugram.helpers.security.PasscodeHelper
import desu.inugram.ui.AccountPickerActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.ui.Components.AlertsCreator
import org.telegram.ui.LaunchActivity

// Fork-owned launcher (long-press) shortcuts. Re-applied on every stock buildShortcuts() pass, since that
// wipes all dynamic shortcuts. Add an Entry here to expose a new one.
// Stock ranks its own: 0 = compose, 1+ = top chats. Launchers only render the first few by rank.
object ShortcutHelper {
    const val SWITCH_ACCOUNT_ACTION = "desu.inugram.action.SWITCH_ACCOUNT"

    private class Entry(
        val id: String,
        val action: String,
        val labelRes: Int,
        val iconRes: Int,
        val rank: Int,
        val target: Class<out Activity> = LaunchActivity::class.java,
        val requiresUnlocked: Boolean = false,
        val shouldShow: () -> Boolean,
        val onClick: (LaunchActivity) -> Unit,
    )

    private val entries = listOf(
        Entry(
            id = "inu_enter_paranoia",
            action = "desu.inugram.action.ENTER_PARANOIA",
            labelRes = R.string.InuParanoiaMode,
            iconRes = R.drawable.inu_shortcut_paranoia,
            rank = 0,
            shouldShow = ParanoiaHelper::shouldShowLauncherShortcut,
            onClick = { activity ->
                if (!ParanoiaHelper.isParanoia() && ParanoiaHelper.canUseLauncherShortcut()) {
                    ParanoiaHelper.enableParanoia(activity)
                }
            },
        ),
        Entry(
            id = "inu_switch_account",
            action = SWITCH_ACCOUNT_ACTION,
            labelRes = R.string.InuAccountSwitchShortcut,
            iconRes = R.drawable.inu_shortcut_switch_account,
            rank = 1,
            target = AccountPickerActivity::class.java,
            requiresUnlocked = true,
            shouldShow = { InuConfig.ACCOUNT_SWITCH_SHORTCUT.value && countSelectableAccounts() > 1 },
            onClick = { activity -> showAccountPicker(activity) },
        ),
    )

    @JvmStatic
    fun sync(context: Context) {
        for (entry in entries) {
            if (entry.shouldShow()) {
                val intent = Intent(context, entry.target).setAction(entry.action)
                val shortcut = ShortcutInfoCompat.Builder(context, entry.id)
                    .setShortLabel(getString(entry.labelRes))
                    .setLongLabel(getString(entry.labelRes))
                    .setIcon(IconCompat.createWithResource(context, entry.iconRes))
                    .setRank(entry.rank)
                    .setIntent(intent)
                    .build()
                ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            } else {
                ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(entry.id))
            }
        }
    }

    @JvmStatic
    fun handleAction(activity: LaunchActivity, intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        val entry = entries.firstOrNull { it.action == action } ?: return false
        // returning false lets stock stash the intent and replay handleIntent() once unlocked,
        // instead of us drawing over the passcode screen.
        if (entry.requiresUnlocked && (AndroidUtilities.needShowPasscode() || SharedConfig.isWaitingForPasscodeEnter)) {
            return false
        }
        entry.onClick(activity)
        return true
    }

    fun countSelectableAccounts(): Int =
        (0 until UserConfig.MAX_ACCOUNT_COUNT).count {
            UserConfig.getInstance(it).currentUser != null && !PasscodeHelper.isAccountHidden(it)
        }

    // in-app fallback, used when AccountPickerActivity handed off because a passcode is set. the app is
    // already resuming here, so the account we're about to leave would otherwise report itself online.
    private fun showAccountPicker(activity: LaunchActivity) {
        if (countSelectableAccounts() < 2) return
        val previous = UserConfig.selectedAccount
        val controller = MessagesController.getInstance(previous)
        controller.ignoreSetOnline = true

        val dialog = AlertsCreator.createAccountSelectDialog(activity) { account ->
            controller.ignoreSetOnline = false
            if (account != previous) activity.switchToAccount(account, true)
        }
        if (dialog == null) {
            controller.ignoreSetOnline = false
            return
        }
        dialog.setOnDismissListener { controller.ignoreSetOnline = false }
        dialog.show()
    }
}
