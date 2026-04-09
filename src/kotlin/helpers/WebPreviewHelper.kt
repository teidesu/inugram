package desu.inugram.helpers

import android.util.Log
import desu.inugram.InuConfig
import org.json.JSONArray
import org.json.JSONObject

object WebPreviewHelper {
    data class Replacement(val pattern: String, val replacement: String)

    val DEFAULT_REPLACEMENTS = listOf(
        Replacement("""(?:x|twitter)\.com/(.*)""", "fixupx.com/$1"),
        Replacement("""(?:www)?\.instagram\.com/(.*)""", "kkinstagram.com/$1"),
        Replacement("""(vm|vt|www)\.tiktok\.com/(.*)""", "$1.kktiktok.com/$2"),
        Replacement("""(?:www)?\.reddit\.com/(.*)""", "www.rxddit.com/$1"),
        Replacement("""bsky\.app/(.*)""", "fxbsky.app/$1"),
        Replacement("""www\.pixiv\.net/(.*)""", "www.phixiv.net/$1"),
    )

    fun load(): List<Replacement> {
        val json = InuConfig.WEB_PREVIEW_REPLACEMENTS.value
        if (json.isEmpty()) return DEFAULT_REPLACEMENTS
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Replacement(obj.getString("p"), obj.getString("r"))
            }
        } catch (_: Exception) {
            DEFAULT_REPLACEMENTS
        }
    }

    fun save(list: List<Replacement>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(JSONObject().apply {
                put("p", r.pattern)
                put("r", r.replacement)
            })
        }
        InuConfig.WEB_PREVIEW_REPLACEMENTS.value = arr.toString()
    }

    fun isDefault(): Boolean = InuConfig.WEB_PREVIEW_REPLACEMENTS.value.isEmpty()

    fun resetToDefault() {
        InuConfig.WEB_PREVIEW_REPLACEMENTS.value = ""
    }

    @JvmStatic
    fun applyReplacements(url: String): String {
        if (!InuConfig.WEB_PREVIEW_REPLACEMENTS_ENABLED.value) return url
        val replacements = load()
        for (r in replacements) {
            try {
                val regex = Regex("(?<=https?://|\\s|^)" + r.pattern)
                if (regex.containsMatchIn(url)) {
                    val newUrl = regex.replaceFirst(url, r.replacement)
                    Log.d("WebPreviewHelper", "replacing url: $url -> $newUrl")
                    return newUrl
                }
            } catch (_: Exception) {
                // skip invalid regexes
            }
        }
        Log.d("WebPreviewHelper", "not replacing url: $url")
        return url
    }
}
