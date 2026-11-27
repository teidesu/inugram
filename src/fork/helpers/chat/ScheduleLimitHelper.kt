package desu.inugram.helpers.chat

import android.text.TextUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.DialogsActivity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ScheduleLimitHelper {
    // server cap on one chat's schedule queue; stock only learns it from SCHEDULE_TOO_MUCH after the sends fail
    private const val LIMIT = 100
    private const val STORAGE_WAIT_MS = 500L

    @JvmStatic
    fun canSchedule(fragment: ChatActivity, text: CharSequence?): Boolean {
        var adding = if (TextUtils.isEmpty(text?.trim())) 0 else 1
        fragment.messagePreviewParams?.forwardMessages?.let {
            val selected = ArrayList<MessageObject>()
            it.getSelectedMessages(selected)
            adding += selected.size
        }
        if (adding == 0) return true
        return check(fragment, fragment.currentAccount, mapOf(fragment.dialogId to adding))
    }

    // sticker, gif and voice sends reach the forward preview without passing the text check
    @JvmStatic
    fun canScheduleForwards(fragment: ChatActivity, count: Int): Boolean =
        count == 0 || check(fragment, fragment.currentAccount, mapOf(fragment.dialogId to count))

    @JvmStatic
    fun canSchedule(picker: DialogsActivity, targets: List<MessagesStorage.TopicKey>, perTarget: Int): Boolean {
        if (perTarget == 0) return true
        val adding = targets.groupingBy { it.dialogId }.eachCount().mapValues { it.value * perTarget }
        if (check(picker, picker.currentAccount, adding)) return true
        // the picker's plain send button reuses these, so a refused schedule would come back on the next tap
        picker.scheduleDate = 0
        picker.scheduleRepeatPeriod = 0
        return false
    }

    private fun check(fragment: BaseFragment, account: Int, adding: Map<Long, Int>): Boolean {
        val queued = queuedCounts(account, adding.keys)
        val fits = adding.all { (dialogId, count) -> count + (queued[dialogId] ?: 0) <= LIMIT }
        if (!fits) {
            BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.MessageScheduledLimitReached)).show()
        }
        return fits
    }

    // the cache only knows chats whose schedule was loaded, so unknown chats count as empty and fall back to the server error
    private fun queuedCounts(account: Int, dialogIds: Collection<Long>): Map<Long, Int> {
        val storage = MessagesStorage.getInstance(account)
        val counts = ConcurrentHashMap<Long, Int>()
        val latch = CountDownLatch(1)
        storage.storageQueue.postRunnable {
            try {
                val cursor = storage.database.queryFinalized(
                    "SELECT uid, COUNT(mid) FROM scheduled_messages_v2 WHERE uid IN (${dialogIds.joinToString(",")}) " +
                        "AND (mid > 0 OR send_state = ${MessageObject.MESSAGE_SEND_STATE_SENDING}) GROUP BY uid",
                )
                try {
                    while (cursor.next()) counts[cursor.longValue(0)] = cursor.intValue(1)
                } finally {
                    cursor.dispose()
                }
            } catch (_: Exception) {
            } finally {
                latch.countDown()
            }
        }
        latch.await(STORAGE_WAIT_MS, TimeUnit.MILLISECONDS)
        return counts
    }
}
