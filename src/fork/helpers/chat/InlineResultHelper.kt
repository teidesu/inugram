package desu.inugram.helpers.chat

import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.tgnet.SerializedData
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.ItemOptions
import java.util.function.Consumer

object InlineResultHelper {
    private fun canSendWithoutVia(result: TLRPC.BotInlineResult): Boolean {
        val message = result.send_message ?: return false
        if (message is TLRPC.TL_botInlineMessageMediaInvoice) return false
        return !(message is TLRPC.TL_botInlineMessageMediaAuto && result.type == "game")
    }

    private fun hasButtons(result: TLRPC.BotInlineResult) = result.send_message?.reply_markup != null

    // users can't send reply markup, and the cached result must stay intact for regular inline sends
    @JvmStatic
    fun copyWithoutButtons(result: TLRPC.BotInlineResult): TLRPC.BotInlineResult {
        if (!hasButtons(result)) return result
        val data = SerializedData(result.objectSize)
        result.serializeToStream(data)
        val stream = SerializedData(data.toByteArray())
        data.cleanup()
        val copy = TLRPC.BotInlineResult.TLdeserialize(stream, stream.readInt32(false), false)
        stream.cleanup()
        copy.send_message.reply_markup = null
        return copy
    }

    @JvmStatic
    fun maybeConfirmSendWithoutVia(activity: ChatActivity, result: TLRPC.BotInlineResult, restricted: Boolean, send: Runnable): Boolean {
        if (!canSendWithoutVia(result)) return false
        val ctx = activity.parentActivity ?: return false
        val message = StringBuilder(LocaleController.getString(if (restricted) R.string.InuSendWithoutViaRestrictedConfirm else R.string.InuSendWithoutViaConfirm))
        if (hasButtons(result)) {
            message.append("\n\n").append(LocaleController.getString(R.string.InuSendWithoutViaButtonsWarning))
        }
        activity.showDialog(
            AlertDialog.Builder(ctx, activity.resourceProvider)
                .setTitle(LocaleController.getString(R.string.InuSendWithoutVia))
                .setMessage(message)
                .setPositiveButton(LocaleController.getString(R.string.Send)) { _, _ -> send.run() }
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create()
        )
        return true
    }

    @JvmStatic
    fun addSendWithoutViaItem(options: ItemOptions, result: TLRPC.BotInlineResult, checked: Boolean, onToggle: Consumer<Boolean>) {
        if (!canSendWithoutVia(result)) return
        var isChecked = checked
        val item = options.addChecked()
        item.setText(LocaleController.getString(R.string.InuSendWithoutVia))
        if (hasButtons(result)) item.setSubtext(LocaleController.getString(R.string.InuSendWithoutViaButtonsLost))
        item.setChecked(isChecked)
        item.setOnClickListener {
            isChecked = !isChecked
            item.setChecked(isChecked)
            onToggle.accept(isChecked)
        }
    }
}
