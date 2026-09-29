package desu.inugram.helpers.chat

import android.content.DialogInterface
import android.content.res.ColorStateList
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper

object DeleteOwnMessagesHelper {
    private const val TAG = "inu/delete-own"
    private const val BATCH_SIZE = 100
    private const val DELETE_BATCH_DELAY_MS = 100L
    private const val MAX_CONCURRENT_SEARCH = 5

    private enum class Phase { SEARCH, DELETE }

    @JvmStatic
    fun isApplicable(chat: TLRPC.Chat?): Boolean {
        if (chat == null) return false
        if (ChatObject.isMonoForum(chat)) return false
        return ChatObject.isMegagroup(chat) || !ChatObject.isChannel(chat)
    }

    @JvmStatic
    fun start(activity: ChatActivity) {
        val chat = activity.currentChat ?: return
        if (!isApplicable(chat)) return
        Session(activity, chat).startSearch()
    }

    private class Session(val activity: ChatActivity, val chat: TLRPC.Chat) {
        val accountId = activity.currentAccount
        val primaryDialogId = -chat.id
        val mergeDialogId = activity.mergeDialogId
        val topMsgId = activity.topicId.toInt()
        val selfPeer: TLRPC.InputPeer = MessagesController.getInputPeer(
            UserConfig.getInstance(accountId).currentUser
        )

        val primaryFoundIds = LinkedHashSet<Int>()
        val mergeFoundIds = LinkedHashSet<Int>()

        var cancelled = false
        var dialog: AlertDialog? = null
        var statusText: TextView? = null
        var progressBar: ProgressBar? = null

        var deletedSoFar = 0
        var batches: List<Batch> = emptyList()
        var batchIndex = 0

        var inflightSearches = 0
        val pendingSearches = ArrayDeque<Runnable>()
        var primaryTotal = -1
        var mergeTotal = -1
        var primaryPagesCompleted = 0
        var mergePagesCompleted = 0

        var phase = Phase.SEARCH
        var floodWaitUntilMs = 0L
        var floodTickerRunning = false

        fun startSearch() {
            showProgressDialog(indeterminate = true)
            updateSearchStatus()
            schedulePage(primaryDialogId, primaryFoundIds, 0, primary = true)
            if (mergeDialogId != 0L) {
                schedulePage(mergeDialogId, mergeFoundIds, 0, primary = false)
            }
        }

        fun schedulePage(dialogId: Long, target: LinkedHashSet<Int>, addOffset: Int, primary: Boolean) {
            if (cancelled) return
            val task = Runnable { sendPage(dialogId, target, addOffset, primary) }
            if (inflightSearches < MAX_CONCURRENT_SEARCH) {
                inflightSearches++
                task.run()
            } else {
                pendingSearches.addLast(task)
            }
        }

        fun sendPage(dialogId: Long, target: LinkedHashSet<Int>, addOffset: Int, primary: Boolean) {
            if (cancelled) {
                releaseSlot()
                return
            }
            val peer = MessagesController.getInstance(accountId).getInputPeer(dialogId) ?: run {
                releaseSlot()
                onError(null)
                return
            }
            val req = TLRPC.TL_messages_search().apply {
                this.peer = peer
                q = ""
                limit = BATCH_SIZE
                this.offset_id = 0
                this.add_offset = addOffset
                filter = TLRPC.TL_inputMessagesFilterEmpty()
                from_id = selfPeer
                flags = flags or 1
                if (topMsgId != 0 && primary) {
                    top_msg_id = topMsgId
                    flags = flags or 2
                }
            }
            ConnectionsManager.getInstance(accountId).sendRequest(req) { response, error ->
                AndroidUtilities.runOnUIThread {
                    onSearchResponse(dialogId, target, addOffset, primary, response, error)
                }
            }
        }

        fun releaseSlot() {
            inflightSearches--
            if (cancelled) return
            val next = pendingSearches.removeFirstOrNull() ?: return
            inflightSearches++
            next.run()
        }

