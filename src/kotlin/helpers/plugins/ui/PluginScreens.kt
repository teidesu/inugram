package desu.inugram.helpers.plugins.ui

import desu.inugram.core.plugins.ScreenChangeAction
import desu.inugram.core.plugins.ScreenRef
import desu.inugram.core.plugins.ScreenStack
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.api.PluginApi
import desu.inugram.ui.settings.SettingsPageActivity
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ChatActivity
import org.telegram.ui.DialogsActivity
import org.telegram.ui.LaunchActivity
import org.telegram.ui.ProfileActivity

/**
 * Kotlin side of `inu.ui.getCurrentScreen`/`onScreenChanged` (rust: `screens.rs`).
 *
 * The app's own listener slot is single-slot and already claimed by `LaunchActivity`, so
 * [onFragmentStackChanged] chains onto its lambda rather than taking it; it carries no payload
 * either, so what happened is [ScreenStack.diff]'s to derive.
 *
 * It runs on the ui thread, where the fragment stack lives and is the only place it may be read,
 * publishes an immutable snapshot and posts the dispatch to [Utilities.globalQueue].
 * [currentScreenWire] answers off that snapshot without hopping, `getCurrentScreen()` being a
 * synchronous getter.
 *
 * A process a push notification woke has never seen a stack change, so the snapshot is empty and
 * the answer is `null`; an activity destroyed and recreated republishes an equal snapshot
 * ([ScreenRef] compares by value), so a configuration change is not a navigation.
 */
object PluginScreens {
    // ui-thread writes, globalQueue reads, hence @Volatile and an immutable list
    @Volatile private var stack: List<ScreenRef> = emptyList()

    @JvmStatic
    fun onFragmentStackChanged(activity: LaunchActivity) {
        val fragments = activity.actionBarLayout?.fragmentStack ?: return
        val next = fragments.filterNotNull().map(::describe)
        val previous = stack
        stack = next
        val action = ScreenStack.diff(previous, next) ?: return
        if (PluginManager.plugins().isEmpty()) return

        val change = JSONObject()
            .put("action", action.name.lowercase())
            .put("screen", next.lastOrNull()?.let(::toJson) ?: JSONObject.NULL)
            .put("previous", previous.lastOrNull()?.let(::toJson) ?: JSONObject.NULL)
            .toString()
        val stackJson = JSONArray().apply { next.forEach { put(toJson(it)) } }.toString()
        Utilities.globalQueue.postRunnable {
            for (plugin in PluginManager.plugins()) {
                plugin.engine?.dispatchScreenChange(change, stackJson)
            }
        }
    }

    fun currentScreenWire(): String {
        if (!PluginApi.isForeground) return "N"
        val top = stack.lastOrNull() ?: return "N"
        return "J${toJson(top)}"
    }

    private fun toJson(screen: ScreenRef): JSONObject {
        val out = JSONObject().put("type", screen.type).put("account", screen.accountId)
        if (screen.dialogId != 0L) out.put("dialogId", screen.dialogId)
        if (screen.topicId != 0) out.put("topicId", screen.topicId)
        return out
    }

    /**
     * A secret chat is opened as an ordinary [ChatActivity] whose `dialog_id` is
     * `DialogObject.makeEncryptedDialogId(encId)`, so the id is dropped here for the same reason
     * [desu.inugram.helpers.plugins.tg.PluginReads.dialogIdOf] answers `null` for one and
     * [PluginActions.Surface] refuses one: `common.d.ts` says a secret chat has no `DialogId` to
     * name. `type` still says there was a chat, which is what [toJson] omitting a zero id leaves.
     */
    private fun namedDialogId(id: Long): Long = if (DialogObject.isEncryptedDialog(id)) 0L else id

    /** stock has no marker for "this is a settings screen", so the fallback is the name every one of them is spelled with; getting it wrong costs a screen the `other` label */
    private fun describe(fragment: BaseFragment): ScreenRef = when (fragment) {
        is ChatActivity -> ScreenRef(
            "chat",
            namedDialogId(fragment.dialogId),
            fragment.topicId.toInt(),
            fragment.currentAccount,
        )
        is ProfileActivity -> ScreenRef("profile", namedDialogId(fragment.dialogId), 0, fragment.currentAccount)
        is DialogsActivity -> ScreenRef("dialogs", 0, 0, fragment.currentAccount)
        is SettingsPageActivity -> ScreenRef("settings", 0, 0, fragment.currentAccount)
        else -> ScreenRef(
            if (fragment.javaClass.simpleName.endsWith("SettingsActivity")) "settings" else "other",
            0,
            0,
            fragment.currentAccount,
        )
    }
}
