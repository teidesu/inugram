package desu.inugram.helpers

import android.content.DialogInterface
import android.widget.TextView
import desu.inugram.InuConfig
import desu.inugram.core.diff.DiffKind
import desu.inugram.core.diff.WordDiff
import desu.inugram.ui.MessageDetailsActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ContactsController
import org.telegram.messenger.Emoji
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.tgnet.NativeByteBuffer
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBarMenu
import org.telegram.ui.ActionBar.ActionBarMenuSubItem
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChannelAdminLogActivity

object AdminLogHelper {
    const val OPTION_DETAILS = 510

    private const val MENU_TOGGLE_DIFF = 100

    @JvmStatic
    fun addMenuItems(
        items: ArrayList<CharSequence>,
        options: ArrayList<Int>,
        icons: ArrayList<Int>,
        selected: MessageObject,
    ) {
        if (selected.currentEvent == null) return
        items.add(LocaleController.getString(R.string.InuMessageDetails))
        options.add(OPTION_DETAILS)
        icons.add(R.drawable.msg_info)
    }

    private var approvedBanForMessage: MessageObject? = null

    @JvmStatic
    fun processMenuOption(option: Int, fragment: ChannelAdminLogActivity, selected: MessageObject): Boolean {
        when (option) {
            OPTION_DETAILS -> fragment.presentFragment(MessageDetailsActivity(selected, null))
            ChannelAdminLogActivity.OPTION_BAN -> {
                if (selected == approvedBanForMessage) return false; // continue to default impl

                val name = when (val from = selected.messageOwner.from_id) {
                    is TLRPC.TL_peerUser -> ContactsController.formatName(
                        fragment.messagesController.getUser(from.user_id) ?: return true
                    )

                    is TLRPC.TL_peerChannel, is TLRPC.TL_peerChat -> fragment.messagesController.getChat(from.user_id)?.title
                        ?: return true

                    else -> return true
                }

                val dialog = AlertDialog.Builder(fragment.context)
                    .setTitle(LocaleController.getString(R.string.KickFromGroup))
                    .setMessage(
                        AndroidUtilities.replaceTags(
                            LocaleController.formatString(
                                R.string.InuEventLogBanConfirm,
                                name
                            )
                        )
                    )
                    .setPositiveButton(LocaleController.getString(R.string.Remove)) { _: DialogInterface, _: Int ->
                        approvedBanForMessage = selected
                        fragment.processSelectedOption(option)
                    }
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .create()

                fragment.showDialog(dialog)

                dialog.messageTextView.text =
                    Emoji.replaceEmoji(dialog.messageTextView.text, dialog.messageTextView.paint.fontMetricsInt, false)
                (dialog.getButton(DialogInterface.BUTTON_POSITIVE) as TextView).setTextColor(Theme.getColor(Theme.key_text_RedBold))
            }

            else -> return false
        }
        return true
    }

    @JvmStatic
    fun injectBubbles(
        accountNum: Int,
        event: TLRPC.TL_channelAdminLogEvent,
        messageObjects: ArrayList<MessageObject>,
        chat: TLRPC.Chat,
        mid: IntArray,
        addToEnd: Boolean,
    ) {
        val action = event.action as? TLRPC.TL_channelAdminLogEventActionEditMessage ?: return
        val oldMessage = action.prev_message ?: return
        val newMessage = action.new_message ?: return
        val oldMedia = oldMessage.media ?: return
        val newMedia = newMessage.media ?: return

        if (oldMedia is TLRPC.TL_messageMediaEmpty || oldMedia is TLRPC.TL_messageMediaWebPage) return
        if (newMedia is TLRPC.TL_messageMediaEmpty || newMedia is TLRPC.TL_messageMediaWebPage) return

        val differentClass = newMedia.javaClass != oldMedia.javaClass
        val differentPhoto = newMedia.photo != null && oldMedia.photo != null && newMedia.photo.id != oldMedia.photo.id
        val differentDoc =
            newMedia.document != null && oldMedia.document != null && newMedia.document.id != oldMedia.document.id
        if (!differentClass && !differentPhoto && !differentDoc) return

        val peer = TLRPC.TL_peerChannel().apply { channel_id = chat.id }

        val labelMsg = TLRPC.TL_messageService().apply {
            this.action = TLRPC.TL_messageActionEmpty()
            peer_id = peer
            date = event.date
            id = mid[0]++
            dialog_id = -chat.id
            from_id = TLRPC.TL_peerUser().apply { user_id = event.user_id }
        }
        val label = MessageObject(accountNum, labelMsg, false, false)
        label.messageText = LocaleController.getString(R.string.InuEventLogMediaBeforeEdit)
        label.messageOwner.message = label.messageText.toString()
        label.contentType = 1
        label.currentEvent = event

        val mediaMsg = TLRPC.TL_message().apply {
            out = false
            unread = false
            peer_id = peer
            date = event.date
            realId = oldMessage.id
            id = mid[0]++
            from_id = newMessage.from_id ?: TLRPC.TL_peerUser().apply { user_id = event.user_id }
            dialog_id = -chat.id
            media = oldMedia
            message = oldMessage.message ?: ""
            if (oldMessage.entities != null) entities = oldMessage.entities
        }
        val mo = MessageObject(accountNum, mediaMsg, null, null, true, true, event.id)
        mo.currentEvent = event
        if (mo.contentType < 0) return

        val player = MediaController.getInstance()
        if (player.isPlayingMessage(mo)) {
            val playing = player.playingMessageObject
            mo.audioProgress = playing.audioProgress
            mo.audioProgressSec = playing.audioProgressSec
        }

        if (addToEnd) {
            messageObjects.add(0, mo)
            messageObjects.add(0, label)
        } else {
            messageObjects.add(messageObjects.size - 1, label)
            messageObjects.add(messageObjects.size - 1, mo)
        }
    }

