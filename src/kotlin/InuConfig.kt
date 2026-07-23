package desu.inugram

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import desu.inugram.helpers.chat.PinnedReactionsHelper
import desu.inugram.helpers.font.FontConfig
import desu.inugram.helpers.menu.ChatMenuConfig
import desu.inugram.helpers.menu.MessageMenuConfig
import desu.inugram.ui.FormattingPopupConfig

object InuConfig {
    private const val PREFS_NAME = "inugram"

    lateinit var prefs: SharedPreferences
    private val _items = mutableListOf<Item<*>>()
    val items: List<Item<*>> get() = _items

    fun load(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        FontConfig.register() // force-init so its items join _items before we load them
        for (item in _items) item.load(prefs)
    }

    abstract class Item<T>(val key: String, val default: T, val exportable: Boolean = true) {
        private var currentValue: T = default

        open var value: T
            get() = currentValue
            set(v) {
                currentValue = v
                save()
            }

        init {
            _items.add(this)
        }

        fun load(prefs: SharedPreferences) {
            currentValue = read(prefs)
        }

        fun save() {
            prefs.edit { write() }
        }

        // batched write: set currentValue and stage it onto [editor] (call inside a prefs.edit {} block,
        // passing that block's editor, to commit several items in one transaction)
        fun unsafeSet(value: T, editor: SharedPreferences.Editor) {
            currentValue = value
            editor.write()
        }

        protected abstract fun read(prefs: SharedPreferences): T
        protected abstract fun SharedPreferences.Editor.write()
    }

