package desu.inugram.helpers.menu

import android.content.SharedPreferences
import desu.inugram.InuConfig
import desu.inugram.helpers.chat.ChatActionsHelper
import desu.inugram.helpers.chat.ChatHelper
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.R
import org.telegram.ui.ChatActivity

interface MenuOrderItem {
    val key: String
    val labelRes: Int
    val iconRes: Int
    val ordinal: Int

    /** synthetic "smart slot" that lives only in the bottom row and resolves to an action at render */
    val isSlot: Boolean get() = false
}

data class MenuOrderEntry<I : MenuOrderItem>(val item: I, val enabled: Boolean, val bottom: Boolean = false)

abstract class MenuOrderConfig<I : MenuOrderItem>(
    key: String,
    private val allItems: List<I>,
    private val offByDefault: Set<I>,
) : InuConfig.Item<List<MenuOrderEntry<I>>>(key, allItems.map { MenuOrderEntry(it, it !in offByDefault, it.isSlot) }) {

    protected abstract fun itemByKey(key: String): I?

    override fun read(prefs: SharedPreferences): List<MenuOrderEntry<I>> {
        val json = prefs.getString(this.key, "") ?: ""
        if (json.isEmpty()) return default
        return try {
            val arr = JSONArray(json)
            val seen = HashSet<I>()
            val out = ArrayList<MenuOrderEntry<I>>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val item = itemByKey(obj.getString("k")) ?: continue
                if (!seen.add(item)) continue
                out.add(MenuOrderEntry(item, obj.optBoolean("e", true), obj.optBoolean("b", false)))
            }
            for (it in allItems) {
                if (!seen.contains(it)) out.add(MenuOrderEntry(it, it !in offByDefault, it.isSlot))
            }
            out
        } catch (_: Exception) {
            default
        }
    }

    override fun SharedPreferences.Editor.write() {
        val arr = JSONArray()
        for (e in value) {
            arr.put(JSONObject().apply {
                put("k", e.item.key)
                put("e", e.enabled)
                if (e.bottom) put("b", true)
            })
        }
        putString(key, arr.toString())
    }

    fun resetToDefault() {
        value = default
    }
}

/**
 * Permutes `rows` to match the saved order in `entries`. Each row is
 * classified to an item via [classify]; unclassified rows anchor to the
 * preceding classified row (or the head if none). Disabled items are dropped.
 */
inline fun <Row, I : MenuOrderItem> reorderByMenu(
    rows: List<Row>,
    entries: List<MenuOrderEntry<I>>,
    classify: (Row) -> I?,
): ArrayList<Row> {
    val byItem = HashMap<I, ArrayList<Row>>()
    val unknownAfter = HashMap<I?, ArrayList<Row>>()
    var lastKnown: I? = null
    for (row in rows) {
        val cfgItem = classify(row)
        if (cfgItem != null) {
            byItem.getOrPut(cfgItem) { ArrayList() }.add(row)
            lastKnown = cfgItem
        } else {
            unknownAfter.getOrPut(lastKnown) { ArrayList() }.add(row)
        }
    }

    val ordered = ArrayList<Row>(rows.size)
    unknownAfter.remove(null)?.let { ordered.addAll(it) }
    for (entry in entries) {
        val rs = byItem.remove(entry.item)
        if (rs != null && entry.enabled) ordered.addAll(rs)
        unknownAfter.remove(entry.item)?.let { ordered.addAll(it) }
    }
    // items absent from saved order (e.g. enum extended after save) — append at end
    for ((item, rs) in byItem) {
        ordered.addAll(rs)
        unknownAfter.remove(item)?.let { ordered.addAll(it) }
    }
    // unknowns anchored to a disabled-and-missing item (shouldn't happen, but safe): append
    for ((_, rs) in unknownAfter) ordered.addAll(rs)

    return ordered
}

