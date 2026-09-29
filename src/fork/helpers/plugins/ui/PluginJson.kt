package desu.inugram.helpers.plugins.ui

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.text(key: String): String? = optString(key).takeIf { it.isNotEmpty() }

/** a formatted date writes its own text, so the pair is absent only when both halves are */
internal fun JSONObject.formatted(key: String, prepare: (String) -> String = { it }): CharSequence? {
    val text = optString(key)
    val entities = optJSONArray("${key}Entities")
    if (text.isEmpty() && (entities == null || entities.length() == 0)) return null
    return PluginText.formatted(prepare(text), entities)
}

internal fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it) }.filter(String::isNotEmpty)
}