    open class BoolItem(key: String, default: Boolean, exportable: Boolean = true) :
        Item<Boolean>(key, default, exportable) {
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

    open class IntItem(key: String, default: Int, exportable: Boolean = true) : Item<Int>(key, default, exportable) {
        override fun read(prefs: SharedPreferences): Int = prefs.getInt(key, default)
        override fun SharedPreferences.Editor.write() {
            putInt(key, value)
        }
    }

    class FloatItem(key: String, default: Float, exportable: Boolean = true) : Item<Float>(key, default, exportable) {
        override fun read(prefs: SharedPreferences): Float = prefs.getFloat(key, default)
        override fun SharedPreferences.Editor.write() {
            putFloat(key, value)
        }
    }

    class StringItem(key: String, default: String, exportable: Boolean = true) :
        Item<String>(key, default, exportable) {
        override fun read(prefs: SharedPreferences): String = prefs.getString(key, default) ?: default
        override fun SharedPreferences.Editor.write() {
            putString(key, value)
        }
    }

    class LongItem(key: String, default: Long, exportable: Boolean = true) : Item<Long>(key, default, exportable) {
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
    val MATERIAL3_SWITCHES = BoolItem("material3_switches", false)

    @JvmField
    val MATERIAL3_FABS = BoolItem("material3_fabs", true)

    @JvmField
    val M3_SECTIONS_STYLE = BoolItem("m3_sections_style", false)

    @JvmField
    val MATERIAL3_AVATARS = BoolItem("material3_avatars", false)

    // snapshot of theme state before Monet was enabled, "day|night|autoNightType"; empty = none
    @JvmField
    val MONET_PREV = StringItem("monet_prev", "", exportable = false)

    class PredictiveBackModeItem : IntItem("predictive_back_mode", OFF) {
        companion object {
            const val OFF = 0
            const val STOCK = 1
            const val MATERIAL3 = 2
        }
    }

    @JvmField
    val PREDICTIVE_BACK_MODE = PredictiveBackModeItem()

    class TextSpoilerModeItem : IntItem("text_spoiler_mode", SIMPLE) {
        companion object {
            const val DEFAULT = 0
            const val SIMPLE = 1
            const val EPSTEIN = 2
        }
    }

    @JvmField
    val TEXT_SPOILER_MODE = TextSpoilerModeItem()

    @JvmField
    val SPOILER_EXTEND_TO_LINE_END = BoolItem("spoiler_extend_to_line_end", false)

    @JvmField
    val LINK_PREVIEW_SPOILER = BoolItem("link_preview_spoiler", true)

    class MediaSpoilerModeItem : IntItem("media_spoiler_mode", PILL) {
        // Migrate the old `simple_media_spoilers` boolean toggle: on → pill, off → telegram.
        override fun read(prefs: SharedPreferences): Int {
            if (prefs.contains(key)) return prefs.getInt(key, default)
            if (!prefs.contains("simple_media_spoilers")) return default
            val migrated = if (prefs.getBoolean("simple_media_spoilers", true)) PILL else TELEGRAM
            prefs.edit {
                putInt(key, migrated)
                remove("simple_media_spoilers")
            }
            return migrated
        }

        companion object {
            const val TELEGRAM = 0
            const val PILL = 1
            const val CIRCLE = 2
        }
    }

    @JvmField
    val MEDIA_SPOILER_MODE = MediaSpoilerModeItem()

    class BlockedMessagesModeItem : IntItem("blocked_messages_mode", OFF) {
        companion object {
            const val OFF = 0
            const val SPOILER = 1
            const val HIDE = 2
        }
    }

    @JvmField
    val BLOCKED_MESSAGES_MODE = BlockedMessagesModeItem()

    @JvmField
    val DISABLE_INSTANT_CAMERA = BoolItem("disable_instant_camera", true)

    @JvmField
    val GIF_SEEKBAR = BoolItem("gif_seekbar", true)

    @JvmField
    val AUTO_DISABLE_PROXY_ON_VPN = BoolItem("auto_disable_proxy_on_vpn", false)

    @JvmField
    val PROXY_SUPPRESSED_BY_VPN = BoolItem("proxy_suppressed_by_vpn", false, exportable = false)

    @JvmField
    val SHOW_ALL_RECENT_STICKERS = BoolItem("show_all_recent_stickers", true)

    @JvmField
    val HIDE_TRENDING_STICKERS = BoolItem("hide_trending_stickers", true)

    @JvmField
    val NAVIGATION_DRAWER = BoolItem("navigation_drawer", false)

    @JvmField
    val DRAWER_BACK_GESTURE = BoolItem("drawer_back_gesture", false)

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
    val DISABLE_SWIPE_TO_HIDE_GENERAL_TOPIC = BoolItem("disable_swipe_to_hide_general_topic", true)

    @JvmField
    val OPEN_ARCHIVE_ON_PULL = BoolItem("open_archive_on_pull", false)

    @JvmField
    val CHAT_ALWAYS_SHOW_DOWN = BoolItem("chat_always_show_down", true)

    @JvmField
    val CHAT_TWO_FINGER_SELECT = BoolItem("chat_two_finger_select", true)

    @JvmField
    val CHAT_REMEMBER_ALL_REPLIES = BoolItem("chat_remember_all_replies", true)

    @JvmField
    val DISABLE_BOT_DRAFT_TOP = BoolItem("disable_bot_draft_top", true)

    @JvmField
    val HIDE_BOTTOM_BAR_JOINED = BoolItem("hide_bottom_bar_joined", false)

    @JvmField
    val HIDE_BOTTOM_BAR_NON_JOINED = BoolItem("hide_bottom_bar_non_joined", false)

    @JvmField
    val HIDE_BOTTOM_BAR_NON_JOINED_GROUPS = BoolItem("hide_bottom_bar_non_joined_groups", false)

    @JvmField
    val HIDE_BOTTOM_BAR_REPLIES = BoolItem("hide_bottom_bar_replies", false)

    @JvmField
    val HIDE_BOTTOM_BAR_PINNED = BoolItem("hide_bottom_bar_pinned", false)

    @JvmField
    val HIDE_BOT_SLASH_GROUPS = BoolItem("hide_bot_slash_groups", true)

    @JvmField
    val HIDE_BOT_SLASH_BOTS = BoolItem("hide_bot_slash_bots", false)

    @JvmField
    val HIDE_BOT_WEBVIEW_INPUT = BoolItem("hide_bot_webview_input", false)

    @JvmField
    val HIDE_SEND_AS_PICKER = BoolItem("hide_send_as_picker", false)

    @JvmField
    val SEND_TO_DISCUSS_WITHOUT_JOIN = BoolItem("send_to_discuss_without_join", true)

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
    val HIDE_HASHTAG_SUGGESTIONS = BoolItem("hide_hashtag_suggestions", true)

    @JvmField
    val DISABLE_PROFILE_SCROLL_SNAP = BoolItem("disable_profile_scroll_snap", true)

    @JvmField
    val REDUCE_PROFILE_MOTION = BoolItem("reduce_profile_motion", true)

    @JvmField
    val PROFILE_PREFER_MEDIA_TAB = BoolItem("profile_prefer_media_tab", true)

    @JvmField
    val DISABLE_MOTION_PHOTOS = BoolItem("disable_motion_photos", true)

    @JvmField
    val DISABLE_VOLUME_PLAY_VIDEO = BoolItem("disable_volume_play_video", true)

    @JvmField
    val DISABLE_QUICK_SHARE = BoolItem("disable_quick_share", true)

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

    @JvmField
    val HIDE_ALL_CHATS_TAB = BoolItem("hide_all_chats_tab", false)

    class CommunityDisplayModeItem : IntItem("community_display_mode", REGULAR) {
        companion object {
            const val REGULAR = 1
            const val LONG_TAP = 2
            const val INVISIBLE = 3
        }
    }

    @JvmField
    val COMMUNITY_DISPLAY_MODE = CommunityDisplayModeItem()

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

    @JvmField
    val CONFIRM_INTERNAL_LINKS = BoolItem("confirm_internal_links", false)

    @JvmField
    val DISABLE_BROWSER_SWIPE_COLLAPSE = BoolItem("disable_browser_swipe_collapse", true)

    @JvmField
    val CONFIRM_REACTION_NON_MEMBER = BoolItem("confirm_reaction_non_member", false)

    @JvmField
    val HIDE_CALL_ACTION_BUTTON = BoolItem("hide_call_action_button", true)

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
    val STRIP_TRACKING_PARAMS_ON_OPEN = BoolItem("strip_tracking_params", true)

    @JvmField
    val STRIP_TRACKING_PARAMS_ON_PASTE = BoolItem("strip_tracking_params_on_paste", true)

    @JvmField
    val DISABLE_INTRO_STICKER = BoolItem("disable_intro_sticker", true)

    @JvmField
    val DISABLE_DRAFT_UPLOAD = BoolItem("disable_draft_upload", false)

    @JvmField
    val ROUND_DEFAULT_CAMERA = IntItem("round_default_camera", 1) // 1=Front, 2=Rear, 3=Ask

    @JvmField
    val ROUND_RECORDER_KEEP_ZOOM = BoolItem("round_recorder_keep_zoom", false)

    @JvmField
    val ROUND_RECORDER_ZOOM_SLIDER = BoolItem("round_recorder_zoom_slider", true)

    @JvmField
    val ROUND_RECORDER_EXPONENTIAL_ZOOM = BoolItem("round_recorder_exponential_zoom", true)

    @JvmField
    val ROUND_RECORDER_DUAL_CAMERA = BoolItem("round_recorder_dual_camera", true)

    // todo: remove in 40
    class NonIslandSplitFromTabBarsItem(key: String) : BoolItem(key, false) {
        override fun read(prefs: SharedPreferences): Boolean {
            if (prefs.contains(key)) return prefs.getBoolean(key, default)
            if (!prefs.contains("non_island_tab_bars")) return default
            val migrated = prefs.getBoolean("non_island_tab_bars", false)
            prefs.edit { putBoolean(key, migrated) }
            return migrated
        }
    }

    @JvmField
    val NON_ISLAND_FOLDERS_BAR = NonIslandSplitFromTabBarsItem("non_island_folders_bar")

    @JvmField
    val NON_ISLAND_SHARED_MEDIA_TABS = NonIslandSplitFromTabBarsItem("non_island_shared_media_tabs")

    @JvmField
    val NON_ISLAND_GLOBAL_SEARCH = BoolItem("non_island_global_search", false)

    @JvmField
    val NON_ISLAND_CHAT_ELEMENTS = BoolItem("non_island_chat_elements", false)

    @JvmField
    val HIDE_FADE_VIEW = BoolItem("hide_fade_view", false)

    @JvmField
    val DISABLE_SCRIM_BLUR = BoolItem("disable_scrim_blur", false)

    @JvmField
    val DISABLE_GLASS_GLARE = BoolItem("disable_glass_glare", true)

    @JvmField
    val REDUCE_MENU_MOTION = BoolItem("reduce_menu_motion", true)

    @JvmField
    val PROFILE_PHOTO_GRADIENT_FADE = BoolItem("profile_photo_gradient_fade", false)

    @JvmField
    val SIMPLE_ATTACH_POPUP_ANIMATION = BoolItem("simple_attach_popup_animation", false)

    @JvmField
    val CHAT_VOICE_IN_ATTACH = BoolItem("chat_voice_in_attach", false)

    @JvmField
    val CHAT_VIEWS_BOTTOM = BoolItem("chat_views_bottom", false)

    @JvmField
    val DISABLE_CHAT_TITLE_PHONE = BoolItem("disable_chat_title_phone", true)

    @JvmField
    val SEARCH_FROM_GLOBAL = BoolItem("search_from_global", true)

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
    val SHOW_FORWARD_TIME = BoolItem("show_forward_time", true)

    @JvmField
    val COMPACT_EDITED = BoolItem("compact_edited", false)

    @JvmField
    val BUBBLE_TAILS = BoolItem("bubble_tails", true)

    @JvmField
    val INTERACTIVE_CHAT_PREVIEW = BoolItem("disable_chat_preview_expand", true)

    @JvmField
    val FORMATTING_POPUP = BoolItem("formatting_popup", true)

    @JvmField
    val SUGGEST_CUSTOM_EMOJI_AFTER = BoolItem("suggest_custom_emoji_after", true)

    @JvmField
    val EMOJI_PANEL_KEYWORD_SEARCH = BoolItem("emoji_panel_keyword_search", true)

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

    @JvmField
    val MESSAGE_MENU_ITEMS = MessageMenuConfig("message_menu_items")

    @JvmField
    val MESSAGE_MENU_BOTTOM_ROW = BoolItem("message_menu_bottom_row", false)

    // false = bottom (default), true = top
    @JvmField
    val MESSAGE_MENU_QUICK_ACTIONS_TOP = BoolItem("message_menu_quick_actions_top", false)

    @JvmField
    val CHAT_MENU_ITEMS = ChatMenuConfig("chat_menu_items")

    class ForwardLongTapItem : IntItem("forward_long_tap_action", CHOOSE_MODE) {
        companion object {
            const val OFF = 0
            const val CHOOSE_MODE = 1
            const val WITHOUT_AUTHOR = 2
            const val WITHOUT_CAPTION = 3
        }
    }

    @JvmField
    val FORWARD_LONG_TAP_ACTION = ForwardLongTapItem()

    class ReplyLongTapItem : IntItem("reply_long_tap_action", OFF) {
        companion object {
            const val OFF = 0
            const val CHOOSE_MODE = 1
            const val REPLY_IN = 2
            const val REPLY_IN_DMS = 3
        }
    }

    @JvmField
    val REPLY_LONG_TAP_ACTION = ReplyLongTapItem()

    class RepeatModeItem : IntItem("repeat_mode", COPY) {
        companion object {
            const val COPY = 0
            const val FORWARD = 1
            const val ASK = 2
        }
    }

    @JvmField
    val REPEAT_MODE = RepeatModeItem()

    @JvmField
    val ANIMATION_SPEED = FloatItem("animation_speed", 1.0f)

    class IconReplacementItem : IntItem("icon_replacement", OFF) {
        companion object {
            const val OFF = 0
            const val SOLAR = 1
        }
    }

    @JvmField
    val ICON_REPLACEMENT = IconReplacementItem()

    class NotificationIconItem : IntItem("notification_icon", TELEGRAM) {
        companion object {
            const val TELEGRAM = 0
            const val INUGRAM = 1
        }
    }

    @JvmField
    val NOTIFICATION_ICON = NotificationIconItem()

    class MapProviderItem : IntItem("map_provider", GOOGLE) {
        companion object {
            const val GOOGLE = 0
            const val OSM = 1
        }
    }

    @JvmField
    val MAP_PROVIDER = MapProviderItem()

    class MapPreviewProviderItem : IntItem("map_preview_provider", DEFAULT) {
        companion object {
            const val DEFAULT = 0
            const val TELEGRAM = 1
            const val GOOGLE = 2
            const val YANDEX = 3
            const val DISABLED = 4
        }
    }

    @JvmField
    val MAP_PREVIEW_PROVIDER = MapPreviewProviderItem()

    class UpdatesEnabledItem : BoolItem("updates_enabled", true, exportable = false) {
        override fun read(prefs: SharedPreferences): Boolean {
            // compat, remove after a few months
            if (!prefs.contains(key) && prefs.contains("update_channel")) {
                val value = prefs.getInt("update_channel", 1) != 0
                prefs.edit { putBoolean(key, value) }
                return value
            }
            return prefs.getBoolean(key, default)
        }
    }

    @JvmField
    val UPDATES_ENABLED = UpdatesEnabledItem()

    // internal state
    @JvmField
    val VOICE_HINT_SHOWN = BoolItem("voice_hint_shown", false, exportable = false)

    @JvmField
    val MINIMIZE_STICKERS_CREATOR = BoolItem("minimize_stickers_creator", true, exportable = false)

    @JvmField
    val UPDATE_LAST_CHECK_MS = LongItem("update_last_check_ms", 0L, exportable = false)

    @JvmField
    val CLOUD_SYNC_ACCOUNT_ID = LongItem("cloud_sync_account_id", 0L, exportable = false)

    @JvmField
    val CLOUD_SYNC_AUTO = BoolItem("cloud_sync_auto", false)

    @JvmField
    val CLOUD_SYNC_AUTO_USER_SET = BoolItem("cloud_sync_auto_user_set", false, exportable = false)

    @JvmField
    val EVENT_LOG_CHAR_DIFF = BoolItem("event_log_char_diff", true)

    @JvmField
    val IN_PLACE_TRANSLATION = BoolItem("in_place_translation", true)

    @JvmField
    val TRANSLATE_WEB_PREVIEWS = BoolItem("translate_web_previews", true)

    @JvmField
    val KEEP_ORIGINAL_AFTER_TRANSLATION = BoolItem("keep_original_after_translation", false)

    @JvmField
    val TRANSLATE_AUTO_DETECT_LANG = BoolItem("translate_auto_detect_lang", true)

    @JvmField
    val ACCOUNT_ORDER = StringItem("account_order", "", exportable = false)

    @JvmField
    val FASTER_DOWNLOADS = BoolItem("faster_downloads", true)

    @JvmField
    val FASTER_UPLOADS = BoolItem("faster_uploads", true)

    @JvmField
    val BIOMETRIC_CONFIRM_DELETE_CHAT = BoolItem("biometric_confirm_delete_chat", false)

    @JvmField
    val BIOMETRIC_CONFIRM_LOGOUT = BoolItem("biometric_confirm_logout", false)

    @JvmField
    val BIOMETRIC_ALLOW_DEVICE_CREDENTIAL = BoolItem("biometric_allow_device_credential", false)
}
