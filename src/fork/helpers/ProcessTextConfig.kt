package desu.inugram.helpers

import android.content.SharedPreferences
import desu.inugram.InuConfig
import org.json.JSONArray
import org.json.JSONObject

class ProcessTextConfig(key: String) : InuConfig.Item<List<ProcessTextConfig.Entry>?>(key, null) {
    data class Entry(val key: String, val enabled: Boolean)

    override val prefType = InuConfig.PrefType.STRING

    override fun read(prefs: SharedPreferences): List<Entry>? {
        val json = prefs.getString(key, null) ?: return null
        return try {
            val arr = JSONArray(json)
            val seen = HashSet<String>()
            (0 until arr.length()).mapNotNull {
                val obj = arr.getJSONObject(it)
                val itemKey = obj.getString("k")
                if (seen.add(itemKey)) Entry(itemKey, obj.optBoolean("e", true)) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun SharedPreferences.Editor.write() {
        val entries = value ?: run {
            remove(key)
            return
        }
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().apply {
                put("k", e.key)
                put("e", e.enabled)
            })
        }
        putString(key, arr.toString())
    }
}
