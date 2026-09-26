package desu.inugram.helpers.plugins.ui

import desu.inugram.helpers.plugins.EngineDispatch

import desu.inugram.core.plugins.ScreenRef
import desu.inugram.core.plugins.ScreenStack
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.telegram.PeerSpecs
import desu.inugram.ui.settings.SettingsPageActivity
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ChatActivity
import org.telegram.ui.DialogsActivity
import org.telegram.ui.LaunchActivity
import org.telegram.ui.ProfileActivity

/**
 * Chains onto `LaunchActivity`'s single listener, which has no payload, so [ScreenStack.diff] derives
 * the change. Recreating an activity yields an equal snapshot and no event.
 */
object PluginScreens {
    // ui-thread writes, engine-queue reads
    @Volatile private var stack: List<ScreenRef> = emptyList()

    @JvmStatic
    fun onFragmentStackChanged(activity: LaunchActivity) {
        val fragments = activity.actionBarLayout?.fragmentStack ?: return
        val next = fragments.filterNotNull().map(::describe)
        val previous = stack
        stack = next
        val action = ScreenStack.diff(previous, next) ?: return
        // the stack is kept regardless, so a plugin started later reads the real one
        if (!PluginManager.anyRunning) return

        val change = JSONObject()
            .put("action", action.name.lowercase())
            .put("screen", next.lastOrNull()?.let(::toJson) ?: JSONObject.NULL)
            .put("previous", previous.lastOrNull()?.let(::toJson) ?: JSONObject.NULL)
            .toString()
        val stackJson = JSONArray().apply { next.forEach { put(toJson(it)) } }.toString()
        EngineDispatch.scheduler.postRunnable {
            for (plugin in PluginManager.plugins()) {
                plugin.engine?.dispatchScreenChange(change, stackJson)
            }
        }
    }

    fun currentScreenWire(): String {
        if (!PluginAppVisibility.isForeground) return "N"
        val top = stack.lastOrNull() ?: return "N"
        return "J${toJson(top)}"
    }

    private fun toJson(screen: ScreenRef): JSONObject {
        val out = JSONObject().put("type", screen.type).put("account", screen.accountId)
        if (screen.dialogId != 0L) {
            out.put("dialogId", PeerSpecs.toMarkedPeerId(MessagesController.getInstance(screen.accountId), screen.dialogId))
        }
        if (screen.topicId != 0) out.put("topicId", screen.topicId)
        return out
    }

    /** a secret chat is an ordinary [ChatActivity] with an encrypted `dialog_id`, which `common.d.ts` says plugins cannot name */
    private fun namedDialogId(id: Long): Long = if (DialogObject.isEncryptedDialog(id)) 0L else id

    /** stock has no settings-screen marker, so this goes by class name; a miss only costs the `other` label */
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
