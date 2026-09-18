package desu.inugram.helpers.plugins.ui

import org.json.JSONArray
import org.json.JSONObject

/** an absent key and an empty string mean the same thing on every option object rust builds */
internal fun JSONObject.text(key: String): String? = optString(key).takeIf { it.isNotEmpty() }

/** the string list shape rust sends: an absent array and an empty one are the same, and blanks never count */
internal fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it) }.filter(String::isNotEmpty)
}
