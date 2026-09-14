package desu.inugram.helpers.plugins.ui

import desu.inugram.helpers.plugins.SessionResource
import desu.inugram.helpers.plugins.EngineDispatch

import android.util.Log
import desu.inugram.InuConfig
import desu.inugram.core.plugins.ActionRegistration
import desu.inugram.core.plugins.ActionRegistry
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.QuickJs
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.Utilities

/**
 * one rendered row, and the [QuickJs] a tap on it goes back to. Never a [Plugin]: a reload builds
 * a new engine whose tokens restart at 1.
 */
data class ActionKey(val pluginId: String, val kind: Int, val id: String) {
    val storageKey: String get() = "$pluginId:$id"
}

data class ActionRow(
    val owner: QuickJs,
    val token: Int,
    val key: ActionKey,
    val text: String,
    val pluginName: String,
    val icon: String?,
)

data class RegisteredActionRow(
    val owner: QuickJs,
    val token: Int,
    val key: ActionKey,
    val text: String?,
    val pluginName: String,
    val icon: String?,
    val placements: Int,
    val dynamicFields: Int,
)

/**
 * Kotlin side of `inu.register*Action` (rust: `actions.rs`): rows a plugin contributes to menus the
 * app owns.
 *
 * Dynamic getters are rendered on [EngineDispatch.scheduler]. Static presentation is cached during
 * registration, so rows without relevant getters never enter an engine.
 */
object PluginActions : SessionResource {
    private const val TAG = "InuPluginActions"

    // keep in sync with rust `actions::KIND_*`
    const val KIND_GLOBAL = 0
    const val KIND_CHAT = 1
    const val KIND_MESSAGE = 2
    const val KIND_PROFILE = 3
    const val KIND_EDITOR = 4
    private const val KIND_COUNT = 5

    // keep in sync with rust `actions::MESSAGE_PLACEMENT_*`
    const val MESSAGE_PLACEMENT_BUBBLE = 1
    const val MESSAGE_PLACEMENT_SELECTION = 2
    private const val ALL_PLACEMENTS = -1

    // keep in sync with rust `actions::DYNAMIC_*`
    const val DYNAMIC_TEXT = 1
    const val DYNAMIC_ICON = 2
    const val DYNAMIC_VISIBLE = 4
    private const val DYNAMIC_PRESENTATION = DYNAMIC_TEXT or DYNAMIC_ICON
    private const val DYNAMIC_ALL = DYNAMIC_PRESENTATION or DYNAMIC_VISIBLE

    // keep in sync with rust `actions::EDITOR_*`
    const val EDITOR_REPLACE = 0
    const val EDITOR_SEND = 1

    /** globalQueue is shared with every other engine op, so the wait has to be bounded by something other than the plugins' own good behaviour */
    const val RENDER_BUDGET_MS = 150L

    private val registry = ActionRegistry<QuickJs>()

    @Volatile private var counts = IntArray(KIND_COUNT)
    @Volatile private var selectionCount = 0
    @Volatile private var registeredRows = List(KIND_COUNT) { emptyList<RegisteredActionRow>() }

    private val optionIds = HashMap<ActionKey, Int>()
    private var nextOptionId = OPTION_BASE