        fun onSearchResponse(
            dialogId: Long,
            target: LinkedHashSet<Int>,
            addOffset: Int,
            primary: Boolean,
            response: TLObject?,
            error: TLRPC.TL_error?,
        ) {
            if (cancelled) {
                releaseSlot()
                return
            }
            if (error != null) {
                val text = error.text.orEmpty()
                val floodSecs = parseFloodWait(text)
                if (floodSecs != null) {
                    Log.d(TAG, "search FLOOD_WAIT dialog=$dialogId offset=$addOffset ${floodSecs}s ($text)")
                    handleFloodWait(floodSecs) {
                        sendPage(dialogId, target, addOffset, primary)
                    }
                    return
                }
                Log.e(TAG, "search failed: code=${error.code} text=$text")
                releaseSlot()
                onError(text)
                return
            }
            val messages = response as? TLRPC.messages_Messages
            val usable = messages != null && messages !is TLRPC.TL_messages_messagesNotModified
            if (usable) {
                for (m in messages!!.messages) {
                    if (!m.out || m.post) continue
                    target.add(m.id)
                }
            }
            val isProbe = (if (primary) primaryTotal else mergeTotal) < 0
            if (isProbe) {
                val count = if (usable) readCount(messages!!) else 0
                if (primary) primaryTotal = count else mergeTotal = count
                val needed = pagesNeededFor(count)
                for (i in 1 until needed) {
                    schedulePage(dialogId, target, i * BATCH_SIZE, primary)
                }
            }
            if (primary) primaryPagesCompleted++ else mergePagesCompleted++
            updateSearchStatus()
            releaseSlot()
            maybeAdvance()
        }

        fun readCount(msgs: TLRPC.messages_Messages): Int = when (msgs) {
            is TLRPC.TL_messages_messagesSlice -> msgs.count
            is TLRPC.TL_messages_channelMessages -> msgs.count
            else -> msgs.messages.size
        }

        fun pagesNeededFor(count: Int): Int =
            if (count <= 0) 1 else (count + BATCH_SIZE - 1) / BATCH_SIZE

        fun primaryDone(): Boolean =
            primaryTotal >= 0 && primaryPagesCompleted >= pagesNeededFor(primaryTotal)

        fun mergeDone(): Boolean =
            mergeDialogId == 0L || (mergeTotal >= 0 && mergePagesCompleted >= pagesNeededFor(mergeTotal))

        fun maybeAdvance() {
            if (cancelled) return
            if (primaryDone() && mergeDone()) onAllSearchDone()
        }

        fun updateSearchStatus() {
            if (inFloodWait()) return
            val found = primaryFoundIds.size + mergeFoundIds.size
            val anyTotal = primaryTotal >= 0 || (mergeDialogId != 0L && mergeTotal >= 0)
            statusText?.text = if (!anyTotal) {
                LocaleController.getString(R.string.InuDeleteOwnMessagesSearching)
            } else {
                val total = primaryTotal.coerceAtLeast(0) + mergeTotal.coerceAtLeast(0)
                if (total == 0) {
                    LocaleController.getString(R.string.InuDeleteOwnMessagesSearching)
                } else {
                    LocaleController.formatString(
                        R.string.InuDeleteOwnMessagesFoundProgress, found, total
                    )
                }
            }
        }

        fun onAllSearchDone() {
            val total = primaryFoundIds.size + mergeFoundIds.size
            dismissDialog()
            if (total == 0) {
                BulletinFactory.of(activity)
                    .createSimpleBulletin(
                        R.raw.chats_infotip,
                        LocaleController.getString(R.string.InuDeleteOwnMessagesNone),
                    ).show()
                return
            }
            showConfirm(total)
        }

