package desu.inugram.helpers.plugins.ui

import android.util.Log
import desu.inugram.core.plugins.ActionRegistry
import desu.inugram.core.plugins.ActionRow
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.QuickJs
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DialogObject
import org.telegram.messenger.Utilities

/**
 * Kotlin side of `inu.register*Action` (rust: `actions.rs`): rows a plugin contributes to menus the
 * app owns.
 *
 * **Every menu here is two crossings, and that is forced.** Android menus are built on the ui
 * thread and an engine may only be entered from [Utilities.globalQueue], so `text`/`visible` cannot
 * run while a menu is being built: [render] posts, walks every engine in plugin-list order, and
 * hands the rows back on the ui thread. What *is* answerable synchronously is how many rows there
 * are ([rowCount]), which is what lets a menu reserve them rather than grow under the user's finger.
 *
 * A row is owned by the [QuickJs] that registered it, never by the [Plugin]: a reload builds a new
 * engine whose tokens restart at 1. [liveOrder] is both the ordering and the liveness test.
 */
object PluginActions {
    private const val TAG = "InuPluginActions"

    // keep in sync with rust `actions::KIND_*`
    const val KIND_GLOBAL = 0
    const val KIND_CHAT = 1
    const val KIND_MESSAGE = 2
    const val KIND_PROFILE = 3
    const val KIND_EDITOR = 4
    private const val KIND_COUNT = 5

    // keep in sync with rust `actions::EDITOR_*`
    const val EDITOR_REPLACE = 0
    const val EDITOR_SEND = 1

    /** globalQueue is shared with every other engine op, so the wait has to be bounded by something other than the plugins' own good behaviour */
    const val RENDER_BUDGET_MS = 150L

    private val registry = ActionRegistry<QuickJs>()

    @Volatile private var counts = IntArray(KIND_COUNT)

    /**
     * Every menu but one is built by the gesture that opens it, so it reads the fresh count itself;
     * the drawer is built once and lives as long as the activity, so a row registered afterwards
     * would not appear until something else rebuilt it.
     *
     * Copy-on-write: a screen registers from the ui thread while [publishCounts] reads the list from
     * an upcall on [Utilities.globalQueue].
     */
    private val onCountsChanged = CopyOnWriteArrayList<() -> Unit>()

    fun watchCounts(redraw: () -> Unit) {
        onCountsChanged.add(redraw)
    }

    /** the composers a `MessageEditorActionContext` may still name, ui thread + a volatile view */
    private val editorSurfaces = HashMap<Long, EditorSurface>()
    @Volatile private var liveEditorSurfaces = emptySet<Long>()
    private var nextEditorSurface = 1L

    interface EditorSurface {
        fun replaceDraft(text: String, entitiesJson: String?)
        fun sendDraft(text: String, entitiesJson: String?)
    }

    fun register(plugin: Plugin, engine: QuickJs, kind: Int, token: Int, id: String): String? {
        val refusal = registry.register(engine, kind, token, id)
        if (refusal != null) {
            Log.w(TAG, "[${plugin.manifest.name}] refused an action row: $refusal")
            // a `P` wire, so the cap refusal carries its own code: every other answer this upcall can give is a JNI-level failure, and reporting those as `quota-exceeded` tells a plugin it is at a limit it is nowhere near
            return PluginWire.encodePluginError("quota-exceeded", refusal)
        }
        publishCounts()
        return null
    }

    fun unregister(engine: QuickJs, kind: Int, token: Int) {
        registry.unregister(engine, kind, token)
        publishCounts()
    }

    /** the engine is going away; everything it drew is inert from here on */
    fun detach(engine: QuickJs) {
        registry.forget(engine)
        publishCounts()
    }

    fun editorOp(op: Int, surface: Long, payloadJson: String): String? {
        if (surface !in liveEditorSurfaces) return "the composer this action came from is gone"
        val payload = try {
            JSONObject(payloadJson)
        } catch (e: Exception) {
            return "action: ${e.message}"
        }
        val text = payload.optString("text")
        val entities = payload.optJSONArray("entities")?.toString()
        AndroidUtilities.runOnUIThread {
            val target = editorSurfaces[surface] ?: return@runOnUIThread
            if (op == EDITOR_REPLACE) target.replaceDraft(text, entities) else target.sendDraft(text, entities)
        }
        return null
    }

    /**
     * where plugin rows start in the menu-item id spaces the app's own menus use. Far above stock's
     * ids and the fork's own (`ChatHelper.OPTION_*`, `ChatActionsHelper.ACTION_*`), so a plugin row
     * can never be mistaken for one of them.
     */
    const val OPTION_BASE = 900_000

    fun optionIdAt(index: Int): Int = OPTION_BASE + index