    /**
     * Every menu but one is built by the gesture that opens it, so it reads the fresh count itself;
     * the drawer is built once and lives as long as the activity, so a row registered afterwards
     * would not appear until something else rebuilt it.
     *
     * Copy-on-write: a screen registers from the ui thread while [publishCounts] reads the list from
     * an upcall on [EngineDispatch.scheduler].
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

    fun register(
        session: PluginSession,
        kind: Int,
        token: Int,
        id: String,
        placements: Int = getDefaultPlacements(kind),
        text: String? = null,
        icon: String? = null,
        dynamicFields: Int = DYNAMIC_ALL,
    ): String? {
        val refusal = registry.register(session.engine, kind, token, id, placements, text, icon, dynamicFields)
        if (refusal != null) {
            Log.w(TAG, "[${session.manifest.name}] refused an action row: $refusal")
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
    override fun detach(session: PluginSession) {
        registry.forget(session.engine)
        publishCounts()
    }

    fun editorOp(op: Int, surface: Long, payloadJson: String): String? {
        if (surface !in liveEditorSurfaces) {
            return PluginWire.encodePluginError("handle-expired", "the composer this action came from is gone")
        }
        val payload = try {
            JSONObject(payloadJson)
        } catch (e: Exception) {
            return PluginWire.encodePluginError("invalid-argument", "action: ${e.message}")
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

    @Synchronized
    fun optionIdFor(key: ActionKey): Int = optionIds.getOrPut(key) { nextOptionId++ }

    @Synchronized
    fun keyForOption(optionId: Int): ActionKey? = optionIds.entries.firstOrNull { it.value == optionId }?.key

    fun rowAt(rows: List<ActionRow>, optionId: Int): ActionRow? = rows.firstOrNull { optionIdFor(it.key) == optionId }

    fun rowCount(kind: Int, placements: Int = getDefaultPlacements(kind)): Int =
        if (kind == KIND_MESSAGE && placements == MESSAGE_PLACEMENT_SELECTION) selectionCount
        else counts.getOrElse(kind) { 0 }

    fun hasRows(kind: Int, placements: Int = getDefaultPlacements(kind)): Boolean = rowCount(kind, placements) > 0

    fun registeredRows(kind: Int, placements: Int = getDefaultPlacements(kind)): List<RegisteredActionRow> {
        val rows = registeredRows.getOrElse(kind) { emptyList() }
        return PluginManager.plugins().mapNotNull { it.engine }.flatMap { engine ->
            rows.filter { it.owner === engine && it.placements and placements != 0 }
        }
    }

    fun isEnabled(key: ActionKey): Boolean = key.storageKey !in config(key.kind).value.disabled

    fun isPinned(key: ActionKey): Boolean = key.storageKey in config(key.kind).value.pinned

    fun setEnabled(key: ActionKey, enabled: Boolean) {
        val config = config(key.kind)
        val disabled = config.value.disabled.toMutableSet()
        if (enabled) disabled.remove(key.storageKey) else disabled.add(key.storageKey)
        config.value = config.value.copy(disabled = disabled)
    }

    fun setPinned(key: ActionKey, pinned: Boolean) {
        val config = config(key.kind)
        val current = config.value
        val pins = current.pinned.toMutableSet()
        if (pinned) pins.add(key.storageKey) else pins.remove(key.storageKey)
        config.value = current.copy(
            pinned = pins,
            mainOrder = if (pinned) current.mainOrder else current.mainOrder - pluginOrderKey(key),
            pluginOrder = if (pinned) current.pluginOrder - key.storageKey else (current.pluginOrder + key.storageKey).distinct(),
        )
    }

    fun resetSettings(kind: Int) {
        config(kind).value = PluginActionSettings()
    }

    fun setMainOrder(kind: Int, order: List<String>) {
        val config = config(kind)
        var index = 0
        val visible = order.toSet()
        config.value = config.value.copy(
            mainOrder = config.value.mainOrder.map {
                if (it in visible && index < order.size) order[index++] else it
            } + order.drop(index),
        )
    }

    fun setPluginOrder(kind: Int, keys: List<ActionKey>) {
        val config = config(kind)
        config.value = config.value.copy(pluginOrder = keys.map { it.storageKey })
    }

    fun orderRows(kind: Int, rows: List<ActionRow>, pinned: Boolean): List<ActionRow> {
        val order = config(kind).value.pluginOrder
        val positions = order.withIndex().associate { it.value to it.index }
        return rows.filter { isPinned(it.key) == pinned }
            .sortedWith(compareBy<ActionRow> { positions[it.key.storageKey] ?: Int.MAX_VALUE }.thenBy { rows.indexOf(it) })
    }

    fun mainOrder(kind: Int, builtIns: List<String>, plugins: List<ActionKey>): List<String> {
        val available = builtIns.map(::builtInOrderKey) + plugins.map(::pluginOrderKey)
        val saved = config(kind).value.mainOrder
        return saved.filter { it in available } + available.filter { it !in saved }
    }

    fun orderMainKeys(kind: Int, keys: List<ActionKey>): List<ActionKey> {
        val positions = mainOrder(kind, emptyList(), keys).withIndex().associate { it.value to it.index }
        return keys.sortedWith(compareBy<ActionKey> { positions[pluginOrderKey(it)] ?: Int.MAX_VALUE }.thenBy { keys.indexOf(it) })
    }

    fun builtInOrderKey(key: String): String = "b:$key"

    fun pluginOrderKey(key: ActionKey): String = "p:${key.storageKey}"

    fun renderSettings(kind: Int, onRows: (List<ActionRow>) -> Unit) {
        render(kind, ALL_PLACEMENTS, true, { "null" }, onRows)
    }

    /**
     * renders [kind]'s rows for [surface] and hands them back on the ui thread. [onRows] runs
     * exactly once and never before this returns, so a menu can reserve its rows and bind its cells
     * in the same turn it asked; a caller that has already given up ignores it.
     */
    fun render(kind: Int, surface: ActionSurface, onRows: (List<ActionRow>) -> Unit) {
        if (surface.isSecret) {
            AndroidUtilities.runOnUIThread { onRows(emptyList()) }
            return
        }
        render(kind, surface.placements, false, { session -> surface.getJson(session.permissions) }, onRows)
    }

