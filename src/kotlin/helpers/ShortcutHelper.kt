package desu.inugram.helpers

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import desu.inugram.helpers.security.ParanoiaHelper
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.R
import org.telegram.ui.LaunchActivity

// Fork-owned launcher (long-press) shortcuts. Re-applied on every stock buildShortcuts() pass, since that
// wipes all dynamic shortcuts. Add an Entry here to expose a new one.
object ShortcutHelper {
    private class Entry(
        val id: String,
        val action: String,
        val labelRes: Int,
        val iconRes: Int,
        val shouldShow: () -> Boolean,
        val onClick: (LaunchActivity) -> Unit,
    )

    private val entries = listOf(
        Entry(
            id = "inu_enter_paranoia",
            action = "desu.inugram.action.ENTER_PARANOIA",
            labelRes = R.string.InuParanoiaMode,
            iconRes = R.drawable.msg_permissions,
            shouldShow = ParanoiaHelper::shouldShowLauncherShortcut,
            onClick = { activity ->
                if (!ParanoiaHelper.isParanoia() && ParanoiaHelper.canUseLauncherShortcut()) {
                    ParanoiaHelper.enableParanoia(activity)
                }
            },
        ),
    )

    @JvmStatic
    fun sync(context: Context) {
        for (entry in entries) {
            if (entry.shouldShow()) {
                val intent = Intent(context, LaunchActivity::class.java).setAction(entry.action)
                val shortcut = ShortcutInfoCompat.Builder(context, entry.id)
                    .setShortLabel(getString(entry.labelRes))
                    .setLongLabel(getString(entry.labelRes))
                    .setIcon(IconCompat.createWithResource(context, entry.iconRes))
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
        entry.onClick(activity)
        return true
    }
}
