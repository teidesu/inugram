package desu.inugram.helpers.plugins.ui

import desu.inugram.core.plugins.PluginPermissions
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlJson
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.DialogObject
import org.telegram.tgnet.TLRPC

enum class MessageActionSource(val wire: String, val placements: Int) {
    BUBBLE("bubble", PluginActions.MESSAGE_PLACEMENT_BUBBLE),
    SELECTION("selection", PluginActions.MESSAGE_PLACEMENT_SELECTION),
}

/** Everything a `*ActionContext` is built from. Message snapshots are filtered per plugin. */
class ActionSurface private constructor(
    private val json: String,
    internal val kind: Int,
    internal val placements: Int,
    dialogId: Long,
    private val messages: List<TLRPC.Message>? = null,
) {
    /**
     * an action never fires in a secret chat, which is the same rule
     * [desu.inugram.helpers.plugins.telegram.PeerSpecs.dialogIdOf] enforces for every read - stated once
     * here so no attach point can forget it
     */
    internal val isSecret: Boolean = DialogObject.isEncryptedDialog(dialogId)

    internal fun getJson(permissions: PluginPermissions): String {
        val messages = messages ?: return json
        val policy = TlFilter.policyFor(permissions)
        return JSONObject(json)
            .put("messages", JSONArray().apply { for (message in messages) put(TlJson.toJson(message, policy)) })
            .toString()
    }

    companion object {
        fun global(accountId: Int): ActionSurface =
            ActionSurface(JSONObject().put("accountId", accountId).toString(), PluginActions.KIND_GLOBAL, -1, 0)

        fun chat(accountId: Int, dialogId: Long, topicId: Long?): ActionSurface =
            ActionSurface(chatJson(accountId, dialogId, topicId).toString(), PluginActions.KIND_CHAT, -1, dialogId)

        fun profile(accountId: Int, dialogId: Long): ActionSurface =
            ActionSurface(chatJson(accountId, dialogId, null).toString(), PluginActions.KIND_PROFILE, -1, dialogId)

        fun message(
            accountId: Int,
            dialogId: Long,
            topicId: Long?,
            source: MessageActionSource,
            messages: List<TLRPC.Message>,
        ): ActionSurface {
            return ActionSurface(
                chatJson(accountId, dialogId, topicId)
                    .put("source", source.wire)
                    .toString(),
                PluginActions.KIND_MESSAGE,
                source.placements,
                dialogId,
                messages.toList(),
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
                -1,
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