class ChatMenuConfig(key: String) : MenuOrderConfig<ChatMenuConfig.Item>(key, Item.entries, OFF_BY_DEFAULT) {
    enum class Item(
        override val key: String,
        val ids: List<Int>,
        override val labelRes: Int,
        override val iconRes: Int,
    ) : MenuOrderItem {
        VIEW_AS_TOPICS("view_as_topics", listOf(ChatActivity.view_as_topics), R.string.TopicViewAsTopics, R.drawable.msg_topics),
        OPEN_DIRECT("open_direct", listOf(ChatActivity.open_direct), R.string.ChannelOpenDirect, R.drawable.msg_markunread),
        CALL("call", listOf(ChatActivity.call), R.string.Call, R.drawable.msg_callback),
        VIDEO_CALL("video_call", listOf(ChatActivity.video_call), R.string.VideoCall, R.drawable.msg_videocall),
        SEARCH("search", listOf(ChatActivity.search), R.string.Search, R.drawable.msg_search),
        TRANSLATE("translate", listOf(ChatActivity.translate), R.string.TranslateMessage, R.drawable.msg_translate),
        REPORT("report", listOf(ChatActivity.report), R.string.ReportChat, R.drawable.msg_report),
        ADD_CONTACT("add_contact", listOf(ChatActivity.share_contact), R.string.AddToContacts, R.drawable.msg_addcontact),
        SET_TIMER("set_timer", listOf(ChatActivity.chat_enc_timer), R.string.SetTimer, R.drawable.msg_autodelete),
        CHANGE_COLORS("change_colors", listOf(ChatActivity.change_colors), R.string.SetWallpapers, R.drawable.msg_background),
        ADD_SHORTCUT("add_shortcut", listOf(ChatActivity.add_shortcut), R.string.AddShortcut, R.drawable.msg_home),
        CLEAR_HISTORY("clear_history", listOf(ChatActivity.clear_history), R.string.ClearHistory, R.drawable.msg_clear),
        DELETE_OWN_MESSAGES(
            "delete_own_messages",
            listOf(ChatActionsHelper.ACTION_DELETE_OWN_MESSAGES),
            R.string.InuDeleteOwnMessages,
            R.drawable.msg_delete
        ),
        DELETE_CHAT("delete_chat", listOf(ChatActivity.delete_chat), R.string.DeleteChatUser, R.drawable.msg_delete),
        BOT_SETTINGS("bot_settings", listOf(ChatActivity.bot_settings), R.string.InuBotSettings, R.drawable.msg_settings_old),
        BOT_HELP("bot_help", listOf(ChatActivity.bot_help), R.string.InuBotHelp, R.drawable.msg_help),
        OPEN_FORUM("open_forum", listOf(ChatActivity.open_forum), R.string.OpenAllTopics, R.drawable.msg_discussion),
        CLOSE_TOPIC("close_topic", listOf(ChatActivity.topic_close), R.string.CloseTopic, R.drawable.msg_topic_close),
        SHOW_PINNED_PANEL("show_pinned_panel", listOf(ChatActionsHelper.ACTION_SHOW_PINNED_PANEL), R.string.InuShowPinnedPanel, R.drawable.msg_pin),
        RECENT_ACTIONS("recent_actions", listOf(ChatActionsHelper.ACTION_RECENT_ACTIONS), R.string.EventLog, R.drawable.msg_log),
        GO_TO_BEGINNING("go_to_beginning", listOf(ChatActionsHelper.ACTION_GO_TO_BEGINNING), R.string.InuJumpToBeginning, R.drawable.msg_go_up),
        GO_TO_MESSAGE("go_to_message", listOf(ChatActionsHelper.ACTION_GO_TO_MESSAGE), R.string.InuGoToMessage, R.drawable.msg_message);

        companion object {
            private val byId: Map<Int, Item> by lazy {
                val map = HashMap<Int, Item>()
                for (e in Item.entries) for (id in e.ids) map[id] = e
                map
            }

            private val byKey: Map<String, Item> by lazy { Item.entries.associateBy { it.key } }

            fun forId(id: Int): Item? = byId[id]
            fun forKey(k: String): Item? = byKey[k]
        }
    }

    override fun itemByKey(key: String): Item? = Item.forKey(key)

    companion object {
        private val OFF_BY_DEFAULT = setOf(Item.RECENT_ACTIONS, Item.GO_TO_BEGINNING, Item.GO_TO_MESSAGE, Item.DELETE_OWN_MESSAGES)
    }
}