    private var toggleDiffItem: ActionBarMenuSubItem? = null

    @JvmStatic
    fun setupHeader(menu: ActionBarMenu) {
        val headerItem = menu.addItem(0, R.drawable.ic_ab_other)
        headerItem.contentDescription = LocaleController.getString(R.string.AccDescrMoreOptions)
        toggleDiffItem = headerItem.addSubItem(
            MENU_TOGGLE_DIFF,
            R.drawable.inu_tabler_file_diff,
            LocaleController.getString(R.string.InuEventLogShowDiff),
            false,
        ).apply {
            makeCheckView(2)
            // makeCheckView clobbers textView padding; restore the icon-side gap
            val pad = AndroidUtilities.dp(43f)
            textView.setPadding(
                if (LocaleController.isRTL) pad else pad,
                0,
                if (LocaleController.isRTL) pad else pad,
                0
            )
            setChecked(InuConfig.EVENT_LOG_CHAR_DIFF.value)
        }
    }

    @JvmStatic
    fun processActionBarMenuItem(id: Int, fragment: ChannelAdminLogActivity): Boolean {
        if (id != MENU_TOGGLE_DIFF) return false
        InuConfig.EVENT_LOG_CHAR_DIFF.toggle()
        toggleDiffItem?.setChecked(InuConfig.EVENT_LOG_CHAR_DIFF.value)
        fragment.inu_rebuildEvents()
        return true
    }

    @JvmStatic
    fun applyEditDiff(message: TLRPC.Message, oldMessage: TLRPC.Message?, newMessage: TLRPC.Message?): Boolean {
        if (!InuConfig.EVENT_LOG_CHAR_DIFF.value) return false
        if (oldMessage == null || newMessage == null) return false
        val oldText = oldMessage.message ?: ""
        val newText = newMessage.message ?: ""
        if (oldText == newText) return false

        val (combined, entities) = computeInlineDiff(oldText, newText, oldMessage.entities, newMessage.entities)
        message.message = combined
        message.entities = entities

        if (message.media is TLRPC.TL_messageMediaWebPage) {
            message.media = TLRPC.TL_messageMediaEmpty()
        } else {
            message.media?.webpage = null
        }
        return true
    }

    private fun computeInlineDiff(
        oldText: String,
        newText: String,
        oldEntities: List<TLRPC.MessageEntity>?,
        newEntities: List<TLRPC.MessageEntity>?,
    ): Pair<String, ArrayList<TLRPC.MessageEntity>> {
        val ops = WordDiff.compute(oldText, newText)
        val sb = StringBuilder(oldText.length + newText.length)
        val entities = ArrayList<TLRPC.MessageEntity>()
        for (op in ops) {
            val combinedStart = sb.length
            when (op.kind) {
                DiffKind.EQUAL -> {
                    sb.append(oldText, op.oldStart, op.oldStart + op.length)
                    appendRemapped(entities, newEntities, op.newStart, op.length, combinedStart)
                }

                DiffKind.DELETE -> {
                    sb.append(oldText, op.oldStart, op.oldStart + op.length)
                    entities.add(TLRPC.TL_messageEntityDiffDelete().apply {
                        offset = combinedStart
                        length = op.length
                    })
                    appendRemapped(entities, oldEntities, op.oldStart, op.length, combinedStart)
                }

                DiffKind.INSERT -> {
                    sb.append(newText, op.newStart, op.newStart + op.length)
                    entities.add(TLRPC.TL_messageEntityDiffInsert().apply {
                        offset = combinedStart
                        length = op.length
                    })
                    appendRemapped(entities, newEntities, op.newStart, op.length, combinedStart)
                }
            }
        }
        return sb.toString() to entities
    }

    private fun appendRemapped(
        out: MutableList<TLRPC.MessageEntity>,
        sourceEnts: List<TLRPC.MessageEntity>?,
        sourceStart: Int,
        length: Int,
        combinedStart: Int,
    ) {
        if (sourceEnts.isNullOrEmpty()) return
        val sourceEnd = sourceStart + length
        for (e in sourceEnts) {
            val eStart = maxOf(e.offset, sourceStart)
            val eEnd = minOf(e.offset + e.length, sourceEnd)
            if (eEnd <= eStart) continue
            val cloned = cloneEntity(e) ?: continue
            cloned.offset = combinedStart + (eStart - sourceStart)
            cloned.length = eEnd - eStart
            out.add(cloned)
        }
    }

    private fun cloneEntity(e: TLRPC.MessageEntity): TLRPC.MessageEntity? {
        return try {
            val buf = NativeByteBuffer(e.objectSize)
            try {
                e.serializeToStream(buf)
                buf.position(0)
                val constructor = buf.readInt32(false)
                TLRPC.MessageEntity.TLdeserialize(buf, constructor, false)
            } finally {
                buf.reuse()
            }
        } catch (_: Exception) {
            null
        }
    }
}
