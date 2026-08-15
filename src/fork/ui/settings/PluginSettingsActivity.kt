package desu.inugram.ui.settings

import android.content.Context
import android.util.Log
import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.platform.PluginJvm
import desu.inugram.helpers.plugins.ui.PluginUi
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Stories.recorder.ButtonWithCounterView

/**
 * Generic host for a plugin-defined settings page (`inu.ui.settingsPage`; rust: `ui.rs`). The
 * page is a declarative model: [requestRender] asks the engine (on globalQueue) for the current
 * element tree as JSON, [fillItems] maps it onto UItems, and every user interaction dispatches a
 * callback slot back into JS followed by an automatic re-render.
 *
 * Row identity is the `key` the engine stamps on every element (`ui.rs`'s `alloc_row_key`), which
 * is also what a `UIAnchor` names; here it is hashed to the stable int the list differ wants.
 */
class PluginSettingsActivity(
    val plugin: Plugin,
    val engine: QuickJs,
    val pageId: Long,
) : SettingsPageActivity() {

    private class SelectOption(val text: String, val subtitle: String?)

    private sealed class Row(val uid: Int, val secondarySlot: Int) {
        class Header(uid: Int, val text: String) : Row(uid, 0)
        class Check(uid: Int, val text: String, val subtitle: String?, val checked: Boolean, val slot: Int, secondary: Int) : Row(uid, secondary)
        class Button(uid: Int, val text: String, val subtitle: String?, val value: String?, val icon: String?, val danger: Boolean, val slot: Int, secondary: Int) : Row(uid, secondary)
        class Select(uid: Int, val text: String, val options: List<SelectOption>, val selected: Int, val icon: String?, val dialog: Boolean, val slot: Int, secondary: Int) : Row(uid, secondary)
        class Slider(
            uid: Int, val text: String?, val min: Double, val max: Double, val step: Double,
            val value: Double, val default: Double?, val labels: List<String>?, val slot: Int,
        ) : Row(uid, 0) {
            /** identity of everything but the live value - a change means the cached cell is stale */
            fun configKey(): String = "$text|$min|$max|$step|$default|${labels?.joinToString("\u0000")}"
        }
        class Separator(uid: Int, val text: String?) : Row(uid, 0)

        /** `inu.android.nativeView`: an `inu.jvm` handle id, resolved to a real `View` at bind */
        class Native(uid: Int, val handle: Long) : Row(uid, 0)
    }

    private class PageModel(
        val title: String,
        val rows: List<Row>,
        /** anchor key -> row uid, for resolving a `UIAnchor` back to a view */
        val rowsByKey: Map<String, Int>,
        val bottomKey: String?,
        val bottomText: String?,
        val bottomSlot: Int,
    )

    private var model: PageModel? = null
    private var bottomButton: ButtonWithCounterView? = null
    private class CachedSlider(val configKey: String, val cell: SliderCell)
    private val sliderCells = HashMap<Int, CachedSlider>()

    override fun getTitle(): CharSequence = model?.title ?: plugin.manifest.name

    override fun onFragmentCreate(): Boolean {
        PluginUi.onPageOpened(this)
        requestRender()
        return super.onFragmentCreate()
    }

    /**
     * a theme or locale rebuild recreates the whole view tree without destroying the fragment - the
     * page survives it, every view cached off the previous tree does not. The model does survive,
     * so the sticky button is re-attached here rather than waiting for the next render.
     */
    override fun createView(context: Context): View {
        bottomButton = null
        sliderCells.clear()
        return super.createView(context).also { root ->
            model?.let { maybeAttachBottomButton(it, root) }
        }
    }

    override fun onFragmentDestroy() {
        PluginUi.onPageClosed(this)
        super.onFragmentDestroy()
    }

    fun requestRender() {
        Utilities.globalQueue.postRunnable { renderNow() }
    }

    /** globalQueue only */
    private fun renderNow() {
        if (plugin.engine !== engine) return
        val json = engine.uiRender(pageId) ?: return
        val parsed = try {
            parseModel(json)
        } catch (e: Exception) {
            Log.e(TAG, "[${plugin.manifest.name}] bad render payload", e)
            return
        }
        AndroidUtilities.runOnUIThread {
            model = parsed
            actionBar?.setTitle(parsed.title)
            maybeAttachBottomButton(parsed, fragmentView)
            listView?.adapter?.update(true)
        }
    }

    private fun dispatch(slot: Int, argJson: String) {
        Utilities.globalQueue.postRunnable {
            if (plugin.engine !== engine) return@postRunnable
            engine.uiEvent(pageId, slot, argJson)
            renderNow()
        }
    }

    private fun parseModel(json: String): PageModel {
        val root = JSONObject(json)
        val itemsArr = root.getJSONArray("items")
        val usedIds = HashSet<Int>()
        val rowsByKey = HashMap<String, Int>()
        fun uidFor(key: String): Int {
            var uid = key.hashCode()
            // 0/-1 collide with "no item" sentinels used around UItem ids
            if (uid == 0 || uid == -1) uid = 2
            while (!usedIds.add(uid)) uid++
            rowsByKey[key] = uid
            return uid
        }

        val rows = (0 until itemsArr.length()).map { i ->
            val o = itemsArr.getJSONObject(i)
            val type = o.getString("type")
            val text = o.optString("text").takeIf { it.isNotEmpty() }
            val uid = uidFor(o.getString("key"))
            val secondary = o.optInt("onSecondaryClick", 0)
            when (type) {
                "header" -> Row.Header(uid, o.getString("text"))
                "separator" -> Row.Separator(uid, text)
                "native" -> Row.Native(uid, o.getLong("handle"))
                "check" -> Row.Check(uid, o.getString("text"), o.optString("subtitle").takeIf { it.isNotEmpty() }, o.getBoolean("checked"), o.getInt("onChange"), secondary)
                "button" -> Row.Button(
                    uid, o.getString("text"),
                    o.optString("subtitle").takeIf { it.isNotEmpty() },
                    o.optString("value").takeIf { it.isNotEmpty() },
                    o.optString("icon").takeIf { it.isNotEmpty() },
                    o.optBoolean("danger"), o.getInt("onClick"), secondary,
                )
                "select" -> {
                    val optionsArr = o.getJSONArray("items")
                    val options = (0 until optionsArr.length()).map { j ->
                        val opt = optionsArr.getJSONObject(j)
                        SelectOption(opt.getString("text"), opt.optString("subtitle").takeIf { it.isNotEmpty() })
                    }
                    Row.Select(
                        uid, o.getString("text"), options, o.getInt("selected"),
                        o.optString("icon").takeIf { it.isNotEmpty() },
                        o.optBoolean("dialog"), o.getInt("onChange"), secondary,
                    )
                }
                "slider" -> Row.Slider(
                    uid, text, o.getDouble("min"), o.getDouble("max"), o.getDouble("step"),
                    o.getDouble("value"), if (o.has("default")) o.getDouble("default") else null,
                    o.optJSONArray("labels")?.let { arr -> (0 until arr.length()).map(arr::getString) },
                    o.getInt("onChange"),
                )
                else -> throw IllegalArgumentException("unknown element type '$type'")
            }
        }

        val bottom = root.optJSONObject("bottomButton")
        return PageModel(
            root.getString("title"),
            rows,
            rowsByKey,
            bottom?.getString("key"),
            bottom?.getString("text"),
            bottom?.getInt("onClick") ?: 0,
        )
    }

    /**
     * UI thread. The list is virtualized, so a row that exists in the model but is scrolled out of
     * view has no holder and answers null - the caller treats that as "not anchorable" rather than
     * as an error, since it is the same situation as the row having gone away entirely.
     */
    fun anchorViewFor(key: String): View? {
        val model = model ?: return null
        if (key == model.bottomKey) return bottomButton
        val uid = model.rowsByKey[key] ?: return null
        val list = listView ?: return null
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            val position = list.getChildAdapterPosition(child)
            if (position >= 0 && list.adapter.getItem(position)?.id == uid) return child
        }
        return null
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val model = model ?: return
        val liveUids = HashSet<Int>()
        for (row in model.rows) {
            liveUids.add(row.uid)
            val item = when (row) {
                is Row.Header -> UItem.asHeader(row.uid, row.text)
                is Row.Separator -> UItem.asShadow(row.uid, row.text)
                is Row.Check -> buildCheck(row)
                is Row.Button -> buildButton(row)
                is Row.Select -> ButtonCellFactory.of(row.uid, row.text, row.options[row.selected].text, null, ButtonIcon(row.icon, engine), false)
                is Row.Slider -> UItem.asCustom(row.uid, sliderCellFor(row))
                // the view is the plugin's, not ours: a handle it has since released, or one that
                // never named a View, drops the row rather than failing the whole render - the
                // same rule an action row's throwing `visible` follows
                is Row.Native -> (PluginJvm.objectAt(engine, row.handle) as? View)?.let { UItem.asCustom(row.uid, it) }
            } ?: continue
            items.add(item)
        }
        sliderCells.keys.retainAll(liveUids)
    }

    private fun buildCheck(row: Row.Check): UItem {
        if (row.subtitle == null) {
            return UItem.asCheck(row.uid, row.text).also { it.checked = row.checked }
        }
        return UItem.asButtonCheck(row.uid, row.text, row.subtitle).also {
            it.checked = row.checked
            it.bind = Utilities.Callback { view ->
                val cell = view as? NotificationsCheckCell ?: return@Callback
                // stock bind sets the checkbox hard (and cell.isChecked already reads the new
                // state by now); mirror TextCheckCell's itemId trick by remembering the last
                // (row, checked) in the view tag, so a same-row re-render animates the toggle
                val prev = cell.tag as? Pair<*, *>
                val sameRow = prev?.first == row.uid
                val visualChecked = if (sameRow) prev?.second == true else row.checked
                cell.setTextAndValueAndCheck(
                    row.text, row.subtitle, visualChecked, 0, true,
                    !InuConfig.M3_SECTIONS_STYLE.value,
                )
                cell.setDrawLine(false)
                if (sameRow && visualChecked != row.checked) cell.setChecked(row.checked)
                cell.tag = row.uid to row.checked
            }
        }
    }

    private fun buildButton(row: Row.Button): UItem =
        ButtonCellFactory.of(row.uid, row.text, row.value, row.subtitle, ButtonIcon(row.icon, engine), row.danger)

    private fun sliderCellFor(row: Row.Slider): SliderCell {
        val configKey = row.configKey()
        val cached = sliderCells[row.uid]
        if (cached != null && cached.configKey == configKey) {
            cached.cell.updateValue(row.value.toFloat())
            return cached.cell
        }
        val labels = row.labels
        val min = row.min.toFloat()
        val step = row.step.toFloat()
        val cell = SliderCell(
            context,
            min = min,
            max = row.max.toFloat(),
            defaultValue = (row.default ?: row.value).toFloat(),
            initialValue = row.value.toFloat(),
            step = step,
            title = row.text,
            format = { v ->
                labels?.getOrNull(Math.round((v - min) / step)) ?: trimNumber(v)
            },
            onChanged = {},
            // the cell outlives this render, but slots don't: re-resolve through the current
            // model at release time instead of capturing row.slot (stale after any re-render)
            onReleased = { v ->
                (rowById(row.uid) as? Row.Slider)?.let { dispatch(it.slot, trimNumber(v)) }
            },
        )
        sliderCells[row.uid] = CachedSlider(configKey, cell)
        return cell
    }

    private fun trimNumber(v: Float): String {
        val asLong = v.toLong()
        return if (v == asLong.toFloat()) asLong.toString() else v.toString()
    }

    private fun rowById(id: Int): Row? = model?.rows?.firstOrNull { it.uid == id }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (val row = rowById(item.id)) {
            is Row.Check -> dispatch(row.slot, (!row.checked).toString())
            is Row.Button -> dispatch(row.slot, "")
            is Row.Select -> showSelect(row, view)
            else -> {}
        }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean {
        val row = rowById(item.id) ?: return false
        if (row.secondarySlot == 0) return false
        dispatch(row.secondarySlot, "")
        return true
    }

    private fun showSelect(row: Row.Select, anchor: View) {
        if (row.dialog) {
            val dialog = RadioDialogBuilder(context, resourceProvider)
                .setTitle(row.text)
                .setItems(
                    row.options.map { RadioDialogBuilder.Item(it.text, it.subtitle) },
                    row.selected,
                ) { _, index ->
                    if (index != row.selected) dispatch(row.slot, index.toString())
                }
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create()
            showDialog(dialog)
        } else {
            RadioItemOptions.show(this, anchor, row.options.map { it.text }, row.selected) { index ->
                if (index != row.selected) dispatch(row.slot, index.toString())
            }
        }
    }

    private fun maybeAttachBottomButton(model: PageModel, rootView: View?) {
        val text = model.bottomText ?: return
        val existing = bottomButton
        if (existing != null) {
            existing.setText(text, false)
            return
        }
        if (rootView == null) return
        val button = ButtonWithCounterView(rootView.context, true, resourceProvider).setRound().apply {
            setText(text, false)
            setOnClickListener {
                val slot = this@PluginSettingsActivity.model?.bottomSlot ?: return@setOnClickListener
                dispatch(slot, "")
            }
        }
        bottomButton = button
        attachStickyButton(rootView, button)
    }

    companion object {
        private const val TAG = "InuPluginUi"
    }
}
