package desu.inugram.helpers.plugins.ui

import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.DialogObject

/**
 * everything a `*ActionContext` is built from, already serialized. Held as one string because it
 * crosses to the engine unchanged and is the same for every plugin in one menu.
 */
class ActionSurface private constructor(val json: String, internal val kind: Int, dialogId: Long) {
    /**
     * an action never fires in a secret chat, which is the same rule
     * [desu.inugram.helpers.plugins.tg.PeerSpecs.dialogIdOf] enforces for every read - stated once
     * here so no attach point can forget it
     */
    internal val isSecret: Boolean = DialogObject.isEncryptedDialog(dialogId)

    companion object {
        fun global(accountId: Int): ActionSurface =
            ActionSurface(JSONObject().put("accountId", accountId).toString(), PluginActions.KIND_GLOBAL, 0)

        fun chat(accountId: Int, dialogId: Long, topicId: Long?): ActionSurface =
            ActionSurface(chatJson(accountId, dialogId, topicId).toString(), PluginActions.KIND_CHAT, dialogId)

        fun profile(accountId: Int, dialogId: Long): ActionSurface =
            ActionSurface(chatJson(accountId, dialogId, null).toString(), PluginActions.KIND_PROFILE, dialogId)

        fun message(accountId: Int, dialogId: Long, topicId: Long?, messageIds: List<Int>): ActionSurface {
            val ids = JSONArray()
            for (id in messageIds) ids.put(id)
            return ActionSurface(
                chatJson(accountId, dialogId, topicId).put("messageIds", ids).toString(),
                PluginActions.KIND_MESSAGE,
                dialogId,
            )
        }

        fun editor(
            accountId: Int,
            dialogId: Long,
            topicId: Long?,
            surfaceId: Long,
            text: String,
            entitiesJson: String?,
        ): ActionSurface {
            val draft = JSONObject().put("text", text)
            if (entitiesJson != null) draft.put("entities", JSONArray(entitiesJson))
            return ActionSurface(
                chatJson(accountId, dialogId, topicId)
                    .put("surface", surfaceId)
                    .put("draft", draft)
                    .toString(),
                PluginActions.KIND_EDITOR,
                dialogId,
            )
        }

        private fun chatJson(accountId: Int, dialogId: Long, topicId: Long?): JSONObject {
            val out = JSONObject().put("accountId", accountId).put("dialogId", dialogId)
            if (topicId != null && topicId != 0L) out.put("topicId", topicId)
            return out
        }
    }
}
