package desu.inugram.helpers.plugins.ui

import android.content.SharedPreferences
import desu.inugram.InuConfig
import org.json.JSONArray
import org.json.JSONObject

data class PluginActionSettings(
    val disabled: Set<String> = emptySet(),
    val pinned: Set<String> = emptySet(),
    val mainOrder: List<String> = emptyList(),
    val pluginOrder: List<String> = emptyList(),
)

class PluginActionSettingsConfig(key: String) : InuConfig.Item<PluginActionSettings>(key, PluginActionSettings()) {
    override val prefType = InuConfig.PrefType.STRING

    override fun read(prefs: SharedPreferences): PluginActionSettings {
        val source = prefs.getString(key, null) ?: return default
        return try {
            val json = JSONObject(source)
            PluginActionSettings(
                disabled = json.optJSONArray("disabled").strings().toSet(),
                pinned = json.optJSONArray("pinned").strings().toSet(),
                mainOrder = json.optJSONArray("mainOrder").strings(),
                pluginOrder = json.optJSONArray("pluginOrder").strings(),
            )
        } catch (_: Exception) {
            default
        }
    }

    override fun SharedPreferences.Editor.write() {
        putString(key, JSONObject().apply {
            put("disabled", JSONArray(value.disabled))
            put("pinned", JSONArray(value.pinned))
            put("mainOrder", JSONArray(value.mainOrder))
            put("pluginOrder", JSONArray(value.pluginOrder))
        }.toString())
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }.filter(String::isNotEmpty)
    }
}