        fun showConfirm(total: Int) {
            val ctx = activity.parentActivity ?: run { cancel(); return }
            val theme = activity.resourceProvider
            val dlg = AlertDialog.Builder(ctx, theme)
                .setTitle(LocaleController.getString(R.string.InuDeleteOwnMessages))
                .setMessage(LocaleController.formatPluralString("InuDeleteOwnMessagesConfirm", total))
                .setPositiveButton(LocaleController.getString(R.string.Delete)) { _, _ -> startDelete() }
                .setNegativeButton(LocaleController.getString(R.string.Cancel)) { _, _ -> cancelled = true }
                .setOnCancelListener { cancelled = true }
                .create()
            activity.showDialog(dlg)
            (dlg.getButton(DialogInterface.BUTTON_POSITIVE) as? TextView)?.setTextColor(
                Theme.getColor(Theme.key_text_RedBold, theme)
            )
        }

        data class Batch(val dialogId: Long, val ids: ArrayList<Int>)

        fun startDelete() {
            val all = ArrayList<Batch>()
            chunkInto(all, primaryDialogId, primaryFoundIds)
            if (mergeDialogId != 0L) chunkInto(all, mergeDialogId, mergeFoundIds)
            batches = all
            batchIndex = 0
            deletedSoFar = 0
            phase = Phase.DELETE
            showProgressDialog(indeterminate = false)
            updateDeleteStatus()
            sendDeleteBatch()
        }

        fun chunkInto(out: ArrayList<Batch>, dialogId: Long, ids: Collection<Int>) {
            val list = ids.toList()
            var i = 0
            while (i < list.size) {
                val end = (i + BATCH_SIZE).coerceAtMost(list.size)
                out.add(Batch(dialogId, ArrayList(list.subList(i, end))))
                i = end
            }
        }

        fun sendDeleteBatch() {
            if (cancelled) return
            if (batchIndex >= batches.size) {
                onDeleteDone(); return
            }
            val batch = batches[batchIndex]
            val req: TLObject = if (
                batch.dialogId == primaryDialogId && ChatObject.isChannel(chat)
            ) {
                TLRPC.TL_channels_deleteMessages().apply {
                    channel = MessagesController.getInputChannel(chat)
                    id = batch.ids
                }
            } else {
                TLRPC.TL_messages_deleteMessages().apply {
                    revoke = true
                    id = batch.ids
                }
            }
            ConnectionsManager.getInstance(accountId).sendRequest(req) { response, error ->
                AndroidUtilities.runOnUIThread {
                    onDeleteResponse(batch, response, error)
                }
            }
        }

        fun onDeleteResponse(batch: Batch, response: TLObject?, error: TLRPC.TL_error?) {
            if (cancelled) return
            if (error != null) {
                val text = error.text.orEmpty()
                val floodSecs = parseFloodWait(text)
                if (floodSecs != null) {
                    Log.d(TAG, "delete FLOOD_WAIT dialog=${batch.dialogId} ${floodSecs}s ($text)")
                    handleFloodWait(floodSecs) { sendDeleteBatch() }
                    return
                }
                Log.e(TAG, "delete failed: code=${error.code} text=$text")
                onError(text)
                return
            }
            val topicForDelete = if (batch.dialogId == primaryDialogId) topMsgId else 0
            MessagesController.getInstance(accountId)
                .deleteMessages(batch.ids, null, null, batch.dialogId, topicForDelete, true, 0, true)
            deletedSoFar += batch.ids.size
            batchIndex++
            updateDeleteStatus()
            if (batchIndex < batches.size) {
                AndroidUtilities.runOnUIThread({ sendDeleteBatch() }, DELETE_BATCH_DELAY_MS)
            } else {
                onDeleteDone()
            }
        }

        fun updateDeleteStatus() {
            val total = primaryFoundIds.size + mergeFoundIds.size
            val pct = if (total == 0) 100 else (deletedSoFar * 100 / total)
            progressBar?.progress = pct
            if (inFloodWait()) return
            statusText?.text = LocaleController.formatString(
                R.string.InuDeleteOwnMessagesDeleting, deletedSoFar, total
            )
        }