    fun rowAt(rows: List<ActionRow<QuickJs>>, optionId: Int): ActionRow<QuickJs>? =
        rows.getOrNull(optionId - OPTION_BASE)

    fun rowCount(kind: Int): Int = counts.getOrElse(kind) { 0 }

    fun hasRows(kind: Int): Boolean = rowCount(kind) > 0

    /**
     * renders [kind]'s rows for [surface] and hands them back on the ui thread. [onRows] runs
     * exactly once and never before this returns, so a menu can reserve its rows and bind its cells
     * in the same turn it asked; a caller that has already given up ignores it.
     */
    fun render(kind: Int, surface: Surface, onRows: (List<ActionRow<QuickJs>>) -> Unit) {
        if (surface.isSecret) {
            AndroidUtilities.runOnUIThread { onRows(emptyList()) }
            return
        }
        val order = liveOrder()
        Utilities.globalQueue.postRunnable {
            val rows = registry.rowsInOrder(kind, order) { engine ->
                engine.renderActions(kind, surface.json)?.let { parseRows(engine, it) }
            }
            AndroidUtilities.runOnUIThread { onRows(rows) }
        }
    }

    /**
     * the user tapped [row]. The engine that drew it is re-checked on the way in: a plugin
     * disabled, uninstalled or reloaded while its menu was open has an engine nothing lists any
     * more, and the row does nothing rather than reaching whatever took its token.
     */
    fun dispatch(row: ActionRow<QuickJs>, surface: Surface) {
        Utilities.globalQueue.postRunnable {
            if (row.owner !in liveOrder()) return@postRunnable
            row.owner.dispatchAction(surface.kind, row.token, surface.json)
        }
    }

    fun openEditorSurface(target: EditorSurface): Long {
        val id = nextEditorSurface++
        editorSurfaces[id] = target
        liveEditorSurfaces = editorSurfaces.keys.toSet()
        return id
    }

    fun closeEditorSurface(id: Long) {
        if (editorSurfaces.remove(id) == null) return
        liveEditorSurfaces = editorSurfaces.keys.toSet()
    }

    /**
     * everything a context is built from, already serialized. Held as one string because it crosses
     * to the engine unchanged and is the same for every plugin in one menu.
     */
    class Surface private constructor(val json: String, internal val kind: Int, dialogId: Long) {
        /**
         * an action never fires in a secret chat, which is the same rule
         * [PluginReads.dialogIdOf] enforces for every read - stated once here so no attach point
         * can forget it
         */
        internal val isSecret: Boolean = DialogObject.isEncryptedDialog(dialogId)

        companion object {
            fun global(accountId: Int): Surface =
                Surface(JSONObject().put("accountId", accountId).toString(), KIND_GLOBAL, 0)

            fun chat(accountId: Int, dialogId: Long, topicId: Long?): Surface =
                Surface(chatJson(accountId, dialogId, topicId).toString(), KIND_CHAT, dialogId)

            fun profile(accountId: Int, dialogId: Long): Surface =
                Surface(chatJson(accountId, dialogId, null).toString(), KIND_PROFILE, dialogId)

            fun message(accountId: Int, dialogId: Long, topicId: Long?, messageIds: List<Int>): Surface {
                val ids = JSONArray()
                for (id in messageIds) ids.put(id)
                return Surface(
                    chatJson(accountId, dialogId, topicId).put("messageIds", ids).toString(),
                    KIND_MESSAGE,
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
            ): Surface {
                val draft = JSONObject().put("text", text)
                if (entitiesJson != null) draft.put("entities", JSONArray(entitiesJson))
                return Surface(
                    chatJson(accountId, dialogId, topicId)
                        .put("surface", surfaceId)
                        .put("draft", draft)
                        .toString(),
                    KIND_EDITOR,
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

    /**
     * the plugin list's own order, restricted to running engines. Both halves matter: the order is
     * what `common.d.ts` promises rows come out in, and membership is what makes a row from a
     * stopped or reloaded engine inert.
     */
    private fun liveOrder(): List<QuickJs> = PluginManager.plugins().mapNotNull { it.engine }

    private fun publishCounts() {
        val order = liveOrder()
        val updated = IntArray(KIND_COUNT) { registry.size(it, order) }
        if (updated.contentEquals(counts)) return
        counts = updated
        if (onCountsChanged.isEmpty()) return
        AndroidUtilities.runOnUIThread { for (redraw in onCountsChanged) redraw() }
    }

    private fun parseRows(engine: QuickJs, json: String): List<ActionRow<QuickJs>>? {
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val row = array.getJSONObject(i)
                ActionRow(engine, row.getInt("token"), row.getString("text"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "unreadable render answer", e)
            null
        }
    }
}
