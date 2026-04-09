package desu.inugram.helpers

import android.content.SharedPreferences
import desu.inugram.InuConfig
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.R

class FormattingPopupConfig(key: String) : InuConfig.Item<List<FormattingPopupConfig.Entry>>(key, DEFAULT) {
    enum class Item(val key: String, val labelRes: Int, val iconRes: Int) {
        SELECT_ALL("select_all", R.string.SelectAll, R.drawable.inu_tabler_select_all),
        COPY("copy", R.string.Copy, R.drawable.inu_tabler_copy),
        CUT("cut", R.string.InuCut, R.drawable.inu_tabler_scissors),
        PASTE("paste", R.string.Paste, R.drawable.inu_tabler_clipboard),
        DIVIDER("divider", R.string.InuFormattingPopupDivider, 0),
        BOLD("bold", R.string.Bold, R.drawable.inu_tabler_bold),
        ITALIC("italic", R.string.Italic, R.drawable.inu_tabler_italic),
        UNDERLINE("underline", R.string.Underline, R.drawable.inu_tabler_underline),
        STRIKE("strike", R.string.Strike, R.drawable.inu_tabler_strikethrough),
        MONO("mono", R.string.Mono, R.drawable.inu_tabler_code),
        SPOILER("spoiler", R.string.Spoiler, R.drawable.inu_tabler_background),
        QUOTE("quote", R.string.Quote, R.drawable.inu_tabler_quote),
        LINK("link", R.string.CreateLink, R.drawable.inu_tabler_link),
        CLEAR("clear", R.string.Regular, R.drawable.inu_tabler_clear_formatting);
    }

    data class Entry(val item: Item, val enabled: Boolean)

    override fun read(prefs: SharedPreferences): List<Entry> {
        val json = prefs.getString(key, "") ?: ""
        if (json.isEmpty()) return DEFAULT
        return try {
            val arr = JSONArray(json)
            val seen = HashSet<Item>()
            val out = ArrayList<Entry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val itemKey = obj.getString("k")
                val item = Item.entries.firstOrNull { it.key == itemKey } ?: continue
                if (!seen.add(item)) continue
                out.add(Entry(item, obj.optBoolean("e", true)))
            }
            for (it in Item.entries) {
                if (!seen.contains(it)) out.add(Entry(it, true))
            }
            out
        } catch (_: Exception) {
            DEFAULT
        }
    }

    override fun SharedPreferences.Editor.write() {
        val arr = JSONArray()
        for (e in value) {
            arr.put(JSONObject().apply {
                put("k", e.item.key)
                put("e", e.enabled)
            })
        }
        putString(key, arr.toString())
    }

    fun resetToDefault() {
        value = DEFAULT
    }

    companion object {
        val DEFAULT: List<Entry> = Item.entries.map { Entry(it, true) }
    }
}
