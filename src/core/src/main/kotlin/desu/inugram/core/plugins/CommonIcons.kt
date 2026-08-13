package desu.inugram.core.plugins

/**
 * The `inu.icons.common` table: curated api name -> stock drawable name. The single source of
 * truth - rust asks through `commonIcon` on the bridge instead of keeping a copy, and
 * `icons_tests.rs` parses this file to cross-check the entries against the contract's union.
 */
object CommonIcons {
    val TABLE: Map<String, String> = mapOf(
        "archive" to "msg_archive",
        "bookmark" to "msg_saved",
        "bot" to "msg_bot",
        "channel" to "msg_channel",
        "check" to "ic_ab_done",
        "close" to "msg_close",
        "copy" to "msg_copy",
        "delete" to "msg_delete",
        "download" to "msg_download",
        "edit" to "msg_edit",
        "eye" to "msg_views",
        "eyeOff" to "msg_archive_hide",
        "forward" to "msg_forward",
        "group" to "msg_groups",
        "info" to "msg_info",
        "link" to "msg_link",
        "lock" to "msg_secret",
        "minus" to "msg_remove",
        "more" to "ic_ab_other",
        "mute" to "msg_mute",
        "pin" to "msg_pin",
        "plus" to "msg_add",
        "refresh" to "msg_retry",
        "reply" to "menu_reply",
        "search" to "msg_search",
        "settings" to "msg_settings",
        "share" to "msg_share",
        "star" to "msg_fave",
        "translate" to "msg_translate",
        "unmute" to "msg_unmute",
        "user" to "msg_contacts",
    )

    fun resolve(name: String): String? = TABLE[name]
}
