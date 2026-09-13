package desu.inugram.helpers.plugins.telegram

import desu.inugram.core.plugins.PluginWire.refuse
import org.json.JSONObject

/** the JSON object `reads.js`/`writes.js` build for one call; an absent or null key is its zero */
internal open class JsonArgs(val json: JSONObject) {
    fun int(key: String): Int = if (json.isNull(key)) 0 else {
        json.optString(key).toIntOrNull() ?: refuse("invalid-argument", "$key: expected a 32-bit integer")
    }

    fun flag(key: String): Boolean = if (json.isNull(key)) false else {
        json.get(key) as? Boolean ?: refuse("invalid-argument", "$key: expected a boolean")
    }

    fun optedIn(key: String): Boolean = if (json.isNull(key)) true else flag(key)

    fun ints(key: String): List<Int> {
        val array = json.optJSONArray(key) ?: return emptyList()
        return List(array.length()) { index ->
            array.optString(index).toIntOrNull() ?: refuse("invalid-argument", "$key: expected 32-bit integers")
        }
    }

    fun strings(key: String): List<String>? {
        val array = json.optJSONArray(key) ?: return null
        return List(array.length()) { index -> array.optString(index) }
    }
}