        fun onDeleteDone() {
            dismissDialog()
            if (deletedSoFar == 0) return
            BulletinFactory.of(activity)
                .createSimpleBulletin(
                    R.raw.ic_delete,
                    LocaleController.formatPluralString("InuDeleteOwnMessagesDone", deletedSoFar),
                ).show()
        }

        fun showProgressDialog(indeterminate: Boolean) {
            dismissDialog()
            val ctx = activity.parentActivity ?: run { cancel(); return }
            val theme = activity.resourceProvider
            val text = TextView(ctx).apply {
                setTextColor(Theme.getColor(Theme.key_dialogTextBlack, theme))
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                gravity = if (LocaleController.isRTL) Gravity.RIGHT else Gravity.LEFT
            }
            val accent = ColorStateList.valueOf(Theme.getColor(Theme.key_dialogLineProgress, theme))
            val track = ColorStateList.valueOf(Theme.getColor(Theme.key_dialogLineProgressBackground, theme))
            val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = indeterminate
                progressTintList = accent
                indeterminateTintList = accent
                progressBackgroundTintList = track
                if (!indeterminate) {
                    max = 100
                    progress = 0
                }
            }
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    AndroidUtilities.dp(24f), AndroidUtilities.dp(8f),
                    AndroidUtilities.dp(24f), 0,
                )
                addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
                addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 12f, 0f, 0f))
            }
            val dlg = AlertDialog.Builder(ctx, theme)
                .setTitle(LocaleController.getString(R.string.InuDeleteOwnMessages))
                .setView(container)
                .setNegativeButton(LocaleController.getString(R.string.Cancel)) { _, _ -> cancel() }
                .setOnCancelListener { cancel() }
                .create()
            dlg.setCanceledOnTouchOutside(false)
            activity.showDialog(dlg)
            dialog = dlg
            statusText = text
            progressBar = bar
        }

        fun dismissDialog() {
            dialog?.dismiss()
            dialog = null
            statusText = null
            progressBar = null
        }

        fun onError(text: String?) {
            cancelled = true
            dismissDialog()
            BulletinFactory.of(activity).createErrorBulletin(
                LocaleController.formatString(R.string.InuDeleteOwnMessagesError, text ?: "?")
            ).show()
        }

        fun cancel() {
            cancelled = true
            dismissDialog()
        }

        fun parseFloodWait(text: String): Int? {
            val prefix = when {
                text.startsWith("FLOOD_WAIT_") -> "FLOOD_WAIT_"
                text.startsWith("FLOOD_PREMIUM_WAIT_") -> "FLOOD_PREMIUM_WAIT_"
                else -> return null
            }
            return text.removePrefix(prefix).toIntOrNull()?.coerceAtLeast(1)
        }

        fun inFloodWait(): Boolean = System.currentTimeMillis() < floodWaitUntilMs

        fun handleFloodWait(secs: Int, retry: () -> Unit) {
            if (cancelled) return
            val until = System.currentTimeMillis() + secs * 1000L
            if (until > floodWaitUntilMs) floodWaitUntilMs = until
            ensureFloodTicker()
            AndroidUtilities.runOnUIThread({
                if (cancelled) return@runOnUIThread
                retry()
            }, secs * 1000L)
        }

        fun ensureFloodTicker() {
            if (floodTickerRunning) return
            floodTickerRunning = true
            floodTick()
        }

        fun floodTick() {
            if (cancelled) {
                floodTickerRunning = false
                return
            }
            val now = System.currentTimeMillis()
            if (now >= floodWaitUntilMs) {
                floodTickerRunning = false
                refreshStatus()
                return
            }
            val remaining = ((floodWaitUntilMs - now + 999L) / 1000L).toInt().coerceAtLeast(1)
            statusText?.text = LocaleController.formatString(
                R.string.InuDeleteOwnMessagesFloodWait, remaining
            )
            AndroidUtilities.runOnUIThread({ floodTick() }, 1000L)
        }

        fun refreshStatus() {
            when (phase) {
                Phase.SEARCH -> updateSearchStatus()
                Phase.DELETE -> updateDeleteStatus()
            }
        }
    }
}