class MessageMenuConfig(key: String) : MenuOrderConfig<MessageMenuConfig.Item>(key, Item.entries, OFF_BY_DEFAULT) {
    enum class Item(
        override val key: String,
        val optionIds: List<Int>,
        override val labelRes: Int,
        override val iconRes: Int,
        override val isSlot: Boolean = false,
    ) : MenuOrderItem {
        REPLY("reply", listOf(ChatActivity.OPTION_REPLY), R.string.Reply, R.drawable.menu_reply),
        REPLY_IN("reply_in", listOf(ChatHelper.OPTION_REPLY_IN), R.string.InuReplyIn, R.drawable.menu_reply),
        ADD_TO_STICKERS(
            "add_to_stickers",
            listOf(ChatActivity.OPTION_ADD_TO_STICKERS_OR_MASKS, ChatActivity.OPTION_ADD_STICKER_TO_FAVORITES, ChatActivity.OPTION_ADD_TO_GIFS),
            R.string.InuAddToStickersGifs,
            R.drawable.msg_sticker
        ),
        COPY("copy", listOf(ChatActivity.OPTION_COPY, ChatHelper.OPTION_COPY_MEDIA), R.string.Copy, R.drawable.msg_copy),
        COPY_LINK("copy_link", listOf(ChatActivity.OPTION_COPY_LINK), R.string.CopyLink, R.drawable.msg_link),
        SAVE_TO_GALLERY(
            "save_to_gallery",
            listOf(ChatActivity.OPTION_SAVE_TO_GALLERY, ChatActivity.OPTION_SAVE_TO_GALLERY2),
            R.string.SaveToGallery,
            R.drawable.msg_gallery
        ),
        SAVE_TO_DOWNLOADS(
            "save_to_downloads",
            listOf(ChatActivity.OPTION_SAVE_TO_DOWNLOADS_OR_MUSIC),
            R.string.SaveToDownloads,
            R.drawable.msg_download
        ),
        FORWARD("forward", listOf(ChatActivity.OPTION_FORWARD), R.string.Forward, R.drawable.msg_forward),
        FORWARD_NO_QUOTE("forward_no_quote", listOf(ChatHelper.OPTION_FORWARD_NO_QUOTE), R.string.InuForwardNoQuote, R.drawable.msg_forward),
        SAVE("save", listOf(ChatHelper.OPTION_SAVE), R.string.InuSaveToSavedMessages, R.drawable.msg_saved),
        PIN("pin", listOf(ChatActivity.OPTION_PIN, ChatActivity.OPTION_UNPIN), R.string.PinMessage, R.drawable.msg_pin),
        TRANSLATE(
            "translate",
            listOf(ChatActivity.OPTION_TRANSLATE, ChatHelper.OPTION_TRANSLATE_REVERT),
            R.string.TranslateMessage,
            R.drawable.msg_translate
        ),
        SUMMARIZE("summarize", listOf(ChatHelper.OPTION_SUMMARIZE), R.string.InuSummarize, R.drawable.magic_stick_solar),
        EDIT("edit", listOf(ChatActivity.OPTION_EDIT), R.string.Edit, R.drawable.msg_edit),
        REPORT("report", listOf(ChatActivity.OPTION_REPORT_CHAT), R.string.ReportChat, R.drawable.msg_report),
        SHARE("share", listOf(ChatActivity.OPTION_SHARE), R.string.ShareFile, R.drawable.msg_share),
        STATISTICS("statistics", listOf(ChatActivity.OPTION_STATISTICS), R.string.Statistics, R.drawable.msg_stats),
        SHOW_IN_CHAT("show_in_chat", listOf(ChatHelper.OPTION_SHOW_IN_CHAT), R.string.InuShowInChat, R.drawable.msg_openin),
        REMOVE_FROM_CACHE("remove_from_cache", listOf(ChatHelper.OPTION_REMOVE_FROM_CACHE), R.string.InuRemoveFromCache, R.drawable.msg_clear),
        DELETE("delete", listOf(ChatActivity.OPTION_DELETE), R.string.Delete, R.drawable.msg_delete),
        DETAILS("details", listOf(ChatHelper.OPTION_DETAILS), R.string.InuMessageDetails, R.drawable.msg_info),

        // bottom-row "smart slots" — no real option id; resolved via fallback chains at render
        // (see ChatHelper.resolveSlot). Default to the bottom row, NagramX-style.
        SLOT_REPLY("slot_reply", emptyList(), R.string.Reply, R.drawable.menu_reply, true),
        SLOT_COPY("slot_copy", emptyList(), R.string.Copy, R.drawable.msg_copy, true),
        SLOT_DELETE("slot_delete", emptyList(), R.string.InuMenuSlotDelete, R.drawable.msg_delete, true),
        SLOT_EDIT_FORWARD("slot_edit_forward", emptyList(), R.string.InuMenuSlotEditForward, R.drawable.msg_edit, true);

        companion object {
            private val byOption: Map<Int, Item> by lazy {
                val map = HashMap<Int, Item>()
                for (e in Item.entries) for (id in e.optionIds) map[id] = e
                map
            }

            private val byKey: Map<String, Item> by lazy { Item.entries.associateBy { it.key } }

            fun forOption(optionId: Int): Item? = byOption[optionId]
            fun forKey(key: String): Item? = byKey[key]
        }
    }

    override fun itemByKey(key: String): Item? = Item.forKey(key)

    companion object {
        private val OFF_BY_DEFAULT = setOf(Item.REPLY_IN, Item.DETAILS, Item.FORWARD_NO_QUOTE, Item.SUMMARIZE, Item.REMOVE_FROM_CACHE)
    }
}
