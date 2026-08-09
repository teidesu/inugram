package desu.inugram.helpers.chat

import android.content.SharedPreferences
import desu.inugram.InuConfig
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble.VisibleReaction

object PinnedReactionsHelper {
    const val MAX_PINS = 20

    data class Pin(val emoji: String?, val docId: Long) {
        fun toVisibleReaction(): VisibleReaction = if (docId != 0L)
            VisibleReaction.fromCustomEmoji(docId)
        else
            VisibleReaction.fromEmojicon(emoji ?: "")
    }

    class ConfigItem(key: String) : InuConfig.Item<MutableList<Pin>>(key, mutableListOf()) {
        override val prefType = InuConfig.PrefType.STRING

        override fun read(prefs: SharedPreferences): MutableList<Pin> {
            val json = prefs.getString(key, "") ?: ""
            if (json.isEmpty()) return mutableListOf()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).mapTo(mutableListOf()) { i ->
                    val obj = arr.getJSONObject(i)
                    Pin(
                        emoji = if (obj.has("e")) obj.getString("e") else null,
                        docId = obj.optLong("d", 0L),
                    )
                }
            } catch (_: Exception) {
                mutableListOf()
            }
        }

        override fun SharedPreferences.Editor.write() {
            val arr = JSONArray()
            for (p in value) {
                arr.put(JSONObject().apply {
                    if (p.docId != 0L) put("d", p.docId) else put("e", p.emoji ?: "")
                })
            }
            putString(key, arr.toString())
        }
    }

    @JvmStatic
    fun reorder(visible: MutableList<VisibleReaction>, hasStar: Boolean, allReactionsAvailable: Boolean) {
        if (!InuConfig.PINNED_REACTIONS_ENABLED.value) return
        val pins = InuConfig.PINNED_REACTIONS.value
        if (pins.isEmpty()) return

        var cursor = if (hasStar) 1 else 0
        for (pin in pins) {
            val target = pin.toVisibleReaction()
            val idx = visible.indexOf(target)
            when {
                idx == cursor -> {}
                idx >= 0 -> visible.add(cursor, visible.removeAt(idx))
                allReactionsAvailable -> visible.add(cursor, target)
                else -> continue
            }
            cursor++
        }
    }
}
