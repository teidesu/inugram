package desu.inugram.helpers.icons

import android.util.SparseIntArray
import org.telegram.messenger.R

/*
 * VKUI icon pack, ported from Catogram (ua.itaysonlab.catogram.vkui)
 * Icons: https://github.com/VKCOM/icons (MIT)
 */
object VkIconPack : IconPack() {
    override fun buildIcons() = SparseIntArray(46).apply {
        // Settings
        put(R.drawable.msg_settings, R.drawable.vkui_settings_outline_28)
        put(R.drawable.msg2_language, R.drawable.vkui_globe_outline_28)
        put(R.drawable.msg2_secret, R.drawable.vkui_lock_outline_28)
        put(R.drawable.msg2_data, R.drawable.vkui_services_outline_28)
        put(R.drawable.msg2_discussion, R.drawable.vkui_messages_outline_28)
        put(R.drawable.msg2_folder, R.drawable.vkui_cube_box_outline_28)
        put(R.drawable.msg2_ask_question, R.drawable.vkui_help_outline_28)
        put(R.drawable.msg2_help, R.drawable.vkui_help_outline_28)
        put(R.drawable.msg2_policy, R.drawable.vkui_check_shield_outline_28)
        put(R.drawable.msg2_notifications, R.drawable.vkui_notifications_28)
        put(R.drawable.msg2_devices, R.drawable.vkui_laptop_outline_28)
        put(R.drawable.msg_theme, R.drawable.vkui_palette_outline_28)
        put(R.drawable.msg_log, R.drawable.vkui_grid_square_outline_28)
        put(R.drawable.msg_payment_card, R.drawable.vkui_payment_card_outline_28)
        put(R.drawable.msg_clearcache, R.drawable.vkui_clear_data_outline_28)

        // Chat input
        put(R.drawable.input_attach, R.drawable.vkui_attach_outline_28)
        put(R.drawable.input_mic, R.drawable.vkui_voice_outline_28)
        put(R.drawable.input_video, R.drawable.vkui_videocam_outline_28)
        put(R.drawable.input_schedule, R.drawable.vkui_calendar_outline_28)
        put(R.drawable.input_keyboard, R.drawable.vkui_keyboard_outline_28)
        put(R.drawable.input_bot1, R.drawable.vkui_chevron_right_circle_outline_28)
        put(R.drawable.input_bot2, R.drawable.vkui_keyboard_bots_outline_28)
        put(R.drawable.input_smile, R.drawable.vkui_smile_outline_28)
        put(R.drawable.input_notify_off, R.drawable.vkui_notification_disable_outline_28)
        put(R.drawable.input_notify_on, R.drawable.vkui_notifications_28)
        put(R.drawable.smiles_tab_stickers, R.drawable.vkui_sticker_outline_28)
        put(R.drawable.smiles_tab_gif, R.drawable.vkui_picture_outline_28)

        // Drawer
        put(R.drawable.msg_archive, R.drawable.vkui_archive_outline_28)
        put(R.drawable.msg_contacts, R.drawable.vkui_user_outline_28)
        put(R.drawable.msg_calls, R.drawable.vkui_phone_outline_28)
        put(R.drawable.msg_saved, R.drawable.vkui_bookmark_outline_28)
        put(R.drawable.msg_addcontact, R.drawable.vkui_user_add_outline_24)

        // Message context menu
        put(R.drawable.menu_reply, R.drawable.vkui_reply_outline_28)
        put(R.drawable.msg_link, R.drawable.vkui_link_circle_outline_28)
        put(R.drawable.msg_viewreplies, R.drawable.vkui_message_reply_outline_28)
        put(R.drawable.msg_pin, R.drawable.vkui_pin_outline_28)
        put(R.drawable.msg_gallery, R.drawable.vkui_picture_outline_28)
        put(R.drawable.msg_edit, R.drawable.vkui_edit_outline_28)
        put(R.drawable.msg_delete, R.drawable.vkui_delete_outline_android_28)
        put(R.drawable.msg_forward, R.drawable.vkui_share_outline_28)
        put(R.drawable.msg_gif, R.drawable.vkui_airplay_video_outline_28)
        put(R.drawable.msg_report, R.drawable.vkui_report_outline_28)
        put(R.drawable.msg_copy, R.drawable.vkui_copy_outline_28)
        put(R.drawable.msg_fave, R.drawable.vkui_favorite_outline_28)
        put(R.drawable.msg_shareout, R.drawable.vkui_share_external_outline_28)
        put(R.drawable.msg_download, R.drawable.vkui_download_outline_28)
    }
}