    private fun render(
        kind: Int,
        placements: Int,
        settings: Boolean,
        getSurfaceJson: (PluginSession) -> String,
        onRows: (List<ActionRow>) -> Unit,
    ) {
        val plugins = PluginManager.plugins().mapNotNull { plugin -> plugin.session?.takeIf { it.canDispatch() } }
        val cached = registeredRows.getOrElse(kind) { emptyList() }
        val registrations = plugins.flatMap { session ->
            cached.filter { it.owner === session.engine && it.placements and placements != 0 }
        }
        val relevantFields = if (settings) DYNAMIC_PRESENTATION else DYNAMIC_ALL
        if (registrations.none { it.dynamicFields and relevantFields != 0 }) {
            AndroidUtilities.runOnUIThread { onRows(registrations.mapNotNull(::getStaticRow)) }
            return
        }
        EngineDispatch.scheduler.postRunnable {
            val dynamicRows = HashMap<Pair<QuickJs, Int>, DynamicRow>()
            for (session in plugins) {
                if (!session.canDispatch()) continue
                val plugin = session.plugin
                val engine = session.engine
                if (registrations.none { it.owner === engine && it.dynamicFields and relevantFields != 0 }) continue
                val surfaceJson = try {
                    getSurfaceJson(session)
                } catch (e: Exception) {
                    Log.e(TAG, "cannot serialize action surface for ${session.manifest.name}", e)
                    continue
                }
                val rows = engine.renderActions(kind, surfaceJson)?.let(::parseDynamicRows) ?: continue
                for (row in rows) dynamicRows[engine to row.token] = row
            }
            val rows = registrations.mapNotNull { registration ->
                if (registration.dynamicFields and relevantFields == 0) return@mapNotNull getStaticRow(registration)
                val dynamic = dynamicRows[registration.owner to registration.token] ?: return@mapNotNull null
                ActionRow(
                    registration.owner,
                    registration.token,
                    registration.key,
                    dynamic.text ?: registration.text ?: return@mapNotNull null,
                    registration.pluginName,
                    if (registration.dynamicFields and DYNAMIC_ICON != 0) dynamic.icon else registration.icon,
                )
            }
            AndroidUtilities.runOnUIThread { onRows(rows) }
        }
    }

    /**
     * the user tapped [row]. The row is resolved to a live registration on the way in, by key
     * rather than by the engine that drew it: a reload leaves menus that outlive it holding rows
     * whose engine nothing lists any more, and the same key on the new engine answers for them.
     * A key nothing registers - the plugin was disabled, uninstalled, or dropped that action -
     * does nothing rather than reaching whatever took its token.
     */
    fun dispatch(row: ActionRow, surface: ActionSurface) {
        EngineDispatch.scheduler.postRunnable {
            val live = registeredRows.getOrElse(row.key.kind) { emptyList() }
                .firstOrNull { it.key == row.key } ?: return@postRunnable
            val session = PluginManager.plugins().mapNotNull { it.session }.firstOrNull { it.engine === live.owner } ?: return@postRunnable
            val surfaceJson = try {
                surface.getJson(session.permissions)
            } catch (e: Exception) {
                Log.e(TAG, "cannot serialize action surface for ${session.manifest.name}", e)
                return@postRunnable
            }
            live.owner.dispatchAction(surface.kind, live.token, surfaceJson)
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
     * the plugin list's own order, restricted to running engines. Both halves matter: the order is
     * what `common.d.ts` promises rows come out in, and membership is what makes a row from a
     * stopped or reloaded engine inert.
     */
    private fun publishCounts() {
        val plugins = PluginManager.plugins().mapNotNull { plugin -> plugin.session?.takeIf { it.canDispatch() } }
        val order = plugins.map { it.engine }
        val rows = List(KIND_COUNT) { kind ->
            registry.registrationsInOrder(kind, order).mapNotNull { registration ->
                val session = plugins.firstOrNull { it.engine === registration.owner } ?: return@mapNotNull null
                registration.toRegisteredRow(session)
            }
        }
        if (rows == registeredRows) return
        counts = IntArray(KIND_COUNT) { kind -> rows[kind].count { it.placements and getDefaultPlacements(kind) != 0 } }
        selectionCount = rows[KIND_MESSAGE].count { it.placements and MESSAGE_PLACEMENT_SELECTION != 0 }
        registeredRows = rows
        if (onCountsChanged.isEmpty()) return
        AndroidUtilities.runOnUIThread { for (redraw in onCountsChanged) redraw() }
    }

    private data class DynamicRow(val token: Int, val text: String?, val icon: String?)

    private fun parseDynamicRows(json: String): List<DynamicRow>? {
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val row = array.getJSONObject(i)
                DynamicRow(
                    row.getInt("token"),
                    if (row.has("text")) row.getString("text") else null,
                    if (row.has("icon")) row.getString("icon") else null,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "unreadable render answer", e)
            null
        }
    }

    private fun ActionRegistration<QuickJs>.toRegisteredRow(session: PluginSession) = RegisteredActionRow(
        owner,
        token,
        ActionKey(session.plugin.id, kind, id),
        text,
        session.manifest.name,
        icon,
        placements,
        dynamicFields,
    )

    private fun getStaticRow(row: RegisteredActionRow): ActionRow? {
        val text = row.text ?: return null
        return ActionRow(row.owner, row.token, row.key, text, row.pluginName, row.icon)
    }

    private fun config(kind: Int): PluginActionSettingsConfig = when (kind) {
        KIND_CHAT -> InuConfig.CHAT_PLUGIN_ACTIONS
        KIND_MESSAGE -> InuConfig.MESSAGE_PLUGIN_ACTIONS
        else -> error("action settings are unavailable for kind $kind")
    }

    private fun getDefaultPlacements(kind: Int): Int =
        if (kind == KIND_MESSAGE) MESSAGE_PLACEMENT_BUBBLE else ALL_PLACEMENTS
}
