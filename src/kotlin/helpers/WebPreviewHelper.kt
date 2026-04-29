package desu.inugram.helpers

import android.util.Log
import desu.inugram.InuConfig
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.tgnet.TLRPC

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
    fun shouldShowAllLines(webPage: TLRPC.WebPage): Boolean {
        // stock only checks for site_name.lower() == "twitter", which is the old name that no longer applies
        // fix + expand it a bit
        val siteName = webPage.site_name.lowercase()
        if (siteName == "twitter" || siteName == "x (formerly twitter)") return true
        if (
            siteName == "bluesky social"
            || siteName == "witchsky"
            || siteName == "blacksky"
            || siteName == "deer.social"
            || siteName == "anartia"
        ) return true

        if (webPage.cached_page == null) {
            // also apply to fixupx and friends without instant view (i.e. non-threads)
            return siteName.contains("fixupx")
                || siteName.contains("fxtwitter")
                || siteName.contains("stupidpenisx")
                || siteName.contains("girlcockx")
        }

        return false;
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
