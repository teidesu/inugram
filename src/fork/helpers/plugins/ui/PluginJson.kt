package desu.inugram.helpers.plugins.ui

import org.json.JSONArray
import org.json.JSONObject

/** an absent key and an empty string mean the same thing on every option object rust builds */
internal fun JSONObject.text(key: String): String? = optString(key).takeIf { it.isNotEmpty() }

/**
 * text and the entities beside it, as a surface draws it. An entity can carry the whole of what a
 * key says - a formatted date writes its own text - so the pair is absent only when both halves are.
 */
internal fun JSONObject.formatted(key: String, prepare: (String) -> String = { it }): CharSequence? {
    val text = optString(key)
    val entities = optJSONArray("${key}Entities")
    if (text.isEmpty() && (entities == null || entities.length() == 0)) return null
    return PluginText.formatted(prepare(text), entities)
}

/** the string list shape rust sends: an absent array and an empty one are the same, and blanks never count */
internal fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it) }.filter(String::isNotEmpty)
}
