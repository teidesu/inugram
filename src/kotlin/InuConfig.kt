package desu.inugram

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import desu.inugram.helpers.FormattingPopupConfig
import desu.inugram.helpers.PinnedReactionsHelper
import org.telegram.messenger.BuildConfig

object InuConfig {
    private const val PREFS_NAME = "inugram"

    lateinit var prefs: SharedPreferences
    private val items = mutableListOf<Item<*>>()

    fun load(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        for (item in items) item.load(prefs)
    }

    abstract class Item<T>(val key: String, val default: T) {
        private var currentValue: T = default

        open var value: T
            get() = currentValue
            set(v) {
                currentValue = v
                save()
            }

        init {
            items.add(this)
        }

        fun load(prefs: SharedPreferences) {
            currentValue = read(prefs)
        }

        fun save() {
            prefs.edit { write() }
        }

        protected abstract fun read(prefs: SharedPreferences): T
        protected abstract fun SharedPreferences.Editor.write()
    }

    class BoolItem(key: String, default: Boolean) : Item<Boolean>(key, default) {
        override fun read(prefs: SharedPreferences): Boolean = prefs.getBoolean(key, default)
        override fun SharedPreferences.Editor.write() {
            putBoolean(key, value)
        }

        fun toggle(): Boolean {
            val new = !this.value
            this.value = new
            return new
        }
    }

    open class IntItem(key: String, default: Int) : Item<Int>(key, default) {
        override fun read(prefs: SharedPreferences): Int = prefs.getInt(key, default)
        override fun SharedPreferences.Editor.write() {
            putInt(key, value)
        }
    }

    class FloatItem(key: String, default: Float) : Item<Float>(key, default) {
        override fun read(prefs: SharedPreferences): Float = prefs.getFloat(key, default)
        override fun SharedPreferences.Editor.write() {
            putFloat(key, value)
        }
    }

    class StringItem(key: String, default: String) : Item<String>(key, default) {
        override fun read(prefs: SharedPreferences): String = prefs.getString(key, default) ?: default
        override fun SharedPreferences.Editor.write() {
            putString(key, value)
        }
    }

    class LongItem(key: String, default: Long) : Item<Long>(key, default) {
        override fun read(prefs: SharedPreferences): Long = prefs.getLong(key, default)
        override fun SharedPreferences.Editor.write() {
            putLong(key, value)
        }
    }

    // visible in ui
    @JvmField
    val HIDE_STORIES = BoolItem("hide_stories", false)

    @JvmField
    val SHOW_SECONDS = BoolItem("show_seconds", false)

    @JvmField
    val DISABLE_ROUNDING = BoolItem("disable_rounding", false)

    @JvmField
    val DISABLE_PREDICTIVE_BACK = BoolItem("disable_predictive_back", true)

    @JvmField
    val DISABLE_INSTANT_CAMERA = BoolItem("disable_instant_camera", true)

    @JvmField
    val SHOW_ALL_RECENT_STICKERS = BoolItem("show_all_recent_stickers", true)

    @JvmField
    val HIDE_TRENDING_STICKERS = BoolItem("hide_trending_stickers", true)

    @JvmField
    val BOTTOM_TABS_HIDE = BoolItem("bottom_tabs_hide", false)

    @JvmField
    val BOTTOM_TABS_HIDE_CONTACTS = BoolItem("bottom_tabs_hide_contacts", false)

    @JvmField
    val BOTTOM_TABS_COMPACT_MODE = BoolItem("bottom_tabs_hide_compact_mode", false)

    @JvmField
    val DIALOGS_FAB_MAIN_ACTION = IntItem("dialogs_fab_main_action", 1)

    @JvmField
    val DIALOGS_FAB_SECONDARY_ACTION = IntItem("dialogs_fab_secondary_action", 2)

    @JvmField
    val DIALOGS_FAB_HIDE_ON_SCROLL = BoolItem("dialogs_fab_hide_on_scroll", true)

    @JvmField
    val DIALOGS_FAB_OFFSET_FOR_BOTTOM_BAR = BoolItem("dialogs_fab_offset_for_bottom_bar", true)

    @JvmField
    val DIALOGS_FAB_LEFT_SIDE = BoolItem("dialogs_fab_left_side", false)

    @JvmField
    val HIDE_KEYBOARD_ON_SCROLL = BoolItem("hide_keyboard_on_scroll", true)

    @JvmField
    val DISABLE_PULL_TO_NEXT = BoolItem("disable_pull_to_next", true)

    @JvmField
    val DISABLE_SENSITIVE = BoolItem("disable_sensitive", false)

    @JvmField
    val DISABLE_CHAT_BACKGROUNDS = BoolItem("disable_chat_backgrounds", false)

    @JvmField
    val DISABLE_CHAT_THEMES = BoolItem("disable_chat_themes", false)

    @JvmField
    val DISABLE_BG_PARALLAX = BoolItem("disable_bg_parallax", true)

