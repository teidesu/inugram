package desu.inugram.helpers.font

import android.content.SharedPreferences
import desu.inugram.InuConfig
import desu.inugram.InuConfig.BoolItem
import desu.inugram.InuConfig.StringItem
import org.json.JSONArray
import org.json.JSONObject

object FontConfig {
    // ids used inside the serialized [FontMode] to mark the two non-custom modes
    internal const val SYSTEM_STACK_ID = "@system@"
    internal const val BUNDLED_STACK_ID = "@default@"

    /** App UI font selection: the bundled font, the device default, or a custom primary + fallbacks. */
    sealed class FontMode {
        object Bundled : FontMode()
        object System : FontMode()

        // fontId: a [Family] with an empty id is the legacy "first family" marker (resolved by FontHelper)
        data class Custom(val fontId: FontId, val fallbacks: List<FontId>) : FontMode()

        fun toJson(): String {
            val obj = JSONObject()
            when (this) {
                Bundled -> obj.put("id", BUNDLED_STACK_ID)
                System -> obj.put("id", SYSTEM_STACK_ID)
                is Custom -> {
                    obj.put("id", fontId.token())
                    obj.put("fallbacks", JSONArray(fallbacks.map { it.token() }))
                }
            }
            return obj.toString()
        }

        companion object {
            fun fromJson(json: String): FontMode? = try {
                val obj = JSONObject(json)
                when (val id = obj.getString("id")) {
                    SYSTEM_STACK_ID -> System
                    BUNDLED_STACK_ID -> Bundled
                    else -> Custom(FontId.parseWithLegacyCompat(id), obj.optJSONArray("fallbacks").toFontIds())
                }
            } catch (_: Exception) {
                null
            }

            private fun JSONArray?.toFontIds(): List<FontId> =
                if (this == null) emptyList() else List(length()) { FontId.parse(getString(it)) }
        }
    }

    class FontModeItem : InuConfig.Item<FontMode>("font_config", FontMode.Bundled, false) {
        override fun read(prefs: SharedPreferences): FontMode {
            prefs.getString(key, null)?.let { return FontMode.fromJson(it) ?: default }
            // migrate the legacy split keys (font_mode int + active_font_id + font_fallbacks), then the
            // even older use_system_font bool. Legacy keys are left in place — re-read harmlessly until
            // the next save writes font_config.
            return when {
                prefs.contains("font_mode") -> when (prefs.getInt("font_mode", 0)) {
                    1 -> FontMode.System
                    2 -> FontMode.Custom(
                        FontId.parseWithLegacyCompat(prefs.getString("active_font_id", "") ?: ""),
                        prefs.getString("font_fallbacks", null).parseFallbacks(),
                    )

                    else -> FontMode.Bundled
                }

                prefs.getBoolean("use_system_font", false) -> FontMode.System
                else -> default
            }
        }

        override fun SharedPreferences.Editor.write() {
            putString(key, value.toJson())
        }

        private fun String?.parseFallbacks(): List<FontId> = try {
            if (this == null) emptyList() else JSONArray(this).let { List(it.length()) { i -> FontId.parse(it.getString(i)) } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    val FONT = FontModeItem()

    // include device system fonts (e.g. Google Sans, Coming Soon) in the editor roster + app font list
    val FONT_INCLUDE_SYSTEM = BoolItem("font_include_system", false)

    // roster token of the font used for monospace blocks (inline code + pre); "" = stock monospace
    val MONO_FONT = StringItem("mono_font", "")

    // crutch for [InuConfig.load] to populate _items correctly. check if needed.
    fun register() = Unit
}