    @JvmField
    val DISABLE_SWIPE_TO_UNARCHIVE = BoolItem("disable_swipe_to_unarchive", true)

    @JvmField
    val CHAT_ALWAYS_SHOW_DOWN = BoolItem("chat_always_show_down", true)

    @JvmField
    val CHAT_REMEMBER_ALL_REPLIES = BoolItem("chat_remember_all_replies", true)

    @JvmField
    val HIDE_BOTTOM_BAR_JOINED = BoolItem("hide_bottom_bar_joined", false)

    @JvmField
    val HIDE_BOTTOM_BAR_NON_JOINED = BoolItem("hide_bottom_bar_non_joined", false)

    @JvmField
    val HIDE_BOTTOM_BAR_REPLIES = BoolItem("hide_bottom_bar_replies", false)

    @JvmField
    val HIDE_BOT_SLASH_GROUPS = BoolItem("hide_bot_slash_groups", true)

    @JvmField
    val HIDE_BOT_SLASH_BOTS = BoolItem("hide_bot_slash_bots", false)

    @JvmField
    val HIDE_BOT_WEBVIEW_INPUT = BoolItem("hide_bot_webview_input", false)

    @JvmField
    val HIDE_BOT_WEBVIEW_DIALOGS = BoolItem("hide_bot_webview_dialogs", true)

    @JvmField
    val HIDE_AI_EDITOR = BoolItem("hide_ai_editor", false)

    @JvmField
    val HIDE_MESSAGE_SUMMARY = BoolItem("hide_message_summary", false)

    @JvmField
    val HIDE_IV_SUMMARY = BoolItem("hide_iv_summary", false)

    @JvmField
    val HIDE_REPOST_TO_STORY = BoolItem("hide_repost_to_story", true)

    @JvmField
    val HIDE_PAID_REACTION_UPSELL = BoolItem("hide_paid_reaction_upsell", true)

    @JvmField
    val OPT_IN_MOTION_PHOTOS = BoolItem("opt_in_motion_photos", true)

    @JvmField
    val HIDE_REACTIONS_ENTRY = BoolItem("hide_reactions_entry", false)

    @JvmField
    val HIDE_SUGGESTION_BIRTHDAY_SETUP = BoolItem("hide_suggestion_birthday_setup", false)

    @JvmField
    val HIDE_SUGGESTION_BIRTHDAY_CONTACTS = BoolItem("hide_suggestion_birthday_contacts", false)

    @JvmField
    val HIDE_SUGGESTION_PASSWORD = BoolItem("hide_suggestion_password", false)

    @JvmField
    val HIDE_SUGGESTION_PHONE = BoolItem("hide_suggestion_phone", false)

    @JvmField
    val HIDE_SUGGESTION_PREMIUM = BoolItem("hide_suggestion_premium", true)

    @JvmField
    val HIDE_SUGGESTION_CUSTOM = BoolItem("hide_suggestion_custom", false)

    @JvmField
    val DELETE_FOR_BOTH_MESSAGES = BoolItem("delete_for_both_messages", true)

    @JvmField
    val DELETE_FOR_BOTH_DMS = BoolItem("delete_for_both_dms", false)

    @JvmField
    val DELETE_FOR_BOTH_GROUPS = BoolItem("delete_for_both_groups", false)

    @JvmField
    val DOUBLE_TAP_ACTION_INCOMING = IntItem("double_tap_action_incoming", 1)

    @JvmField
    val DOUBLE_TAP_ACTION_OUTGOING = IntItem("double_tap_action_outgoing", 1)

    @JvmField
    val DOUBLE_TAP_DELAY = IntItem("double_tap_delay", 220)

    @JvmField
    val STICKER_SIZE = FloatItem("sticker_size", 14.0f)

    @JvmField
    val NO_STICKER_EXTRA_PADDING = BoolItem("no_sticker_extra_padding", true)

    class FoldersDisplayModeItem : IntItem("folders_display_mode", TITLES) {
        companion object {
            const val TITLES = 1
            const val TITLES_AND_ICONS = 2
            const val ICONS_ONLY = 3
        }
    }

    @JvmField
    val FOLDERS_DISPLAY_MODE = FoldersDisplayModeItem()

    class FoldersUnreadCounterModeItem : IntItem("folders_unread_counter_mode", REGULAR) {
        companion object {
            const val HIDE = 0
            const val REGULAR = 1
            const val EXCLUDE_MUTED = 2
            const val EXCLUDE_MUTED_NON_DMS = 3
        }
    }

    @JvmField
    val FOLDERS_UNREAD_COUNTER_MODE = FoldersUnreadCounterModeItem()

    class StickerTimeModeItem : IntItem("sticker_time_mode", SHOW) {
        companion object {
            const val SHOW = 1;
            const val HIDE_TIME = 2;
            const val HIDE_INCOMING = 3;
            const val HIDE_FULL = 4;
        }

        fun isHideTime(): Boolean = value == HIDE_TIME
        fun isHideIncoming(): Boolean = value == HIDE_INCOMING
        fun isHideFull(): Boolean = value == HIDE_FULL
    }

    @JvmField
    val STICKER_TIME_MODE = StickerTimeModeItem()

    @JvmField
    val CALL_CONFIRMATION = BoolItem("call_confirmation", true)

    class ProfileIdModeItem : IntItem("profile_id_mode", BOT_API_ID) {
        companion object {
            const val OFF = 0
            const val TELEGRAM_ID = 1
            const val BOT_API_ID = 2
        }
    }

    @JvmField
    val PROFILE_ID_MODE = ProfileIdModeItem()

    @JvmField
    val DISABLE_CHAT_BUBBLES = BoolItem("disable_chat_bubbles", true)

    @JvmField
    val WEB_PREVIEW_REPLACEMENTS_ENABLED = BoolItem("web_preview_replacements_enabled", true)

    @JvmField
    val WEB_PREVIEW_REPLACEMENTS = StringItem("web_preview_replacements", "")

    @JvmField
    val DISABLE_INTRO_STICKER = BoolItem("disable_intro_sticker", true)

    @JvmField
    val DISABLE_DRAFT_UPLOAD = BoolItem("disable_draft_upload", false)

    @JvmField
    val ROUND_DEFAULT_CAMERA = IntItem("round_default_camera", 1) // 1=Front, 2=Rear, 3=Ask

    @JvmField
    val NON_ISLAND_TAB_BARS = BoolItem("non_island_tab_bars", false)

    @JvmField
    val NON_ISLAND_GLOBAL_SEARCH = BoolItem("non_island_global_search", false)

    @JvmField
    val NON_ISLAND_CHAT_ELEMENTS = BoolItem("non_island_chat_elements", false)

    @JvmField
    val HIDE_FADE_VIEW = BoolItem("hide_fade_view", false)

    @JvmField
    val SIMPLE_ATTACH_POPUP_ANIMATION = BoolItem("simple_attach_popup_animation", false)

    @JvmField
    val CHAT_VOICE_IN_ATTACH = BoolItem("chat_voice_in_attach", false)

    @JvmField
    val CHAT_VIEWS_BOTTOM = BoolItem("chat_views_bottom", false)

    @JvmField
    val DISABLE_CHAT_TITLE_PHONE = BoolItem("disable_chat_title_phone", true)

    @JvmField
    val HIDE_MY_PHONE_NUMBER = BoolItem("hide_my_phone_number", true)

    @JvmField
    val REACTIONS_IN_ROW = IntItem("reactions_in_row", 8)

    @JvmField
    val CHAT_INPUT_MAX_LINES = IntItem("chat_input_max_lines", 8)

    @JvmField
    val REACTION_BAR_BELOW = BoolItem("reaction_bar_below", false)

    @JvmField
    val PINNED_REACTIONS_ENABLED = BoolItem("pinned_reactions_enabled", false)

    @JvmField
    val PINNED_REACTIONS = PinnedReactionsHelper.ConfigItem("pinned_reactions")

    @JvmField
    val OLD_MENTION_INDICATOR = BoolItem("old_mention_indicator", true)

    @JvmField
    val DISABLE_CHAT_PREVIEW_EXPAND = BoolItem("disable_chat_preview_expand", true)

    @JvmField
    val FORMATTING_POPUP = BoolItem("formatting_popup", true)

    class TextClassifierModeItem : IntItem("text_classifier_mode", IMPROVED) {
        companion object {
            const val NATIVE = 1
            const val IMPROVED = 2
            const val OFF = 3
        }
    }

    @JvmField
    val TEXT_CLASSIFIER_MODE = TextClassifierModeItem()

    @JvmField
    val FORMATTING_POPUP_ITEMS = FormattingPopupConfig("formatting_popup_items")

    // internal state
    @JvmField
    val VOICE_HINT_SHOWN = BoolItem("voice_hint_shown", false)

    @JvmField
    val MINIMIZE_STICKERS_CREATOR = BoolItem("minimize_stickers_creator", true)

    @JvmField
    val ANIMATION_SPEED = FloatItem("animation_speed", 1.0f)

    class UpdateChannelItem : IntItem("update_channel", STABLE) {
        companion object {
            const val DISABLED = 0
            const val STABLE = 1
            const val CANARY = 2
        }

        override fun read(prefs: SharedPreferences): Int {
            if (!prefs.contains(key)) {
                return if (BuildConfig.INU_BUILD_TYPE == "canary") CANARY else STABLE
            }
            return prefs.getInt(key, default)
        }
    }

    @JvmField
    val UPDATE_CHANNEL = UpdateChannelItem()

    @JvmField
    val UPDATE_LAST_CHECK_MS = LongItem("update_last_check_ms", 0L)

    class IconReplacementItem : IntItem("icon_replacement", OFF) {
        companion object {
            const val OFF = 0
            const val SOLAR = 1
        }
    }

    @JvmField
    val ICON_REPLACEMENT = IconReplacementItem()
}
